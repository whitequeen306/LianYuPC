import { describe, expect, it, vi } from 'vitest'
import { recoverFromStartupRouteError, shouldReloadForCssPreloadError } from '../startupErrorRecovery'

function createStorage() {
  const values = new Map()
  return {
    getItem: vi.fn((key) => values.get(key) || null),
    setItem: vi.fn((key, value) => values.set(key, value)),
  }
}

describe('startup route error recovery', () => {
  it('reloads once when Vite CSS preload fails', () => {
    const storage = createStorage()
    const locationObject = { href: 'file:///app/index.html#/', reload: vi.fn() }
    const error = new Error('Unable to preload CSS for file:///app/assets/LandingPage.css')

    expect(recoverFromStartupRouteError(error, locationObject, storage)).toBe(true)
    expect(locationObject.reload).toHaveBeenCalledTimes(1)

    expect(recoverFromStartupRouteError(error, locationObject, storage)).toBe(false)
    expect(locationObject.reload).toHaveBeenCalledTimes(1)
  })

  it('ignores non CSS preload errors', () => {
    const storage = createStorage()
    const locationObject = { href: 'file:///app/index.html#/', reload: vi.fn() }

    expect(shouldReloadForCssPreloadError(new Error('chunk failed'), locationObject, storage)).toBe(false)
    expect(storage.setItem).not.toHaveBeenCalled()
  })

  it('does not throw when session storage is unavailable', () => {
    const storage = {
      getItem: vi.fn(() => { throw new Error('storage unavailable') }),
      setItem: vi.fn(() => { throw new Error('storage unavailable') }),
    }
    const locationObject = { href: 'file:///app/index.html#/login', reload: vi.fn() }
    const error = new Error('Unable to preload CSS for file:///app/assets/LoginPage.css')

    expect(recoverFromStartupRouteError(error, locationObject, storage)).toBe(true)
    expect(locationObject.reload).toHaveBeenCalledTimes(1)
  })
})
