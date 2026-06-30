import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

const mocks = vi.hoisted(() => ({
  refreshAuthToken: vi.fn(),
  clearAuth: vi.fn(),
  clearTokenStorage: vi.fn(),
  readToken: vi.fn(),
  storeToken: vi.fn(),
  syncSetTokenCache: vi.fn(),
  ElMessage: { error: vi.fn() }
}))

vi.mock('element-plus', () => ({ ElMessage: mocks.ElMessage }))
vi.mock('@/utils/runtime', () => ({
  apiBasePath: () => 'http://127.0.0.1:8080/api',
  ensureApiOriginReady: () => Promise.resolve(),
  isElectronRuntime: () => false
}))
vi.mock('@/utils/errorMessage', () => ({
  extractApiError: () => ({ message: 'err' }),
  humanizeError: (_e, fb) => fb
}))
vi.mock('@/utils/secureToken', () => ({
  readToken: mocks.readToken,
  clearTokenStorage: mocks.clearTokenStorage,
  syncSetTokenCache: mocks.syncSetTokenCache,
  storeToken: mocks.storeToken
}))
vi.mock('@/utils/outputLanguageHeader', () => ({ applyOutputLanguageHeaders: h => h }))
vi.mock('@/api/electronAdapter', () => ({
  electronMainProcessAdapter: vi.fn(),
  shouldUseMainProcessAdapter: () => false
}))
vi.mock('@/api/auth', () => ({ refreshAuthToken: mocks.refreshAuthToken }))
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ clearAuth: mocks.clearAuth }) }))

// #15 回归：验证 401 三态机——刷新成功重放、刷新失败登出、刷新成功但重放仍 401 不连坐登出。
describe('session refresh (#15)', () => {
  let http
  let savedWindow

  function adapterWith (responses) {
    let i = 0
    return (config) => {
      const r = responses[Math.min(i, responses.length - 1)]
      i += 1
      return Promise.resolve({
        data: r.data,
        status: r.status || 200,
        statusText: 'OK',
        headers: {},
        config,
        request: {}
      })
    }
  }

  beforeEach(async () => {
    vi.clearAllMocks()
    mocks.readToken.mockResolvedValue('token-abc')
    mocks.clearAuth.mockResolvedValue(undefined)
    savedWindow = globalThis.window
    globalThis.window = { location: { hash: '' } }
    vi.resetModules()
    const mod = await import('@/api/index')
    http = mod.default
  })

  afterEach(() => {
    globalThis.window = savedWindow
  })

  it('401 -> refresh ok -> replay ok resolves, no logout', async () => {
    mocks.refreshAuthToken.mockResolvedValueOnce({ token: 'new' })
    const adapter = adapterWith([
      { data: { code: 401, message: 'expired' } },
      { data: { code: 200, data: 'OK' } }
    ])
    const result = await http.get('/x', { adapter })
    expect(result).toBe('OK')
    expect(mocks.refreshAuthToken).toHaveBeenCalledTimes(1)
    expect(mocks.clearTokenStorage).not.toHaveBeenCalled()
  })

  it('401 -> refresh fails -> dedup logout', async () => {
    mocks.refreshAuthToken.mockRejectedValueOnce(new Error('refresh 401'))
    const adapter = adapterWith([{ data: { code: 401, message: 'expired' } }])
    await expect(http.get('/x', { adapter })).rejects.toThrow('登录已过期')
    expect(mocks.clearTokenStorage).toHaveBeenCalledTimes(1)
    expect(mocks.refreshAuthToken).toHaveBeenCalledTimes(1)
    expect(globalThis.window.location.hash).toBe('#/')
  })

  it('401 -> refresh ok -> replay 401 -> no cascade logout', async () => {
    mocks.refreshAuthToken.mockResolvedValueOnce({ token: 'new' })
    const adapter = adapterWith([
      { data: { code: 401, message: 'expired' } },
      { data: { code: 401, message: 'business 401' } }
    ])
    await expect(http.get('/x', { adapter })).rejects.toThrow('登录已过期')
    expect(mocks.refreshAuthToken).toHaveBeenCalledTimes(1)
    expect(mocks.clearTokenStorage).not.toHaveBeenCalled()
    expect(globalThis.window.location.hash).toBe('')
  })
})
