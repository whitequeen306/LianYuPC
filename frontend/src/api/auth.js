import http from './httpCore'
import { storeToken, clearTokenStorage } from '@/utils/secureToken'
import { getElectronAPI } from '@/utils/electron'

export function getCaptcha() {
  return http.get('/auth/captcha', { skipGlobalError: true })
}

export function register(data) {
  return http.post('/auth/register', data, { timeout: 30000 })
}

export async function login(data) {
  const result = await http.post('/auth/login', data, { timeout: 30000 })
  if (result?.token) {
    await storeToken(result.token)
  }
  return result
}

export async function logout() {
  try { await http.post('/auth/logout') } catch { /* ignore */ }
  clearTokenStorage()
}

/**
 * #15：滑动续签——刷新当前会话的绝对过期时间（后端 renewTimeout）。
 * 标记 skipAuthRefresh 防止响应拦截器在刷新自身 401 时递归刷新；skipGlobalError 静默失败
 * （由单飞逻辑统一处理，不在右下角弹错）。
 */
export async function refreshAuthToken() {
  const result = await http.post('/auth/refresh', {}, { skipAuthRefresh: true, skipGlobalError: true })
  if (result?.token) {
    await storeToken(result.token)
    // 同步主进程 auth-session.bin：apiProxy 转发请求时 strip 渲染层 header、
    // 从 auth-session.bin 重新注入 token，若不同步会导致旧 token → 401 → 误判掉线。
    try { await getElectronAPI()?.updateAuthToken?.(result.token) } catch { /* 非 Electron 或无 session，忽略 */ }
  }
  return result
}

export function getProfile(config = {}) {
  return http.get('/auth/me', config)
}

export function updateProfile(data) {
  return http.put('/auth/me', data)
}

export function uploadProfileAvatar(file) {
  const formData = new FormData()
  formData.append('file', file)
  return http.post('/auth/me/avatar', formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

export function changePassword(data) {
  return http.put('/auth/me/password', data)
}
