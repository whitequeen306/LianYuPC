import { app, ipcMain, net, shell } from 'electron'
import * as logger from '../logger.js'
import { getRuntimeSecrets } from '../runtimeSecrets.js'
import { performApiRequest, isAllowedEgressUrl } from '../apiProxy.js'
import { verifyManifestSignature } from './manifestSignature.js'
import path from 'path'
import fs from 'fs'
import { createHash } from 'crypto'

let initialized = false
/** 「未配置公钥、跳过验签」只告警一次，避免每次探测都刷同一条。 */
let manifestSignatureWarningLogged = false
let mainWindowRef = null
let currentState = { state: 'idle', info: {} }
let downloadedInstallerPath = null

const UPDATE_MANIFEST_PATH = '/api/public/files/updates/latest.yml'
const UPDATE_DOWNLOAD_CONCURRENCY = 6
const UPDATE_DOWNLOAD_RETRIES = 2
/**
 * 多久没收到任何字节就判定"断了"。
 *
 * 为什么必须有：`requestToFile` 原先没有任何超时。服务端如果把连接挂住
 * （不发数据、也不关不 RST），`res` 永远不触发 `end`/`error`，Promise 永不
 * settle，`withRetries` 也就永远没机会重试 —— 用户看到的就是进度条停在某个
 * 百分比、无限转圈。2026-10-21 实测 0.3.1 下载 166MB 时有数分钟的停顿，
 * 那次是自己恢复了，但没有这层保险就是真的吊死。
 *
 * 阈值取 45s：远大于正常分片间隔（实测慢速链路上每块也在秒级），
 * 又远小于用户的忍耐极限。
 */
const UPDATE_IDLE_TIMEOUT_MS = 45000

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

function resolveManifestPublicKey() {
  const secrets = getRuntimeSecrets()
  return String(secrets?.updateManifestPublicKey || '').trim()
}

/**
 * 校验 latest.yml 的分离签名（见 manifestSignature.js 文件头）。
 *
 * 未配置公钥 → 保持旧行为（只告警一次），避免「客户端先发、签名后上」时中断更新；
 * 配置了公钥 → 签名缺失或错误一律拒绝，宁可这次不更新，也不装来源不明的包。
 */
async function verifyManifestOrThrow(ymlText, ymlUrl, apiOrigin) {
  const publicKeyBase64 = resolveManifestPublicKey()
  if (!publicKeyBase64) {
    if (!manifestSignatureWarningLogged) {
      manifestSignatureWarningLogged = true
      logger.warn('updater', 'latest.yml signature check disabled: no updateManifestPublicKey')
    }
    return
  }
  const resp = await performApiRequest({
    method: 'GET',
    url: `${ymlUrl}.sig`,
    apiOrigin,
    timeoutMs: 15000,
  })
  if (resp.status !== 200) {
    throw new Error(`latest.yml.sig HTTP ${resp.status}`)
  }
  const ok = verifyManifestSignature({
    payload: ymlText,
    signatureBase64: String(resp.data || '').trim(),
    publicKeyBase64,
  })
  if (!ok) {
    throw new Error('update manifest signature invalid')
  }
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
    let idleTimer = null

    const clearIdle = () => {
      if (idleTimer) {
        clearTimeout(idleTimer)
        idleTimer = null
      }
    }
    const finish = (fn, value) => {
      if (settled) return
      settled = true
      clearIdle()
      fn(value)
    }
    /** 每收到一块就重新计时；超时则主动 abort，让 withRetries 拿到一个普通的 reject。 */
    const armIdle = () => {
      clearIdle()
      idleTimer = setTimeout(() => {
        try { if (typeof req.abort === 'function') req.abort() } catch (_) {}
        try { ws.destroy() } catch (_) {}
        finish(reject, new Error(`download stalled: no data for ${UPDATE_IDLE_TIMEOUT_MS / 1000}s`))
      }, UPDATE_IDLE_TIMEOUT_MS)
      // 不要让这个计时器把进程吊住（主进程里 node 的定时器默认会）
      if (typeof idleTimer.unref === 'function') idleTimer.unref()
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
      armIdle()
      res.on('data', (chunk) => {
        armIdle()
        received += chunk.length
        onProgress?.(chunk.length, total)
        ws.write(chunk)
      })
      res.on('end', () => {
        clearIdle()
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
    armIdle()
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
      clearTimeout(watchdog)
      ws.destroy()
      try { fs.unlinkSync(probePath) } catch (_) {}
      resolve(value)
    }
    // 探针只是"问一句支不支持分片"。它挂住不应该让整个下载停在 0%。
    // 超时就当不支持 → 退回单连接下载（会慢，但能下）。
    const watchdog = setTimeout(() => {
      try { if (typeof req.abort === 'function') req.abort() } catch (_) {}
      finish(null)
    }, 20000)
    if (typeof watchdog.unref === 'function') watchdog.unref()

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

function cleanupInstallerArtifacts(installerPath) {
  const targets = [installerPath]
  for (let index = 0; index < UPDATE_DOWNLOAD_CONCURRENCY; index++) {
    targets.push(`${installerPath}.part.${index}`)
  }
  for (const target of targets) {
    try {
      if (fs.existsSync(target)) fs.unlinkSync(target)
    } catch (_) {}
  }
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
    ? `正在打开 v${version} 安装向导，请在弹出的窗口中继续安装。`
    : '正在打开安装向导，请在弹出的窗口中继续安装。'
}

function formatOpenFolderMessage(version) {
  return version
    ? `安装包 v${version} 已下载到本地，请打开目录后双击安装包完成更新。`
    : '安装包已下载到本地，请打开目录后双击安装包完成更新。'
}

function isValidInstallerVersion(version) {
  return /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(version)
}

async function openInstallerFolder(installerPath) {
  shell.showItemInFolder(installerPath)
  const errorMessage = await shell.openPath(path.dirname(installerPath))
  if (errorMessage) {
    throw new Error(errorMessage)
  }
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

/**
 * 取 latest.yml 并解析出 {version,url,sha512}。
 *
 * 抽出来是为了让 `updater:check`（用户主动点「检查更新」）与 `updater:probe`
 * （启动时静默探测）共用同一套取数与解析逻辑 —— 两边各写一份必然会在
 * 某次改动后分叉，而这类分叉的表现是「手动检查说有新版本、启动提示说没有」。
 *
 * 失败一律抛异常，由调用方决定怎么呈现（state 机 / 静默返回）。
 */
async function fetchLatestInfo(apiOrigin) {
  const ymlUrl = resolveLatestYmlUrl(apiOrigin)
  const resp = await performApiRequest({
    method: 'GET',
    url: ymlUrl,
    apiOrigin,
    timeoutMs: 15000,
  })
  if (resp.status !== 200) {
    throw new Error(`latest.yml HTTP ${resp.status}`)
  }
  await verifyManifestOrThrow(resp.data, ymlUrl, apiOrigin)
  const info = parseLatestYml(resp.data)
  if (!info) {
    throw new Error('latest.yml parse failed')
  }
  return info
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
      const info = await fetchLatestInfo(apiOrigin)
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

  /**
   * 启动时的**静默**版本探测。
   *
   * 与 `updater:check` 的唯一区别是：**绝不碰 state 机**。
   * state 机是给「用户主动点检查更新」用的 —— 一旦把 state 设成
   * `update-available`，`AppUpdateDialog` 就会自己弹出来，用户还没做选择
   * 就已经被推进更新流程了。启动提示要的是「先问一句」，不是「直接开干」。
   *
   * 失败也保持安静：只记日志，返回 `{ok:false}` —— 断网/更新源临时不可用
   * 不该在用户刚打开软件时糊一个错误弹窗。
   */
  ipcMain.handle('updater:probe', async () => {
    const apiOrigin = resolveApiOrigin()
    if (!apiOrigin) {
      return { ok: false, error: 'no api origin' }
    }
    try {
      const info = await fetchLatestInfo(apiOrigin)
      const currentVersion = app.getVersion()
      const hasUpdate = compareVersions(currentVersion, info.version) > 0
      logger.info('updater',
        `probe: current=${currentVersion} latest=${info.version} hasUpdate=${hasUpdate}`)
      return { ok: true, hasUpdate, version: info.version, currentVersion }
    } catch (err) {
      const msg = err?.message || String(err)
      logger.warn('updater', `probe failed (silent): ${msg}`)
      return { ok: false, error: msg }
    }
  })

  ipcMain.handle('updater:download', async () => {
    const apiOrigin = resolveApiOrigin()
    if (!apiOrigin) {
      setState({ state: 'error', info: { errorMessage: 'no api origin' } })
      return { ok: false, error: 'no api origin' }
    }
    let installerPath = null
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
      await verifyManifestOrThrow(ymlResp.data, ymlUrl, apiOrigin)
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

      const installerDir = path.join(app.getPath('userData'), 'updates')
      fs.mkdirSync(installerDir, { recursive: true })
      installerPath = path.join(installerDir, `YuNian-Setup-${info.version}.exe`)

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
      if (installerPath) cleanupInstallerArtifacts(installerPath)
      logger.error('updater', `download failed: ${msg}`)
      setState({ state: 'error', info: { errorMessage: msg } })
      return { ok: false, error: msg }
    }
  })

  ipcMain.handle('updater:install', async () => {
    if (!downloadedInstallerPath || !fs.existsSync(downloadedInstallerPath)) {
      return { ok: false, error: 'no downloaded installer' }
    }
    const versionMatch = path.basename(downloadedInstallerPath).match(/YuNian-Setup-(.+)\.exe$/)
    const version = versionMatch ? versionMatch[1] : ''
    if (!isValidInstallerVersion(version)) {
      return { ok: false, error: 'invalid installer version' }
    }
    setState({ state: 'ready', info: { version, message: formatOpenFolderMessage(version) } })
    return { ok: true }
  })

  ipcMain.handle('updater:openInstallerFolder', async () => {
    if (!downloadedInstallerPath || !fs.existsSync(downloadedInstallerPath)) {
      return { ok: false, error: 'no downloaded installer' }
    }
    const versionMatch = path.basename(downloadedInstallerPath).match(/YuNian-Setup-(.+)\.exe$/)
    const version = versionMatch ? versionMatch[1] : ''
    try {
      logger.info('updater', `open installer folder: ${downloadedInstallerPath}`)
      await openInstallerFolder(downloadedInstallerPath)
      setState({ state: 'ready', info: { version, message: formatOpenFolderMessage(version) } })
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
  registerIpc()
  logger.info('updater', 'initialized (manual mode, self-hosted update source)')
}

export function getUpdaterState() {
  return currentState
}
