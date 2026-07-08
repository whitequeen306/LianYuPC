import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { createQqBridgeCoordinator } from '../qqBridgeCoordinator.js'

function createWindowMock() {
  const handlers = {}
  const webContentsHandlers = {}
  return {
    handlers,
    webContentsHandlers,
    destroyed: false,
    shown: false,
    focused: false,
    loadedUrl: '',
    isDestroyed() {
      return this.destroyed
    },
    show: vi.fn(function show() {
      this.shown = true
    }),
    focus: vi.fn(function focus() {
      this.focused = true
    }),
    loadURL: vi.fn(async function loadURL(url) {
      this.loadedUrl = url
    }),
    setMenuBarVisibility: vi.fn(),
    once: vi.fn((event, fn) => {
      handlers[event] = fn
    }),
    on: vi.fn((event, fn) => {
      handlers[event] = fn
    }),
    webContents: {
      send: vi.fn(),
      on: vi.fn((event, fn) => {
        webContentsHandlers[event] = fn
      }),
      setWindowOpenHandler: vi.fn(),
    },
  }
}

function createDeps(overrides = {}) {
  const notifications = []
  const mainWindow = { isDestroyed: () => false, webContents: { send: vi.fn() } }
  const launcherWindow = { isDestroyed: () => false, webContents: { send: vi.fn() } }
  const BrowserWindowMock = vi.fn(() => createWindowMock())
  const NotificationMock = function NotificationMock({ title, body }) {
    this.title = title
    this.body = body
    this.on = vi.fn()
    this.show = vi.fn(() => {
      notifications.push({ title, body })
    })
  }
  NotificationMock.isSupported = vi.fn(() => true)

  const deps = {
    getWindows: () => [mainWindow, launcherWindow],
    Notification: NotificationMock,
    showMainWindow: vi.fn(),
    log: vi.fn(),
    readQqBridgeSettings: vi.fn(() => ({
      enabled: true,
      hosting: { mode: 'manual', consented: true, webuiPort: 6099, webuiToken: 'abc' },
      binding: { conversationId: '42', allowUsers: [], allowGroups: [] },
      napcat: { wsUrl: 'ws://127.0.0.1:3001' },
    })),
    writeQqBridgeSettings: vi.fn(),
    startNapCatHost: vi.fn(async () => true),
    stopNapCatHost: vi.fn(async () => {}),
    getNapCatHostStatus: vi.fn(() => ({
      state: 'running',
      webui: { port: 6099, token: 'abc', url: 'http://127.0.0.1:6099/webui?token=abc' },
    })),
    startQqBridge: vi.fn(() => true),
    stopQqBridge: vi.fn(),
    getQqBridgeStatus: vi.fn(() => ({ state: 'stopped', selfId: '' })),
    resolveDesktopAuthToken: vi.fn(async () => 'token'),
    resolveApiOrigin: vi.fn(() => 'https://api.example.com'),
    performApiRequest: vi.fn(),
    BrowserWindow: BrowserWindowMock,
    shell: { openExternal: vi.fn() },
    isAllowedExternalUrl: vi.fn(() => true),
    resolveDistPath: vi.fn(() => 'icon.ico'),
    logger: { getLogContent: vi.fn(() => '') },
    ...overrides,
  }

  return { deps, notifications, mainWindow, launcherWindow, BrowserWindowMock }
}

describe('qqBridgeCoordinator', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('connected 15s 后仍未 ready 时发送未登录告警', async () => {
    const { deps, notifications, mainWindow, launcherWindow } = createDeps()
    const coordinator = createQqBridgeCoordinator(deps)

    coordinator.pushQqBridgeStatus({ state: 'connected' })
    await vi.advanceTimersByTimeAsync(14999)
    expect(notifications).toHaveLength(0)

    await vi.advanceTimersByTimeAsync(1)
    expect(notifications).toHaveLength(1)
    expect(notifications[0].title).toBe('QQ 未登录')
    expect(mainWindow.webContents.send).toHaveBeenCalledWith(
      'desktop:qq-bridge-alert',
      expect.objectContaining({ type: 'not_logged_in' }),
    )
    expect(launcherWindow.webContents.send).toHaveBeenCalledWith(
      'desktop:qq-bridge-alert',
      expect.objectContaining({ type: 'not_logged_in' }),
    )
  })

  it('ready 到来时清除未登录计时器', async () => {
    const { deps, notifications } = createDeps()
    const coordinator = createQqBridgeCoordinator(deps)

    coordinator.pushQqBridgeStatus({ state: 'connected' })
    coordinator.pushQqBridgeStatus({ state: 'ready' })
    await vi.advanceTimersByTimeAsync(15000)

    expect(notifications).toHaveLength(0)
  })

  it('kicked + connected 时弹告警并重启 NapCat', async () => {
    const { deps, notifications } = createDeps({
      readQqBridgeSettings: vi.fn(() => ({ hosting: { mode: 'auto', consented: true } })),
    })
    const coordinator = createQqBridgeCoordinator(deps)

    coordinator.pushQqBridgeStatus({ state: 'connected', kicked: true })
    await vi.advanceTimersByTimeAsync(2000)

    expect(notifications[0].title).toBe('QQ 已掉线')
    expect(deps.stopNapCatHost).toHaveBeenCalledTimes(1)
    expect(deps.startNapCatHost).toHaveBeenCalledTimes(1)
  })

  it('auto 模式下跳过独立 bridge 自动启动', async () => {
    const { deps } = createDeps({
      readQqBridgeSettings: vi.fn(() => ({ enabled: true, hosting: { mode: 'auto' }, binding: { conversationId: '1' } })),
    })
    const coordinator = createQqBridgeCoordinator(deps)

    await coordinator.autoStartQqBridgeIfNeeded()

    expect(deps.startQqBridge).not.toHaveBeenCalled()
  })

  it('手动模式且已登录有绑定时自动启动 bridge', async () => {
    const { deps } = createDeps({
      readQqBridgeSettings: vi.fn(() => ({
        enabled: true,
        hosting: { mode: 'manual' },
        binding: { conversationId: '42' },
        napcat: { wsUrl: 'ws://127.0.0.1:3001' },
      })),
    })
    const coordinator = createQqBridgeCoordinator(deps)

    await coordinator.autoStartQqBridgeIfNeeded()

    expect(deps.startQqBridge).toHaveBeenCalledTimes(1)
    expect(deps.startQqBridge).toHaveBeenCalledWith(expect.objectContaining({
      apiOrigin: 'https://api.example.com',
      authToken: 'token',
    }))
  })

  it('bridge starter 在未配置绑定时会自动绑定后再启动', async () => {
    const { deps } = createDeps({
      readQqBridgeSettings: vi
        .fn()
        .mockReturnValueOnce({ enabled: false, hosting: { mode: 'auto', consented: true }, binding: {}, napcat: {} })
        .mockReturnValueOnce({ enabled: false, hosting: { mode: 'auto', consented: true }, binding: { conversationId: 'conv1', allowMode: 'open' }, napcat: {} }),
      performApiRequest: vi
        .fn()
        .mockResolvedValueOnce({ status: 200, data: JSON.stringify({ code: 200, data: [] }) })
        .mockResolvedValueOnce({ status: 200, data: JSON.stringify({ code: 200, data: [{ id: 'char1' }] }) })
        .mockResolvedValueOnce({ status: 200, data: JSON.stringify({ code: 200, data: { id: 'conv1' } }) }),
    })
    const coordinator = createQqBridgeCoordinator(deps)

    await coordinator.makeNapCatBridgeStarter()({ wsUrl: 'ws://127.0.0.1:3001', accessToken: 'abc' })

    expect(deps.writeQqBridgeSettings).toHaveBeenCalledWith({
      binding: { conversationId: 'conv1', allowMode: 'open' },
    })
    expect(deps.startQqBridge).toHaveBeenCalledTimes(1)
  })

  it('未同意托管时拒绝打开 QQ 登录窗口', () => {
    const { deps } = createDeps({
      readQqBridgeSettings: vi.fn(() => ({ hosting: { consented: false } })),
    })
    const coordinator = createQqBridgeCoordinator(deps)

    expect(coordinator.openQqLoginWindow()).toEqual({ ok: false, reason: 'not_consented' })
  })

  it('已有 QQ 登录窗口时复用并重新加载 url', () => {
    const { deps, BrowserWindowMock } = createDeps()
    const coordinator = createQqBridgeCoordinator(deps)

    expect(coordinator.openQqLoginWindow()).toEqual({ ok: true })
    const win = BrowserWindowMock.mock.results[0].value

    expect(coordinator.openQqLoginWindow()).toEqual({ ok: true, reused: true })
    expect(win.show).toHaveBeenCalled()
    expect(win.focus).toHaveBeenCalled()
    expect(win.loadURL).toHaveBeenCalledWith('http://127.0.0.1:6099/webui?token=abc')
  })

  it('获取桥接日志时仅返回桥接相关行', () => {
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

    expect(coordinator.getQqBridgeLogs()).toEqual({
      ok: true,
      lines: ['[qqBridge] hello', '[napcatHost] world'],
    })
  })

  it('dispose 会关闭已打开的 QQ 登录窗口并清理计时器', async () => {
    const { deps, BrowserWindowMock, notifications } = createDeps()
    const coordinator = createQqBridgeCoordinator(deps)

    coordinator.pushQqBridgeStatus({ state: 'connected' })
    coordinator.openQqLoginWindow()
    const win = BrowserWindowMock.mock.results[0].value
    win.destroy = vi.fn(() => {
      win.destroyed = true
    })

    coordinator.dispose()
    await vi.advanceTimersByTimeAsync(15000)

    expect(win.destroy).toHaveBeenCalledTimes(1)
    expect(notifications).toHaveLength(0)
  })
})
