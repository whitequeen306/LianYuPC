import http from './httpCore'

/** 生成一个 6 位绑定码，返回后再去 QQ 里发给机器人。 */
export function issueQqBindCode(characterId) {
  return http.post('/qq/bind-code', { characterId })
}

export function listQqBindings(options = {}) {
  return http.get('/qq/bindings', { skipGlobalError: options.silent === true })
}

export function unbindQq(id) {
  return http.delete(`/qq/bindings/${id}`)
}
