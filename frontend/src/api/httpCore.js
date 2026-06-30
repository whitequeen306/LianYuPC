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

const http = axios.create({
  baseURL: isElectronRuntime() ? `${ELECTRON_BASE_PLACEHOLDER}/api` : apiBasePath(),
  timeout: 60000,
  headers: { 'Content-Type': 'application/json' },
})

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
        return rejectSessionExpired()
      }
      const msg = humanizeError(body.message, '请求失败，请稍后再试')
      return Promise.reject(new Error(msg))
    }
    return body
  },
  (error) => {
    if (error.response?.status === 401) {
      return rejectSessionExpired()
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
    return Promise.reject(new Error(msg))
  },
)

export default http
