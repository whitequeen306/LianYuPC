import http from './httpCore'

// ── 扫码连接（BYO）：用户用自己的 QQ 机器人，凭据只落库在后端 ──
// 三步：起任务拿二维码 → 用户手机 QQ 扫码（可在扫码页直接新建机器人）→ 轮询到 completed，
// 再把这个机器人绑到某个角色。扫码那一刻腾讯已经给了 openid，所以没有「发 6 位码」这一步。

/** 起一个扫码任务，返回 { taskId, qrUrl, ttlSeconds }。 */
export function startQqBotTask(source = 'yuni-pc') {
  return http.post('/qq/bot/task', { source })
}

/** 轮询扫码结果：{ status: pending|completed|expired|unknown, appId?, botName?, characterId? }。 */
export function pollQqBotTask(taskId) {
  return http.get(`/qq/bot/task/${taskId}`)
}

/** 我名下已连接的机器人。 */
export function listQqBotAccounts(options = {}) {
  return http.get('/qq/bot/accounts', { skipGlobalError: options.silent === true })
}

/** 把机器人绑到某个角色（同时建/复用会话）。 */
export function bindQqBotCharacter(appId, characterId) {
  return http.post(`/qq/bot/accounts/${appId}/bind`, { characterId })
}

/** 断开机器人：解绑 + 删除凭据。 */
export function removeQqBotAccount(appId) {
  return http.delete(`/qq/bot/accounts/${appId}`)
}

// ── 旧的 6 位绑定码入口（服务端仍在，页面已不再使用）──
export function issueQqBindCode(characterId) {
  return http.post('/qq/bind-code', { characterId })
}

export function listQqBindings(options = {}) {
  return http.get('/qq/bindings', { skipGlobalError: options.silent === true })
}

export function unbindQq(id) {
  return http.delete(`/qq/bindings/${id}`)
}
