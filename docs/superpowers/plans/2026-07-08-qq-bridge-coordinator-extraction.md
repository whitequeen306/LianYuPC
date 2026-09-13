# QQ Bridge Coordinator Extraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract QQ bridge and NapCat host orchestration out of `frontend/electron/main.js` into a dedicated coordinator module without changing existing QQ bridge behavior.

**Architecture:** Add a `createQqBridgeCoordinator(deps)` module that owns QQ-related orchestration, timers, alerts, auto-start, auto-bind, login-window flow, and log filtering. Keep `main.js` as the Electron shell and IPC registrar while preserving all IPC contracts and runtime behavior.

**Tech Stack:** Electron main process, Vitest, existing QQ bridge runtime, existing NapCat host runtime

---

### Task 1: Add coordinator test harness first

**Files:**
- Create: `frontend/electron/qqBridge/__tests__/qqBridgeCoordinator.test.js`
- Read for patterns: `frontend/electron/qqBridge/__tests__/qqBridge.fetchImage.test.js`
- Read for patterns: `frontend/electron/napcatRuntime/__tests__/napcatHost.test.js`

- [ ] **Step 1: Write the failing test file skeleton**

```js
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'

describe('qqBridgeCoordinator', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('schedules not-logged-in alert after connected state', async () => {
    expect(true).toBe(false)
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- qqBridgeCoordinator`
Expected: FAIL because placeholder assertion fails.

- [ ] **Step 3: Replace placeholder with behavior-driven failing tests**

```js
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { createQqBridgeCoordinator } from '../qqBridgeCoordinator.js'

function createDeps(overrides = {}) {
  const sent = []
  const notifications = []
  const windows = [
    {
      isDestroyed: () => false,
      webContents: { send: vi.fn((channel, payload) => sent.push({ channel, payload })) },
    },
  ]
  const NotificationCtor = function NotificationMock({ title, body }) {
    this.title = title
    this.body = body
    this.on = vi.fn()
    this.show = vi.fn(() => notifications.push({ title, body }))
  }
  NotificationCtor.isSupported = vi.fn(() => true)
  return {
    sent,
    notifications,
    deps: {
      getWindows: () => windows,
      Notification: NotificationCtor,
      showMainWindow: vi.fn(),
      log: vi.fn(),
      readQqBridgeSettings: vi.fn(() => ({ enabled: true, hosting: { mode: 'manual', consented: true } })),
      writeQqBridgeSettings: vi.fn(),
      startNapCatHost: vi.fn(async () => true),
      stopNapCatHost: vi.fn(async () => {}),
      startQqBridge: vi.fn(() => true),
      getNapCatHostStatus: vi.fn(() => ({ state: 'running', webui: { port: 6099, token: 'abc', url: 'http://127.0.0.1:6099/webui?token=abc' } })),
      resolveDesktopAuthToken: vi.fn(async () => 'token'),
      resolveApiOrigin: vi.fn(() => 'https://api.example.com'),
      performApiRequest: vi.fn(),
      BrowserWindow: vi.fn(),
      shell: { openExternal: vi.fn() },
      isAllowedExternalUrl: vi.fn(() => true),
      resolveDistPath: vi.fn(() => 'icon.ico'),
      logger: { getLogContent: vi.fn(() => '') },
      ...overrides,
    },
  }
}

describe('qqBridgeCoordinator', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('schedules not-logged-in alert after connected state', async () => {
    const { deps, notifications } = createDeps()
    const coordinator = createQqBridgeCoordinator(deps)

    coordinator.pushQqBridgeStatus({ state: 'connected' })
    await vi.advanceTimersByTimeAsync(14999)
    expect(notifications).toHaveLength(0)

    await vi.advanceTimersByTimeAsync(1)
    expect(notifications).toHaveLength(1)
    expect(notifications[0].title).toContain('QQ 未登录')
  })

  it('clears pending not-logged-in alert when ready arrives', async () => {
    const { deps, notifications } = createDeps()
    const coordinator = createQqBridgeCoordinator(deps)

    coordinator.pushQqBridgeStatus({ state: 'connected' })
    coordinator.pushQqBridgeStatus({ state: 'ready' })
    await vi.advanceTimersByTimeAsync(15000)

    expect(notifications).toHaveLength(0)
  })

  it('restarts NapCat after kicked connected status', async () => {
    const { deps, notifications } = createDeps({
      readQqBridgeSettings: vi.fn(() => ({ hosting: { mode: 'auto', consented: true } })),
    })
    const coordinator = createQqBridgeCoordinator(deps)

    coordinator.pushQqBridgeStatus({ state: 'connected', kicked: true })
    await Promise.resolve()

    expect(notifications[0].title).toContain('QQ 已掉线')
    expect(deps.stopNapCatHost).toHaveBeenCalledTimes(1)
    expect(deps.startNapCatHost).toHaveBeenCalledTimes(1)
  })
})
```

- [ ] **Step 4: Run test to verify it fails for missing module/export**

Run: `npm test -- qqBridgeCoordinator`
Expected: FAIL with import error for `../qqBridgeCoordinator.js` or missing export.

- [ ] **Step 5: Commit**

```bash
git add frontend/electron/qqBridge/__tests__/qqBridgeCoordinator.test.js
git commit -m "test: add qq bridge coordinator coverage"
```

### Task 2: Implement minimal coordinator module to satisfy tests

**Files:**
- Create: `frontend/electron/qqBridge/qqBridgeCoordinator.js`
- Test: `frontend/electron/qqBridge/__tests__/qqBridgeCoordinator.test.js`

- [ ] **Step 1: Add the coordinator factory with alert state and minimal methods**

```js
export function createQqBridgeCoordinator(deps) {
  let prevBridgeState = ''
  let notLoggedInTimer = null
  let lastDisconnectNotifyTs = 0
  let lastKickedTs = 0
  let napcatRestarting = false

  function clearNotLoggedInTimer() {
    if (notLoggedInTimer) {
      clearTimeout(notLoggedInTimer)
      notLoggedInTimer = null
    }
  }

  function broadcast(channel, payload) {
    for (const win of deps.getWindows()) {
      if (win && !win.isDestroyed()) {
        try {
          win.webContents.send(channel, payload)
        } catch {
          // ignore destroyed race
        }
      }
    }
  }

  function showQqBridgeNotification(title, body) {
    if (!deps.Notification?.isSupported?.()) return
    const notification = new deps.Notification({ title, body, silent: false })
    notification.on?.('click', () => deps.showMainWindow?.('#/app/qq-bridge'))
    notification.show?.()
  }

  function sendQqBridgeAlert(type, message) {
    const titleMap = {
      kicked: 'QQ 已掉线',
      disconnected: 'QQ 桥接掉线',
      not_logged_in: 'QQ 未登录',
      restart_failed: 'NapCat 重启失败',
    }
    broadcast('desktop:qq-bridge-alert', { type, message, ts: Date.now() })
    showQqBridgeNotification(titleMap[type] || 'QQ 桥接提醒', message)
  }

  async function restartNapCatForReconnect() {
    if (napcatRestarting) return
    napcatRestarting = true
    try {
      const settings = deps.readQqBridgeSettings()
      if (settings.hosting?.mode !== 'auto') {
        sendQqBridgeAlert('restart_failed', 'QQ 已掉线，手动模式下需自行重启 NapCat 并扫码登录')
        return
      }
      await deps.stopNapCatHost()
      await new Promise((resolve) => setTimeout(resolve, 2000))
      const ok = await deps.startNapCatHost({
        settings,
        onStatus: pushQqHostStatus,
        onDownload: pushQqHostDownload,
        bridgeStarter: makeNapCatBridgeStarter(),
      })
      if (!ok) {
        sendQqBridgeAlert('restart_failed', 'NapCat 重启失败，请打开 QQ 桥接页面扫码登录')
      }
    } finally {
      napcatRestarting = false
    }
  }

  function pushQqBridgeStatus(status) {
    broadcast('desktop:qq-bridge-status', { ...status, ts: Date.now() })
    const state = status?.state || ''
    if (state === 'ready') {
      clearNotLoggedInTimer()
    } else if (status?.kicked && state === 'connected') {
      lastKickedTs = Date.now()
      clearNotLoggedInTimer()
      sendQqBridgeAlert('kicked', 'QQ 已掉线（被踢下线/另一终端登录），正在重启 NapCat 尝试重新登录…')
      void restartNapCatForReconnect()
    } else if (state === 'connected' && prevBridgeState !== 'connected') {
      clearNotLoggedInTimer()
      notLoggedInTimer = setTimeout(() => {
        sendQqBridgeAlert('not_logged_in', 'QQ 桥接已连接但未检测到登录态，请打开 QQ 桥接页面扫码登录')
      }, 15000)
    } else if (state === 'disconnected') {
      const now = Date.now()
      if (now - lastKickedTs > 60000 && now - lastDisconnectNotifyTs > 30000 && (prevBridgeState === 'ready' || prevBridgeState === 'connected')) {
        lastDisconnectNotifyTs = now
        sendQqBridgeAlert('disconnected', 'QQ 连接已断开，正在尝试重连…')
      }
      clearNotLoggedInTimer()
    } else if (state === 'stopped') {
      clearNotLoggedInTimer()
    }
    prevBridgeState = state
  }

  function pushQqHostStatus(status) {
    broadcast('desktop:qq-host-status', { ...status, ts: Date.now() })
  }

  function pushQqHostDownload(progress) {
    broadcast('desktop:qq-host-download', { ...progress, ts: Date.now() })
  }

  function makeNapCatBridgeStarter() {
    return async () => {}
  }

  return {
    pushQqBridgeStatus,
    pushQqHostStatus,
    pushQqHostDownload,
    makeNapCatBridgeStarter,
  }
}
```

- [ ] **Step 2: Run coordinator test to verify the minimal implementation passes first cases**

Run: `npm test -- qqBridgeCoordinator`
Expected: PASS for the initial status tests, with later tests still pending if not yet implemented.

- [ ] **Step 3: Commit**

```bash
git add frontend/electron/qqBridge/qqBridgeCoordinator.js frontend/electron/qqBridge/__tests__/qqBridgeCoordinator.test.js
git commit -m "feat: add qq bridge coordinator shell"
```

### Task 3: Expand tests to cover auto-start, auto-bind, login-window, and log filtering

**Files:**
- Modify: `frontend/electron/qqBridge/__tests__/qqBridgeCoordinator.test.js`
- Read: `frontend/electron/main.js:1883-2167`

- [ ] **Step 1: Add failing tests for auto-start and bridge starter behavior**

```js
it('autoStartQqBridgeIfNeeded skips auto hosting mode', async () => {
  const { deps } = createDeps({
    readQqBridgeSettings: vi.fn(() => ({ enabled: true, hosting: { mode: 'auto' }, binding: { conversationId: '1' } })),
  })
  const coordinator = createQqBridgeCoordinator(deps)

  await coordinator.autoStartQqBridgeIfNeeded()

  expect(deps.startQqBridge).not.toHaveBeenCalled()
})

it('autoStartQqBridgeIfNeeded starts bridge for manual mode with auth and binding', async () => {
  const { deps } = createDeps({
    readQqBridgeSettings: vi.fn(() => ({ enabled: true, hosting: { mode: 'manual' }, binding: { conversationId: '42' }, napcat: { wsUrl: 'ws://127.0.0.1:3001' } })),
  })
  const coordinator = createQqBridgeCoordinator(deps)

  await coordinator.autoStartQqBridgeIfNeeded()

  expect(deps.startQqBridge).toHaveBeenCalledTimes(1)
})

it('bridge starter auto-binds then starts qq bridge', async () => {
  const { deps } = createDeps({
    readQqBridgeSettings: vi.fn(() => ({ enabled: false, hosting: { mode: 'auto', consented: true }, binding: {}, napcat: {} })),
    performApiRequest: vi
      .fn()
      .mockResolvedValueOnce({ status: 200, data: JSON.stringify({ code: 200, data: [] }) })
      .mockResolvedValueOnce({ status: 200, data: JSON.stringify({ code: 200, data: [{ id: 'c1' }] }) })
      .mockResolvedValueOnce({ status: 200, data: JSON.stringify({ code: 200, data: { id: 'conv1' } }) }),
  })
  const coordinator = createQqBridgeCoordinator(deps)

  await coordinator.makeNapCatBridgeStarter()({ wsUrl: 'ws://127.0.0.1:3001', accessToken: 'abc' })

  expect(deps.writeQqBridgeSettings).toHaveBeenCalled()
  expect(deps.startQqBridge).toHaveBeenCalledTimes(1)
})
```

- [ ] **Step 2: Add failing tests for login window and logs**

```js
it('openQqLoginWindow rejects when not consented', () => {
  const { deps } = createDeps({
    readQqBridgeSettings: vi.fn(() => ({ hosting: { consented: false } })),
  })
  const coordinator = createQqBridgeCoordinator(deps)

  expect(coordinator.openQqLoginWindow()).toEqual({ ok: false, reason: 'not_consented' })
})

it('getQqBridgeLogs filters bridge-related log lines', () => {
  const { deps } = createDeps({
    logger: {
      getLogContent: vi.fn(() => [
        '[qqBridge] hello',
        '[napcatHost] world',
        '[other] ignore',
      ].join('\n')),
    },
  })
  const coordinator = createQqBridgeCoordinator(deps)

  const result = coordinator.getQqBridgeLogs()
  expect(result.ok).toBe(true)
  expect(result.lines).toHaveLength(2)
})
```

- [ ] **Step 3: Run test to verify it fails for missing coordinator methods**

Run: `npm test -- qqBridgeCoordinator`
Expected: FAIL with missing `autoStartQqBridgeIfNeeded`, `openQqLoginWindow`, `getQqBridgeLogs`, or incomplete bridge starter behavior.

- [ ] **Step 4: Commit**

```bash
git add frontend/electron/qqBridge/__tests__/qqBridgeCoordinator.test.js
git commit -m "test: cover qq bridge coordinator flows"
```

### Task 4: Finish coordinator implementation with parity logic

**Files:**
- Modify: `frontend/electron/qqBridge/qqBridgeCoordinator.js`
- Read: `frontend/electron/main.js:1883-2167`
- Test: `frontend/electron/qqBridge/__tests__/qqBridgeCoordinator.test.js`

- [ ] **Step 1: Implement `ensureBridgeBinding()` using injected `performApiRequest`**

```js
async function ensureBridgeBinding({ apiOrigin, authToken, characterId }) {
  const unwrap = (res, path) => {
    if (!res || res.status < 200 || res.status >= 300) throw new Error(`api:${path} HTTP ${res?.status}`)
    const body = JSON.parse(res.data || '{}')
    if (typeof body.code === 'number') {
      if (body.code !== 200) throw new Error(`api:${path} code ${body.code}`)
      return body.data
    }
    return body
  }
  const apiGet = async (path) => unwrap(await deps.performApiRequest({ method: 'GET', url: `${apiOrigin}${path}`, timeoutMs: 15000, apiOrigin, authToken }), path)
  const apiPost = async (path, payload) => unwrap(await deps.performApiRequest({ method: 'POST', url: `${apiOrigin}${path}`, headers: { 'Content-Type': 'application/json' }, body: payload, timeoutMs: 30000, apiOrigin, authToken }), path)

  try {
    const list = await apiGet('/api/conversation')
    if (Array.isArray(list) && list.length) {
      const single = characterId
        ? list.find((c) => c?.mode === 'SINGLE' && String(c?.characterId) === String(characterId))
        : (list.find((c) => c?.mode === 'SINGLE') || list[0])
      if (single?.id) {
        return {
          conversationId: String(single.id),
          characterId: characterId ? String(characterId) : (single.characterId ? String(single.characterId) : ''),
        }
      }
    }
  } catch (e) {
    deps.log?.(`[ensureBridgeBinding] list conversations failed: ${e?.message || e}`)
  }

  try {
    const chars = await apiGet('/api/character')
    const pick = characterId
      ? (Array.isArray(chars) ? chars.find((c) => String(c?.id) === String(characterId)) : null)
      : (Array.isArray(chars) && chars.length ? chars[0] : null)
    if (!pick?.id) return null
    const created = await apiPost('/api/conversation', { characterId: String(pick.id), mode: 'SINGLE' })
    if (!created?.id) return null
    return { conversationId: String(created.id), characterId: String(pick.id) }
  } catch (e) {
    deps.log?.(`[ensureBridgeBinding] auto-create conversation failed: ${e?.message || e}`)
  }
  return null
}
```

- [ ] **Step 2: Implement `autoStartQqBridgeIfNeeded()` and `makeNapCatBridgeStarter()` with existing branching**

```js
async function autoStartQqBridgeIfNeeded() {
  try {
    const settings = deps.readQqBridgeSettings()
    if (settings.hosting?.mode === 'auto') return
    if (!settings.enabled || !settings.binding?.conversationId) return
    const authToken = await deps.resolveDesktopAuthToken()
    if (!authToken) return
    deps.startQqBridge({
      apiOrigin: deps.resolveApiOrigin(),
      authToken,
      settings,
      onStatus: (status) => pushQqBridgeStatus(status),
    })
  } catch (e) {
    deps.log?.('[qqBridge] auto-start failed:', e?.message || e)
  }
}

function makeNapCatBridgeStarter() {
  return async ({ wsUrl, accessToken }) => {
    try {
      const authToken = await deps.resolveDesktopAuthToken()
      if (!authToken) return
      let settings = deps.readQqBridgeSettings()
      if (!settings.binding?.conversationId && !settings.binding?.characterId) {
        const result = await ensureBridgeBinding({ apiOrigin: deps.resolveApiOrigin(), authToken, characterId: settings.binding?.characterId })
        if (!result?.conversationId) return
        const prevBinding = settings.binding || {}
        const hasAllowEntries = (prevBinding.allowUsers || []).length > 0 || (prevBinding.allowGroups || []).length > 0
        deps.writeQqBridgeSettings({
          binding: {
            ...prevBinding,
            conversationId: result.conversationId,
            ...(hasAllowEntries ? {} : { allowMode: 'open' }),
          },
        })
        settings = deps.readQqBridgeSettings()
      }
      deps.startQqBridge({
        apiOrigin: deps.resolveApiOrigin(),
        authToken,
        settings: {
          ...settings,
          enabled: true,
          napcat: { ...settings.napcat, wsUrl, accessToken },
        },
        onStatus: (status) => pushQqBridgeStatus(status),
      })
    } catch (e) {
      deps.log?.('[napcatHost] bridge starter failed:', e?.message || e)
    }
  }
}
```

- [ ] **Step 3: Implement `openQqLoginWindow()` and `getQqBridgeLogs()` with injected dependencies**

```js
let qqLoginWindow = null

function isLocalNapCatUrl(url, port) {
  try {
    const u = new URL(url)
    return (u.hostname === '127.0.0.1' || u.hostname === 'localhost') && u.port === String(port)
  } catch {
    return false
  }
}

function openQqLoginWindow() {
  const settings = deps.readQqBridgeSettings()
  if (!settings.hosting?.consented) return { ok: false, reason: 'not_consented' }
  const status = deps.getNapCatHostStatus()
  const webui = status?.webui
  const port = webui?.port || settings.hosting?.webuiPort || 6099
  const token = webui?.token || settings.hosting?.webuiToken || ''
  if (!token) return { ok: false, reason: 'not_running' }
  const url = webui?.url || `http://127.0.0.1:${port}/webui?token=${token}`

  if (qqLoginWindow && !qqLoginWindow.isDestroyed()) {
    qqLoginWindow.show()
    qqLoginWindow.focus()
    try { qqLoginWindow.loadURL(url) } catch {}
    return { ok: true, reused: true }
  }

  const win = new deps.BrowserWindow({
    width: 520,
    height: 720,
    minWidth: 360,
    minHeight: 480,
    title: 'QQ 登录 · NapCat',
    icon: deps.resolveDistPath('icon.ico'),
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
  win.webContents.on('will-navigate', (e, navUrl) => {
    if (!isLocalNapCatUrl(navUrl, port)) e.preventDefault()
  })
  win.webContents.setWindowOpenHandler(({ url: openUrl }) => {
    if (isLocalNapCatUrl(openUrl, port)) return { action: 'allow' }
    if (deps.isAllowedExternalUrl(openUrl)) deps.shell.openExternal(openUrl)
    return { action: 'deny' }
  })
  win.on('closed', () => {
    qqLoginWindow = null
  })
  void win.loadURL(url)
  return { ok: true }
}

function getQqBridgeLogs() {
  try {
    const content = deps.logger.getLogContent(50000)
    const all = content ? content.split(/\r?\n/).filter(Boolean) : []
    const bridgeRe = /\[(qqBridge|napcatHost|resolve-conversation|ensureBridgeBinding)\]/
    const lines = all.filter((l) => bridgeRe.test(l))
    return { ok: true, lines: lines.slice(-500) }
  } catch (e) {
    return { ok: false, reason: 'read_failed', error: e?.message || String(e) }
  }
}
```

- [ ] **Step 4: Run coordinator tests and make them pass**

Run: `npm test -- qqBridgeCoordinator`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/electron/qqBridge/qqBridgeCoordinator.js frontend/electron/qqBridge/__tests__/qqBridgeCoordinator.test.js
git commit -m "feat: extract qq bridge coordinator logic"
```

### Task 5: Wire main.js to the coordinator without changing IPC contracts

**Files:**
- Modify: `frontend/electron/main.js`
- Test: `frontend/electron/qqBridge/__tests__/qqBridgeCoordinator.test.js`

- [ ] **Step 1: Import and initialize coordinator near existing QQ bridge imports**

```js
import { createQqBridgeCoordinator } from './qqBridge/qqBridgeCoordinator.js'

const qqBridgeCoordinator = createQqBridgeCoordinator({
  getWindows: () => [mainWindow, launcherWindow],
  Notification,
  showMainWindow,
  log,
  readQqBridgeSettings,
  writeQqBridgeSettings,
  startNapCatHost,
  stopNapCatHost,
  getNapCatHostStatus,
  startQqBridge,
  stopQqBridge,
  getQqBridgeStatus,
  resolveDesktopAuthToken,
  resolveApiOrigin,
  performApiRequest,
  BrowserWindow,
  shell,
  isAllowedExternalUrl,
  resolveDistPath,
  logger,
})
```

- [ ] **Step 2: Replace in-file QQ coordinator helpers with delegation**

```js
async function autoStartQqBridgeIfNeeded() {
  return qqBridgeCoordinator.autoStartQqBridgeIfNeeded()
}

async function autoStartNapCatHostIfNeeded() {
  return qqBridgeCoordinator.autoStartNapCatHostIfNeeded()
}
```

And replace callback uses like:

```js
onStatus: (status) => qqBridgeCoordinator.pushQqBridgeStatus(status)
onDownload: (progress) => qqBridgeCoordinator.pushQqHostDownload(progress)
bridgeStarter: qqBridgeCoordinator.makeNapCatBridgeStarter()
```

- [ ] **Step 3: Update IPC handlers to call coordinator methods**

```js
ipcMain.handle('desktop:qq-bridge-resolve-conversation', async (event, characterId) => {
  if (!guardTrusted(event)) return { ok: false, reason: 'untrusted_sender' }
  const authToken = await resolveDesktopAuthToken()
  if (!authToken) return { ok: false, reason: 'not_logged_in' }
  const result = await qqBridgeCoordinator.ensureBridgeBinding({
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
  return { ok: true, ...result }
})

ipcMain.handle('desktop:qq-bridge-get-logs', (event) => {
  if (!guardTrusted(event)) return { ok: false, reason: 'untrusted_sender' }
  return qqBridgeCoordinator.getQqBridgeLogs()
})

ipcMain.handle('desktop:open-qq-login-window', (event) => {
  if (!guardTrusted(event)) return { ok: false, reason: 'untrusted_sender' }
  return qqBridgeCoordinator.openQqLoginWindow()
})
```

- [ ] **Step 4: Run targeted tests to verify no regression in QQ modules**

Run: `npm test -- qqBridgeCoordinator qqBridge napcatHost`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/electron/main.js frontend/electron/qqBridge/qqBridgeCoordinator.js frontend/electron/qqBridge/__tests__/qqBridgeCoordinator.test.js
git commit -m "refactor: move qq bridge orchestration out of main"
```

### Task 6: Final verification and review gate

**Files:**
- Verify: `frontend/electron/main.js`
- Verify: `frontend/electron/qqBridge/qqBridgeCoordinator.js`
- Verify: `frontend/electron/qqBridge/__tests__/qqBridgeCoordinator.test.js`

- [ ] **Step 1: Run the focused Electron test suite**

Run: `npm test -- qqBridgeCoordinator qqBridgeSettings qqBridge.fetchImage napcatHost`
Expected: PASS.

- [ ] **Step 2: Run the broader frontend test suite once**

Run: `npm test`
Expected: PASS or existing unrelated failures only; if unrelated failures appear, document them explicitly before claiming success.

- [ ] **Step 3: Review the diff for architecture and behavior parity**

Run: `git diff -- frontend/electron/main.js frontend/electron/qqBridge/qqBridgeCoordinator.js frontend/electron/qqBridge/__tests__/qqBridgeCoordinator.test.js`
Expected: `main.js` shrinks in QQ orchestration areas; coordinator owns orchestration logic; IPC contracts unchanged.

- [ ] **Step 4: Request code review and address findings before completion**

Run an internal review against:
- correctness of alert timing and restart flow
- login-window behavior parity
- auto-bind parity
- dependency injection clarity

Expected: no blocking findings remain.

- [ ] **Step 5: Commit**

```bash
git add frontend/electron/main.js frontend/electron/qqBridge/qqBridgeCoordinator.js frontend/electron/qqBridge/__tests__/qqBridgeCoordinator.test.js
git commit -m "test: verify qq bridge coordinator extraction"
```
