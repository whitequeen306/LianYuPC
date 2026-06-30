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
  readAppearance,
  writeAppearance,
  resolveWindowBackgroundColor,
} from './appearanceStore.js'
import {
  startDesktopObserver,
  stopDesktopObserver,
  onWindowChanged,
} from './desktopObserver.js'
import { prepareGreetingPayload } from './greetingAudio.js'
import { performApiRequest, isAllowedEgressUrl, egressLimiter } from './apiProxy.js'
import {
  startQqBridge,
  stopQqBridge,
  getQqBridgeStatus,
} from './qqBridge/qqBridge.js'
import {
  readQqBridgeSettings,
  writeQqBridgeSettings,
} from './qqBridge/qqBridgeSettings.js'
import {
  startNapCatHost,
  stopNapCatHost,
  getNapCatHostStatus,
  isQqntReady,
} from './napcatRuntime/napcatHost.js'
import { wipeNapCatInstall } from './napcatRuntime/napcatRelease.js'
import { loadRuntimeSecrets, getRuntimeSecrets } from './runtimeSecrets.js'
import { RENDERER_AUTH_TOKEN_SCRIPT } from './rendererTokenScript.js'

const __dirname = path.dirname(fileURLToPath(import.meta.url))

const isDev = !!process.env.VITE_DEV_SERVER_URL
const isDebug = process.env.LIANYU_DEBUG === '1' || process.argv.includes('--lianyu-debug')

/** @type {BrowserWindow | null} */
let mainWindow = null
/** @type {BrowserWindow | null} */
let launcherWindow = null
/** @type {BrowserWindow | null} */
let pickerWindow = null
/** @type {BrowserWindow | null} */
let quickChatShell = null
let quickChatShellReady = false
/** @type {string | null} */
let quickChatPendingShowId = null
let quickChatReadyFallbackTimer = null
/** NapCat WebUI 扫码登录窗（自管托管）单例 */
let qqLoginWindow = null
/** @type {Tray | null} */
let tray = null
let isQuitting = false
let pendingHideAfterHint = false
let pickerBlurTimer = null
let pickerOpeningUntil = 0
let launcherHiddenForPicker = false
let launcherPickerOpen = false
/** @type {{ x: number, y: number, width: number, height: number } | null} */
let launcherBoundsBeforePicker = null
let launcherIsDragging = false
let launcherSuppressMoveSave = false
/** 桌宠是否允许显示（用户已登录后才允许） */
let launcherLoggedIn = false

const SHARED_WEB_PREFS = {
  preload: path.join(__dirname, app.isPackaged ? 'preload.cjs' : 'preload.cjs'),
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
  /** 识图问候 TTS 在无人点击桌宠时也需自动播放 */
  autoplayPolicy: 'no-user-gesture-required',
}

const DEFAULT_API_ORIGIN = 'http://localhost:8080'

loadRuntimeSecrets({
  secretsDir: __dirname,
  metaPath: path.join(__dirname, 'client-build.json'),
  isPackaged: app.isPackaged,
  isDev,
})

function resolveApiOrigin() {
  const secrets = getRuntimeSecrets()
  if (secrets?.apiOrigin) return secrets.apiOrigin
  return DEFAULT_API_ORIGIN
}

function pinnedSpkiValue() {
  return getRuntimeSecrets()?.pinnedSpki || ''
}

function expectedCertFingerprint() {
  return getRuntimeSecrets()?.certFingerprint || ''
}

/** 企业环境若需兼容安全软件 HTTPS 扫描，构建时设 LIANYU_ALLOW_SYSTEM_CA=1（默认拒绝不匹配证书） */
const ALLOW_SYSTEM_CA = process.env.LIANYU_ALLOW_SYSTEM_CA === '1'

function isApiHostname(hostname) {
  const apiOrigin = resolveApiOrigin()
  try {
    return hostname === new URL(apiOrigin).hostname
  } catch {
    return false
  }
}

/** Electron cert.fingerprint 为 sha256/<base64>，构建注入为冒号分隔 hex */
function normalizeCertFingerprint(fingerprint) {
  if (!fingerprint) return ''
  const raw = String(fingerprint).trim()
  if (/^sha256\//i.test(raw)) {
    try {
      return Buffer.from(raw.slice(7), 'base64').toString('hex').toLowerCase()
    } catch {
      return ''
    }
  }
  return raw.replace(/:/g, '').toLowerCase()
}

function certificateMatchesPin(cert) {
  if (!cert) return false
  try {
    const spki = getSPKIHash(cert)
    if (spki === pinnedSpkiValue()) return true
  } catch {
    // fall through to fingerprint check
  }
  const certFp = expectedCertFingerprint()
  if (!certFp) return false
  const expectedFp = certFp.replace(/:/g, '').toLowerCase()
  const actualFp = normalizeCertFingerprint(cert.fingerprint)
  return actualFp.length > 0 && actualFp === expectedFp
}

function getRendererSession() {
  return session.fromPartition(SHARED_WEB_PREFS.partition)
}

/** defaultSession（net.request）与 persist:lianyu（渲染进程 axios）均需证书 pin */
function forEachAppSession(fn) {
  fn(session.defaultSession)
  fn(getRendererSession())
}

function installCertificateVerifyProc(ses) {
  // 方式一（主力）：API 域名仅接受 SPKI 指纹匹配，不匹配则拒绝（-2）
  ses.setCertificateVerifyProc((request, callback) => {
    const { hostname, certificate } = request

    if (!isApiHostname(hostname)) {
      callback(-3) // 其他域名走 Chromium 默认验证
      return
    }

    if (certificateMatchesPin(certificate)) {
      callback(0)
      return
    }

    try {
      const spki = getSPKIHash(certificate)
      log(`cert SPKI mismatch for ${hostname}: got ${spki}, expected ${pinnedSpkiValue()}`)
    } catch (e) {
      log(`cert SPKI compute failed for ${hostname}: ${e.message}`)
    }

    if (ALLOW_SYSTEM_CA) {
      log(`cert pin mismatch for ${hostname}, falling back to system CA (LIANYU_ALLOW_SYSTEM_CA=1)`)
      callback(-3)
      return
    }
    log(`cert pin mismatch for ${hostname}, rejecting connection`)
    callback(-2)
  })
}

function configureCertificatePinning() {
  forEachAppSession(installCertificateVerifyProc)

  // 方式二（兜底）：certificate-error 仅 pin 匹配时放行
  app.on('certificate-error', (event, _webContents, url, _error, cert, cb) => {
    let hostname = ''
    try {
      hostname = new URL(url).hostname
    } catch {
      cb(false)
      return
    }
    if (!isApiHostname(hostname)) {
      return // 非 API 域名由 Chromium 默认处理
    }
    if (certificateMatchesPin(cert)) {
      log(`cert-error pin OK for ${url}`)
      event.preventDefault()
      cb(true)
      return
    }
    log(`cert-error REJECTED: url=${url} issuer=${cert.issuerName} subject=${cert.subjectName} fp=${normalizeCertFingerprint(cert.fingerprint)}`)
    if (ALLOW_SYSTEM_CA) {
      event.preventDefault()
      cb(true)
      return
    }
    cb(false)
  })
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

function configureContentSecurityPolicy() {
  const csp = [
    "default-src 'self'",
    // vue-i18n 运行时用 new Function 编译带参数的翻译文案，需放开 unsafe-eval；
    // 桌面端脚本源仍限定为 'self'，无外部脚本注入面，风险可控。
    "script-src 'self' 'unsafe-eval'",
    "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com",
    "font-src 'self' https://fonts.gstatic.com",
    "img-src 'self' data: blob: https:",
    "connect-src 'self' https: wss:",
    "media-src 'self' data: blob: file:",
    "object-src 'none'",
    "base-uri 'self'",
    "frame-ancestors 'none'",
  ].join('; ')
  forEachAppSession((ses) => {
    ses.webRequest.onHeadersReceived((details, callback) => {
      callback({
        responseHeaders: {
          ...details.responseHeaders,
          'Content-Security-Policy': [csp],
        },
      })
    })
  })
}

const EXTERNAL_URL_HOST_ALLOWLIST = new Set([
  'github.com',
  'www.github.com',
  'raw.githubusercontent.com',
  'fonts.googleapis.com',
  'fonts.gstatic.com',
])

function isAllowedExternalUrl(rawUrl) {
  try {
    const parsed = new URL(rawUrl)
    const protocol = parsed.protocol
    if (protocol === 'file:' || protocol === 'data:' || protocol === 'javascript:') {
      return false
    }
    if (protocol !== 'https:') return false
    const host = parsed.hostname.toLowerCase()
    if (EXTERNAL_URL_HOST_ALLOWLIST.has(host)) return true
    if (isApiHostname(host)) return true
    return false
  } catch {
    return false
  }
}

/** @type {Set<number>} */
const trustedWebContentsIds = new Set()

function registerTrustedWebContents(win) {
  const webContentsId = win.webContents.id
  trustedWebContentsIds.add(webContentsId)
  win.on('closed', () => {
    trustedWebContentsIds.delete(webContentsId)
  })
}

function assertTrustedSender(event) {
  if (!trustedWebContentsIds.has(event.sender.id)) {
    throw new Error('Untrusted IPC sender')
  }
  const url = event.senderFrame?.url || event.sender.getURL?.() || ''
  if (!url) {
    // 打包后 hash 路由下 frame URL 可能为空；已注册窗口仍视为可信
    return
  }
  const isPackagedUrl = url.startsWith('file://') || url.startsWith('app://')
  const isDevServerUrl = isDev && (url.startsWith('http://') || url.startsWith('https://'))
  if (!isPackagedUrl && !isDevServerUrl) {
    throw new Error('Untrusted IPC sender URL')
  }
}

function guardTrusted(event) {
  try {
    assertTrustedSender(event)
    return true
  } catch {
    return false
  }
}

function lockDownDevTools(win) {
  if (isDebug) return
  win.webContents.on('before-input-event', (event, input) => {
    const k = input.key?.toLowerCase()
    if (input.key === 'F12') {
      event.preventDefault()
      return
    }
    if ((input.control || input.meta) && input.shift && (k === 'i' || k === 'j' || k === 'c')) {
      event.preventDefault()
    }
    if ((input.control || input.meta) && k === 'u') {
      event.preventDefault()
    }
  })
  win.webContents.on('devtools-opened', () => {
    win.webContents.closeDevTools()
  })
}

function configureSecurity() {
  // 客户端完整性由 Electron asar-integrity fuse 守护（package.json electronFuses.enableEmbeddedAsarIntegrityValidation: true）：
  // electron-builder 在打包阶段把 app.asar 头哈希嵌入二进制，Electron 在 main 运行前校验，
  // 攻击者无法在不改二进制的前提下绕过；进程内自校验（读同目录 .hex）本身可被绕过，故移除。
  configureCertificatePinning()
  if (!isDev) {
    configureContentSecurityPolicy()
  }
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
  const ses = getRendererSession()

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

  ses.webRequest.onErrorOccurred({ urls: [`${apiOrigin}/*`] }, (details) => {
    log(`renderer API request failed: error=${details.error} url=${details.url}`)
  })
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
  return path.join(__dirname, '../dist')
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
  registerTrustedWebContents(win)
  lockDownDevTools(win)
  win.webContents.on('did-fail-load', (_event, errorCode, errorDescription, validatedURL) => {
    log(`${label} did-fail-load code=${errorCode} desc=${errorDescription} url=${validatedURL}`)
  })
  win.webContents.on('did-finish-load', () => {
    log(`${label} did-finish-load url=${win.webContents.getURL()}`)
    if (label === 'main') {
      void syncChromeFromRenderer(win)
    }
  })
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (isAllowedExternalUrl(url)) {
      shell.openExternal(url)
    } else {
      log(`blocked external url: ${url}`)
    }
    return { action: 'deny' }
  })
}

function isMainWindowOccupyingDesktop() {
  if (!mainWindow || mainWindow.isDestroyed()) return false
  if (mainWindow.isMinimized()) return false
  return mainWindow.isVisible()
}

function isPositionWithinAnyWorkArea(x, y, width = LAUNCHER_WINDOW.width, height = LAUNCHER_WINDOW.height) {
  const right = x + width
  const bottom = y + height
  return screen.getAllDisplays().some((display) => {
    const area = display.workArea
    return x >= area.x && y >= area.y && right <= area.x + area.width && bottom <= area.y + area.height
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
  const winWidth = bounds.width
  const winHeight = bounds.height
  const centerX = bounds.x + winWidth / 2
  const centerY = bounds.y + winHeight / 2
  const display = screen.getDisplayNearestPoint({ x: centerX, y: centerY })
  const area = display.workArea
  let x = bounds.x
  let y = bounds.y
  if (x + winWidth > area.x + area.width) x = area.x + area.width - winWidth
  if (y + winHeight > area.y + area.height) y = area.y + area.height - winHeight
  if (x < area.x) x = area.x
  if (y < area.y) y = area.y
  if (x !== bounds.x || y !== bounds.y) {
    launcherSuppressMoveSave = true
    launcherWindow.setPosition(Math.round(x), Math.round(y))
    writeLauncherPosition(x, y)
    launcherSuppressMoveSave = false
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
/** 角色列表固定高度：不随角色数量变化 */
const PICKER_PANEL_HEIGHT = 320
const PICKER_PANEL_WIDTH = 320
const PICKER_PET_GAP = 8

function applyLauncherMouseMode() {
  if (!launcherWindow || launcherWindow.isDestroyed()) return
  if (!launcherWindow.isVisible()) {
    launcherWindow.setIgnoreMouseEvents(true, { forward: true })
    return
  }
  // 桌宠可见时始终由窗口接收鼠标；可点区域由 CSS pointer-events 限定（与 v0.2.112 前一致）
  launcherWindow.setIgnoreMouseEvents(false)
}

function setLauncherMousePassthrough(ignore) {
  if (!launcherWindow || launcherWindow.isDestroyed()) return
  if (!launcherWindow.isVisible()) {
    launcherWindow.setIgnoreMouseEvents(true, { forward: true })
    return
  }
  if (ignore) {
    launcherWindow.setIgnoreMouseEvents(true, { forward: true })
  } else {
    launcherWindow.setIgnoreMouseEvents(false)
  }
}

/** 显示/拖拽结束后重置穿透：角色列表打开时整窗接收点击 */
function resetLauncherInteraction() {
  launcherIsDragging = false
  applyLauncherMouseMode()
  if (launcherWindow && !launcherWindow.isDestroyed() && !launcherWindow.webContents.isDestroyed()) {
    launcherWindow.webContents.send('desktop:launcher-interaction-reset')
  }
}

function restoreLauncherAfterPicker() {
  if (!launcherHiddenForPicker) return
  launcherHiddenForPicker = false
  if (launcherWindow && !launcherWindow.isDestroyed()) {
    if (!launcherWindow.isVisible()) {
      launcherWindow.show()
    }
    resetLauncherInteraction()
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
    return
  }

  showLauncherMessageNotification(payload)
}

function hideLauncherWindow() {
  if (launcherWindow && !launcherWindow.isDestroyed()) {
    stopDesktopObserver()
    launcherWindow.webContents.send('desktop:launcher-hidden')
    launcherWindow.setIgnoreMouseEvents(true, { forward: true })
    launcherWindow.hide()
  }
}

/** 主进程 auth session 为准，避免 renderer setLoginState IPC 丢失时桌宠永不显示 */
function refreshLauncherLoginState() {
  if (readAuthSession()?.token) {
    launcherLoggedIn = true
  }
}

const RENDERER_DESKTOP_STATE_SCRIPT = `
(function () {
  try {
    const theme = localStorage.getItem('lianyu-theme') === 'light' ? 'light' : 'dark'
    const loggedIn = Boolean(
      localStorage.getItem('_ltt')
      || localStorage.getItem('lianyu-user-profile')
    )
    const petRaw = localStorage.getItem('lianyu-show-desktop-pet')
    const launcherRaw = localStorage.getItem('lianyu-show-launcher')
    let showDesktopPet = true
    if (petRaw === 'false' || launcherRaw === 'false') showDesktopPet = false
    if (petRaw === 'true' || launcherRaw === 'true') showDesktopPet = true
    const launcherPetId = localStorage.getItem('lianyu-launcher-pet') || 'raiden'
    // #10：allowScreenObserve 不再从渲染层 localStorage 同步——XSS 写一条 localStorage 即可偷开屏幕上传。
    // 该开关只经主进程 desktop:set-settings IPC、由用户手势（授权确认）设置，权威存于 desktop-settings.json。
    return { theme, loggedIn, showDesktopPet, launcherPetId }
  } catch {
    return { theme: 'dark', loggedIn: false, showDesktopPet: true, launcherPetId: 'raiden' }
  }
})()
`

async function pullRendererDesktopState(win = mainWindow) {
  if (!win || win.isDestroyed() || win.webContents.isDestroyed()) return null
  try {
    return await win.webContents.executeJavaScript(RENDERER_DESKTOP_STATE_SCRIPT, true)
  } catch (e) {
    log(`pullRendererDesktopState failed: ${e.message}`)
    return null
  }
}

async function pullRendererAuthToken(win) {
  if (!win || win.isDestroyed() || win.webContents.isDestroyed()) return null
  try {
    const token = await win.webContents.executeJavaScript(RENDERER_AUTH_TOKEN_SCRIPT, true)
    if (typeof token === 'string' && token.trim()) return token.trim()
    return null
  } catch (e) {
    log(`pullRendererAuthToken failed: ${e.message}`)
    return null
  }
}

async function ensureAuthSessionFromRenderer(win = mainWindow) {
  if (readAuthSession()?.token) return true
  const sources = []
  if (win && !win.isDestroyed()) sources.push(win)
  if (mainWindow && !mainWindow.isDestroyed() && win !== mainWindow) sources.push(mainWindow)
  if (launcherWindow && !launcherWindow.isDestroyed() && win !== launcherWindow) sources.push(launcherWindow)
  for (const source of sources) {
    const token = await pullRendererAuthToken(source)
    if (token) {
      writeAuthSession({
        token,
        tokenName: 'lianyu-token',
        savedAt: Date.now(),
      })
      launcherLoggedIn = true
      return true
    }
  }
  return false
}

async function reconcileScreenObserveSetting(win = mainWindow) {
  // #10：不再从渲染层 state 同步 allowScreenObserve（XSS 可偷开屏幕上传）。
  // 开关只经 desktop:set-settings IPC 由用户手势设置，权威存于 desktop-settings.json；此处仅返回主进程设置。
  void win
  return readDesktopSettings()
}

async function syncLauncherPetId(petId, { notifyLauncher = true } = {}) {
  if (!petId) return
  const next = writeDesktopSettings({ launcherPetId: petId })
  if (notifyLauncher && launcherWindow && !launcherWindow.isDestroyed()) {
    resetLauncherCompactSize()
    clampLauncherToWorkArea()
    launcherWindow.webContents.send('desktop:launcher-pet-changed', next.launcherPetId || petId)
  }
}

async function syncChromeFromRenderer(win = mainWindow) {
  const state = await pullRendererDesktopState(win)
  if (!state) return
  if (state.loggedIn) {
    launcherLoggedIn = true
  }
  if (state.theme === 'light' || state.theme === 'dark') {
    writeAppearance(state.theme)
    applyTitleBarOverlayToAllWindows(state.theme)
    if (mainWindow && !mainWindow.isDestroyed()) {
      try {
        mainWindow.setBackgroundColor(resolveWindowBackgroundColor(state.theme))
      } catch (e) {
        log(`syncChrome setBackgroundColor failed: ${e.message}`)
      }
    }
  }
  if (state.showDesktopPet === false) {
    writeDesktopSettings({ showDesktopPet: false, showLauncherLogo: false })
  } else if (state.showDesktopPet === true) {
    writeDesktopSettings({ showDesktopPet: true, showLauncherLogo: true })
  }
  if (state.launcherPetId) {
    await syncLauncherPetId(state.launcherPetId)
  }
  // #10：allowScreenObserve 不再从渲染层 state 同步（防 XSS 偷开屏幕上传），只经主进程 IPC 由用户手势设置
  await ensureAuthSessionFromRenderer(win)
}

async function resolveDesktopAuthToken() {
  refreshLauncherLoginState()
  let session = readAuthSession()
  if (session?.token) return session.token
  await ensureAuthSessionFromRenderer(mainWindow)
  session = readAuthSession()
  if (session?.token) return session.token
  await ensureAuthSessionFromRenderer(launcherWindow)
  session = readAuthSession()
  if (session?.token) return session.token
  return null
}

function showLauncherWindow(options = {}) {
  void showLauncherWindowAsync(options)
}

async function showLauncherWindowAsync(options = {}) {
  const { center = false, force = false } = options
  refreshLauncherLoginState()
  if (!launcherLoggedIn && mainWindow && !mainWindow.isDestroyed()) {
    const state = await pullRendererDesktopState(mainWindow)
    if (state?.loggedIn) {
      launcherLoggedIn = true
      log('showLauncherWindow: recovered login state from renderer storage')
    }
    if (state?.showDesktopPet === true) {
      writeDesktopSettings({ showDesktopPet: true, showLauncherLogo: true })
    }
    if (state?.launcherPetId) {
      void syncLauncherPetId(state.launcherPetId)
    }
  }
  if (!launcherLoggedIn) {
    log('showLauncherWindow: aborted — not logged in')
    return
  }
  const settings = readDesktopSettings()
  if (!isDesktopPetEnabled(settings)) {
    log('showLauncherWindow: aborted — desktop pet disabled in settings')
    return
  }
  if (!force && isMainWindowOccupyingDesktop()) return
  const win = ensureLauncherWindow()
  if (!win) {
    log('showLauncherWindow: aborted — launcher window unavailable')
    return
  }
  resetLauncherCompactSize()
  if (center) {
    centerLauncherOnScreen()
  } else {
    clampLauncherToWorkArea()
  }
  const reveal = () => {
    if (!force && isMainWindowOccupyingDesktop()) return
    if (!win || win.isDestroyed()) return
    win.webContents.setAudioMuted(false)
    win.show()
    win.moveTop()
    resetLauncherInteraction()
    win.webContents.send('desktop:launcher-shown')
    log('showLauncherWindow: launcher shown')
    if (mainWindow && !mainWindow.isDestroyed()) {
      void syncChromeFromRenderer(mainWindow)
    }
  }
  if (win.webContents.isLoading()) {
    win.webContents.once('ready-to-show', () => { void reveal() })
  } else {
    void reveal()
  }
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

/** Windows 标题栏：透明 overlay 背景 + 主题色图标，保留系统原生 min/max/close */
const TITLE_BAR_PRESETS = {
  dark: { color: '#00000000', symbolColor: '#d4d4d8' },
  light: { color: '#00000000', symbolColor: '#1a1a1e' },
  landing: { color: '#00000000', symbolColor: '#e8eaef' },
}

let currentTitleBarPreset = 'dark'

function resolveTitleBarOverlay(presetKey = currentTitleBarPreset) {
  const preset = TITLE_BAR_PRESETS[presetKey] || TITLE_BAR_PRESETS.dark
  return {
    color: preset.color,
    symbolColor: preset.symbolColor,
    height: CAPTION_BAR_HEIGHT,
  }
}

function applyTitleBarOverlayToWindow(win, presetKey = currentTitleBarPreset) {
  if (process.platform !== 'win32' || !win || win.isDestroyed()) return
  const overlay = resolveTitleBarOverlay(presetKey)
  const mode = presetKey === 'light' ? 'light' : presetKey === 'landing' ? 'dark' : presetKey
  const apply = () => {
    try {
      win.setTitleBarOverlay(overlay)
      win.setBackgroundColor(resolveWindowBackgroundColor(mode === 'light' ? 'light' : 'dark'))
      pushCaptionMetrics(win)
    } catch (e) {
      log(`setTitleBarOverlay failed preset=${presetKey}: ${e.message}`)
    }
  }
  apply()
  setTimeout(apply, 0)
  setTimeout(apply, 150)
}

function applyTitleBarOverlayToAllWindows(presetKey) {
  currentTitleBarPreset = presetKey in TITLE_BAR_PRESETS ? presetKey : 'dark'
  applyTitleBarOverlayToWindow(mainWindow, currentTitleBarPreset)
  for (const win of [quickChatShell]) {
    if (win && !win.isDestroyed()) {
      applyTitleBarOverlayToWindow(win, currentTitleBarPreset)
    }
  }
  if (mainWindow && !mainWindow.isDestroyed()) {
    const mode = currentTitleBarPreset === 'light' ? 'light' : 'dark'
    try {
      mainWindow.setBackgroundColor(resolveWindowBackgroundColor(mode))
    } catch (e) {
      log(`setBackgroundColor failed: ${e.message}`)
    }
  }
}

function resolveTitleBarPresetKey({ surface, theme }) {
  if (surface === 'landing' || surface === 'auth') {
    return 'landing'
  }
  return theme === 'light' ? 'light' : 'dark'
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

/** 快捷聊天：无边框，仅页面内自定义 × 关闭 */
function buildQuickChatWindowOptions() {
  return {
    frame: false,
    thickFrame: false,
    hasShadow: true,
    skipTaskbar: false,
    resizable: true,
  }
}

/** Windows 须 hidden + titleBarOverlay，保留系统原生窗口按钮 */
function buildCaptionWindowOptions() {
  if (process.platform === 'win32') {
    return {
      titleBarStyle: 'hidden',
      titleBarOverlay: resolveTitleBarOverlay(currentTitleBarPreset),
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
  const appearanceMode = readAppearance()
  currentTitleBarPreset = appearanceMode === 'light' ? 'light' : 'dark'
  const win = new BrowserWindow({
    width: 1280,
    height: 800,
    minWidth: 960,
    minHeight: 640,
    title: 'LianYu - 恋语',
    icon: resolveDistPath('logo.png'),
    backgroundColor: resolveWindowBackgroundColor(appearanceMode),
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
    applyTitleBarOverlayToWindow(win, currentTitleBarPreset)
    pushCaptionMetrics(win)
    void syncChromeFromRenderer(win)
    win.show()
  })

  win.on('minimize', () => {
    showLauncherWindow({ center: true, force: true })
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

  loadRoute(win, '#/')
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
  win.setIgnoreMouseEvents(true, { forward: true })

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
    if (moveTimer) clearTimeout(moveTimer)
    if (launcherIsDragging) return
    if (launcherSuppressMoveSave) return
    moveTimer = setTimeout(() => {
      const bounds = win.getBounds()
      if (launcherPickerOpen) {
        const petX = bounds.x + Math.round((bounds.width - LAUNCHER_WINDOW.width) / 2)
        const petY = bounds.y + bounds.height - LAUNCHER_WINDOW.height
        writeLauncherPosition(petX, petY)
      } else {
        writeLauncherPosition(bounds.x, bounds.y)
      }
      clampLauncherToWorkArea()
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

function expandLauncherForPicker() {
  if (!launcherWindow || launcherWindow.isDestroyed()) return
  const bounds = launcherWindow.getBounds()

  if (!launcherBoundsBeforePicker) {
    if (bounds.width <= LAUNCHER_WINDOW.width + 8 && bounds.height <= LAUNCHER_WINDOW.height + 8) {
      launcherBoundsBeforePicker = { ...bounds }
    } else {
      launcherBoundsBeforePicker = {
        x: bounds.x + Math.round((bounds.width - LAUNCHER_WINDOW.width) / 2),
        y: bounds.y + bounds.height - LAUNCHER_WINDOW.height,
        width: LAUNCHER_WINDOW.width,
        height: LAUNCHER_WINDOW.height,
      }
    }
  }

  const pet = launcherBoundsBeforePicker
  const display = screen.getDisplayNearestPoint({
    x: pet.x + pet.width / 2,
    y: pet.y + pet.height / 2,
  })
  const area = display.workArea
  const width = PICKER_PANEL_WIDTH
  const height = PICKER_PANEL_HEIGHT + LAUNCHER_WINDOW.height + PICKER_PET_GAP

  let x = pet.x + Math.round((pet.width - width) / 2)
  let y = pet.y - PICKER_PANEL_HEIGHT - PICKER_PET_GAP
  if (y < area.y) {
    y = pet.y + pet.height + PICKER_PET_GAP
  }
  if (x + width > area.x + area.width) {
    x = area.x + area.width - width
  }
  if (x < area.x) x = area.x
  if (y + height > area.y + area.height) {
    y = area.y + area.height - height
  }
  if (y < area.y) y = area.y

  launcherWindow.setBounds({
    x: Math.round(x),
    y: Math.round(y),
    width,
    height,
  }, false)
}

function shrinkLauncherAfterPicker() {
  if (!launcherWindow || launcherWindow.isDestroyed()) return
  const saved = launcherBoundsBeforePicker
  if (saved) {
    launcherWindow.setBounds({
      x: saved.x,
      y: saved.y,
      width: LAUNCHER_WINDOW.width,
      height: LAUNCHER_WINDOW.height,
    }, false)
  } else {
    clampLauncherToWorkArea()
  }
  launcherBoundsBeforePicker = null
}

function openCharacterPicker() {
  if (!launcherLoggedIn) {
    refreshLauncherLoginState()
    void pullRendererDesktopState(mainWindow).then((state) => {
      if (state?.loggedIn) launcherLoggedIn = true
    })
  }
  ensureLauncherWindow()
  if (!launcherWindow || launcherWindow.isDestroyed()) return

  if (launcherPickerOpen) {
    closeCharacterPicker()
    return
  }

  launcherPickerOpen = true
  applyLauncherMouseMode()
  expandLauncherForPicker()

  if (!launcherWindow.isVisible()) {
    launcherWindow.show()
  }
  launcherWindow.focus()
  launcherWindow.moveTop()
  launcherWindow.webContents.send('desktop:picker-toggle', { open: true })
}

function toggleCharacterPicker() {
  if (launcherPickerOpen) {
    closeCharacterPicker()
    return
  }
  openCharacterPicker()
}

function closeCharacterPicker() {
  if (!launcherPickerOpen && !(pickerWindow && !pickerWindow.isDestroyed() && pickerWindow.isVisible())) {
    return
  }

  launcherPickerOpen = false
  if (launcherWindow && !launcherWindow.isDestroyed()) {
    launcherWindow.webContents.send('desktop:picker-toggle', { open: false })
    shrinkLauncherAfterPicker()
    resetLauncherInteraction()
  }

  if (pickerWindow && !pickerWindow.isDestroyed()) {
    pickerWindow.hide()
  }
  restoreLauncherAfterPicker()
}

function positionQuickChatNearLauncher(win) {
  if (!win || win.isDestroyed()) return
  if (!launcherWindow || launcherWindow.isDestroyed()) return
  const anchor = launcherWindow
  const anchorBounds = anchor.getBounds()
  const winBounds = win.getBounds()
  const display = screen.getDisplayNearestPoint({
    x: anchorBounds.x + anchorBounds.width / 2,
    y: anchorBounds.y + anchorBounds.height / 2,
  })
  const area = display.workArea
  const gap = 12
  let x = anchorBounds.x + anchorBounds.width + gap
  let y = anchorBounds.y + Math.round((anchorBounds.height - winBounds.height) / 2)
  if (x + winBounds.width > area.x + area.width) {
    x = anchorBounds.x - winBounds.width - gap
  }
  if (x < area.x) x = area.x
  if (y < area.y) y = area.y
  if (y + winBounds.height > area.y + area.height) {
    y = area.y + area.height - winBounds.height
  }
  win.setPosition(Math.round(x), Math.round(y))
}

function clearQuickChatReadyFallback() {
  if (quickChatReadyFallbackTimer) {
    clearTimeout(quickChatReadyFallbackTimer)
    quickChatReadyFallbackTimer = null
  }
}

function revealPendingQuickChat(win) {
  if (!win || win.isDestroyed() || !quickChatPendingShowId) return
  const id = quickChatPendingShowId
  quickChatPendingShowId = null
  clearQuickChatReadyFallback()
  showQuickChatWindow(win, id)
}

function scheduleQuickChatReadyFallback(win) {
  clearQuickChatReadyFallback()
  quickChatReadyFallbackTimer = setTimeout(() => {
    quickChatReadyFallbackTimer = null
    revealPendingQuickChat(win)
  }, 2500)
}

async function navigateQuickChatShell(conversationId) {
  const id = String(conversationId)
  const win = quickChatShell
  if (!win || win.isDestroyed() || win.webContents.isDestroyed()) return false
  const target = `/quick/chat/${id}`
  try {
    await win.webContents.executeJavaScript(
      `(function () {
        const target = ${JSON.stringify(target)}
        if (typeof window.__lianyuNavigateQuickChat === 'function') {
          return window.__lianyuNavigateQuickChat(target)
        }
        window.location.hash = '#' + target
        return true
      })()`,
      true,
    )
    return true
  } catch (e) {
    log(`navigateQuickChatShell failed conversation=${id}: ${e.message}`)
    loadRoute(win, `#${target}`)
    return true
  }
}

function createQuickChatShell() {
  if (quickChatShell && !quickChatShell.isDestroyed()) {
    return quickChatShell
  }

  quickChatShellReady = false
  const appearance = readAppearance()
  const win = new BrowserWindow({
    width: 380,
    height: 560,
    minWidth: 320,
    minHeight: 420,
    title: 'LianYu 聊天',
    icon: resolveDistPath('logo.png'),
    backgroundColor: resolveWindowBackgroundColor(appearance),
    ...buildQuickChatWindowOptions(),
    show: false,
    webPreferences: { ...SHARED_WEB_PREFS },
  })
  win.lianyuKind = 'quickChat'
  win.setMenuBarVisibility(false)
  attachWindowLogging(win, 'quickChat')

  win.webContents.once('did-finish-load', () => {
    quickChatShellReady = true
    if (quickChatPendingShowId) {
      void navigateQuickChatShell(quickChatPendingShowId)
    }
  })

  win.webContents.once('did-fail-load', () => {
    log('createQuickChatShell: load failed')
    if (!win.isDestroyed()) win.destroy()
    if (quickChatShell === win) quickChatShell = null
    quickChatShellReady = false
    quickChatPendingShowId = null
    clearQuickChatReadyFallback()
  })

  win.on('closed', () => {
    if (quickChatShell === win) quickChatShell = null
    quickChatShellReady = false
    quickChatPendingShowId = null
    clearQuickChatReadyFallback()
    resetLauncherInteraction()
  })

  loadRoute(win, '#/quick/chat/0')
  quickChatShell = win
  return win
}

function prewarmQuickChatShell() {
  if (!launcherLoggedIn) return
  createQuickChatShell()
}

function showQuickChatWindow(win, conversationId) {
  if (!win || win.isDestroyed()) return false
  const appearance = readAppearance()
  win.setBackgroundColor(resolveWindowBackgroundColor(appearance))
  positionQuickChatNearLauncher(win)
  if (!win.isVisible()) win.show()
  win.focus()
  win.moveTop()
  log(`openQuickChatWindow: shown conversation=${conversationId}`)
  return true
}

function openQuickChatWindow(conversationId) {
  const id = String(conversationId)
  if (!id || id === 'undefined' || id === 'null') {
    log('openQuickChatWindow: invalid conversation id')
    return null
  }
  closeCharacterPicker()

  const win = createQuickChatShell()
  quickChatPendingShowId = id

  const beginOpen = async () => {
    await navigateQuickChatShell(id)
    if (win.isDestroyed()) return
    if (win.isVisible()) {
      revealPendingQuickChat(win)
      return
    }
    scheduleQuickChatReadyFallback(win)
  }

  if (quickChatShellReady && !win.webContents.isLoading()) {
    void beginOpen()
  } else {
    win.webContents.once('did-finish-load', () => { void beginOpen() })
  }
  return win
}

function hideQuickChatShell() {
  if (!quickChatShell || quickChatShell.isDestroyed()) return false
  quickChatPendingShowId = null
  clearQuickChatReadyFallback()
  quickChatShell.hide()
  return true
}

function destroyQuickChatWindow(win, reason = 'unknown') {
  if (!win || win.isDestroyed()) return false
  if (win === quickChatShell) {
    log(`hideQuickChatShell: reason=${reason}`)
    return hideQuickChatShell()
  }
  log(`destroyQuickChatWindow: reason=${reason} kind=${win.lianyuKind || 'unknown'}`)
  win.destroy()
  return true
}

function closeQuickChatFromEvent(event) {
  if (!guardTrusted(event)) {
    log('closeQuickChatFromEvent: untrusted sender')
    return false
  }
  const win = BrowserWindow.fromWebContents(event.sender)
  if (win && win.lianyuKind === 'quickChat') {
    return destroyQuickChatWindow(win, 'sender-window')
  }
  for (const chatWin of [quickChatShell]) {
    if (chatWin && !chatWin.isDestroyed() && chatWin.webContents.id === event.sender.id) {
      return hideQuickChatShell()
    }
  }
  const focused = BrowserWindow.getFocusedWindow()
  if (focused && focused.lianyuKind === 'quickChat') {
    return destroyQuickChatWindow(focused, 'focused-window')
  }
  log(`closeQuickChatFromEvent: no quick chat window for sender=${event.sender.id}`)
  return false
}

function closeFocusedQuickChat() {
  const focused = BrowserWindow.getFocusedWindow()
  if (focused && focused.lianyuKind === 'quickChat') {
    return destroyQuickChatWindow(focused, 'focused-close')
  }
  return false
}

function quitApplication() {
  isQuitting = true
  stopQqBridge()
  stopNapCatHost()
  if (qqLoginWindow && !qqLoginWindow.isDestroyed()) {
    qqLoginWindow.destroy()
  }
  qqLoginWindow = null
  closeCharacterPicker()
  if (quickChatShell && !quickChatShell.isDestroyed()) quickChatShell.destroy()
  quickChatShell = null
  if (launcherWindow && !launcherWindow.isDestroyed()) launcherWindow.destroy()
  if (mainWindow && !mainWindow.isDestroyed()) mainWindow.destroy()
  tray?.destroy()
  tray = null
  app.quit()
}

/** QQ 桥接状态推送到主窗口/启动器窗口，供渲染进程展示连接态/二维码（后续阶段） */
function pushQqBridgeStatus(status) {
  const payload = { ...status, ts: Date.now() }
  for (const win of [mainWindow, launcherWindow]) {
    if (win && !win.isDestroyed()) {
      try {
        win.webContents.send('desktop:qq-bridge-status', payload)
      } catch {
        /* 窗口可能已销毁 */
      }
    }
  }
}

/** 启动时若已开启 QQ 桥接且已登录，自动拉起（默认自动；用户也可在设置中手动开关） */
async function autoStartQqBridgeIfNeeded() {
  try {
    const settings = readQqBridgeSettings()
    if (settings.hosting?.mode === 'auto') return // 自管模式下桥接由 napcatHost 在就绪后拉起
    if (!settings.enabled || !settings.binding?.conversationId) return
    const authToken = await resolveDesktopAuthToken()
    if (!authToken) {
      log('[qqBridge] auto-start skipped: not logged in yet')
      return
    }
    startQqBridge({
      apiOrigin: resolveApiOrigin(),
      authToken,
      settings,
      onStatus: (status) => pushQqBridgeStatus(status),
    })
    log('[qqBridge] auto-started ->', settings.napcat?.wsUrl)
  } catch (e) {
    console.warn('[qqBridge] auto-start failed:', e?.message || e)
  }
}

/** NapCat 自管托管状态推送到主窗口/启动器窗口，供渲染进程展示下载进度/连接态/扫码入口 */
function pushQqHostStatus(status) {
  const payload = { ...status, ts: Date.now() }
  for (const win of [mainWindow, launcherWindow]) {
    if (win && !win.isDestroyed()) {
      try {
        win.webContents.send('desktop:qq-host-status', payload)
      } catch {
        /* 窗口可能已销毁 */
      }
    }
  }
}

/** 下载/解压进度推送（phase: downloading/extracting/done） */
function pushQqHostDownload(progress) {
  const payload = { ...progress, ts: Date.now() }
  for (const win of [mainWindow, launcherWindow]) {
    if (win && !win.isDestroyed()) {
      try {
        win.webContents.send('desktop:qq-host-download', payload)
      } catch {
        /* 窗口可能已销毁 */
      }
    }
  }
}

/**
 * 自动绑定桥接会话——全自动链路的最后一步：conversationId 为空时无需用户手动粘贴 UUID。
 * 先复用最近的单聊会话（私聊机器人路由到 SINGLE 会话最贴切）；若无则用首个可用人设新建一个。
 * 返回 { conversationId } 或 null（无可用会话/人设——用户须先在 App 内建一个人设）。
 * 复用主进程 performApiRequest：走 net.request + 证书 pin + lianyu-token 注入 + SSRF 校验，
 * 与渲染端 axios 同构地解包 Result<T> 信封（code===200 取 data）。
 */
async function ensureBridgeBinding({ apiOrigin, authToken, characterId }) {
  const unwrap = (res, path) => {
    if (!res || res.status < 200 || res.status >= 300) throw new Error(`api:${path} HTTP ${res?.status}`)
    let body
    try { body = JSON.parse(res.data || '{}') } catch { throw new Error(`api:${path} non-json`) }
    if (typeof body.code === 'number') {
      if (body.code !== 200) throw new Error(`api:${path} code ${body.code}`)
      return body.data
    }
    return body
  }
  const apiGet = async (path) => {
    const res = await performApiRequest({ method: 'GET', url: `${apiOrigin}${path}`, timeoutMs: 15000, apiOrigin, authToken })
    return unwrap(res, path)
  }
  const apiPost = async (path, payload) => {
    const res = await performApiRequest({
      method: 'POST',
      url: `${apiOrigin}${path}`,
      headers: { 'Content-Type': 'application/json' },
      body: payload,
      timeoutMs: 30000,
      apiOrigin,
      authToken,
    })
    return unwrap(res, path)
  }

  // 1. 复用 SINGLE 会话：指定 characterId 时只认该角色的 SINGLE 会话；否则取首个 SINGLE（或首项）
  try {
    const list = await apiGet('/api/conversation')
    if (Array.isArray(list) && list.length) {
      const single = characterId
        ? list.find((c) => c?.mode === 'SINGLE' && String(c?.characterId) === String(characterId))
        : (list.find((c) => c?.mode === 'SINGLE') || list[0])
      if (single?.id) {
        log(`[ensureBridgeBinding] reuse conversation ${single.id}` + (characterId ? ` (character ${characterId})` : ''))
        return {
          conversationId: String(single.id),
          characterId: characterId ? String(characterId) : (single.characterId ? String(single.characterId) : ''),
        }
      }
    }
  } catch (e) {
    log(`[ensureBridgeBinding] list conversations failed: ${e?.message || e}`)
  }

  // 2. 无可复用会话 → 新建 SINGLE：指定 characterId 时用它（须在用户角色列表内），否则用首个可用角色
  try {
    const chars = await apiGet('/api/character')
    const pick = characterId
      ? (Array.isArray(chars) ? chars.find((c) => String(c?.id) === String(characterId)) : null)
      : (Array.isArray(chars) && chars.length ? chars[0] : null)
    if (!pick?.id) {
      log(`[ensureBridgeBinding] no character available${characterId ? ` for id=${characterId}` : ''}; skip auto-bind`)
      return null
    }
    const created = await apiPost('/api/conversation', { characterId: String(pick.id), mode: 'SINGLE' })
    if (!created?.id) {
      log('[ensureBridgeBinding] create conversation returned no id')
      return null
    }
    log(`[ensureBridgeBinding] created conversation ${created.id} (character ${pick.id})`)
    return { conversationId: String(created.id), characterId: String(pick.id) }
  } catch (e) {
    log(`[ensureBridgeBinding] auto-create conversation failed: ${e?.message || e}`)
  }
  return null
}

/**
 * napcatHost 就绪后拉起 qqBridge 的闭包：在此懒解析 LianYu 登录态与 apiOrigin，
 * 用 host 写入的 wsUrl/accessToken 覆盖设置后 startQqBridge。
 * 桥接失败不致命——NapCat 进程仍在，用户可在设置页重试。
 */
function makeNapCatBridgeStarter() {
  return async ({ wsUrl, accessToken }) => {
    try {
      const authToken = await resolveDesktopAuthToken()
      if (!authToken) {
        log('[napcatHost] bridge starter: 未登录，暂不拉起桥接')
        return
      }
      let settings = readQqBridgeSettings()
      // 已绑定角色 id 时跳过 ensureBridgeBinding：桥接按角色做 per-user 懒建会话
      // （resolveConversationId 为每个 QQ 用户独立建该角色的 SINGLE 会话），无需共享会话号；
      // 也不受清空上下文影响（会话被删后 404 自动 re-resolve）。仅当角色/会话号都没配时才兜底自动绑定。
      if (!settings.binding?.conversationId && !settings.binding?.characterId) {
        log('[napcatHost] bridge starter: 未配置 characterId/conversationId，自动绑定中…')
        const result = await ensureBridgeBinding({ apiOrigin: resolveApiOrigin(), authToken, characterId: settings.binding?.characterId })
        if (!result?.conversationId) {
          log('[napcatHost] bridge starter: 自动绑定失败（无可用会话/人设），暂不拉起桥接')
          return
        }
        const prevBinding = settings.binding || {}
        const hasAllowEntries = (prevBinding.allowUsers || []).length > 0 || (prevBinding.allowGroups || []).length > 0
        // 用户选择 open：自动绑定时默认开放（任何 QQ 用户/群均可驱动机器人，配额风险用户已知晓）。
        // 但若用户已显式配置白名单条目，保留其原 allowMode，不擅自放宽。
        writeQqBridgeSettings({
          binding: {
            ...prevBinding,
            conversationId: result.conversationId,
            ...(hasAllowEntries ? {} : { allowMode: 'open' }),
          },
        })
        settings = readQqBridgeSettings()
        log(`[napcatHost] bridge starter: 已自动绑定 conversationId=${result.conversationId}`)
      }
      startQqBridge({
        apiOrigin: resolveApiOrigin(),
        authToken,
        settings: {
          ...settings,
          enabled: true,
          napcat: { ...settings.napcat, wsUrl, accessToken },
        },
        onStatus: (status) => {
          // 仅推送桥接状态；不再写回 hosting.qqUserId——该字段原意是 quick-login
          // 参数，但 resolveLaunchTarget 未实际使用（死代码），残留旧号反而会让下次
          // 启动误判已登录。停止托管时已统一清空 qqUserId + bot profile 登录态。
          pushQqBridgeStatus(status)
        },
      })
      log('[napcatHost] bridge auto-started ->', wsUrl)
    } catch (e) {
      console.warn('[napcatHost] bridge starter failed:', e?.message || e)
    }
  }
}

/** 启动时若开启自管托管且已同意，自动拉起 NapCat 运行时（登录态由 bridgeStarter 懒解析） */
async function autoStartNapCatHostIfNeeded() {
  try {
    const settings = readQqBridgeSettings()
    if (settings.hosting?.mode !== 'auto' || !settings.hosting?.consented) return
    await startNapCatHost({
      settings,
      onStatus: (status) => pushQqHostStatus(status),
      onDownload: (progress) => pushQqHostDownload(progress),
      bridgeStarter: makeNapCatBridgeStarter(),
    })
    log('[napcatHost] auto-started')
  } catch (e) {
    console.warn('[napcatHost] auto-start failed:', e?.message || e)
  }
}

/** 判定 URL 是否为本地 NapCat WebUI 同源（127.0.0.1/localhost + 指定端口） */
function isLocalNapCatUrl(url, port) {
  try {
    const u = new URL(url)
    return (u.hostname === '127.0.0.1' || u.hostname === 'localhost') && u.port === String(port)
  } catch {
    return false
  }
}

/**
 * 打开 NapCat WebUI 扫码登录窗：独立会话分区装
 * http://127.0.0.1:<port>/webui?token=<token>。仅允许 127.0.0.1 同源导航/弹窗，
 * 外部链接交系统浏览器、其余拒绝。单例复用，重开则聚焦并重载（令牌可能已变）。
 * 同意门 + 运行态双校验：未同意或 host 未就绪则拒绝，避免空窗。
 */
function openQqLoginWindow() {
  const settings = readQqBridgeSettings()
  if (!settings.hosting?.consented) {
    log('openQqLoginWindow: not consented')
    return { ok: false, reason: 'not_consented' }
  }
  const status = getNapCatHostStatus()
  const webui = status?.webui
  const port = webui?.port || settings.hosting?.webuiPort || 6099
  const token = webui?.token || settings.hosting?.webuiToken || ''
  if (!token) {
    log('openQqLoginWindow: no webui token (host not ready)')
    return { ok: false, reason: 'not_running' }
  }
  const url = webui?.url || `http://127.0.0.1:${port}/webui?token=${token}`

  if (qqLoginWindow && !qqLoginWindow.isDestroyed()) {
    qqLoginWindow.show()
    qqLoginWindow.focus()
    try {
      qqLoginWindow.loadURL(url)
    } catch {
      /* ignore */
    }
    return { ok: true, reused: true }
  }

  const win = new BrowserWindow({
    width: 520,
    height: 720,
    minWidth: 360,
    minHeight: 480,
    title: 'QQ 登录 · NapCat',
    icon: resolveDistPath('logo.png'),
    backgroundColor: '#ffffff',
    autoHideMenuBar: true,
    show: false,
    webPreferences: {
      contextIsolation: true,
      sandbox: true,
      nodeIntegration: false,
      partition: 'persist:napcat-webui',
    },
  })
  qqLoginWindow = win
  win.setMenuBarVisibility(false)
  win.once('ready-to-show', () => {
    if (!win.isDestroyed()) win.show()
  })
  win.webContents.on('did-fail-load', (_e, errorCode, errorDesc) => {
    log(`openQqLoginWindow: load failed ${errorCode} ${errorDesc}`)
  })
  // 仅允许 127.0.0.1 同源导航；阻止任何外部跳转/重定向
  win.webContents.on('will-navigate', (e, navUrl) => {
    if (!isLocalNapCatUrl(navUrl, port)) e.preventDefault()
  })
  win.webContents.setWindowOpenHandler(({ url: openUrl }) => {
    if (isLocalNapCatUrl(openUrl, port)) return { action: 'allow' }
    if (isAllowedExternalUrl(openUrl)) shell.openExternal(openUrl)
    return { action: 'deny' }
  })
  win.on('closed', () => {
    qqLoginWindow = null
  })
  win.loadURL(url)
  return { ok: true }
}

function registerIpcHandlers() {
  ipcMain.on('desktop:sync-chrome', (event) => {
    if (!trustedWebContentsIds.has(event.sender.id)) return
    const win = BrowserWindow.fromWebContents(event.sender)
    if (!win) return
    void (async () => {
      if (win.lianyuKind === 'launcher' && mainWindow && !mainWindow.isDestroyed()) {
        await syncChromeFromRenderer(mainWindow)
        await reconcileScreenObserveSetting(mainWindow)
        await ensureAuthSessionFromRenderer(mainWindow)
      } else {
        await syncChromeFromRenderer(win)
      }
    })()
  })

  ipcMain.handle('desktop:get-window-kind', (event) => {
    if (!guardTrusted(event)) return 'unknown'
    const win = BrowserWindow.fromWebContents(event.sender)
    return win?.lianyuKind || 'unknown'
  })

  ipcMain.handle('desktop:open-main', (event, hash) => {
    if (!guardTrusted(event)) return { ok: false, reason: 'untrusted_sender' }
    showMainWindow(hash || '#/app')
    return { ok: true }
  })

  ipcMain.handle('desktop:open-quick-chat', (event, conversationId) => {
    if (!guardTrusted(event)) {
      log('open-quick-chat: untrusted sender')
      return { ok: false, reason: 'untrusted_sender' }
    }
    const win = openQuickChatWindow(conversationId)
    return win ? { ok: true } : { ok: false, reason: 'invalid_conversation' }
  })

  ipcMain.on('desktop:open-quick-chat', (event, conversationId) => {
    if (!trustedWebContentsIds.has(event.sender.id)) {
      log('open-quick-chat(send): untrusted sender')
      return
    }
    openQuickChatWindow(conversationId)
  })

  ipcMain.on('desktop:quick-chat-ready', (event) => {
    if (!trustedWebContentsIds.has(event.sender.id)) return
    const win = BrowserWindow.fromWebContents(event.sender)
    if (!win || win.lianyuKind !== 'quickChat') return
    revealPendingQuickChat(win)
  })

  ipcMain.handle('desktop:toggle-picker', (event) => {
    if (!guardTrusted(event)) return { ok: false, reason: 'untrusted_sender' }
    toggleCharacterPicker()
    return { ok: true }
  })

  ipcMain.handle('desktop:picker-interaction', (event) => {
    if (!guardTrusted(event)) return { ok: false, reason: 'untrusted_sender' }
    return { ok: true }
  })

  ipcMain.handle('desktop:close-picker', (event) => {
    if (!guardTrusted(event)) return { ok: false, reason: 'untrusted_sender' }
    closeCharacterPicker()
    return { ok: true }
  })

  ipcMain.handle('desktop:close-quick-chat', (event) => {
    if (!guardTrusted(event)) return { ok: false, reason: 'untrusted_sender' }
    return { ok: closeQuickChatFromEvent(event) }
  })

  ipcMain.on('desktop:close-quick-chat', (event) => {
    if (!trustedWebContentsIds.has(event.sender.id)) return
    closeQuickChatFromEvent(event)
  })

  ipcMain.handle('desktop:notify-proactive-message', (event, payload) => {
    if (!guardTrusted(event)) return { ok: false, reason: 'untrusted_sender' }
    handleProactiveMessageNotification(payload || {})
    return { ok: true }
  })

  ipcMain.handle('desktop:get-caption-height', (event) => {
    if (!guardTrusted(event)) return 0
    return CAPTION_BAR_HEIGHT
  })

  ipcMain.handle('desktop:get-caption-metrics', (event) => {
    if (!guardTrusted(event)) return null
    const win = BrowserWindow.fromWebContents(event.sender)
    return resolveCaptionMetrics(win)
  })

  ipcMain.handle('desktop:set-title-bar-appearance', (event, payload = {}) => {
    if (!guardTrusted(event)) {
      void syncChromeFromRenderer(mainWindow)
      return { ok: false, reason: 'untrusted_sender' }
    }
    const presetKey = resolveTitleBarPresetKey(payload)
    applyTitleBarOverlayToAllWindows(presetKey)
    return { ok: true, preset: presetKey }
  })

  ipcMain.handle('desktop:save-appearance', (event, mode) => {
    if (!guardTrusted(event)) {
      void syncChromeFromRenderer(mainWindow)
      return { ok: false, reason: 'untrusted_sender' }
    }
    const normalized = writeAppearance(mode)
    const presetKey = normalized === 'light' ? 'light' : 'dark'
    applyTitleBarOverlayToAllWindows(presetKey)
    return { ok: true, mode: normalized }
  })

  ipcMain.handle('desktop:set-login-state', (event, loggedIn) => {
    if (!guardTrusted(event)) return { ok: false, reason: 'untrusted_sender' }
    launcherLoggedIn = !!loggedIn
    if (launcherLoggedIn) {
      prewarmLauncherWindow()
      prewarmQuickChatShell()
    } else {
      hideLauncherWindow()
      closeCharacterPicker()
    }
    return { ok: true }
  })

  ipcMain.handle('desktop:hide-launcher', (event) => {
    if (!guardTrusted(event)) return { ok: false, reason: 'untrusted_sender' }
    hideLauncherWindow()
    return { ok: true }
  })

  ipcMain.handle('desktop:show-launcher', (event) => {
    if (!guardTrusted(event)) return { ok: false, reason: 'untrusted_sender' }
    showLauncherWindow()
    return { ok: true }
  })

  ipcMain.handle('desktop:quit', (event) => {
    if (!guardTrusted(event)) return { ok: false, reason: 'untrusted_sender' }
    quitApplication()
    return { ok: true }
  })

  ipcMain.handle('desktop:set-launcher-mouse-passthrough', (event, ignore) => {
    if (!guardTrusted(event)) return { ok: false, reason: 'untrusted_sender' }
    setLauncherMousePassthrough(!!ignore)
    return { ok: true }
  })

  ipcMain.handle('runtime:get-config', (event) => {
    if (!guardTrusted(event)) return null
    const origin = resolveApiOrigin()
    try {
      const url = new URL(origin)
      const wsProtocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
      return { apiOrigin: origin, wsUrl: `${wsProtocol}//${url.host}/ws` }
    } catch {
      return { apiOrigin: origin, wsUrl: 'ws://localhost:8080/ws' }
    }
  })

  ipcMain.handle('api:request', async (event, payload) => {
    if (!guardTrusted(event)) {
      return { ok: false, status: 0, statusText: '', headers: {}, data: '' }
    }
    const url = payload?.url || ''
    const apiOrigin = resolveApiOrigin()
    // #6：出口仅限 API origin（host:port 精确匹配），阻断 SSRF / CSP 绕过 / pin 绕过
    if (!isAllowedEgressUrl(url, apiOrigin)) {
      log(`api:request egress blocked url=${url} apiOrigin=${apiOrigin}`)
      return { ok: false, status: 0, statusText: 'egress_blocked', headers: {}, data: '' }
    }
    // #6：出口限流（令牌桶），兜住 XSS 驱动的失控外联循环
    if (!egressLimiter.tryAcquire()) {
      log(`api:request rate-limited url=${url}`)
      return { ok: false, status: 429, statusText: 'rate_limited', headers: {}, data: '' }
    }
    try {
      const res = await performApiRequest({
        method: payload?.method || 'GET',
        url,
        headers: payload?.headers || {},
        body: payload?.body,
        timeoutMs: payload?.timeout || 60000,
        apiOrigin,
        authToken: readAuthSession()?.token || '',
      })
      let host = ''
      try { host = new URL(url).host } catch { /* ignore */ }
      log(`api:request ${(payload?.method || 'GET').toUpperCase()} ${host} -> ${res.status} ${res.data?.length || 0}b`)
      return {
        ok: true,
        status: res.status,
        statusText: res.statusText,
        headers: res.headers,
        data: res.data,
      }
    } catch (err) {
      if (err?.code === 'EGRESS_BLOCKED') {
        log(`api:request egress blocked url=${url}`)
        return { ok: false, status: 0, statusText: 'egress_blocked', headers: {}, data: '' }
      }
      log(`api:request failed url=${url} err=${err?.message || err}`)
      return { ok: false, status: 0, statusText: '', headers: {}, data: '' }
    }
  })

  ipcMain.handle('auth:get-session', (event) => {
    if (!guardTrusted(event)) return { ok: false, reason: 'untrusted_sender' }
    // #6：不回传明文 token——鉴权头由主进程在 api:request 内统一注入。
    // #14：渲染层直连（SSE/STOMP 等）所需 token 由 auth:bootstrap-token 一次性注入内存态；
    //      本接口持续不回明文，避免渲染层多处持有。STOMP WS 帧鉴权需明文，故无法彻底零接触（见 secureToken.js 注释）。
    const session = readAuthSession()
    if (!session) return { hasToken: false }
    const safe = { ...session }
    delete safe.token
    return { ...safe, hasToken: !!session.token }
  })
  // #14：重载后渲染层内存 token 丢失，启动期一次性回传明文 token 供 secureToken 内存态恢复。
  //      仅 guardTrusted 的渲染进程可调；STOMP WS 帧鉴权需明文，主进程 webRequest 无法注入 WS 帧，故保留此通道。
  ipcMain.handle('auth:bootstrap-token', (event) => {
    if (!guardTrusted(event)) return ''
    return readAuthSession()?.token || ''
  })
  ipcMain.handle('auth:set-session', (event, session) => {
    if (!guardTrusted(event)) return { ok: false, reason: 'untrusted_sender' }
    const saved = writeAuthSession(session)
    if (session?.token && !saved) {
      return { ok: false, reason: 'session_write_failed' }
    }
    if (session?.token) {
      launcherLoggedIn = true
      prewarmLauncherWindow()
      prewarmQuickChatShell()
    }
    return { ok: true }
  })
  ipcMain.handle('auth:clear-session', (event) => {
    if (!guardTrusted(event)) return { ok: false, reason: 'untrusted_sender' }
    clearAuthSession()
    return { ok: true }
  })

  ipcMain.handle('desktop:get-settings', (event) => {
    if (!guardTrusted(event)) return null
    return readDesktopSettings()
  })

  ipcMain.handle('desktop:set-settings', (event, partial) => {
    if (!guardTrusted(event)) {
      void syncChromeFromRenderer(mainWindow)
      return { ok: false, reason: 'untrusted_sender' }
    }
    const next = writeDesktopSettings(partial || {})
    if (!isDesktopPetEnabled(next) && launcherWindow && !launcherWindow.isDestroyed()) {
      launcherWindow.close()
    } else if (isDesktopPetEnabled(next) && mainWindow && !mainWindow.isVisible()) {
      showLauncherWindow()
    }
    if (partial?.launcherPetId != null && launcherWindow && !launcherWindow.isDestroyed()) {
      resetLauncherCompactSize()
      clampLauncherToWorkArea()
      launcherWindow.webContents.send('desktop:launcher-pet-changed', next.launcherPetId)
    }
    if (
      partial?.allowScreenObserve === true
      && launcherWindow
      && !launcherWindow.isDestroyed()
      && isDesktopPetActivelyVisible()
    ) {
      launcherWindow.webContents.send('desktop:restart-observer')
    }
    if (tray) {
      tray.setContextMenu(buildTrayMenu())
    }
    return { ok: true, settings: next }
  })

  ipcMain.handle('desktop:ack-close-hint', (event) => {
    if (!guardTrusted(event)) return { ok: false, reason: 'untrusted_sender' }
    writeDesktopSettings({ closeHintShown: true })
    pendingHideAfterHint = false
    return { ok: true }
  })

  ipcMain.handle('desktop:is-launcher-visible', (event) => {
    if (!guardTrusted(event)) return false
    return isDesktopPetActivelyVisible()
  })

  ipcMain.handle('desktop:start-observer', async (event, { persona, petId }) => {
    if (!guardTrusted(event)) return { ok: false, reason: 'untrusted_sender' }
    if (!isDesktopPetActivelyVisible()) {
      return { ok: false, reason: 'launcher_not_visible' }
    }
    if (mainWindow && !mainWindow.isDestroyed()) {
      await reconcileScreenObserveSetting(mainWindow)
    }
    const settings = readDesktopSettings()
    if (!settings.allowScreenObserve) {
      return { ok: false, reason: 'screen_observe_disabled' }
    }
    const authToken = await resolveDesktopAuthToken()
    if (!authToken) {
      log('start-observer: no auth token in main process')
      return { ok: false, reason: 'not_logged_in' }
    }
    const resolvedPetId = petId || settings.launcherPetId || 'raiden'
    const started = startDesktopObserver({
      apiOrigin: resolveApiOrigin(),
      authToken,
      persona,
      petId: resolvedPetId,
      onGreeting: (payload) => {
        if (!isDesktopPetActivelyVisible()) return
        const forward = prepareGreetingPayload(payload)
        if (payload?.audioBase64 && !forward.audioUrl) {
          console.warn('[desktopObserver] failed to materialize greeting audio file')
        } else if (forward.audioUrl) {
          console.log('[desktopObserver] greeting audio ready:', forward.audioUrl.slice(0, 80))
        }
        if (launcherWindow && !launcherWindow.isDestroyed()) {
          launcherWindow.webContents.setAudioMuted(false)
          launcherWindow.webContents.send('desktop:launcher-greeting', forward)
        }
      },
      // #10：抓取期间向桌宠发送不可隐藏的捕获指示
      onCaptureStart: () => {
        if (launcherWindow && !launcherWindow.isDestroyed()) {
          launcherWindow.webContents.send('desktop:observe-capturing', true)
        }
      },
      onCaptureEnd: () => {
        if (launcherWindow && !launcherWindow.isDestroyed()) {
          launcherWindow.webContents.send('desktop:observe-capturing', false)
        }
      },
    })
    return started ? { ok: true } : { ok: false, reason: 'start_failed' }
  })

  ipcMain.handle('desktop:stop-observer', (event) => {
    if (!guardTrusted(event)) return { ok: false, reason: 'untrusted_sender' }
    stopDesktopObserver()
    return { ok: true }
  })

  ipcMain.handle('desktop:get-qq-bridge-settings', (event) => {
    if (!guardTrusted(event)) return null
    return readQqBridgeSettings()
  })

  ipcMain.handle('desktop:set-qq-bridge-settings', (event, partial) => {
    if (!guardTrusted(event)) return { ok: false, reason: 'untrusted_sender' }
    const next = writeQqBridgeSettings(partial || {})
    return { ok: true, settings: next }
  })

  ipcMain.handle('desktop:start-qq-bridge', async (event, override) => {
    if (!guardTrusted(event)) return { ok: false, reason: 'untrusted_sender' }
    const base = readQqBridgeSettings()
    const settings = override && typeof override === 'object' ? { ...base, ...override } : base
    if (!settings.enabled) return { ok: false, reason: 'disabled' }
    if (!settings.binding?.conversationId && !settings.binding?.characterId) return { ok: false, reason: 'no_conversation' }
    const authToken = await resolveDesktopAuthToken()
    if (!authToken) {
      log('start-qq-bridge: no auth token in main process')
      return { ok: false, reason: 'not_logged_in' }
    }
    const started = startQqBridge({
      apiOrigin: resolveApiOrigin(),
      authToken,
      settings,
      onStatus: (status) => pushQqBridgeStatus(status),
    })
    return started ? { ok: true } : { ok: false, reason: 'start_failed' }
  })

  ipcMain.handle('desktop:stop-qq-bridge', (event) => {
    if (!guardTrusted(event)) return { ok: false, reason: 'untrusted_sender' }
    stopQqBridge()
    return { ok: true }
  })

  ipcMain.handle('desktop:get-qq-bridge-status', (event) => {
    if (!guardTrusted(event)) return { state: 'unknown', selfId: '' }
    return getQqBridgeStatus()
  })

  // 按角色自动获取/绑定会话号：指定 characterId 时 find/create 该角色的 SINGLE 会话；
  // 不指定则按 ensureBridgeBinding 默认（首个 SINGLE/首个角色）。结果写回 binding 并返回。
  ipcMain.handle('desktop:qq-bridge-resolve-conversation', async (event, characterId) => {
    if (!guardTrusted(event)) return { ok: false, reason: 'untrusted_sender' }
    const authToken = await resolveDesktopAuthToken()
    if (!authToken) return { ok: false, reason: 'not_logged_in' }
    const result = await ensureBridgeBinding({
      apiOrigin: resolveApiOrigin(),
      authToken,
      characterId: characterId || undefined,
    })
    if (!result?.conversationId) return { ok: false, reason: 'resolve_failed' }
    const prev = readQqBridgeSettings()
    writeQqBridgeSettings({
      binding: {
        ...(prev.binding || {}),
        conversationId: result.conversationId,
        ...(result.characterId ? { characterId: result.characterId } : {}),
      },
    })
    log(`[resolve-conversation] bound conversation ${result.conversationId}` + (result.characterId ? ` (character ${result.characterId})` : ''))
    return { ok: true, ...result }
  })

  // 查看桥接日志：读 startup.log，过滤只留桥接相关行（qqBridge/napcatHost/resolve-conversation/
  // ensureBridgeBinding 标签），尾部返回 500 条。原尾 300 条被 setTitleBarOverlay/api:request
  // 等非桥接噪声淹没（872 行中噪声占 800+），真正的桥接诊断日志被挤出窗口——故先过滤再截尾。
  ipcMain.handle('desktop:qq-bridge-get-logs', (event) => {
    if (!guardTrusted(event)) return { ok: false, reason: 'untrusted_sender' }
    try {
      let content = ''
      try {
        content = fs.readFileSync(logPath(), 'utf-8')
      } catch (e) {
        if (e.code !== 'ENOENT') throw e // 无日志文件：返回空，非错误
      }
      const all = content ? content.split(/\r?\n/).filter(Boolean) : []
      const bridgeRe = /\] \[(qqBridge|napcatHost|resolve-conversation|ensureBridgeBinding)\]/
      const lines = all.filter((l) => bridgeRe.test(l))
      return { ok: true, lines: lines.slice(-500) }
    } catch (e) {
      return { ok: false, reason: 'read_failed', error: e?.message || String(e) }
    }
  })

  ipcMain.handle('desktop:start-qq-host', async (event, override) => {
    if (!guardTrusted(event)) return { ok: false, reason: 'untrusted_sender' }
    if (override && typeof override === 'object' && override.hosting) {
      writeQqBridgeSettings({ hosting: override.hosting })
    }
    const settings = readQqBridgeSettings()
    if (settings.hosting?.mode !== 'auto') return { ok: false, reason: 'not_auto_mode' }
    if (!settings.hosting?.consented) return { ok: false, reason: 'not_consented' }
    // QQNT 预检：未装则直接返回 qqnt_not_found，不进 startNapCatHost——否则会先白下
    // 28MB NapCat，再到 resolveLaunchTarget 抛「QQ install dir not found」被吞成
    // 误导性的 start_failed「请检查网络」。预检让用户拿到「请先安装 QQNT」的明确引导。
    if (!isQqntReady()) return { ok: false, reason: 'qqnt_not_found' }
    const started = await startNapCatHost({
      settings,
      onStatus: (status) => pushQqHostStatus(status),
      onDownload: (progress) => pushQqHostDownload(progress),
      bridgeStarter: makeNapCatBridgeStarter(),
    })
    return started ? { ok: true } : { ok: false, reason: 'start_failed' }
  })

  ipcMain.handle('desktop:stop-qq-host', async (event) => {
    if (!guardTrusted(event)) return { ok: false, reason: 'untrusted_sender' }
    await stopNapCatHost()
    return { ok: true }
  })

  ipcMain.handle('desktop:get-qq-host-status', (event) => {
    if (!guardTrusted(event)) return { state: 'stopped' }
    return getNapCatHostStatus()
  })

  // 升级/重装：停止 → 清空安装根与残件 → 用最新设置重新拉起（重下最新发行）
  ipcMain.handle('desktop:reinstall-qq-host', async (event) => {
    if (!guardTrusted(event)) return { ok: false, reason: 'untrusted_sender' }
    // QQNT 预检：未装则不 stop/wipe（避免删掉已有 NapCat 却仍跑不起来），直接引导安装。
    if (!isQqntReady()) return { ok: false, reason: 'qqnt_not_found' }
    // 必须 await：stop 会中断进行中的下载并等待 .part 写流关闭，否则 wipe 撞未释放句柄
    await stopNapCatHost()
    wipeNapCatInstall()
    const settings = readQqBridgeSettings()
    const started = await startNapCatHost({
      settings,
      onStatus: (status) => pushQqHostStatus(status),
      onDownload: (progress) => pushQqHostDownload(progress),
      bridgeStarter: makeNapCatBridgeStarter(),
    })
    return started ? { ok: true } : { ok: false, reason: 'reinstall_failed' }
  })

  ipcMain.handle('desktop:open-qq-login-window', (event) => {
    if (!guardTrusted(event)) return { ok: false, reason: 'untrusted_sender' }
    return openQqLoginWindow()
  })

  ipcMain.handle('desktop:notify-window-changed', (event) => {
    if (!guardTrusted(event)) return { ok: false, reason: 'untrusted_sender' }
    onWindowChanged()
    return { ok: true }
  })

  ipcMain.handle('desktop:save-launcher-position', (event, { x, y }) => {
    if (!guardTrusted(event)) return { ok: false, reason: 'untrusted_sender' }
    if (Number.isFinite(x) && Number.isFinite(y)) {
      writeLauncherPosition(x, y)
    }
    return { ok: true }
  })

  ipcMain.handle('desktop:move-launcher-by-delta', (event, { dx, dy }) => {
    if (!guardTrusted(event)) return { ok: false, reason: 'untrusted_sender' }
    if (!launcherWindow || launcherWindow.isDestroyed()) return { ok: false }
    if (!Number.isFinite(dx) || !Number.isFinite(dy)) return { ok: false }
    const bounds = launcherWindow.getBounds()
    launcherWindow.setPosition(Math.round(bounds.x + dx), Math.round(bounds.y + dy))
    const next = launcherWindow.getBounds()
    writeLauncherPosition(next.x, next.y)
    return { ok: true }
  })

  // 同步 IPC：拖动时逐帧位移，避免 invoke 排队导致窗口跟不上鼠标（尤其向右拖）
  ipcMain.on('desktop:launcher-drag-start', (event) => {
    if (!guardTrusted(event)) return
    launcherIsDragging = true
    if (launcherWindow && !launcherWindow.isDestroyed()) {
      launcherWindow.setBackgroundColor('#00000000')
    }
    applyLauncherMouseMode()
  })

  ipcMain.on('desktop:launcher-drag-move', (event, { dx, dy }) => {
    if (!guardTrusted(event)) return
    if (!launcherWindow || launcherWindow.isDestroyed()) return
    if (!Number.isFinite(dx) || !Number.isFinite(dy)) return
    if (dx === 0 && dy === 0) return
    launcherIsDragging = true
    const bounds = launcherWindow.getBounds()
    launcherWindow.setPosition(Math.round(bounds.x + dx), Math.round(bounds.y + dy))
  })

  ipcMain.on('desktop:launcher-drag-end', (event) => {
    if (!guardTrusted(event)) return
    if (!launcherWindow || launcherWindow.isDestroyed()) {
      launcherIsDragging = false
      return
    }
    launcherIsDragging = false
    launcherSuppressMoveSave = true
    clampLauncherToWorkArea()
    resetLauncherInteraction()
    setTimeout(() => {
      launcherSuppressMoveSave = false
    }, 250)
  })

  ipcMain.handle('desktop:set-launcher-screen-position', (event, { x, y }) => {
    if (!guardTrusted(event)) return { ok: false, reason: 'untrusted_sender' }
    if (!launcherWindow || launcherWindow.isDestroyed()) return { ok: false }
    if (!Number.isFinite(x) || !Number.isFinite(y)) return { ok: false }
    launcherIsDragging = true
    launcherWindow.setPosition(Math.round(x), Math.round(y))
    writeLauncherPosition(Math.round(x), Math.round(y))
    return { ok: true }
  })

  ipcMain.handle('desktop:set-launcher-dragging', (event, dragging) => {
    if (!guardTrusted(event)) return { ok: false, reason: 'untrusted_sender' }
    launcherIsDragging = !!dragging
    if (!launcherIsDragging) {
      clampLauncherToWorkArea()
      resetLauncherInteraction()
    }
    return { ok: true }
  })

  ipcMain.handle('desktop:clamp-launcher-position', (event) => {
    if (!guardTrusted(event)) return { ok: false, reason: 'untrusted_sender' }
    launcherIsDragging = false
    clampLauncherToWorkArea()
    resetLauncherInteraction()
    return { ok: true }
  })
}

async function runLauncherSmokeTest() {
  try {
    configureSecurity()
    registerIpcHandlers()
    const apiOrigin = resolveApiOrigin()
    const captchaUrl = `${apiOrigin}/api/auth/captcha`
    log(`API smoke: GET ${captchaUrl}`)
    try {
      const apiRes = await performApiRequest({ method: 'GET', url: captchaUrl, timeoutMs: 25000 })
      if (!apiRes.status || apiRes.status >= 500) {
        throw new Error(`status=${apiRes.status}`)
      }
      console.log('API_SMOKE_OK', JSON.stringify({ status: apiRes.status, origin: apiOrigin }))
    } catch (apiErr) {
      log(`API smoke skipped (pack continues): ${apiErr?.message || apiErr}`)
      console.log('API_SMOKE_SKIP', JSON.stringify({ origin: apiOrigin, reason: apiErr?.message || String(apiErr) }))
    }
    launcherLoggedIn = true
    writeDesktopSettings({ showDesktopPet: true, showLauncherLogo: true, allowScreenObserve: true })
    writeAuthSession({ token: 'launcher-smoke-token', tokenName: 'lianyu-token', savedAt: Date.now() })
    const win = createLauncherWindow()
    if (!win) throw new Error('launcher window missing')
    await new Promise((resolve) => {
      if (win.webContents.isLoading()) {
        win.webContents.once('did-finish-load', resolve)
      } else {
        resolve()
      }
    })
    win.show()
    resetLauncherInteraction()
    // 桌宠 UI 在 app.mount 后才渲染，而 mount 前要 await bootstrapLauncherSession
    // （含 /api/auth/me）。后端不可达时该调用要等超时（8s）才失败，单次 400ms 探测
    // 会误判 hasHitbox 缺失。改为轮询：每 300ms 探一次，最长 12s，覆盖超时 + 挂载延迟。
    const probeScript = `(() => ({
      hasApi: typeof window.electronAPI !== 'undefined',
      isElectron: window.electronAPI?.isElectron === true,
      hasToggle: typeof window.electronAPI?.toggleCharacterPicker === 'function',
      hasHitbox: !!document.querySelector('.pet-hitbox'),
    }))()`
    let probe = null
    const probeDeadline = Date.now() + 12000
    while (Date.now() < probeDeadline) {
      probe = await win.webContents.executeJavaScript(probeScript, true)
      if (probe?.hasApi && probe?.isElectron && probe?.hasToggle && probe?.hasHitbox) break
      await new Promise((r) => setTimeout(r, 300))
    }
    if (!probe?.hasApi || !probe?.isElectron || !probe?.hasToggle || !probe?.hasHitbox) {
      throw new Error(`probe failed: ${JSON.stringify(probe)}`)
    }
    const toggleResult = await win.webContents.executeJavaScript(
      'window.electronAPI.toggleCharacterPicker()',
      true,
    )
    if (!toggleResult || toggleResult.ok !== true) {
      throw new Error(`toggleCharacterPicker failed: ${JSON.stringify(toggleResult)}`)
    }
    const observerStart = await win.webContents.executeJavaScript(
      `window.electronAPI.startDesktopObserver({
        persona: '你是雷电将军，说话简洁有力。',
        petId: 'raiden',
      })`,
      true,
    )
    if (!observerStart || observerStart.ok !== true) {
      log(`smoke observer skipped: ${JSON.stringify(observerStart)}`)
    } else {
      stopDesktopObserver()
    }
    console.log('LAUNCHER_SMOKE_OK')
    app.exit(0)
  } catch (err) {
    console.error('LAUNCHER_SMOKE_FAIL', err?.message || err)
    app.exit(1)
  }
}

app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required')

app.whenReady().then(() => {
  if (process.env.LIANYU_LAUNCHER_SMOKE === '1') {
    log('launcher smoke test starting')
    return runLauncherSmokeTest()
  }
  log('app ready')
  configureSecurity()
  configureAntiDebug()
  patchDesktopRequestOrigin()
  applyLaunchAtLogin(readDesktopSettings().launchAtLogin)
  registerIpcHandlers()
  createMainWindow()
  ensureTray()

  if (readAuthSession()) {
    launcherLoggedIn = true
    prewarmLauncherWindow()
    prewarmQuickChatShell()
  }

  void autoStartQqBridgeIfNeeded()
  void autoStartNapCatHostIfNeeded()

  // 电源事件：唤醒后通知窗口切换检测
  if (powerMonitor) {
    powerMonitor.on('resume', () => onWindowChanged())
    powerMonitor.on('unlock-screen', () => onWindowChanged())
  }

  // 桌宠在登录后预创建；启动时若已有 auth session 则并行预热

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
