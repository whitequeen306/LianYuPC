import { ref } from 'vue'
import { buildVoiceWsUrl, ensureApiOriginReady } from '@/utils/runtime'
import { syncToken } from '@/utils/secureToken'
import { usePcmCapture } from '@/composables/usePcmCapture'
import { createPcmPlayback } from '@/composables/pcmPlayback'
import { createTurnTailGate } from '@/composables/voiceTurnTail'
import { shouldReuseVoiceSocket } from '@/composables/voiceSocket'
import * as logger from '@/utils/logger'

/**
 * Duplex voice session over /ws/voice.
 */
export function useVoiceDuplex() {
  const connected = ref(false)
  const mode = ref('')
  const partialText = ref('')
  const phase = ref('idle')
  const lastError = ref('')

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

  function maybeBargeIn(meta) {
    if (!bargeArmed || phase.value !== 'speaking') return
    if (Date.now() < bargeGraceUntil) return
    const peak = meta?.peak || 0
    if (peak <= BARGE_PEAK_THRESHOLD) {
      bargeHotFrames = 0
      return
    }
    bargeHotFrames += 1
    if (bargeHotFrames < BARGE_HOT_FRAMES_REQUIRED) return
    resetBargeState()
    turnTail.stop()
    playback?.clear()
    sendJson({ type: 'barge_in' })
    phase.value = 'listening'
  }

  function sendJson(obj) {
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify(obj))
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
    if (type === 'asr.partial') {
      partialText.value = String(msg.text || '')
      if (mode.value === 'call') phase.value = 'listening'
    } else if (type === 'asr.final') {
      partialText.value = String(msg.text || '')
    } else if (type === 'turn.start') {
      // 新回合开始：上一回合的尾音排空监视必须撤销，否则它会在新回合中把状态压回 idle。
      turnTail.stop()
      phase.value = 'thinking'
    } else if (type === 'llm.delta') {
      phase.value = 'thinking'
    } else if (type === 'tts.audio') {
      turnTail.stop()
      phase.value = 'speaking'
      if (!playback) playback = createPcmPlayback({ sampleRate: msg.sampleRate || 24000 })
      playback.enqueueBase64Audio(msg.base64, {
        mime: msg.mime || 'audio/pcm',
        sampleRate: msg.sampleRate || 24000,
      })
      noteSpeakingStarted()
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
    playback = createPcmPlayback({ sampleRate: 24000 })
    // 同 dictation：先确保拿到了真实 apiOrigin 再拼 wss 地址。
    await ensureApiOriginReady()
    await connect()
    sendJson({ type: 'session.start', mode: 'call', conversationId })
    await startPcmStream({
      vadProfile,
      onFrame: (pcm, meta) => {
        if (ws?.readyState === WebSocket.OPEN) ws.send(pcm)
        maybeBargeIn(meta)
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
    phase.value = 'idle'
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
    turnTail.stop()
    playback?.clear()
    sendJson({ type: 'barge_in' })
    phase.value = 'listening'
    resetBargeState()
  }

  return {
    connected,
    mode,
    partialText,
    phase,
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
