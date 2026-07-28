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

  function sendJson(obj) {
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify(obj))
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
      playback.enqueueBase64Pcm(msg.base64, msg.sampleRate || 24000)
      bargeArmed = true
    } else if (type === 'tts.done' || type === 'turn.done') {
      if (type === 'turn.done') {
        phase.value = 'idle'
        bargeArmed = false
      }
    } else if (type === 'turn.cancelled') {
      playback?.clear()
      phase.value = 'idle'
      bargeArmed = false
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
        // Barge-in while character is speaking.
        if (bargeArmed && phase.value === 'speaking' && (meta?.peak || 0) > 0.16) {
          bargeArmed = false
          playback?.clear()
          sendJson({ type: 'barge_in' })
          phase.value = 'listening'
        }
      },
      onEndpoint: () => sendJson({ type: 'endpoint' }),
    })
  }

  async function stop() {
    sendJson({ type: 'session.end' })
    await stopPcmStream()
    playback?.clear()
    playback = null
    bargeArmed = false
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
    bargeArmed = false
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
