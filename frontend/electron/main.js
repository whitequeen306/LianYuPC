import {
  app,
  BrowserWindow,
  shell,
  globalShortcut,
  Tray,
  Menu,
  ipcMain,
  screen,
  session,
  net,
} from 'electron'
import path from 'path'
import fs from 'fs'
import { fileURLToPath } from 'url'
import {
  readDesktopSettings,
  writeDesktopSettings,
  applyLaunchAtLogin,
  readLauncherPosition,
  writeLauncherPosition,
} from './desktopSettings.js'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const isDev = !!process.env.VITE_DEV_SERVER_URL
const isDebug = process.env.LIANYU_DEBUG === '1' || process.argv.includes('--lianyu-debug')

/** @type {BrowserWindow | null} */
let mainWindow = null
/** @type {BrowserWindow | null} */
let launcherWindow = null
/** @type {BrowserWindow | null} */
let pickerWindow = null
/** @type {Map<string, BrowserWindow>} */
const quickChatWindows = new Map()
/** @type {Tray | null} */
let tray = null
let isQuitting = false
let pendingHideAfterHint = false
let pickerBlurTimer = null
let pickerOpeningUntil = 0

const SHARED_WEB_PREFS = {
  preload: path.join(__dirname, 'preload.cjs'),
  contextIsolation: true,
  nodeIntegration: false,
  sandbox: false,
  partition: 'persist:lianyu',
}

const DEFAULT_API_ORIGIN = 'http://localhost:8080'

function resolveApiOrigin() {
  const configured = process.env.LIANYU_API_ORIGIN || process.env.VITE_LIANYU_API_ORIGIN
  const trimmed = configured && String(configured).trim()
  return (trimmed || DEFAULT_API_ORIGIN).replace(/\/$/, '')
}

/** 服务器证书 SHA-256 指纹（构建时注入），用于自签名证书固定 */
const EXPECTED_CERT_FINGERPRINT = (process.env.LIANYU_CERT_FINGERPRINT || '').trim()

// SPKI 白名单必须在 app.whenReady() 之前！否则无效
const SPKI = 'EdDpp/Z9REuRjqZLzXXrOW8opTtR8Yph2YM0s+xuLss='
app.commandLine.appendSwitch('ignore-certificate-errors-spki-list', SPKI)

function configureCertificatePinning() {
  if (!EXPECTED_CERT_FINGERPRINT) return

  // certificate-error 事件兜底
  app.on('certificate-error', (event, _webContents, url, _error, cert, callback) => {
    const lowercase = cert.fingerprint?.toLowerCase() || ''
    const expected = EXPECTED_CERT_FINGERPRINT.toLowerCase().replace(/:/g, '')
    if (lowercase === expected) {
      log(`cert pin OK for ${url}`)
      event.preventDefault()
      callback(true)
      return
    }
    log(`cert pin REJECTED for ${url} — got ${lowercase}`)
    callback(false)
  })
}

function configureSecurity() {
  configureCertificatePinning()
}

function resolveWsUrlPrefix(httpOrigin) {
  try {
    const url = new URL(httpOrigin)
    const wsProtocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
    return `${wsProtocol}//${url.host}`
  } catch {
    return 'ws://localhost:8080'
  }
}

/** file:// 页面 Origin 为 null — 统一改写为 API Origin，满足后端 CORS / WS 校验 */
function patchDesktopRequestOrigin() {
  if (isDev) return
  const apiOrigin = resolveApiOrigin()
  const wsPrefix = resolveWsUrlPrefix(apiOrigin)
  const ses = session.fromPartition(SHARED_WEB_PREFS.partition)

  ses.webRequest.onBeforeSendHeaders(
    { urls: [`${apiOrigin}/*`] },
    (details, callback) => {
      details.requestHeaders.Origin = apiOrigin
      callback({ requestHeaders: details.requestHeaders })
    },
  )

  ses.webRequest.onBeforeSendHeaders(
    { urls: [`${wsPrefix}/*`] },
    (details, callback) => {
      details.requestHeaders.Origin = apiOrigin
      callback({ requestHeaders: details.requestHeaders })
    },
  )
}

function logPath() {
  return path.join(app.getPath('userData'), 'startup.log')
}

function log(message) {
  const line = `[${new Date().toISOString()}] ${message}\n`
  console.log(message)
  try {
    fs.mkdirSync(path.dirname(logPath()), { recursive: true })
    fs.appendFileSync(logPath(), line)
  } catch {
    // ignore
  }
}

function resolveDistRoot() {
  if (isDev) {
    return path.join(__dirname, '../dist')
  }
  return path.join(process.resourcesPath, 'dist')
}

function resolveDistPath(...segments) {
  return path.join(resolveDistRoot(), ...segments)
}

function resolveTrayIcon() {
  const ico = path.join(__dirname, '../build/icon.ico')
  if (fs.existsSync(ico)) {
    return ico
  }
  return resolveDistPath('logo.png')
}

function normalizeHashRoute(route) {
  if (!route) return ''
  return route.startsWith('#') ? route : `#${route}`
}

function loadRoute(win, hashRoute) {
  const hash = normalizeHashRoute(hashRoute)
  if (isDev) {
    const base = process.env.VITE_DEV_SERVER_URL.replace(/\/$/, '')
    return win.loadURL(`${base.replace(/\/$/, '')}${hash}`)
  }
  const indexPath = resolveDistPath('index.html')
  const hashBody = hash.startsWith('#') ? hash.slice(1) : hash
  return win.loadFile(indexPath, { hash: hashBody })
}

function attachWindowLogging(win, label) {
  win.webContents.on('did-fail-load', (_event, errorCode, errorDescription, validatedURL) => {
    log(`${label} did-fail-load code=${errorCode} desc=${errorDescription} url=${validatedURL}`)
  })
  win.webContents.on('did-finish-load', () => {
    log(`${label} did-finish-load url=${win.webContents.getURL()}`)
  })
  win.webContents.setWindowOpenHandler(({ url }) => {
    shell.openExternal(url)
    return { action: 'deny' }
  })
}

function defaultLauncherPosition() {
  const area = screen.getPrimaryDisplay().workArea
  return {
    x: area.x + area.width - LAUNCHER_COMPACT.width - 8,
    y: area.y + area.height - LAUNCHER_COMPACT.height - 8,
  }
}

function ensureTray() {
  if (tray) return tray

  tray = new Tray(resolveTrayIcon())
  tray.setToolTip('LianYu - 恋语')
  tray.setContextMenu(buildTrayMenu())
  tray.on('double-click', () => {
    showMainWindow()
  })
  return tray
}

function buildTrayMenu() {
  return Menu.buildFromTemplate([
    {
      label: '打开 LianYu',
      click: () => showMainWindow(),
    },
    {
      label: '快速聊天…',
      click: () => openCharacterPicker(),
    },
    { type: 'separator' },
    {
      label: '彻底退出',
      click: () => quitApplication(),
    },
  ])
}

const LAUNCHER_COMPACT = { width: 192, height: 208 }
const LAUNCHER_EXPANDED = { width: 320, height: 280 }
let launcherSizeRestoreTimer = null

function shouldPulseLauncherForMessage() {
  if (!mainWindow || mainWindow.isDestroyed()) return true
  if (!mainWindow.isVisible()) return true
  return !mainWindow.isFocused()
}

function expandLauncherForToast() {
  if (!launcherWindow || launcherWindow.isDestroyed() || !launcherWindow.isVisible()) return
  const b = launcherWindow.getBounds()
  launcherWindow.setBounds({
    x: b.x - (LAUNCHER_EXPANDED.width - LAUNCHER_COMPACT.width),
    y: b.y - (LAUNCHER_EXPANDED.height - LAUNCHER_COMPACT.height),
    width: LAUNCHER_EXPANDED.width,
    height: LAUNCHER_EXPANDED.height,
  })
  clearTimeout(launcherSizeRestoreTimer)
  launcherSizeRestoreTimer = setTimeout(() => {
    if (!launcherWindow || launcherWindow.isDestroyed()) return
    const cur = launcherWindow.getBounds()
    launcherWindow.setBounds({
      x: cur.x + (cur.width - LAUNCHER_COMPACT.width),
      y: cur.y + (cur.height - LAUNCHER_COMPACT.height),
      width: LAUNCHER_COMPACT.width,
      height: LAUNCHER_COMPACT.height,
    })
  }, 4800)
}

function notifyLauncherWindows(payload) {
  if (!shouldPulseLauncherForMessage()) return
  expandLauncherForToast()
  if (launcherWindow && !launcherWindow.isDestroyed() && launcherWindow.isVisible()) {
    launcherWindow.webContents.send('desktop:launcher-new-message', payload)
  }
  if (pickerWindow && !pickerWindow.isDestroyed() && pickerWindow.isVisible()) {
    pickerWindow.webContents.send('desktop:launcher-new-message', payload)
  }
}

function hideLauncherWindow() {
  if (launcherWindow && !launcherWindow.isDestroyed()) {
    launcherWindow.hide()
  }
}

function showLauncherWindow() {
  const settings = readDesktopSettings()
  if (!settings.showLauncherLogo) return
  if (mainWindow && !mainWindow.isDestroyed() && mainWindow.isVisible()) return
  const win = ensureLauncherWindow()
  win?.show()
}

function showMainWindow(hash = '') {
  if (!mainWindow || mainWindow.isDestroyed()) {
    mainWindow = createMainWindow()
  }
  if (hash) {
    loadRoute(mainWindow, hash)
  }
  if (!mainWindow.isVisible()) {
    mainWindow.show()
  }
  mainWindow.focus()
  hideLauncherWindow()
}

function hideMainToTray() {
  if (!mainWindow || mainWindow.isDestroyed()) return
  mainWindow.hide()
  ensureTray()
  showLauncherWindow()
}

function createMainWindow() {
  const win = new BrowserWindow({
    width: 1280,
    height: 800,
    minWidth: 960,
    minHeight: 640,
    title: 'LianYu - 恋语',
    icon: resolveDistPath('logo.png'),
    backgroundColor: '#0a0a10',
    show: false,
    webPreferences: { ...SHARED_WEB_PREFS },
  })
  win.lianyuKind = 'main'
  win.setMenuBarVisibility(false)
  attachWindowLogging(win, 'main')

  win.once('ready-to-show', () => {
    win.show()
  })

  win.on('close', (event) => {
    if (isQuitting) return
    const settings = readDesktopSettings()
    if (!settings.closeToTray) {
      quitApplication()
      return
    }

    event.preventDefault()
    if (!settings.closeHintShown) {
      pendingHideAfterHint = true
      win.webContents.send('desktop:close-hint')
      return
    }
    hideMainToTray()
  })

  loadRoute(win, '#/app')
  if (isDev) {
    win.webContents.openDevTools({ mode: 'detach' })
  }
  if (isDebug) {
    win.webContents.openDevTools({ mode: 'detach' })
  }
  mainWindow = win
  return win
}

function createLauncherWindow() {
  if (launcherWindow && !launcherWindow.isDestroyed()) {
    return launcherWindow
  }

  const saved = readLauncherPosition() || defaultLauncherPosition()
  const win = new BrowserWindow({
    width: LAUNCHER_COMPACT.width,
    height: LAUNCHER_COMPACT.height,
    x: saved.x,
    y: saved.y,
    frame: false,
    transparent: true,
    resizable: false,
    alwaysOnTop: true,
    skipTaskbar: true,
    show: false,
    hasShadow: false,
    webPreferences: { ...SHARED_WEB_PREFS },
  })
  win.lianyuKind = 'launcher'
  attachWindowLogging(win, 'launcher')

  win.once('ready-to-show', () => {
    win.show()
  })

  let moveTimer = null
  win.on('moved', () => {
    if (moveTimer) clearTimeout(moveTimer)
    moveTimer = setTimeout(() => {
      const bounds = win.getBounds()
      writeLauncherPosition(bounds.x, bounds.y)
      repositionPickerNearLauncher()
    }, 200)
  })

  win.on('closed', () => {
    launcherWindow = null
  })

  loadRoute(win, '#/launcher')
  launcherWindow = win
  return win
}

function ensureLauncherWindow() {
  const settings = readDesktopSettings()
  if (!settings.showLauncherLogo) {
    if (launcherWindow && !launcherWindow.isDestroyed()) {
      launcherWindow.close()
    }
    return null
  }
  return createLauncherWindow()
}

function repositionPickerNearLauncher() {
  if (!pickerWindow || pickerWindow.isDestroyed() || !launcherWindow || launcherWindow.isDestroyed()) {
    return
  }
  const launcherBounds = launcherWindow.getBounds()
  const pickerBounds = pickerWindow.getBounds()
  const display = screen.getDisplayNearestPoint({
    x: launcherBounds.x,
    y: launcherBounds.y,
  })
  const area = display.workArea

  let x = launcherBounds.x - pickerBounds.width - 8
  let y = launcherBounds.y
  if (x < area.x) {
    x = launcherBounds.x + launcherBounds.width + 8
  }
  if (y + pickerBounds.height > area.y + area.height) {
    y = area.y + area.height - pickerBounds.height
  }
  if (y < area.y) y = area.y
  pickerWindow.setPosition(Math.round(x), Math.round(y))
}

function createPickerWindow() {
  if (pickerWindow && !pickerWindow.isDestroyed()) {
    return pickerWindow
  }

  const win = new BrowserWindow({
    width: 320,
    height: 420,
    frame: false,
    transparent: true,
    resizable: false,
    alwaysOnTop: true,
    skipTaskbar: true,
    show: false,
    webPreferences: { ...SHARED_WEB_PREFS },
  })
  win.lianyuKind = 'picker'
  attachWindowLogging(win, 'picker')

  win.on('blur', () => {
    if (pickerBlurTimer) clearTimeout(pickerBlurTimer)
    pickerBlurTimer = setTimeout(() => {
      if (win.isDestroyed() || win.isFocused()) return
      if (Date.now() < pickerOpeningUntil) return
      win.hide()
    }, 280)
  })

  win.on('closed', () => {
    pickerWindow = null
  })

  loadRoute(win, '#/launcher/pick')
  pickerWindow = win
  return win
}

function openCharacterPicker(options = {}) {
  const { inactive = false } = options
  ensureLauncherWindow()
  const win = createPickerWindow()
  repositionPickerNearLauncher()
  pickerOpeningUntil = Date.now() + 450
  if (inactive) {
    win.showInactive()
  } else {
    win.show()
    win.focus()
  }
}

function toggleCharacterPicker(options = {}) {
  if (pickerWindow && !pickerWindow.isDestroyed() && pickerWindow.isVisible()) {
    pickerWindow.hide()
    return
  }
  openCharacterPicker(options)
}

function closeCharacterPicker() {
  if (pickerWindow && !pickerWindow.isDestroyed()) {
    pickerWindow.hide()
  }
}

function openQuickChatWindow(conversationId) {
  const id = String(conversationId)
  closeCharacterPicker()

  const existing = quickChatWindows.get(id)
  if (existing && !existing.isDestroyed()) {
    existing.show()
    existing.focus()
    return existing
  }

  const win = new BrowserWindow({
    width: 420,
    height: 680,
    minWidth: 360,
    minHeight: 520,
    title: 'LianYu 聊天',
    icon: resolveDistPath('logo.png'),
    backgroundColor: '#0a0a10',
    show: false,
    webPreferences: { ...SHARED_WEB_PREFS },
  })
  win.lianyuKind = 'quickChat'
  win.setMenuBarVisibility(false)
  attachWindowLogging(win, `quickChat:${id}`)

  win.once('ready-to-show', () => {
    win.show()
  })

  win.on('closed', () => {
    quickChatWindows.delete(id)
  })

  loadRoute(win, `#/quick/chat/${id}`)
  quickChatWindows.set(id, win)
  return win
}

function closeFocusedQuickChat() {
  const focused = BrowserWindow.getFocusedWindow()
  if (focused && focused.lianyuKind === 'quickChat' && !focused.isDestroyed()) {
    focused.close()
  }
}

function quitApplication() {
  isQuitting = true
  closeCharacterPicker()
  for (const win of quickChatWindows.values()) {
    if (!win.isDestroyed()) win.destroy()
  }
  quickChatWindows.clear()
  if (launcherWindow && !launcherWindow.isDestroyed()) launcherWindow.destroy()
  if (mainWindow && !mainWindow.isDestroyed()) mainWindow.destroy()
  tray?.destroy()
  tray = null
  app.quit()
}

function registerIpcHandlers() {
  ipcMain.handle('desktop:get-window-kind', (event) => {
    const win = BrowserWindow.fromWebContents(event.sender)
    return win?.lianyuKind || 'unknown'
  })

  ipcMain.handle('desktop:open-main', (_event, hash) => {
    showMainWindow(hash || '#/app')
  })

  ipcMain.handle('desktop:open-quick-chat', (_event, conversationId) => {
    openQuickChatWindow(conversationId)
  })

  ipcMain.handle('desktop:toggle-picker', () => {
    toggleCharacterPicker({ inactive: true })
  })

  ipcMain.handle('desktop:close-picker', () => {
    closeCharacterPicker()
  })

  ipcMain.handle('desktop:close-quick-chat', () => {
    closeFocusedQuickChat()
  })

  ipcMain.handle('desktop:notify-launcher-new-message', (_event, payload) => {
    notifyLauncherWindows(payload || {})
    return { ok: true }
  })

  ipcMain.handle('desktop:hide-launcher', () => {
    hideLauncherWindow()
  })

  ipcMain.handle('desktop:show-launcher', () => {
    showLauncherWindow()
  })

  ipcMain.handle('desktop:quit', () => {
    quitApplication()
  })

  ipcMain.handle('desktop:get-settings', () => readDesktopSettings())

  ipcMain.handle('desktop:set-settings', (_event, partial) => {
    const next = writeDesktopSettings(partial || {})
    if (!next.showLauncherLogo && launcherWindow && !launcherWindow.isDestroyed()) {
      launcherWindow.close()
    } else if (next.showLauncherLogo && mainWindow && !mainWindow.isVisible()) {
      showLauncherWindow()
    }
    if (tray) {
      tray.setContextMenu(buildTrayMenu())
    }
    return next
  })

  ipcMain.handle('desktop:ack-close-hint', () => {
    writeDesktopSettings({ closeHintShown: true })
    if (pendingHideAfterHint) {
      pendingHideAfterHint = false
      hideMainToTray()
    }
  })

  ipcMain.handle('desktop:save-launcher-position', (_event, { x, y }) => {
    if (Number.isFinite(x) && Number.isFinite(y)) {
      writeLauncherPosition(x, y)
    }
  })

  ipcMain.handle('desktop:move-launcher-by-delta', (_event, { dx, dy }) => {
    if (!launcherWindow || launcherWindow.isDestroyed()) return
    if (!Number.isFinite(dx) || !Number.isFinite(dy)) return
    const bounds = launcherWindow.getBounds()
    launcherWindow.setPosition(Math.round(bounds.x + dx), Math.round(bounds.y + dy))
    const next = launcherWindow.getBounds()
    writeLauncherPosition(next.x, next.y)
    repositionPickerNearLauncher()
  })
}

app.whenReady().then(() => {
  log('app ready')
  configureSecurity()
  patchDesktopRequestOrigin()
  applyLaunchAtLogin(readDesktopSettings().launchAtLogin)
  registerIpcHandlers()
  createMainWindow()
  ensureTray()

  globalShortcut.register('F12', () => {
    const win = BrowserWindow.getFocusedWindow()
    win?.webContents.toggleDevTools()
  })

  app.on('activate', () => {
    showMainWindow()
  })
})

app.on('will-quit', () => {
  globalShortcut.unregisterAll()
})

app.on('window-all-closed', () => {
  // Keep running in tray / launcher mode on Windows
})
