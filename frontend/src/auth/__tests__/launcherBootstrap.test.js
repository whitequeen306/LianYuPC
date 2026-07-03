import { describe, it, expect, beforeEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { syncToken, clearTokenStorage } from '@/utils/secureToken'

describe('refreshLauncherSession (#14 token recovery)', () => {
  let electronAPI

  beforeEach(() => {
    clearTokenStorage()
    electronAPI = {
      getAuthSession: vi.fn(),
      bootstrapAuthToken: vi.fn(),
      setLoginState: vi.fn(),
    }
    vi.stubGlobal('window', { electronAPI, navigator: { userAgent: 'vitest' } })
    vi.stubGlobal('localStorage', { removeItem: vi.fn(), getItem: vi.fn(), setItem: vi.fn() })
  })

  it('recovers token via bootstrapAuthToken when session.hasToken is true', async () => {
    electronAPI.getAuthSession.mockResolvedValue({
      hasToken: true,
      userId: 5,
      username: 'u',
      tokenName: 'lianyu-token',
    })
    electronAPI.bootstrapAuthToken.mockResolvedValue('plain-tok-xyz')

    const pinia = createPinia()
    setActivePinia(pinia)
    const { refreshLauncherSession } = await import('@/auth/launcherBootstrap')
    const { useUserStore } = await import('@/stores/user')

    const ok = await refreshLauncherSession(pinia)

    expect(ok).toBe(true)
    expect(electronAPI.bootstrapAuthToken).toHaveBeenCalled()
    expect(syncToken()).toBe('plain-tok-xyz')
    expect(useUserStore(pinia).token).toBe('plain-tok-xyz')
    expect(electronAPI.setLoginState).toHaveBeenCalledWith(true)
  })

  it('returns false when session has no token and bootstrapAuthToken empty', async () => {
    electronAPI.getAuthSession.mockResolvedValue({ hasToken: false })
    electronAPI.bootstrapAuthToken.mockResolvedValue('')

    const pinia = createPinia()
    setActivePinia(pinia)
    const { refreshLauncherSession } = await import('@/auth/launcherBootstrap')

    const ok = await refreshLauncherSession(pinia)

    expect(ok).toBe(false)
    expect(syncToken()).toBeNull()
  })
})
