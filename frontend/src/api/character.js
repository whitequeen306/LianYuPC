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

export function getCustomVoice(id) {
  return http.get(`/character/${id}/custom-voice`)
}

export function upsertCustomVoice(id, {
  provider, audio, apiKey, refText, endpoint, httpModel, realtimeModel,
}) {
  const formData = new FormData()
  formData.append('provider', provider)
  formData.append('audio', audio)
  if (apiKey) formData.append('apiKey', apiKey)
  if (refText) formData.append('refText', refText)
  if (endpoint) formData.append('endpoint', endpoint)
  if (httpModel) formData.append('httpModel', httpModel)
  if (realtimeModel) formData.append('realtimeModel', realtimeModel)
  return http.post(`/character/${id}/custom-voice`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 180000,
  })
}

export function deleteCustomVoice(id) {
  return http.delete(`/character/${id}/custom-voice`)
}
