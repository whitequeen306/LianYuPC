/**
 * AgentEngine 下载 + sha256 校验 + 解压。
 *
 * 大文件不走 performApiRequest（其把响应攒进内存且有 20MB 上限），
 * 用 Electron net.request 流式落盘（与应用更新同一证书 pin / 出口白名单通道）。
 */
import fs from 'fs'
import path from 'path'
import crypto from 'crypto'
import { net } from 'electron'
import extract from 'extract-zip'
import {
  getEngineRoot,
  getEngineVersionDir,
  getEnginePartPath,
  findEngineExe,
  isEngineInstallIntact,
  readInstalledMeta,
  writeInstalledMeta,
  MAX_ENGINE_ZIP_BYTES,
} from './engineRelease.js'

const STALL_MS = 60_000

function rmQuiet(target) {
  try {
    fs.rmSync(target, { recursive: true, force: true })
  } catch {
    /* ignore */
  }
}

async function sha256File(filePath) {
  const hash = crypto.createHash('sha256')
  const rs = fs.createReadStream(filePath, { highWaterMark: 64 * 1024 })
  for await (const chunk of rs) hash.update(chunk)
  return hash.digest('hex')
}

function toRelPath(absPath) {
  return path.relative(getEngineRoot(), absPath).split(path.sep).join('/')
}

/**
 * 流式下载到 destPath，边下边限制体积。返回落盘字节数。
 */
export function downloadToFile({ url, destPath, expectedSize = 0, onProgress, stallMs = STALL_MS }) {
  return new Promise((resolve, reject) => {
    fs.mkdirSync(path.dirname(destPath), { recursive: true })
    const req = net.request({ method: 'GET', url })
    const ws = fs.createWriteStream(destPath)
    let received = 0
    let settled = false
    let stallTimer = null
    const finish = (fn, value) => {
      if (settled) return
      settled = true
      if (stallTimer) clearTimeout(stallTimer)
      fn(value)
    }
    const touchStall = () => {
      if (stallTimer) clearTimeout(stallTimer)
      stallTimer = setTimeout(() => {
        try { req.abort() } catch { /* ignore */ }
        ws.destroy()
        finish(reject, new Error(`download stalled: no progress for ${stallMs}ms`))
      }, stallMs)
    }

    ws.on('error', (err) => finish(reject, err))
    req.on('error', (err) => finish(reject, err))
    req.on('response', (res) => {
      if (res.statusCode !== 200) {
        ws.destroy()
        finish(reject, new Error(`download HTTP ${res.statusCode}`))
        return
      }
      const lenHeader = res.headers?.['content-length'] || res.headers?.['Content-Length']
      const headerLen = Array.isArray(lenHeader) ? lenHeader[0] : lenHeader
      const declared = headerLen ? parseInt(headerLen, 10) || 0 : expectedSize
      if (declared > MAX_ENGINE_ZIP_BYTES) {
        ws.destroy()
        finish(reject, new Error('engine zip exceeds size limit'))
        return
      }
      const total = expectedSize || declared
      touchStall()
      res.on('data', (chunk) => {
        received += chunk.length
        if (received > MAX_ENGINE_ZIP_BYTES) {
          try { req.abort() } catch { /* ignore */ }
          ws.destroy()
          finish(reject, new Error('engine zip exceeds size limit'))
          return
        }
        touchStall()
        ws.write(chunk)
        onProgress?.({
          phase: 'downloading',
          received,
          total,
          percent: total ? Math.min(100, Math.round((received / total) * 100)) : 0,
        })
      })
      res.on('end', () => {
        ws.end(() => finish(resolve, { received, total }))
      })
      res.on('error', (err) => finish(reject, err))
    })
    req.end()
  })
}

function cleanupOldVersions(keepVersion) {
  const root = getEngineRoot()
  let entries
  try {
    entries = fs.readdirSync(root)
  } catch {
    return
  }
  const keep = `v${keepVersion}`
  for (const name of entries) {
    if (!/^v\d+\.\d+\.\d+$/.test(name) || name === keep) continue
    rmQuiet(path.join(root, name))
  }
}

/**
 * 若已安装且 sha256 一致则跳过；否则下载、校验、解压到 mcp-engine/v{version}/。
 */
export async function ensureManagedEngine({
  manifest,
  downloadUrl,
  onProgress,
  downloadFn = downloadToFile,
  extractZip = extract,
}) {
  if (!manifest?.version || !manifest?.sha256 || !downloadUrl) {
    throw new Error('engine manifest incomplete')
  }
  const expected = String(manifest.sha256).toLowerCase()
  const meta = readInstalledMeta()
  const currentDir = getEngineVersionDir(manifest.version)
  if (meta?.sha256 === expected && isEngineInstallIntact(currentDir)) {
    const exe = findEngineExe(currentDir)
    onProgress?.({ phase: 'done', skipped: true, version: manifest.version, percent: 100 })
    return { skipped: true, exePath: exe, version: manifest.version }
  }

  const part = getEnginePartPath()
  rmQuiet(part)
  onProgress?.({ phase: 'downloading', received: 0, total: manifest.size || 0, percent: 0 })
  const { received } = await downloadFn({
    url: downloadUrl,
    destPath: part,
    expectedSize: manifest.size || 0,
    onProgress,
  })
  if (manifest.size > 0 && received !== manifest.size) {
    rmQuiet(part)
    throw new Error(`download incomplete: ${received}/${manifest.size}`)
  }

  const digest = await sha256File(part)
  if (digest !== expected) {
    rmQuiet(part)
    throw new Error(`sha256 mismatch: expected ${expected}, got ${digest}`)
  }

  onProgress?.({ phase: 'extracting', received, total: manifest.size || received, percent: 100 })
  rmQuiet(currentDir)
  fs.mkdirSync(currentDir, { recursive: true })
  try {
    await extractZip(part, { dir: currentDir })
  } catch (e) {
    rmQuiet(currentDir)
    rmQuiet(part)
    throw e
  }
  rmQuiet(part)

  if (!isEngineInstallIntact(currentDir)) {
    rmQuiet(currentDir)
    throw new Error('extracted engine missing AgentEngine.exe or _internal')
  }
  const exe = findEngineExe(currentDir)
  writeInstalledMeta({
    version: manifest.version,
    sha256: expected,
    exeRelPath: toRelPath(exe),
    size: manifest.size || received,
  })
  cleanupOldVersions(manifest.version)
  onProgress?.({ phase: 'done', skipped: false, version: manifest.version, percent: 100 })
  return { skipped: false, exePath: exe, version: manifest.version }
}
