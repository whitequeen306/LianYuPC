import { describe, expect, it } from 'vitest'
import {
  findParenthesisRanges,
  hasInnerThoughtMarkers,
  normalizeAssistantContent,
  parseInnerThoughtSegments,
  stripInnerThoughts,
} from '../innerThoughtFilter'

describe('parseInnerThoughtSegments', () => {
  it('parses full-width parentheses as inner thoughts', () => {
    expect(parseInnerThoughtSegments('你好（轻轻点头）再见')).toEqual([
      { type: 'speech', text: '你好' },
      { type: 'inner', text: '（轻轻点头）' },
      { type: 'speech', text: '再见' },
    ])
  })

  it('supports mixed full/half-width parentheses', () => {
    expect(parseInnerThoughtSegments('（我轻轻抚摸了你的头说道) 嗯？')).toEqual([
      { type: 'speech', text: '' },
      { type: 'inner', text: '（我轻轻抚摸了你的头说道)' },
      { type: 'speech', text: ' 嗯？' },
    ].filter((segment) => segment.text !== ''))
  })

  it('keeps multiline parenthetical content together', () => {
    const text = '（她靠近你，轻轻握住你的手\n\n）好的，我等一下。'
    expect(parseInnerThoughtSegments(text)).toEqual([
      { type: 'inner', text: '（她靠近你，轻轻握住你的手\n\n）' },
      { type: 'speech', text: '好的，我等一下。' },
    ])
  })

  it('styles unclosed parenthesis to end of bubble as inner thought', () => {
    expect(parseInnerThoughtSegments('（被他轻轻摸头的瞬间，我愣了一下')).toEqual([
      { type: 'inner', text: '（被他轻轻摸头的瞬间，我愣了一下' },
    ])
  })
})

describe('normalizeAssistantContent', () => {
  const WJ = '\u2060'

  it('flattens and closes inner thought parentheses', () => {
    const raw = '（愣了一下\n\n目光瞟了一眼'
    expect(normalizeAssistantContent(raw)).toBe(`（愣了一下 目光瞟了一眼${WJ}）`)
  })

  it('collapses hard newlines so closing paren is not alone on next line', () => {
    const raw =
      '嗯。(她把手机屏幕朝下扣在枕边，闭上眼睛。\n心里默默算着：还有八个多小时就能见到你了。\n这次是真的不回了。\n)'
    const normalized = normalizeAssistantContent(raw)
    expect(normalized).not.toContain('\n')
    expect(normalized.endsWith(`${WJ})`)).toBe(true)
    expect(normalized).toBe(
      `嗯。(她把手机屏幕朝下扣在枕边，闭上眼睛。 心里默默算着：还有八个多小时就能见到你了。 这次是真的不回了。${WJ})`
    )
  })
})

describe('stripInnerThoughts', () => {
  it('removes mixed-width parenthetical blocks when hidden', () => {
    const text = '你好（动作) 再见'
    expect(stripInnerThoughts(text, false)).toBe('你好 再见')
  })
})

describe('hasInnerThoughtMarkers', () => {
  it('detects mixed-width markers', () => {
    expect(hasInnerThoughtMarkers('（动作)')).toBe(true)
    expect(findParenthesisRanges('（动作)').length).toBe(1)
  })
})
