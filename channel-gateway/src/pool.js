/**
 * 账号池：一机多号的生命周期编排。
 *
 * 与已删除的桌面端 napcatHost.js 的区别：那支是模块级单例（一个 App 只托管一个号），
 * 云端要 N 个号并存，故状态全部收进 Map<key, runtime>，由本模块持有。
 *
 * 单个号的状态机：
 *   stopped → starting（起 launcher）→ waiting_login（等扫码/等 WS）
 *          → online（OneBot ready，selfId 已知）→ stopped
 * 任意阶段异常 → error（保留 lastError，人工介入；不自动重试登录）
 */
import { accountDir, ensureAccountConfig, listAccounts, profileDir, readAccount, writeAccount } from './accountStore.js'
import { startAccountProcess } from './launch.js'
import { checkLoginStatus, getQrcode, login, readQrcodeImage, webuiBase } from './napcatWebui.js'
import { createNapCatClient } from './onebotClient.js'
import { extractMessageContent, resolveReplyTarget, resolveSender } from './onebotExtract.js'
import { killQqByProfile } from './winproc.js'

const WEBUI_POLL_MS = 3000
const MAX_REPLY_CHARS = 300

export function createAccountPool({ cfg, preflight }) {
  /** @type {Map<string, any>} */
  const runtimes = new Map()

  function runtimeOf(key) {
    let rt = runtimes.get(key)
    if (!rt) {
      rt = {
        key,
        phase: 'stopped',
        uin: '',
        selfId: '',
        qrcodeUrl: '',
        lastError: '',
        logs: [],
        messages: [],
        proc: null,
        client: null,
        webuiTimer: null,
        polling: false,
        startedAt: 0,
      }
      runtimes.set(key, rt)
    }
    return rt
  }

  function log(rt, line) {
    const stamp = new Date().toTimeString().slice(0, 8)
    const text = `[${stamp}] ${line}`
    rt.logs.push(text)
    if (rt.logs.length > cfg.logRingSize) rt.logs.splice(0, rt.logs.length - cfg.logRingSize)
  }

  function stopTimers(rt) {
    if (rt.webuiTimer) {
      clearInterval(rt.webuiTimer)
      rt.webuiTimer = null
    }
  }

  function snapshot(rt, account) {
    return {
      key: rt.key,
      note: account?.note || '',
      phase: rt.phase,
      uin: rt.uin || account?.lastUin || '',
      selfId: rt.selfId,
      wsPort: account?.wsPort || 0,
      webuiPort: account?.webuiPort || 0,
      qrcodeUrl: rt.qrcodeUrl,
      hasQrcode: Boolean(readQrcodeImage(accountDir(cfg, rt.key))),
      lastError: rt.lastError,
      messages: rt.messages.slice(-20),
      startedAt: rt.startedAt,
    }
  }

  async function start(key) {
    const account = readAccount(cfg, key)
    if (!account) throw new Error(`account_not_found:${key}`)

    const guard = preflight()
    if (!guard.ok) {
      const rt = runtimeOf(key)
      rt.phase = 'error'
      rt.lastError = guard.problems.map((p) => p.detail).join('；')
      throw new Error(`preflight_failed:${rt.lastError}`)
    }

    const rt = runtimeOf(key)
    if (['online', 'starting', 'waiting_login', 'connecting'].includes(rt.phase)) {
      return snapshot(rt, account)
    }

    rt.phase = 'starting'
    rt.lastError = ''
    rt.qrcodeUrl = ''
    rt.startedAt = Date.now()
    rt.logs = []

    ensureAccountConfig(cfg, account)

    // 网关重启后旧 QQ 进程可能还挂在外面（launcher 早已退出，父子关系断了）。
    // 不清掉就再起一份，同一个 profile 的两个 QQ 会互相抢登录态。
    const orphans = killQqByProfile(profileDir(cfg, key), { log: (l) => log(rt, l) })
    if (orphans.length) log(rt, `[pool] cleared ${orphans.length} orphan QQ process(es)`)

    // 1) 子进程先起：launcher 注入 hook 后自行退出（code=0 不重启），NapCat 活在 QQ 进程里
    try {
      const { proc } = startAccountProcess(cfg, account, {
        onLog: (line) => log(rt, line),
        onExit: (info) => log(rt, `[process] exit code=${info.code} expected=${info.expected}`),
        onError: () => {
          rt.phase = 'error'
          rt.lastError = 'process_restart_exhausted'
        },
      })
      rt.proc = proc
      log(rt, `[process] launched ws=${account.wsPort} webui=${account.webuiPort}`)
    } catch (err) {
      rt.phase = 'error'
      rt.lastError = err?.message || String(err)
      log(rt, `[process] launch failed: ${rt.lastError}`)
      throw err
    }

    // 2) 二维码轮询先跑，OneBot 客户端等登录成功再建
    await pollWebuiOnce(rt, account)
    rt.webuiTimer = setInterval(() => {
      void pollWebuiOnce(rt, account)
    }, WEBUI_POLL_MS)
    rt.webuiTimer.unref?.()

    return snapshot(rt, account)
  }

  /**
   * 登录成功后才建 OneBot 客户端。
   * 登录前 NapCat 的 WS 服务端还没监听，提前建只会每 6s 刷一条 ECONNREFUSED。
   */
  function startOneBotClient(rt, account) {
    rt.client?.stop()
    rt.client = createNapCatClient({
      wsUrl: `ws://127.0.0.1:${account.wsPort}`,
      accessToken: account.wsToken,
      onStatus: (info) => {
        if (info.state === 'ready') {
          rt.phase = 'online'
          rt.selfId = info.selfId || rt.selfId
          rt.uin = rt.selfId || rt.uin
          log(rt, `[onebot] ready selfId=${rt.selfId}`)
          if (rt.selfId && rt.selfId !== account.lastUin) {
            // 记下 uin，重启网关后控制台仍能看到是哪个号
            try {
              writeAccount(cfg, account.key, { ...readAccount(cfg, account.key), lastUin: rt.selfId })
            } catch {
              /* 落盘失败不影响收发 */
            }
          }
        } else if (info.state === 'stopped') {
          log(rt, '[onebot] stopped')
        } else {
          log(rt, `[onebot] ${info.state}`)
        }
      },
      onMessage: (event) => {
        void handleIncoming(rt, account, event)
      },
    })
    rt.client.start()
  }

  /** 每轮：WebUI 登录 → 查登录态 → 未登录就把二维码 URL 抓下来；已登录则建 OneBot 客户端 */
  async function pollWebuiOnce(rt, account) {
    if (rt.phase === 'stopped' || rt.polling) return
    rt.polling = true
    try {
      const credential = await login(account)
      const status = await checkLoginStatus(account, credential)
      const loggedIn = Boolean(status?.isLogin)

      if (loggedIn) {
        rt.uin = rt.uin || account.lastUin || ''
        if (!rt.client) {
          rt.phase = 'connecting'
          log(rt, `[webui] qq logged in (isOffline=${status?.isOffline}), connecting onebot…`)
          startOneBotClient(rt, account)
        }
        return
      }

      if (rt.phase === 'starting') rt.phase = 'waiting_login'
      if (!rt.qrcodeUrl) {
        try {
          const qr = await getQrcode(account, credential)
          if (qr?.qrcode) {
            rt.qrcodeUrl = String(qr.qrcode)
            log(rt, '[webui] qrcode ready (cache/qrcode.png)')
          }
        } catch (err) {
          log(rt, `[webui] qrcode pending: ${short(err)}`)
        }
      }
      if (status?.loginError) {
        rt.lastError = String(status.loginError)
        log(rt, `[webui] loginError=${status.loginError}`)
      }
    } catch (err) {
      // WebUI 还没起来（QQ 进程启动中）属正常，只在变化时记一句
      log(rt, `[webui] ${webuiBase(account)} not ready: ${short(err)}`)
    } finally {
      rt.polling = false
    }
  }

  /**
   * 入向消息：W1 只做回显，用来证明「云端 → QQ」这条链路通了。
   * 真正的会话编排（建会话 / 调 /messages/stream / 分段回发）留在 W2。
   */
  async function handleIncoming(rt, account, event) {
    try {
      if (event?.post_type !== 'message') return
      if (String(event.user_id) === String(rt.selfId)) return
      const sender = resolveSender(event)
      const target = resolveReplyTarget(event)
      const content = extractMessageContent(event, rt.selfId)
      const text = String(content?.text || '').trim()
      rt.messages.push({
        at: new Date().toISOString(),
        from: sender?.nickname ? `${sender.nickname}(${sender.userId})` : String(sender?.userId || ''),
        scope: target?.kind || 'private',
        text: text.slice(0, MAX_REPLY_CHARS),
        hasImage: Boolean(content?.imageUrl),
      })
      if (rt.messages.length > 50) rt.messages.splice(0, rt.messages.length - 50)

      if (!text) return
      const reply = `[${account.key}] ${text.slice(0, MAX_REPLY_CHARS)}`
      if (target?.kind === 'group') {
        await rt.client.sendGroupMsg(target.groupId, reply)
      } else if (target?.kind === 'private') {
        await rt.client.sendPrivateMsg(target.userId, reply)
      }
      log(rt, `[echo] ${target?.kind} <- ${sender?.userId}: ${text.slice(0, 40)}`)
    } catch (err) {
      log(rt, `[echo] failed: ${short(err)}`)
    }
  }

  function stop(key) {
    const account = readAccount(cfg, key)
    const rt = runtimeOf(key)
    stopTimers(rt)
    try {
      rt.client?.stop()
    } catch {
      /* ignore */
    }
    rt.client = null
    try {
      rt.proc?.stop()
    } catch {
      /* ignore */
    }
    rt.proc = null
    // launcher 早就退出，QQ 进程挂在外面：按本号 profile 精准补杀
    const killed = killQqByProfile(profileDir(cfg, key), { log: (l) => log(rt, l) })
    rt.phase = 'stopped'
    rt.selfId = ''
    rt.qrcodeUrl = ''
    log(rt, `[pool] stopped (killed=${killed.length})`)
    return snapshot(rt, account)
  }

  function remove(key) {
    const rt = runtimeOf(key)
    stop(key)
    const dir = accountDir(cfg, key)
    // 只允许删 accountsRoot 下的直接子目录，防路径穿越
    const root = cfg.accountsRoot.replace(/\\/g, '/').replace(/\/$/, '')
    const target = dir.replace(/\\/g, '/')
    if (!target.startsWith(`${root}/`) || target.slice(root.length + 1).includes('/')) {
      throw new Error(`refuse_remove:${dir}`)
    }
    runtimes.delete(key)
    return dir
  }

  function statusList() {
    return listAccounts(cfg).map((account) => snapshot(runtimeOf(account.key), account))
  }

  function status(key) {
    const account = readAccount(cfg, key)
    if (!account) throw new Error(`account_not_found:${key}`)
    return snapshot(runtimeOf(key), account)
  }

  function qrcode(key) {
    return readQrcodeImage(accountDir(cfg, key))
  }

  function logs(key, tail = 200) {
    const rt = runtimeOf(key)
    return rt.logs.slice(-tail)
  }

  function stopAll() {
    for (const key of runtimes.keys()) {
      try {
        stop(key)
      } catch {
        /* 尽力而为 */
      }
    }
  }

  return { start, stop, remove, status, statusList, qrcode, logs, stopAll }
}

function short(err) {
  const msg = err?.message || String(err)
  return msg.length > 200 ? `${msg.slice(0, 200)}…` : msg
}
