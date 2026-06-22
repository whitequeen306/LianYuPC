import { describe, it, expect, beforeEach, vi } from 'vitest'
import { syncSetTokenCache, syncToken, clearTokenStorage } from '@/utils/secureToken'

describe('secureToken', () => {
  beforeEach(() => {
    vi.stubGlobal('localStorage', {
      removeItem: vi.fn(),
      getItem: vi.fn(),
      setItem: vi.fn(),
    })
    clearTokenStorage()
  })

  it('syncToken returns cached value after syncSetTokenCache', () => {
    syncSetTokenCache('test-token-abc')
    expect(syncToken()).toBe('test-token-abc')
  })

  it('clearTokenStorage removes cached token', () => {
    syncSetTokenCache('gone')
    clearTokenStorage()
    expect(syncToken()).toBeNull()
  })
})
