import http from './httpCore'

function requestConfig(options = {}) {
  return options.silent === true ? { skipGlobalError: true } : {}
}

export function getCharacterState(characterId, options = {}) {
  return http.get(`/character-state/${characterId}/state`, requestConfig(options))
}

export function listCharacterStates(options = {}) {
  return http.get('/character-state/states', requestConfig(options))
}

export function listCharacterDiaries(characterId, params = {}, options = {}) {
  return http.get(`/character-state/${characterId}/diary`, { params, ...requestConfig(options) })
}

export function getDiary(diaryId, options = {}) {
  return http.get(`/character-state/diary/${diaryId}`, requestConfig(options))
}

export function listAllDiaries(params = {}, options = {}) {
  return http.get('/character-state/diaries', { params, ...requestConfig(options) })
}