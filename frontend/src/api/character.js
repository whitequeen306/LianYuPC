import http from './httpCore'

export function listCharacters(options = {}) {
  return http.get('/character', {
    skipGlobalError: options.silent === true
  })
}

export function getCharacter(id) {
  return http.get(`/character/${id}`)
}

export function createCharacter(data) {
  return http.post('/character', data)
}

export function updateCharacter(id, data) {
  return http.put(`/character/${id}`, data)
}

export function deleteCharacter(id) {
  return http.delete(`/character/${id}`)
}

export function uploadAvatar(id, file) {
  const formData = new FormData()
  formData.append('file', file)
  return http.post(`/character/${id}/avatar`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

export function uploadChatBackground(id, file) {
  const formData = new FormData()
  formData.append('file', file)
  return http.post(`/character/${id}/chat-background`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

export function generateCharacter(data) {
  return http.post('/character/generate', data, { timeout: 120000 })
}
