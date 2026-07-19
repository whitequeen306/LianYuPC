import axios from 'axios'
import { apiBasePath, ensureApiOriginReady, isElectronRuntime } from '@/utils/runtime'
import { extractApiError, humanizeError } from '@/utils/errorMessage'
import { readToken, clearTokenStorage } from '@/utils/secureToken'
import { applyOutputLanguageHeaders } from '@/utils/outputLanguageHeader'
import { electronMainProcessAdapter, shouldUseMainProcessAdapter } from '@/api/electronAdapter'

/** Placeholder until initElectronRuntimeConfig resolves real apiOrigin */
const ELECTRON_BASE_PLACEHOLDER = 'http://127.0.0.1:0'

function isAuxRendererSurface() {
  if (typeof window === 'undefined') return false
  if (window.__lianyuAuxSurface === 'launcher' || window.__lianyuAuxSurface === 'quick') {
    return true
  }
  const path = String(window.location.pathname || '')
  if (/launcher\.html$/i.test(path)) return true
  if (/quick\.html$/i.test(path)) return true
  const hash = (window.location.hash.replace(/^#/, '') || '/').split('?')[0]
  return hash === '/launcher' || hash.startsWith('/launcher/') || hash.startsWith('/quick/')
}

function rejectSessionExpired() {
  clearTokenStorage()
  void import('@/stores/user').then(({ useUserStore }) => {
    void useUserStore().clearAuth({ keepUsername: true })
  })
  const hash = window.location.hash || ''
  if (!hash.includes('#/login') && !hash.includes('#/register') && !isAuxRendererSurface()) {
    window.location.hash = '#/'
  }
  return Promise.reject(new Error('登录已过期，请重新登录'))
}

// #15 单飞刷新：多个并发 401 共享同一次 refresh，避免刷新风暴。
// refreshAuthToken 自身带 skipAuthRefresh 标记，不会递归触发。
let refreshPromise = null
function singleFlightRefresh() {
  if (!refreshPromise) {
    refreshPromise = (async () => {
      try {
        const { refreshAuthToken } = await import('@/api/auth')
        await refreshAuthToken()
        return true
      } catch {
        return false
      } finally {
        refreshPromise = null
      }
    })()
  }
  return refreshPromise
}

/**
 * 401 处理：先尝试单飞刷新 + 重放原请求，刷新失败才登出。
 * - skipAuthRefresh：刷新请求自身的 401 不再递归刷新，直接登出
 * - _authReplayed：已重放过的请求再 401 不二次刷新、不连坐登出
 *   （刷新已成功但此请求仍 401，可能是业务级权限问题，不应踢掉整个会话）
 */
async function handle401(failedConfig) {
  if (!failedConfig || failedConfig.skipAuthRefresh) {
    return rejectSessionExpired()
  }
  if (failedConfig._authReplayed) {
    // 重放仍 401：刷新已成功但此请求仍失败，不连坐登出
    return Promise.reject(new Error('登录已过期，请重新登录'))
  }
  const ok = await singleFlightRefresh()
  if (!ok) {
    return rejectSessionExpired()
  }
  // 刷新成功：用新 token 重放原请求（request interceptor 会重新读 token）
  return http({ ...failedConfig, _authReplayed: true })
}

const http = axios.create({
  baseURL: isElectronRuntime() ? `${ELECTRON_BASE_PLACEHOLDER}/api` : apiBasePath(),
  timeout: 60000,
  headers: { 'Content-Type': 'application/json' },
})

/** Keep axios config (e.g. skipGlobalError) on wrapped rejects so toast interceptor can honor it. */
export function toHttpError(message, config, extras = {}) {
  const err = new Error(message)
  if (config) err.config = config
  if (extras.response) err.response = extras.response
  if (extras.code != null) err.code = extras.code
  return err
}

http.interceptors.request.use(async (config) => {
  if (isElectronRuntime()) {
    await ensureApiOriginReady()
    config.baseURL = apiBasePath()
  }
  const token = await readToken()
  if (token) {
    config.headers['lianyu-token'] = token
  }
  const traceId = crypto.randomUUID ? crypto.randomUUID().replace(/-/g, '') : Date.now().toString(36)
  config.headers['X-Trace-Id'] = traceId
  config.headers = applyOutputLanguageHeaders(config.headers)
  if (shouldUseMainProcessAdapter(config)) {
    config.adapter = electronMainProcessAdapter
  }
  return config
})

http.interceptors.response.use(
  (response) => {
    const body = response.data
    if (body && typeof body.code === 'number') {
      if (body.code === 200) {
        return body.data
      }
      if (body.code === 401) {
        return handle401(response.config)
      }
      const msg = humanizeError(body.message, '请求失败，请稍后再试')
      return Promise.reject(toHttpError(msg, response.config, { response, code: body.code }))
    }
    return body
  },
  (error) => {
    if (error.response?.status === 401) {
      return handle401(error.config)
    }
    const apiErr = extractApiError(error)
    let fallback = '请求失败，请稍后再试'
    if (!error.response) {
      fallback = '无法连接服务器，请检查网络后重试'
    } else if (error.response.status >= 500) {
      fallback = '服务暂时不可用，请稍后再试'
    } else if (!apiErr?.message) {
      fallback = '请求失败，请稍后再试'
    }
    const msg = humanizeError(error, fallback)
    return Promise.reject(toHttpError(msg, error.config, {
      response: error.response,
      code: apiErr?.code,
    }))
  },
)

export default http
