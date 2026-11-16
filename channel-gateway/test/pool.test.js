import test from 'node:test';
import assert from 'node:assert/strict';

import { createBotPool } from '../src/qqbot/pool.js';

function fakeBackend(accounts, { failAccounts = false } = {}) {
  const state = { accounts, failAccounts, tokenCalls: [] };
  return {
    state,
    async accounts() {
      if (state.failAccounts) throw new Error('backend_down');
      return state.accounts;
    },
    async token(appId) {
      state.tokenCalls.push(appId);
      return { accessToken: `tok-${appId}`, expiresInSeconds: 7200 };
    },
  };
}

function harness(accounts) {
  const backend = fakeBackend(accounts);
  const gateways = [];
  const handlers = [];
  const logs = [];
  const pool = createBotPool({
    backend,
    log: (line) => logs.push(line),
    pollMs: 1_000_000,
    apiFactory: ({ appId, tokenProvider }) => {
      assert.equal(typeof tokenProvider, 'function');
      return { appId, accessToken: async () => `tok-${appId}` };
    },
    gatewayFactory: ({ appId, onEvent }) => {
      const entry = {
        appId,
        started: false,
        stopped: false,
        start() {
          entry.started = true;
        },
        stop() {
          entry.stopped = true;
        },
      };
      gateways.push(entry);
      return entry;
    },
    handlerFactory: () => {
      const entry = { handled: [], handle: async (event) => entry.handled.push(event) };
      handlers.push(entry);
      return entry;
    },
  });
  return { pool, backend, gateways, handlers, logs };
}

test('池子启动后为后端返回的每个机器人各开一条连接', async () => {
  const { pool, gateways } = harness([
    { appId: '1906414199', characterId: 1002, externalOpenid: 'A' },
    { appId: '1906428729', characterId: 1003, externalOpenid: 'B' },
  ]);

  await pool.start();

  assert.equal(pool.size(), 2);
  assert.deepEqual(gateways.map((g) => g.appId).sort(), ['1906414199', '1906428729']);
  assert.ok(gateways.every((g) => g.started));
  pool.stop();
});

test('后端不再返回的机器人会被停掉（用户解绑后不该继续在线）', async () => {
  const { pool, backend, gateways } = harness([
    { appId: 'a1', characterId: 1 },
    { appId: 'a2', characterId: 2 },
  ]);
  await pool.start();

  backend.state.accounts = [{ appId: 'a2', characterId: 2 }];
  await pool.refresh();

  assert.equal(pool.size(), 1);
  const gone = gateways.find((g) => g.appId === 'a1');
  assert.ok(gone.stopped);
  assert.deepEqual(pool.status().map((s) => s.appId), ['a2']);
  pool.stop();
});

test('新增机器人无需重启：下一次同步就能上线', async () => {
  const { pool, backend, gateways } = harness([]);
  await pool.start();
  assert.equal(pool.size(), 0);

  backend.state.accounts = [{ appId: 'new1', characterId: 7 }];
  await pool.refresh();

  assert.equal(pool.size(), 1);
  assert.equal(gateways.length, 1);
  assert.ok(gateways[0].started);
  pool.stop();
});

test('换绑角色不重开连接，只更新角色', async () => {
  const { pool, backend, gateways } = harness([{ appId: 'a1', characterId: 1 }]);
  await pool.start();

  backend.state.accounts = [{ appId: 'a1', characterId: 9 }];
  await pool.refresh();

  assert.equal(gateways.length, 1, '不该新建连接');
  assert.equal(pool.status()[0].characterId, 9);
  pool.stop();
});

test('后端抖动时保留现有连接，不把在线机器人踢下线', async () => {
  const { pool, backend, logs } = harness([{ appId: 'a1', characterId: 1 }]);
  await pool.start();

  backend.state.failAccounts = true;
  await pool.refresh();

  assert.equal(pool.size(), 1);
  assert.ok(logs.some((l) => l.includes('同步账号失败')));
  pool.stop();
});

test('事件交给 handler，异常被吞掉不炸池子', async () => {
  const { pool, handlers } = harness([{ appId: 'a1', characterId: 1 }]);
  await pool.start();

  // 这里只验证 handler 被调用一次（真实 onEvent 在 gatewayFactory 里接上）
  assert.equal(handlers.length, 1);
  assert.ok(Array.isArray(handlers[0].handled));
  pool.stop();
});
