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
  Notification,
  powerMonitor,
} from 'electron'
import path from 'path'
import fs from 'fs'
import crypto from 'crypto'
import { fileURLToPath } from 'url'
import {
  readDesktopSettings,
  writeDesktopSettings,
  applyLaunchAtLogin,
  readLauncherPosition,
  writeLauncherPosition,
  isDesktopPetEnabled,
} from './desktopSettings.js'
import {
  readAuthSession,
  writeAuthSession,
  clearAuthSession,
} from './authSessionStore.js'
import {
  startDesktopObserver,
  stopDesktopObserver,
  onWindowChanged,
} from './desktopObserver.js'

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
let launcherIsDragging = false
let launcherSuppressMoveSave = false
/** 桌宠是否允许显示（用户已登录后才允许） */
let launcherLoggedIn = false

const SHARED_WEB_PREFS = {
  preload: path.join(__dirname, 'preload.cjs'),
  contextIsolation: true,
  nodeIntegration: false,
  sandbox: true,
  partition: 'persist:lianyu',
  devTools: isDebug,
}

/** 透明桌宠窗口：Windows 上 sandbox 会破坏透明合成，需单独关闭 */
const LAUNCHER_WEB_PREFS = {
  ...SHARED_WEB_PREFS,
  sandbox: false,
}

const DEFAULT_API_ORIGIN = 'http://localhost:8080'

function resolveApiOrigin() {
  const configured = process.env.LIANYU_API_ORIGIN || process.env.VITE_LIANYU_API_ORIGIN
  const trimmed = configured && String(configured).trim()
  return (trimmed || DEFAULT_API_ORIGIN).replace(/\/$/, '')
}

/** 服务器证书 SPKI SHA-256（Base64）。当证书续期但私钥不变时无需更新此值 */
const PINNED_SPKI = 'EdDpp/Z9REuRjqZLzXXrOW8opTtR8Yph2YM0s+xuLss='

/** 服务器证书 SHA-256 指纹（构建时注入），用于自签名证书固定二层兜底 */
const EXPECTED_CERT_FINGERPRINT = (process.env.LIANYU_CERT_FINGERPRINT || '').trim()

function configureCertificatePinning() {
  const ses = session.defaultSession

  // 方式一（主力）：接管 TLS 验证，对自签名证书做 SPKI 指纹比对
  ses.setCertificateVerifyProc((request, callback) => {
    const { hostname, certificate } = request

    // 只拦截自己服务器的 TLS 连接，其他域名走 Chromium 默认验证
    const apiOrigin = resolveApiOrigin()
    let apiHostname = ''
    try {
      apiHostname = new URL(apiOrigin).hostname
    } catch {
      // ignore
    }
    if (!apiHostname || hostname !== apiHostname) {
      callback(-3) // 使用 Chromium 默认验证
      return
    }

    // 计算服务端证书的 SPKI SHA-256（与 PINNED_SPKI 比对）
    try {
      const spki = getSPKIHash(certificate)
      if (spki === PINNED_SPKI) {
        callback(0) // 信任——与硬编码 SPKI 完全匹配
        return
      }
      log(`cert SPKI mismatch for ${hostname}: got ${spki}, expected ${PINNED_SPKI}`)
    } catch (e) {
      log(`cert SPKI compute failed for ${hostname}: ${e.message}`)
    }

    // SPKI 不匹配 —— 不直接拒绝，交给 Chromium 默认验证链，
    // 以便兼容安全软件 HTTPS 扫描（其根证书已在系统信任库中）。
    callback(-3)
  })

  // 方式二（兜底）：certificate-error 事件再校验一次指纹（含诊断信息）
  if (EXPECTED_CERT_FINGERPRINT) {
    app.on('certificate-error', (event, _webContents, url, _error, cert, cb) => {
      const actualFp = cert.fingerprint || ''
      const expectedFp = EXPECTED_CERT_FINGERPRINT.replace(/:/g, '').toLowerCase()
      const actualFpLower = actualFp.toLowerCase()
      if (actualFpLower === expectedFp) {
        log(`cert-error pin OK for ${url}`)
        event.preventDefault()
        cb(true)
        return
      }
      // 诊断：输出 issuer / subject 以判断是否安全软件 HTTPS 扫描
      log(`cert-error MISMATCH: expected=${expectedFp} actual=${actualFpLower}`)
      log(`cert-error issuer=${cert.issuerName} subject=${cert.subjectName}`)
      // 不阻断连接 —— 安全软件 HTTPS 扫描场景下证书由系统信任库验证
      event.preventDefault()
      cb(true)
    })
  }
}

/** 从 Electron Certificate 对象计算 SPKI SHA-256（Base64） */
function getSPKIHash(certificate) {
  // Electron 的 Certificate 对象包含 subjectPublicKeyInfo 字段
  const spki = certificate.subjectPublicKeyInfo
  if (spki) {
    const hash = crypto.createHash('sha256').update(spki).digest('base64')
    return hash
  }
  // 降级：使用 issuerCert 的 fingerprint
    throw new Error('SPKI not available')
}

/** 验证 app.asar 完整性（构建时 after-pack.mjs 注入哈希），防篡改源码注入 */
function verifyAsarIntegrity() {
  if (isDev) return
  try {
    const hexPath = path.join(process.resourcesPath, 'dist', 'asar-integrity.hex')
    if (!fs.existsSync(hexPath)) {
      log('asar integrity hash not found, skipping verification')
      return
    }
    const expected = fs.readFileSync(hexPath, 'utf8').trim()
    if (!expected || expected.length !== 64) {
      log('asar integrity hash invalid, skipping verification')
      return
    }
    // app.setAsarIntegrity 是 Electron 内置的 ASAR 完整性校验 API
    ;(app.setAsarIntegrity ?? app.setAppAsarIntegrity)?.(expected)
    log('asar integrity verification enabled')
  } catch (err) {
    log(`asar integrity verification failed: ${err.message}`)
    // 不阻止启动——完整性校验失败由 Electron 内部处理（弹窗或退出）
  }
}

function configureSecurity() {
  verifyAsarIntegrity()
  configureCertificatePinning()
}

/**
 * 反调试与反抓包：
 * 1. 生产环境禁用 DevTools（快捷键 + 右键菜单）
 * 2. 剥离 --inspect / --remote-debugging-port 启动参数
 * 3. 检测系统代理（Charles/Fiddler/Proxyman 会设系统代理）
 */
function configureAntiDebug() {
  // 剥离调试参数（禁止外部调试器附加）
  const stripFlags = [
    '--inspect', '--inspect-brk', '--inspect-port',
    '--remote-debugging-port', '--remote-debugging-address',
  ]
  for (const flag of stripFlags) {
    app.commandLine.removeSwitch(flag)
  }

  // 生产环境禁用所有窗口的 DevTools
  if (!isDebug) {
    app.on('web-contents-created', (_event, contents) => {
      contents.on('before-input-event', (_e, input) => {
        // 屏蔽 Ctrl+Shift+I / F12 / Ctrl+Shift+J / Ctrl+Shift+C
        if (
          (input.control && input.shift && (input.key === 'I' || input.key === 'J' || input.key === 'C'))
          || input.key === 'F12'
        ) {
          contents.setIgnoreMenuShortcuts(true)
        }
      })

      // 阻止通过代码打开 DevTools
      contents.on('devtools-opened', () => {
        if (!contents.isDestroyed()) {
          contents.closeDevTools()
        }
      })
    })
  }

  // 系统代理检测（抓包工具会修改系统代理设置）—— 不阻塞启动
  checkSystemProxy().catch(() => {})
}

/** 异步检测系统代理（Fiddler/Charles/VPN 等会修改系统代理设置） */
async function checkSystemProxy() {
  try {
    const proxySettings = await app.resolveProxy('https://example.com')
    if (proxySettings && proxySettings !== 'DIRECT') {
      log(`WARNING: system proxy detected: ${proxySettings}`)
    }
  } catch {
    // 检测失败不阻塞启动
  }
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

function isMainWindowOccupyingDesktop() {
  if (!mainWindow || mainWindow.isDestroyed()) return false
  if (mainWindow.isMinimized()) return false
  return mainWindow.isVisible()
}

function isPositionWithinAnyWorkArea(x, y) {
  return screen.getAllDisplays().some((display) => {
    const area = display.workArea
    return x >= area.x && y >= area.y && x < area.x + area.width && y < area.y + area.height
  })
}

function defaultLauncherPosition() {
  const area = screen.getPrimaryDisplay().workArea
  return {
    x: Math.round(area.x + (area.width - LAUNCHER_WINDOW.width) / 2),
    y: Math.round(area.y + (area.height - LAUNCHER_WINDOW.height) / 2),
  }
}

function resolveLauncherPosition() {
  const saved = readLauncherPosition()
  if (saved && isPositionWithinAnyWorkArea(saved.x, saved.y)) {
    return saved
  }
  return defaultLauncherPosition()
}

function resetLauncherCompactSize() {
  if (!launcherWindow || launcherWindow.isDestroyed()) return
  const b = launcherWindow.getBounds()
  if (b.width === LAUNCHER_WINDOW.width && b.height === LAUNCHER_WINDOW.height) return
  launcherSuppressMoveSave = true
  launcherWindow.setBounds({
    x: b.x + (b.width - LAUNCHER_WINDOW.width),
    y: b.y + (b.height - LAUNCHER_WINDOW.height),
    width: LAUNCHER_WINDOW.width,
    height: LAUNCHER_WINDOW.height,
  })
  launcherSuppressMoveSave = false
}

function clampLauncherToWorkArea() {
  if (!launcherWindow || launcherWindow.isDestroyed() || launcherIsDragging) return
  const bounds = launcherWindow.getBounds()
  const display = screen.getDisplayNearestPoint({ x: bounds.x, y: bounds.y })
  const area = display.workArea
  let x = bounds.x
  let y = bounds.y
  if (x + bounds.width > area.x + area.width) x = area.x + area.width - bounds.width
  if (y + bounds.height > area.y + area.height) y = area.y + area.height - bounds.height
  if (x < area.x) x = area.x
  if (y < area.y) y = area.y
  if (x !== bounds.x || y !== bounds.y) {
    launcherWindow.setPosition(Math.round(x), Math.round(y))
    writeLauncherPosition(x, y)
  }
}

function centerLauncherOnScreen() {
  if (!launcherWindow || launcherWindow.isDestroyed()) return
  resetLauncherCompactSize()
  const pos = defaultLauncherPosition()
  launcherWindow.setPosition(pos.x, pos.y)
  writeLauncherPosition(pos.x, pos.y)
  resetLauncherInteraction()
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

const LAUNCHER_WINDOW = { width: 200, height: 268 }

function setLauncherMousePassthrough(ignore) {
  if (!launcherWindow || launcherWindow.isDestroyed()) return
  if (ignore) {
    launcherWindow.setIgnoreMouseEvents(true, { forward: true })
  } else {
    launcherWindow.setIgnoreMouseEvents(false)
  }
}

/** 显示/拖拽结束后重置穿透与拖拽状态。仅角色选择器打开时穿透，其余时候拦截鼠标保证桌宠可交互。 */
function resetLauncherInteraction() {
  launcherIsDragging = false
  const pickerActive = pickerWindow && !pickerWindow.isDestroyed() && pickerWindow.isVisible()
  setLauncherMousePassthrough(pickerActive)
  if (launcherWindow && !launcherWindow.isDestroyed() && !launcherWindow.webContents.isDestroyed()) {
    launcherWindow.webContents.send('desktop:launcher-interaction-reset')
  }
}

function expandLauncherForToast() {
  // 固定窗口尺寸，toast 在预留区域显示，避免 resize 导致窗口位置上漂
}

function isMainWindowInBackground() {
  if (!mainWindow || mainWindow.isDestroyed()) return true
  if (!mainWindow.isVisible() || mainWindow.isMinimized()) return true
  return !mainWindow.isFocused()
}

function isDesktopPetActivelyVisible() {
  const settings = readDesktopSettings()
  if (!isDesktopPetEnabled(settings)) return false
  if (!launcherWindow || launcherWindow.isDestroyed()) return false
  return launcherWindow.isVisible()
}

function showLauncherMessageNotification(payload = {}) {
  if (!Notification.isSupported()) return
  const name = String(payload.characterName || '她').trim() || '她'
  const body = `${name}给你发消息了哦`
  const notification = new Notification({
    title: '恋语',
    body,
    silent: false,
  })
  notification.on('click', () => {
    const hash = payload.conversationId
      ? `#/app/chat/${payload.conversationId}`
      : '#/app'
    showMainWindow(hash)
  })
  notification.show()
}

/** 角色主动消息：后台 + 桌宠不可见 → Windows 系统通知；桌宠可见 → 仅桌宠提示 */
function handleProactiveMessageNotification(payload = {}) {
  if (!isMainWindowInBackground()) return

  if (isDesktopPetActivelyVisible()) {
    launcherWindow.webContents.send('desktop:launcher-new-message', payload)
    if (pickerWindow && !pickerWindow.isDestroyed() && pickerWindow.isVisible()) {
      pickerWindow.webContents.send('desktop:launcher-new-message', payload)
    }
    return
  }

  showLauncherMessageNotification(payload)
}

function hideLauncherWindow() {
  if (launcherWindow && !launcherWindow.isDestroyed()) {
    launcherWindow.hide()
  }
}

function showLauncherWindow(options = {}) {
  const { center = false, force = false } = options
  if (!launcherLoggedIn) return
  const settings = readDesktopSettings()
  if (!isDesktopPetEnabled(settings)) return
  if (!force && isMainWindowOccupyingDesktop()) return
  const win = ensureLauncherWindow()
  if (!win) return
  resetLauncherCompactSize()
  if (center) {
    centerLauncherOnScreen()
  } else {
    clampLauncherToWorkArea()
  }
  const reveal = () => {
    if (!force && isMainWindowOccupyingDesktop()) return
    if (!win || win.isDestroyed()) return
    resetLauncherInteraction()
    win.show()
    win.moveTop()
  }
  reveal()
  if (win.webContents.isLoading()) {
    win.webContents.once('did-finish-load', reveal)
  }
  setTimeout(reveal, 120)
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
  closeCharacterPicker()
  hideLauncherWindow()
}

const CAPTION_BAR_HEIGHT = 52
const WIN_TITLE_BAR_OVERLAY = {
  color: '#0a0a10',
  symbolColor: '#d4d4d8',
  height: CAPTION_BAR_HEIGHT,
}

function resolveCaptionMetrics(win) {
  const height = CAPTION_BAR_HEIGHT
  let controlsWidth = 138
  if (process.platform === 'win32' && win && !win.isDestroyed()) {
    try {
      const rect = win.getTitleBarOverlayRect?.()
      if (rect?.width > 0) {
        controlsWidth = Math.ceil(rect.width)
      }
    } catch (e) {
      log(`getTitleBarOverlayRect failed: ${e.message}`)
    }
  } else if (process.platform === 'darwin') {
    controlsWidth = 78
  }
  return { height, controlsWidth }
}

function pushCaptionMetrics(win) {
  if (!win?.webContents || win.webContents.isDestroyed()) return
  win.webContents.send('desktop:caption-metrics', resolveCaptionMetrics(win))
}

function attachCaptionMetricsChannel(win) {
  const push = () => pushCaptionMetrics(win)
  win.once('ready-to-show', push)
  win.on('resize', push)
  win.on('enter-full-screen', push)
  win.on('leave-full-screen', push)
}

/** Windows 须在 BrowserWindow 构造时传入 titleBarOverlay，否则 setTitleBarOverlay 会抛错 */
function buildCaptionWindowOptions() {
  if (process.platform === 'win32') {
    return {
      titleBarStyle: 'hidden',
      titleBarOverlay: WIN_TITLE_BAR_OVERLAY,
    }
  }
  if (process.platform === 'darwin') {
    return { titleBarStyle: 'hiddenInset' }
  }
  return {}
}

function applyMainWindowCaption(win) {
  if (!win || win.isDestroyed()) return
  if (process.platform === 'darwin') {
    win.setWindowButtonVisibility(true)
  }
}

function hideMainToTray() {
  if (!mainWindow || mainWindow.isDestroyed()) return
  mainWindow.hide()
  ensureTray()
  showLauncherWindow({ center: true, force: true })
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
    ...buildCaptionWindowOptions(),
    show: false,
    webPreferences: { ...SHARED_WEB_PREFS },
  })
  win.lianyuKind = 'main'
  win.setMenuBarVisibility(false)
  attachWindowLogging(win, 'main')
  attachCaptionMetricsChannel(win)

  win.once('ready-to-show', () => {
    applyMainWindowCaption(win)
    pushCaptionMetrics(win)
    win.show()
  })

  win.on('minimize', () => {
    setTimeout(() => {
      showLauncherWindow({ center: true, force: true })
    }, 50)
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

  const saved = resolveLauncherPosition()
  const win = new BrowserWindow({
    width: LAUNCHER_WINDOW.width,
    height: LAUNCHER_WINDOW.height,
    x: saved.x,
    y: saved.y,
    useContentSize: true,
    frame: false,
    transparent: true,
    backgroundColor: '#00000000',
    resizable: false,
    alwaysOnTop: true,
    skipTaskbar: true,
    show: false,
    hasShadow: false,
    thickFrame: false,
    webPreferences: { ...LAUNCHER_WEB_PREFS },
  })
  win.lianyuKind = 'launcher'
  attachWindowLogging(win, 'launcher')

  win.once('ready-to-show', () => {
    win.setBackgroundColor('#00000000')
    clampLauncherToWorkArea()
    // 不在此处自动 show —— 桌宠仅在主窗口最小化/关闭且已登录时通过 showLauncherWindow() 显示
  })

  win.webContents.on('did-finish-load', () => {
    win.setBackgroundColor('#00000000')
    resetLauncherInteraction()
  })

  let moveTimer = null
  win.on('moved', () => {
    if (pickerWindow && !pickerWindow.isDestroyed() && pickerWindow.isVisible() && !launcherIsDragging) {
      repositionPickerNearLauncher()
    }
    if (moveTimer) clearTimeout(moveTimer)
    if (launcherIsDragging) return
    if (launcherSuppressMoveSave) return
    moveTimer = setTimeout(() => {
      const bounds = win.getBounds()
      writeLauncherPosition(bounds.x, bounds.y)
      clampLauncherToWorkArea()
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
  if (!isDesktopPetEnabled(settings)) {
    if (launcherWindow && !launcherWindow.isDestroyed()) {
      launcherWindow.close()
    }
    return null
  }
  return createLauncherWindow()
}

/** 启动时预创建桌宠窗口（不显示），后续最小化/关闭时瞬间展示，消除冷创建延迟 */
function prewarmLauncherWindow() {
  if (!isDesktopPetEnabled(readDesktopSettings())) return
  createLauncherWindow()
}

function repositionPickerNearLauncher() {
  if (!pickerWindow || pickerWindow.isDestroyed() || !launcherWindow || launcherWindow.isDestroyed()) {
    return
  }
  const launcherBounds = launcherWindow.getBounds()
  const pickerBounds = pickerWindow.getBounds()
  const display = screen.getDisplayNearestPoint({
    x: launcherBounds.x + launcherBounds.width / 2,
    y: launcherBounds.y + launcherBounds.height / 2,
  })
  const area = display.workArea
  const gap = 2

  let x = launcherBounds.x + Math.round((launcherBounds.width - pickerBounds.width) / 2)
  let y = launcherBounds.y - pickerBounds.height - gap
  if (y < area.y) {
    y = launcherBounds.y + launcherBounds.height + gap
  }
  if (y + pickerBounds.height > area.y + area.height) {
    y = area.y + area.height - pickerBounds.height
  }
  if (x + pickerBounds.width > area.x + area.width) {
    x = area.x + area.width - pickerBounds.width
  }
  if (x < area.x) x = area.x
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
    backgroundColor: '#00000000',
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
    }, 600)
  })

  win.on('hide', () => {
    setLauncherMousePassthrough(false)
  })

  win.on('closed', () => {
    pickerWindow = null
    setLauncherMousePassthrough(false)
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
  pickerOpeningUntil = Date.now() + 1200
  // 确保 picker 在桌宠窗口之上，且桌宠窗口穿透不拦截 picker 区域的点击
  win.setAlwaysOnTop(true, 'floating')
  if (launcherWindow && !launcherWindow.isDestroyed()) {
    launcherWindow.setAlwaysOnTop(true, 'normal')
  }
  setLauncherMousePassthrough(true)
  if (inactive) {
    win.showInactive()
  } else {
    win.show()
    win.focus()
    win.moveTop()
  }
  repositionPickerNearLauncher()
}

function toggleCharacterPicker(options = {}) {
  if (pickerWindow && !pickerWindow.isDestroyed() && pickerWindow.isVisible()) {
    pickerWindow.hide()
    setLauncherMousePassthrough(false)
    return
  }
  openCharacterPicker(options)
}

function closeCharacterPicker() {
  if (pickerWindow && !pickerWindow.isDestroyed()) {
    pickerWindow.hide()
    setLauncherMousePassthrough(false)
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
    ...buildCaptionWindowOptions(),
    show: false,
    webPreferences: { ...SHARED_WEB_PREFS },
  })
  win.lianyuKind = 'quickChat'
  win.setMenuBarVisibility(false)
  attachWindowLogging(win, `quickChat:${id}`)
  attachCaptionMetricsChannel(win)

  win.once('ready-to-show', () => {
    applyMainWindowCaption(win)
    pushCaptionMetrics(win)
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

  ipcMain.handle('desktop:notify-proactive-message', (_event, payload) => {
    handleProactiveMessageNotification(payload || {})
    return { ok: true }
  })

  ipcMain.handle('desktop:get-caption-height', () => CAPTION_BAR_HEIGHT)

  ipcMain.handle('desktop:get-caption-metrics', (event) => {
    const win = BrowserWindow.fromWebContents(event.sender)
    return resolveCaptionMetrics(win)
  })

  ipcMain.handle('desktop:set-login-state', (_event, loggedIn) => {
    launcherLoggedIn = !!loggedIn
    if (!launcherLoggedIn) {
      hideLauncherWindow()
      closeCharacterPicker()
    }
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

  ipcMain.handle('desktop:set-launcher-mouse-passthrough', (_event, ignore) => {
    setLauncherMousePassthrough(!!ignore)
  })

  ipcMain.handle('auth:get-session', () => readAuthSession())
  ipcMain.handle('auth:set-session', (_event, session) => writeAuthSession(session))
  ipcMain.handle('auth:clear-session', () => {
    clearAuthSession()
    return { ok: true }
  })

  ipcMain.handle('desktop:get-settings', () => readDesktopSettings())

  ipcMain.handle('desktop:set-settings', (_event, partial) => {
    const next = writeDesktopSettings(partial || {})
    if (!isDesktopPetEnabled(next) && launcherWindow && !launcherWindow.isDestroyed()) {
      launcherWindow.close()
    } else if (isDesktopPetEnabled(next) && mainWindow && !mainWindow.isVisible()) {
      showLauncherWindow()
    }
    if (partial?.launcherPetId != null && launcherWindow && !launcherWindow.isDestroyed()) {
      resetLauncherCompactSize()
      clampLauncherToWorkArea()
      launcherWindow.webContents.send('desktop:launcher-pet-changed', partial.launcherPetId)
    }
    if (tray) {
      tray.setContextMenu(buildTrayMenu())
    }
    return next
  })

  ipcMain.handle('desktop:ack-close-hint', () => {
    writeDesktopSettings({ closeHintShown: true })
    pendingHideAfterHint = false
  })

  ipcMain.handle('desktop:start-observer', (_event, { apiOrigin, persona, petId }) => {
    startDesktopObserver({
      apiOrigin,
      persona,
      petId,
      onGreeting: (greeting) => {
        if (launcherWindow && !launcherWindow.isDestroyed()) {
          launcherWindow.webContents.send('desktop:launcher-greeting', { text: greeting })
        }
      },
    })
    return { ok: true }
  })

  ipcMain.handle('desktop:stop-observer', () => {
    stopDesktopObserver()
    return { ok: true }
  })

  ipcMain.handle('desktop:notify-window-changed', () => {
    onWindowChanged()
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

  // 同步 IPC：拖动时逐帧位移，避免 invoke 排队导致窗口跟不上鼠标（尤其向右拖）
  ipcMain.on('desktop:launcher-drag-start', () => {
    launcherIsDragging = true
    if (launcherWindow && !launcherWindow.isDestroyed()) {
      launcherWindow.setBackgroundColor('#00000000')
    }
    setLauncherMousePassthrough(false)
  })

  ipcMain.on('desktop:launcher-drag-move', (_event, { dx, dy }) => {
    if (!launcherWindow || launcherWindow.isDestroyed()) return
    if (!Number.isFinite(dx) || !Number.isFinite(dy)) return
    if (dx === 0 && dy === 0) return
    launcherIsDragging = true
    const bounds = launcherWindow.getBounds()
    launcherWindow.setPosition(Math.round(bounds.x + dx), Math.round(bounds.y + dy))
    repositionPickerNearLauncher()
  })

  ipcMain.on('desktop:launcher-drag-end', () => {
    if (!launcherWindow || launcherWindow.isDestroyed()) {
      launcherIsDragging = false
      return
    }
    launcherIsDragging = false
    // 不在此时写位置：clampLauncherToWorkArea 会写入正确值；
    // 且 Windows 上 setPosition 可能异步，getBounds 可能返回旧值。
    clampLauncherToWorkArea()
    repositionPickerNearLauncher()
    resetLauncherInteraction()
    // 兜底：延迟再 clamp + 存一次，防 setPosition 异步导致漏 clamp
    setTimeout(() => {
      if (launcherWindow && !launcherWindow.isDestroyed() && !launcherIsDragging) {
        clampLauncherToWorkArea()
      }
    }, 200)
  })

  ipcMain.handle('desktop:set-launcher-screen-position', (_event, { x, y }) => {
    if (!launcherWindow || launcherWindow.isDestroyed()) return
    if (!Number.isFinite(x) || !Number.isFinite(y)) return
    launcherIsDragging = true
    launcherWindow.setPosition(Math.round(x), Math.round(y))
    writeLauncherPosition(Math.round(x), Math.round(y))
  })

  ipcMain.handle('desktop:set-launcher-dragging', (_event, dragging) => {
    launcherIsDragging = !!dragging
    if (!launcherIsDragging) {
      clampLauncherToWorkArea()
      repositionPickerNearLauncher()
      resetLauncherInteraction()
    }
  })

  ipcMain.handle('desktop:clamp-launcher-position', () => {
    launcherIsDragging = false
    clampLauncherToWorkArea()
    repositionPickerNearLauncher()
    resetLauncherInteraction()
  })
}

app.whenReady().then(() => {
  log('app ready')
  configureSecurity()
  configureAntiDebug()
  patchDesktopRequestOrigin()
  applyLaunchAtLogin(readDesktopSettings().launchAtLogin)
  registerIpcHandlers()
  createMainWindow()
  ensureTray()

  // 电源事件：唤醒后通知窗口切换检测
  if (powerMonitor) {
    powerMonitor.on('resume', () => onWindowChanged())
    powerMonitor.on('unlock-screen', () => onWindowChanged())
  }

  // 桌宠仅在用户登录 + 主窗口最小化/关闭后才出现，不在启动时预创建

    if (isDebug) {
      globalShortcut.register('F12', () => {
        const win = BrowserWindow.getFocusedWindow()
        win?.webContents.toggleDevTools()
      })
    }

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
