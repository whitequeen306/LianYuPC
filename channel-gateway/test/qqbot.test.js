import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import http from 'node:http';

import { createQqBotApi } from '../src/qqbot/api.js';
import {
  INTENT_GROUP_AND_C2C,
  buildIdentify,
  buildResume,
  classifyClose,
  normalizeEvent,
  stripMention,
} from '../src/qqbot/gateway.js';
import {
  UNBOUND_HINT,
  channelOf,
  conversationKey,
  createQqBotHandler,
  looksLikeBindCode,
} from '../src/qqbot/handler.js';
import { createBackendClient, signHeaders } from '../src/qqbot/backend.js';

function jsonResponse(body, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
    text: async () => JSON.stringify(body),
  };
}

// 起一个真的 HTTP 服务器：签名是「网关 ↔ Java」之间的契约，
// 用假的 fetch 测不到 header 到底写成什么样。
async function startServer(respond) {
  const requests = [];
  const server = http.createServer((req, res) => {
    const chunks = [];
    req.on('data', (chunk) => chunks.push(chunk));
    req.on('end', () => {
      const raw = Buffer.concat(chunks).toString('utf8');
      const body = raw ? JSON.parse(raw) : null;
      requests.push({ url: req.url, method: req.method, headers: req.headers, body, raw });
      const { status = 200, payload } = respond({ url: req.url, body, headers: req.headers, raw });
      res.writeHead(status, { 'content-type': 'application/json' });
      res.end(JSON.stringify(payload));
    });
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const { port } = server.address();
  return {
    origin: `http://127.0.0.1:${port}`,
    requests,
    close: () => new Promise((resolve) => server.close(resolve)),
  };
}

function verifySignature(secret, request) {
  const bodyHash = crypto.createHash('sha256').update(request.raw ?? '').digest('hex');
  const canonical = [
    request.headers['x-qq-ts'],
    request.headers['x-qq-nonce'],
    request.method,
    request.url,
    bodyHash,
  ].join('\n');
  return crypto.createHmac('sha256', secret).update(canonical).digest('hex');
}

test('buildIdentify 用官方口径：token 前缀 QQBot、intents 1<<25、无分片 [0,1]', () => {
  const p = buildIdentify('tok');
  assert.equal(p.op, 2);
  assert.equal(p.d.token, 'QQBot tok');
  assert.equal(p.d.intents, INTENT_GROUP_AND_C2C);
  assert.equal(p.d.intents, 33554432);
  assert.deepEqual(p.d.shard, [0, 1]);
  assert.ok(p.d.properties.$os);
});

test('buildResume 带 session_id 与 seq', () => {
  const p = buildResume('tok', { sessionId: 's1', seq: 42 });
  assert.equal(p.op, 6);
  assert.equal(p.d.session_id, 's1');
  assert.equal(p.d.seq, 42);
  assert.equal(p.d.token, 'QQBot tok');
});

test('normalizeEvent：C2C 用 user_openid，群用 member_openid + group_openid', () => {
  const c2c = normalizeEvent('C2C_MESSAGE_CREATE', {
    id: 'ROBOT1.0_a',
    author: { user_openid: 'U1' },
    content: '你好',
    timestamp: '2026-07-21T10:00:00+08:00',
  });
  assert.equal(c2c.kind, 'c2c');
  assert.equal(c2c.openid, 'U1');
  assert.equal(c2c.msgId, 'ROBOT1.0_a');

  const group = normalizeEvent('GROUP_AT_MESSAGE_CREATE', {
    id: 'ROBOT1.0_b',
    author: { member_openid: 'M1' },
    group_openid: 'G1',
    content: '/今日天气',
  });
  assert.equal(group.kind, 'group');
  assert.equal(group.openid, 'M1');
  assert.equal(group.groupOpenid, 'G1');

  assert.equal(normalizeEvent('AT_MESSAGE_CREATE', { id: 'x' }), null);
  assert.equal(normalizeEvent('C2C_MESSAGE_CREATE', null), null);
});

test('stripMention 只剥开头提及', () => {
  assert.equal(stripMention('<@!123> 你好'), '你好');
  assert.equal(stripMention('你好 <@!123>'), '你好 <@!123>');
});

test('classifyClose：4009→resume，4006/4007→identify，4914/4915→fatal', () => {
  assert.equal(classifyClose(4009), 'resume');
  assert.equal(classifyClose(4006), 'identify');
  assert.equal(classifyClose(4007), 'identify');
  assert.equal(classifyClose(4914), 'fatal');
  assert.equal(classifyClose(4915), 'fatal');
  assert.equal(classifyClose(1006), 'identify');
});

test('accessToken：失败时 HTTP 200 也要按 body.code 报错', async () => {
  const api = createQqBotApi({
    appId: '123',
    clientSecret: 'sec',
    fetchImpl: async () => jsonResponse({ code: 100016, message: 'invalid appid or secret' }),
  });
  await assert.rejects(() => api.accessToken(), /token_rejected_100016/);
});

test('accessToken：expires_in 是字符串 "7200" 也能解析，且有效期内复用', async () => {
  let calls = 0;
  const api = createQqBotApi({
    appId: '123',
    clientSecret: 'sec',
    now: () => 0,
    fetchImpl: async () => {
      calls += 1;
      return jsonResponse({ access_token: 'T', expires_in: '7200' });
    },
  });
  assert.equal(await api.accessToken(), 'T');
  assert.equal(await api.accessToken(), 'T');
  assert.equal(calls, 1);
});

test('sendC2C 走 /v2/users/{openid}/messages，带 msg_type/msg_id/msg_seq', async () => {
  const seen = [];
  const api = createQqBotApi({
    appId: '123',
    clientSecret: 'sec',
    fetchImpl: async (url, init) => {
      seen.push({ url, init });
      if (url.endsWith('/app/getAppAccessToken')) return jsonResponse({ access_token: 'T', expires_in: 7200 });
      return jsonResponse({ id: 'ROBOT1.0_sent', timestamp: '2026-07-21T10:30:00+08:00' });
    },
  });

  await api.sendC2C('OPENID-1', '在呢', { msgId: 'ROBOT1.0_in', msgSeq: 2 });
  const send = seen.find((s) => s.url.includes('/messages'));
  assert.ok(send.url.endsWith('/v2/users/OPENID-1/messages'));
  assert.equal(send.init.headers.Authorization, 'QQBot T');
  assert.deepEqual(JSON.parse(send.init.body), {
    content: '在呢',
    msg_type: 0,
    msg_seq: 2,
    msg_id: 'ROBOT1.0_in',
  });
});

test('sendGroup 走 /v2/groups/{group_openid}/messages，业务失败按 err_code 抛', async () => {
  const api = createQqBotApi({
    appId: '123',
    clientSecret: 'sec',
    fetchImpl: async (url) => {
      if (url.endsWith('/app/getAppAccessToken')) return jsonResponse({ access_token: 'T', expires_in: 7200 });
      return jsonResponse({ err_code: 40054005, message: '消息被去重', trace_id: 'tr' });
    },
  });
  await assert.rejects(() => api.sendGroup('G1', 'hi', { msgId: 'm1' }), /send_rejected_40054005/);
});

test('conversationKey：群聊按「群 + 成员」隔离，不同群友不共用一个角色会话', () => {
  assert.equal(conversationKey({ kind: 'c2c', openid: 'U1' }), 'U1');
  assert.equal(conversationKey({ kind: 'group', openid: 'M1', groupOpenid: 'G1' }), 'G1:M1');
  assert.notEqual(
    conversationKey({ kind: 'group', openid: 'M1', groupOpenid: 'G1' }),
    conversationKey({ kind: 'group', openid: 'M2', groupOpenid: 'G1' }),
  );
});

test('channelOf / looksLikeBindCode：渠道名与绑定码判定', () => {
  assert.equal(channelOf({ kind: 'c2c' }), 'qqbot-c2c');
  assert.equal(channelOf({ kind: 'group' }), 'qqbot-group');
  assert.equal(looksLikeBindCode('123456'), true);
  assert.equal(looksLikeBindCode(' 12 34 56 '), true);
  assert.equal(looksLikeBindCode('12345'), false);
  assert.equal(looksLikeBindCode('今天天气不错'), false);
});

test('signHeaders：口径固定为 HMAC(ts\\nnonce\\nMETHOD\\npath\\nsha256(body))', () => {
  const secret = 's3cret';
  const headers = signHeaders({
    secret,
    method: 'POST',
    path: '/internal/qq/turn',
    body: '{"a":1}',
    timestamp: 1700000000000,
    nonce: 'abcd',
  });
  const bodyHash = crypto.createHash('sha256').update('{"a":1}').digest('hex');
  const canonical = `1700000000000\nabcd\nPOST\n/internal/qq/turn\n${bodyHash}`;
  const expected = crypto.createHmac('sha256', secret).update(canonical).digest('hex');
  assert.equal(headers['x-qq-sign'], expected);
  assert.equal(headers['x-qq-ts'], '1700000000000');
  assert.equal(headers['x-qq-nonce'], 'abcd');
});

test('backend.turn：打 /internal/qq/turn，签名可被服务端复算，返回 data.reply', async () => {
  const server = await startServer(() => ({ payload: { code: 200, data: { reply: '在呢', conversationId: 953 } } }));
  try {
    const client = createBackendClient({ apiOrigin: server.origin, secret: 'k' });
    const result = await client.turn({ channel: 'qqbot-c2c', externalId: 'U1', text: '在吗' });
    assert.deepEqual(result, { reply: '在呢', conversationId: 953 });

    const req = server.requests[0];
    assert.equal(req.url, '/internal/qq/turn');
    assert.deepEqual(req.body, { channel: 'qqbot-c2c', externalId: 'U1', text: '在吗' });
    assert.equal(req.headers['x-qq-sign'], verifySignature('k', req));
  } finally {
    await server.close();
  }
});

test('backend.turn：后端返回非 200 code 时抛出带 code 的错误（handler 靠它分辨未绑定）', async () => {
  const server = await startServer(() => ({ payload: { code: 9003, message: '这个 QQ 号还没绑定角色' } }));
  try {
    const client = createBackendClient({ apiOrigin: server.origin, secret: 'k' });
    await assert.rejects(() => client.turn({ channel: 'qqbot-c2c', externalId: 'U1', text: '在吗' }), (err) => {
      assert.equal(err.code, 9003);
      assert.match(err.message, /还没绑定角色/);
      return true;
    });
  } finally {
    await server.close();
  }
});

test('handler：未绑定时回引导语，不发正文', async () => {
  const sent = [];
  const api = {
    sendC2C: async (openid, content) => sent.push({ openid, content }),
    sendGroup: async () => assert.fail('不该走群'),
  };
  const backend = {
    turn: async () => {
      const err = new Error('这个 QQ 号还没绑定角色');
      err.code = 9003;
      throw err;
    },
  };
  const handler = createQqBotHandler({ api, backend });
  assert.deepEqual(await handler.handle({ kind: 'c2c', openid: 'U1', text: '在吗', msgId: 'm1' }), {
    unbound: true,
  });
  assert.equal(sent[0].content, UNBOUND_HINT);
});

test('handler：发 6 位绑定码就绑上，并回角色名；重复 msg_id 只回一次', async () => {
  const sent = [];
  const bindCalls = [];
  const api = {
    sendC2C: async (openid, content, opts) => sent.push({ openid, content, opts }),
    sendGroup: async () => assert.fail('不该走群'),
  };
  const backend = {
    bind: async (args) => {
      bindCalls.push(args);
      return { characterName: '小满', conversationId: 953 };
    },
    turn: async () => assert.fail('绑定码不该走回合'),
  };
  const handler = createQqBotHandler({ api, backend });
  const event = { kind: 'c2c', openid: 'U1', text: '123456', msgId: 'm1' };

  assert.deepEqual(await handler.handle(event), { bound: true });
  assert.deepEqual(bindCalls[0], { channel: 'qqbot-c2c', externalId: 'U1', code: '123456' });
  assert.match(sent[0].content, /小满/);

  assert.deepEqual(await handler.handle(event), { skipped: 'duplicate' });
  assert.equal(sent.length, 1);
});

test('handler：绑定码不对时不能吞掉这句话，继续按普通消息回', async () => {
  const sent = [];
  const api = { sendC2C: async (_o, content) => sent.push(content), sendGroup: async () => {} };
  const backend = {
    bind: async () => {
      const err = new Error('绑定码不对，或者已经被用过了');
      err.code = 9001;
      throw err;
    },
    turn: async () => ({ reply: '那就当你在跟我报数吧', conversationId: 953 }),
  };
  const handler = createQqBotHandler({ api, backend });
  const result = await handler.handle({ kind: 'c2c', openid: 'U1', text: '000000', msgId: 'm2' });
  assert.equal(result.replied, true);
  assert.deepEqual(sent, ['那就当你在跟我报数吧']);
});

test('handler：群聊走 sendGroup，并把「群 + 成员」当外部身份', async () => {
  const sent = [];
  const seen = [];
  const api = {
    sendC2C: async () => assert.fail('不该走单聊'),
    sendGroup: async (gid, content, opts) => sent.push({ gid, content, opts }),
  };
  const backend = {
    turn: async (args) => {
      seen.push(args);
      return { reply: '群里也在', conversationId: 954 };
    },
  };
  const handler = createQqBotHandler({ api, backend });
  const result = await handler.handle({ kind: 'group', openid: 'M1', groupOpenid: 'G1', text: '大家好', msgId: 'gm1' });

  assert.equal(result.replied, true);
  assert.deepEqual(seen[0], { channel: 'qqbot-group', externalId: 'G1:M1', text: '大家好' });
  assert.deepEqual(sent[0], { gid: 'G1', content: '群里也在', opts: { msgId: 'gm1' } });
});

test('handler：没配模型把后端的人话转给用户，而不是回兜底文案', async () => {
  const sent = [];
  const api = { sendC2C: async (_o, content) => sent.push(content), sendGroup: async () => {} };
  const backend = {
    turn: async () => {
      const err = new Error('你还没在予念里配置文本模型，配好之后我就能在这儿回你了');
      err.code = 4004;
      throw err;
    },
  };
  const handler = createQqBotHandler({ api, backend });
  await handler.handle({ kind: 'c2c', openid: 'U1', text: '在吗', msgId: 'm3' });
  assert.match(sent[0], /配置文本模型/);
});
