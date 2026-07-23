import { describe, expect, it } from 'vitest'
import {
  buildChatImageShareDraft,
  isShareSelectableMessage
} from '@/utils/communityShareDraft'

describe('communityShareDraft', () => {
  it('detects share-selectable timeline items', () => {
    expect(isShareSelectableMessage({ type: 'message', id: 12 })).toBe(true)
    expect(isShareSelectableMessage({ type: 'message', id: 12, _streamGroupId: 's1' })).toBe(false)
    expect(isShareSelectableMessage({ type: 'time' })).toBe(false)
  })

  it('builds chat image share draft from uploaded screenshot', () => {
    const draft = buildChatImageShareDraft({
      imageUrl: '/community-images/a.png',
      linkedCharacterId: 7
    })

    expect(draft.kind).toBe('chat')
    expect(draft.imageUrl).toBe('/community-images/a.png')
    expect(draft.linkedCharacterId).toBe(7)
  })
})
