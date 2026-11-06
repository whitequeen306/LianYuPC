import http from './httpCore'

export function listMemories(characterId) {
  return http.get('/memory', { params: characterId ? { characterId } : {} })
}

export function getMemory(id) {
  return http.get(`/memory/${id}`)
}

export function setMemoryPinned(id, pinned) {
  return http.patch(`/memory/${id}/pin`, { pinned: Boolean(pinned) })
}

export function deleteMemory(id) {
  return http.delete(`/memory/${id}`)
}
