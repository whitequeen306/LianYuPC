/**
 * WeChat channel zip download + sha256 + extract (AgentEngine twin).
 */
import fs from 'fs'
import path from 'path'
import crypto from 'crypto'
import { net } from 'electron'
import extract from 'extract-zip'
import {
  getWechatChannelRoot,
  getWechatChannelVersionDir,
  getWechatChannelPartPath,
  findWechatChannelLaunch,
  isWechatChannelInstallIntact,
  readWechatChannelInstalledMeta,
  writeWechatChannelInstalledMeta,
  MAX_WECHAT_CHANNEL_ZIP_BYTES,
} from './wechatChannelRelease.js'

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
  return path.relative(getWechatChannelRoot(), absPath).split(path.sep).join('/')
}

export function downloadWechatChannelToFile({ url, destPath, expectedSize = 0, onProgress, stallMs = STALL_MS }) {
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
      if (declared > MAX_WECHAT_CHANNEL_ZIP_BYTES) {
        ws.destroy()
        finish(reject, new Error('wechat channel zip exceeds size limit'))
        return
      }
      const total = expectedSize || declared
      touchStall()
      res.on('data', (chunk) => {
        received += chunk.length
        if (received > MAX_WECHAT_CHANNEL_ZIP_BYTES) {
          try { req.abort() } catch { /* ignore */ }
          ws.destroy()
          finish(reject, new Error('wechat channel zip exceeds size limit'))
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
  const root = getWechatChannelRoot()
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

export async function ensureWechatChannelRuntime({
  manifest,
  downloadUrl,
  onProgress,
  downloadFn = downloadWechatChannelToFile,
  extractZip = extract,
}) {
  if (!manifest?.version || !manifest?.sha256 || !downloadUrl) {
    throw new Error('wechat channel manifest incomplete')
  }
  const expected = String(manifest.sha256).toLowerCase()
  const meta = readWechatChannelInstalledMeta()
  const currentDir = getWechatChannelVersionDir(manifest.version)
  if (meta?.sha256 === expected && isWechatChannelInstallIntact(currentDir)) {
    const launch = findWechatChannelLaunch(currentDir)
    onProgress?.({ phase: 'done', skipped: true, version: manifest.version, percent: 100 })
    return { skipped: true, launch, version: manifest.version }
  }

  const part = getWechatChannelPartPath()
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

  if (!isWechatChannelInstallIntact(currentDir)) {
    rmQuiet(currentDir)
    throw new Error('extracted wechat channel missing node.exe or host.mjs')
  }
  const launch = findWechatChannelLaunch(currentDir)
  writeWechatChannelInstalledMeta({
    version: manifest.version,
    sha256: expected,
    cwdRelPath: toRelPath(launch.cwd),
    size: manifest.size || received,
  })
  cleanupOldVersions(manifest.version)
  onProgress?.({ phase: 'done', skipped: false, version: manifest.version, percent: 100 })
  return { skipped: false, launch, version: manifest.version }
}

export function wipeWechatChannelInstall() {
  const root = getWechatChannelRoot()
  const keep = new Set(['credentials.json', 'sync-state.json'])
  let entries
  try {
    entries = fs.readdirSync(root)
  } catch {
    return
  }
  for (const name of entries) {
    if (keep.has(name)) continue
    rmQuiet(path.join(root, name))
  }
}
