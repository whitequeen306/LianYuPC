// 网关 → 予念后端的内部通道。
//
// 这条通道不带用户凭据：只带 channel + externalId（QQ openid），由后端自己查
// 「这个 QQ 号是谁、聊哪个角色、会话 id 是多少」。网关因此退化成一条哑管道，
// 被攻破也拿不到任何用户 token。
//
// 鉴权是 HMAC：ts + nonce + method + path + sha256(body)，与 Java 侧
// InternalSignatureFilter 同一口径。nonce 让重放失效，时间窗 5 分钟。

import crypto from 'node:crypto';
import http from 'node:http';
import https from 'node:https';

export function signHeaders({ secret, method, path, body = '', timestamp = Date.now(), nonce }) {
  const nonceValue = nonce || crypto.randomBytes(8).toString('hex');
  const bodyBytes = Buffer.isBuffer(body) ? body : Buffer.from(body ?? '', 'utf8');
  const bodyHash = crypto.createHash('sha256').update(bodyBytes).digest('hex');
  const canonical = [String(timestamp), nonceValue, String(method).toUpperCase(), path, bodyHash].join('\n');
  const sign = crypto.createHmac('sha256', secret).update(canonical).digest('hex');
  return {
    'x-qq-ts': String(timestamp),
    'x-qq-nonce': nonceValue,
    'x-qq-sign': sign,
  };
}

function requestJson({ origin, path, method, body, headers, timeoutMs, insecureTls }) {
  const url = new URL(path, origin);
  const transport = url.protocol === 'https:' ? https : http;
  const payload = body == null ? null : Buffer.from(JSON.stringify(body), 'utf8');
  const options = {
    method,
    hostname: url.hostname,
    port: url.port || (url.protocol === 'https:' ? 443 : 80),
    path: `${url.pathname}${url.search}`,
    headers: {
      accept: 'application/json',
      ...(payload ? { 'content-type': 'application/json', 'content-length': String(payload.length) } : {}),
      ...headers,
    },
  };
  if (url.protocol === 'https:' && insecureTls) {
    options.rejectUnauthorized = false;
  }

  return new Promise((resolve, reject) => {
    const req = transport.request(options, (res) => {
      const chunks = [];
      res.on('data', (chunk) => chunks.push(chunk));
      res.on('end', () => {
        const text = Buffer.concat(chunks).toString('utf8');
        let parsed = null;
        try {
          parsed = text ? JSON.parse(text) : null;
        } catch {
          parsed = null;
        }
        resolve({ status: res.statusCode, body: parsed, text });
      });
    });
    req.setTimeout(timeoutMs, () => req.destroy(new Error(`timeout_after_${timeoutMs}ms`)));
    req.on('error', reject);
    if (payload) req.write(payload);
    req.end();
  });
}

function toError({ status, body, text }, path) {
  const code = body && typeof body.code === 'number' ? body.code : status;
  const message = (body && body.message) || text || `http_${status}`;
  const err = new Error(String(message));
  err.code = code;
  err.status = status;
  err.path = path;
  return err;
}

export function createBackendClient({
  apiOrigin,
  secret,
  timeoutMs = 200000,
  insecureTls = false,
  now = () => Date.now(),
}) {
  if (!apiOrigin) throw new Error('backend apiOrigin required');
  if (!secret) throw new Error('backend secret required');

  async function call(path, body) {
    const raw = JSON.stringify(body);
    const headers = signHeaders({ secret, method: 'POST', path, body: raw, timestamp: now() });
    const res = await requestJson({
      origin: apiOrigin,
      path,
      method: 'POST',
      body,
      headers,
      timeoutMs,
      insecureTls,
    });
    if (res.status !== 200 || !res.body || res.body.code !== 200) {
      throw toError(res, path);
    }
    return res.body.data;
  }

  return {
    origin: apiOrigin,

    /** 跑一个回合：后端负责解析身份、选模型、落库、返回要发回 QQ 的文本。 */
    async turn({ channel, externalId, text }) {
      const data = await call('/internal/qq/turn', { channel, externalId, text });
      return { reply: String((data && data.reply) || ''), conversationId: data && data.conversationId };
    },

    /** 用绑定码把外部身份绑到角色上。 */
    async bind({ channel, externalId, code }) {
      return call('/internal/qq/bind', { channel, externalId, code });
    },
  };
}
