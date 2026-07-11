import { describe, expect, it, test } from 'vitest'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { buildMomentCommentThread, formatMomentCommentAuthorLabel } from '../momentsCommentThread'

const currentDir = dirname(fileURLToPath(import.meta.url))
const momentsPagePath = resolve(currentDir, '../MomentsPage.vue')

describe('momentsCommentThread', () => {
  it('groups replies under the correct parent comment and labels reply targets', () => {
    const comments = [
      { id: 1, parentId: null, characterName: '小雪', content: '顶层 A' },
      { id: 2, parentId: null, characterName: '小雨', content: '顶层 B' },
      { id: 3, parentId: 1, characterName: '阿宁', content: '回复 A' },
      { id: 4, parentId: 2, characterName: '阿宁', content: '回复 B' }
    ]

    const thread = buildMomentCommentThread(comments)

    expect(thread).toHaveLength(2)
    expect(thread[0].id).toBe(1)
    expect(thread[1].id).toBe(2)
    expect(thread[0].replies.map(item => item.id)).toEqual([3])
    expect(thread[1].replies.map(item => item.id)).toEqual([4])
    expect(
      formatMomentCommentAuthorLabel(thread[0].replies[0], '你', (author, target) => `${author} 回复 ${target}`)
    ).toBe('阿宁 回复 小雪')
    expect(
      formatMomentCommentAuthorLabel(thread[1].replies[0], '你', (author, target) => `${author} 回复 ${target}`)
    ).toBe('阿宁 回复 小雨')
  })

  it('keeps orphaned replies visible as top-level comments', () => {
    const comments = [
      { id: 10, parentId: 999, characterName: '阿宁', content: '找不到父评论' }
    ]

    const thread = buildMomentCommentThread(comments)

    expect(thread).toHaveLength(1)
    expect(thread[0].id).toBe(10)
    expect(thread[0].replies).toEqual([])
    expect(formatMomentCommentAuthorLabel(thread[0], '你', (author, target) => `${author} 回复 ${target}`)).toBe('阿宁')
  })
})

describe('MomentsPage comment threading integration', () => {
  test('renders nested replies with dedicated reply author label', () => {
    const source = readFileSync(momentsPagePath, 'utf8')

    expect(source).toContain('feed-comment-reply-list')
    expect(source).toContain('formatCommentAuthorLabel')
    expect(source).toContain('threadedComments(item.post.id)')
  })
})
