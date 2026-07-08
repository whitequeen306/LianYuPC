import { app, ipcMain, net } from 'electron'
import * as logger from '../logger.js'
import { getRuntimeSecrets } from '../runtimeSecrets.js'
import { performApiRequest, isAllowedEgressUrl } from '../apiProxy.js'
import { spawn } from 'child_process'
import path from 'path'
import fs from 'fs'

let initialized = false
let mainWindowRef = null
let currentState = { state: 'idle', info: {} }
let downloadedInstallerPath = null
let quitAndInstallRef = null

const UPDATE_MANIFEST_PATH = '/api/public/files/updates/latest.yml'

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

function formatInstallMessage(version) {
  return version
    ? `正在安装 v${version}，应用会关闭并在后台完成更新，请稍候。`
    : '正在安装更新，应用会关闭并在后台完成更新，请稍候。'
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
      const downloadUrl = resolveUpdateAssetUrl(info.url, ymlUrl, resolveUpdateOrigin())
      if (!isAllowedEgressUrl(downloadUrl, apiOrigin, resolveUpdateOrigin())) {
        throw new Error('download url not allowed')
      }

      const installerDir = path.join(app.getPath('temp'), 'lianyu-updater')
      fs.mkdirSync(installerDir, { recursive: true })
      const installerPath = path.join(installerDir, `LianYu-Setup-${info.version}.exe`)

      setState({ state: 'downloading', info: { percent: 0 } })

      await new Promise((resolve, reject) => {
        const req = net.request({
          method: 'GET',
          url: downloadUrl,
        })
        let received = 0
        let total = 0
        const startedAt = Date.now()
        const ws = fs.createWriteStream(installerPath)
        let settled = false
        const finish = (fn, v) => {
          if (settled) return
          settled = true
          fn(v)
        }
        ws.on('error', (err) => finish(reject, err))

        req.on('response', (res) => {
          if (res.statusCode !== 200) {
            finish(reject, new Error(`download HTTP ${res.statusCode}`))
            return
          }
          const lenHeader = res.headers['content-length']
          if (lenHeader) total = parseInt(Array.isArray(lenHeader) ? lenHeader[0] : lenHeader, 10) || 0

          res.on('data', (chunk) => {
            received += chunk.length
            const percent = total > 0 ? Math.round((received / total) * 100) : 0
            const elapsedSec = Math.max(0.001, (Date.now() - startedAt) / 1000)
            const speedBytesPerSec = Math.round(received / elapsedSec)
            const remaining = total > received && speedBytesPerSec > 0 ? total - received : 0
            const etaSeconds = remaining > 0 ? Math.ceil(remaining / speedBytesPerSec) : 0
            setState({
              state: 'downloading',
              info: { percent, transferred: received, total, speedBytesPerSec, etaSeconds, version: info.version },
            })
            ws.write(chunk)
          })
          res.on('end', () => {
            if (total > 0 && received !== total) {
              ws.destroy()
              finish(reject, new Error(`download incomplete: ${received}/${total}`))
              return
            }
            ws.end(() => {
              downloadedInstallerPath = installerPath
              setState({ state: 'ready', info: { version: info.version } })
              finish(resolve, { ok: true })
            })
          })
          res.on('error', (err) => finish(reject, err))
        })
        req.on('error', (err) => finish(reject, err))
        req.end()
      })

      return { ok: true }
    } catch (err) {
      const msg = err?.message || String(err)
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
    setState({ state: 'installing', info: { version, message: formatInstallMessage(version) } })
    try {
      logger.info('updater', `installing: ${downloadedInstallerPath}`)
      spawn(downloadedInstallerPath, ['/S', '--force-run'], {
        detached: true,
        stdio: 'ignore',
      }).unref()
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
