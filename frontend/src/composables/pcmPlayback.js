/**
 * Low-latency audio playback queue.
 * Supports raw PCM16 LE and encoded formats (mp3/wav) via decodeAudioData.
 */
export function createPcmPlayback({ sampleRate = 24000 } = {}) {
  let ctx = null
  let nextTime = 0
  let playing = false
  /** @type {Promise<void>} */
  let chain = Promise.resolve()
  /**
   * 上一帧多出来的半字节（0 或 1 个字节）。
   *
   * PCM16 是两字节一个样本，而「一帧」的边界由服务端的分帧决定，历史上服务端是按
   * HTTP/DataBuffer 的任意边界切的 —— 于是帧可能从样本中间开始。若每帧独立
   * `new Int16Array(...)`，偏移错一个字节就等于把整帧高低字节对调，听感是电子杂音。
   * 服务端已改为样本对齐分帧；这里保留 carry 作为第二道防线，让老服务端也不会炸。
   */
  let pcmCarry = null

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

  function base64ToBytes(base64) {
    const raw = atob(base64)
    const bytes = new Uint8Array(raw.length)
    for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i)
    return bytes
  }

  function enqueueBase64Pcm(base64, rate = sampleRate) {
    if (!base64) return
    let bytes = base64ToBytes(base64)
    if (pcmCarry && pcmCarry.length) {
      const merged = new Uint8Array(pcmCarry.length + bytes.length)
      merged.set(pcmCarry, 0)
      merged.set(bytes, pcmCarry.length)
      bytes = merged
    }
    const evenLen = bytes.length - (bytes.length % 2)
    pcmCarry = evenLen < bytes.length ? bytes.subarray(evenLen) : null
    if (evenLen === 0) return
    const buf = int16ToAudioBuffer(bytes.subarray(0, evenLen), rate)
    schedule(buf)
  }

  /**
   * @param {string} base64
   * @param {{ mime?: string, sampleRate?: number }} [opts]
   */
  function enqueueBase64Audio(base64, opts = {}) {
    if (!base64) return
    const mime = String(opts.mime || 'audio/pcm').toLowerCase()
    const rate = opts.sampleRate || sampleRate
    if (mime.includes('pcm') || mime === 'audio/l16') {
      enqueueBase64Pcm(base64, rate)
      return
    }
    const bytes = base64ToBytes(base64)
    const copy = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength)
    chain = chain
      .then(async () => {
        const audioCtx = ensureCtx()
        if (audioCtx.state === 'suspended') {
          await audioCtx.resume().catch(() => {})
        }
        const buf = await audioCtx.decodeAudioData(copy)
        schedule(buf)
      })
      .catch(() => {
        // ignore decode failures for a single chunk
      })
  }

  function clear() {
    chain = Promise.resolve()
    pcmCarry = null
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

  return { enqueueBase64Pcm, enqueueBase64Audio, clear, isPlaying }
}
