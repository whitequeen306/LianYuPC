import axios from 'axios'
import { ElMessage } from 'element-plus'
import { apiBasePath, ensureApiOriginReady, isElectronRuntime } from '@/utils/runtime'
import { extractApiError, humanizeError } from '@/utils/errorMessage'
import { readToken, clearTokenStorage } from '@/utils/secureToken'
import { applyOutputLanguageHeaders } from '@/utils/outputLanguageHeader'
import { electronMainProcessAdapter, shouldUseMainProcessAdapter } from '@/api/electronAdapter'

/** Placeholder until initElectronRuntimeConfig resolves real apiOrigin */
const ELECTRON_BASE_PLACEHOLDER = 'http://127.0.0.1:0'

const EXPIRED_ERR = new Error('登录已过期，请重新登录')
/** #15：去重锁——N 个并发 401 只清一次登录态、只跳一次路由，避免 fanout 竞态 */
let isExpiring = false
/** #15：单飞刷新 promise——并发 401 共享同一次刷新，不重复打 refresh */
let refreshInFlight = null

function rejectSessionExpired() {
  if (isExpiring) return Promise.reject(EXPIRED_ERR)
  isExpiring = true
  refreshInFlight = null
  clearTokenStorage()
  void import('@/stores/user').then(({ useUserStore }) => {
    void useUserStore().clearAuth({ keepUsername: true }).finally(() => {
      // 留窗口让并发 401 走 dedup 分支，之后复位以便重新登录后能再次触发
      setTimeout(() => { isExpiring = false }, 1500)
    })
  })
  const hash = window.location.hash || ''
  if (!hash.includes('#/login') && !hash.includes('#/register')) {
    window.location.hash = '#/'
  }
  return Promise.reject(EXPIRED_ERR)
}

/** #15：单飞刷新——所有并发 401 共享同一 promise；完成后清空以便下次 401 可再次刷新 */
function singleFlightRefresh() {
  if (!refreshInFlight) {
    refreshInFlight = import('@/api/auth')
      .then(({ refreshAuthToken }) => refreshAuthToken())
      .finally(() => { refreshInFlight = null })
  }
  return refreshInFlight
}

/**
 * #15：401 处理——区分「token 真过期」与「单请求 401」：
 *  - skipAuthRefresh：刷新请求自身 401 → token 真过期 → 登出（不递归）
 *  - _retried：刷新成功后重放仍 401 → 业务级拒签（token 有效）→ 仅拒绝本请求，不连坐登出
 *  - 否则：单飞刷新，成功则重放原请求一次；刷新失败（含 401）→ 登出
 */
function handle401(config) {
  if (config?.skipAuthRefresh) return rejectSessionExpired()
  if (config?._retried) return Promise.reject(EXPIRED_ERR)
  return singleFlightRefresh()
    .catch(() => rejectSessionExpired())
    .then(() => http.request({ ...config, _retried: true }))
}

const http = axios.create({
  baseURL: isElectronRuntime() ? `${ELECTRON_BASE_PLACEHOLDER}/api` : apiBasePath(),
  timeout: 60000,
  headers: { 'Content-Type': 'application/json' }
})

// Request interceptor — async inject encrypted Sa-Token
http.interceptors.request.use(async config => {
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

// Response interceptor — unwrap Result<T>, handle errors
http.interceptors.response.use(
  response => {
    const body = response.data
    if (body && typeof body.code === 'number') {
      if (body.code === 200) {
        return body.data
      }
      if (body.code === 401) {
        return handle401(response.config)
      }
      const msg = humanizeError(body.message, '请求失败，请稍后再试')
      if (response.config?.skipGlobalError !== true) {
        ElMessage.error(msg)
      }
      return Promise.reject(new Error(msg))
    }
    return body
  },
  error => {
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
    const skipToast = error.config?.skipGlobalError === true
    if (!skipToast && error.response?.status !== 401) {
      ElMessage.error(msg)
    }
    return Promise.reject(new Error(msg))
  }
)

export default http
