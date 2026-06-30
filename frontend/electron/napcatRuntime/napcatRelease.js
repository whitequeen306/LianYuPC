/**
 * NapCat 运行时——发行解析与安装路径助手（B 方案自管托管）。
 *
 * 职责：
 * - 向 NapCat 官方 GitHub Release 拉取最新 `NapCat.Shell.zip`
 *   资产（URL + sha256 + size），24h 内复用本地缓存，受限网络下回退到版本锚点。
 * - 提供 userData 下的安装根目录、入口（exe/bat）扫描定位，以及"已安装否"的探测
 *   （容忍 Shell zip 内部可能存在的包裹目录层级）。
 *
 * 与 qqBridgeSettings.js 同构：userData 下 JSON 缓存 + 兜底常量。
 * 仅主进程使用；不经过 Electron net/证书 pin（Node fetch 仅系统 CA，可被 MITM）。
 * 故信任不来自传输通道，而来自内置 PINNED_RELEASES 的 out-of-band digest 锚点——
 * 在线 release 的 (tag, sha256) 须命中锚点才被信任，否则回退到 newestPinnedRelease()
 * （见 issue #5：杜绝「校验值与被校验对象走同一无 pin 通道」的供应链 RCE）。
 */
import { app } from 'electron'
import path from 'path'
import fs from 'fs'
import { spawnSync } from 'node:child_process'

const RELEASE_API = 'https://api.github.com/repos/NapNeko/NapCatQQ/releases/latest'
const SHELL_ASSET_NAME = 'NapCat.Shell.zip'

/**
 * GitHub CDN（release-assets.githubusercontent.com）在受限网络下大传输常被 RST 重置
 * （直连整包下载往往 Connection reset，29MB 的 Shell 整包也难幸免）。下列代理镜像返回与
 * GitHub 字节一致的同一文件，故仍由 downloader 的 sha256 锚点强校验兜底——被篡改则
 * mismatch 删档，防 MITM（issue #5 信任链不依赖传输通道）。直连优先（非受限网络更快），
 * 失败再依次走镜像。仅对 github.com 资产追加镜像。
 */
const ASSET_MIRROR_PREFIXES = ['https://ghproxy.net/']
const CACHE_TTL_MS = 24 * 60 * 60 * 1000 // 1 天内复用缓存的发行信息

/**
 * Out-of-band 信任锚点表：每个支持的 NapCat 版本在此硬编码 {tag, assetUrl, sha256, size}。
 * 信任不来自 GitHub 传输通道（Node fetch 仅系统 CA、无证书 pin，可被 MITM）——而来自本表：
 * 在线解析到的 release 其 (tag, sha256) 必须命中本表才被信任，且下载用本表的 assetUrl/
 * sha256（信道外）；否则回退到 newestPinnedRelease()。这样即便 MITM 同时伪造 release
 * JSON 与匹配的恶意 zip，因 digest 不在本表，校验链拒绝信任，杜绝供应链 RCE（issue #5）。
 *
 * 这是「下界兜底」而非「最新」——未被 pin 的新版本不会自动安装，需发版追加锚点。
 * 已装更新版本的用户不应被此锚点降级（见 napcatHost 的 compareReleaseTags 下界守卫：
 * 仅当最新发行严格新于已装版本才提示升级，且回写 napcatVersion 以实际拉起版本为准）。
 * 发版后应定期人工刷新本表（追加新 tag/URL/digest/size）以缩小与真实的差距。
 *
 * 当前锚定 Shell 发行版（NapCat.Shell.zip）而非 OneKey：Shell 的 launcher
 * 显式接收 QQPath+InjectPath+extra，可转发 --user-data-dir 使机器人 QQ 与用户
 * QQ 分 profile 共存；OneKey 的 launcher 仅吃 argv[1] 作 quickLogin，无法转发
 * --user-data-dir，会撞 QQNT 单实例而不初始化（见 napcatHost.resolveLaunchTarget）。
 */
const PINNED_RELEASES = [
  {
    tag: 'v4.18.7',
    assetName: SHELL_ASSET_NAME,
    assetUrl: 'https://github.com/NapNeko/NapCatQQ/releases/download/v4.18.7/NapCat.Shell.zip',
    sha256: '628621ac6333b7c016c1ef213495af39c31ce9c4ce2b8b041ec47b0d8557a3e1',
    size: 29425972,
  },
]

function cachePath() {
  return path.join(app.getPath('userData'), 'napcat-release-cache.json')
}

function readCache() {
  try {
    const raw = fs.readFileSync(cachePath(), 'utf8')
    const parsed = JSON.parse(raw)
    if (parsed && parsed.assetUrl && parsed.sha256) return parsed
    return null
  } catch {
    return null
  }
}

function writeCache(entry) {
  try {
    fs.mkdirSync(path.dirname(cachePath()), { recursive: true })
    fs.writeFileSync(cachePath(), JSON.stringify(entry, null, 2))
  } catch {
    /* 缓存写入失败不影响主流程 */
  }
}

function parseAsset(asset, tag) {
  if (!asset) return null
  const digest = typeof asset.digest === 'string' ? asset.digest : ''
  // GitHub 资产 digest 形如 "sha256:xxxx"；无 digest 视为不可校验，拒绝
  const sha = digest.startsWith('sha256:') ? digest.slice('sha256:'.length).toLowerCase() : ''
  const url = typeof asset.browser_download_url === 'string' ? asset.browser_download_url : ''
  if (!sha || !url) return null
  return {
    tag: String(tag || ''),
    assetName: String(asset.name || SHELL_ASSET_NAME),
    assetUrl: url,
    sha256: sha,
    size: Number(asset.size) || 0,
  }
}

/**
 * 解析最新 Shell 发行信息。优先 GitHub API → 本地缓存（24h）→ 锚点兜底。
 * 在线/缓存结果须经 PINNED_RELEASES 锚点校验（tag+sha256 命中）才信任，否则回退到
 * newestPinnedRelease()——信任来自信道外的内置锚点，而非可被 MITM 的在线通道。
 * @param {{ force?: boolean, signal?: AbortSignal, fetchTimeoutMs?: number }} [opts]
 * @returns {Promise<{ tag: string, assetName: string, assetUrl: string, sha256: string, size: number, fetchedAt: number }>}
 */
export async function resolveLatestNapCatRelease({ force = false, signal, fetchTimeoutMs = 12_000 } = {}) {
  const cached = readCache()
  // 缓存也须命中锚点：防范历史（修前）写入的未 pin digest 残留被静默复用
  if (!force && cached && isPinnedRelease(cached) && Date.now() - (cached.fetchedAt || 0) < CACHE_TTL_MS) {
    return cached
  }
  // 受限网络下 GitHub API 可能 hang（GFW 丢包：连接已建但无响应，fetch 既不 resolve
  // 也不 reject）。若不设上限，doStart 会永久卡在 'resolving-release'，下载阶段无从
  // 进入，UI 也收不到任何进度推送——用户观感为「点击下载无反应/无进度条」。故给在线
  // 解析一个超时上限：到点即 abort 并回退到内置锚点 newestPinnedRelease()，使流程
  // 继续推进到下载阶段（锚点可信，见 issue #5）。signal 联动 stop/重装的外部中断。
  const controller = new AbortController()
  if (signal) {
    if (signal.aborted) controller.abort()
    else signal.addEventListener('abort', () => controller.abort(), { once: true })
  }
  let timeout = null
  try {
    const parsed = await Promise.race([
      fetchOnlineRelease(controller.signal),
      new Promise((_, reject) => {
        timeout = setTimeout(() => {
          controller.abort()
          reject(new Error(`release resolve timeout after ${fetchTimeoutMs}ms`))
        }, fetchTimeoutMs)
      }),
    ])
    // 信任锚点校验：在线 (tag, sha256) 须命中内置 PINNED_RELEASES 才信任。
    // 命中则用锚点的 out-of-band assetUrl/sha256（不用在线返回的，防 MITM 伪造 URL/digest）。
    if (isPinnedRelease(parsed)) {
      const pinned = PINNED_RELEASES.find((p) => p.tag === parsed.tag)
      const entry = { ...pinned, fetchedAt: Date.now() }
      writeCache(entry)
      return entry
    }
    // 在线版本未被 pin：可能是 MITM 伪造，或 NapCat 发了尚未追加锚点的新版。
    // 不信任在线，回退到 newestPinnedRelease()；不写缓存（fetchedAt:0 使下次仍重试在线）。
    console.warn(
      '[napcatHost] online release not in pinned trust anchor, falling back:',
      `online tag=${parsed.tag} sha=${parsed.sha256.slice(0, 12)}…`,
    )
    return { ...newestPinnedRelease(), fetchedAt: 0 }
  } catch (e) {
    if (cached && isPinnedRelease(cached)) {
      console.warn('[napcatHost] release resolve failed, using pinned cache:', e?.message || e)
      return cached
    }
    console.warn('[napcatHost] release resolve failed, using newest pinned anchor:', e?.message || e)
    return { ...newestPinnedRelease(), fetchedAt: 0 }
  } finally {
    if (timeout) clearTimeout(timeout)
  }
}

/**
 * 在线解析最新 Shell 发行（fetch GitHub API → json → parseAsset）。
 * 抽出供 resolveLatestNapCatRelease 与超时 Promise.race 竞速；signal 用于
 * 超时/外部 abort——abort 后 fetch 会以 AbortError reject，使竞速尽快落定。
 */
async function fetchOnlineRelease(signal) {
  const res = await fetch(RELEASE_API, {
    headers: {
      Accept: 'application/vnd.github+json',
      'X-GitHub-Api-Version': '2022-11-28',
      'User-Agent': 'lianyu-desktop', // GitHub 要求带 UA，否则 403
    },
    signal,
  })
  if (!res.ok) throw new Error(`github api status ${res.status}`)
  const json = await res.json()
  const assets = Array.isArray(json?.assets) ? json.assets : []
  const asset = assets.find((a) => a?.name === SHELL_ASSET_NAME)
  const parsed = parseAsset(asset, json?.tag_name || json?.name)
  if (!parsed) throw new Error('shell asset not found in latest release')
  return parsed
}

/**
 * 比较 NapCat 发行 tag 的版本先后（如 'v4.18.7' < 'v4.20.0'）。
 * 忽略前导 'v'；主干按点分数字逐段比；带 '-' 后缀视为预发布，正式版 > 同主干预发布，
 * 预发布之间按后缀字符串比。供 napcatHost 判定"最新发行是否严格新于已装版本"——
 * 下界守卫的核心：避免把过时的锚点误当成"可升级的新版本"而诱导用户降级重装。
 * GitHub /releases/latest 实际只返回正式版，后缀分支仅作兜底。
 * @param {string} a
 * @param {string} b
 * @returns {number} -1（a<b）/ 0（相等）/ 1（a>b）
 */
export function compareReleaseTags(a, b) {
  if (a === b) return 0
  const split = (t) => {
    const s = String(t ?? '').replace(/^v/i, '')
    const [core, pre] = s.split('-', 2)
    return { core: core.split('.'), pre: pre ?? '' }
  }
  const A = split(a)
  const B = split(b)
  const len = Math.max(A.core.length, B.core.length)
  for (let i = 0; i < len; i++) {
    const sa = A.core[i] ?? ''
    const sb = B.core[i] ?? ''
    if (/^\d+$/.test(sa) && /^\d+$/.test(sb)) {
      const xa = Number(sa)
      const xb = Number(sb)
      if (xa !== xb) return xa < xb ? -1 : 1
    } else if (sa !== sb) {
      return sa < sb ? -1 : 1
    }
  }
  // 主干全等：无后缀（正式版）新于有后缀（预发布）；后缀按字符串比保证确定性
  if (A.pre === B.pre) return 0
  if (!A.pre) return 1
  if (!B.pre) return -1
  return A.pre < B.pre ? -1 : 1
}

/**
 * 本应用内置的 out-of-band 信任锚点判定：entry 的 (tag, sha256) 须精确命中
 * PINNED_RELEASES（sha 不分大小写）。供 resolveLatestNapCatRelease 校验在线/缓存结果，
 * 杜绝「校验值与被校验对象走同一无 pin 通道」的供应链 RCE（issue #5）。
 */
function isPinnedRelease(entry) {
  if (!entry || !entry.tag || !entry.sha256) return false
  const sha = String(entry.sha256).toLowerCase()
  return PINNED_RELEASES.some((p) => p.tag === entry.tag && p.sha256.toLowerCase() === sha)
}

/** 锚点表中按 compareReleaseTags 最新的条目；在线不可信/不可达时作为可信下载源。 */
function newestPinnedRelease() {
  return [...PINNED_RELEASES].sort((a, b) => compareReleaseTags(b.tag, a.tag))[0]
}

/**
 * 解析资产的可下载源列表：直连优先，受限网络下追加代理镜像。仅对 github.com 资产
 * 追加镜像（本地/测试地址原样返回单元素列表，避免污染单测）。镜像与直连返回同一文件，
 * 由 downloader 的 sha256 锚点强校验保证完整性——即便镜像被 MITM 篡改也会 mismatch
 * 删档拒绝（issue #5：信任不来自传输通道）。
 * @param {{ assetUrl?: string }} release
 * @returns {string[]}
 */
export function getAssetDownloadUrls(release) {
  const direct = release?.assetUrl
  if (!direct || !/^https:\/\/github\.com\//i.test(direct)) return [direct]
  const urls = [direct]
  for (const p of ASSET_MIRROR_PREFIXES) {
    const u = p + direct
    if (!urls.includes(u)) urls.push(u)
  }
  return urls
}

export function getNapCatInstallRoot() {
  return path.join(app.getPath('userData'), 'napcat')
}

/**
 * 机器人 QQ 的独立 user-data-dir。Shell 启动以此作为 QQ.exe 的 --user-data-dir，
 * 使 NapCat 拉起的机器人 QQ 与用户日常 QQ（默认 user-data-dir）分属两个独立
 * Electron 单实例 profile，从而共存——用户 QQ 无需关闭，机器人号扫码登录后
 * 会话缓存于此目录，下次启动可自动重连（首次仍需扫码）。同号不能双开两台 PC，
 * 故共存仅对独立机器人号有意义。
 */
export function getNapCatBotProfileDir() {
  return path.join(app.getPath('userData'), 'napcat-bot-profile')
}

/**
 * 读注册表定位 QQ 安装目录（QQ.exe 所在目录）。
 * NapCat Shell 的 NapCatWinBootMain.exe 显式接收 QQPath 作首参数（不像 OneKey
 * 按自身目录定位 QQ），但仍需我们解析出 QQ.exe 完整路径传入。注册表查找在
 * 非标准路径（如用户自选的 D:\qq）下可能失效，故依次查多个键并验证目录下确有
 * QQ.exe。供 napcatHost.resolveLaunchTarget 拼装启动命令用。
 * 依次查 WOW6432Node（QQNT 32位重定向视图，最常见）、64位视图、HKCU；
 * 命中后验证目录下确有 QQ.exe，否则视为无效。
 * @returns {string|null}
 */
export function resolveQqInstallDir() {
  const keys = [
    'HKLM\\SOFTWARE\\WOW6432Node\\Tencent\\QQNT',
    'HKLM\\SOFTWARE\\Tencent\\QQNT',
    'HKCU\\SOFTWARE\\Tencent\\QQNT',
  ]
  for (const key of keys) {
    try {
      const r = spawnSync('reg', ['query', key, '/v', 'Install'], {
        windowsHide: true,
        encoding: 'utf8',
      })
      if (r.status === 0 && r.stdout) {
        const m = r.stdout.match(/Install\s+REG_SZ\s+(.+)/)
        if (m) {
          const dir = m[1].trim()
          if (dir && fs.existsSync(path.join(dir, 'QQ.exe'))) return dir
        }
      }
    } catch {
      /* ignore — 试下一个键 */
    }
  }
  return null
}

/**
 * 扫描安装根，定位 NapCat 入口（exe 优先，bat 次之）。
 * 容忍 Shell zip 解压后可能多一层包裹目录；返回的 cwd 为入口所在目录，
 * 使 NapCat 以该目录为根读取 config/，与本模块的 config 写入位置一致。
 * @param {string} [installRoot]
 * @returns {{ exe: string|null, bat: string|null, cwd: string }|null}
 */
export function locateNapCatEntry(installRoot = getNapCatInstallRoot()) {
  function scan(dir, depth) {
    if (!dir || !fs.existsSync(dir)) return null
    let exe = null
    let bat = null
    let entries
    try {
      entries = fs.readdirSync(dir)
    } catch {
      return null
    }
    for (const name of entries) {
      const full = path.join(dir, name)
      let st
      try {
        st = fs.statSync(full)
      } catch {
        continue
      }
      if (st.isFile()) {
        if (name === 'NapCatWinBootMain.exe') exe = full
        else if (name.toLowerCase() === 'napcat.bat') bat = full
      }
    }
    if (exe || bat) return { exe, bat, cwd: dir }
    if (depth > 0) {
      for (const name of entries) {
        const full = path.join(dir, name)
        let st
        try {
          st = fs.statSync(full)
        } catch {
          continue
        }
        if (st.isDirectory()) {
          const r = scan(full, depth - 1)
          if (r) return r
        }
      }
    }
    return null
  }
  return scan(installRoot, 2)
}

export function isNapCatInstalled(installRoot = getNapCatInstallRoot()) {
  return !!locateNapCatEntry(installRoot)
}

/**
 * 清空安装根及其兄弟临时件（.zip.part / .zip.part.json），供升级/重装前调用。
 * 注意：会一并删除 napcat 自身的 config 目录——但 token 持久化在
 * qq-bridge-settings.json 的 hosting 块，ensureNapCatConfig 会在下次启动时
 * 按需重建，故可安全清空。
 */
export function wipeNapCatInstall() {
  const installRoot = getNapCatInstallRoot()
  try {
    fs.rmSync(installRoot, { recursive: true, force: true })
  } catch {
    /* ignore */
  }
  // 下载临时件是安装根的兄弟文件，需单独清理
  for (const ext of ['.zip.part', '.zip.part.json']) {
    try {
      fs.rmSync(installRoot + ext, { force: true })
    } catch {
      /* ignore */
    }
  }
}
