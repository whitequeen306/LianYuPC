/**
 * WeChat channel runtime install paths + MinIO manifest parse.
 * Same trust model as AgentEngine: filename-only url, sha256, size cap.
 */
import { app } from 'electron'
import path from 'path'
import fs from 'fs'

export const WECHAT_CHANNEL_MANIFEST_PATH = '/api/public/files/updates/wechat-channel-latest.yml'
export const WECHAT_CHANNEL_ZIP_NAME_RE = /^WechatChannel-win-x64-\d+\.\d+\.\d+\.zip$/
export const WECHAT_CHANNEL_VERSION_RE = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/
export const WECHAT_CHANNEL_SHA256_RE = /^[a-fA-F0-9]{64}$/
export const MAX_WECHAT_CHANNEL_ZIP_BYTES = 150 * 1024 * 1024
export const HOST_SCRIPT_NAME = 'host.mjs'
export const NODE_EXE_NAME = 'node.exe'

export function getWechatChannelRoot() {
  return path.join(app.getPath('userData'), 'wechat-channel')
}

export function getWechatChannelVersionDir(version) {
  return path.join(getWechatChannelRoot(), `v${version}`)
}

export function getWechatChannelMetaPath() {
  return path.join(getWechatChannelRoot(), 'installed.json')
}

export function getWechatChannelPartPath() {
  return path.join(getWechatChannelRoot(), 'download.zip.part')
}

export function isSafeWechatChannelAssetFilename(name) {
  return typeof name === 'string' && WECHAT_CHANNEL_ZIP_NAME_RE.test(name)
}

export function parseWechatChannelLatestYml(ymlText) {
  if (typeof ymlText !== 'string' || !ymlText.trim()) return null
  const versionMatch = ymlText.match(/^version:\s*(\S+)/m)
  const urlMatch = ymlText.match(/^url:\s*(\S+)/m)
  const shaMatch = ymlText.match(/^sha256:\s*(\S+)/m)
  const sizeMatch = ymlText.match(/^size:\s*(\d+)/m)
  if (!versionMatch || !urlMatch || !shaMatch) return null
  const version = versionMatch[1]
  const url = urlMatch[1]
  const sha256 = shaMatch[1].toLowerCase()
  if (!WECHAT_CHANNEL_VERSION_RE.test(version)) return null
  if (!WECHAT_CHANNEL_SHA256_RE.test(sha256)) return null
  if (!isSafeWechatChannelAssetFilename(url)) return null
  const size = sizeMatch ? Number(sizeMatch[1]) : 0
  if (!Number.isFinite(size) || size < 0 || size > MAX_WECHAT_CHANNEL_ZIP_BYTES) return null
  return { version, url, sha256, size }
}

export function resolveWechatChannelAssetUrl(assetUrl, manifestUrl, updateOrigin = '') {
  if (!assetUrl || !isSafeWechatChannelAssetFilename(assetUrl)) {
    throw new Error('wechat channel asset filename rejected')
  }
  if (updateOrigin && !/^[a-zA-Z][a-zA-Z\d+.-]*:/.test(assetUrl)) {
    return new URL(assetUrl.replace(/^\/+/, ''), `${updateOrigin.replace(/\/+$/, '')}/`).toString()
  }
  return new URL(assetUrl, manifestUrl).toString()
}

function isFile(p) {
  try {
    return fs.statSync(p).isFile()
  } catch {
    return false
  }
}

function isDir(p) {
  try {
    return fs.statSync(p).isDirectory()
  } catch {
    return false
  }
}

export function findWechatChannelLaunch(installRoot) {
  if (!installRoot) return null
  const directNode = path.join(installRoot, NODE_EXE_NAME)
  const directHost = path.join(installRoot, HOST_SCRIPT_NAME)
  if (isFile(directNode) && isFile(directHost)) {
    return { nodePath: directNode, hostPath: directHost, cwd: installRoot }
  }
  let entries
  try {
    entries = fs.readdirSync(installRoot)
  } catch {
    return null
  }
  for (const name of entries) {
    const sub = path.join(installRoot, name)
    if (!isDir(sub)) continue
    const nodePath = path.join(sub, NODE_EXE_NAME)
    const hostPath = path.join(sub, HOST_SCRIPT_NAME)
    if (isFile(nodePath) && isFile(hostPath)) {
      return { nodePath, hostPath, cwd: sub }
    }
  }
  return null
}

export function isWechatChannelInstallIntact(installRoot) {
  return Boolean(findWechatChannelLaunch(installRoot))
}

export function readWechatChannelInstalledMeta() {
  try {
    const raw = fs.readFileSync(getWechatChannelMetaPath(), 'utf8')
    const parsed = JSON.parse(raw)
    if (!parsed || typeof parsed !== 'object') return null
    const version = typeof parsed.version === 'string' ? parsed.version : ''
    const sha256 = typeof parsed.sha256 === 'string' ? parsed.sha256.toLowerCase() : ''
    const cwdRelPath = typeof parsed.cwdRelPath === 'string' ? parsed.cwdRelPath : ''
    if (!WECHAT_CHANNEL_VERSION_RE.test(version) || !WECHAT_CHANNEL_SHA256_RE.test(sha256)) return null
    return { version, sha256, cwdRelPath, size: Number(parsed.size) || 0 }
  } catch {
    return null
  }
}

export function writeWechatChannelInstalledMeta(meta) {
  const next = {
    version: meta.version,
    sha256: String(meta.sha256).toLowerCase(),
    cwdRelPath: meta.cwdRelPath || '',
    size: Number(meta.size) || 0,
    installedAt: new Date().toISOString(),
  }
  const dest = getWechatChannelMetaPath()
  fs.mkdirSync(path.dirname(dest), { recursive: true })
  fs.writeFileSync(dest, JSON.stringify(next, null, 2))
  return next
}

export function resolveInstalledWechatChannel() {
  const meta = readWechatChannelInstalledMeta()
  if (meta?.cwdRelPath) {
    const abs = path.join(getWechatChannelRoot(), meta.cwdRelPath)
    const launch = findWechatChannelLaunch(abs)
    if (launch) return launch
  }
  if (meta?.version) {
    const dir = getWechatChannelVersionDir(meta.version)
    const launch = findWechatChannelLaunch(dir)
    if (launch) return launch
  }
  return null
}

export function getWechatChannelStatus() {
  const launch = resolveInstalledWechatChannel()
  const meta = readWechatChannelInstalledMeta()
  return {
    installed: Boolean(launch),
    version: launch && meta ? meta.version : '',
    nodePath: launch?.nodePath || '',
    hostPath: launch?.hostPath || '',
  }
}
