import { describe, expect, it } from 'vitest'
import { filterOutCharacterNotifications } from '@/constants/notificationTypes'

describe('filterOutCharacterNotifications', () => {
  it('removes only notifications for the deleted character', () => {
    const list = [
      { id: 1, characterId: 9, type: 'PROACTIVE_MESSAGE', read: false },
      { id: 2, characterId: 8, type: 'MOMENT_NEW', read: false },
      { id: 3, characterId: null, type: 'COMMUNITY_POST_NEW', read: false },
      { id: 4, characterId: '9', type: 'DIARY_NEW', read: true },
    ]

    expect(filterOutCharacterNotifications(list, 9).map((n) => n.id)).toEqual([2, 3])
  })

  it('returns original list when characterId is missing', () => {
    const list = [{ id: 1, characterId: 9 }]
    expect(filterOutCharacterNotifications(list, null)).toEqual(list)
  })
})
