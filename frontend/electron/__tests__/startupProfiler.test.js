import { describe, expect, it, vi } from 'vitest'
import { createStartupProfiler } from '../startupProfiler.js'

describe('createStartupProfiler', () => {
  it('logs elapsed milliseconds from the initial start time', () => {
    const log = vi.fn()
    const now = vi.spyOn(Date, 'now')
      .mockReturnValueOnce(1000)
      .mockReturnValueOnce(1018)

    const profiler = createStartupProfiler({ prefix: 'startup-main', log })
    profiler.mark('configureSecurity:done')

    expect(log).toHaveBeenCalledWith('[startup-main] configureSecurity:done +18ms')
    now.mockRestore()
  })

  it('keeps elapsed time monotonic across multiple marks', () => {
    const log = vi.fn()
    const now = vi.spyOn(Date, 'now')
      .mockReturnValueOnce(2000)
      .mockReturnValueOnce(2010)
      .mockReturnValueOnce(2045)

    const profiler = createStartupProfiler({ prefix: 'startup-main', log })
    profiler.mark('createMainWindow:start')
    profiler.mark('createMainWindow:done')

    expect(log.mock.calls).toEqual([
      ['[startup-main] createMainWindow:start +10ms'],
      ['[startup-main] createMainWindow:done +45ms'],
    ])
    now.mockRestore()
  })
})
