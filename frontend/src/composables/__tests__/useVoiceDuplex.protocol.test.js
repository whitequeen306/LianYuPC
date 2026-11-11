import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * 语音双工协议（P0）的客户端契约：
 *  · session.start 声明能力，call.state 是打断门控的唯一依据；
 *  · vad 边沿上报；
 *  · 打断先发 barge_in{preRollMs}，再按序回灌 pre-roll 帧；
 *  · 本地播放排空后上报 play.drained。
 */

const hoisted = vi.hoisted(() => {
  const capture = { onFrame: null, onSpeechEdge: null, onEndpoint: null }
  const playback = {
    enqueued: [],
    urls: [],
    pcm: [],
    cleared: 0,
    playing: false,
    enqueueBase64Audio(base64, opts = {}) {
      this.enqueued.push({ base64, ...opts })
    },
    enqueueUrl(url, opts = {}) {
      this.urls.push({ url, ...opts })
    },
    enqueuePcmBytes(bytes, rate) {
      this.pcm.push({ bytes, rate })
    },
    clear() {
      this.cleared += 1
      this.playing = false
    },
    isPlaying() {
      return this.playing
    },
  }
  const sockets = []
  return { capture, playback, sockets }
})

vi.mock('@/composables/usePcmCapture', () => ({
  usePcmCapture: () => ({
    recording: { value: false },
    audioLevel: { value: 0 },
    speaking: { value: false },
    startPcmStream: async (opts = {}) => {
      hoisted.capture.onFrame = opts.onFrame
      hoisted.capture.onSpeechEdge = opts.onSpeechEdge
      hoisted.capture.onEndpoint = opts.onEndpoint
    },
    stopPcmStream: async () => {},
  }),
}))

vi.mock('@/composables/pcmPlayback', () => ({
  createPcmPlayback: () => hoisted.playback,
}))

vi.mock('@/utils/runtime', () => ({
  buildVoiceWsUrl: () => 'ws://test/ws/voice',
  ensureApiOriginReady: async () => {},
}))

vi.mock('@/utils/secureToken', () => ({ syncToken: () => 'test-token' }))
vi.mock('@/utils/logger', () => ({
  info: () => {},
  warn: () => {},
  debug: () => {},
  error: () => {},
}))

class FakeWebSocket {
  static OPEN = 1
  static CONNECTING = 0
  static CLOSING = 2
  static CLOSED = 3

  constructor(url) {
    this.url = url
    this.readyState = FakeWebSocket.CONNECTING
    this.sent = []
    hoisted.sockets.push(this)
  }

  send(data) {
    this.sent.push(data)
  }

  close() {
    this.readyState = FakeWebSocket.CLOSED
    this.onclose?.({})
  }

  open() {
    this.readyState = FakeWebSocket.OPEN
    this.onopen?.({})
  }

  emit(msg) {
    this.onmessage?.({ data: JSON.stringify(msg) })
  }

  jsonSent() {
    return this.sent.filter((x) => typeof x === 'string').map((x) => JSON.parse(x))
  }
}

async function until(fn, timeoutMs = 2000) {
  const startedAt = Date.now()
  while (Date.now() - startedAt < timeoutMs) {
    const value = fn()
    if (value) return value
    await new Promise((resolve) => setTimeout(resolve, 5))
  }
  throw new Error('等待条件超时')
}

async function startCall() {
  const { useVoiceDuplex } = await import('../useVoiceDuplex.js')
  const duplex = useVoiceDuplex()
  const started = duplex.startCall({ conversationId: 42 })
  const ws = await until(() => hoisted.sockets[hoisted.sockets.length - 1])
  ws.open()
  ws.emit({ type: 'ready' })
  await started
  return { duplex, ws }
}

function pcmFrame() {
  return new Int16Array([100, -100, 200, -200]).buffer
}

describe('useVoiceDuplex protocol', () => {
  beforeEach(() => {
    vi.resetModules()
    hoisted.sockets.length = 0
    hoisted.capture.onFrame = null
    hoisted.capture.onSpeechEdge = null
    hoisted.playback.cleared = 0
    hoisted.playback.playing = false
    hoisted.playback.enqueued.length = 0
    hoisted.playback.urls.length = 0
    hoisted.playback.pcm.length = 0
    globalThis.WebSocket = FakeWebSocket
  })

  afterEach(() => {
    delete globalThis.WebSocket
  })

  it('session.start 声明能力，call.state 驱动 phase 且丢弃乱序帧', async () => {
    const { duplex, ws } = await startCall()

    const start = ws.jsonSent().find((m) => m.type === 'session.start')
    expect(start.mode).toBe('call')
    expect(start.conversationId).toBe(42)
    expect(start.caps).toEqual(expect.arrayContaining(['call.state', 'barge.preroll']))

    ws.emit({ type: 'call.state', seq: 2, state: 'SPEAKING' })
    expect(duplex.phase.value).toBe('speaking')

    // 旧 seq 不允许把状态拉回去（网络乱序）
    ws.emit({ type: 'call.state', seq: 1, state: 'LISTENING' })
    expect(duplex.phase.value).toBe('speaking')

    ws.emit({ type: 'call.state', seq: 3, state: 'USER_SPEAKING' })
    expect(duplex.phase.value).toBe('listening')
  })

  it('vad 边沿与端点照常上报', async () => {
    const { ws } = await startCall()

    hoisted.capture.onSpeechEdge({ phase: 'start' })
    hoisted.capture.onSpeechEdge({ phase: 'end' })
    hoisted.capture.onEndpoint()

    const types = ws.jsonSent().map((m) => `${m.type}:${m.phase || ''}`)
    expect(types).toEqual(expect.arrayContaining(['vad:start', 'vad:end', 'endpoint:']))
  })

  it('打断先发 barge_in，再按序回灌 pre-roll 帧', async () => {
    const { ws } = await startCall()
    ws.emit({ type: 'call.state', seq: 1, state: 'SPEAKING' })
    hoisted.capture.onSpeechEdge({ phase: 'start' })
    expect(hoisted.capture.onFrame).toBeTypeOf('function')

    const before = ws.sent.length
    for (let i = 0; i < 4; i++) {
      hoisted.capture.onFrame(pcmFrame(), { peak: 0.5 })
    }

    const tail = ws.sent.slice(before)
    const bargeIndex = tail.findIndex(
      (x) => typeof x === 'string' && JSON.parse(x).type === 'barge_in',
    )
    expect(bargeIndex).toBeGreaterThanOrEqual(0)
    expect(JSON.parse(tail[bargeIndex]).preRollMs).toBe(1500)
    // barge_in 之后：先回灌 3 帧历史语音，再发触发的这一帧（实时），共 4 帧二进制
    const binary = tail.slice(bargeIndex + 1).filter((x) => typeof x !== 'string')
    expect(binary.length).toBe(4)
    expect(tail.slice(bargeIndex + 1).some((x) => typeof x === 'string')).toBe(false)
  })

  it('播放排空后上报 play.drained', async () => {
    const { ws } = await startCall()
    ws.emit({ type: 'call.state', seq: 1, state: 'SPEAKING' })
    ws.emit({ type: 'tts.audio', seq: 11, base64: '', mime: 'audio/pcm', sampleRate: 24000 })

    hoisted.playback.playing = false
    ws.emit({ type: 'turn.done' })

    const drained = ws.jsonSent().filter((m) => m.type === 'play.drained')
    expect(drained.length).toBe(1)
    expect(drained[0].seq).toBe(11)
  })

  it('tts.clip 走统一播放队列并带 fallbackMs', async () => {
    const { ws } = await startCall()
    ws.emit({ type: 'tts.clip', petId: 'ganyu', kind: 'laugh', fallbackMs: 900, seq: 5 })

    const clip = hoisted.playback.urls.at(-1)
    expect(clip.url).toContain('pet/voice/ganyu/laugh.wav')
    expect(clip.fallbackMs).toBe(900)
  })

  it('二进制音频帧按头解析后入队 PCM', async () => {
    const { ws } = await startCall()
    const frame = new ArrayBuffer(16 + 4)
    const view = new DataView(frame)
    view.setUint8(0, 0x4c)
    view.setUint8(1, 0x59)
    view.setUint8(2, 1)
    view.setUint8(3, 1)
    view.setUint8(5, 16)
    view.setUint32(8, 42)
    view.setUint32(12, 24000)
    new Uint8Array(frame, 16).set([1, 2, 3, 4])

    ws.onmessage?.({ data: frame })

    const pcm = hoisted.playback.pcm.at(-1)
    expect(pcm.rate).toBe(24000)
    expect(Array.from(pcm.bytes)).toEqual([1, 2, 3, 4])
  })
})
