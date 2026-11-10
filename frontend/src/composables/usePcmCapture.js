import { ref } from 'vue'

const VAD_PROFILES = {
  off: null,
  loose: { speechLevel: 0.045, silenceMs: 650, minSpeechMs: 220 },
  strict: { speechLevel: 0.1, silenceMs: 750, minSpeechMs: 280 },
}

/**
 * 16 kHz mono PCM capture via AudioWorklet.
 */
export function usePcmCapture() {
  const recording = ref(false)
  const audioLevel = ref(0)
  const speaking = ref(false)

  let mediaStream = null
  let audioCtx = null
  let source = null
  let worklet = null
  let active = false
  let session = 0
  let speechStartedAt = 0
  let lastSpeechAt = 0
  let silenceArmed = false
  /** 上一帧是否在说话：用来产出 vad 的 start/end 边沿（每段话各一次，不是每帧）。 */
  let wasSpeaking = false
  let vad = VAD_PROFILES.loose
  /** @type {((pcm: ArrayBuffer, meta: { peak: number }) => void) | null} */
  let onFrame = null
  /** @type {(() => void) | null} */
  let onEndpoint = null
  /** @type {((edge: { phase: 'start' | 'end' }) => void) | null} */
  let onSpeechEdge = null

  function resolveVad(name) {
    if (name === false || name === 'off') return null
    if (name === 'strict') return VAD_PROFILES.strict
    if (name === 'loose') return VAD_PROFILES.loose
    return VAD_PROFILES.loose
  }

  async function ensureStream() {
    if (mediaStream) return
    mediaStream = await navigator.mediaDevices.getUserMedia({
      audio: {
        echoCancellation: true,
        noiseSuppression: true,
        channelCount: 1,
      },
      video: false,
    })
  }

  async function startPcmStream({
    vadProfile = 'loose',
    onFrame: frameCb,
    onEndpoint: endpointCb,
    onSpeechEdge: edgeCb,
  } = {}) {
    if (active) return
    active = true
    const sid = ++session
    vad = resolveVad(vadProfile)
    onFrame = typeof frameCb === 'function' ? frameCb : null
    onEndpoint = typeof endpointCb === 'function' ? endpointCb : null
    onSpeechEdge = typeof edgeCb === 'function' ? edgeCb : null
    speechStartedAt = 0
    lastSpeechAt = 0
    silenceArmed = false
    wasSpeaking = false

    await ensureStream()
    const Ctx = window.AudioContext || window.webkitAudioContext
    audioCtx = new Ctx()
    if (audioCtx.state === 'suspended') {
      await audioCtx.resume()
    }
    const workletUrl = `${import.meta.env.BASE_URL || '/'}worklets/pcm-capture-processor.js`
    await audioCtx.audioWorklet.addModule(workletUrl)
    source = audioCtx.createMediaStreamSource(mediaStream)
    worklet = new AudioWorkletNode(audioCtx, 'pcm-capture-processor')
    worklet.port.onmessage = (ev) => {
      if (!active || sid !== session) return
      const { pcm, peak } = ev.data || {}
      const level = typeof peak === 'number' ? peak : 0
      audioLevel.value = level
      const threshold = vad?.speechLevel ?? 0.08
      const now = performance.now()
      if (level >= threshold) {
        speaking.value = true
        // vad.start：一段话只发一次，服务端据此进入 USER_SPEAKING（思考期插话依赖它）
        if (vad && !wasSpeaking) {
          wasSpeaking = true
          onSpeechEdge?.({ phase: 'start' })
        }
        if (!speechStartedAt) speechStartedAt = now
        lastSpeechAt = now
        silenceArmed = true
      } else {
        speaking.value = false
        // vad.end：静音满 silenceMs 才算这段说完（不是一帧安静就发）
        if (vad && wasSpeaking && (now - lastSpeechAt) >= (vad.silenceMs || 650)) {
          wasSpeaking = false
          onSpeechEdge?.({ phase: 'end' })
        }
        if (
          silenceArmed
          && speechStartedAt
          && vad
          && (now - speechStartedAt) >= (vad.minSpeechMs || 200)
          && (now - lastSpeechAt) >= (vad.silenceMs || 650)
        ) {
          silenceArmed = false
          speechStartedAt = 0
          onEndpoint?.()
        }
      }
      if (pcm && onFrame) {
        onFrame(pcm, { peak: level })
      }
    }
    source.connect(worklet)
    // Keep graph alive without audible monitor.
    const mute = audioCtx.createGain()
    mute.gain.value = 0
    worklet.connect(mute)
    mute.connect(audioCtx.destination)
    recording.value = true
  }

  async function stopPcmStream() {
    active = false
    session += 1
    recording.value = false
    speaking.value = false
    audioLevel.value = 0
    wasSpeaking = false
    onFrame = null
    onEndpoint = null
    onSpeechEdge = null
    try {
      worklet?.port && (worklet.port.onmessage = null)
      worklet?.disconnect()
    } catch { /* ignore */ }
    worklet = null
    try {
      source?.disconnect()
    } catch { /* ignore */ }
    source = null
    if (audioCtx) {
      const ctx = audioCtx
      audioCtx = null
      void ctx.close().catch(() => {})
    }
    if (mediaStream) {
      mediaStream.getTracks().forEach((t) => t.stop())
      mediaStream = null
    }
  }

  return {
    recording,
    audioLevel,
    speaking,
    startPcmStream,
    stopPcmStream,
  }
}
