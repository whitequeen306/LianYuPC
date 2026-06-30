/**
 * NapCat 运行时——配置生成（B 方案自管托管）。
 *
 * 幂等写入两份配置，并把 token 定死为 lianyupc（用户指定）：
 * - config/onebot11.json：正向 WS 服务，127.0.0.1:3001 + token=lianyupc，enable=true
 *   （文件缺失则新建、已存在则强制覆盖 token；token 不回读旧随机值）
 * - config/onebot11_<uin>.json：per-account 配置（NapCat 首次登录某号时从模板复制生成，
 *   之后 WS 服务读的就是它）。扫描覆盖所有 onebot11_*.json 的 token=lianyupc，否则
 *   历史残留旧号 per-account 带旧随机 token 会让 bridge 用 lianyupc 连时鉴权失败
 * - config/webui.json  ：WebUI 127.0.0.1:6099 + token=lianyupc（文件层兜底）
 *
 * 注意：webui token 的真正强制点不在本文件，而在 spawn env 的 NAPCAT_WEBUI_SECRET_KEY
 * （见 napcatHost.resolveLaunchTarget）——NapCat 启动 init 检测到该 env 会主动
 * UpdateWebUIConfig 把 webui token 改成 lianyupc，必赢过它自身的「默认密码→随机安全
 * 密码」重生成。此处 webui.json 的写入仅作文件层兜底，二者一致故 NapCat 不会再改。
 *
 * 与 desktopSettings.js 同构：userData 下 JSON + try/catch 兜底。
 */
import fs from 'fs'
import path from 'path'
import { getNapCatInstallRoot, locateNapCatEntry } from './napcatRelease.js'

const DEFAULT_WS_PORT = 3001
const DEFAULT_WEBUI_PORT = 6099

/**
 * 解析 NapCat 的 config 目录。优先用已存在的 config/（扫描安装根 ±1 层）；
 * 若尚不存在（首启前），则在安装根下创建 config/ —— NapCat 以 cwd 为根读取。
 */
function resolveConfigDir(installRoot = getNapCatInstallRoot()) {
  const candidates = [
    path.join(installRoot, 'config'),
    path.join(installRoot, 'napcat', 'config'),
  ]
  const entry = locateNapCatEntry(installRoot)
  if (entry?.cwd) {
    // 入口所在目录才是 NapCat 实际的根，config 应在其下
    candidates.unshift(path.join(entry.cwd, 'config'))
  }
  for (const c of candidates) {
    if (fs.existsSync(c)) return c
  }
  const primary = candidates[0]
  try {
    fs.mkdirSync(primary, { recursive: true })
  } catch {
    /* ignore */
  }
  return primary
}

function readJsonIfExists(filePath) {
  try {
    return JSON.parse(fs.readFileSync(filePath, 'utf8'))
  } catch {
    return null
  }
}

/**
 * 幂等确保配置存在。端口在文件缺失时写入、已存在则回读实际端口（保留用户改动）；
 * token 定死为 'lianyupc'（用户指定）——文件缺失时写入此值，已存在则强制覆盖
 * token 字段（不保留旧随机 token），保证登录窗 URL 与 bridge 鉴权统一可预测。
 * @param {{ wsPort?: number, wsToken?: string, webuiPort?: number, webuiToken?: string }} [opts]
 * @returns {{ configDir: string, wsPort: number, wsToken: string, webuiPort: number, webuiToken: string }}
 */
export function ensureNapCatConfig({
  wsPort = DEFAULT_WS_PORT,
  wsToken = 'lianyupc',
  webuiPort = DEFAULT_WEBUI_PORT,
  webuiToken = 'lianyupc',
} = {}) {
  const installRoot = getNapCatInstallRoot()
  const configDir = resolveConfigDir(installRoot)
  try {
    fs.mkdirSync(configDir, { recursive: true })
  } catch {
    /* ignore */
  }

  const onebotPath = path.join(configDir, 'onebot11.json')
  const webuiPath = path.join(configDir, 'webui.json')

  // ---- onebot11.json：正向 WS ----
  let effectiveWsPort = wsPort
  let effectiveWsToken = wsToken
  const existingOnebot = readJsonIfExists(onebotPath)
  if (existingOnebot) {
    const srv = Array.isArray(existingOnebot?.network?.websocketServers)
      ? existingOnebot.network.websocketServers.find((s) => s && s.enable !== false) || existingOnebot.network.websocketServers[0]
      : null
    if (Number.isFinite(srv?.port)) effectiveWsPort = Number(srv.port)
    // token 定死为传入值（lianyupc）：不回读旧 token，旧值不同则回写覆盖
    if (srv && srv.token !== wsToken) {
      srv.token = wsToken
      writeJsonSync(onebotPath, existingOnebot)
    }
  } else {
    const onebot = {
      network: {
        httpServers: [],
        httpClients: [],
        websocketServers: [
          {
            name: 'lianyu-ws',
            enable: true,
            host: '127.0.0.1',
            port: wsPort,
            token: wsToken,
          },
        ],
        websocketClients: [],
      },
      musicSignUrl: '',
      enableLocalFile2Url: false,
      parseMultMsg: false,
    }
    writeJsonSync(onebotPath, onebot)
  }

  // ---- onebot11_<uin>.json：per-account 配置 token 覆盖 ----
  // NapCat 首次登录某 QQ 号时，从 onebot11.json 模板复制生成 onebot11_<uin>.json，
  // 之后 WS 服务读的就是这份 per-account 文件（不再回看模板）。问题：若历史残留
  // per-account 文件带旧随机 token（旧版本生成），仅改 onebot11.json 模板不够——
  // NapCat 仍按 per-account 的旧 token 鉴权 WS，bridge 用 lianyupc 连会被拒。
  // 故扫描 config 目录下所有 onebot11_*.json，统一覆盖其 websocketServers token
  // 为定死值，保证无论 from-zero（无 per-account）还是有残留旧号 per-account，
  // NapCat 实际读取的 WS token 都是 lianyupc，与 bridge 鉴权一致。
  try {
    for (const name of fs.readdirSync(configDir)) {
      if (!/^onebot11_.*\.json$/i.test(name)) continue
      const acctPath = path.join(configDir, name)
      const acct = readJsonIfExists(acctPath)
      if (!acct) continue
      const srv = Array.isArray(acct?.network?.websocketServers)
        ? acct.network.websocketServers.find((s) => s && s.enable !== false) || acct.network.websocketServers[0]
        : null
      if (srv && srv.token !== wsToken) {
        srv.token = wsToken
        writeJsonSync(acctPath, acct)
      }
    }
  } catch {
    /* 目录读取失败不影响主流程 */
  }

  // ---- webui.json：WebUI 登录窗 ----
  let effectiveWebuiPort = webuiPort
  let effectiveWebuiToken = webuiToken
  const existingWebui = readJsonIfExists(webuiPath)
  if (existingWebui) {
    if (Number.isFinite(existingWebui.port)) effectiveWebuiPort = Number(existingWebui.port)
    // token 定死为传入值（lianyupc）：不回读旧 token，旧值不同则回写覆盖
    if (existingWebui.token !== webuiToken) {
      existingWebui.token = webuiToken
      writeJsonSync(webuiPath, existingWebui)
    }
  } else {
    const webui = {
      host: '127.0.0.1',
      port: webuiPort,
      token: webuiToken,
      loginRate: 3,
      debug: false,
    }
    writeJsonSync(webuiPath, webui)
  }

  return {
    configDir,
    wsPort: effectiveWsPort,
    wsToken: effectiveWsToken,
    webuiPort: effectiveWebuiPort,
    webuiToken: effectiveWebuiToken,
  }
}

function writeJsonSync(filePath, obj) {
  try {
    fs.mkdirSync(path.dirname(filePath), { recursive: true })
    fs.writeFileSync(filePath, JSON.stringify(obj, null, 2))
  } catch (e) {
    console.warn('[napcatHost] config write failed:', filePath, e?.message || e)
  }
}
