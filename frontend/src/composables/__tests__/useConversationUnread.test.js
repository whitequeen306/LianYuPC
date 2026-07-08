import { beforeEach, describe, expect, it } from 'vitest'
import { useConversationUnread } from '@/composables/useConversationUnread'

describe('useConversationUnread', () => {
  beforeEach(() => {
    const unread = useConversationUnread()
    unread.conversationModeById.value = {}
    unread.unreadByCharacterId.value = {}
    unread.unreadByGroupId.value = {}
  })

  it('counts MESSAGE and PROACTIVE_MESSAGE for character cards', () => {
    const unread = useConversationUnread()
    unread.ingestConversations([
      { id: 1, mode: 'SINGLE' },
      { id: 2, mode: 'GROUP' },
    ])

    unread.ingestUnreadNotifications([
      { read: false, type: 'MESSAGE', conversationId: 1, characterId: 101 },
      { read: false, type: 'PROACTIVE_MESSAGE', conversationId: 1, characterId: 101 },
      { read: false, type: 'GROUP_MESSAGE', conversationId: 2, characterId: 202 },
    ])

    expect(unread.unreadCountForCharacter(101)).toBe(2)
    expect(unread.unreadCountForGroup(2)).toBe(1)
  })
})
