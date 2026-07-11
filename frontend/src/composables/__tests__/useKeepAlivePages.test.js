import { describe, expect, it } from 'vitest'
import { KEEP_ALIVE_PAGES, isCacheable } from '@/composables/useKeepAlivePages'

describe('useKeepAlivePages', () => {
  it('contains exactly the 7 heavy list pages', () => {
    expect(KEEP_ALIVE_PAGES).toEqual([
      'HomePage',
      'CharactersPage',
      'CharacterSquarePage',
      'MomentsPage',
      'MemoryPage',
      'DiaryPage',
      'ProfilePage',
    ])
  })

  it('isCacheable returns true for cached page names', () => {
    expect(isCacheable('HomePage')).toBe(true)
    expect(isCacheable('MomentsPage')).toBe(true)
    expect(isCacheable('ProfilePage')).toBe(true)
  })

  it('isCacheable returns false for non-cached / stateful pages', () => {
    expect(isCacheable('Chat')).toBe(false)
    expect(isCacheable('CharacterChatDetail')).toBe(false)
    expect(isCacheable('GroupChat')).toBe(false)
    expect(isCacheable('Settings')).toBe(false)
    expect(isCacheable('Landing')).toBe(false)
    expect(isCacheable(undefined)).toBe(false)
  })
})
