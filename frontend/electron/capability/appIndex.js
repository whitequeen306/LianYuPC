/**
 * 应用索引：把「网易云音乐 / 记事本 / WeChat」这类人话解析成可启动的目标。
 *
 * 三层来源，按可靠性排序：
 * 1. **内置别名**（中文常用名 → 可执行名）—— 覆盖陪伴场景里 90% 的说法，零 IO；
 * 2. **开始菜单 `.lnk` 扫描** —— 用户实际安装过的应用都会在这里；
 * 3. **注册表 App Paths** —— 兜底（只在前面都没命中时才查，避免每次启动都跑 reg）。
 *
 * 只返回「解析结果」，不负责启动（见 `launcher.js`）—— 这样索引可以纯函数单测。
 */

import fsDefault from 'node:fs'
import pathDefault from 'node:path'
import { execFileSync as execFileSyncDefault } from 'node:child_process'

/** 陪伴场景高频应用的直接映射（避免为一个「打开记事本」去扫开始菜单） */
export const BUILTIN_ALIASES = {
  记事本: 'notepad.exe',
  计算器: 'calc.exe',
  画图: 'mspaint.exe',
  资源管理器: 'explorer.exe',
  文件管理器: 'explorer.exe',
  任务管理器: 'taskmgr.exe',
  浏览器: 'msedge.exe',
  微信: 'WeChat.exe',
  网易云: 'cloudmusic.exe',
  网易云音乐: 'cloudmusic.exe',
  qq音乐: 'QQMusic.exe',
  设置: 'ms-settings:',
  notepad: 'notepad.exe',
  calculator: 'calc.exe',
  explorer: 'explorer.exe',
  paint: 'mspaint.exe',
}

const MAX_ENTRIES = 2000
const MAX_SCAN_DEPTH = 4

function walkLinks(dir, out, depth, fsImpl, pathImpl) {
  if (depth > MAX_SCAN_DEPTH || out.size >= MAX_ENTRIES) return
  let entries
  try {
    entries = fsImpl.readdirSync(dir, { withFileTypes: true })
  } catch {
    return
  }
  for (const entry of entries) {
    if (out.size >= MAX_ENTRIES) return
    const full = pathImpl.join(dir, entry.name)
    if (entry.isDirectory()) {
      walkLinks(full, out, depth + 1, fsImpl, pathImpl)
      continue
    }
    if (!/\.lnk$/i.test(entry.name)) continue
    const key = entry.name.replace(/\.lnk$/i, '').trim().toLowerCase()
    if (key && !out.has(key)) out.set(key, full)
  }
}

export function createAppIndex({
  fsImpl = fsDefault,
  pathImpl = pathDefault,
  env = process.env,
  execFileSyncImpl = execFileSyncDefault,
  log = () => {},
} = {}) {
  let startMenuIndex = null
  let appPathsIndex = null

  function buildStartMenuIndex() {
    const map = new Map()
    const roots = [
      env.ProgramData ? pathImpl.join(env.ProgramData, 'Microsoft', 'Windows', 'Start Menu', 'Programs') : '',
      env.APPDATA ? pathImpl.join(env.APPDATA, 'Microsoft', 'Windows', 'Start Menu', 'Programs') : '',
    ].filter(Boolean)
    for (const root of roots) walkLinks(root, map, 0, fsImpl, pathImpl)
    log(`app index: ${map.size} start-menu entries`)
    return map
  }

  function buildAppPathsIndex() {
    const map = new Map()
    try {
      const out = execFileSyncImpl('reg', [
        'query',
        'HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths',
        '/s',
      ], { encoding: 'utf8', timeout: 4000, windowsHide: true })
      let current = ''
      for (const rawLine of String(out).split(/\r?\n/)) {
        const line = rawLine.trim()
        const keyMatch = line.match(/App Paths\\([^\\]+)$/i)
        if (keyMatch) {
          current = keyMatch[1].toLowerCase()
          continue
        }
        const valueMatch = line.match(/REG_SZ\s+(.+)$/i)
        if (current && valueMatch && !map.has(current)) {
          map.set(current, valueMatch[1].trim())
        }
      }
    } catch (e) {
      log(`app index: reg query failed (${e?.message || e})`)
    }
    return map
  }

  /**
   * @param {string} name 用户/模型给的应用名
   * @param {object} [options]
   * @param {boolean} [options.skipBuiltin] 跳过内置别名，只查「开始菜单 / App Paths」
   *
   * 为什么要这个开关：内置别名给的是**裸 exe 名**（`cloudmusic.exe`），而很多应用
   * 装完不在 PATH 上（例如网易云装在 `D:\网易云\CloudMusic\cloudmusic.exe`）。
   * 这时需要能跳区别名、直接走开始菜单的 `.lnk` 兜底。
   */
  function resolve(name, { skipBuiltin = false } = {}) {
    const raw = String(name || '').trim()
    if (!raw) return null
    const key = raw.toLowerCase()

    if (!skipBuiltin) {
      const alias = BUILTIN_ALIASES[raw] || BUILTIN_ALIASES[key]
      if (alias) return alias
    }

    if (!startMenuIndex) startMenuIndex = buildStartMenuIndex()
    if (startMenuIndex.has(key)) return startMenuIndex.get(key)

    // 包含匹配：取最短的那个，避免「网易云音乐」命中「网易云音乐 帮助」
    let best = null
    for (const [entryKey, value] of startMenuIndex) {
      if (!entryKey.includes(key)) continue
      if (!best || entryKey.length < best.key.length) best = { key: entryKey, value }
    }
    if (best) return best.value

    if (!appPathsIndex) appPathsIndex = buildAppPathsIndex()
    if (appPathsIndex.has(key)) return appPathsIndex.get(key)
    for (const [entryKey, value] of appPathsIndex) {
      if (entryKey.includes(key) || key.includes(entryKey.replace(/\.exe$/, ''))) return value
    }
    return null
  }

  return {
    resolve,
    /** 测试/诊断用：不触发扫描也能看规模 */
    stats: () => ({
      startMenu: startMenuIndex ? startMenuIndex.size : -1,
      appPaths: appPathsIndex ? appPathsIndex.size : -1,
    }),
  }
}
