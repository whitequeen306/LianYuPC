import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

// node 环境无 window，getElectronAPI 原本返回 null；mock 之为可控的假 API，
// 并用 holder 暴露 onQqHostStatus/onQqHostDownload 注册的回调，测试体据此
// 模拟主进程推送（状态/进度），驱动 store 的乐观进度与清空逻辑。
const { holder } = vi.hoisted(() => ({
  holder: { api: null, hostStatusCb: null, downloadCb: null, bridgeCb: null },
}))
vi.mock('@/utils/electron', () => ({
  getElectronAPI: () => holder.api,
  isElectronApp: () => true,
}))

import { useQqBridgeStore } from '@/stores/qqBridge'

/** 构造可控假 electronAPI：IPC 调用为 vi.fn，订阅回调登记到 holder 供测试触发。 */
function makeApi(overrides = {}) {
  holder.hostStatusCb = null
  holder.downloadCb = null
  holder.bridgeCb = null
  const api = {
    isElectron: true,
    getQqBridgeSettings: vi.fn(async () => null),
    getQqBridgeStatus: vi.fn(async () => ({ state: 'stopped', selfId: '' })),
    getQqHostStatus: vi.fn(async () => ({ state: 'stopped', webui: null, version: '', config: null, upgrade: null })),
    onQqBridgeStatus: vi.fn((cb) => { holder.bridgeCb = cb; return () => { holder.bridgeCb = null } }),
    onQqHostStatus: vi.fn((cb) => { holder.hostStatusCb = cb; return () => { holder.hostStatusCb = null } }),
    onQqHostDownload: vi.fn((cb) => { holder.downloadCb = cb; return () => { holder.downloadCb = null } }),
    startQqHost: vi.fn(async () => ({ ok: true })),
    stopQqHost: vi.fn(async () => undefined),
    reinstallQqHost: vi.fn(async () => ({ ok: true })),
    setQqBridgeSettings: vi.fn(async (p) => ({ settings: p })),
    ...overrides,
  }
  holder.api = api
  return api
}

let store

beforeEach(() => {
  setActivePinia(createPinia())
  vi.useFakeTimers()
  makeApi()
  store = useQqBridgeStore()
})

afterEach(() => {
  store?.dispose?.()
  vi.useRealTimers()
  holder.api = null
})

describe('qqBridge store — 下载进度乐观更新与清空', () => {
  it('startHost：点击即设 preparing 乐观进度（IPC resolve 前），ok:true 不清空', async () => {
    // 用延迟 resolve 的 startQqHost，断言 await 期间 downloadProgress 已是 preparing
    let resolveStart
    holder.api.startQqHost = vi.fn(() => new Promise((r) => { resolveStart = r }))

    let p
    const race = store.startHost().then((res) => { p = res })
    // flush 微任务：乐观进度在调用 IPC 前已写入
    await Promise.resolve()
    await Promise.resolve()
    expect(store.downloadProgress).toMatchObject({ phase: 'preparing', percent: 0 })

    resolveStart({ ok: true })
    await race
    expect(p).toEqual({ ok: true })
    // ok:true 不主动清空（留给后续状态推送 done/running 清空）
    expect(store.downloadProgress).toMatchObject({ phase: 'preparing', percent: 0 })
  })

  it('startHost：ok:false 快速失败时清空乐观进度（不留悬挂进度条）', async () => {
    holder.api.startQqHost = vi.fn(async () => ({ ok: false }))
    const res = await store.startHost()
    expect(res).toEqual({ ok: false })
    expect(store.downloadProgress).toBeNull()
  })

  it('startHost：已安装（napcatVersion 有值）不弹下载窗——停止后再点启动不误弹下载', async () => {
    // syncFromMain 拉取的设置含 napcatVersion（已装过），startHost 仅启动、不设乐观下载
    holder.api.getQqBridgeSettings = vi.fn(async () => ({ hosting: { napcatVersion: 'v4.18.7' } }))
    holder.api.startQqHost = vi.fn(async () => ({ ok: true }))
    await store.syncFromMain()
    const res = await store.startHost()
    expect(res).toEqual({ ok: true })
    // 已安装：不设乐观下载进度，避免「停止托管后再点启动却弹下载进度窗」的误判
    expect(store.downloadProgress).toBeNull()
  })

  it('reinstallHost：同样的乐观进度 + ok:false 清空', async () => {
    holder.api.reinstallQqHost = vi.fn(async () => ({ ok: false }))
    const res = await store.reinstallHost()
    expect(res).toEqual({ ok: false })
    expect(store.downloadProgress).toBeNull()
  })

  it('onQqHostDownload：新进度到达即更新 downloadProgress', async () => {
    await store.syncFromMain()
    expect(holder.downloadCb).toBeTruthy()
    holder.downloadCb({ phase: 'downloading', percent: 30, received: 300, total: 1000 })
    expect(store.downloadProgress).toMatchObject({ phase: 'downloading', percent: 30 })
  })

  it('onQqHostDownload done：1500ms 后自动清空（短暂保留供 UI 看到完成态）', async () => {
    await store.syncFromMain()
    holder.downloadCb({ phase: 'done', skipped: false })
    expect(store.downloadProgress).toMatchObject({ phase: 'done' })
    // 未到 1500ms 仍保留
    vi.advanceTimersByTime(1499)
    expect(store.downloadProgress).toMatchObject({ phase: 'done' })
    // 到点清空
    vi.advanceTimersByTime(2)
    expect(store.downloadProgress).toBeNull()
  })

  it('done 后清空定时未触发前又来新进度：取消旧定时，不被误清', async () => {
    await store.syncFromMain()
    holder.downloadCb({ phase: 'done', skipped: false })
    vi.advanceTimersByTime(1000) // 旧定时即将在 500ms 后触发
    // 新进度到达应取消未触发的清空定时
    holder.downloadCb({ phase: 'downloading', percent: 50 })
    vi.advanceTimersByTime(2000) // 超过原 1500ms 定时点
    expect(store.downloadProgress).toMatchObject({ phase: 'downloading', percent: 50 })
  })

  it('onQqHostStatus running：清空残留下载进度（下载完成进入运行态）', async () => {
    await store.syncFromMain()
    holder.downloadCb({ phase: 'extracting', percent: 100 })
    expect(store.downloadProgress).toMatchObject({ phase: 'extracting' })
    holder.hostStatusCb({ state: 'running', webui: { url: 'x' }, version: 'v4.18.7' })
    expect(store.downloadProgress).toBeNull()
  })

  it('onQqHostStatus error/stopped：同样清空残留进度（失败/中断不留悬挂进度条）', async () => {
    await store.syncFromMain()
    holder.downloadCb({ phase: 'downloading', percent: 40 })
    holder.hostStatusCb({ state: 'error' })
    expect(store.downloadProgress).toBeNull()

    holder.downloadCb({ phase: 'downloading', percent: 60 })
    holder.hostStatusCb({ state: 'stopped' })
    expect(store.downloadProgress).toBeNull()
  })

  it('onQqHostStatus 过渡态（downloading）：不清空进度（避免与进度推送竞态）', async () => {
    await store.syncFromMain()
    holder.downloadCb({ phase: 'downloading', percent: 50 })
    holder.hostStatusCb({ state: 'downloading' })
    // 过渡态不在清空集合内，进度保留
    expect(store.downloadProgress).toMatchObject({ phase: 'downloading', percent: 50 })
  })
})
