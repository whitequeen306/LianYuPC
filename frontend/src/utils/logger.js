/**
 * 渲染进程日志 —— 通过 IPC 转发到主进程 logger。
 *
 * 生产环境 console.* 被 antiDebug 吞掉，本模块走独立 IPC 通道（desktop:renderer-log）
 * 把日志送到主进程落盘。electronAPI 不可用时安全 no-op（如纯 Web 或开发模式）。
 */

function getAPI() {
  return typeof window !== 'undefined' ? window.electronAPI : undefined
}

function stringifyArg(a) {
  if (a == null) return String(a)
  if (a instanceof Error) return a.stack || a.message || String(a)
  if (typeof a === 'string') return a
  if (typeof a === 'number' || typeof a === 'boolean') return String(a)
  try {
    return JSON.stringify(a)
  } catch {
    return String(a)
  }
}

function send(level, tag, ...args) {
  try {
    const msg = args.map(stringifyArg).join(' ')
    getAPI()?.rendererLog?.(level, tag, msg)
  } catch {
    // IPC 失败不可抛（避免日志自身导致渲染崩溃）
  }
}

export function log(level, tag, ...args) {
  send(level, tag, ...args)
}

export function debug(tag, ...args) {
  send('DEBUG', tag, ...args)
}

export function info(tag, ...args) {
  send('INFO', tag, ...args)
}

export function warn(tag, ...args) {
  send('WARN', tag, ...args)
}

export function error(tag, ...args) {
  send('ERROR', tag, ...args)
}
