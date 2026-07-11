import { describe, expect, it, vi } from 'vitest'
import {
  ROUTE_TRANSITION_CLASS,
  ROUTE_TRANSITION_DURATION_MS,
  PREFETCH_ROUTE_FACTORIES,
  prefetchRoutesOnIdle,
} from '@/composables/useRouteTransition'

describe('useRouteTransition', () => {
  it('exposes the transition class name and duration', () => {
    expect(ROUTE_TRANSITION_CLASS).toBe('ly-route-transitioning')
    expect(ROUTE_TRANSITION_DURATION_MS).toBe(280)
  })

  it('provides 6 prefetch factory functions', () => {
    expect(PREFETCH_ROUTE_FACTORIES).toHaveLength(6)
    for (const f of PREFETCH_ROUTE_FACTORIES) {
      expect(typeof f).toBe('function')
    }
  })

  it('prefetchRoutesOnIdle calls all factories on idle callback', () => {
    const calls = []
    const factories = [() => calls.push('a'), () => calls.push('b')]
    const ric = vi.fn((cb) => cb())
    const allSettled = vi.fn((arr) => Promise.all(arr))
    prefetchRoutesOnIdle({ factories, requestIdleCallback: ric, Promise: { allSettled } })
    expect(calls).toEqual(['a', 'b'])
    expect(ric).toHaveBeenCalled()
  })

  it('prefetchRoutesOnIdle no-ops when requestIdleCallback unavailable', () => {
    const factories = [() => { throw new Error('should not run') }]
    expect(() => prefetchRoutesOnIdle({ factories, requestIdleCallback: undefined })).not.toThrow()
  })
})
