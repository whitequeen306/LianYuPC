import http from './httpCore'

export function fetchModels(provider) {
  return http.get('/ai/models', { params: { provider }, skipGlobalError: true })
}

export function chat(data) {
  return http.post('/ai/chat', data)
}

export function listVaults() {
  return http.get('/ai/vault')
}

export function getVault(provider) {
  return http.get(`/ai/vault/${provider}`)
}

export function createVault(data) {
  return http.post('/ai/vault', data)
}

export function updateVault(id, data) {
  return http.put(`/ai/vault/${id}`, data)
}

export function deleteVault(id) {
  return http.delete(`/ai/vault/${id}`)
}
