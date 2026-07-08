import { describe, expect, it } from 'vitest'
import { buildLatestSingleConversationMap, selectCharacterPreview } from '@/pages/charactersPreview'

describe('selectCharacterPreview', () => {
  it('prefers the latest assistant message preview', () => {
    expect(selectCharacterPreview({
      lastMessage: '用户：你在吗',
      lastCharacterMessage: '角色：我在，一直都在。',
    }, '暂无消息')).toBe('角色：我在，一直都在。')
  })

  it('falls back to the generic last message when no assistant reply exists', () => {
    expect(selectCharacterPreview({
      lastMessage: '用户：你好',
      lastCharacterMessage: '',
    }, '暂无消息')).toBe('用户：你好')
  })

  it('uses fallback text when neither preview exists', () => {
    expect(selectCharacterPreview({
      lastMessage: '',
      lastCharacterMessage: '',
    }, '暂无消息')).toBe('暂无消息')
  })

  it('keeps the newest single conversation per character when building the preview map', () => {
    const map = buildLatestSingleConversationMap([
      {
        id: 11,
        mode: 'SINGLE',
        characterId: 7,
        lastMessage: '旧用户消息',
        lastCharacterMessage: '旧角色回复',
        updatedAt: '2026-07-08T10:00:00.000Z',
      },
      {
        id: 12,
        mode: 'SINGLE',
        characterId: 7,
        lastMessage: '新用户消息',
        lastCharacterMessage: '新角色回复',
        updatedAt: '2026-07-08T12:00:00.000Z',
      },
    ])

    expect(map[7]).toMatchObject({
      id: 12,
      lastMessage: '新用户消息',
      lastCharacterMessage: '新角色回复',
    })
  })
})
