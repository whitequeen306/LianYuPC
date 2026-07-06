import { describe, it, expect, beforeEach, vi } from 'vitest'

// mock @/utils/electron：控制 isElectronApp + getElectronAPI
let mockIsElectron = true
const onUpdateStateCallbacks = []
const mockApi = {
  checkForUpdates: vi.fn(() => Promise.resolve({ ok: true })),
  downloadUpdate: vi.fn(() => Promise.resolve({ ok: true })),
  installNow: vi.fn(() => Promise.resolve({ ok: true })),
  onUpdateState: (cb) => {
    onUpdateStateCallbacks.push(cb)
    return () => {
      const i = onUpdateStateCallbacks.indexOf(cb)
      if (i >= 0) onUpdateStateCallbacks.splice(i, 1)
    }
  },
}

vi.mock('@/utils/electron', () => ({
  isElectronApp: () => mockIsElectron,
  getElectronAPI: () => mockApi,
}))

// 捕获 onBeforeUnmount 回调以便测试中手动触发
let onBeforeUnmountCb = null
vi.mock('vue', async () => {
  const real = await vi.importActual('vue')
  return {
    ...real,
    onBeforeUnmount: (fn) => { onBeforeUnmountCb = fn },
  }
})

function emitState(payload) {
  onUpdateStateCallbacks.forEach(cb => cb(payload))
}

describe('useAppUpdater', () => {
  beforeEach(() => {
    mockIsElectron = true
    onUpdateStateCallbacks.length = 0
    mockApi.checkForUpdates.mockClear()
    mockApi.downloadUpdate.mockClear()
    mockApi.installNow.mockClear()
    onBeforeUnmountCb = null
    vi.resetModules()
  })

  it('初始 state=idle, info={}, isElectron=true', async () => {
    const { useAppUpdater } = await import('../useAppUpdater.js')
    const { state, info, isElectron } = useAppUpdater()
    expect(state.value).toBe('idle')
    expect(info.value).toEqual({})
    expect(isElectron.value).toBe(true)
  })

  it('订阅 onUpdateState，payload 更新 state/info', async () => {
    const { useAppUpdater } = await import('../useAppUpdater.js')
    const { state, info } = useAppUpdater()
    emitState({ state: 'update-available', info: { version: '0.2.260' } })
    expect(state.value).toBe('update-available')
    expect(info.value).toEqual({ version: '0.2.260' })
  })

  it('check 调用 electronAPI.checkForUpdates', async () => {
    const { useAppUpdater } = await import('../useAppUpdater.js')
    const { check } = useAppUpdater()
    await check()
    expect(mockApi.checkForUpdates).toHaveBeenCalled()
  })

  it('download 调用 electronAPI.downloadUpdate', async () => {
    const { useAppUpdater } = await import('../useAppUpdater.js')
    const { download } = useAppUpdater()
    await download()
    expect(mockApi.downloadUpdate).toHaveBeenCalled()
  })

  it('install 调用 electronAPI.installNow', async () => {
    const { useAppUpdater } = await import('../useAppUpdater.js')
    const { install } = useAppUpdater()
    await install()
    expect(mockApi.installNow).toHaveBeenCalled()
  })

  it('非 Electron 环境 isElectron=false 且 actions 为 no-op', async () => {
    mockIsElectron = false
    const { useAppUpdater } = await import('../useAppUpdater.js')
    const { isElectron, check, download, install } = useAppUpdater()
    expect(isElectron.value).toBe(false)
    expect(await check()).toBeUndefined()
    expect(await download()).toBeUndefined()
    expect(await install()).toBeUndefined()
    expect(mockApi.checkForUpdates).not.toHaveBeenCalled()
    expect(onUpdateStateCallbacks.length).toBe(0)
  })

  it('onBeforeUnmount 时取消订阅', async () => {
    const { useAppUpdater } = await import('../useAppUpdater.js')
    useAppUpdater()
    expect(onUpdateStateCallbacks.length).toBe(1)
    expect(onBeforeUnmountCb).toBeInstanceOf(Function)
    onBeforeUnmountCb()
    expect(onUpdateStateCallbacks.length).toBe(0)
  })

  it('payload 为空时安全跳过不抛错', async () => {
    const { useAppUpdater } = await import('../useAppUpdater.js')
    const { state } = useAppUpdater()
    expect(() => emitState(null)).not.toThrow()
    expect(state.value).toBe('idle')
  })
})
