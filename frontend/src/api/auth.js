import http from './index'

export function getCaptcha() {
  return http.get('/auth/captcha')
}

export function register(data) {
  return http.post('/auth/register', data)
}

export function login(data) {
  return http.post('/auth/login', data)
}

export function logout() {
  return http.post('/auth/logout')
}

export function getProfile() {
  return http.get('/auth/me')
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
