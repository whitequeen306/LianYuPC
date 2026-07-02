/**
 * 全局日志模块 —— 主进程共享。
 *
 * 统一记录 main / qqBridge / napcatHost / renderer / uncaughtException 等所有来源日志，
 * 带级别（DEBUG/INFO/WARN/ERROR）和自动轮转（10MB/文件，保留 5 个轮转文件）。
 *
 * 日志文件：userData/logs/app.log
 * 格式：[ISO时间] [LEVEL] [tag] 消息
 *
 * 同时兼容旧 startup.log（导出时一并读取）。
 */
import fs from 'fs'
import path from 'path'
import { app } from 'electron'

const MAX_LOG_SIZE = 10 * 1024 * 1024 // 10MB 单文件上限
const MAX_ROTATED = 5 // 保留 5 个轮转文件 (app.1.log ~ app.5.log)

// ---- 路径 ----

function getLogDir() {
  return path.join(app.getPath('userData'), 'logs')
}

function getLogPath() {
  return path.join(getLogDir(), 'app.log')
}

function getRotatedPath(i) {
  return path.join(getLogDir(), `app.${i}.log`)
}

/** 旧日志路径（向后兼容：导出时一并读取） */
function getLegacyLogPath() {
  return path.join(app.getPath('userData'), 'startup.log')
}

// ---- 写入 ----

function stringifyArg(a) {
  if (a == null) return String(a)
  if (a instanceof Error) return a.stack || a.message || String(a)
  if (typeof a === 'string') return a
  if (typeof a === 'number' || typeof a === 'boolean') return String(a)
  try {
    return JSON.stringify(a, null, 0)
  } catch {
    return String(a)
  }
}

function rotateIfNeeded() {
  try {
    const stat = fs.statSync(getLogPath())
    if (stat.size < MAX_LOG_SIZE) return
    // 删除最老的轮转文件
    try { fs.unlinkSync(getRotatedPath(MAX_ROTATED)) } catch { /* 不存在则忽略 */ }
    // 逐个后移: app.(n-1).log → app.n.log
    for (let i = MAX_ROTATED - 1; i >= 1; i--) {
      const src = getRotatedPath(i)
      const dst = getRotatedPath(i + 1)
      try {
        if (fs.existsSync(src)) fs.renameSync(src, dst)
      } catch { /* ignore */ }
    }
    // 当前 → app.1.log
    try { fs.renameSync(getLogPath(), getRotatedPath(1)) } catch { /* ignore */ }
  } catch {
    // 文件不存在，无需轮转
  }
}

/**
 * 核心日志写入。
 * @param {string} level - DEBUG | INFO | WARN | ERROR
 * @param {string} tag - 来源标签（main / qqBridge / napcatHost / renderer / vue-error 等）
 * @param {...any} args - 日志内容
 */
export function log(level, tag, ...args) {
  const ts = new Date().toISOString()
  const msg = args.map(stringifyArg).join(' ')
  const line = `[${ts}] [${level.toUpperCase()}] [${tag}] ${msg}\n`
  // stdout 镜像（开发可见、生产 stdout 也有）
  const consoleMsg = line.trimEnd()
  if (level === 'ERROR') console.error(consoleMsg)
  else if (level === 'WARN') console.warn(consoleMsg)
  else console.log(consoleMsg)

  try {
    fs.mkdirSync(getLogDir(), { recursive: true })
    rotateIfNeeded()
    fs.appendFileSync(getLogPath(), line)
  } catch {
    // 写入失败不可抛（避免日志自身导致崩溃）
  }
}

export function debug(tag, ...args) { log('DEBUG', tag, ...args) }
export function info(tag, ...args) { log('INFO', tag, ...args) }
export function warn(tag, ...args) { log('WARN', tag, ...args) }
export function error(tag, ...args) { log('ERROR', tag, ...args) }

// ---- 全局错误捕获 ----

let errorHandlersInitialized = false

/**
 * 注册主进程全局错误处理器。应在 app.whenReady() 后调用。
 * 捕获 uncaughtException、unhandledRejection、render-process-gone、child-process-gone。
 */
export function initGlobalErrorHandlers() {
  if (errorHandlersInitialized) return
  errorHandlersInitialized = true

  process.on('uncaughtException', (err) => {
    error('uncaughtException', err?.stack || err?.message || String(err))
  })

  process.on('unhandledRejection', (reason) => {
    error('unhandledRejection', reason?.stack || reason?.message || String(reason))
  })

  app.on('render-process-gone', (_event, webContents, details) => {
    try {
      const url = webContents?.getURL?.() || 'unknown'
      error('render-process-gone', `url=${url}`, JSON.stringify(details))
    } catch {
      error('render-process-gone', JSON.stringify(details))
    }
  })

  app.on('child-process-gone', (_event, details) => {
    warn('child-process-gone', JSON.stringify(details))
  })

  info('logger', 'global error handlers installed')
}

// ---- 读取 / 导出 ----

/**
 * 读取所有日志文件内容（轮转文件 + 当前 + 旧 startup.log），返回尾部 maxLines 行。
 * @param {number} [maxLines=10000]
 * @returns {string}
 */
export function getLogContent(maxLines = 10000) {
  const files = []

  // 旧 startup.log（如果存在）
  const legacy = getLegacyLogPath()
  if (fs.existsSync(legacy)) files.push(legacy)

  // 轮转文件（从老到新: app.5 → app.1）
  for (let i = MAX_ROTATED; i >= 1; i--) {
    const p = getRotatedPath(i)
    if (fs.existsSync(p)) files.push(p)
  }

  // 当前日志
  if (fs.existsSync(getLogPath())) files.push(getLogPath())

  const parts = []
  for (const f of files) {
    try {
      const content = fs.readFileSync(f, 'utf8')
      if (content) {
        parts.push(`=== ${path.basename(f)} ===`)
        parts.push(content)
      }
    } catch { /* ignore */ }
  }

  const all = parts.join('\n')
  const lines = all.split(/\r?\n/).filter(Boolean)
  return lines.slice(-maxLines).join('\n')
}

/**
 * 把全部日志写入指定文件。
 * @param {string} filePath
 * @returns {{ ok: boolean, bytes?: number, error?: string }}
 */
export function exportLogs(filePath) {
  try {
    const content = getLogContent(100000)
    fs.writeFileSync(filePath, content, 'utf8')
    return { ok: true, bytes: Buffer.byteLength(content, 'utf8') }
  } catch (e) {
    return { ok: false, error: e?.message || String(e) }
  }
}

/**
 * 返回日志目录路径（供 shell.openPath 使用）。
 * @returns {string}
 */
export function getLogDirPath() {
  return getLogDir()
}
