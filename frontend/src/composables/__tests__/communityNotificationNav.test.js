import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/router', () => ({
  default: {
    push: vi.fn(),
    replace: vi.fn()
  }
}))

vi.mock('@/api/conversation', () => ({
  createConversation: vi.fn()
}))

vi.mock('@/stores/notifications', () => ({
  useNotificationsStore: () => ({
    markNotificationsByIds: vi.fn(),
    markConversationRead: vi.fn()
  })
}))

vi.mock('@/stores/conversations', () => ({
  useConversationsStore: () => ({
    fetchList: vi.fn(async () => [])
  })
}))

describe('community notification deep links', () => {
  beforeEach(() => {
    vi.resetModules()
  })

  it('routes community like/comment/post to community page', async () => {
    const { buildNotificationHash } = await import('@/composables/useNotificationNavigation')
    expect(buildNotificationHash({ type: 'COMMUNITY_LIKE', conversationId: 12 }))
      .toBe('#/app/community')
    expect(buildNotificationHash({ type: 'COMMUNITY_COMMENT', conversationId: 12 }))
      .toBe('#/app/community')
    expect(buildNotificationHash({ type: 'COMMUNITY_POST_NEW', conversationId: 12 }))
      .toBe('#/app/community')
  })
})
