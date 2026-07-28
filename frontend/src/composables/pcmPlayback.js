/**
 * Low-latency PCM playback queue (24 kHz mono int16 by default).
 */
export function createPcmPlayback({ sampleRate = 24000 } = {}) {
  let ctx = null
  let nextTime = 0
  let playing = false
  const queue = []

  function ensureCtx() {
    if (ctx) return ctx
    const Ctx = window.AudioContext || window.webkitAudioContext
    ctx = new Ctx({ sampleRate })
    nextTime = ctx.currentTime
    return ctx
  }

  function int16ToAudioBuffer(pcmBytes, rate) {
    const samples = new Int16Array(
      pcmBytes.buffer,
      pcmBytes.byteOffset,
      Math.floor(pcmBytes.byteLength / 2),
    )
    const audioCtx = ensureCtx()
    const buf = audioCtx.createBuffer(1, samples.length, rate)
    const ch = buf.getChannelData(0)
    for (let i = 0; i < samples.length; i++) {
      ch[i] = samples[i] / 32768
    }
    return buf
  }

  function schedule(buf) {
    const audioCtx = ensureCtx()
    const src = audioCtx.createBufferSource()
    src.buffer = buf
    src.connect(audioCtx.destination)
    const startAt = Math.max(audioCtx.currentTime + 0.02, nextTime)
    src.start(startAt)
    nextTime = startAt + buf.duration
    playing = true
    src.onended = () => {
      if (audioCtx.currentTime >= nextTime - 0.05) {
        playing = false
      }
    }
  }

  function enqueueBase64Pcm(base64, rate = sampleRate) {
    if (!base64) return
    const raw = atob(base64)
    const bytes = new Uint8Array(raw.length)
    for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i)
    const buf = int16ToAudioBuffer(bytes, rate)
    schedule(buf)
  }

  function clear() {
    queue.length = 0
    if (ctx) {
      const old = ctx
      ctx = null
      nextTime = 0
      playing = false
      void old.close().catch(() => {})
    }
  }

  function isPlaying() {
    return playing || (ctx != null && ctx.currentTime < nextTime)
  }

  return { enqueueBase64Pcm, clear, isPlaying }
}
