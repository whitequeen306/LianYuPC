import http from './httpCore'
import { storeToken, clearTokenStorage } from '@/utils/secureToken'

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
