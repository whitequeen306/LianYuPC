// 事件 → 绑定 → 予念 → 回复。
//
// 这里是整条链的接缝：QQ 那边给的是 openid（不是 QQ 号，也不跨 bot 通用），
// 予念这边要的是 (userId, characterId)。bindings.json 就是这两者的桥。
// 记忆、人设、关系全在予念库里，换入口不换记忆——所以这里刻意不存任何对话内容。

import { createBindingStore } from '../binding.js';

// 同一条消息可能因重连被重投，reply 一次就够。
const DEDUPE_LIMIT = 200;

export function conversationKey(event) {
  // 群聊里每个人各聊各的：把群维度一起拼进去，避免两个群友共用一个角色会话。
  return event.kind === 'group' ? `${event.groupOpenid}:${event.openid}` : event.openid;
}

// /messages 的响应体是「整条消息」，回复正文在 content 上；字符串则原样返回。
export function replyText(reply) {
  if (reply == null) return '';
  if (typeof reply === 'string') return reply;
  if (typeof reply === 'object') return typeof reply.content === 'string' ? reply.content : '';
  return String(reply);
}

// 会话 id 有两种历史形态：数字（正确）与会话对象（早期把 ensureConversation 的返回值直接落了库）。// 这里统一收敛成数字，顺便把已经写坏的 bindings.json 自愈掉，不必让用户手工删文件。
export function normalizeConversationId(value) {
  const raw = value && typeof value === 'object' ? value.id : value;
  const id = Number(raw);
  return Number.isFinite(id) && id > 0 ? id : null;
}

export function createQqBotHandler({
  api,
  lianyu,
  token,
  defaultCharacterId,
  dataRoot,
  log = () => {},
  maxReplyChars = 1500,
}) {
  const bindings = createBindingStore(dataRoot);
  const seen = [];
  const seenSet = new Set();

  function firstTime(msgId) {
    if (!msgId) return true;
    if (seenSet.has(msgId)) return false;
    seenSet.add(msgId);
    seen.push(msgId);
    if (seen.length > DEDUPE_LIMIT) {
      const old = seen.shift();
      seenSet.delete(old);
    }
    return true;
  }

  async function resolveBinding(event) {
    const channel = event.kind === 'group' ? 'qqbot-group' : 'qqbot-c2c';
    const externalId = conversationKey(event);
    const existing = bindings.get(channel, externalId);
    const existingConversationId = normalizeConversationId(existing && existing.conversationId);
    if (existing && existing.characterId && existingConversationId) {
      // 老数据里可能存着会话对象：认出来就顺手改回数字，避免每条消息都走一次修复。
      if (existing.conversationId !== existingConversationId) {
        const healed = bindings.upsert(channel, externalId, { conversationId: existingConversationId });
        return { channel, externalId, binding: healed };
      }
      return { channel, externalId, binding: existing };
    }

    const characterId = (existing && existing.characterId) || defaultCharacterId;
    if (!characterId) throw new Error('no_character_bound');

    // 会话只有在第一次时才建；换入口（NapCat → 官方 bot）时沿用同一个 conversationId 更好，
    // 但那需要用户手动迁移，这里按「同角色→已存在的 SINGLE 会话」复用。
    // 注意 ensureConversation 返回的是会话对象（{id, characterId, …}），落库要的是数字 id：
    // 直接把对象塞进 URL 会变成 /api/conversation/[object Object]/messages → 400。
    let conversationId = existingConversationId;
    if (!conversationId) {
      const conversation = await lianyu.ensureConversation(token, characterId, 'QQ');
      conversationId = normalizeConversationId(conversation);
    }
    if (!conversationId) throw new Error('conversation_id_missing');
    const binding = bindings.upsert(channel, externalId, {
      characterId,
      conversationId,
      userId: existing?.userId,
    });
    return { channel, externalId, binding };
  }

  return {
    bindings,

    async handle(event) {
      if (!event || !event.openid) return { skipped: 'no_openid' };
      if (!firstTime(event.msgId)) return { skipped: 'duplicate' };

      const text = String(event.text || '').trim();
      if (!text) return { skipped: 'empty' };

      const { binding } = await resolveBinding(event);
      log(`[qqbot] ${event.kind} ${event.openid} → user=${binding.userId || '?'} char=${binding.characterId}`);

      const reply = await lianyu.sendText(token, binding.conversationId, text);
      // /messages 返回的是整条消息对象（{id, seq, content, tokens, …}），不是字符串；
      // 直接 String() 会得到 "[object Object]" 并原样发到 QQ 里。
      const content = String(replyText(reply) ?? '').slice(0, maxReplyChars);
      if (!content) return { skipped: 'empty_reply' };

      if (event.kind === 'group') {
        await api.sendGroup(event.groupOpenid, content, { msgId: event.msgId });
      } else {
        await api.sendC2C(event.openid, content, { msgId: event.msgId });
      }
      log(`[qqbot] 已回 ${content.length} 字（conv=${binding.conversationId}）`);
      return { replied: true, chars: content.length };
    },
  };
}
