// QQ 官方机器人 → 予念 的一条链路（本地/云上都可跑）。
//
//   QQBOT WS 网关 → 事件（openid + 文本）→ /internal/qq/turn → 予念回合 → 回复
//
// 跑法（Windows PowerShell）：
//   $env:QQ_APPID  = '<AppID>'
//   $env:QQ_SECRET = '<AppSecret>'
//   $env:QQ_GATEWAY_SECRET = '<与服务端 lianyu.qq.gateway-secret 同一个值>'
//   $env:LIANYU_API_ORIGIN = 'http://127.0.0.1:8080'   # 云上同机部署时直连
//   $env:LIANYU_INSECURE_TLS = '0'
//   node channel-gateway/scripts/qqbot-run.mjs
//
// 只想验证「QQ 能通到服务」时加 --echo：不碰云端，收到什么就原样回。

import https from 'node:https';
import process from 'node:process';
import { createQqBotApi } from '../src/qqbot/api.js';
import { createQqBotGateway } from '../src/qqbot/gateway.js';
import { createQqBotHandler } from '../src/qqbot/handler.js';
import { createBackendClient } from '../src/qqbot/backend.js';
import { createBotPool } from '../src/qqbot/pool.js';

const env = process.env;
const echo = process.argv.includes('--echo');
const log = (line) => console.log(`${new Date().toISOString()} ${line}`);

const appId = env.QQ_APPID;
const clientSecret = env.QQ_SECRET;

// ── 连接池模式（生产用这个）──
// 不再有「我们自己的一个 bot」，而是每个用户扫码后由后端告诉我们该连哪些 appId；
// 网关只拿短期 accessToken，appSecret 留在 Java 库里。
if (env.QQ_POOL === '1' || (!appId && env.QQ_GATEWAY_SECRET)) {
  if (!env.QQ_GATEWAY_SECRET) {
    console.error('[qqbot] 连接池模式需要 QQ_GATEWAY_SECRET');
    process.exit(2);
  }
  const poolBackend = createBackendClient({
    apiOrigin: env.LIANYU_API_ORIGIN || 'https://downloadlianyu.asia',
    secret: env.QQ_GATEWAY_SECRET,
    insecureTls: env.LIANYU_INSECURE_TLS === '1',
  });
  const pool = createBotPool({
    backend: poolBackend,
    log,
    fetchImage: fetchImageBytes,
    handlerOptions: bubbleOptions(env),
  });
  await pool.start();
  log(`[qqbot] 连接池已启动：后端 ${poolBackend.origin}，当前 ${pool.size()} 个机器人`);
  const shutdown = () => {
    log('[qqbot] 收到停止信号，关闭连接池');
    pool.stop();
    process.exit(0);
  };
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
  setInterval(() => log(`[qqbot] 连接池在线 ${pool.size()} 个机器人`), 30 * 60_000);
  // 顶层永久挂起：连接池自己维持生命周期，不再走下面单账号逻辑。
  await new Promise(() => {});
}

if (!appId || !clientSecret) {
  console.error('[qqbot] 缺 QQ_APPID / QQ_SECRET（开放平台 → 开发设置 → 查看手动连接参数）');
  process.exit(2);
}
if (!echo && !env.QQ_GATEWAY_SECRET) {
  console.error('[qqbot] 缺 QQ_GATEWAY_SECRET（要与后端 lianyu.qq.gateway-secret 一致；只想验通道就加 --echo）');
  process.exit(2);
}

const apiBase = env.QQ_API_BASE || 'https://api.bot.qq.com';
const api = createQqBotApi({ appId, clientSecret, apiBase });

// gateway 每次要 token 都读这个变量；定时刷新，长跑不重启。
let current = await api.accessToken();
log(`[qqbot] access_token 就绪：${current.slice(0, 6)}…`);
setInterval(() => {
  api
    .accessToken()
    .then((t) => {
      current = t;
    })
    .catch((err) => log(`[qqbot] 续 token 失败：${err.message}`));
}, 10 * 60_000).unref();

const backend = echo
  ? null
  : createBackendClient({
      apiOrigin: env.LIANYU_API_ORIGIN || 'https://downloadlianyu.asia',
      secret: env.QQ_GATEWAY_SECRET,
      insecureTls: env.LIANYU_INSECURE_TLS === '1',
    });

// 一条用户消息最多回几条文字气泡 + 气泡之间停多久。
// 腾讯的限制是「单聊 4 条 / 群聊 5 条」（图片也占一条），所以默认 2 条文字留足余量，
// 真正需要一口气说三段时用 QQ_MAX_BUBBLES 调，别超过 3（还要给表情包留位置）。
function bubbleOptions(env) {
  const maxBubbles = Number(env.QQ_MAX_BUBBLES || 2);
  const bubbleDelayMs = Number(env.QQ_BUBBLE_DELAY_MS || 700);
  return {
    maxBubbles: Number.isFinite(maxBubbles) && maxBubbles > 0 ? Math.min(3, maxBubbles) : 2,
    bubbleDelayMs: Number.isFinite(bubbleDelayMs) && bubbleDelayMs >= 0 ? bubbleDelayMs : 700,
  };
}

// QQ 图片给的是一个带 rkey 的限时 URL（multimedia.nt.qq.com.cn），我们这里只取字节，
// 落库由后端负责（它要按对象存储的 key 走识图链路）。限制 5MB 与后端同一口径。
const IMAGE_MAX_BYTES = 5 * 1024 * 1024;

function fetchImageBytes(url, redirects = 0) {
  return new Promise((resolve, reject) => {
    if (redirects > 3) {
      reject(new Error('too_many_redirects'));
      return;
    }
    const req = https.get(url, { timeout: 10_000 }, (res) => {
      const status = res.statusCode || 0;
      if (status >= 300 && status < 400 && res.headers.location) {
        res.resume();
        resolve(fetchImageBytes(new URL(res.headers.location, url).toString(), redirects + 1));
        return;
      }
      if (status !== 200) {
        res.resume();
        reject(new Error(`http_${status}`));
        return;
      }
      const contentType = String(res.headers['content-type'] || 'image/jpeg').split(';')[0].trim();
      const chunks = [];
      let size = 0;
      res.on('data', (chunk) => {
        size += chunk.length;
        if (size > IMAGE_MAX_BYTES) {
          req.destroy(new Error('image_too_large'));
          return;
        }
        chunks.push(chunk);
      });
      res.on('end', () => {
        if (!chunks.length) {
          reject(new Error('empty_body'));
          return;
        }
        resolve({ base64: Buffer.concat(chunks).toString('base64'), contentType });
      });
    });
    req.on('timeout', () => req.destroy(new Error('timeout')));
    req.on('error', reject);
  });
}

const handler = echo
  ? {
      async handle(event) {
        const text = `[${event.kind}] 收到：${event.text}`.slice(0, 1500);
        if (event.kind === 'group') await api.sendGroup(event.groupOpenid, text, { msgId: event.msgId });
        else await api.sendC2C(event.openid, text, { msgId: event.msgId });
        log(`[echo] ${event.kind} ${event.openid} ← ${event.text}`);
        return { replied: true };
      },
    }
  : createQqBotHandler({ api, backend, log, fetchImage: fetchImageBytes, ...bubbleOptions(env) });

// 图片/表情包不在 content 里，而是走 attachments（content_type 区分）。线上排查时
// 我们只想知道「这条消息到底带了什么」，所以打一行摘要；要看全貌设 QQ_LOG_RAW=1。
function logAttachments(type, event) {
  const data = event?.raw || {};
  const attachments = data.attachments;
  if (Array.isArray(attachments) && attachments.length > 0) {
    const kinds = attachments.map((item) => item?.content_type || '?').join(',');
    log(`[qqbot] 附件 ${type} content_type=${kinds} ${JSON.stringify(attachments).slice(0, 600)}`);
  } else if (env.QQ_LOG_RAW === '1') {
    log(`[qqbot] 原始事件 ${type} ${JSON.stringify(data).slice(0, 800)}`);
  }
}

const gateway = createQqBotGateway({
  appId,
  apiBase,
  accessToken: () => current,
  onLog: log,
  onEvent: (type, event) => {
    logAttachments(type, event);
    return handler.handle(event);
  },
});

gateway.start().catch((err) => log(`[qqbot] 启动失败：${err.message}`));
log(
  echo
    ? '[qqbot] echo 模式已启动：在 QQ 里给机器人发一句话，会原样回给你'
    : `[qqbot] 已启动：appId=${appId} 后端=${backend.origin}（无用户凭据，身份由后端解析）`,
);
