import { app, ipcMain, net } from 'electron'
import * as logger from '../logger.js'
import { getRuntimeSecrets } from '../runtimeSecrets.js'
import { performApiRequest, isAllowedEgressUrl } from '../apiProxy.js'
import { spawn } from 'child_process'
import path from 'path'
import fs from 'fs'
import { createHash } from 'crypto'

let initialized = false
let mainWindowRef = null
let currentState = { state: 'idle', info: {} }
let downloadedInstallerPath = null
let quitAndInstallRef = null

const UPDATE_MANIFEST_PATH = '/api/public/files/updates/latest.yml'
const UPDATE_DOWNLOAD_CONCURRENCY = 6
const UPDATE_DOWNLOAD_RETRIES = 2

function setState(partial) {
  currentState = { ...currentState, ...partial }
  sendState(currentState)
}

function sendState(payload) {
  if (!mainWindowRef || mainWindowRef.isDestroyed()) return
  mainWindowRef.webContents.send('updater:state', payload)
}

function resolveApiOrigin() {
  const secrets = getRuntimeSecrets()
  return secrets?.apiOrigin || ''
}

function resolveUpdateOrigin() {
  const secrets = getRuntimeSecrets()
  return secrets?.updateOrigin || ''
}

function parseLatestYml(ymlText) {
  const versionMatch = ymlText.match(/^version:\s*(\S+)/m)
  const urlMatch = ymlText.match(/^url:\s*(\S+)/m) || ymlText.match(/^\s*-\s*url:\s*(\S+)/m)
  const sha512Match = ymlText.match(/^sha512:\s*(\S+)/m)
  if (!versionMatch) return null
  return {
    version: versionMatch[1],
    url: urlMatch ? urlMatch[1] : '',
    sha512: sha512Match ? sha512Match[1] : '',
  }
}

function resolveLatestYmlUrl(apiOrigin) {
  return `${apiOrigin}${UPDATE_MANIFEST_PATH}`
}

function resolveUpdateAssetUrl(assetUrl, latestYmlUrl, updateOrigin = '') {
  if (updateOrigin && assetUrl && !/^[a-zA-Z][a-zA-Z\d+.-]*:/.test(assetUrl)) {
    return new URL(assetUrl.replace(/^\/+/, ''), `${updateOrigin}/`).toString()
  }
  return new URL(assetUrl, latestYmlUrl).toString()
}

function getHeader(headers, name) {
  const value = headers?.[name] || headers?.[name.toLowerCase()]
  return Array.isArray(value) ? value[0] : value
}

function parseContentRange(value) {
  const match = String(value || '').match(/^bytes\s+(\d+)-(\d+)\/(\d+)$/i)
  if (!match) return null
  return { start: Number(match[1]), end: Number(match[2]), total: Number(match[3]) }
}

function createRequest(url, range) {
  const req = net.request({ method: 'GET', url })
  if (range && typeof req.setHeader === 'function') req.setHeader('Range', range)
  return req
}

function requestToFile({ url, filePath, range, expectedBytes, expectedRange, onProgress }) {
  return new Promise((resolve, reject) => {
    const req = createRequest(url, range)
    const ws = fs.createWriteStream(filePath)
    let received = 0
    let settled = false
    const finish = (fn, value) => {
      if (settled) return
      settled = true
      fn(value)
    }

    ws.on('error', (err) => finish(reject, err))
    req.on('response', (res) => {
      const expectedStatus = range ? 206 : 200
      if (res.statusCode !== expectedStatus) {
        ws.destroy()
        finish(reject, new Error(`download HTTP ${res.statusCode}`))
        return
      }
      if (expectedRange) {
        const contentRange = parseContentRange(getHeader(res.headers, 'content-range'))
        if (!contentRange || contentRange.start !== expectedRange.start || contentRange.end !== expectedRange.end || contentRange.total !== expectedRange.total) {
          ws.destroy()
          finish(reject, new Error('Content-Range mismatch'))
          return
        }
      }
      const lenHeader = getHeader(res.headers, 'content-length')
      const total = expectedBytes || (lenHeader ? parseInt(lenHeader, 10) || 0 : 0)
      res.on('data', (chunk) => {
        received += chunk.length
        onProgress?.(chunk.length, total)
        ws.write(chunk)
      })
      res.on('end', () => {
        if (total > 0 && received !== total) {
          ws.destroy()
          finish(reject, new Error(`download incomplete: ${received}/${total}`))
          return
        }
        ws.end(() => finish(resolve, { received, total }))
      })
      res.on('error', (err) => finish(reject, err))
    })
    req.on('error', (err) => finish(reject, err))
    req.end()
  })
}

function probeRangeSupport(url, probePath) {
  return new Promise((resolve) => {
    const req = createRequest(url, 'bytes=0-0')
    const ws = fs.createWriteStream(probePath)
    let settled = false
    const finish = (value) => {
      if (settled) return
      settled = true
      ws.destroy()
      try { fs.unlinkSync(probePath) } catch (_) {}
      resolve(value)
    }

    ws.on('error', () => finish(null))
    req.on('response', (res) => {
      const range = parseContentRange(getHeader(res.headers, 'content-range'))
      if (res.statusCode !== 206 || !range?.total) {
        if (typeof res.destroy === 'function') res.destroy()
        if (typeof req.abort === 'function') req.abort()
        finish(null)
        return
      }
      res.on('data', (chunk) => ws.write(chunk))
      res.on('end', () => ws.end(() => finish({ total: range.total })))
      res.on('error', () => finish(null))
    })
    req.on('error', () => finish(null))
    req.end()
  })
}

function createParts(total, concurrency = UPDATE_DOWNLOAD_CONCURRENCY) {
  const partCount = Math.min(concurrency, total)
  const partSize = Math.ceil(total / partCount)
  return Array.from({ length: partCount }, (_, index) => {
    const start = index * partSize
    const end = Math.min(total - 1, start + partSize - 1)
    return { index, start, end, size: end - start + 1 }
  })
}

async function withRetries(fn) {
  let lastError = null
  for (let attempt = 0; attempt <= UPDATE_DOWNLOAD_RETRIES; attempt++) {
    try {
      return await fn()
    } catch (err) {
      lastError = err
    }
  }
  throw lastError
}

function emitDownloadProgress({ received, total, startedAt, version }) {
  const percent = total > 0 ? Math.round((received / total) * 100) : 0
  const elapsedSec = Math.max(0.001, (Date.now() - startedAt) / 1000)
  const speedBytesPerSec = Math.round(received / elapsedSec)
  const remaining = total > received && speedBytesPerSec > 0 ? total - received : 0
  const etaSeconds = remaining > 0 ? Math.ceil(remaining / speedBytesPerSec) : 0
  setState({
    state: 'downloading',
    info: { percent, transferred: received, total, speedBytesPerSec, etaSeconds, version },
  })
}

function mergeParts(parts, installerPath) {
  return new Promise((resolve, reject) => {
    const ws = fs.createWriteStream(installerPath)
    let current = 0
    let settled = false
    const finish = (fn, value) => {
      if (settled) return
      settled = true
      fn(value)
    }
    ws.on('error', (err) => finish(reject, err))

    const pipeNext = () => {
      if (current >= parts.length) {
        ws.end(() => finish(resolve))
        return
      }
      const part = parts[current]
      const rs = fs.createReadStream(part.filePath)
      rs.on('error', (err) => finish(reject, err))
      rs.on('end', () => {
        try { fs.unlinkSync(part.filePath) } catch (_) {}
        current += 1
        pipeNext()
      })
      rs.pipe(ws, { end: false })
    }
    pipeNext()
  })
}

function hashFile(filePath) {
  return new Promise((resolve, reject) => {
    const hash = createHash('sha512')
    const rs = fs.createReadStream(filePath)
    rs.on('data', (chunk) => hash.update(chunk))
    rs.on('end', () => resolve(hash.digest('base64')))
    rs.on('error', reject)
  })
}

async function verifyInstallerSha512(installerPath, expectedSha512) {
  if (!expectedSha512) throw new Error('latest.yml missing sha512')
  const actualSha512 = await hashFile(installerPath)
  if (actualSha512 !== expectedSha512) {
    try { fs.unlinkSync(installerPath) } catch (_) {}
    throw new Error('sha512 mismatch')
  }
}

async function downloadSingle({ downloadUrl, installerPath, startedAt, version }) {
  let received = 0
  return requestToFile({
    url: downloadUrl,
    filePath: installerPath,
    onProgress: (bytes, total) => {
      received += bytes
      emitDownloadProgress({ received, total, startedAt, version })
    },
  })
}

async function downloadParallel({ downloadUrl, installerPath, total, startedAt, version }) {
  const parts = createParts(total).map((part) => ({
    ...part,
    filePath: `${installerPath}.part.${part.index}`,
  }))
  const partProgress = new Array(parts.length).fill(0)

  await Promise.all(parts.map((part) => withRetries(() => {
    partProgress[part.index] = 0
    return requestToFile({
      url: downloadUrl,
      filePath: part.filePath,
      range: `bytes=${part.start}-${part.end}`,
      expectedBytes: part.size,
      expectedRange: { start: part.start, end: part.end, total },
      onProgress: (bytes) => {
        partProgress[part.index] += bytes
        const received = partProgress.reduce((sum, value) => sum + value, 0)
        emitDownloadProgress({ received, total, startedAt, version })
      },
    })
  })))

  const received = partProgress.reduce((sum, value) => sum + value, 0)
  if (received !== total) {
    throw new Error(`download incomplete: ${received}/${total}`)
  }
  await mergeParts(parts, installerPath)
}

function formatInstallMessage(version) {
  return version
    ? `正在安装 v${version}，应用会关闭并在后台完成更新，请稍候。`
    : '正在安装更新，应用会关闭并在后台完成更新，请稍候。'
}

function isValidInstallerVersion(version) {
  return /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(version)
}

function quoteCmdArg(value) {
  const text = String(value)
  if (/["\r\n]/.test(text)) throw new Error('invalid installer path')
  return `"${text}"`
}

function launchInstallerDetached(installerPath) {
  const command = `start "" /min ${quoteCmdArg(installerPath)} /S --force-run`
  return spawn('cmd.exe', ['/d', '/s', '/c', command], {
    detached: true,
    stdio: 'ignore',
    windowsHide: true,
  })
}

function compareVersions(current, latest) {
  const c = current.split('.').map(Number)
  const l = latest.split('.').map(Number)
  for (let i = 0; i < Math.max(c.length, l.length); i++) {
    const cv = c[i] || 0
    const lv = l[i] || 0
    if (lv > cv) return 1
    if (lv < cv) return -1
  }
  return 0
}

function registerIpc() {
  ipcMain.handle('updater:check', async () => {
    const apiOrigin = resolveApiOrigin()
    if (!apiOrigin) {
      setState({ state: 'error', info: { errorMessage: 'no api origin' } })
      return { ok: false, error: 'no api origin' }
    }
    setState({ state: 'checking', info: {} })
    try {
      const ymlUrl = resolveLatestYmlUrl(apiOrigin)
      const resp = await performApiRequest({
        method: 'GET',
        url: ymlUrl,
        apiOrigin,
        timeoutMs: 15000,
      })
      if (resp.status !== 200) {
        const msg = `latest.yml HTTP ${resp.status}`
        setState({ state: 'error', info: { errorMessage: msg } })
        return { ok: false, error: msg }
      }
      const info = parseLatestYml(resp.data)
      if (!info) {
        const msg = 'latest.yml parse failed'
        setState({ state: 'error', info: { errorMessage: msg } })
        return { ok: false, error: msg }
      }
      const currentVersion = app.getVersion()
      const diff = compareVersions(currentVersion, info.version)
      if (diff > 0) {
        setState({ state: 'update-available', info: { version: info.version } })
        return { ok: true, hasUpdate: true, version: info.version }
      } else {
        setState({ state: 'no-update', info: {} })
        return { ok: true, hasUpdate: false }
      }
    } catch (err) {
      const msg = err?.message || String(err)
      logger.error('updater', `check failed: ${msg}`)
      setState({ state: 'error', info: { errorMessage: msg } })
      return { ok: false, error: msg }
    }
  })

  ipcMain.handle('updater:download', async () => {
    const apiOrigin = resolveApiOrigin()
    if (!apiOrigin) {
      setState({ state: 'error', info: { errorMessage: 'no api origin' } })
      return { ok: false, error: 'no api origin' }
    }
    try {
      downloadedInstallerPath = null
      const ymlUrl = resolveLatestYmlUrl(apiOrigin)
      const ymlResp = await performApiRequest({
        method: 'GET',
        url: ymlUrl,
        apiOrigin,
        timeoutMs: 15000,
      })
      if (ymlResp.status !== 200) {
        throw new Error(`latest.yml HTTP ${ymlResp.status}`)
      }
      const info = parseLatestYml(ymlResp.data)
      if (!info || !info.url) {
        throw new Error('latest.yml missing url')
      }
      if (!isValidInstallerVersion(info.version)) {
        throw new Error('invalid installer version')
      }
      const downloadUrl = resolveUpdateAssetUrl(info.url, ymlUrl, resolveUpdateOrigin())
      if (!isAllowedEgressUrl(downloadUrl, apiOrigin, resolveUpdateOrigin())) {
        throw new Error('download url not allowed')
      }

      const installerDir = path.join(app.getPath('temp'), 'lianyu-updater')
      fs.mkdirSync(installerDir, { recursive: true })
      const installerPath = path.join(installerDir, `LianYu-Setup-${info.version}.exe`)

      setState({ state: 'downloading', info: { percent: 0 } })
      const startedAt = Date.now()
      const probe = await probeRangeSupport(downloadUrl, `${installerPath}.probe`)
      if (probe?.total) {
        await downloadParallel({ downloadUrl, installerPath, total: probe.total, startedAt, version: info.version })
      } else {
        await downloadSingle({ downloadUrl, installerPath, startedAt, version: info.version })
      }

      await verifyInstallerSha512(installerPath, info.sha512)
      downloadedInstallerPath = installerPath
      setState({ state: 'ready', info: { version: info.version } })

      return { ok: true }
    } catch (err) {
      const msg = err?.message || String(err)
      downloadedInstallerPath = null
      logger.error('updater', `download failed: ${msg}`)
      setState({ state: 'error', info: { errorMessage: msg } })
      return { ok: false, error: msg }
    }
  })

  ipcMain.handle('updater:install', async () => {
    if (!downloadedInstallerPath || !fs.existsSync(downloadedInstallerPath)) {
      return { ok: false, error: 'no downloaded installer' }
    }
    const versionMatch = path.basename(downloadedInstallerPath).match(/LianYu-Setup-(.+)\.exe$/)
    const version = versionMatch ? versionMatch[1] : ''
    if (!isValidInstallerVersion(version)) {
      return { ok: false, error: 'invalid installer version' }
    }
    setState({ state: 'installing', info: { version, message: formatInstallMessage(version) } })
    try {
      logger.info('updater', `installing: ${downloadedInstallerPath}`)
      launchInstallerDetached(downloadedInstallerPath).unref()
      setTimeout(() => {
        if (typeof quitAndInstallRef === 'function') {
          quitAndInstallRef()
          return
        }
        app.quit()
      }, 500)
      return { ok: true }
    } catch (err) {
      const msg = err?.message || String(err)
      setState({ state: 'error', info: { errorMessage: msg } })
      return { ok: false, error: msg }
    }
  })
}

export function initUpdater(mainWindow, options = {}) {
  if (initialized) return
  initialized = true
  mainWindowRef = mainWindow
  quitAndInstallRef = typeof options.quitAndInstall === 'function' ? options.quitAndInstall : null
  registerIpc()
  logger.info('updater', 'initialized (manual mode, self-hosted update source)')
}

export function getUpdaterState() {
  return currentState
}
