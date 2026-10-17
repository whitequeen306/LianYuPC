import { describe, expect, it } from 'vitest'
// 注意：这是真实行为测试——import 被三处消费方共用的产品实现，而非测试内副本。
import {
  createSseAccumulator,
  parseSseDataPayload,
  parseSseStream,
} from '../../shared/sseParser.js'

describe('parseSseDataPayload', () => {
  it('ignores empty, [DONE] and non-JSON lines', () => {
    expect(parseSseDataPayload('')).toBeNull()
    expect(parseSseDataPayload('[DONE]')).toBeNull()
    expect(parseSseDataPayload('not json')).toBeNull()
    expect(parseSseDataPayload('123')).toBeNull()
  })

  it('extracts content delta', () => {
    expect(parseSseDataPayload('{"content":"你好"}')).toEqual({ content: '你好' })
  })

  it('extracts replace as string', () => {
    expect(parseSseDataPayload('{"replace":"全文"}')).toEqual({ replace: '全文' })
  })

  it('extracts pieces array', () => {
    expect(parseSseDataPayload('{"replace":"x","pieces":["段1","段2"]}')).toEqual({
      replace: 'x',
      pieces: ['段1', '段2'],
    })
  })

  it('extracts error', () => {
    expect(parseSseDataPayload('{"error":"生成失败"}')).toEqual({ error: '生成失败' })
  })

  it('keeps empty-string replace (language gate may emit empty)', () => {
    const applied = parseSseDataPayload('{"replace":""}')
    expect(applied).toEqual({ replace: '' })
  })
})

describe('parseSseStream (整流)', () => {
  it('accumulates content deltas across events', () => {
    const raw = 'data: {"content":"你好"}\n\ndata: {"content":"，世界"}\n\ndata: [DONE]\n'
    expect(parseSseStream(raw).content).toBe('你好，世界')
  })

  it('replace overrides accumulated content', () => {
    const raw = 'data: {"content":"abc"}\n\ndata: {"replace":"校正后"}\n\n'
    expect(parseSseStream(raw)).toMatchObject({ content: 'abc', replace: '校正后' })
  })

  it('keeps last pieces frame', () => {
    const raw = 'data: {"pieces":["a"]}\n\ndata: {"pieces":["a","b"]}\n\n'
    expect(parseSseStream(raw).pieces).toEqual(['a', 'b'])
  })

  it('skips comment heartbeat lines and CRLF block separators', () => {
    const raw = ': ping\r\n\r\ndata: {"content":"hi"}\r\n\r\n'
    expect(parseSseStream(raw).content).toBe('hi')
  })

  it('joins multi-line data payloads of one block', () => {
    const raw = 'data: {"content":"a"}\ndata: {"content":"b"}\n\n'
    // 多行 data 按 SSE 规范拼接后整体非 JSON → 忽略（与后端单行事件契约一致）
    expect(parseSseStream(raw).content).toBe('')
  })

  it('captures error field', () => {
    expect(parseSseStream('data: {"error":"超时"}\n\n').error).toBe('超时')
  })

  it('handles empty and non-string input', () => {
    expect(parseSseStream('')).toMatchObject({ pieces: [], replace: '', content: '', error: '' })
    expect(parseSseStream(null)).toMatchObject({ pieces: [], replace: '', content: '', error: '' })
  })
})

describe('createSseAccumulator (流式)', () => {
  it('accumulates and returns pieces null when absent', () => {
    const acc = createSseAccumulator()
    acc.feed('data: {"content":"你"}')
    acc.feed('data: {"content":"好"}')
    acc.feed('data: [DONE]')
    expect(acc.result()).toEqual({ fullContent: '你好', pieces: null })
  })

  it('pieces are trimmed and filtered', () => {
    const acc = createSseAccumulator()
    acc.feed('data: {"pieces":["  段1 ", "", "段2"]}')
    expect(acc.result().pieces).toEqual(['段1', '段2'])
  })

  it('throws on error frame', () => {
    const acc = createSseAccumulator()
    expect(() => acc.feed('data: {"error":"生成失败"}')).toThrow('生成失败')
  })

  it('ignores non-data lines', () => {
    const acc = createSseAccumulator()
    acc.feed(': heartbeat')
    acc.feed('event: message')
    acc.feed('data: {"content":"x"}')
    expect(acc.result().fullContent).toBe('x')
  })
})
