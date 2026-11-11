/**
 * Low-latency audio playback queue.
 * Supports raw PCM16 LE and encoded formats (mp3/wav) via decodeAudioData.
 *
 * 所有入队都走**同一条链**：语气词 clip 需要先 fetch + decodeAudioData 才能排期，
 * 若不排队，紧跟其后的 PCM 帧会先被 schedule —— 听感就是「语气词被台词盖掉」。
 * clear() 用代数隔离：清空后，旧链里尚未执行的排期一律作废。
 */
export function createPcmPlayback({ sampleRate = 24000, gainEnabled = false, duckDb = -12 } = {}) {
  let ctx = null
  let nextTime = 0
  let playing = false
  /** 响度节点（默认关）：开着时所有排期都经它落到 destination，便于统一 duck。 */
  let gainNode = null
  let ducked = false
  /** @type {Promise<void>} */
  let chain = Promise.resolve()
  /** 清空代数：clear() 后旧任务全部失效。 */
  let gen = 0
  /**
   * 已入队但尚未排期的任务数（clip 要 fetch+decode，PCM 走微任务）。
   * isPlaying() 必须把它算进去，否则「刚 enqueue 还没排期」会被当成已播完，
   * 尾音门会提前 idle、麦克风在角色还在出声时被放行。
   */
  let pendingTasks = 0
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

  function silenceBuffer(ms) {
    const audioCtx = ensureCtx()
    const length = Math.max(1, Math.floor((audioCtx.sampleRate * ms) / 1000))
    return audioCtx.createBuffer(1, length, audioCtx.sampleRate)
  }

  /**
   * 响度归一 / ducking 的落点：音量统一从 GainNode 走，避免每帧改 BufferSource。
   * 只有显式开启时才会创建节点（老浏览器/测试环境没有 createGain 也不受影响）。
   */
  function ensureGain(audioCtx) {
    if (!gainEnabled) return null
    if (!gainNode) {
      gainNode = audioCtx.createGain()
      gainNode.gain.value = 1
      gainNode.connect(audioCtx.destination)
    }
    return gainNode
  }

  function applyGain() {
    if (!gainNode) return
    gainNode.gain.value = ducked ? Math.pow(10, duckDb / 20) : 1
  }

  function schedule(buf) {
    const audioCtx = ensureCtx()
    const src = audioCtx.createBufferSource()
    src.buffer = buf
    const gain = ensureGain(audioCtx)
    if (gain) {
      applyGain()
      src.connect(gain)
    } else {
      src.connect(audioCtx.destination)
    }
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

  /** 用户开口时把角色音量压下去（默认关，需 gainEnabled）。 */
  function setDucked(on) {
    ducked = !!on
    applyGain()
  }

  /** 把一次排期放进同一条链；clear() 之后旧任务直接丢弃。 */
  function enqueueTask(task) {
    const myGen = gen
    pendingTasks += 1
    chain = chain
      .then(async () => {
        if (myGen !== gen) return
        await task()
      })
      .catch(() => {
        // 单个片段失败不阻断后续音频
      })
      .finally(() => {
        if (myGen === gen) pendingTasks = Math.max(0, pendingTasks - 1)
      })
  }

  function base64ToBytes(base64) {
    const raw = atob(base64)
    const bytes = new Uint8Array(raw.length)
    for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i)
    return bytes
  }

  function enqueueBytes(bytes, rate = sampleRate) {
    if (!bytes || bytes.length === 0) return
    let merged = bytes
    if (pcmCarry && pcmCarry.length) {
      merged = new Uint8Array(pcmCarry.length + bytes.length)
      merged.set(pcmCarry, 0)
      merged.set(bytes, pcmCarry.length)
    }
    const evenLen = merged.length - (merged.length % 2)
    pcmCarry = evenLen < merged.length ? merged.subarray(evenLen) : null
    if (evenLen === 0) return
    const buf = int16ToAudioBuffer(merged.subarray(0, evenLen), rate)
    enqueueTask(() => schedule(buf))
  }

  function enqueueBase64Pcm(base64, rate = sampleRate) {
    if (!base64) return
    enqueueBytes(base64ToBytes(base64), rate)
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
    enqueueTask(async () => {
      const audioCtx = ensureCtx()
      if (audioCtx.state === 'suspended') {
        await audioCtx.resume().catch(() => {})
      }
      const buf = await audioCtx.decodeAudioData(copy)
      schedule(buf)
    })
  }

  /**
   * 预录语气词 / 任意音频文件：与 PCM 走同一条队列，顺序严格按入队次序。
   * 文件缺失或解码失败时按 fallbackMs 插等长静音 —— 绝不阻断后续音频。
   */
  function enqueueUrl(url, { fallbackMs = 0 } = {}) {
    if (!url) {
      if (fallbackMs > 0) {
        const buf = silenceBuffer(fallbackMs)
        enqueueTask(() => schedule(buf))
      }
      return
    }
    enqueueTask(async () => {
      try {
        const resp = await fetch(url)
        if (!resp || !resp.ok) throw new Error(`clip_http_${resp ? resp.status : 0}`)
        const data = await resp.arrayBuffer()
        const audioCtx = ensureCtx()
        if (audioCtx.state === 'suspended') {
          await audioCtx.resume().catch(() => {})
        }
        const buf = await audioCtx.decodeAudioData(data)
        schedule(buf)
      } catch {
        if (fallbackMs > 0) {
          schedule(silenceBuffer(fallbackMs))
        }
      }
    })
  }

  function clear() {
    gen += 1
    chain = Promise.resolve()
    pendingTasks = 0
    pcmCarry = null
    gainNode = null
    if (ctx) {
      const old = ctx
      ctx = null
      nextTime = 0
      playing = false
      void old.close().catch(() => {})
    }
  }

  function isPlaying() {
    return playing || pendingTasks > 0 || (ctx != null && ctx.currentTime < nextTime)
  }

  return {
    enqueueBase64Pcm,
    enqueuePcmBytes: enqueueBytes,
    enqueueBase64Audio,
    enqueueUrl,
    setDucked,
    clear,
    isPlaying,
  }
}
