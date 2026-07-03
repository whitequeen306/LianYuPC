import http from './httpCore'

export function listCharacterSquareTemplates(params = {}) {
  return http.get('/character/square', { params })
}

export function fetchSquareTemplateDetail(templateId) {
  return http.get(`/character/square/${templateId}`)
}

export function addCharacterFromSquare(templateId, data) {
  return http.post(`/character/square/${templateId}/add`, data)
}

export function toggleSquareLike(templateId) {
  return http.post(`/character/square/${templateId}/like`)
}

export function fetchSquareComments(templateId) {
  return http.get(`/character/square/${templateId}/comments`)
}

export function fetchSquareCommentsBatch(templateIds = []) {
  const ids = [...new Set(templateIds.filter(Boolean))]
  if (!ids.length) return Promise.resolve({})
  return http.get('/character/square/comments', { params: { ids: ids.join(',') } })
}

export function postSquareComment(templateId, content) {
  return http.post(`/character/square/${templateId}/comment`, { content })
}

export function deleteSquareComment(templateId) {
  return http.delete(`/character/square/${templateId}/comment`)
}
