import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import test from 'node:test';

import { createQqBotGateway } from '../src/qqbot/gateway.js';

// 假的 ws：网关用的是「事件对象」风格（ws 包的 on/message/close），
// 所以这里用 EventEmitter 复刻，test 里手动 emit 帧。
class FakeWs extends EventEmitter {
  constructor(url) {
    super();
    this.url = url;
    this.sent = [];
    this.readyState = 1; // WebSocket.OPEN
    FakeWs.last = this;
  }

  send(payload) {
    this.sent.push(JSON.parse(payload));
  }

  close() {
    this.readyState = 3;
    this.emit('close', 1000, 'closed');
  }

  push(frame) {
    this.emit('message', JSON.stringify(frame));
  }
}

function fakeFetch(calls) {
  return async (url, init) => {
    calls.push({ url, headers: init?.headers || {} });
    return { ok: true, status: 200, json: async () => ({ url: 'wss://fake/ws' }) };
  };
}

const HELLO = { op: 10, d: { heartbeat_interval: 45000 } };
const tick = () => new Promise((resolve) => setImmediate(resolve));

test('accessToken 是异步的也要等出来，identify 里不能出现 [object Promise]', async () => {
  const calls = [];
  const gateway = createQqBotGateway({
    appId: '1906428729',
    apiBase: 'https://api.bot.qq.com',
    // 连接池里 token 是向后端要的，必然是 Promise —— 线上就是漏了 await，腾讯回 401。
    accessToken: async () => 'real-token',
    fetchImpl: fakeFetch(calls),
    wsFactory: (url) => new FakeWs(url),
    onEvent: () => {},
  });

  await gateway.start();
  const ws = FakeWs.last;
  ws.emit('open');
  await tick();
  ws.push(HELLO);
  await tick();

  assert.equal(calls[0].headers.Authorization, 'QQBot real-token');
  assert.equal(ws.sent.length, 1);
  assert.equal(ws.sent[0].op, 2);
  assert.equal(ws.sent[0].d.token, 'QQBot real-token');
  assert.ok(!JSON.stringify(ws.sent[0]).includes('Promise'));
  gateway.stop();
});

test('建连用的 /gateway 也把异步 token 等出来', async () => {
  const calls = [];
  let resolved = false;
  const gateway = createQqBotGateway({
    appId: 'a1',
    apiBase: 'https://api.bot.qq.com',
    accessToken: async () => {
      await new Promise((resolve) => setTimeout(resolve, 5));
      resolved = true;
      return 'slow-token';
    },
    fetchImpl: fakeFetch(calls),
    wsFactory: (url) => new FakeWs(url),
    onEvent: () => {},
  });

  await gateway.start();

  assert.ok(resolved, '必须先拿到 token 再请求 /gateway');
  assert.equal(calls[0].headers.Authorization, 'QQBot slow-token');
  gateway.stop();
});

test('token 被判 401 时强制刷新一次再重试（重新扫码会作废旧 token）', async () => {
  const seen = [];
  let issued = 0;
  const gateway = createQqBotGateway({
    appId: 'a1',
    apiBase: 'https://api.bot.qq.com',
    accessToken: async ({ force } = {}) => {
      if (force || issued === 0) issued += 1;
      return `tok-${issued}`;
    },
    fetchImpl: async (url, init) => {
      const auth = init?.headers?.Authorization;
      seen.push(auth);
      if (seen.length === 1) return { ok: false, status: 401, json: async () => ({ message: 'AccessToken无效或过期' }) };
      return { ok: true, status: 200, json: async () => ({ url: 'wss://fake/ws' }) };
    },
    wsFactory: (url) => new FakeWs(url),
    onEvent: () => {},
  });

  await gateway.start();

  assert.deepEqual(seen, ['QQBot tok-1', 'QQBot tok-2'], '第二次必须带新 token');
  gateway.stop();
});
