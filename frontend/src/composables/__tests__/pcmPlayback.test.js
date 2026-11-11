import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPcmPlayback } from '@/composables/pcmPlayback'

/**
 * 无头环境没有 AudioContext，用最小替身记录「被调度的缓冲区」。
 * 这里要钉住两件事：
 *  1. PCM 分帧的**样本对齐**：帧边界可能把半个样本切开，客户端必须自己 carry；
 *  2. **统一队列**：clip（异步 fetch + decode）与 PCM 必须严格按入队顺序排期。
 */
class FakeBufferSource {
  constructor() {
    this.buffer = null
    this.startedAt = null
    this.onended = null
  }

  connect() {}

  start(at) {
    this.startedAt = at
    FakeAudioContext.started.push(this.buffer)
  }

  stop() {}
}

class FakeAudioContext {
  static started = []
  static instances = []

  constructor(opts = {}) {
    this.sampleRate = opts.sampleRate || 44100
    this.currentTime = 0
    this.state = 'running'
    this.destination = {}
    FakeAudioContext.instances.push(this)
  }

  createBuffer(channels, length, rate) {
    const data = new Float32Array(length)
    return {
      length,
      sampleRate: rate,
      duration: length / rate,
      getChannelData: () => data,
    }
  }

  createBufferSource() {
    return new FakeBufferSource()
  }

  /** 用「字节数 = 样本数」的假 buffer，方便断言先后顺序 */
  decodeAudioData(data) {
    return Promise.resolve({
      length: data.byteLength,
      sampleRate: 24000,
      duration: data.byteLength / 24000,
      getChannelData: () => new Float32Array(data.byteLength),
    })
  }

  close() {
    this.state = 'closed'
    return Promise.resolve()
  }

  resume() {
    return Promise.resolve()
  }
}

/** 5 字节 01 02 03 04 05 —— 故意是奇数长度，最后半个样本不完整 */
const ODD_A = 'AQIDBAU='
/** 5 字节 06 07 08 09 0A */
const ODD_B = 'BgcICQo='

const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

describe('createPcmPlayback 的 PCM 样本对齐', () => {
  beforeEach(() => {
    FakeAudioContext.started = []
    FakeAudioContext.instances = []
    vi.stubGlobal('window', { AudioContext: FakeAudioContext })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('奇数长度的帧不会让样本错位', async () => {
    const pb = createPcmPlayback({ sampleRate: 24000 })
    pb.enqueueBase64Pcm(ODD_A)
    pb.enqueueBase64Pcm(ODD_B)
    await flush()

    const [first, second] = FakeAudioContext.started
    // 第一帧 5 字节：只能出 2 个样本，剩 1 字节 carry
    expect(first.length).toBe(2)
    // 第二帧补上 carry 后 6 字节 → 3 个样本（不是各自 2 个后把半个样本丢掉）
    expect(second.length).toBe(3)

    // carry 生效的证据：第二帧第一个样本由 05 06 组成（低位 05、高位 06）
    const samples = second.getChannelData()
    expect(Math.round(samples[0] * 32768)).toBe(0x0605)
  })

  it('连续奇数帧不会累积错位', async () => {
    const pb = createPcmPlayback({ sampleRate: 24000 })
    // 连续喂 4 个奇数帧 = 20 字节 = 10 个样本
    for (let i = 0; i < 4; i++) pb.enqueueBase64Pcm(ODD_A)
    await flush()

    const total = FakeAudioContext.started.reduce((n, buf) => n + buf.length, 0)
    expect(total).toBe(10)
  })

  it('clear() 丢掉 carry，避免上一轮的半个字节串到下一轮', async () => {
    const pb = createPcmPlayback({ sampleRate: 24000 })
    pb.enqueueBase64Pcm(ODD_A) // 留下 1 字节 carry
    await flush()
    pb.clear()
    FakeAudioContext.started = []
    pb.enqueueBase64Pcm(ODD_B)
    await flush()

    expect(FakeAudioContext.started[0].length).toBe(2)
  })

  it('按帧声明的采样率建缓冲区', async () => {
    const pb = createPcmPlayback({ sampleRate: 24000 })
    pb.enqueueBase64Audio(ODD_A, { mime: 'audio/pcm', sampleRate: 48000 })
    await flush()
    expect(FakeAudioContext.started[0].sampleRate).toBe(48000)
  })
})

describe('createPcmPlayback 的统一队列（clip + PCM）', () => {
  beforeEach(() => {
    FakeAudioContext.started = []
    FakeAudioContext.instances = []
    vi.stubGlobal('window', { AudioContext: FakeAudioContext })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('clip 先入队时，后续 PCM 必须排在它之后', async () => {
    vi.stubGlobal('fetch', async () => ({
      ok: true,
      arrayBuffer: async () => new Uint8Array([9, 9, 9, 9]).buffer,
    }))
    const pb = createPcmPlayback({ sampleRate: 24000 })
    pb.enqueueUrl('clip.wav', { fallbackMs: 500 })
    // 还没 decode 完也不能当成「没有声音」：否则尾音门会提前 idle、麦克风被放行
    expect(pb.isPlaying()).toBe(true)
    pb.enqueueBase64Pcm('AQIDBA==')
    await flush()

    expect(FakeAudioContext.started.length).toBe(2)
    expect(FakeAudioContext.started[0].length).toBe(4) // clip（假 decode：字节数=样本数）
    expect(FakeAudioContext.started[1].length).toBe(2) // 之后的 PCM
  })

  it('clip 缺失时插等长静音，后续音频照常播', async () => {
    vi.stubGlobal('fetch', async () => ({ ok: false, status: 404 }))
    const pb = createPcmPlayback({ sampleRate: 24000 })
    pb.enqueueUrl('missing.wav', { fallbackMs: 100 })
    pb.enqueueBase64Pcm('AQIDBA==')
    await flush()

    expect(FakeAudioContext.started.length).toBe(2)
    expect(FakeAudioContext.started[0].length).toBe(2400) // 100ms @ 24kHz
    expect(FakeAudioContext.started[1].length).toBe(2)
  })

  it('clear() 让旧链里未执行的排期失效', async () => {
    vi.stubGlobal('fetch', async () => ({
      ok: true,
      arrayBuffer: async () => new Uint8Array([1, 2]).buffer,
    }))
    const pb = createPcmPlayback({ sampleRate: 24000 })
    pb.enqueueUrl('clip.wav')
    pb.clear()
    await flush()

    expect(FakeAudioContext.started.length).toBe(0)
  })
})

describe('createPcmPlayback 基本契约', () => {
  it('暴露 enqueue/clear 且无音频时 isPlaying 为 false', () => {
    vi.stubGlobal('window', { AudioContext: FakeAudioContext })
    const pb = createPcmPlayback({ sampleRate: 24000 })
    expect(typeof pb.enqueueBase64Pcm).toBe('function')
    expect(typeof pb.enqueueBase64Audio).toBe('function')
    expect(typeof pb.enqueueUrl).toBe('function')
    expect(typeof pb.enqueuePcmBytes).toBe('function')
    expect(typeof pb.clear).toBe('function')
    pb.clear()
    expect(pb.isPlaying()).toBe(false)
    vi.unstubAllGlobals()
  })
})
