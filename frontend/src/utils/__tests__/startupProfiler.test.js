import { describe, expect, it, vi } from 'vitest'
import { createStartupProfiler } from '@/utils/startupProfiler'

describe('renderer createStartupProfiler', () => {
  it('uses the provided now function to compute elapsed time', () => {
    const log = vi.fn()
    const now = vi.fn()
      .mockReturnValueOnce(10)
      .mockReturnValueOnce(37)

    const profiler = createStartupProfiler({ prefix: 'startup-renderer', log, now })
    profiler.mark('prepareAuthRoute:done')

    expect(log).toHaveBeenCalledWith('[startup-renderer] prepareAuthRoute:done +27ms')
  })

  it('falls back to a non-negative elapsed time when called repeatedly', () => {
    const log = vi.fn()
    const now = vi.fn()
      .mockReturnValueOnce(100)
      .mockReturnValueOnce(100)
      .mockReturnValueOnce(115)

    const profiler = createStartupProfiler({ prefix: 'startup-renderer', log, now })
    profiler.mark('entry')
    profiler.mark('mount')

    expect(log.mock.calls).toEqual([
      ['[startup-renderer] entry +0ms'],
      ['[startup-renderer] mount +15ms'],
    ])
  })
})
