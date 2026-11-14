// 予念云端对话 API 的最小客户端。
//
// 网关自己不存任何对话内容：上下文、记忆、关系状态全在云端（MySQL/Milvus），
// 这里只做三件事——找到这条关系的会话、把用户的话送进去、把回复拿出来。
// 这也是「换通道不丢记忆」的前提：记忆的键是 (userId, characterId)，不是 QQ 号。

import https from 'node:https';
import http from 'node:http';
import { URL } from 'node:url';

const DEFAULT_TIMEOUT_MS = 180_000;

function request(urlString, { method = 'GET', headers = {}, body = null, timeoutMs = DEFAULT_TIMEOUT_MS, insecureTls = false } = {}) {
  const url = new URL(urlString);
  const isHttps = url.protocol === 'https:';
  const transport = isHttps ? https : http;
  const payload = body === null ? null : Buffer.from(JSON.stringify(body), 'utf8');

  const options = {
    method,
    hostname: url.hostname,
    port: url.port || (isHttps ? 443 : 80),
    path: `${url.pathname}${url.search}`,
    headers: {
      Accept: 'application/json',
      ...(payload ? { 'Content-Type': 'application/json', 'Content-Length': payload.length } : {}),
      ...headers,
    },
    // 云端用的是自签证书；网关跑在内网，按 host 白名单 + 显式开关放行，
    // 不在这里做证书 pin（那是客户端的事，见 frontend 的 performApiRequest）。
    ...(isHttps && insecureTls ? { rejectUnauthorized: false } : {}),
  };

  return new Promise((resolve, reject) => {
    const req = transport.request(options, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        const raw = Buffer.concat(chunks).toString('utf8');
        let parsed = null;
        try {
          parsed = raw ? JSON.parse(raw) : null;
        } catch {
          parsed = null;
        }
        resolve({ status: res.statusCode, raw, json: parsed });
      });
    });
    req.setTimeout(timeoutMs, () => req.destroy(new Error(`timeout_${timeoutMs}ms`)));
    req.on('error', reject);
    if (payload) req.write(payload);
    req.end();
  });
}

export function createLianyuClient({
  apiOrigin,
  provider,
  model,
  timeoutMs = DEFAULT_TIMEOUT_MS,
  insecureTls = false,
}) {
  if (!apiOrigin) throw new Error('api_origin_required');
  const origin = apiOrigin.replace(/\/+$/, '');

  const call = async (path, { method, body, token } = {}) => {
    if (!token) throw new Error('lianyu_token_required');
    const res = await request(`${origin}${path}`, {
      method,
      body,
      token,
      timeoutMs,
      insecureTls,
      headers: { 'lianyu-token': token },
    });
    const json = res.json;
    if (!json) throw new Error(`bad_response_${res.status}`);
    if (json.code !== 200) throw new Error(`api_${json.code}:${json.message || ''}`);
    return json.data;
  };

  return {
    origin,

    listConversations: (token) => call('/api/conversation', { token }),

    // 同一个角色只应有一条 SINGLE 会话；找不到再建，避免每次消息都新开一条关系。
    async ensureConversation(token, characterId, title = '') {
      const list = await call('/api/conversation', { token });
      const hit = (list || []).find((c) => Number(c.characterId) === Number(characterId));
      if (hit) return hit;
      return call('/api/conversation', {
        method: 'POST',
        token,
        body: { characterId: Number(characterId), mode: 'SINGLE', title: title || undefined },
      });
    },

    // 非流式：网关要的是「一整句回复」，分段与节奏由网关自己决定。
    // （流式接口是 SSE，客户端要做帧解析；这里先用 /messages 换可读性。）
    async sendText(token, conversationId, content) {
      const body = { provider, model, content };
      return call(`/api/conversation/${conversationId}/messages`, { method: 'POST', token, body });
    },

    async listMessages(token, conversationId, limit = 20) {
      return call(`/api/conversation/${conversationId}/messages?limit=${limit}`, { token });
    },

    async listCharacters(token) {
      return call('/api/character', { token });
    },
  };
}
