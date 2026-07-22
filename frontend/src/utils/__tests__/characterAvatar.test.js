import { describe, expect, it } from 'vitest'
import {
  nextCharacterAvatarTier,
  pickCharacterAvatarRaw,
  resolveCharacterAvatarSrc,
} from '@/utils/characterAvatar'

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

describe('resolveCharacterAvatarSrc', () => {
  const storeChar = {
    id: 10,
    avatarThumbUrl: '/thumb/from-store.webp',
    avatarUrl: '/orig/from-store.webp',
  }

  it('prefers store character over DTO fallbacks', () => {
    expect(resolveCharacterAvatarSrc({
      characterId: 10,
      characters: [storeChar],
      avatarUrl: '/orig/dto.webp',
      avatarThumbUrl: '/thumb/dto.webp',
    })).toBe(storeChar.avatarThumbUrl)
  })

  it('uses character object when provided', () => {
    expect(resolveCharacterAvatarSrc({
      character: storeChar,
      characterId: 99,
      characters: [],
      avatarUrl: '/orig/dto.webp',
    })).toBe(storeChar.avatarThumbUrl)
  })

  it('normalizes characterAvatar* DTO aliases', () => {
    expect(resolveCharacterAvatarSrc({
      characterId: 7,
      characterAvatarUrl: '/orig/alias.webp',
      characterAvatarThumbUrl: '/thumb/alias.webp',
    })).toBe('/thumb/alias.webp')

    expect(resolveCharacterAvatarSrc({
      characterAvatarUrl: '/orig/only.webp',
      tier: 'orig',
    })).toBe('/orig/only.webp')
  })

  it('falls back to DTO fields when store has no avatars', () => {
    expect(resolveCharacterAvatarSrc({
      characterId: 10,
      characters: [{ id: 10, avatarUrl: '', avatarThumbUrl: '' }],
      characterAvatarUrl: '/orig/dto.webp',
      characterAvatarThumbUrl: '/thumb/dto.webp',
    })).toBe('/thumb/dto.webp')
  })

  it('uses fallback fields when character is not in store', () => {
    expect(resolveCharacterAvatarSrc({
      characterId: 99,
      characters: [storeChar],
      avatarUrl: '/orig/fallback.webp',
      avatarThumbUrl: '/thumb/fallback.webp',
    })).toBe('/thumb/fallback.webp')
  })

  it('respects tier orig and broken', () => {
    expect(resolveCharacterAvatarSrc({
      character: storeChar,
      tier: 'orig',
    })).toBe(storeChar.avatarUrl)

    expect(resolveCharacterAvatarSrc({
      character: storeChar,
      tier: 'broken',
    })).toBe('')
  })
})
