import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

import { createBindingStore, bindingKey } from '../src/binding.js';

function tempRoot() {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'gw-binding-'));
}

test('binding: 同一外部身份只换出同一条 (user, character, conversation)', () => {
  const root = tempRoot();
  const store = createBindingStore(root);

  store.upsert('openid', 'OPENID-A', { userId: 421, token: 't1', characterId: 1002, conversationId: 953 });
  const again = store.get('openid', 'OPENID-A');

  assert.equal(again.userId, 421);
  assert.equal(again.characterId, 1002);
  assert.equal(again.conversationId, 953);
  assert.equal(bindingKey('openid', 'OPENID-A'), 'openid:OPENID-A');
});

test('binding: upsert 不覆盖未提交的字段（换号只改 token 也留得住角色）', () => {
  const root = tempRoot();
  const store = createBindingStore(root);

  store.upsert('openid', 'OPENID-B', { userId: 7, token: 'old', characterId: 1005, conversationId: 960 });
  store.upsert('openid', 'OPENID-B', { token: 'new' });

  const row = store.get('openid', 'OPENID-B');
  assert.equal(row.token, 'new');
  assert.equal(row.characterId, 1005, '改动 token 不该把角色记忆关联弄丢');
  assert.equal(row.conversationId, 960);
});

test('binding: 落盘后可重新读回，进程重启不丢映射', () => {
  const root = tempRoot();
  const first = createBindingStore(root);
  first.upsert('openid', 'OPENID-C', { userId: 9, token: 'x', characterId: 1006, conversationId: 961 });

  const second = createBindingStore(root);
  assert.equal(second.get('openid', 'OPENID-C').conversationId, 961);
});

test('binding: 不同通道的同名 id 不互相覆盖', () => {
  const root = tempRoot();
  const store = createBindingStore(root);

  store.upsert('openid', '10001', { userId: 1, characterId: 1002, conversationId: 1 });
  store.upsert('qq', '10001', { userId: 2, characterId: 1003, conversationId: 2 });

  assert.equal(store.get('openid', '10001').userId, 1);
  assert.equal(store.get('qq', '10001').userId, 2);
});

test('binding: remove 只摘掉目标身份', () => {
  const root = tempRoot();
  const store = createBindingStore(root);
  store.upsert('openid', 'OPENID-D', { userId: 3 });
  store.upsert('openid', 'OPENID-E', { userId: 4 });

  assert.equal(store.remove('openid', 'OPENID-D'), true);
  assert.equal(store.get('openid', 'OPENID-D'), null);
  assert.equal(store.get('openid', 'OPENID-E').userId, 4);
  assert.equal(store.remove('openid', 'OPENID-D'), false);
});
