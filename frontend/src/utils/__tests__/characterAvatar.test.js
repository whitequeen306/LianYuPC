import { describe, expect, it } from 'vitest'
import { nextCharacterAvatarTier, pickCharacterAvatarRaw } from '@/utils/characterAvatar'

describe('characterAvatar', () => {
  const character = {
    id: 1,
    avatarThumbUrl: '/api/public/files/square-avatars-thumb/kurumi.jpg',
    avatarUrl: '/api/public/files/square-avatars/kurumi.jpg',
  }

  it('prefers thumb for list display', () => {
    expect(pickCharacterAvatarRaw(character, 'thumb')).toBe(character.avatarThumbUrl)
  })

  it('falls back to original when thumb tier exhausted', () => {
    expect(pickCharacterAvatarRaw(character, 'orig')).toBe(character.avatarUrl)
  })

  it('advances tier on load error', () => {
    expect(nextCharacterAvatarTier(character, 'thumb')).toBe('orig')
    expect(nextCharacterAvatarTier(character, 'orig')).toBe('broken')
  })

  it('falls back to thumb when raw avatarUrl is missing but thumb exists', () => {
    const partial = {
      id: 2,
      avatarThumbUrl: '/api/public/files/square-avatars-thumb/citlali.jpg',
      avatarUrl: '',
    }

    expect(pickCharacterAvatarRaw(partial, 'thumb')).toBe(partial.avatarThumbUrl)
    expect(pickCharacterAvatarRaw(partial, 'orig')).toBe(partial.avatarThumbUrl)
  })
})
