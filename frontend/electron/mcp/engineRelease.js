/**
 * AgentEngine 发行解析与安装路径。
 *
 * 清单来自同一套 MinIO updates/ 前缀（与 Electron latest.yml 并列）：
 *   /api/public/files/updates/agent-latest.yml
 * 信任与 Electron 更新相同：出口 host 白名单 + 主进程 net（证书 pin）+ sha256 强校验。
 * url 只允许「文件名」，禁止路径穿越。
 */
import { app } from 'electron'
import path from 'path'
import fs from 'fs'

export const ENGINE_MANIFEST_PATH = '/api/public/files/updates/agent-latest.yml'
export const ENGINE_EXE_NAME = 'AgentEngine.exe'
export const ENGINE_ZIP_NAME_RE = /^AgentEngine-hosted-win-x64-\d+\.\d+\.\d+\.zip$/
export const ENGINE_VERSION_RE = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/
export const ENGINE_SHA256_RE = /^[a-fA-F0-9]{64}$/
export const MAX_ENGINE_ZIP_BYTES = 200 * 1024 * 1024

export function getEngineRoot() {
  return path.join(app.getPath('userData'), 'mcp-engine')
}

export function getEngineVersionDir(version) {
  return path.join(getEngineRoot(), `v${version}`)
}

export function getEngineMetaPath() {
  return path.join(getEngineRoot(), 'installed.json')
}

export function getEnginePartPath() {
  return path.join(getEngineRoot(), 'download.zip.part')
}

export function isSafeEngineAssetFilename(name) {
  return typeof name === 'string' && ENGINE_ZIP_NAME_RE.test(name)
}

export function parseAgentLatestYml(ymlText) {
  if (typeof ymlText !== 'string' || !ymlText.trim()) return null
  const versionMatch = ymlText.match(/^version:\s*(\S+)/m)
  const urlMatch = ymlText.match(/^url:\s*(\S+)/m)
  const shaMatch = ymlText.match(/^sha256:\s*(\S+)/m)
  const sizeMatch = ymlText.match(/^size:\s*(\d+)/m)
  if (!versionMatch || !urlMatch || !shaMatch) return null
  const version = versionMatch[1]
  const url = urlMatch[1]
  const sha256 = shaMatch[1].toLowerCase()
  if (!ENGINE_VERSION_RE.test(version)) return null
  if (!ENGINE_SHA256_RE.test(sha256)) return null
  // 只接受纯文件名，拒绝 ../、绝对路径、带 query 的 URL
  if (!isSafeEngineAssetFilename(url)) return null
  const size = sizeMatch ? Number(sizeMatch[1]) : 0
  if (!Number.isFinite(size) || size < 0 || size > MAX_ENGINE_ZIP_BYTES) return null
  return { version, url, sha256, size }
}

export function resolveEngineAssetUrl(assetUrl, manifestUrl, updateOrigin = '') {
  if (!assetUrl || !isSafeEngineAssetFilename(assetUrl)) {
    throw new Error('engine asset filename rejected')
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

/**
 * 在安装根下定位 AgentEngine.exe。
 * 当前 zip 根就是 exe + _internal/；也容忍将来多一层包裹目录，但不深入 _internal。
 */
export function findEngineExe(installRoot) {
  if (!installRoot) return null
  const direct = path.join(installRoot, ENGINE_EXE_NAME)
  if (isFile(direct)) return direct
  let entries
  try {
    entries = fs.readdirSync(installRoot)
  } catch {
    return null
  }
  for (const name of entries) {
    if (name === '_internal') continue
    const sub = path.join(installRoot, name)
    if (!isDir(sub)) continue
    const candidate = path.join(sub, ENGINE_EXE_NAME)
    if (isFile(candidate)) return candidate
  }
  return null
}

export function isEngineInstallIntact(installRoot) {
  const exe = findEngineExe(installRoot)
  if (!exe) return false
  return isDir(path.join(path.dirname(exe), '_internal'))
}

export function readInstalledMeta() {
  try {
    const raw = fs.readFileSync(getEngineMetaPath(), 'utf8')
    const parsed = JSON.parse(raw)
    if (!parsed || typeof parsed !== 'object') return null
    const version = typeof parsed.version === 'string' ? parsed.version : ''
    const sha256 = typeof parsed.sha256 === 'string' ? parsed.sha256.toLowerCase() : ''
    const exeRelPath = typeof parsed.exeRelPath === 'string' ? parsed.exeRelPath : ''
    if (!ENGINE_VERSION_RE.test(version) || !ENGINE_SHA256_RE.test(sha256)) return null
    return { version, sha256, exeRelPath, size: Number(parsed.size) || 0 }
  } catch {
    return null
  }
}

export function writeInstalledMeta(meta) {
  const next = {
    version: meta.version,
    sha256: String(meta.sha256).toLowerCase(),
    exeRelPath: meta.exeRelPath || '',
    size: Number(meta.size) || 0,
    installedAt: new Date().toISOString(),
  }
  const dest = getEngineMetaPath()
  fs.mkdirSync(path.dirname(dest), { recursive: true })
  fs.writeFileSync(dest, JSON.stringify(next, null, 2))
  return next
}

export function resolveInstalledExe() {
  const meta = readInstalledMeta()
  if (meta?.exeRelPath) {
    const abs = path.join(getEngineRoot(), meta.exeRelPath)
    if (isFile(abs) && isDir(path.join(path.dirname(abs), '_internal'))) return abs
  }
  if (meta?.version) {
    const dir = getEngineVersionDir(meta.version)
    if (isEngineInstallIntact(dir)) return findEngineExe(dir)
  }
  return null
}

export function getEngineStatus() {
  const exePath = resolveInstalledExe()
  const meta = readInstalledMeta()
  return {
    installed: Boolean(exePath),
    version: exePath && meta ? meta.version : '',
    exePath: exePath || '',
  }
}

export function resolveManagedEngineCommand() {
  const exe = resolveInstalledExe()
  if (!exe) return null
  return {
    command: exe,
    args: [],
    cwd: path.dirname(exe),
  }
}
