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

describe('prepareAuthRoute route restore', () => {
  let electronAPI
  let localStorageMock

  beforeEach(() => {
    vi.resetModules()
    replaceMock.mockReset()
    electronAPI = {
      bootstrapAuthToken: vi.fn(),
    }
    localStorageMock = {
      getItem: vi.fn((key) => {
        if (key === 'lianyu-last-route') return '/app/settings'
        return null
      }),
      setItem: vi.fn(),
      removeItem: vi.fn(),
    }
    vi.stubGlobal('window', { electronAPI, location: { hash: '#/' }, navigator: { userAgent: 'vitest' } })
    vi.stubGlobal('localStorage', localStorageMock)
    currentRoute.value = { path: '/', meta: { public: true } }
  })

  it('restores the last protected app route for authenticated startup', async () => {
    electronAPI.bootstrapAuthToken.mockResolvedValue('tok-123')

    const pinia = createPinia()
    setActivePinia(pinia)
    const { prepareAuthRoute } = await import('@/auth/bootstrap')
    const { clearTokenStorage } = await import('@/utils/secureToken')
    clearTokenStorage()

    const routed = await prepareAuthRoute(pinia)

    expect(routed).toBe(true)
    expect(replaceMock).toHaveBeenCalledWith('/app/settings')
  })

  it('keeps anonymous startup on landing even if a last route exists', async () => {
    electronAPI.bootstrapAuthToken.mockResolvedValue('')

    const pinia = createPinia()
    setActivePinia(pinia)
    const { prepareAuthRoute } = await import('@/auth/bootstrap')
    const { clearTokenStorage } = await import('@/utils/secureToken')
    clearTokenStorage()

    const routed = await prepareAuthRoute(pinia)

    expect(routed).toBe(false)
    expect(replaceMock).not.toHaveBeenCalled()
  })
})
