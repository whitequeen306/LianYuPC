import { describe, expect, it } from 'vitest'
import { resolveCharacterCardRefreshTarget } from '@/pages/charactersRealtime'

describe('resolveCharacterCardRefreshTarget', () => {
  it('returns the single-chat conversation id for MESSAGE notifications', () => {
    expect(resolveCharacterCardRefreshTarget(
      { type: 'MESSAGE', conversationId: 12 },
      { 12: 'SINGLE' },
    )).toBe(12)
  })

  it('ignores group notifications for character-card refresh', () => {
    expect(resolveCharacterCardRefreshTarget(
      { type: 'GROUP_MESSAGE', conversationId: 12 },
      { 12: 'GROUP' },
    )).toBeNull()
  })
})
