import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  clearChatMessageToasts,
  dismissChatMessageToast,
  pushChatMessageToast,
  useInAppMessageToast,
} from '../useInAppMessageToast.js'

describe('useInAppMessageToast', () => {
  beforeEach(() => {
    clearChatMessageToasts()
    vi.useFakeTimers()
  })

  it('pushes WeChat-style toast with name preview and time', () => {
    pushChatMessageToast({
      characterName: '雷电将军',
      preview: '你也要与我为敌吗？',
      createdAt: '2026-07-19T12:34:00',
      conversationId: 9,
      avatarUrl: 'https://cdn.example/raiden.png',
    })
    const { toasts } = useInAppMessageToast()
    expect(toasts.value).toHaveLength(1)
    expect(toasts.value[0].characterName).toBe('雷电将军')
    expect(toasts.value[0].preview).toBe('你也要与我为敌吗？')
    expect(toasts.value[0].timeLabel).toMatch(/^\d{2}:\d{2}$/)
    expect(toasts.value[0].avatarUrl).toBe('https://cdn.example/raiden.png')
  })

  it('caps stack size and auto-dismisses', () => {
    pushChatMessageToast({ characterName: 'A', preview: '1', conversationId: 1 })
    pushChatMessageToast({ characterName: 'B', preview: '2', conversationId: 2 })
    pushChatMessageToast({ characterName: 'C', preview: '3', conversationId: 3 })
    pushChatMessageToast({ characterName: 'D', preview: '4', conversationId: 4 })
    const { toasts } = useInAppMessageToast()
    expect(toasts.value).toHaveLength(3)
    expect(toasts.value[0].characterName).toBe('D')

    const firstId = toasts.value[0].id
    vi.advanceTimersByTime(5000)
    expect(toasts.value.find((t) => t.id === firstId)).toBeUndefined()
  })

  it('dismisses by id', () => {
    const id = pushChatMessageToast({ characterName: '甘雨', preview: '你好', conversationId: 2 })
    dismissChatMessageToast(id)
    const { toasts } = useInAppMessageToast()
    expect(toasts.value).toHaveLength(0)
  })
})
