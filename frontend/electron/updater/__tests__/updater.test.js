import { describe, it, expect, beforeEach, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  isPackaged: true,
  handleRegistry: new Map(),
  webSend: vi.fn(),
  apiResponses: {},
  netRequestImpl: null,
  appVersion: '0.2.258',
  appQuit: vi.fn(),
  quitAndInstall: vi.fn(),
}))

vi.mock('electron', () => ({
  app: {
    get isPackaged() { return mocks.isPackaged },
    getVersion: () => mocks.appVersion,
    getPath: () => '/tmp/lianyu-test',
    quit: mocks.appQuit,
  },
  ipcMain: { handle: (ch, fn) => mocks.handleRegistry.set(ch, fn) },
  net: {
    request: (opts) => {
      if (mocks.netRequestImpl) return mocks.netRequestImpl(opts)
      throw new Error('net.request not configured')
    },
  },
}))

vi.mock('child_process', () => ({
  spawn: vi.fn(() => ({ unref: vi.fn() })),
}))

vi.mock('fs', () => ({
  default: {
    mkdirSync: vi.fn(),
    createWriteStream: vi.fn(() => ({ write: vi.fn(), end: (cb) => cb && cb(), on: vi.fn(), destroy: vi.fn() })),
    existsSync: vi.fn(() => true),
  },
}))

vi.mock('path', () => ({
  default: {
    join: (...args) => args.join('/'),
    basename: (value) => String(value).split('/').pop(),
  },
}))

vi.mock('../../logger.js', () => ({
  log: vi.fn(), info: vi.fn(), warn: vi.fn(), error: vi.fn(),
}))

vi.mock('../../runtimeSecrets.js', () => ({
  getRuntimeSecrets: () => ({ apiOrigin: 'https://api.lianyu.test' }),
}))

vi.mock('../../apiProxy.js', () => ({
  performApiRequest: vi.fn(async ({ url }) => {
    if (mocks.apiResponses[url]) return mocks.apiResponses[url]
    return { status: 404, data: '' }
  }),
  isAllowedEgressUrl: () => true,
}))

const mockMainWindow = { isDestroyed: () => false, webContents: { send: mocks.webSend } }

async function loadUpdater() {
  return import('../updater.js')
}

describe('updater (manual mode)', () => {
  beforeEach(() => {
    mocks.handleRegistry.clear()
    mocks.webSend.mockClear()
    mocks.apiResponses = {}
    mocks.netRequestImpl = null
    mocks.isPackaged = true
    mocks.appVersion = '0.2.258'
    mocks.appQuit.mockClear()
    mocks.quitAndInstall.mockClear()
    vi.resetModules()
  })

  it('initUpdater 注册 3 个 IPC 通道', async () => {
    const { initUpdater } = await loadUpdater()
    initUpdater(mockMainWindow)
    expect(mocks.handleRegistry.has('updater:check')).toBe(true)
    expect(mocks.handleRegistry.has('updater:download')).toBe(true)
    expect(mocks.handleRegistry.has('updater:install')).toBe(true)
  })

  it('check: 有新版本时推送 update-available', async () => {
    mocks.apiResponses['https://api.lianyu.test/api/public/files/updates/latest.yml'] = {
      status: 200,
      data: 'version: 0.2.260\nfiles:\n  - url: LianYu-Setup-0.2.260.exe\npath: LianYu-Setup-0.2.260.exe\nsha512: abc\n',
    }
    const { initUpdater } = await loadUpdater()
    initUpdater(mockMainWindow)
    const ret = await mocks.handleRegistry.get('updater:check')()
    expect(ret.ok).toBe(true)
    expect(ret.hasUpdate).toBe(true)
    expect(ret.version).toBe('0.2.260')
    expect(mocks.webSend).toHaveBeenLastCalledWith('updater:state', expect.objectContaining({
      state: 'update-available', info: { version: '0.2.260' },
    }))
  })

  it('check: 已是最新时推送 no-update', async () => {
    mocks.appVersion = '0.2.260'
    mocks.apiResponses['https://api.lianyu.test/api/public/files/updates/latest.yml'] = {
      status: 200,
      data: 'version: 0.2.260\nfiles:\n  - url: test.exe\nsha512: abc\n',
    }
    const { initUpdater } = await loadUpdater()
    initUpdater(mockMainWindow)
    const ret = await mocks.handleRegistry.get('updater:check')()
    expect(ret.ok).toBe(true)
    expect(ret.hasUpdate).toBe(false)
    expect(mocks.webSend).toHaveBeenLastCalledWith('updater:state', expect.objectContaining({
      state: 'no-update',
    }))
  })

  it('check: latest.yml 请求失败时推送 error', async () => {
    mocks.apiResponses['https://api.lianyu.test/api/public/files/updates/latest.yml'] = {
      status: 500, data: '',
    }
    const { initUpdater } = await loadUpdater()
    initUpdater(mockMainWindow)
    const ret = await mocks.handleRegistry.get('updater:check')()
    expect(ret.ok).toBe(false)
    expect(mocks.webSend).toHaveBeenLastCalledWith('updater:state', expect.objectContaining({
      state: 'error',
    }))
  })

  it('install: 调用 spawn 启动安装包并退出', async () => {
    const { initUpdater } = await loadUpdater()
    initUpdater(mockMainWindow, { quitAndInstall: mocks.quitAndInstall })
    // 先模拟下载完成（downloadedInstallerPath 由 download 设置）
    // 直接调 install，需要先调 download 设置路径
    mocks.apiResponses['https://api.lianyu.test/api/public/files/updates/latest.yml'] = {
      status: 200,
      data: 'version: 0.2.260\nfiles:\n  - url: LianYu-Setup-0.2.260.exe\nsha512: abc\n',
    }
    // mock net.request for download
    let requestedDownloadUrl = ''
    mocks.netRequestImpl = (opts) => {
      requestedDownloadUrl = opts.url
      const handlers = { response: null, error: null }
      const req = {
        on: (ev, fn) => { handlers[ev] = fn; return req },
        end: () => {
          setTimeout(() => {
            handlers.response({
              statusCode: 200,
              headers: { 'content-length': '100' },
              on: (ev, fn) => {
                if (ev === 'data') setTimeout(() => fn(Buffer.alloc(100)), 1)
                if (ev === 'end') setTimeout(() => fn(), 2)
              },
            })
          }, 1)
        },
      }
      return req
    }
    await mocks.handleRegistry.get('updater:download')()
    expect(requestedDownloadUrl).toBe('https://api.lianyu.test/api/public/files/updates/LianYu-Setup-0.2.260.exe')
    expect(mocks.webSend).toHaveBeenCalledWith('updater:state', expect.objectContaining({
      state: 'downloading',
      info: expect.objectContaining({ speedBytesPerSec: expect.any(Number), etaSeconds: expect.any(Number) }),
    }))
    const ret = await mocks.handleRegistry.get('updater:install')()
    expect(ret.ok).toBe(true)
    expect(mocks.webSend).toHaveBeenLastCalledWith('updater:state', expect.objectContaining({
      state: 'installing',
    }))
    await new Promise((resolve) => setTimeout(resolve, 550))
    expect(mocks.quitAndInstall).toHaveBeenCalledTimes(1)
    expect(mocks.appQuit).not.toHaveBeenCalled()
  })

  it('check: 无 apiOrigin 时推送 error', async () => {
    vi.doMock('../../runtimeSecrets.js', () => ({
      getRuntimeSecrets: () => null,
    }))
    const { initUpdater } = await loadUpdater()
    initUpdater(mockMainWindow)
    const ret = await mocks.handleRegistry.get('updater:check')()
    expect(ret.ok).toBe(false)
    vi.doUnmock('../../runtimeSecrets.js')
  })

  it('重复 initUpdater 幂等', async () => {
    const { initUpdater } = await loadUpdater()
    initUpdater(mockMainWindow)
    initUpdater(mockMainWindow)
    expect(mocks.handleRegistry.size).toBe(3)
  })

  it('主窗口销毁时 send 静默跳过', async () => {
    const destroyedWin = { isDestroyed: () => true, webContents: { send: vi.fn() } }
    const { initUpdater } = await loadUpdater()
    initUpdater(destroyedWin)
    mocks.apiResponses['https://api.lianyu.test/api/public/files/updates/latest.yml'] = {
      status: 200,
      data: 'version: 0.2.260\nfiles:\n  - url: test.exe\nsha512: abc\n',
    }
    await mocks.handleRegistry.get('updater:check')()
    expect(destroyedWin.webContents.send).not.toHaveBeenCalled()
  })
})
