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
      const ymlUrl = `${apiOrigin}/api/public/updater/latest.yml`
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
      const ymlUrl = `${apiOrigin}/api/public/updater/latest.yml`
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
      const downloadUrl = info.url.startsWith('http')
        ? info.url
        : `${apiOrigin}${info.url}`
      if (!isAllowedEgressUrl(downloadUrl, apiOrigin)) {
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
        const ws = fs.createWriteStream(installerPath)
        let settled = false
        const finish = (fn, v) => {
          if (settled) return
          settled = true
          fn(v)
        }

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
            setState({
              state: 'downloading',
              info: { percent, transferred: received, total },
            })
            ws.write(chunk)
          })
          res.on('end', () => {
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
    setState({ state: 'installing', info: {} })
    try {
      logger.info('updater', `installing: ${downloadedInstallerPath}`)
      spawn(downloadedInstallerPath, ['/S', '--force-run'], {
        detached: true,
        stdio: 'ignore',
      }).unref()
      setTimeout(() => app.quit(), 500)
      return { ok: true }
    } catch (err) {
      const msg = err?.message || String(err)
      setState({ state: 'error', info: { errorMessage: msg } })
      return { ok: false, error: msg }
    }
  })
}

export function initUpdater(mainWindow) {
  if (initialized) return
  initialized = true
  mainWindowRef = mainWindow
  registerIpc()
  logger.info('updater', 'initialized (manual mode, no electron-updater HTTP)')
}

export function getUpdaterState() {
  return currentState
}
