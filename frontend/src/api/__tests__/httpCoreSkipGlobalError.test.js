import { describe, expect, it } from 'vitest'
import { toHttpError } from '../httpCore.js'

describe('toHttpError', () => {
  it('preserves skipGlobalError so toast interceptor can silence expected 404s', () => {
    const config = { skipGlobalError: true, url: '/conversation/1' }
    const err = toHttpError('对话不存在', config, { code: 3001 })
    expect(err).toBeInstanceOf(Error)
    expect(err.message).toBe('对话不存在')
    expect(err.config?.skipGlobalError).toBe(true)
    expect(err.code).toBe(3001)
  })
})
