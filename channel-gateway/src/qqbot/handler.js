// 事件 → 予念 → 回复。
//
// 这里是整条链的接缝：QQ 那边给的是 openid（不是 QQ 号，也不跨 bot 通用），
// 予念那边要的是 (userId, characterId)。绑定关系现在存在后端库里（qq_channel_binding），
// 网关只负责「把 openid 和一句话交给后端，把答案发回 QQ」，不存凭据、不存对话。

// 同一条消息可能因重连被重投，reply 一次就够。
const DEDUPE_LIMIT = 200;

// 未绑定时的引导语。用户第一次跟机器人说话都会看到这句，所以要能照着做。
export const UNBOUND_HINT =
  '这个 QQ 号还没绑定角色。打开予念 → 角色设置 →「在 QQ 里聊」→ 生成 6 位绑定码，发给我就行。';

export const FALLBACK_REPLY = '（我这会儿有点接不上，稍后再发一次？）';

export function channelOf(event) {
  return event.kind === 'group' ? 'qqbot-group' : 'qqbot-c2c';
}

export function conversationKey(event) {
  // 群聊里每个人各聊各的：把群维度一起拼进去，避免两个群友共用一个角色会话。
  return event.kind === 'group' ? `${event.groupOpenid}:${event.openid}` : event.openid;
}

/** 像绑定码吗：6 位纯数字。用户复制粘贴带空格也算。 */
export function looksLikeBindCode(text) {
  return /^\d{6}$/.test(String(text || '').replace(/\s+/g, ''));
}

export function createQqBotHandler({ api, backend, log = () => {}, maxReplyChars = 1500 }) {
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

  async function send(event, content) {
    const text = String(content || '').slice(0, maxReplyChars);
    if (!text) return;
    if (event.kind === 'group') {
      await api.sendGroup(event.groupOpenid, text, { msgId: event.msgId });
    } else {
      await api.sendC2C(event.openid, text, { msgId: event.msgId });
    }
  }

  async function attemptBind(channel, externalId, text) {
    const code = String(text).replace(/\s+/g, '');
    const result = await backend.bind({ channel, externalId, code });
    return `绑好了。接下来你发的话，会由「${result.characterName}」回你。`;
  }

  return {
    async handle(event) {
      if (!event || !event.openid) return { skipped: 'no_openid' };
      if (!firstTime(event.msgId)) return { skipped: 'duplicate' };

      const text = String(event.text || '').trim();
      if (!text) return { skipped: 'empty' };

      const channel = channelOf(event);
      const externalId = conversationKey(event);

      // 6 位数字优先当绑定码：这样老用户也能靠再发一个码换角色。
      // 绑定失败（码不对/过期）就让这句话继续走正常回合，不吞掉用户的话。
      if (looksLikeBindCode(text)) {
        try {
          const confirmation = await attemptBind(channel, externalId, text);
          await send(event, confirmation);
          log(`[qqbot] 绑定成功 ${channel} ${externalId.slice(0, 4)}***`);
          return { bound: true };
        } catch (err) {
          log(`[qqbot] 绑定失败（按普通消息继续）：${err.message}`);
        }
      }

      try {
        const result = await backend.turn({ channel, externalId, text });
        const reply = String(result.reply || '').trim();
        if (!reply) {
          await send(event, FALLBACK_REPLY);
          return { skipped: 'empty_reply' };
        }
        await send(event, reply);
        log(`[qqbot] 已回 ${reply.length} 字（conv=${result.conversationId}）`);
        return { replied: true, chars: reply.length };
      } catch (err) {
        if (err.code === 9003) {
          await send(event, UNBOUND_HINT);
          log(`[qqbot] 未绑定：${channel} ${externalId.slice(0, 4)}***`);
          return { unbound: true };
        }
        // 后端把「没配模型」这类人话放在 message 里，直接转给用户比吞掉有用。
        if (err.code === 4004 || err.code === 4001) {
          await send(event, err.message);
          log(`[qqbot] 后端拒绝：${err.code} ${err.message}`);
          return { backendError: err.code };
        }
        await send(event, FALLBACK_REPLY);
        log(`[qqbot] 回合失败：${err.code || ''} ${err.message}`);
        return { failed: true };
      }
    },
  };
}
