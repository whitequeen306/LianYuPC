#!/usr/bin/env node
// 扮演「QQ 那一侧」：只有一条 openid 绑定记录，其它什么都不知道。
//
// 用途：在没有腾讯审核、没有协议端的情况下，验证「外部身份 → 云端角色 → 同一段记忆」
// 这条链真的通；换通道时只需要换掉这个脚本，剩下的调用一模一样。
//
// 用法：
//   node scripts/simulate-qq.mjs --from OPENID-1 --token <lianyu-token> \
//        --user 421 --character 1002 --text "我下周三要干嘛？"
//   （首次会建绑定；之后只传 --from 与 --text 即可）

import process from 'node:process';
import { loadConfig } from '../src/config.js';
import { createBindingStore } from '../src/binding.js';
import { createLianyuClient } from '../src/lianyu.js';

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i += 1) {
    const token = argv[i];
    if (!token.startsWith('--')) continue;
    const key = token.slice(2);
    const value = argv[i + 1] && !argv[i + 1].startsWith('--') ? argv[i + 1] : 'true';
    out[key] = value;
    if (value !== 'true') i += 1;
  }
  return out;
}

const args = parseArgs(process.argv.slice(2));
const channel = args.channel || 'openid';
const externalId = args.from;
const text = args.text;

if (!externalId || !text) {
  console.error('用法: node scripts/simulate-qq.mjs --from <openid> --text <消息> [--token .. --user .. --character ..]');
  process.exit(2);
}

const env = process.env;
const cfg = loadConfig(env);
const apiOrigin = env.LIANYU_API_ORIGIN;
if (!apiOrigin) {
  console.error('缺少 LIANYU_API_ORIGIN');
  process.exit(2);
}

const store = createBindingStore(cfg.dataRoot);
const client = createLianyuClient({
  apiOrigin,
  provider: env.LIANYU_PROVIDER || 'platform',
  model: env.LIANYU_MODEL || '',
  insecureTls: env.LIANYU_INSECURE_TLS === '1',
});

let binding = store.get(channel, externalId);

if (!binding) {
  if (!args.token || !args.character) {
    console.error('该身份还没有绑定；首次请补 --token 与 --character');
    process.exit(3);
  }
  const conversation = await client.ensureConversation(args.token, args.character, args.title || '');
  binding = store.upsert(channel, externalId, {
    userId: Number(args.user) || null,
    token: args.token,
    characterId: Number(args.character),
    conversationId: Number(conversation.id),
    characterName: conversation.characterName || '',
  });
  console.log(`[bind] ${channel}:${externalId} → user=${binding.userId} char=${binding.characterId} conv=${binding.conversationId} (${binding.characterName})`);
}

// 会话可能被用户在 App 里删掉：404 时重建一次，记忆照样跟着 (user, character) 回来。
let conversationId = binding.conversationId;
try {
  const reply = await client.sendText(binding.token, conversationId, text);
  console.log(`[qq] ${text}`);
  console.log(`[${binding.characterName || binding.characterId}] ${reply.content}`);
  console.log(`[meta] conv=${conversationId} seq=${reply.seq} tokens=${reply.tokens}`);
} catch (err) {
  const message = String(err && err.message);
  if (message.includes('404') || message.includes('api_4004')) {
    const fresh = await client.ensureConversation(binding.token, binding.characterId, '');
    binding = store.upsert(channel, externalId, { conversationId: Number(fresh.id) });
    conversationId = binding.conversationId;
    const reply = await client.sendText(binding.token, conversationId, text);
    console.log(`[rebind] conv=${conversationId}`);
    console.log(`[qq] ${text}`);
    console.log(`[${binding.characterName || binding.characterId}] ${reply.content}`);
  } else {
    console.error(`[error] ${message}`);
    process.exit(1);
  }
}
