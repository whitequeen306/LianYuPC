import http from './httpCore'

export function listMemories(characterId) {
  return http.get('/memory', { params: characterId ? { characterId } : {} })
}

export function listEpisodes(characterId, limit = 50) {
  const params = { limit }
  if (characterId) params.characterId = characterId
  return http.get('/memory/episodes', { params })
}

export function getMemory(id) {
  return http.get(`/memory/${id}`)
}

export function setMemoryPinned(id, pinned) {
  return http.patch(`/memory/${id}/pin`, { pinned: Boolean(pinned) })
}

export function reviveMemory(id) {
  return request.post(`/memory/${id}/revive`)
}

export function setMemoryMuted(id, muted) {
  return http.patch(`/memory/${id}/mute`, { muted: Boolean(muted) })
}

export function deleteMemory(id) {
  return http.delete(`/memory/${id}`)
}
