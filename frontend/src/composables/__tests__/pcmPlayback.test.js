import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPcmPlayback } from '@/composables/pcmPlayback'

/**
 * 无头环境没有 AudioContext，用最小替身记录「被调度的缓冲区」。
 * 这里要钉住的不变量是 PCM 分帧的**样本对齐**：
 * 帧边界可能把半个样本切开，客户端必须自己 carry 住那半个字节。
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

describe('createPcmPlayback 的 PCM 样本对齐', () => {
  beforeEach(() => {
    FakeAudioContext.started = []
    FakeAudioContext.instances = []
    vi.stubGlobal('window', { AudioContext: FakeAudioContext })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('奇数长度的帧不会让样本错位', () => {
    const pb = createPcmPlayback({ sampleRate: 24000 })
    pb.enqueueBase64Pcm(ODD_A)
    pb.enqueueBase64Pcm(ODD_B)

    const [first, second] = FakeAudioContext.started
    // 第一帧 5 字节：只能出 2 个样本，剩 1 字节 carry
    expect(first.length).toBe(2)
    // 第二帧补上 carry 后 6 字节 → 3 个样本（不是各自 2 个后把半个样本丢掉）
    expect(second.length).toBe(3)

    // carry 生效的证据：第二帧第一个样本由 05 06 组成（低位 05、高位 06）
    const samples = second.getChannelData()
    expect(Math.round(samples[0] * 32768)).toBe(0x0605)
  })

  it('连续奇数帧不会累积错位', () => {
    const pb = createPcmPlayback({ sampleRate: 24000 })
    // 连续喂 4 个奇数帧 = 20 字节 = 10 个样本
    for (let i = 0; i < 4; i++) pb.enqueueBase64Pcm(ODD_A)

    const total = FakeAudioContext.started.reduce((n, buf) => n + buf.length, 0)
    expect(total).toBe(10)
  })

  it('clear() 丢掉 carry，避免上一轮的半个字节串到下一轮', () => {
    const pb = createPcmPlayback({ sampleRate: 24000 })
    pb.enqueueBase64Pcm(ODD_A) // 留下 1 字节 carry
    pb.clear()
    FakeAudioContext.started = []
    pb.enqueueBase64Pcm(ODD_B)

    expect(FakeAudioContext.started[0].length).toBe(2)
  })

  it('按帧声明的采样率建缓冲区', () => {
    const pb = createPcmPlayback({ sampleRate: 24000 })
    pb.enqueueBase64Audio(ODD_A, { mime: 'audio/pcm', sampleRate: 48000 })
    expect(FakeAudioContext.started[0].sampleRate).toBe(48000)
  })
})

describe('createPcmPlayback 基本契约', () => {
  it('暴露 enqueue/clear 且无音频时 isPlaying 为 false', () => {
    vi.stubGlobal('window', { AudioContext: FakeAudioContext })
    const pb = createPcmPlayback({ sampleRate: 24000 })
    expect(typeof pb.enqueueBase64Pcm).toBe('function')
    expect(typeof pb.enqueueBase64Audio).toBe('function')
    expect(typeof pb.clear).toBe('function')
    pb.clear()
    expect(pb.isPlaying()).toBe(false)
    vi.unstubAllGlobals()
  })
})
