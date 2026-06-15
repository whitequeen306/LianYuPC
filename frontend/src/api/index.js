import axios from 'axios'
import { ElMessage } from 'element-plus'
import { apiBasePath } from '@/utils/runtime'
import { extractApiError, humanizeError } from '@/utils/errorMessage'
import { readToken, clearTokenStorage } from '@/utils/secureToken'
import { applyOutputLanguageHeaders } from '@/utils/outputLanguageHeader'

const http = axios.create({
  baseURL: apiBasePath(),
  timeout: 60000,
  headers: { 'Content-Type': 'application/json' }
})

// Request interceptor — async inject encrypted Sa-Token
http.interceptors.request.use(async config => {
  const token = await readToken()
  if (token) {
    config.headers['lianyu-token'] = token
  }
  const traceId = crypto.randomUUID ? crypto.randomUUID().replace(/-/g, '') : Date.now().toString(36)
  config.headers['X-Trace-Id'] = traceId
  config.headers = applyOutputLanguageHeaders(config.headers)
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
        clearTokenStorage()
        void import('@/stores/user').then(({ useUserStore }) => {
          void useUserStore().clearAuth({ keepUsername: true })
        })
        const hash = window.location.hash || ''
        if (!hash.includes('#/login') && !hash.includes('#/register')) {
          window.location.hash = '#/'
        }
        return Promise.reject(new Error('登录已过期，请重新登录'))
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
      clearTokenStorage()
      void import('@/stores/user').then(({ useUserStore }) => {
        void useUserStore().clearAuth({ keepUsername: true })
      })
      const hash = window.location.hash || ''
      if (!hash.includes('#/login') && !hash.includes('#/register')) {
        window.location.hash = '#/'
      }
      return Promise.reject(new Error('登录已过期，请重新登录'))
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
