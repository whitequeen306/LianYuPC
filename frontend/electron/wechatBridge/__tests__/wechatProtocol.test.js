import { describe, it, expect } from 'vitest'
import {
  parseHostLine,
  encodeHostCommand,
  extractInboundPayload,
  shouldSkipWechatProactive,
  pickLatestAssistant,
  buildWeixinSendMessage,
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

describe('buildWeixinSendMessage', () => {
  it('assigns a unique client_id and the official bot fields', () => {
    const a = buildWeixinSendMessage({ text: 'hi', toUserId: 'wx1', contextToken: 'tok' })
    const b = buildWeixinSendMessage({ text: 'hi', toUserId: 'wx1', contextToken: 'tok' })
    expect(a.msg.from_user_id).toBe('')
    expect(a.msg.to_user_id).toBe('wx1')
    expect(a.msg.message_type).toBe(2)
    expect(a.msg.message_state).toBe(2)
    expect(a.msg.context_token).toBe('tok')
    expect(a.msg.item_list).toEqual([{ type: 1, text_item: { text: 'hi' } }])
    expect(a.msg.client_id).toMatch(/^lianyu-[0-9a-f]{16}$/)
    expect(a.msg.client_id).not.toBe(b.msg.client_id)
  })

  it('rejects empty text or missing peer context', () => {
    expect(() => buildWeixinSendMessage({ text: ' ', toUserId: 'wx1', contextToken: 'tok' })).toThrow('empty text')
    expect(() => buildWeixinSendMessage({ text: 'hi', toUserId: '', contextToken: 'tok' })).toThrow('missing context')
    expect(() => buildWeixinSendMessage({ text: 'hi', toUserId: 'wx1', contextToken: '' })).toThrow('missing context')
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
