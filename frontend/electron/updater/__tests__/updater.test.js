import { describe, it, expect, beforeEach, vi } from 'vitest'

// 用 vi.hoisted 把 mock 状态提到 hoist 层，工厂函数可安全引用；
// vi.resetModules 后工厂重新执行，但 get/set 仍指向这些单例对象，状态得以保留/重置。
const mocks = vi.hoisted(() => ({
  isPackaged: true,
  handleRegistry: new Map(),
  webSend: vi.fn(),
  events: {},
  calls: { check: 0, download: 0, quitInstall: 0 },
  config: { autoDownload: true, autoInstallOnAppQuit: true },
}))

vi.mock('electron', () => ({
  app: { get isPackaged() { return mocks.isPackaged } },
  ipcMain: { handle: (ch, fn) => mocks.handleRegistry.set(ch, fn) },
}))

vi.mock('electron-updater', () => ({
  autoUpdater: {
    get autoDownload() { return mocks.config.autoDownload },
    set autoDownload(v) { mocks.config.autoDownload = v },
    get autoInstallOnAppQuit() { return mocks.config.autoInstallOnAppQuit },
    set autoInstallOnAppQuit(v) { mocks.config.autoInstallOnAppQuit = v },
    on: (ev, fn) => { mocks.events[ev] = fn },
    checkForUpdates: () => { mocks.calls.check++; return Promise.resolve() },
    downloadUpdate: () => { mocks.calls.download++; return Promise.resolve() },
    quitAndInstall: () => { mocks.calls.quitInstall++ },
  },
}))

vi.mock('../../logger.js', () => ({ log: vi.fn(), info: vi.fn() }))

const mockMainWindow = { isDestroyed: () => false, webContents: { send: mocks.webSend } }

// 动态 import 配合 vi.resetModules，每个用例拿到干净的模块实例（initialized=false）
async function loadUpdater() {
  return import('../updater.js')
}

describe('updater — initUpdater', () => {
  beforeEach(() => {
    mocks.handleRegistry.clear()
    mocks.webSend.mockClear()
    mocks.calls.check = 0
    mocks.calls.download = 0
    mocks.calls.quitInstall = 0
    mocks.events = {}
    mocks.config.autoDownload = true
    mocks.config.autoInstallOnAppQuit = true
    mocks.isPackaged = true
    vi.resetModules()
  })

  it('注册 3 个 IPC 通道并设 autoDownload=false / autoInstallOnAppQuit=false', async () => {
    const { initUpdater } = await loadUpdater()
    initUpdater(mockMainWindow)
    expect(mocks.handleRegistry.has('updater:check')).toBe(true)
    expect(mocks.handleRegistry.has('updater:download')).toBe(true)
    expect(mocks.handleRegistry.has('updater:install')).toBe(true)
    expect(mocks.config.autoDownload).toBe(false)
    expect(mocks.config.autoInstallOnAppQuit).toBe(false)
  })

  it('重复 initUpdater 不重复注册（幂等）', async () => {
    const { initUpdater } = await loadUpdater()
    initUpdater(mockMainWindow)
    initUpdater(mockMainWindow)
    expect(mocks.handleRegistry.size).toBe(3)
  })

  it('autoUpdater 事件触发后推送正确状态到 renderer', async () => {
    const { initUpdater } = await loadUpdater()
    initUpdater(mockMainWindow)

    mocks.events['checking-for-update']()
    expect(mocks.webSend).toHaveBeenLastCalledWith('updater:state', { state: 'checking', info: {} })

    mocks.events['update-available']({ version: '0.2.260' })
    expect(mocks.webSend).toHaveBeenLastCalledWith('updater:state', expect.objectContaining({
      state: 'update-available', info: { version: '0.2.260' },
    }))

    mocks.events['update-not-available']()
    expect(mocks.webSend).toHaveBeenLastCalledWith('updater:state', { state: 'no-update', info: {} })

    mocks.events['download-progress']({ percent: 45, transferred: 100, total: 200, bytesPerSecond: 50 })
    expect(mocks.webSend).toHaveBeenLastCalledWith('updater:state', expect.objectContaining({
      state: 'downloading',
      info: { percent: 45, transferred: 100, total: 200, bytesPerSecond: 50 },
    }))

    mocks.events['update-downloaded']({ version: '0.2.260' })
    expect(mocks.webSend).toHaveBeenLastCalledWith('updater:state', expect.objectContaining({
      state: 'ready', info: { version: '0.2.260' },
    }))

    mocks.events['error'](new Error('network'))
    expect(mocks.webSend).toHaveBeenLastCalledWith('updater:state', expect.objectContaining({
      state: 'error', info: { errorMessage: 'network' },
    }))
  })

  it('updater:check 在 packaged 模式调用 autoUpdater.checkForUpdates', async () => {
    const { initUpdater } = await loadUpdater()
    initUpdater(mockMainWindow)
    const ret = await mocks.handleRegistry.get('updater:check')()
    expect(ret).toEqual({ ok: true })
    expect(mocks.calls.check).toBe(1)
  })

  it('updater:check 在 dev 模式返回 dev-mode 且不调用 autoUpdater', async () => {
    mocks.isPackaged = false
    const { initUpdater } = await loadUpdater()
    initUpdater(mockMainWindow)
    const ret = await mocks.handleRegistry.get('updater:check')()
    expect(ret).toEqual({ ok: false, reason: 'dev-mode' })
    expect(mocks.calls.check).toBe(0)
    expect(mocks.webSend).toHaveBeenLastCalledWith('updater:state', expect.objectContaining({
      state: 'error', info: { errorMessage: 'dev-mode' },
    }))
  })

  it('updater:download 调用 autoUpdater.downloadUpdate', async () => {
    const { initUpdater } = await loadUpdater()
    initUpdater(mockMainWindow)
    const ret = await mocks.handleRegistry.get('updater:download')()
    expect(ret).toEqual({ ok: true })
    expect(mocks.calls.download).toBe(1)
  })

  it('updater:install 调用 autoUpdater.quitAndInstall', async () => {
    const { initUpdater } = await loadUpdater()
    initUpdater(mockMainWindow)
    const ret = await mocks.handleRegistry.get('updater:install')()
    expect(ret).toEqual({ ok: true })
    expect(mocks.calls.quitInstall).toBe(1)
    expect(mocks.webSend).toHaveBeenLastCalledWith('updater:state', expect.objectContaining({
      state: 'installing',
    }))
  })

  it('updater:check 异常时推送 error 状态', async () => {
    // 临时把 checkForUpdates 改成 reject
    mocks.events.__throw = true
    const { initUpdater } = await loadUpdater()
    initUpdater(mockMainWindow)
    // 重新注册一个会抛错的 check
    const au = (await import('electron-updater')).autoUpdater
    au.checkForUpdates = () => Promise.reject(new Error('boom'))
    const ret = await mocks.handleRegistry.get('updater:check')()
    expect(ret.ok).toBe(false)
    expect(ret.error).toBe('boom')
    expect(mocks.webSend).toHaveBeenLastCalledWith('updater:state', expect.objectContaining({
      state: 'error', info: { errorMessage: 'boom' },
    }))
  })

  it('主窗口被销毁时 send 静默跳过不抛错', async () => {
    const destroyedWin = { isDestroyed: () => true, webContents: { send: vi.fn() } }
    const { initUpdater } = await loadUpdater()
    initUpdater(destroyedWin)
    mocks.events['checking-for-update']()
    expect(destroyedWin.webContents.send).not.toHaveBeenCalled()
  })

  it('getUpdaterState 返回当前状态', async () => {
    const { initUpdater, getUpdaterState } = await loadUpdater()
    initUpdater(mockMainWindow)
    mocks.events['update-available']({ version: '0.2.260' })
    expect(getUpdaterState()).toEqual(expect.objectContaining({
      state: 'update-available', info: { version: '0.2.260' },
    }))
  })
})
