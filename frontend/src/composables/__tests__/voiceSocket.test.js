import { describe, expect, it } from 'vitest'
import { shouldReuseVoiceSocket, WS_OPEN } from '../voiceSocket.js'

describe('shouldReuseVoiceSocket', () => {
  it('只在 OPEN 时复用', () => {
    expect(shouldReuseVoiceSocket({ readyState: WS_OPEN })).toBe(true)
  })

  it('CONNECTING 不得复用（线上回归：残留半开 socket 导致静默假连接）', () => {
    // 0 = CONNECTING。旧实现把它当作「已在连接」直接 return，
    // 于是麦克风开了、PCM 帧被丢光、服务端收不到任何 /ws/voice 请求。
    expect(shouldReuseVoiceSocket({ readyState: 0 })).toBe(false)
  })

  it('CLOSING / CLOSED 不得复用', () => {
    expect(shouldReuseVoiceSocket({ readyState: 2 })).toBe(false)
    expect(shouldReuseVoiceSocket({ readyState: 3 })).toBe(false)
  })

  it('没有 socket 时不复用', () => {
    expect(shouldReuseVoiceSocket(null)).toBe(false)
    expect(shouldReuseVoiceSocket(undefined)).toBe(false)
  })
})
