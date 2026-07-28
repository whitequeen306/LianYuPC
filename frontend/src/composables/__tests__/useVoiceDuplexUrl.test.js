import { describe, expect, it } from 'vitest'

function buildVoiceWsUrlFromStomp(stompUrl) {
  return `${String(stompUrl || '').replace(/\/+$/, '')}/voice`
}

describe('voice duplex url helper', () => {
  it('appends /voice under the STOMP /ws endpoint', () => {
    expect(buildVoiceWsUrlFromStomp('wss://example.com/ws')).toBe('wss://example.com/ws/voice')
    expect(buildVoiceWsUrlFromStomp('ws://localhost:8080/ws')).toBe('ws://localhost:8080/ws/voice')
  })
})
