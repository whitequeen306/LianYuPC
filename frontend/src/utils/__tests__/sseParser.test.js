import { describe, it, expect } from 'vitest'

/** Minimal SSE payload parser mirroring ChatPage stream handling (CL-052). */
function parseSseDataLine(line) {
  const trimmed = line.trim()
  if (!trimmed.startsWith('data:')) return null
  const payload = trimmed.slice(5).trim()
  if (payload === '[DONE]') return { done: true }
  try {
    return JSON.parse(payload)
  } catch {
    return null
  }
}

describe('sse parser', () => {
  it('parses JSON token payload', () => {
    expect(parseSseDataLine('data: {"token":"hi"}')).toEqual({ token: 'hi' })
  })

  it('detects DONE marker', () => {
    expect(parseSseDataLine('data: [DONE]')).toEqual({ done: true })
  })

  it('returns null for invalid JSON', () => {
    expect(parseSseDataLine('data: not-json')).toBeNull()
  })

  it('parses error payload', () => {
    expect(parseSseDataLine('data: {"error":"fail"}')).toEqual({ error: 'fail' })
  })
})
