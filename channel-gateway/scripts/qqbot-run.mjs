// QQ 官方机器人 → 予念 的一条链路（本地/云上都可跑）。
//
//   QQBOT WS 网关 → 事件（openid + 文本）→ bindings.json → 予念 /messages → 回复
//
// 跑法（Windows PowerShell）：
//   $env:QQ_APPID  = '1906414199'
//   $env:QQ_SECRET = '<AppSecret>'
//   $env:LIANYU_API_ORIGIN = 'https://downloadlianyu.asia'
//   $env:LIANYU_TOKEN = '<该用户的 lianyu-token>'
//   $env:LIANYU_PROVIDER = 'dashscope'
//   $env:LIANYU_MODEL = 'qwen-plus'
//   $env:LIANYU_INSECURE_TLS = '1'
//   $env:DATA_ROOT = 'local/qq-bot-data'
//   $env:DEFAULT_CHARACTER_ID = '1002'
//   node channel-gateway/scripts/qqbot-run.mjs
//
// 只想验证「QQ 能通到服务」时加 --echo：不碰云端，收到什么就原样回。

import process from 'node:process';
import { createQqBotApi } from '../src/qqbot/api.js';
import { createQqBotGateway } from '../src/qqbot/gateway.js';
import { createQqBotHandler } from '../src/qqbot/handler.js';
import { createLianyuClient } from '../src/lianyu.js';

const env = process.env;
const echo = process.argv.includes('--echo');
const log = (line) => console.log(`${new Date().toISOString()} ${line}`);

const appId = env.QQ_APPID;
const clientSecret = env.QQ_SECRET;
if (!appId || !clientSecret) {
  console.error('[qqbot] 缺 QQ_APPID / QQ_SECRET（开放平台 → 开发设置 → 查看手动连接参数）');
  process.exit(2);
}
if (!echo && (!env.LIANYU_TOKEN || !env.DEFAULT_CHARACTER_ID)) {
  console.error('[qqbot] 缺 LIANYU_TOKEN 或 DEFAULT_CHARACTER_ID（只想验通道就加 --echo）');
  process.exit(2);
}

const apiBase = env.QQ_API_BASE || 'https://api.bot.qq.com';
const dataRoot = env.DATA_ROOT || 'local/qq-bot-data';
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

const lianyu = echo
  ? null
  : createLianyuClient({
      apiOrigin: env.LIANYU_API_ORIGIN || 'https://downloadlianyu.asia',
      provider: env.LIANYU_PROVIDER || 'dashscope',
      model: env.LIANYU_MODEL || 'qwen-plus',
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
  : createQqBotHandler({
      api,
      lianyu,
      token: env.LIANYU_TOKEN,
      defaultCharacterId: Number(env.DEFAULT_CHARACTER_ID),
      dataRoot,
      log,
    });

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
    : `[qqbot] 已启动：appId=${appId} 角色=${env.DEFAULT_CHARACTER_ID} 绑定=${dataRoot}/bindings.json`,
);
