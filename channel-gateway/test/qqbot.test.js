import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

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
  createQqBotHandler,
  conversationKey,
  normalizeConversationId,
  replyText,
} from '../src/qqbot/handler.js';
import { createBindingStore } from '../src/binding.js';

function jsonResponse(body, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
    text: async () => JSON.stringify(body),
  };
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

test('handler：首条消息建绑定并回 C2C；重复 msg_id 只回一次', async () => {
  const dataRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'gw-qqbot-'));
  const sent = [];
  const seenConv = [];
  const api = {
    sendC2C: async (openid, content, opts) => sent.push({ openid, content, opts }),
    sendGroup: async () => assert.fail('不该走群'),
  };
  const lianyu = {
    // 真实接口返回的是会话对象，不是 id 字符串：这里照真实形状，防止又把对象塞进 URL。
    ensureConversation: async (_t, characterId) => ({ id: 953, characterId, characterName: '小满' }),
    // 真实 /messages 返回的是整条消息对象，正文在 content 上。
    sendText: async (_t, conv, text) => {
      seenConv.push(conv);
      return { id: 147320, seq: 5, role: 'ASSISTANT', content: `收到：${text}`, tokens: 2792 };
    },
  };
  const handler = createQqBotHandler({ api, lianyu, token: 'tk', defaultCharacterId: 1002, dataRoot });

  const event = { kind: 'c2c', openid: 'U1', text: '在吗', msgId: 'm1' };
  assert.deepEqual(await handler.handle(event), { replied: true, chars: 5 });
  assert.deepEqual(sent[0], { openid: 'U1', content: '收到：在吗', opts: { msgId: 'm1' } });
  assert.deepEqual(seenConv, [953], 'sendText 必须拿到数字 id，不能是会话对象');

  // 同一条消息被重投：不再回复
  assert.deepEqual(await handler.handle(event), { skipped: 'duplicate' });
  assert.equal(sent.length, 1);

  // 绑定落盘：再次进来复用同一 conversationId
  const binding = handler.bindings.get('qqbot-c2c', 'U1');
  assert.equal(binding.characterId, 1002);
  assert.equal(binding.conversationId, 953);
});

test('handler：会话对象缺 id 时明确报 conversation_id_missing，不发消息', async () => {
  const dataRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'gw-qqbot-'));
  const api = {
    sendC2C: async () => assert.fail('缺会话 id 时不该发消息'),
    sendGroup: async () => assert.fail('不该走群'),
  };
  const lianyu = { ensureConversation: async () => ({ characterId: 1002 }), sendText: async () => 'x' };
  const handler = createQqBotHandler({ api, lianyu, token: 'tk', defaultCharacterId: 1002, dataRoot });
  await assert.rejects(
    () => handler.handle({ kind: 'c2c', openid: 'U9', text: '在吗', msgId: 'm9' }),
    /conversation_id_missing/,
  );
});

test('handler：历史绑定里存成会话对象时自愈成数字 id，且不重新建会话', async () => {
  const dataRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'gw-qqbot-'));
  createBindingStore(dataRoot).upsert('qqbot-c2c', 'U2', {
    characterId: 1002,
    conversationId: { id: 953, characterName: '小满', mode: 'SINGLE' },
  });
  const seenConv = [];
  const api = { sendC2C: async () => {}, sendGroup: async () => assert.fail('不该走群') };
  const lianyu = {
    ensureConversation: async () => assert.fail('已有会话时不该再建'),
    sendText: async (_t, conv) => {
      seenConv.push(conv);
      return '还记得';
    },
  };
  const handler = createQqBotHandler({ api, lianyu, token: 'tk', defaultCharacterId: 1002, dataRoot });
  await handler.handle({ kind: 'c2c', openid: 'U2', text: '在吗', msgId: 'm2' });

  assert.deepEqual(seenConv, [953]);
  assert.equal(handler.bindings.get('qqbot-c2c', 'U2').conversationId, 953);
});

test('replyText：从消息对象里取正文，绝不让 "[object Object]" 发到 QQ', () => {
  assert.equal(replyText({ id: 1, content: '拔智齿。' }), '拔智齿。');
  assert.equal(replyText('直接是字符串'), '直接是字符串');
  assert.equal(replyText({ id: 1 }), '');
  assert.equal(replyText(null), '');
});

test('handler：回复体缺 content 时按 empty_reply 跳过，不发 "[object Object]"', async () => {
  const dataRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'gw-qqbot-'));
  const api = {
    sendC2C: async () => assert.fail('没有正文就不该发消息'),
    sendGroup: async () => assert.fail('不该走群'),
  };
  const lianyu = {
    ensureConversation: async () => ({ id: 953 }),
    sendText: async () => ({ id: 1, seq: 2 }),
  };
  const handler = createQqBotHandler({ api, lianyu, token: 'tk', defaultCharacterId: 1002, dataRoot });
  assert.deepEqual(await handler.handle({ kind: 'c2c', openid: 'U3', text: '在吗', msgId: 'm3' }), {
    skipped: 'empty_reply',
  });
});

test('normalizeConversationId：数字/字符串/对象都能收敛，非法值给 null', () => {
  assert.equal(normalizeConversationId(953), 953);
  assert.equal(normalizeConversationId('953'), 953);
  assert.equal(normalizeConversationId({ id: 953 }), 953);
  assert.equal(normalizeConversationId({ characterId: 1002 }), null);
  assert.equal(normalizeConversationId('conv-1002'), null);
  assert.equal(normalizeConversationId(0), null);
  assert.equal(normalizeConversationId(null), null);
});

test('handler：群聊回复走 sendGroup，且绑定键含群维度', async () => {
  const dataRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'gw-qqbot-'));
  const sent = [];
  const api = {
    sendC2C: async () => assert.fail('不该走单聊'),
    sendGroup: async (gid, content, opts) => sent.push({ gid, content, opts }),
  };
  const lianyu = { ensureConversation: async () => ({ id: 954 }), sendText: async () => '群里也在' };
  const handler = createQqBotHandler({ api, lianyu, token: 'tk', defaultCharacterId: 1002, dataRoot });

  await handler.handle({ kind: 'group', openid: 'M1', groupOpenid: 'G1', text: '大家好', msgId: 'gm1' });
  assert.deepEqual(sent[0], { gid: 'G1', content: '群里也在', opts: { msgId: 'gm1' } });
  assert.equal(handler.bindings.get('qqbot-group', 'G1:M1').conversationId, 954);
});
