import { ref } from 'vue'
import { buildVoiceWsUrl, ensureApiOriginReady } from '@/utils/runtime'
import { syncToken } from '@/utils/secureToken'
import { usePcmCapture } from '@/composables/usePcmCapture'
import { createPcmPlayback } from '@/composables/pcmPlayback'
import { createTurnTailGate } from '@/composables/voiceTurnTail'
import { shouldReuseVoiceSocket } from '@/composables/voiceSocket'
import * as logger from '@/utils/logger'

/** pre-roll 环形缓冲：只留最近 1.5s，打断时按序回灌给 ASR。 */
const PREROLL_MS = 1500
/** 40ms 一帧 / 16kHz / s16le：1.5s 约 48KB；上限只防异常长帧把内存撑爆。 */
const PREROLL_MAX_BYTES = 96 * 1024
/** 回灌只取「像人声」的帧：把静音/底噪灌进 ASR 只会拖慢首条 partial。 */
const PREROLL_PEAK_MIN = 0.12

/**
 * Duplex voice session over /ws/voice.
 */
export function useVoiceDuplex() {
  const connected = ref(false)
  const mode = ref('')
  const partialText = ref('')
  const phase = ref('idle')
  const lastError = ref('')
  /** 服务端权威状态镜像（call.state）。空串 = 服务端没开协议，退回旧事件推导。 */
  const callState = ref('')

  const {
    recording,
    audioLevel,
    speaking,
    startPcmStream,
    stopPcmStream,
  } = usePcmCapture()

  let ws = null
  let playback = null
  /** @type {((ev: any) => void) | null} */
  let onEvent = null
  let bargeArmed = false
  /** ms after first TTS chunk before barge-in is allowed (echo guard) */
  let bargeGraceUntil = 0
  let bargeHotFrames = 0
  const BARGE_GRACE_MS = 900
  const BARGE_PEAK_THRESHOLD = 0.28
  const BARGE_HOT_FRAMES_REQUIRED = 4

  /** call.state 序号：只接受更新的帧，乱序/重复直接丢。 */
  let stateSeq = -1
  /** 最近一帧 tts.audio 的 seq，play.drained 时回传给服务端对账。 */
  let lastAudioSeq = 0

  /** @type {{ pcm: ArrayBuffer, peak: number, at: number }[]} */
  let preRollFrames = []
  let preRollBytes = 0
  /** 本段话的起点（performance.now）；回灌从这个时刻之后取。 */
  let speechStartedAt = 0
  /** 打断时刻：用来量「打断后多久出第一条 partial」。 */
  let bargeAtMs = 0
  let turnStartedPerf = 0
  let firstAudioReported = false

  /**
   * turn.done 只代表服务端不再产音频；本地还压着 2-5 秒尾音。
   * 这段时间必须保持 speaking，否则 maybeBargeIn 的 phase 判断会直接返回，
   * 用户的打断在「角色还在说话」时失效。
   */
  const turnTail = createTurnTailGate({
    isPlaying: () => playback?.isPlaying() === true,
    onIdle: () => {
      resetBargeState()
      phase.value = 'idle'
      // 本地真播完了：服务端状态机据此把 SPEAKING 收回 LISTENING 并放行麦克风。
      // 不发这一帧的话，麦克风要等服务端按字节估算排空才恢复。
      sendJson({ type: 'play.drained', seq: lastAudioSeq })
    },
  })

  function resetBargeState() {
    bargeArmed = false
    bargeGraceUntil = 0
    bargeHotFrames = 0
  }

  function noteSpeakingStarted() {
    bargeArmed = true
    bargeGraceUntil = Date.now() + BARGE_GRACE_MS
    bargeHotFrames = 0
  }

  function resetPreRoll() {
    preRollFrames = []
    preRollBytes = 0
    speechStartedAt = 0
  }

  function pushPreRoll(pcm, peak) {
    if (!pcm) return
    const now = performance.now()
    preRollFrames.push({ pcm, peak: peak || 0, at: now })
    preRollBytes += pcm.byteLength || 0
    const cutoff = now - PREROLL_MS
    while (
      preRollFrames.length > 0
      && (preRollFrames[0].at < cutoff || preRollBytes > PREROLL_MAX_BYTES)
    ) {
      preRollBytes -= preRollFrames[0].pcm.byteLength || 0
      preRollFrames.shift()
    }
  }

  /**
   * 把打断前那半句补回给 ASR。
   *
   * 必须紧跟在 barge_in 之后、实时帧之前发（同一 WS 保序）：服务端这时状态已切到
   * LISTENING，回灌帧会被正常送去识别；不发的话「打断前说的 1 秒」永远进不了 ASR。
   */
  function flushPreRoll() {
    const frames = preRollFrames
    const from = speechStartedAt || (performance.now() - PREROLL_MS)
    resetPreRoll()
    if (!ws || ws.readyState !== WebSocket.OPEN) return 0
    let sent = 0
    for (const f of frames) {
      if (f.at < from) continue
      if (f.peak < PREROLL_PEAK_MIN) continue
      ws.send(f.pcm)
      sent += 1
    }
    return sent
  }

  /**
   * 打断门控只认服务端状态机（call.state）：THINKING / SPEAKING 都能打断。
   * 服务端没开协议时退回旧逻辑（只在朗读期、且已 arm 时才允许）。
   */
  function maybeBargeIn(meta) {
    const st = callState.value
    let allowed
    if (st) {
      allowed = st === 'SPEAKING' || st === 'THINKING'
      // 回声保护只针对朗读期：THINKING 没有外放，不存在回声
      if (allowed && st === 'SPEAKING' && Date.now() < bargeGraceUntil) return
    } else {
      allowed = bargeArmed && phase.value === 'speaking'
      if (allowed && Date.now() < bargeGraceUntil) return
    }
    if (!allowed) return
    const peak = meta?.peak || 0
    if (peak <= BARGE_PEAK_THRESHOLD) {
      bargeHotFrames = 0
      return
    }
    bargeHotFrames += 1
    if (bargeHotFrames < BARGE_HOT_FRAMES_REQUIRED) return
    triggerBarge()
  }

  function triggerBarge() {
    resetBargeState()
    turnTail.stop()
    playback?.clear()
    bargeAtMs = performance.now()
    sendJson({ type: 'barge_in', preRollMs: PREROLL_MS })
    const backfilled = flushPreRoll()
    phase.value = 'listening'
    if (callState.value) callState.value = 'LISTENING'
    logger.info('voice', `barge_in preRollFrames=${backfilled}`)
  }

  function sendJson(obj) {
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify(obj))
    }
  }

  function applyStatePhase(st) {
    if (st === 'SPEAKING' || st === 'BACKCHANNEL') {
      phase.value = 'speaking'
    } else if (st === 'THINKING') {
      phase.value = 'thinking'
    } else if (st === 'LISTENING' || st === 'USER_SPEAKING' || st === 'INTERRUPTED') {
      phase.value = 'listening'
    } else if (st === 'CLOSED') {
      phase.value = 'idle'
    }
  }

  async function playLocalTts(msg) {
    const api = typeof window !== 'undefined' ? window.electronAPI : null
    if (!api?.synthesizeLocalTts) {
      lastError.value = '本地语音仅支持桌面客户端'
      return
    }
    try {
      const result = await api.synthesizeLocalTts({
        endpoint: msg.endpoint,
        text: msg.text,
        refAudioUrl: msg.refAudioUrl,
        refText: msg.refText,
      })
      if (!result?.ok || !result.base64) {
        lastError.value = '本地语音合成失败，请检查 GPT-SoVITS 是否已启动'
        return
      }
      if (!playback) playback = createPcmPlayback({ sampleRate: 24000 })
      playback.enqueueBase64Audio(result.base64, {
        mime: result.mime || 'audio/wav',
        sampleRate: 24000,
      })
      noteSpeakingStarted()
      if (mode.value === 'call') {
        // 本地合成是异步的：tts.done 可能先到并把 phase 复位，这里补挂一次排空监视，
        // 否则服务端永远等不到 play.drained（麦克风一直关着）。
        phase.value = 'speaking'
        turnTail.stop()
        turnTail.settle()
      }
    } catch (e) {
      lastError.value = '本地语音合成失败'
    }
  }

  function handleServerMessage(raw) {
    let msg
    try {
      msg = JSON.parse(raw)
    } catch {
      return
    }
    const type = msg.type
    if (type === 'call.state') {
      const seq = Number(msg.seq ?? 0)
      if (seq >= stateSeq) {
        stateSeq = seq
        callState.value = String(msg.state || '')
        applyStatePhase(callState.value)
      }
    } else if (type === 'asr.partial') {
      partialText.value = String(msg.text || '')
      if (mode.value === 'call') {
        phase.value = 'listening'
        if (bargeAtMs && partialText.value) {
          const cost = Math.round(performance.now() - bargeAtMs)
          bargeAtMs = 0
          sendJson({ type: 'call.metrics', bargeToPartialMs: cost })
        }
      }
    } else if (type === 'asr.final') {
      partialText.value = String(msg.text || '')
    } else if (type === 'turn.start') {
      // 新回合开始：上一回合的尾音排空监视必须撤销，否则它会在新回合中把状态压回 idle。
      turnTail.stop()
      phase.value = 'thinking'
      turnStartedPerf = performance.now()
      firstAudioReported = false
    } else if (type === 'llm.delta') {
      phase.value = 'thinking'
    } else if (type === 'tts.audio') {
      turnTail.stop()
      phase.value = 'speaking'
      const seq = Number(msg.seq ?? 0)
      if (seq) lastAudioSeq = seq
      if (!playback) playback = createPcmPlayback({ sampleRate: msg.sampleRate || 24000 })
      playback.enqueueBase64Audio(msg.base64, {
        mime: msg.mime || 'audio/pcm',
        sampleRate: msg.sampleRate || 24000,
      })
      noteSpeakingStarted()
      if (!firstAudioReported && turnStartedPerf) {
        firstAudioReported = true
        sendJson({ type: 'call.metrics', firstAudioMs: Math.round(performance.now() - turnStartedPerf) })
      }
    } else if (type === 'tts.local') {
      turnTail.stop()
      phase.value = 'speaking'
      void playLocalTts(msg)
    } else if (type === 'turn.done') {
      // 服务端说完了，但本地尾音可能还在播：等排空后再 idle，期间保持可打断。
      turnTail.settle()
    } else if (type === 'turn.cancelled') {
      turnTail.stop()
      playback?.clear()
      phase.value = 'idle'
      resetBargeState()
    } else if (type === 'error') {
      lastError.value = String(msg.message || '语音会话错误')
    }
    onEvent?.(msg)
  }

  async function connect() {
    // 只复用真正 OPEN 的连接。CONNECTING/CLOSING 一律重建，否则握手失败的
    // 残留 socket 会让后续每次「开始通话」静默跳过握手：麦克风开着，
    // PCM 帧因 readyState 不是 OPEN 被丢弃，服务端没有任何记录（见 voiceSocket.js）。
    if (shouldReuseVoiceSocket(ws)) {
      return
    }
    if (ws) {
      try { ws.close() } catch { /* ignore */ }
      ws = null
    }
    const token = syncToken()
    if (!token) {
      throw new Error('未登录')
    }
    // 首帧认证：URL 不再携带 token（避免进入服务器访问日志），
    // 连接建立后先发 {"type":"auth","token":...}，等 ready 才算就绪。
    const url = buildVoiceWsUrl()
    logger.info('voice', `connecting ${url}`)
    await new Promise((resolve, reject) => {
      const socket = new WebSocket(url)
      ws = socket
      socket.binaryType = 'arraybuffer'
      let settled = false
      /** @type {ReturnType<typeof setTimeout> | undefined} */
      let timer
      const fail = (err) => {
        if (settled) return
        settled = true
        if (timer) clearTimeout(timer)
        // 失败必须把引用清掉：否则下一次连接会命中复用判定，永久静默失败。
        if (ws === socket) ws = null
        connected.value = false
        logger.warn('voice', `connect failed: ${err?.message || err} url=${url}`)
        try { socket.close() } catch { /* ignore */ }
        reject(err)
      }
      /**
       * 握手期任何非 ready 的收尾都要拒绝，不能只等 12s 超时：
       * 服务端在 /ws/voice 上可能直接 close（未授权/过载），旧实现会 hang 住，
       * 表现为「不报错、也不说话」，用户只能手动挂断。
       */
      timer = setTimeout(() => fail(new Error('语音通道连接超时')), 12000)
      socket.onopen = () => {
        try {
          socket.send(JSON.stringify({ type: 'auth', token }))
        } catch (e) {
          fail(e)
        }
      }
      socket.onerror = () => fail(new Error('语音通道连接失败'))
      socket.onclose = () => {
        connected.value = false
        fail(new Error('语音通道连接被关闭'))
      }
      socket.onmessage = (ev) => {
        if (typeof ev.data !== 'string') {
          return
        }
        try {
          const msg = JSON.parse(ev.data)
          if (msg?.type === 'ready') {
            settled = true
            if (timer) clearTimeout(timer)
            connected.value = true
            logger.info('voice', 'connected (ready)')
            resolve()
            return
          }
          if (msg?.type === 'error' && !connected.value) {
            fail(new Error(String(msg.message || '语音通道认证失败')))
            return
          }
        } catch { /* 非 JSON 帧，交给统一处理 */ }
        handleServerMessage(ev.data)
      }
    })
  }

  async function startDictation({ onEvent: cb, vadProfile = 'loose' } = {}) {
    mode.value = 'dictation'
    onEvent = cb || null
    lastError.value = ''
    partialText.value = ''
    // 语音 URL 由渲染进程自己拼，必须等主进程下发 apiOrigin，
    // 否则会退回 127.0.0.1:8080 兜底（表现为握手一直挂起）。
    await ensureApiOriginReady()
    await connect()
    sendJson({ type: 'session.start', mode: 'dictation' })
    await startPcmStream({
      vadProfile,
      onFrame: (pcm) => {
        if (ws?.readyState === WebSocket.OPEN) ws.send(pcm)
      },
      onEndpoint: () => sendJson({ type: 'endpoint' }),
    })
  }

  async function startCall({ conversationId, onEvent: cb, vadProfile = 'strict' } = {}) {
    mode.value = 'call'
    onEvent = cb || null
    lastError.value = ''
    partialText.value = ''
    turnTail.stop()
    phase.value = 'idle'
    callState.value = ''
    stateSeq = -1
    lastAudioSeq = 0
    bargeAtMs = 0
    turnStartedPerf = 0
    firstAudioReported = false
    resetPreRoll()
    playback = createPcmPlayback({ sampleRate: 24000 })
    // 同 dictation：先确保拿到了真实 apiOrigin 再拼 wss 地址。
    await ensureApiOriginReady()
    await connect()
    // caps 是能力协商：服务端据此决定是否回 call.state / 接受 pre-roll 打断。
    // 老服务端会忽略该字段，行为退回旧协议。
    sendJson({
      type: 'session.start',
      mode: 'call',
      conversationId,
      caps: ['call.state', 'barge.preroll', 'vad.edge'],
    })
    await startPcmStream({
      vadProfile,
      onFrame: (pcm, meta) => {
        // 先判定打断：命中时 flushPreRoll 回灌历史帧，当前帧随后按实时帧发，不会重复
        maybeBargeIn(meta)
        pushPreRoll(pcm, meta?.peak || 0)
        if (ws?.readyState === WebSocket.OPEN) ws.send(pcm)
      },
      onSpeechEdge: ({ phase: edge }) => {
        if (edge === 'start') {
          speechStartedAt = performance.now()
        }
        sendJson({ type: 'vad', phase: edge })
      },
      onEndpoint: () => sendJson({ type: 'endpoint' }),
    })
    logger.info('voice', 'call pcm stream started')
  }

  async function stop() {
    sendJson({ type: 'session.end' })
    await stopPcmStream()
    turnTail.stop()
    playback?.clear()
    playback = null
    resetBargeState()
    resetPreRoll()
    phase.value = 'idle'
    callState.value = ''
    stateSeq = -1
    partialText.value = ''
    mode.value = ''
    if (ws) {
      try { ws.close() } catch { /* ignore */ }
      ws = null
    }
    connected.value = false
    onEvent = null
  }

  function bargeIn() {
    triggerBarge()
  }

  return {
    connected,
    mode,
    partialText,
    phase,
    callState,
    lastError,
    recording,
    audioLevel,
    speaking,
    startDictation,
    startCall,
    stop,
    bargeIn,
  }
}
