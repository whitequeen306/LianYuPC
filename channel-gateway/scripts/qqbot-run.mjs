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

import process from 'node:process';
import { createQqBotApi } from '../src/qqbot/api.js';
import { createQqBotGateway } from '../src/qqbot/gateway.js';
import { createQqBotHandler } from '../src/qqbot/handler.js';
import { createBackendClient } from '../src/qqbot/backend.js';

const env = process.env;
const echo = process.argv.includes('--echo');
const log = (line) => console.log(`${new Date().toISOString()} ${line}`);

const appId = env.QQ_APPID;
const clientSecret = env.QQ_SECRET;
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
  : createQqBotHandler({ api, backend, log });

const gateway = createQqBotGateway({
  appId,
  apiBase,
  accessToken: () => current,
  onLog: log,
  onEvent: (_type, event) => handler.handle(event),
});

gateway.start().catch((err) => log(`[qqbot] 启动失败：${err.message}`));
log(
  echo
    ? '[qqbot] echo 模式已启动：在 QQ 里给机器人发一句话，会原样回给你'
    : `[qqbot] 已启动：appId=${appId} 后端=${backend.origin}（无用户凭据，身份由后端解析）`,
);
