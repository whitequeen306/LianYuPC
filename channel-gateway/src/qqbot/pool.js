// 多机器人连接池：一个用户一个官方机器人，各自一条 WebSocket。
//
// 与单账号脚本（scripts/qqbot-run.mjs + QQ_APPID/QQ_SECRET）的区别只有一处：
// 凭据不在网关手里。池子向后端要「该连哪些 appId」（/internal/qq/accounts），
// 再按 appId 换短期 accessToken（/internal/qq/token）；appSecret 始终留在 Java 库里。
//
// 生命周期：每 pollMs 拉一次账号表，多退少补——扫码成功的那一刻后端就会返回新 appId，
// 最迟一个轮询周期后这条机器人就能回话，用户不用重启任何东西。

import { createQqBotApi } from './api.js';
import { createQqBotGateway } from './gateway.js';
import { createQqBotHandler } from './handler.js';

const DEFAULT_POLL_MS = 30_000;

export function createBotPool({
  backend,
  log = () => {},
  fetchImage,
  pollMs = DEFAULT_POLL_MS,
  apiBase,
  fetchImpl,
  wsFactory,
  handlerFactory = createQqBotHandler,
  gatewayFactory = createQqBotGateway,
  apiFactory = createQqBotApi,
  now = () => Date.now(),
}) {
  if (!backend) throw new Error('backend_required');

  /** appId → { appId, characterId, api, gateway, handler, lastError, since } */
  const live = new Map();
  let timer = null;
  let syncing = false;
  let stopped = true;

  function build(account) {
    const api = apiFactory({
      appId: account.appId,
      tokenProvider: () => backend.token(account.appId),
      apiBase,
      fetchImpl,
      now,
    });
    const handler = handlerFactory({ api, backend, log, fetchImage });
    const gateway = gatewayFactory({
      appId: account.appId,
      apiBase,
      accessToken: () => api.accessToken(),
      fetchImpl,
      wsFactory,
      onEvent: (event) => {
        handler.handle(event).catch((err) => log(`[pool] ${account.appId} 处理事件失败：${err?.message || err}`));
      },
      onLog: (line) => log(`[pool:${account.appId}] ${line}`),
    });
    return { api, gateway, handler };
  }

  async function sync() {
    if (stopped || syncing) return;
    syncing = true;
    try {
      const accounts = await backend.accounts();
      const wanted = new Map();
      for (const account of accounts) {
        if (account && account.appId) wanted.set(String(account.appId), account);
      }

      // 下线：后端不再返回（用户解绑/换角色失败）的机器人，直接停。
      for (const [appId, entry] of live) {
        if (!wanted.has(appId)) {
          log(`[pool] 停止机器人 ${appId}`);
          entry.gateway.stop();
          live.delete(appId);
        } else if (entry.characterId !== wanted.get(appId).characterId) {
          // 换了角色：连接不用重开，只是回话走的新绑定。
          entry.characterId = wanted.get(appId).characterId;
          log(`[pool] 机器人 ${appId} 换绑到角色 ${entry.characterId}`);
        }
      }

      // 上线：新扫码进来的机器人。
      for (const [appId, account] of wanted) {
        if (live.has(appId)) continue;
        const entry = { ...build(account), appId, characterId: account.characterId, lastError: null, since: now() };
        live.set(appId, entry);
        log(`[pool] 启动机器人 ${appId}（角色 ${account.characterId}）`);
        // 一个机器人连不上（token 过期、被平台封禁…）不能拖垮整个进程：
        // 池子里还有别人的机器人，挂掉要能自愈。
        Promise.resolve(entry.gateway.start()).catch((err) => {
          const message = err?.message || String(err);
          entry.lastError = message;
          log(`[pool] 机器人 ${appId} 启动失败：${message}（下一轮同步重试）`);
          // 起不来就先摘掉：留在 live 里会挡住下一轮同步，这条机器人永远没人再试。
          live.delete(appId);
        });
      }
    } catch (err) {
      log(`[pool] 同步账号失败：${err?.message || err}`);
    } finally {
      syncing = false;
    }
  }

  return {
    async start() {
      stopped = false;
      await sync();
      timer = setInterval(() => {
        sync().catch((err) => log(`[pool] 同步异常：${err?.message || err}`));
      }, pollMs);
      // 故意不 unref：没有机器人在线时，这个定时器就是进程唯一的存活理由。
      // 一旦 unref，Node 会因为「没有待处理的事件」直接退出（连接池型网关会莫名掉线）。
    },

    stop() {
      stopped = true;
      if (timer) clearInterval(timer);
      timer = null;
      for (const [appId, entry] of live) {
        entry.gateway.stop();
        log(`[pool] 关闭机器人 ${appId}`);
      }
      live.clear();
    },

    async refresh() {
      await sync();
    },

    status() {
      return [...live.values()].map((entry) => ({
        appId: entry.appId,
        characterId: entry.characterId,
        since: entry.since,
        // 起不来的机器人留在池子里等下次同步重试，但要让外面看得见「它为什么不在线」。
        lastError: entry.lastError,
      }));
    },

    size() {
      return live.size;
    },
  };
}
