/**
 * NapCat 运行时——托管编排器（B 方案自管托管）。
 *
 * 串联 napcatRelease/Downloader/Config/Process，对外暴露 start/stop/status：
 *   解析发行 → （未装则）下载解压 → 幂等写配置 → spawn 子进程
 *   → 进程就绪（webui/ready 线索，或我们写入的已知 token 兜底）
 *   → 回调 bridgeStarter 用写入的 wsUrl/accessToken 拉起 qqBridge。
 *
 * 与 qqBridge.js / desktopObserver.js 同构：模块级状态、start/stop 对偶、
 * stop 不擦 token（token 持久化在 qq-bridge-settings.json 的 hosting 块）。
 * 云端鉴权/apiOrigin/conversationId 由 main.js 经 bridgeStarter 闭包注入，
 * 本模块不感知——避免与 qqBridge 形成循环依赖。
 */
import fs from 'fs'
import path from 'path'
import crypto from 'crypto'
import {
  resolveLatestNapCatRelease,
  getNapCatInstallRoot,
  getNapCatBotProfileDir,
  locateNapCatEntry,
  compareReleaseTags,
  getAssetDownloadUrls,
  resolveQqInstallDir,
} from './napcatRelease.js'
import { downloadAndExtractNapCat } from './napcatDownloader.js'
import { ensureNapCatConfig } from './napcatConfig.js'
import { createNapCatProcess } from './napcatProcess.js'
import { writeQqBridgeSettings } from '../qqBridge/qqBridgeSettings.js'
import * as logger from '../logger.js'

let proc = null
let onStatus = null
let onDownload = null
let bridgeStarter = null
let currentSettings = null
let currentRelease = null
let configInfo = null
let webuiInfo = null
let currentState = 'stopped'
let bridgeFired = false
let fallbackTimer = null
let currentUpgrade = null
// 实际拉起的二进制版本（已装用已装版本，全新安装用 release.tag）；区别于
// currentRelease（解析到的发行，可能是过时锚点）——状态条"版本"以此为准
let currentRunningVersion = null
// 并发守卫：进行中的启动 Promise（去重并发 start）+ 可中断下载的 AbortController
let startingPromise = null
let startController = null

function emit(state, extra = {}) {
  currentState = state
  try {
    onStatus?.({ state, ...extra })
  } catch {
    /* 回调异常不影响托管 */
  }
}

function clearFallbackTimer() {
  if (fallbackTimer) {
    clearTimeout(fallbackTimer)
    fallbackTimer = null
  }
}

/**
 * 启动 NapCat 自管运行时。
 * @param {{ settings?: object, onStatus?: (s)=>void, onDownload?: (p)=>void, bridgeStarter?: (ctx:{wsUrl:string,accessToken:string})=>void }} [opts]
 * @returns {Promise<boolean>} 是否进入 running（true=已 spawn；false=失败）
 */
export async function startNapCatHost(opts = {}) {
  // 已在运行：直接成功
  if (proc) return true
  // 启动进行中（下载/配置/spawn 前）：复用同一 Promise，避免并发下载竞争同一 .part
  if (startingPromise) return startingPromise
  startController = new AbortController()
  const signal = startController.signal
  startingPromise = doStart(opts, signal).finally(() => {
    startingPromise = null
    startController = null
  })
  return startingPromise
}

/**
 * 实际启动逻辑。signal 用于在 stop/重装时中断进行中的下载（fetch 抛出后由 catch 收尾）。
 */
async function doStart({ settings, onStatus: statusCb, onDownload: dlCb, bridgeStarter: starter } = {}, signal) {
  onStatus = statusCb
  onDownload = dlCb
  bridgeStarter = starter
  currentSettings = settings || {}
  webuiInfo = null
  currentRunningVersion = null
  bridgeFired = false
  try {
    // 提前探测安装入口：locateNapCatEntry 仅依赖安装根（与发行无关），先算出 entry，
    // 可在发行解析前就向 UI 推送 'preparing' 进度。受限网络下 GitHub API 解析可能
    // 较慢/超时（见 resolveLatestNapCatRelease 的超时回退），若等解析完才发进度，
    // 用户会看到「点击后无进度条/无反应」——故需下载时先发 preparing 占位，让进度条
    // 立即出现；解析完成后 downloader 的 'downloading' 相位会覆盖它。
    const installRoot = getNapCatInstallRoot()
    const entry = locateNapCatEntry(installRoot)
    if (!entry) {
      onDownload?.({ phase: 'preparing', percent: 0 })
    }
    emit('resolving-release')
    const release = await resolveLatestNapCatRelease({ signal })
    currentRelease = release

    const installedVersion = (currentSettings.hosting || {}).napcatVersion || ''
    // 版本升级检测：仅当最新发行严格新于已装版本时才提示升级。
    // 反例——GitHub 不可达回退到 PINNED_RELEASES 锚点（如 v4.18.7）时，若已装
    // 版本更新（如 v4.20.0），锚点是"过时下界"而非"最新"，不应误报升级（否则
    // UI 会拿旧版本当"新版本"诱导用户降级重装）。
    currentUpgrade = entry && installedVersion && compareReleaseTags(release.tag, installedVersion) > 0
      ? { installed: installedVersion, latest: release.tag }
      : null
    // 实际拉起的二进制版本：已装则用已装版本（升级不自动下载，仍跑旧版），
    // 全新安装则用本次下载的 release.tag。回写设置与状态条"版本"均以此为准，
    // 避免把过时锚点写回 napcatVersion 造成"已装更新却记录成旧版"的漂移。
    const runningVersion = entry ? (installedVersion || release.tag) : release.tag
    currentRunningVersion = runningVersion
    if (!entry) {
      emit('downloading')
      await downloadFromCandidates(release, onDownload, signal)
    }
    if (!locateNapCatEntry(installRoot)) {
      throw new Error('napcat entry not found after extract')
    }

    // 配置：token 定死为 lianyupc（用户指定）——登录窗 URL 可预测、bridge 鉴权统一，
    // 不再随机生成、不读 hosting 残留旧 token。ensureNapCatConfig 会把配置文件里
    // 的旧 token 强制覆盖为此值（webui.json / onebot11.json 均如此）。
    const hosting = currentSettings.hosting || {}
    const wsToken = 'lianyupc'
    const webuiToken = 'lianyupc'
    const wsPort = Number(hosting.wsPort) || 3001
    const webuiPort = Number(hosting.webuiPort) || 6099
    emit('writing-config')
    const cfg = ensureNapCatConfig({ wsPort, wsToken, webuiPort, webuiToken })
    configInfo = cfg
    // 把生效的端口/令牌/版本回写设置，保证下次启动复用（免重新生成 token）
    writeQqBridgeSettings({
      hosting: {
        ...(currentSettings.hosting || {}),
        mode: 'auto',
        napcatVersion: runningVersion,
        qqUserId: hosting.qqUserId || '',
        webuiPort: cfg.webuiPort,
        webuiToken: cfg.webuiToken,
        wsPort: cfg.wsPort,
        wsToken: cfg.wsToken,
        consented: hosting.consented !== false,
      },
    })

    // 启动子进程
    const { command, args, cwd, env } = resolveLaunchTarget(installRoot, { webuiToken })
    emit('launching')
    proc = createNapCatProcess({
      command,
      args,
      cwd,
      env,
      maxRestarts: 5,
      onLog: (line) => {
        // 全量上抛子进程 stdout/stderr 到全局日志（含 NapCat 自身报错，如找不到 QQ.exe 的
        // Error Code 2）。此前仅 console.log 不落盘，导致启动失败时日志看不到根因（盲区）。
        logger.info('napcat', line)
      },
      onWebui: (info) => {
        webuiInfo = info
        emit('running', { webui: info, upgrade: currentUpgrade })
        fireBridge()
      },
      onReady: () => {
        if (currentState !== 'running') {
          emit('running', { webui: webuiInfo, upgrade: currentUpgrade })
        }
        fireBridge()
      },
      onExit: ({ expected }) => {
        if (!expected) emit('restarting')
      },
      onError: () => {
        // 子进程崩溃重启耗尽（maxRestarts 次后放弃）：进程已死，须把 host 状态从
        // 'running' 降级为 'error'，否则 getNapCatHostStatus 仍报 running 而桥接 WS
        // 实际已断，状态条误导用户。teardownProc 重置运行态字段（进程已死，stop 为
        // 空操作）；emit 须在 clearCallbacks 之前，确保 error 推送送达（见 stop 注释）。
        emit('error', { error: 'napcat process restart limit reached' })
        teardownProc()
        clearCallbacks()
      },
    })
    proc.start()

    // 兜底：若 stdout 未解析出 webui（GUI 入口无控制台输出），用我们写入的
    // 已知 token 直接进入 running 并拉起桥接——登录窗据此 URL 装载即可
    clearFallbackTimer()
    fallbackTimer = setTimeout(() => {
      fallbackTimer = null
      if (!proc || bridgeFired) return
      const info = {
        port: cfg.webuiPort,
        token: cfg.webuiToken,
        url: `http://127.0.0.1:${cfg.webuiPort}/webui?token=${cfg.webuiToken}`,
      }
      webuiInfo = info
      emit('running', { webui: info, upgrade: currentUpgrade })
      fireBridge()
    }, 8000)

    return true
  } catch (e) {
    if (signal?.aborted) {
      // 主动 stop 触发的中断：不报错，由 stopNapCatHost 收尾并推送 stopped
      return false
    }
    console.warn('[napcatHost] start failed:', e?.message || e)
    emit('error', { error: e?.message || String(e) })
    teardownProc()
    clearCallbacks()
    return false
  }
}

/**
 * 按候选源顺序下载 NapCat：直连优先，受限网络下 GitHub CDN 常对整包传输做 RST 重置
 * （29MB 的 Shell 整包也难幸免，受限网络用户会卡在「下载无进度/失败」）。失败（非 abort）
 * 则切换到 getAssetDownloadUrls 给出的镜像继续重试；任一源 sha256 校验通过即视为成功。
 *
 * 切换 assetUrl 会使 downloader 的 sidecar 续传检查（assetUrl 须一致）失败 → 清掉旧
 * .part 从新源整包重下——这是期望行为（避免拿半个直连残片续到镜像上造成哈希错位）。
 * signal.aborted（stop/重装主动中断）直接抛出，不进入下一源。
 */
async function downloadFromCandidates(release, onDownload, signal) {
  const urls = getAssetDownloadUrls(release)
  let lastErr = null
  for (const url of urls) {
    if (signal?.aborted) throw new Error('download aborted')
    try {
      await downloadAndExtractNapCat({
        release: { ...release, assetUrl: url },
        onProgress: (p) => onDownload?.(p),
        signal,
      })
      return
    } catch (e) {
      if (signal?.aborted) throw e // 主动中断：不重试，交给 doStart 的 catch 收尾
      lastErr = e
      console.warn(`[napcatHost] download from ${url} failed, trying next source:`, e?.message || e)
    }
  }
  throw lastErr || new Error('download failed: no candidate sources')
}
export { downloadFromCandidates }

/**
 * 解析 Shell 启动的 napcat.mjs 路径。Shell zip 顶层有 napcat.mjs；少数构建把它
 * 放在 napcat/ 子目录（与出厂 loadNapCat.js 的 ./napcat/napcat.mjs 引用一致）。
 * 两者皆未命中时退回顶层路径，由运行时 import 抛出清晰错误。
 */
function resolveNapCatMainMjs(shellRoot) {
  const top = path.join(shellRoot, 'napcat.mjs')
  if (fs.existsSync(top)) return top
  const sub = path.join(shellRoot, 'napcat', 'napcat.mjs')
  if (fs.existsSync(sub)) return sub
  return top
}

/**
 * 改写 Shell 的 loadNapCat.js 使其 import 本地 napcat.mjs——与官方 launcher.bat
 * 的 `echo (async()=>{await import("file:///<NAPCAT_MAIN_PATH>")})() > loadNapCat.js`
 * 等价。出厂 loadNapCat.js 引用 ./napcat/napcat.mjs，与实际顶层布局不符，不改写
 * 则 QQNT 加载 main 时 import 404、napcat 核心不启动、webui 不起。幂等：内容一致
 * 则跳过，避免每次启动无谓写盘（也避免与 NapCat 自身回写互相覆盖）。
 */
function rewriteLoadNapCatJs(shellRoot, mainMjs) {
  const loadPath = path.join(shellRoot, 'loadNapCat.js')
  const mainUrl = mainMjs.replace(/\\/g, '/')
  const desired = `(async () => {await import("file:///${mainUrl}")})();`
  try {
    if (fs.existsSync(loadPath) && fs.readFileSync(loadPath, 'utf8') === desired) return
    fs.writeFileSync(loadPath, desired)
  } catch (e) {
    console.warn('[napcatHost] rewrite loadNapCat.js failed:', e?.message || e)
  }
}

/**
 * 解析 Shell 启动命令。Shell 的 NapCatWinBootMain.exe 接收三参数
 *   `<QQPath> <InjectPath> <extra...>`
 * 并把 extra 原样追加进 QQ.exe 命令行（--enable-logging -q <extra>），故传
 *   --user-data-dir=<botProfile>
 * 即可使机器人 QQ 跑在独立 profile，与用户日常 QQ（默认 user-data-dir）分属两个
 * Electron 单实例、共存——用户 QQ 无需关闭。env 设 NAPCAT_* 与官方 launcher.bat
 * 一致（hook 实际按自身模块路径相对寻址，env 为冗余兜底，不设亦可，设之无副作用）。
 * cwd=shellRoot 使 NapCat 以该目录为根读 config/（与 napcatConfig 写入位置一致）。
 */
function resolveLaunchTarget(installRoot, { webuiToken = 'lianyupc' } = {}) {
  const entry = locateNapCatEntry(installRoot)
  if (!entry || !entry.exe || !fs.existsSync(entry.exe)) {
    throw new Error(`napcat entry (NapCatWinBootMain.exe) not found under ${installRoot}`)
  }
  const shellRoot = entry.cwd
  const mainMjs = resolveNapCatMainMjs(shellRoot)
  rewriteLoadNapCatJs(shellRoot, mainMjs)

  const hookPath = path.join(shellRoot, 'NapCatWinBootHook.dll')
  if (!fs.existsSync(hookPath)) {
    throw new Error(`napcat hook dll not found: ${hookPath}`)
  }
  const qqDir = resolveQqInstallDir()
  if (!qqDir) {
    throw new Error('QQ install dir not found (注册表无 Tencent\\QQNT Install 键，或目录下无 QQ.exe)')
  }
  const qqExe = path.join(qqDir, 'QQ.exe')
  if (!fs.existsSync(qqExe)) {
    throw new Error(`QQ.exe not found at ${qqExe}`)
  }
  const botProfile = getNapCatBotProfileDir()
  try {
    fs.mkdirSync(botProfile, { recursive: true })
  } catch {
    /* ignore — 启动时再由 QQ 创建 */
  }

  const args = [qqExe, hookPath, `--user-data-dir=${botProfile}`]
  const env = {
    ...process.env,
    NAPCAT_PATCH_PACKAGE: path.join(shellRoot, 'qqnt.json'),
    NAPCAT_LOAD_PATH: path.join(shellRoot, 'loadNapCat.js'),
    NAPCAT_INJECT_PATH: hookPath,
    NAPCAT_LAUNCHER_PATH: entry.exe,
    NAPCAT_MAIN_PATH: mainMjs.replace(/\\/g, '/'),
    // 定死 WebUI token：NapCat 启动 init 检测到此 env 会主动 UpdateWebUIConfig 把
    // webui.json 的 token 强制更新为此值（见 napcat.mjs WebUI init：
    //   if (process.env.NAPCAT_WEBUI_SECRET_KEY && i.token !== env) UpdateWebUIConfig({token: env})
    // ）。这是 NapCat 官方的强制 token 机制，比预写配置文件可靠得多——预写的
    // lianyupc 会被 NapCat 的「默认密码→随机安全密码」重生成或被旧文件残留覆盖，
    // 而 env 在 NapCat 自身 init 内执行，必赢。登录窗 URL 据此可预测、不再 invalid。
    NAPCAT_WEBUI_SECRET_KEY: webuiToken,
  }
  return { command: entry.exe, args, cwd: shellRoot, env }
}

/**
 * QQNT 预检：注册表能否定位 QQ 安装目录且目录下确有 QQ.exe。
 * 供 main.js 的 start/reinstall handler 在调 startNapCatHost 前预检——QQNT 未装时
 * 直接返回 qqnt_not_found，不白下 28MB NapCat（下了也跑不起来，只会到
 * resolveLaunchTarget 抛「QQ install dir not found」再被吞成误导性的 start_failed）。
 */
export function isQqntReady() {
  const qqDir = resolveQqInstallDir()
  return !!(qqDir && fs.existsSync(path.join(qqDir, 'QQ.exe')))
}

function fireBridge() {
  if (bridgeFired || !bridgeStarter || !configInfo) return
  bridgeFired = true
  const wsUrl = `ws://127.0.0.1:${configInfo.wsPort}`
  const accessToken = configInfo.wsToken
  try {
    bridgeStarter({ wsUrl, accessToken })
  } catch (e) {
    console.warn('[napcatHost] bridge starter error:', e?.message || e)
    // 桥接启动失败不致命：进程仍在，用户可在设置页重试
    bridgeFired = false
  }
}

// 同步收尾：杀进程 + 清定时器 + 重置运行态字段。不触碰 startingPromise/startController
// （供 doStart 错误路径调用，避免 await 自身 Promise 死锁），也不置空回调——
// 调用方须在 clearCallbacks() 之前完成 emit，确保推送送达（修复 stop 推送丢失）
function teardownProc() {
  clearFallbackTimer()
  if (proc) {
    try {
      proc.stop()
    } catch {
      /* ignore */
    }
    proc = null
  }
  webuiInfo = null
  configInfo = null
  currentRelease = null
  currentUpgrade = null
  currentRunningVersion = null
  currentSettings = null
}

// 置空回调；须在 emit 之后调用，否则推送被丢
function clearCallbacks() {
  onStatus = null
  onDownload = null
  bridgeStarter = null
  bridgeFired = false
}

export async function stopNapCatHost() {
  // 中断进行中的启动：abort 让 doStart 的 fetch 抛出；await inflight 确保 .part 写流
  // 彻底关闭，避免紧随其后的 wipe 撞未释放句柄（Windows EBUSY）
  const inflight = startingPromise
  if (startController) {
    try { startController.abort() } catch { /* ignore */ }
  }
  if (inflight) {
    try { await inflight } catch { /* 中断已由 abort 预期，吞掉 */ }
  }
  startController = null
  startingPromise = null
  // 先 teardownProc 重置运行态字段（webuiInfo/configInfo 等置空），再 emit('stopped')
  // 推送——此时 onStatus 仍在，状态条收到 clean 的 stopped（webui=null），最后
  // clearCallbacks 置空回调。顺序保证：推送送达 + 字段干净 + 回调不残留。
  teardownProc()
  // 清理 bot QQ 登录态：napcat-bot-profile 目录残留旧号 session，会导致下次启动
  // NapCat 自动快速登录旧号、撞 QQNT 单实例/session 残留而报「已登录无法重复登录」
  // （即使 bot 实际未在线）。停止托管=彻底下线，清掉 profile 让下次启动走扫码，
  // 对齐「用户只需扫码」的全自动理念。进程已在 teardownProc 杀掉，文件句柄已释放。
  try {
    fs.rmSync(getNapCatBotProfileDir(), { recursive: true, force: true })
  } catch (e) {
    console.warn('[napcatHost] clear bot profile failed:', e?.message || e)
  }
  // 清 hosting.qqUserId 残留：该字段原意是 quick-login 参数，但 resolveLaunchTarget
  // 未实际使用它（死代码），停止时清空，避免误导状态判断/下次启动误判已登录。
  try {
    writeQqBridgeSettings({ hosting: { qqUserId: '' } })
  } catch {
    /* ignore */
  }
  emit('stopped')
  clearCallbacks()
}

export function getNapCatHostStatus() {
  return {
    state: currentState,
    webui: webuiInfo,
    version: currentRunningVersion || currentRelease?.tag || '',
    config: configInfo,
    upgrade: currentUpgrade,
  }
}
