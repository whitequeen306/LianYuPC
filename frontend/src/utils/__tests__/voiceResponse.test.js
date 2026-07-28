import { describe, it, expect } from 'vitest'
import { pickAsrText, pickVoiceCallPayload, typewriteText } from '@/utils/voiceResponse'

describe('voiceResponse', () => {
  it('pickAsrText accepts unwrapped and nested shapes', () => {
    expect(pickAsrText({ text: '你好' })).toBe('你好')
    expect(pickAsrText({ data: { text: '世界' } })).toBe('世界')
    expect(pickAsrText('直接字符串')).toBe('直接字符串')
    expect(pickAsrText(null)).toBe('')
  })

  it('pickVoiceCallPayload accepts unwrapped and nested shapes', () => {
    expect(pickVoiceCallPayload({ userText: '我', replyText: '嗯' }).replyText).toBe('嗯')
    expect(pickVoiceCallPayload({ data: { userText: '我', replyText: '嗯' } }).userText).toBe('我')
  })

  it('typewriteText reveals incrementally', async () => {
    const parts = []
    await typewriteText('abc', {
      charDelayMs: 1,
      onUpdate: (s) => parts.push(s),
    })
    expect(parts.at(-1)).toBe('abc')
    expect(parts.length).toBeGreaterThanOrEqual(3)
  })
})
