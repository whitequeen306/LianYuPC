/**
 * 启动器：把已解析的目标交给系统打开。
 *
 * 只用 `shell.openPath` / `shell.openExternal`，**不经过 shell 拼接** ——
 * 模型给的应用名永远不会变成一条命令行（这是「打开应用」最容易出的安全洞）。
 * 应用名本身还要过白名单/黑名单（在 `policy.js`），这里是第二道闸。
 *
 * 一个真实踩到的坑：`appIndex` 的内置别名给的是**裸 exe 名**（`notepad.exe`），
 * 而 `shell.openPath` 要的是**完整路径**——直接传会永远返回 `app_not_installed`。
 * 所以这里先用 `where.exe` 把裸名解析成绝对路径（解析不到就如实报错，不猜）。
 */

import { execFileSync as execFileSyncDefault } from 'node:child_process'
import pathDefault from 'node:path'

const URL_RE = /^https?:\/\//i
const PROTOCOL_RE = /^[a-z][a-z0-9+.-]*:$/i

/**
 * 裸 exe 名 → 绝对路径。解析不到返回 null（调用方据此报 app_not_installed）。
 * 只查 `where.exe`（PATH + App Paths），不接受任何 shell 展开。
 */
export function createExeResolver({ execFileSyncImpl = execFileSyncDefault, log = () => {} } = {}) {
  const cache = new Map()
  return function resolveExe(target) {
    const name = String(target || '').trim()
    if (!name) return null
    // 已带路径分隔符或盘符：原样当作路径
    if (name.includes('/') || name.includes('\\') || /^[a-zA-Z]:/.test(name)) return name
    if (cache.has(name)) return cache.get(name)
    let resolved = null
    try {
      const out = execFileSyncImpl('where.exe', [name], { encoding: 'utf8', timeout: 3000, windowsHide: true })
      resolved = String(out).split(/\r?\n/).map((s) => s.trim()).filter(Boolean)[0] || null
    } catch (e) {
      log(`exe resolve failed for ${name}: ${e?.message || e}`)
      resolved = null
    }
    cache.set(name, resolved)
    return resolved
  }
}

export function createLauncher({ index, shell, log = () => {}, resolveExe = createExeResolver({ log }) } = {}) {
  if (!index || !shell) throw new Error('launcher: index/shell required')

  return {
    async launch({ app, open } = {}) {
      const name = String(app || '').trim()
      if (!name) return { ok: false, error: 'action_failed', detail: 'app required' }

      // 1) 先查应用索引（内置别名 / 开始菜单 / App Paths）
      // 2) 索引没命中，但名字本身就是可执行文件（chrome.exe / notepad.exe）→ 直接按 PATH 解析。
      //    真实踩到：模型说「notepad.exe」时 appIndex 里没有这个键，会被误报 app_not_installed。
      const resolved = index.resolve(name)
      const looksLikeExe = /\.exe$/i.test(name)
      const target = resolved || (looksLikeExe ? name : null)
      if (!target) {
        log(`launch: unresolved app ${name}`)
        return { ok: false, error: 'app_not_installed' }
      }
      if (!resolved && looksLikeExe) {
        log(`launch: index miss for ${name}, treating as executable name`)
      }

      // ms-settings: 这类协议用 openExternal
      if (PROTOCOL_RE.test(target) && !/^[a-zA-Z]:/.test(target)) {
        await shell.openExternal(target)
        return { ok: true, target }
      }

      // 裸 exe 名（内置别名）→ 必须先解析成绝对路径，否则 openPath 必然失败
      const execPath = resolveExe(target)
      if (!execPath) {
        log(`launch: exe not found on PATH for ${target}`)
        return { ok: false, error: 'app_not_installed', detail: target }
      }

      const failure = await shell.openPath(execPath)
      if (failure) {
        log(`launch: openPath failed for ${execPath}: ${failure}`)
        return { ok: false, error: 'app_not_installed', detail: failure }
      }

      const extra = String(open || '').trim()
      if (extra) {
        if (URL_RE.test(extra)) {
          await shell.openExternal(extra)
        } else {
          const extraFailure = await shell.openPath(extra)
          if (extraFailure) log(`launch: open target failed: ${extraFailure}`)
        }
      }
      return { ok: true, target: execPath }
    },
  }
}
