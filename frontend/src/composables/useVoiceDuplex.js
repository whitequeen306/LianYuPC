import { ref } from 'vue'
import { buildVoiceWsUrl } from '@/utils/runtime'
import { syncToken } from '@/utils/secureToken'
import { usePcmCapture } from '@/composables/usePcmCapture'
import { createPcmPlayback } from '@/composables/pcmPlayback'

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
      phase.value = 'thinking'
    } else if (type === 'llm.delta') {
      phase.value = 'thinking'
    } else if (type === 'tts.audio') {
      phase.value = 'speaking'
      if (!playback) playback = createPcmPlayback({ sampleRate: msg.sampleRate || 24000 })
      playback.enqueueBase64Audio(msg.base64, {
        mime: msg.mime || 'audio/pcm',
        sampleRate: msg.sampleRate || 24000,
      })
      noteSpeakingStarted()
    } else if (type === 'tts.local') {
      phase.value = 'speaking'
      void playLocalTts(msg)
    } else if (type === 'tts.done' || type === 'turn.done') {
      if (type === 'turn.done') {
        phase.value = 'idle'
        resetBargeState()
      }
    } else if (type === 'turn.cancelled') {
      playback?.clear()
      phase.value = 'idle'
      resetBargeState()
    } else if (type === 'error') {
      lastError.value = String(msg.message || '语音会话错误')
    }
    onEvent?.(msg)
  }

  async function connect() {
    if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) {
      return
    }
    const token = syncToken()
    if (!token) {
      throw new Error('未登录')
    }
    const url = `${buildVoiceWsUrl()}?token=${encodeURIComponent(token)}`
    await new Promise((resolve, reject) => {
      const socket = new WebSocket(url)
      ws = socket
      socket.binaryType = 'arraybuffer'
      const timer = setTimeout(() => {
        reject(new Error('语音通道连接超时'))
        try { socket.close() } catch { /* ignore */ }
      }, 12000)
      socket.onopen = () => {
        clearTimeout(timer)
        connected.value = true
        resolve()
      }
      socket.onerror = () => {
        clearTimeout(timer)
        reject(new Error('语音通道连接失败'))
      }
      socket.onclose = () => {
        connected.value = false
      }
      socket.onmessage = (ev) => {
        if (typeof ev.data === 'string') {
          handleServerMessage(ev.data)
        }
      }
    })
  }

  async function startDictation({ onEvent: cb, vadProfile = 'loose' } = {}) {
    mode.value = 'dictation'
    onEvent = cb || null
    lastError.value = ''
    partialText.value = ''
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
    phase.value = 'idle'
    playback = createPcmPlayback({ sampleRate: 24000 })
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
  }

  async function stop() {
    sendJson({ type: 'session.end' })
    await stopPcmStream()
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
