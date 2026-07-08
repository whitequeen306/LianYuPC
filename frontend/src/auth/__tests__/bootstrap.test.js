import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

const replaceMock = vi.fn()
const currentRoute = { value: { path: '/', meta: { public: true } } }

vi.mock('@/router/index.js', () => ({
  default: {
    replace: replaceMock,
    currentRoute,
  },
}))

describe('prepareAuthRoute', () => {
  let electronAPI

  beforeEach(() => {
    vi.resetModules()
    replaceMock.mockReset()
    electronAPI = {
      bootstrapAuthToken: vi.fn(),
    }
    vi.stubGlobal('window', { electronAPI, location: { hash: '#/' }, navigator: { userAgent: 'vitest' } })
    vi.stubGlobal('localStorage', { removeItem: vi.fn(), getItem: vi.fn(), setItem: vi.fn() })
    currentRoute.value = { path: '/', meta: { public: true } }
  })

  it('routes authenticated startup directly to /app', async () => {
    electronAPI.bootstrapAuthToken.mockResolvedValue('tok-123')

    const pinia = createPinia()
    setActivePinia(pinia)
    const { prepareAuthRoute } = await import('@/auth/bootstrap')
    const { syncToken, clearTokenStorage } = await import('@/utils/secureToken')
    clearTokenStorage()
    const routed = await prepareAuthRoute(pinia)

    expect(routed).toBe(true)
    expect(syncToken()).toBe('tok-123')
    expect(replaceMock).toHaveBeenCalledWith('/app')
  })

  it('keeps anonymous startup on public route', async () => {
    electronAPI.bootstrapAuthToken.mockResolvedValue('')

    const pinia = createPinia()
    setActivePinia(pinia)
    const { prepareAuthRoute } = await import('@/auth/bootstrap')
    const { syncToken, clearTokenStorage } = await import('@/utils/secureToken')
    clearTokenStorage()
    const routed = await prepareAuthRoute(pinia)

    expect(routed).toBe(false)
    expect(syncToken()).toBeNull()
    expect(replaceMock).not.toHaveBeenCalled()
  })

  it('redirects expired startup sessions back to landing when restore fails on protected route', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const { bootstrapAuth } = await import('@/auth/bootstrap')
    const { useUserStore } = await import('@/stores/user')
    currentRoute.value = { path: '/app', meta: { requiresAuth: true } }

    const userStore = useUserStore(pinia)
    userStore.restoreSession = vi.fn().mockResolvedValue(false)

    const restored = await bootstrapAuth(pinia)

    expect(restored).toBe(false)
    expect(replaceMock).toHaveBeenCalledWith('/')
  })
})
