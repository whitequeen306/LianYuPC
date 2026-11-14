/**
 * 账号工作目录与 NapCat 配置生成。
 *
 * 一个号一个 workdir，目录内存放：config/（NapCat 读写）、cache/（二维码图片）、
 * logs/、plugins/、qq-profile/（QQ 的 --user-data-dir，登录态在这里）。
 *
 * 关键机制：NapCat 的 RR 路径包装器优先读 process.env.NAPCAT_WORKDIR
 * （napcat.mjs 内 `process.env.NAPCAT_WORKDIR ? n = ... : n = this.binaryPath`），
 * 所以多个号可以共用同一份 shell（29MB 只存一份），各自只靠 workdir 隔离配置与登录态。
 * 桌面端不设这个环境变量 → 仍走 binaryPath，行为不变。
 */
import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'

const KEY_RE = /^[a-zA-Z0-9][a-zA-Z0-9_-]{0,31}$/
const BYPASS_KEYS = ['hook', 'window', 'module', 'process', 'container', 'js']

export function isValidKey(key) {
  return KEY_RE.test(String(key || ''))
}

export function accountDir(cfg, key) {
  if (!isValidKey(key)) throw new Error(`invalid_account_key:${key}`)
  return path.join(cfg.accountsRoot, key)
}

export function profileDir(cfg, key) {
  return path.join(accountDir(cfg, key), 'qq-profile')
}

export function randomToken() {
  return crypto.randomBytes(16).toString('hex')
}

function writeJson(filePath, obj) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true })
  fs.writeFileSync(filePath, JSON.stringify(obj, null, 2), { encoding: 'utf8', mode: 0o600 })
}

function readJson(filePath) {
  try {
    return JSON.parse(fs.readFileSync(filePath, 'utf8'))
  } catch {
    return null
  }
}

export function readAccount(cfg, key) {
  return readJson(path.join(accountDir(cfg, key), 'account.json'))
}

export function writeAccount(cfg, key, data) {
  writeJson(path.join(accountDir(cfg, key), 'account.json'), data)
  return data
}

export function listAccounts(cfg) {
  if (!fs.existsSync(cfg.accountsRoot)) return []
  return fs
    .readdirSync(cfg.accountsRoot, { withFileTypes: true })
    .filter((d) => d.isDirectory() && isValidKey(d.name))
    .map((d) => readAccount(cfg, d.name))
    .filter(Boolean)
}

/**
 * 创建账号记录（幂等：已存在则原样返回，不重置令牌，避免把在线号踢下线）。
 * 端口从基准值递增挑空闲，已分配的端口靠 reserved 避让。
 */
export async function createAccount(cfg, { key, note = '' }, { allocatePort }) {
  if (!isValidKey(key)) throw new Error(`invalid_account_key:${key}`)
  const existing = readAccount(cfg, key)
  if (existing) return existing

  const reserved = new Set()
  for (const acc of listAccounts(cfg)) {
    reserved.add(acc.wsPort)
    reserved.add(acc.webuiPort)
  }
  const wsPort = await allocatePort(cfg.wsPortBase, { reserved })
  reserved.add(wsPort)
  const webuiPort = await allocatePort(cfg.webuiPortBase, { reserved })

  const account = {
    key,
    note,
    wsPort,
    webuiPort,
    wsToken: randomToken(),
    webuiToken: randomToken(),
    createdAt: new Date().toISOString(),
    lastUin: '',
  }
  return writeAccount(cfg, key, account)
}

/**
 * 幂等生成该号需要的全部 NapCat 配置文件。
 *
 * - onebot11.json：正向 WS 服务端（127.0.0.1 + 本号端口 + 本号 token）
 * - onebot11_<uin>.json：NapCat 首登时从模板复制，之后只读这份；已存在则强制
 *   覆盖 token/端口，否则历史号会拿着旧 token 拒连
 * - webui.json：WebUI 端口/token（文件层兜底；真正强制点是 NAPCAT_WEBUI_SECRET_KEY）
 * - napcat.json：反检测全开 + o3HookMode=1（与桌面端同口径，否则云端更容易被风控）
 * - loadNapCat.js：每号一份，绝对 file:// 指向共享 shell 的 napcat.mjs
 *   （不写进共享 shell，避免多号并发改同一个文件）
 */
export function ensureAccountConfig(cfg, account) {
  const dir = accountDir(cfg, account.key)
  const configDir = path.join(dir, 'config')
  fs.mkdirSync(configDir, { recursive: true })
  for (const sub of ['cache', 'logs', 'plugins', 'qq-profile']) {
    fs.mkdirSync(path.join(dir, sub), { recursive: true })
  }

  const server = {
    name: `gw-${account.key}`,
    enable: true,
    host: '127.0.0.1',
    port: account.wsPort,
    token: account.wsToken,
    messagePostFormat: 'array',
    reportSelfMessage: false,
    enableForcePushEvent: true,
    debug: false,
    heartInterval: 30000,
  }

  const onebotPath = path.join(configDir, 'onebot11.json')
  const existingOnebot = readJson(onebotPath)
  if (existingOnebot) {
    applyServerOverride(existingOnebot, server)
    writeJson(onebotPath, existingOnebot)
  } else {
    writeJson(onebotPath, {
      network: {
        httpServers: [],
        httpClients: [],
        websocketServers: [server],
        websocketClients: [],
        httpSseServers: [],
        plugins: [],
      },
      musicSignUrl: '',
      enableLocalFile2Url: false,
      parseMultMsg: false,
      imageDownloadProxy: '',
    })
  }

  // 历史 per-account 配置（同名号重登）：token/端口与本号现状对齐
  for (const name of fs.readdirSync(configDir)) {
    if (!/^onebot11_.*\.json$/i.test(name)) continue
    const file = path.join(configDir, name)
    const cfgJson = readJson(file)
    if (!cfgJson) continue
    if (applyServerOverride(cfgJson, server)) writeJson(file, cfgJson)
  }

  writeJson(path.join(configDir, 'webui.json'), {
    host: '127.0.0.1',
    port: account.webuiPort,
    token: account.webuiToken,
    loginRate: 10,
    debug: false,
  })

  const napcatPath = path.join(configDir, 'napcat.json')
  const existingNapcat = readJson(napcatPath)
  const napcat = existingNapcat || {
    fileLog: false,
    consoleLog: true,
    fileLogLevel: 'debug',
    consoleLogLevel: 'info',
    packetBackend: 'auto',
    packetServer: '',
    autoTimeSync: true,
  }
  napcat.bypass = napcat.bypass && typeof napcat.bypass === 'object' ? napcat.bypass : {}
  for (const k of BYPASS_KEYS) napcat.bypass[k] = true
  napcat.o3HookMode = 1
  writeJson(napcatPath, napcat)

  const mainUrl = path.join(cfg.shellRoot, 'napcat.mjs').replace(/\\/g, '/')
  fs.writeFileSync(
    path.join(dir, 'loadNapCat.js'),
    `(async () => {await import("file:///${mainUrl}")})();\n`,
    'utf8',
  )

  return { dir, configDir }
}

function applyServerOverride(onebotJson, server) {
  const net = onebotJson?.network
  if (!net || !Array.isArray(net.websocketServers)) {
    if (!net) return false
    net.websocketServers = []
  }
  const srv = net.websocketServers.find((s) => s && s.enable !== false) || net.websocketServers[0]
  if (!srv) {
    net.websocketServers.push({ ...server })
    return true
  }
  let changed = false
  for (const [k, v] of Object.entries({ port: server.port, token: server.token, host: server.host })) {
    if (srv[k] !== v) {
      srv[k] = v
      changed = true
    }
  }
  return changed
}
