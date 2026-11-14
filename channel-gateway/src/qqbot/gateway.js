// QQ 官方机器人 WebSocket 网关（对齐 bot.q.qq.com/wiki「WebSocket 方式」）。
//
// 流程：GET /gateway → {url: wss://api.bot.qq.com/websocket/}
//      连上先收 op=10 Hello（heartbeat_interval，毫秒）
//      → 发 op=2 Identify（token="QQBot {AccessToken}"、intents、shard、properties）
//      → 收 op=0 READY（含 session_id）→ 按周期 op=1 心跳（d=最新 s）→ op=11 ACK
//      → 断线重连先发 op=6 Resume（token + session_id + seq），补发遗漏事件后回 RESUMED；
//        session 失效（4006/4007）才退回 Identify。
//
// 它只是「消息入口」：收到事件交给上层 handler；不在这里碰记忆，也不拼人设。

import WebSocket from 'ws';

// C2C 消息、群 @、群全量消息都在这一位里。
export const INTENT_GROUP_AND_C2C = 1 << 25;

export function buildIdentify(accessToken, { intents = INTENT_GROUP_AND_C2C, shard = [0, 1] } = {}) {
  return {
    op: 2,
    d: {
      token: `QQBot ${accessToken}`,
      intents,
      shard,
      // 官方说「目前无实际作用，可留空」，但示例是带 $ 前缀的对象，照抄最稳。
      properties: {
        $os: process.platform,
        $browser: 'lianyu-channel-gateway',
        $device: 'lianyu-channel-gateway',
      },
    },
  };
}

export function buildResume(accessToken, { sessionId, seq }) {
  return { op: 6, d: { token: `QQBot ${accessToken}`, session_id: sessionId, seq } };
}

// 事件 → 统一形状；不认识的类型返回 null。
export function normalizeEvent(type, d) {
  if (!d) return null;
  const author = d.author || {};
  switch (type) {
    case 'C2C_MESSAGE_CREATE':
      return {
        kind: 'c2c',
        openid: author.user_openid || author.id,
        text: d.content || '',
        msgId: d.id,
        timestamp: d.timestamp,
        raw: d,
      };
    case 'GROUP_AT_MESSAGE_CREATE':
    case 'GROUP_MESSAGE_CREATE':
      // 群 @ 事件的 content 官方已去掉 @前缀，不用再剥；GROUP_MESSAGE_CREATE 是「接收所有消息」后的全量。
      return {
        kind: 'group',
        openid: author.member_openid || author.id,
        groupOpenid: d.group_openid,
        text: d.content || '',
        msgId: d.id,
        timestamp: d.timestamp,
        raw: d,
      };
    default:
      return null;
  }
}

/** 去掉开头的 <@!123456> 这类提及标记（群里全量消息可能带）。 */
export function stripMention(text) {
  return String(text || '').replace(/^\s*(<@!?\d+>)+\s*/, '').trim();
}

// 关闭码 → 该怎么重试（官方错误码表）。
// 4009 只许 resume；4006/4007 要 identify；4914/4915 是不可恢复的（下架/封禁）。
export function classifyClose(code) {
  if (code === 4914 || code === 4915) return 'fatal';
  if (code === 4006 || code === 4007) return 'identify';
  if (code === 4009) return 'resume';
  return 'identify';
}

export function createQqBotGateway({
  appId,
  apiBase = 'https://api.bot.qq.com',
  accessToken,
  fetchImpl = fetch,
  wsFactory = (url) => new WebSocket(url),
  onEvent,
  onLog = () => {},
  heartbeatMarginMs = 2000,
}) {
  const base = apiBase.replace(/\/+$/, '');
  let ws = null;
  let heartbeatTimer = null;
  let seq = null;
  let sessionId = null;
  let closedByUser = false;
  let reconnectAttempt = 0;
  let heartbeatIntervalMs = 30_000;
  let mode = 'identify'; // 下次重连用 identify 还是 resume

  function stopHeartbeat() {
    if (heartbeatTimer) {
      clearInterval(heartbeatTimer);
      heartbeatTimer = null;
    }
  }

  function startHeartbeat() {
    stopHeartbeat();
    const every = Math.max(5000, heartbeatIntervalMs - heartbeatMarginMs);
    heartbeatTimer = setInterval(() => {
      if (!ws || ws.readyState !== WebSocket.OPEN) return;
      ws.send(JSON.stringify({ op: 1, d: seq }));
    }, every);
  }

  async function fetchGatewayUrl() {
    const res = await fetchImpl(`${base}/gateway`, {
      headers: { Authorization: `QQBot ${accessToken()}` },
    });
    const body = (await res.json().catch(() => null)) || {};
    if (!res.ok || !body.url) throw new Error(`gateway_url_failed_${res.status}:${body.message || ''}`);
    return body.url;
  }

  function scheduleReconnect(reason) {
    if (closedByUser) return;
    reconnectAttempt += 1;
    const delay = Math.min(30_000, 1000 * 2 ** Math.min(reconnectAttempt - 1, 5));
    onLog(`[qqbot] ${delay}ms 后重连（${reason}，下次用 ${mode}）`);
    setTimeout(() => {
      if (!closedByUser) start().catch((err) => onLog(`[qqbot] 重连失败：${err && err.message}`));
    }, delay);
  }

  function handleFrame(frame) {
    if (typeof frame.s === 'number') seq = frame.s;

    switch (frame.op) {
      case 10: // Hello
        heartbeatIntervalMs = (frame.d && frame.d.heartbeat_interval) || 30_000;
        if (mode === 'resume' && sessionId && seq != null) {
          ws.send(JSON.stringify(buildResume(accessToken(), { sessionId, seq })));
        } else {
          ws.send(JSON.stringify(buildIdentify(accessToken())));
        }
        startHeartbeat();
        break;
      case 0: // Dispatch
        if (frame.t === 'READY') {
          sessionId = frame.d && frame.d.session_id;
          mode = 'resume';
          reconnectAttempt = 0;
          onLog(`[qqbot] 已就绪（session=${sessionId}）`);
        } else if (frame.t === 'RESUMED') {
          reconnectAttempt = 0;
          onLog('[qqbot] 会话已恢复');
        } else {
          const event = normalizeEvent(frame.t, frame.d);
          if (event) {
            Promise.resolve(onEvent(frame.t, event)).catch((err) =>
              onLog(`[qqbot] 处理事件失败：${err && err.message}`),
            );
          }
        }
        break;
      case 7: // 服务端要求重连
        mode = 'resume';
        ws.close();
        break;
      case 9: // 会话失效
        mode = 'identify';
        sessionId = null;
        ws.close();
        break;
      case 11: // 心跳 ACK
        break;
      default:
        break;
    }
  }

  async function start() {
    const url = await fetchGatewayUrl();
    onLog(`[qqbot] 连接 ${url}`);
    ws = wsFactory(url);

    ws.on('message', (buf) => {
      let frame;
      try {
        frame = JSON.parse(buf.toString());
      } catch {
        return;
      }
      handleFrame(frame);
    });

    ws.on('close', (code) => {
      stopHeartbeat();
      const action = classifyClose(code);
      if (action === 'fatal') {
        closedByUser = true;
        onLog(`[qqbot] 不可恢复关闭码 ${code}（机器人被下架或封禁），停止重连`);
        return;
      }
      mode = action;
      if (action === 'identify') {
        sessionId = null;
        seq = null;
      }
      if (!closedByUser) scheduleReconnect(`closed_${code}`);
    });

    ws.on('error', (err) => onLog(`[qqbot] ws error: ${err && err.message}`));
  }

  return {
    start,
    stop() {
      closedByUser = true;
      stopHeartbeat();
      if (ws) {
        try {
          ws.close();
        } catch {
          /* 已断 */
        }
      }
    },
    get seq() {
      return seq;
    },
  };
}
