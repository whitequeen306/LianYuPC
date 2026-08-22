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

import { resolveMcpControlActor } from '../mcpControlActor.js'

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
})
