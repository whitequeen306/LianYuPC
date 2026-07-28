/**
 * AudioWorklet: downsample to 16 kHz mono int16 PCM frames (~40ms).
 */
class PcmCaptureProcessor extends AudioWorkletProcessor {
  constructor() {
    super()
    this._ratio = sampleRate / 16000
    this._acc = []
    this._frameSamples = Math.max(320, Math.floor(16000 * 0.04)) // 40ms
  }

  process(inputs) {
    const input = inputs[0]?.[0]
    if (!input || input.length === 0) {
      return true
    }
    // Simple decimation / linear resample toward 16 kHz.
    if (Math.abs(this._ratio - 1) < 0.001) {
      for (let i = 0; i < input.length; i++) {
        this._acc.push(input[i])
      }
    } else {
      const outLen = Math.floor(input.length / this._ratio)
      for (let i = 0; i < outLen; i++) {
        const src = i * this._ratio
        const i0 = Math.floor(src)
        const i1 = Math.min(i0 + 1, input.length - 1)
        const t = src - i0
        this._acc.push(input[i0] * (1 - t) + input[i1] * t)
      }
    }
    while (this._acc.length >= this._frameSamples) {
      const slice = this._acc.splice(0, this._frameSamples)
      const pcm = new Int16Array(slice.length)
      let peak = 0
      for (let i = 0; i < slice.length; i++) {
        let s = slice[i]
        if (s > 1) s = 1
        if (s < -1) s = -1
        const v = (s * 32767) | 0
        pcm[i] = v
        const a = Math.abs(s)
        if (a > peak) peak = a
      }
      this.port.postMessage({ pcm: pcm.buffer, peak }, [pcm.buffer])
    }
    return true
  }
}

registerProcessor('pcm-capture-processor', PcmCaptureProcessor)
