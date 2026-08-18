import { describe, it, expect } from 'vitest'
import {
  parseHostLine,
  encodeHostCommand,
  extractInboundPayload,
  shouldSkipWechatProactive,
  pickLatestAssistant,
  HOST_MSG,
  HOST_CMD,
} from '../wechatProtocol.js'

describe('parseHostLine / encodeHostCommand', () => {
  it('parses a typed JSON line and ignores junk', () => {
    expect(parseHostLine('{"type":"inbound","text":"hi"}')).toEqual({ type: 'inbound', text: 'hi' })
    expect(parseHostLine('')).toBeNull()
    expect(parseHostLine('not-json')).toBeNull()
    expect(parseHostLine('{"text":"no type"}')).toBeNull()
  })

  it('encodes a command as one JSON line', () => {
    expect(encodeHostCommand({ type: HOST_CMD.SEND_TEXT, text: 'ok' })).toBe(
      '{"type":"send_text","text":"ok"}\n',
    )
    expect(encodeHostCommand(null)).toBe('')
  })
})

describe('extractInboundPayload', () => {
  it('accepts text-only inbound', () => {
    expect(extractInboundPayload({
      type: HOST_MSG.INBOUND,
      text: '  hello  ',
      fromUserId: 'wx1',
      contextToken: 'tok',
    })).toMatchObject({ text: 'hello', fromUserId: 'wx1', contextToken: 'tok', imageBase64: '' })
  })

  it('accepts image plus caption', () => {
    const got = extractInboundPayload({
      type: HOST_MSG.INBOUND,
      text: 'see this',
      imageBase64: 'abc',
      mime: 'image/png',
      fromUserId: 'wx1',
    })
    expect(got.imageBase64).toBe('abc')
    expect(got.mime).toBe('image/png')
  })

  it('drops empty inbound and non-inbound types', () => {
    expect(extractInboundPayload({ type: HOST_MSG.INBOUND })).toBeNull()
    expect(extractInboundPayload({ type: HOST_MSG.QR, text: 'x' })).toBeNull()
  })
})

describe('shouldSkipWechatProactive', () => {
  it('skips enter.wav room-enter voice and keeps other audio', () => {
    expect(shouldSkipWechatProactive({ audioUrl: 'https://cdn/voice/enter.wav' })).toBe(true)
    expect(shouldSkipWechatProactive({ audioUrl: 'C:\\media\\enter.wav' })).toBe(true)
    expect(shouldSkipWechatProactive({ audioUrl: '/static/enter.wav' })).toBe(true)
    expect(shouldSkipWechatProactive({ audioUrl: 'https://cdn/voice/noon.wav' })).toBe(false)
    expect(shouldSkipWechatProactive({ audioUrl: '' })).toBe(false)
    expect(shouldSkipWechatProactive({})).toBe(false)
  })
})

describe('pickLatestAssistant', () => {
  it('returns the highest-seq assistant and ignores users', () => {
    const latest = pickLatestAssistant([
      { role: 'USER', seq: 9, content: 'me' },
      { role: 'ASSISTANT', seq: 3, content: 'old' },
      { role: 'assistant', seq: 8, content: 'new' },
    ])
    expect(latest.content).toBe('new')
  })

  it('returns null for empty input', () => {
    expect(pickLatestAssistant([])).toBeNull()
    expect(pickLatestAssistant(null)).toBeNull()
  })
})
