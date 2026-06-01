import axios from 'axios'
import { ElMessage } from 'element-plus'

const http = axios.create({
  baseURL: '/api',
  timeout: 30000,
  headers: { 'Content-Type': 'application/json' }
})

// Request interceptor — inject Sa-Token
http.interceptors.request.use(config => {
  const token = localStorage.getItem('lianyu-token')
  if (token) {
    config.headers['lianyu-token'] = token
  }
  const traceId = generateTraceId()
  config.headers['X-Trace-Id'] = traceId
  return config
})

// Response interceptor — unwrap Result<T>, handle errors
http.interceptors.response.use(
  response => {
    const body = response.data
    // If response follows Result<T> format
    if (body && typeof body.code === 'number') {
      if (body.code === 200) {
        return body.data
      }
      // Token expired
      if (body.code === 401) {
        clearSessionAndGoLanding()
        return Promise.reject(new Error(body.message || '未登录'))
      }
      ElMessage.error(body.message || '请求失败')
      return Promise.reject(new Error(body.message))
    }
    // Non-Result response
    return body
  },
  error => {
    if (error.response?.status === 401) {
      clearSessionAndGoLanding()
      return Promise.reject(new Error('未登录'))
    }
    const msg = error.response?.data?.message || error.message || '网络异常'
    if (error.response?.status !== 401) {
      ElMessage.error(msg)
    }
    return Promise.reject(error)
  }
)

function generateTraceId() {
  return crypto.randomUUID ? crypto.randomUUID().replace(/-/g, '') : Date.now().toString(36)
}

/** 未登录/登录过期：回营销首页，而非强制打开登录页 */
function clearSessionAndGoLanding() {
  localStorage.removeItem('lianyu-token')
  localStorage.removeItem('lianyu-token-name')
  const hash = window.location.hash || ''
  if (!hash.includes('#/login') && !hash.includes('#/register')) {
    window.location.hash = '#/'
  }
}

export default http
