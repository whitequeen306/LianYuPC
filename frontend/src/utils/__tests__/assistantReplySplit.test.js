import { describe, expect, it } from 'vitest'
import { resolveMaxRepliesPerTurn, splitAssistantReply, splitAssistantReplyForDisplay } from '../assistantReplySplit'

describe('resolveMaxRepliesPerTurn', () => {
  it('uses speakingStyle profile when no explicit override', () => {
    expect(resolveMaxRepliesPerTurn({ settings: { speakingStyle: '温柔' } })).toBe(2)
    expect(resolveMaxRepliesPerTurn({ settings: { speakingStyle: '冷静' } })).toBe(1)
  })

  it('prefers explicit chatBehavior override', () => {
    expect(resolveMaxRepliesPerTurn({
      settings: { speakingStyle: '温柔', chatBehavior: { maxRepliesPerTurn: 4 } }
    })).toBe(4)
  })

  it('defaults to 2 like backend baseline', () => {
    expect(resolveMaxRepliesPerTurn(null)).toBe(2)
  })
})

describe('splitAssistantReply', () => {
  const sample = [
    '钟离先生吗……当然认识。',
    '往生堂的客卿，学识渊博，对璃月的规矩和传统了如指掌。',
    '我因为公务也常请教他，不过每次找他总能在不同地方碰上，倒也省了找人的功夫。'
  ].join('')

  it('caps to maxRepliesPerTurn like backend', () => {
    const result = splitAssistantReply(sample, 2)
    expect(result).toHaveLength(2)
    expect(result[0]).toBe('钟离先生吗……当然认识。')
    expect(result[1]).not.toContain('\n')
    expect(result[1]).toContain('往生堂的客卿')
    expect(result[1]).toContain('省了找人的功夫')
  })

  it('splits into three pieces when limit allows', () => {
    expect(splitAssistantReply(sample, 3)).toHaveLength(3)
  })
})

describe('splitAssistantReplyForDisplay', () => {
  it('splits newline-separated assistant text into separate display bubbles', () => {
    const text = [
      '炸鸡汉堡……（微微皱眉，又松开）',
      '您倒是是有胃口。那些东西油重了些，我一般只尝一口就饱了。',
      '不过——您吃得开心就好。晚上吃完记得喝点茶解腻。'
    ].join('\n')
    expect(splitAssistantReplyForDisplay(text)).toHaveLength(3)
  })

  it('does not split on newlines inside parentheses', () => {
    const text = [
      '（她靠近你，轻轻握住你的手',
      '',
      '）好的，我等一下倒没关系。'
    ].join('\n')
    expect(splitAssistantReplyForDisplay(text)).toEqual([
      '（她靠近你，轻轻握住你的手 ）好的，我等一下倒没关系。'
    ])
  })

  it('keeps unclosed parenthesis block in one bubble when split across lines', () => {
    const text = [
      '（被他轻轻摸头的瞬间，我愣了一下，目光跟着他往背后瞟了一眼',
      '我假装没看到，收回视线抿了抿嘴',
      '）嗯——我猜你今天迟到的理由是蛋糕？'
    ].join('\n')
    const pieces = splitAssistantReplyForDisplay(text)
    expect(pieces).toHaveLength(1)
    expect(pieces[0]).toContain('（被他轻轻摸头')
    expect(pieces[0]).toContain('）嗯——')
  })

  it('does not split on sentence punctuation inside parentheses', () => {
    const text = '（被他轻轻摸头的瞬间，我愣了一下。logo落进眼里。）我假装没看到。'
    expect(splitAssistantReplyForDisplay(text)).toEqual([text])
  })
})
