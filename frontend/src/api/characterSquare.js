import http from './index'

export function listCharacterSquareTemplates(params = {}) {
  return http.get('/character/square', { params })
}

export function addCharacterFromSquare(templateId) {
  return http.post(`/character/square/${templateId}/add`)
}
