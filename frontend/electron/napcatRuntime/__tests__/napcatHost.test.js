import { describe, it, expect, beforeEach, vi } from 'vitest'

// vi.mock 工厂被提升，无法直接闭包普通 const；用 vi.hoisted 拿到可被工厂与测试体
// 共享的 holder。downloadAndExtractNapCat / getAssetDownloadUrls 由测试体动态装填
// 行为（resolve/reject/抛错），以覆盖候选源重试的各分支。
const { mocks } = vi.hoisted(() => ({
  mocks: { download: null, getUrls: null },
}))
// electron app.getPath 指向 tmp——napcatHost 经由 qqBridgeSettings/napcatConfig 间接
// 依赖 electron，mock 之以免在 vitest 环境加载真实 electron 报错（downloadFromCandidates
// 本身不触达 app，但模块加载链需要）。
const { state } = vi.hoisted(() => ({ state: { userData: '' } }))
vi.mock('electron', () => ({ app: { getPath: () => state.userData } }))
vi.mock('../napcatDownloader.js', () => ({
  downloadAndExtractNapCat: (...args) => mocks.download?.(...args),
}))
vi.mock('../napcatRelease.js', () => ({
  getAssetDownloadUrls: (...args) => mocks.getUrls?.(...args),
  resolveLatestNapCatRelease: vi.fn(),
  getNapCatInstallRoot: vi.fn(),
  locateNapCatEntry: vi.fn(),
  compareReleaseTags: vi.fn(),
}))

import { downloadFromCandidates } from '../napcatHost.js'

const DIRECT = 'https://github.com/NapNeko/NapCatQQ/releases/download/v4.18.7/NapCat.Shell.zip'
const MIRROR = 'https://ghproxy.net/' + DIRECT
const RELEASE = {
  tag: 'v4.18.7',
  assetUrl: DIRECT,
  sha256: '628621ac6333b7c016c1ef213495af39c31ce9c4ce2b8b041ec47b0d8557a3e1',
  size: 29425972,
}

let downloadMock
let getUrlsMock

beforeEach(() => {
  state.userData = ''
  downloadMock = vi.fn()
  getUrlsMock = vi.fn()
  mocks.download = downloadMock
  mocks.getUrls = getUrlsMock
})

describe('downloadFromCandidates — 候选源重试（受限网络 CDN RST 切镜像）', () => {
  it('直连成功：仅调用一次，用直连 url，不试镜像', async () => {
    getUrlsMock.mockReturnValue([DIRECT, MIRROR])
    downloadMock.mockResolvedValue({ skipped: false })

    await downloadFromCandidates(RELEASE, () => {}, null)

    expect(downloadMock).toHaveBeenCalledTimes(1)
    // 传入的 release：assetUrl 被候选 url 覆盖，sha256/size 等原样保留
    const arg = downloadMock.mock.calls[0][0]
    expect(arg.release.assetUrl).toBe(DIRECT)
    expect(arg.release.sha256).toBe(RELEASE.sha256)
    expect(arg.release.size).toBe(RELEASE.size)
    expect(arg.release.tag).toBe(RELEASE.tag)
  })

  it('直连失败→切镜像成功：调用两次，第二次用镜像 url，最终 resolve', async () => {
    getUrlsMock.mockReturnValue([DIRECT, MIRROR])
    // 第一次（直连）模拟 GFW RST 失败，第二次（镜像）成功
    downloadMock
      .mockRejectedValueOnce(new Error('Connection was reset'))
      .mockResolvedValueOnce({ skipped: false })

    const phases = []
    await downloadFromCandidates(RELEASE, (p) => phases.push(p), null)

    expect(downloadMock).toHaveBeenCalledTimes(2)
    expect(downloadMock.mock.calls[0][0].release.assetUrl).toBe(DIRECT)
    expect(downloadMock.mock.calls[1][0].release.assetUrl).toBe(MIRROR)
  })

  it('全部候选失败：抛最后一个错，遍历完所有源', async () => {
    getUrlsMock.mockReturnValue([DIRECT, MIRROR])
    downloadMock
      .mockRejectedValueOnce(new Error('direct reset'))
      .mockRejectedValueOnce(new Error('mirror reset'))

    await expect(downloadFromCandidates(RELEASE, () => {}, null)).rejects.toThrow('mirror reset')
    expect(downloadMock).toHaveBeenCalledTimes(2)
  })

  it('进入循环前已 aborted：抛 aborted，不调用下载', async () => {
    getUrlsMock.mockReturnValue([DIRECT, MIRROR])
    const ctrl = new AbortController()
    ctrl.abort()

    await expect(
      downloadFromCandidates(RELEASE, () => {}, ctrl.signal),
    ).rejects.toThrow(/aborted/)
    expect(downloadMock).not.toHaveBeenCalled()
  })

  it('首次失败后 signal 被 abort：不重试下一源，直接 rethrow 原始错误', async () => {
    getUrlsMock.mockReturnValue([DIRECT, MIRROR])
    const ctrl = new AbortController()
    // 直连失败，且失败时 signal 已被 abort（模拟 stop 在 RST 后介入）；
    // catch 里 signal.aborted 分支直接 rethrow 原始错误，不进入下一源
    downloadMock.mockImplementationOnce(() => {
      ctrl.abort()
      return Promise.reject(new Error('direct reset'))
    })

    await expect(
      downloadFromCandidates(RELEASE, () => {}, ctrl.signal),
    ).rejects.toThrow('direct reset')
    // signal.aborted → 不再尝试镜像
    expect(downloadMock).toHaveBeenCalledTimes(1)
  })

  it('onProgress 透传：下载层回调转发给外层 onDownload', async () => {
    getUrlsMock.mockReturnValue([DIRECT, MIRROR])
    downloadMock.mockImplementationOnce(async ({ onProgress }) => {
      onProgress?.({ phase: 'downloading', percent: 42 })
      onProgress?.({ phase: 'done', skipped: false })
      return { skipped: false }
    })

    const seen = []
    await downloadFromCandidates(RELEASE, (p) => seen.push(p), null)

    expect(seen).toEqual([
      { phase: 'downloading', percent: 42 },
      { phase: 'done', skipped: false },
    ])
  })

  it('候选源切换时 assetUrl 覆盖：每次传入的 release 是独立 spread（不污染原对象）', async () => {
    getUrlsMock.mockReturnValue([DIRECT, MIRROR])
    downloadMock
      .mockRejectedValueOnce(new Error('reset'))
      .mockResolvedValueOnce({ skipped: false })

    await downloadFromCandidates(RELEASE, () => {}, null)

    const first = downloadMock.mock.calls[0][0].release
    const second = downloadMock.mock.calls[1][0].release
    expect(first).not.toBe(second) // 独立对象
    expect(first.assetUrl).toBe(DIRECT)
    expect(second.assetUrl).toBe(MIRROR)
    // 原始 RELEASE 未被修改（spread 不写回）
    expect(RELEASE.assetUrl).toBe(DIRECT)
  })

  it('空候选列表：抛 no candidate sources，不调用下载', async () => {
    getUrlsMock.mockReturnValue([])

    await expect(downloadFromCandidates(RELEASE, () => {}, null)).rejects.toThrow(
      /no candidate sources/,
    )
    expect(downloadMock).not.toHaveBeenCalled()
  })

  it('单个候选（无镜像场景）：失败即抛，无下一源可切', async () => {
    getUrlsMock.mockReturnValue([DIRECT])
    downloadMock.mockRejectedValueOnce(new Error('reset'))

    await expect(downloadFromCandidates(RELEASE, () => {}, null)).rejects.toThrow('reset')
    expect(downloadMock).toHaveBeenCalledTimes(1)
  })
})
