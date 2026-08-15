import { describe, expect, it, vi } from 'vitest'
import {
  defaultChatExportFileName,
  fetchAllConversationMessages,
  formatChatTranscript,
} from '../exportChatTranscript'

describe('defaultChatExportFileName', () => {
  it('uses the requested Chinese default', () => {
    expect(defaultChatExportFileName('甘雨')).toBe('与甘雨的聊天记录--恋语.txt')
  })

  it('falls back without a character name', () => {
    expect(defaultChatExportFileName('')).toBe('聊天记录--恋语.txt')
  })
})

describe('formatChatTranscript', () => {
  it('formats user and character lines as {role}：content', () => {
    const text = formatChatTranscript([
      { role: 'USER', content: '你好', seq: 1 },
      { role: 'ASSISTANT', content: '嗯，我在听。', seq: 2 },
    ], { userName: '小明', characterName: '甘雨' })
    expect(text).toBe('{小明}：你好\n{甘雨}：嗯，我在听。')
  })

  it('skips system/tool/voice-call rows and empty bodies', () => {
    const text = formatChatTranscript([
      { role: 'system', content: 'ignore' },
      { role: 'assistant', audioUrl: 'system/voice-call-turn', content: 'turn' },
      { role: 'assistant', audioUrl: 'system/voice-call-summary', content: '通话结束' },
      { role: 'user', content: '   ' },
      { role: 'user', content: '还在吗' },
    ], { userName: '用户', characterName: '雷电将军' })
    expect(text).toBe('{用户}：还在吗')
  })

  it('strips inner thoughts unless opted in', () => {
    const msg = { role: 'assistant', content: '你好（轻轻点头）再见' }
    expect(formatChatTranscript([msg], { characterName: '甘雨' })).toBe('{甘雨}：你好再见')
    expect(formatChatTranscript([msg], { characterName: '甘雨', includeInnerThoughts: true }))
      .toBe('{甘雨}：你好（轻轻点头）再见')
  })

  it('uses a picture placeholder for image-only user messages', () => {
    const text = formatChatTranscript([
      { role: 'user', content: '（用户发送了一张图片）', imageUrl: '/x.png' },
    ], { userName: '用户', characterName: '甘雨' })
    expect(text).toBe('{用户}：（图片）')
  })
})

describe('fetchAllConversationMessages', () => {
  it('walks older pages and returns chronological unique rows', async () => {
    const getMessages = vi.fn()
      .mockResolvedValueOnce({
        records: [{ id: 2, seq: 2, role: 'user', content: 'b' }],
        hasMore: true,
        nextBeforeSeq: 2,
      })
      .mockResolvedValueOnce({
        records: [{ id: 1, seq: 1, role: 'assistant', content: 'a' }],
        hasMore: false,
        nextBeforeSeq: null,
      })
    const rows = await fetchAllConversationMessages(9, getMessages)
    expect(getMessages).toHaveBeenNthCalledWith(1, 9, { limit: 200 })
    expect(getMessages).toHaveBeenNthCalledWith(2, 9, { limit: 200, beforeSeq: 2 })
    expect(rows.map((m) => m.id)).toEqual([1, 2])
  })
})
