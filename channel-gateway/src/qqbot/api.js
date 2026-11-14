// QQ 官方机器人 OpenAPI 的最小封装（对齐官方文档 v2，2026-11 版）。
//
// 按 bot.q.qq.com/wiki 的口径：
//   - 统一请求地址 https://api.bot.qq.com（旧的 api.sgroup.qq.com 不再作为默认）
//   - 取凭证 POST /app/getAppAccessToken {appId, clientSecret} → {access_token, expires_in}
//     **失败也是 HTTP 200**，只能在响应体的 code 上判断（100001/100007/100016/10004）
//   - 调用凭证放请求头 Authorization: QQBot <access_token>
//   - 业务失败体是 {err_code, message, trace_id}；成功体直接是业务数据
//
// 腾讯在这条链上只是快递员：内容从哪来、记不记得用户，全在我们自己的库里。

const DEFAULT_API_BASE = 'https://api.bot.qq.com';

// 官方：有效期 7200s；到期前 60s 内重新获取会拿到新值、旧值仍有 60s 缓冲。
// 我们提前 5 分钟换，避免正卡在边界上被 401 打断一轮对话。
const EXPIRY_MARGIN_MS = 5 * 60_000;

export function createQqBotApi({ appId, clientSecret, apiBase = DEFAULT_API_BASE, fetchImpl = fetch, now = () => Date.now() }) {
  if (!appId) throw new Error('app_id_required');
  if (!clientSecret) throw new Error('client_secret_required');
  const base = apiBase.replace(/\/+$/, '');

  let cached = null; // { token, expiresAt }

  async function accessToken({ force = false } = {}) {
    if (!force && cached && cached.expiresAt - EXPIRY_MARGIN_MS > now()) return cached.token;

    const res = await fetchImpl(`${base}/app/getAppAccessToken`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ appId: String(appId), clientSecret }),
    });
    const body = (await res.json().catch(() => null)) || {};

    // 关键：失败时 HTTP 仍是 200，必须看 body。
    if (!body.access_token) {
      throw new Error(`token_rejected_${body.code ?? res.status}:${body.message || ''}`);
    }
    // expires_in 文档写 number、示例给字符串 "7200"，两种都吃。
    const ttl = Number(body.expires_in ?? 7200) * 1000;
    cached = { token: body.access_token, expiresAt: now() + (Number.isFinite(ttl) && ttl > 0 ? ttl : 7_200_000) };
    return cached.token;
  }

  async function post(path, payload) {
    for (let attempt = 0; attempt < 2; attempt += 1) {
      const token = await accessToken({ force: attempt > 0 });
      const res = await fetchImpl(`${base}${path}`, {
        method: 'POST',
        headers: {
          Authorization: `QQBot ${token}`,
          'Content-Type': 'application/json; charset=utf-8',
        },
        body: JSON.stringify(payload),
      });
      const text = await res.text();
      let body = null;
      try {
        body = text ? JSON.parse(text) : null;
      } catch {
        body = null;
      }

      // 401 是凭证问题：换一次 token 再试。
      if (res.status === 401 && attempt === 0) continue;

      if (!res.ok) {
        throw new Error(`send_http_${res.status}:${(body && (body.err_code ?? body.code)) || ''}`);
      }
      // 200 也可能是业务失败（err_code 非 0）。
      if (body && body.err_code) {
        const err = new Error(`send_rejected_${body.err_code}:${body.message || ''}`);
        err.errCode = body.err_code;
        err.traceId = body.trace_id;
        throw err;
      }
      return body;
    }
    throw new Error('send_unreachable');
  }

  return {
    accessToken,

    // 单聊：被动回复必须带 msg_id（取事件的 d.id，官方口径 5 分钟内有效）。
    // 同一条用户消息最多回 4 次，靠递增 msg_seq；相同 msg_id + msg_seq 重发会被去重失败。
    sendC2C(openid, content, { msgId, msgSeq = 1, eventId } = {}) {
      const payload = { content, msg_type: 0, msg_seq: msgSeq };
      if (msgId) payload.msg_id = msgId;
      else if (eventId) payload.event_id = eventId;
      return post(`/v2/users/${encodeURIComponent(openid)}/messages`, payload);
    },

    // 群聊：openid 是群维度的 group_openid，不是群号；被动窗口 5 分钟、最多 5 次。
    sendGroup(groupOpenid, content, { msgId, msgSeq = 1, eventId } = {}) {
      const payload = { content, msg_type: 0, msg_seq: msgSeq };
      if (msgId) payload.msg_id = msgId;
      else if (eventId) payload.event_id = eventId;
      return post(`/v2/groups/${encodeURIComponent(groupOpenid)}/messages`, payload);
    },
  };
}
