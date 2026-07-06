import { app, ipcMain } from 'electron'
import { autoUpdater } from 'electron-updater'
import * as logger from '../logger.js'
import { getRuntimeSecrets } from '../runtimeSecrets.js'

let initialized = false
/** @type {import('electron').BrowserWindow | null} */
let mainWindowRef = null
let currentState = { state: 'idle', info: {} }

function setState(partial) {
  currentState = { ...currentState, ...partial }
  sendState(currentState)
}

function sendState(payload) {
  if (!mainWindowRef || mainWindowRef.isDestroyed()) return
  mainWindowRef.webContents.send('updater:state', payload)
}

function bindAutoUpdaterEvents() {
  autoUpdater.on('checking-for-update', () => setState({ state: 'checking', info: {} }))
  autoUpdater.on('update-available', (info) => setState({
    state: 'update-available',
    info: { version: info?.version },
  }))
  autoUpdater.on('update-not-available', () => setState({ state: 'no-update', info: {} }))
  autoUpdater.on('download-progress', (p) => setState({
    state: 'downloading',
    info: {
      percent: p?.percent,
      transferred: p?.transferred,
      total: p?.total,
      bytesPerSecond: p?.bytesPerSecond,
    },
  }))
  autoUpdater.on('update-downloaded', (info) => setState({
    state: 'ready',
    info: { version: info?.version },
  }))
  autoUpdater.on('error', (err) => setState({
    state: 'error',
    info: { errorMessage: err?.message || String(err) },
  }))
}

function registerIpc() {
  ipcMain.handle('updater:check', async () => {
    if (!app.isPackaged) {
      setState({ state: 'error', info: { errorMessage: 'dev-mode' } })
      return { ok: false, reason: 'dev-mode' }
    }
    try {
      await autoUpdater.checkForUpdates()
      return { ok: true }
    } catch (err) {
      const msg = err?.message || String(err)
      setState({ state: 'error', info: { errorMessage: msg } })
      return { ok: false, error: msg }
    }
  })

  ipcMain.handle('updater:download', async () => {
    try {
      await autoUpdater.downloadUpdate()
      return { ok: true }
    } catch (err) {
      const msg = err?.message || String(err)
      setState({ state: 'error', info: { errorMessage: msg } })
      return { ok: false, error: msg }
    }
  })

  ipcMain.handle('updater:install', async () => {
    try {
      setState({ state: 'installing', info: { ...currentState.info } })
      autoUpdater.quitAndInstall()
      return { ok: true }
    } catch (err) {
      return { ok: false, error: err?.message || String(err) }
    }
  })
}

export function initUpdater(mainWindow) {
  if (initialized) return
  initialized = true
  mainWindowRef = mainWindow
  autoUpdater.autoDownload = false
  autoUpdater.autoInstallOnAppQuit = false
  // 改用 generic provider 指向后端代理（私有仓库不能直连 GitHub API）
  const secrets = getRuntimeSecrets()
  const feedUrl = secrets?.apiOrigin
    ? `${secrets.apiOrigin}/api/public/updater`
    : null
  if (feedUrl) {
    autoUpdater.setFeedURL({ provider: 'generic', url: feedUrl })
    logger.info('updater', `feed url: ${feedUrl}`)
  } else {
    logger.warn('updater', 'no apiOrigin available, feed URL not set')
  }
  bindAutoUpdaterEvents()
  registerIpc()
  logger.info('updater', 'initialized')
}

export function getUpdaterState() {
  return currentState
}
