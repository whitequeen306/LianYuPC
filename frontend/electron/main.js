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
  dialog,
} from 'electron'
import path from 'path'
import fs from 'fs'
import crypto from 'crypto'
import { createRequire } from 'module'
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
import { performApiRequest } from './apiProxy.js'
import { loadRuntimeSecrets, getRuntimeSecrets } from './runtimeSecrets.js'
import { RENDERER_AUTH_TOKEN_SCRIPT } from './rendererTokenScript.js'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const nodeRequire = createRequire(import.meta.url)
/** 主进程在 app.asar 内时，默认 fs 无法读取 asar 本体，需用 original-fs */
const rawFs = nodeRequire('original-fs')

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

function readExpectedAsarIntegrityHash() {
  const hexPath = path.join(process.resourcesPath, 'asar-integrity.hex')
  if (!fs.existsSync(hexPath)) return ''
  const expected = fs.readFileSync(hexPath, 'utf8').trim()
  return expected.length === 64 ? expected : ''
}

/** 流式哈希 app.asar，避免同步 readFileSync 整包阻塞主进程 */
function verifyAsarIntegrityAsync() {
  if (!app.isPackaged) return Promise.resolve(true)
  const asarPath = path.join(process.resourcesPath, 'app.asar')
  const expected = readExpectedAsarIntegrityHash()
  if (!expected) {
    log('asar integrity hash not found')
    dialog.showErrorBox('LianYu', '客户端完整性校验文件缺失，请重新安装。')
    return Promise.resolve(false)
  }

  return new Promise((resolve) => {
    const hash = crypto.createHash('sha256')
    const stream = rawFs.createReadStream(asarPath)
    stream.on('data', (chunk) => hash.update(chunk))
    stream.on('end', () => {
      const digest = hash.digest('hex')
      if (digest !== expected) {
        dialog.showErrorBox('LianYu', '客户端文件被篡改，请重新安装。')
        resolve(false)
        return
      }
      log('asar integrity verification OK')
      resolve(true)
    })
    stream.on('error', (err) => {
      log(`asar integrity verification failed: ${err.message}`)
      dialog.showErrorBox('LianYu', '客户端完整性校验失败，请重新安装。')
      resolve(false)
    })
  })
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
    const allowScreenObserve = localStorage.getItem('lianyu-allow-screen-observe') === 'true'
    return { theme, loggedIn, showDesktopPet, launcherPetId, allowScreenObserve }
  } catch {
    return { theme: 'dark', loggedIn: false, showDesktopPet: true, launcherPetId: 'raiden', allowScreenObserve: false }
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
  const state = await pullRendererDesktopState(win)
  if (!state) return readDesktopSettings()
  if (state.allowScreenObserve === true) {
    return writeDesktopSettings({ allowScreenObserve: true })
  }
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
  if (state.allowScreenObserve === true) {
    writeDesktopSettings({ allowScreenObserve: true })
  }
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
  for (const win of quickChatWindows.values()) {
    applyTitleBarOverlayToWindow(win, currentTitleBarPreset)
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

  const existing = quickChatWindows.get(id)
  if (existing && !existing.isDestroyed()) {
    showQuickChatWindow(existing, id)
    return existing
  }

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
  attachWindowLogging(win, `quickChat:${id}`)

  const reveal = () => showQuickChatWindow(win, id)

  win.once('ready-to-show', reveal)
  win.webContents.once('did-finish-load', () => {
    if (!win.isDestroyed() && !win.isVisible()) {
      setTimeout(reveal, 100)
    }
  })
  win.webContents.once('did-fail-load', () => {
    log(`openQuickChatWindow: load failed conversation=${id}, fallback main window`)
    if (!win.isDestroyed()) win.destroy()
    quickChatWindows.delete(id)
    showMainWindow(`#/app/chat/${id}`)
  })

  const forceTimer = setTimeout(() => {
    if (!win.isDestroyed() && !win.isVisible()) reveal()
  }, 800)

  win.on('closed', () => {
    clearTimeout(forceTimer)
    quickChatWindows.delete(id)
    resetLauncherInteraction()
  })

  loadRoute(win, `#/quick/chat/${id}`)
  quickChatWindows.set(id, win)
  return win
}

function destroyQuickChatWindow(win, reason = 'unknown') {
  if (!win || win.isDestroyed()) return false
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
  for (const chatWin of quickChatWindows.values()) {
    if (!chatWin.isDestroyed() && chatWin.webContents.id === event.sender.id) {
      return destroyQuickChatWindow(chatWin, 'map-by-sender')
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
    try {
      const res = await performApiRequest({
        method: payload?.method || 'GET',
        url: payload?.url || '',
        headers: payload?.headers || {},
        body: payload?.body,
        timeoutMs: payload?.timeout || 60000,
      })
      return {
        ok: true,
        status: res.status,
        statusText: res.statusText,
        headers: res.headers,
        data: res.data,
      }
    } catch (err) {
      log(`api:request failed url=${payload?.url || ''} err=${err?.message || err}`)
      return { ok: false, status: 0, statusText: '', headers: {}, data: '' }
    }
  })

  ipcMain.handle('auth:get-session', (event) => {
    if (!guardTrusted(event)) return { ok: false, reason: 'untrusted_sender' }
    return readAuthSession()
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
    })
    return started ? { ok: true } : { ok: false, reason: 'start_failed' }
  })

  ipcMain.handle('desktop:stop-observer', (event) => {
    if (!guardTrusted(event)) return { ok: false, reason: 'untrusted_sender' }
    stopDesktopObserver()
    return { ok: true }
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
    await new Promise((r) => setTimeout(r, 400))
    const probe = await win.webContents.executeJavaScript(
      `(() => ({
        hasApi: typeof window.electronAPI !== 'undefined',
        isElectron: window.electronAPI?.isElectron === true,
        hasToggle: typeof window.electronAPI?.toggleCharacterPicker === 'function',
        hasHitbox: !!document.querySelector('.pet-hitbox'),
      }))()`,
      true,
    )
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
      throw new Error(`startDesktopObserver failed: ${JSON.stringify(observerStart)}`)
    }
    console.log('LAUNCHER_SMOKE_OK')
    stopDesktopObserver()
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

  void verifyAsarIntegrityAsync().then((ok) => {
    if (!ok) app.exit(1)
  })

  if (readAuthSession()) {
    launcherLoggedIn = true
    prewarmLauncherWindow()
  }

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
