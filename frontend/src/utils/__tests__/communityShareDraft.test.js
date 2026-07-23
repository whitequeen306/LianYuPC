import { describe, expect, it } from 'vitest'
import {
  COMMUNITY_SHARE_MAX_CONTENT,
  formatChatMessagesForCommunityShare,
  isShareSelectableMessage
} from '@/utils/communityShareDraft'

describe('communityShareDraft', () => {
  it('detects share-selectable timeline items', () => {
    expect(isShareSelectableMessage({ type: 'message', id: 12 })).toBe(true)
    expect(isShareSelectableMessage({ type: 'message', id: 12, _streamGroupId: 's1' })).toBe(false)
    expect(isShareSelectableMessage({ type: 'time' })).toBe(false)
  })

  it('formats selected chat messages with labels and image placeholder', () => {
    const content = formatChatMessagesForCommunityShare([
      { id: 1, role: 'user', content: '你好' },
      { id: 2, role: 'assistant', content: '（内心：有点开心）嗨～' },
      { id: 3, role: 'user', imageUrl: '/chat/a.png', content: '（用户发送了一张图片）' }
    ], {
      characterName: '艾丽西亚',
      userLabel: '我'
    })

    expect(content).toContain('我：你好')
    expect(content).toContain('艾丽西亚：')
    expect(content).toContain('我：[图片]')
    expect(content).not.toContain('内心')
  })

  it('truncates content to community limit', () => {
    const content = formatChatMessagesForCommunityShare([
      { id: 1, role: 'user', content: 'x'.repeat(COMMUNITY_SHARE_MAX_CONTENT + 50) }
    ])
    expect(content.length).toBe(COMMUNITY_SHARE_MAX_CONTENT)
  })
})
