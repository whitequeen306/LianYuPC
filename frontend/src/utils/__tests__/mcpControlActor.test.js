import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('@/i18n', () => ({
  i18n: {
    global: {
      t: (key, params) => {
        if (key === 'about.mcpControlBanner') return `${params.name}正在操控你的电脑，按 Esc 取消`
        if (key === 'about.mcpControlFallbackName') return '角色'
        return key
      },
    },
  },
}))

vi.mock('@/utils/media', () => ({
  resolveMediaUrl: (url) => (url ? `resolved:${url}` : ''),
}))

import { resolveMcpActorIdentity, resolveMcpControlActor } from '../mcpControlActor.js'

describe('resolveMcpControlActor', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('prefers fields from the STOMP payload', () => {
    const actor = resolveMcpControlActor({
      characterId: 620,
      characterName: '琉璃',
      characterAvatarUrl: '/api/public/files/a.png',
    }, { characters: [], theme: 'dark' })
    expect(actor.name).toBe('琉璃')
    expect(actor.avatarUrl).toBe('resolved:/api/public/files/a.png')
    expect(actor.caption).toContain('琉璃正在操控你的电脑')
    expect(actor.theme).toBe('dark')
  })

  it('fills name and avatar from the current chat character', () => {
    const actor = resolveMcpControlActor(
      { type: 'tool_call' },
      {
        characters: [],
        currentCharacter: { id: 12, name: '爱莉希雅', avatarUrl: '/eli.png' },
      },
    )
    expect(actor.name).toBe('爱莉希雅')
    expect(actor.avatarUrl).toBe('resolved:/eli.png')
    expect(actor.caption).toContain('爱莉希雅正在操控你的电脑')
  })

  it('does not use an unrelated current character when STOMP has another id', () => {
    const actor = resolveMcpControlActor(
      { characterId: 1 },
      {
        characters: [],
        currentCharacter: { id: 99, name: '别人', avatarUrl: '/x.png' },
      },
    )
    expect(actor.name).toBe('角色')
    expect(actor.avatarUrl).toBe('')
  })

  it('fills name and avatar from the local character list', () => {
    const actor = resolveMcpControlActor(
      { characterId: '9' },
      { characters: [{ id: 9, name: '爱莉', avatarUrl: '/a.png' }], theme: 'light' },
    )
    expect(actor.name).toBe('爱莉')
    expect(actor.avatarUrl).toBe('resolved:/a.png')
    expect(actor.theme).toBe('light')
  })

  it('falls back to a generic name', () => {
    const actor = resolveMcpControlActor({ type: 'tool_call' }, { characters: [] })
    expect(actor.name).toBe('角色')
    expect(actor.caption).toContain('角色')
  })

  it('prefers local public thumb over a STOMP object key', () => {
    const actor = resolveMcpControlActor({
      characterId: '744',
      characterName: '甘雨',
      characterAvatarUrl: 'square-avatars/ganyu.jpg',
    }, {
      characters: [{
        id: 744,
        name: '甘雨',
        avatarUrl: '/api/public/files/square-avatars/ganyu.jpg',
        avatarThumbUrl: '/api/public/files/square-avatars-thumb/ganyu.jpg',
      }],
    })
    expect(actor.avatarUrl).toBe('resolved:/api/public/files/square-avatars-thumb/ganyu.jpg')
  })
})

describe('resolveMcpActorIdentity', () => {
  it('keeps STOMP name but fills avatar from the local character list', () => {
    const identity = resolveMcpActorIdentity({
      characterId: 744,
      characterName: '甘雨',
      characterAvatarUrl: 'square-avatars/ganyu.jpg',
    }, {
      characters: [{
        id: 744,
        name: '甘雨',
        avatarUrl: '/api/public/files/square-avatars/ganyu.jpg',
        avatarThumbUrl: '/api/public/files/square-avatars-thumb/ganyu.jpg',
      }],
    })
    expect(identity).toEqual({
      characterId: 744,
      name: '甘雨',
      avatarUrl: '/api/public/files/square-avatars/ganyu.jpg',
      avatarThumbUrl: '/api/public/files/square-avatars-thumb/ganyu.jpg',
    })
  })

  it('uses the current chat character when the list is empty', () => {
    const identity = resolveMcpActorIdentity(
      { characterId: 12, characterName: '爱莉希雅', characterAvatarUrl: 'avatars/raw-key.png' },
      {
        characters: [],
        currentCharacter: {
          id: 12,
          name: '爱莉希雅',
          avatarUrl: '/api/public/files/avatars/eli.png',
          avatarThumbUrl: '/api/public/files/square-avatars-thumb/eli.jpg',
        },
      },
    )
    expect(identity.avatarUrl).toBe('/api/public/files/avatars/eli.png')
    expect(identity.avatarThumbUrl).toBe('/api/public/files/square-avatars-thumb/eli.jpg')
  })

  it('falls back to the STOMP url when no local avatar is available', () => {
    const identity = resolveMcpActorIdentity({
      characterId: 1,
      characterName: '甘雨',
      characterAvatarUrl: 'square-avatars/ganyu.jpg',
    }, { characters: [] })
    expect(identity.avatarUrl).toBe('square-avatars/ganyu.jpg')
    expect(identity.avatarThumbUrl).toBe('')
  })
})
