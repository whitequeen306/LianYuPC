import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

const getConversationMock = vi.fn(async () => null)

vi.mock('@/api/conversation', () => ({
  listConversations: vi.fn(async () => []),
  getConversation: getConversationMock,
  createGroupConversation: vi.fn(async () => null),
  deleteConversation: vi.fn(async () => null),
  updateGroupTitle: vi.fn(async () => null),
}))

describe('conversations store realtime summary patching', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    getConversationMock.mockReset()
  })

  it('updates lastMessage immediately for a user turn and moves the conversation to the top', async () => {
    const { useConversationsStore } = await import('@/stores/conversations')
    const store = useConversationsStore()
    store.list = [
      { id: 2, mode: 'SINGLE', characterId: 20, lastMessage: '旧摘要', lastCharacterMessage: '旧角色', updatedAt: '2026-07-08T10:00:00.000Z' },
      { id: 1, mode: 'SINGLE', characterId: 10, lastMessage: '更旧摘要', lastCharacterMessage: '更旧角色', updatedAt: '2026-07-08T09:00:00.000Z' },
    ]

    store.patchRealtimeSummary(1, {
      lastMessage: '我刚发的话',
      updatedAt: '2026-07-08T12:00:00.000Z',
    })

    expect(store.list[0]).toMatchObject({
      id: 1,
      lastMessage: '我刚发的话',
      lastCharacterMessage: '更旧角色',
    })
  })

  it('updates both lastMessage and lastCharacterMessage for an assistant reply', async () => {
    const { useConversationsStore } = await import('@/stores/conversations')
    const store = useConversationsStore()
    store.list = [
      { id: 1, mode: 'SINGLE', characterId: 10, lastMessage: '我刚发的话', lastCharacterMessage: '更旧角色', updatedAt: '2026-07-08T12:00:00.000Z' },
    ]

    store.patchRealtimeSummary(1, {
      lastMessage: '角色刚回复的话',
      lastCharacterMessage: '角色刚回复的话',
      updatedAt: '2026-07-08T12:00:02.000Z',
    })

    expect(store.list[0]).toMatchObject({
      id: 1,
      lastMessage: '角色刚回复的话',
      lastCharacterMessage: '角色刚回复的话',
    })
  })

  it('refreshes only the requested conversation summary from the detail API', async () => {
    const { useConversationsStore } = await import('@/stores/conversations')
    const store = useConversationsStore()
    store.list = [
      { id: 1, mode: 'SINGLE', characterId: 10, lastMessage: '旧摘要', lastCharacterMessage: '旧角色', createdAt: '2026-07-08T10:00:00.000Z' },
      { id: 2, mode: 'SINGLE', characterId: 20, lastMessage: '另一个摘要', lastCharacterMessage: '另一个角色', createdAt: '2026-07-08T09:00:00.000Z' },
    ]
    getConversationMock.mockResolvedValue({
      id: 1,
      lastMessage: '接口返回新摘要',
      lastCharacterMessage: '接口返回角色摘要',
      createdAt: '2026-07-08T12:00:00.000Z',
    })

    await store.refreshConversationSummary(1)

    expect(getConversationMock).toHaveBeenCalledWith(1)
    expect(store.list[0]).toMatchObject({
      id: 1,
      lastMessage: '接口返回新摘要',
      lastCharacterMessage: '接口返回角色摘要',
    })
    expect(store.list[1]).toMatchObject({ id: 2, lastMessage: '另一个摘要' })
  })
})
