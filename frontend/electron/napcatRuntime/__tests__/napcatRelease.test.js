import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import fs from 'fs'
import os from 'os'
import path from 'path'

// electron app.getPath 指向可控 tmp 目录，使缓存落盘到隔离位置
const { state } = vi.hoisted(() => ({ state: { userData: '' } }))
vi.mock('electron', () => ({ app: { getPath: () => state.userData } }))

import { resolveLatestNapCatRelease, compareReleaseTags, getAssetDownloadUrls, isNapCatInstallIntact } from '../napcatRelease.js'

// 与 PINNED_RELEASES 内置锚点一致的 out-of-band 期望值
const PINNED_TAG = 'v4.18.7'
const PINNED_SHA = '628621ac6333b7c016c1ef213495af39c31ce9c4ce2b8b041ec47b0d8557a3e1'
const PINNED_URL = 'https://github.com/NapNeko/NapCatQQ/releases/download/v4.18.7/NapCat.Shell.zip'
const ASSET = 'NapCat.Shell.zip'

let fetchMock
let tmp

beforeEach(() => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'lianyu-napcat-release-'))
  state.userData = tmp
  fetchMock = vi.fn()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
  try {
    fs.rmSync(tmp, { recursive: true, force: true })
  } catch {
    /* ignore */
  }
})

function githubJson({ tag, sha, url, size = 29425972 }) {
  return {
    tag_name: tag,
    assets: [{ name: ASSET, digest: `sha256:${sha}`, browser_download_url: url, size }],
  }
}
function mockOk(json) {
  fetchMock.mockResolvedValueOnce({ ok: true, status: 200, json: async () => json })
}
function mockThrow() {
  fetchMock.mockRejectedValueOnce(new Error('network down'))
}
// 模拟受限网络下 fetch 既不 resolve 也不 reject（GFW 丢包：连接已建无响应），
// 仅当传入 signal 被 abort 时才 reject——还原真实 undici fetch 的 abort 语义
function mockHang() {
  fetchMock.mockImplementationOnce((_url, opts) => new Promise((_resolve, reject) => {
    opts?.signal?.addEventListener('abort', () => reject(new Error('The operation was aborted')), { once: true })
  }))
}
function cacheFile() {
  return path.join(tmp, 'napcat-release-cache.json')
}
function writeCache(obj) {
  fs.writeFileSync(cacheFile(), JSON.stringify(obj))
}
function readCache() {
  try {
    return JSON.parse(fs.readFileSync(cacheFile(), 'utf8'))
  } catch {
    return null
  }
}

describe('compareReleaseTags', () => {
  it('相等返回 0', () => {
    expect(compareReleaseTags('v4.18.7', 'v4.18.7')).toBe(0)
  })
  it('严格新于返回 1', () => {
    expect(compareReleaseTags('v4.20.0', 'v4.18.7')).toBe(1)
  })
  it('严格旧于返回 -1', () => {
    expect(compareReleaseTags('v4.18.7', 'v4.20.0')).toBe(-1)
  })
  it('正式版新于同主干预发布（防把预发布误判为可升级）', () => {
    expect(compareReleaseTags('v4.18.7', 'v4.18.7-beta')).toBe(1)
  })
  it('忽略前导 v', () => {
    expect(compareReleaseTags('4.18.7', 'v4.18.7')).toBe(0)
  })
})

describe('resolveLatestNapCatRelease — out-of-band digest pin（issue #5）', () => {
  it('在线命中锚点：用 out-of-band 锚点 url/sha（非在线返回），并写缓存', async () => {
    // MITM 把 assetUrl 改成攻击者域名，但 tag+sha 仍命中锚点 → 必须用锚点 url
    mockOk(githubJson({ tag: PINNED_TAG, sha: PINNED_SHA, url: 'https://attacker.example/evil.zip' }))
    const r = await resolveLatestNapCatRelease({ force: true })
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(r.tag).toBe(PINNED_TAG)
    expect(r.assetUrl).toBe(PINNED_URL) // 锚点 url，非 attacker
    expect(r.sha256).toBe(PINNED_SHA)
    expect(r.fetchedAt).toBeGreaterThan(0)
    const cache = readCache()
    expect(cache?.assetUrl).toBe(PINNED_URL)
    expect(cache?.sha256).toBe(PINNED_SHA)
  })

  it('在线 tag 命中但 sha 被伪造（MITM RCE 向量）：拒信在线，回退锚点，不写缓存', async () => {
    mockOk(
      githubJson({ tag: PINNED_TAG, sha: 'deadbeef'.repeat(8), url: 'https://attacker.example/evil.zip' }),
    )
    const r = await resolveLatestNapCatRelease({ force: true })
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(r.tag).toBe(PINNED_TAG)
    expect(r.sha256).toBe(PINNED_SHA) // 锚点 sha，非伪造
    expect(r.assetUrl).toBe(PINNED_URL)
    expect(r.fetchedAt).toBe(0) // 未写缓存
    expect(readCache()).toBeNull()
  })

  it('在线版本未被 pin（新版/未追锚点）：回退锚点，不写缓存', async () => {
    mockOk(
      githubJson({ tag: 'v9.9.9', sha: 'a'.repeat(64), url: 'https://github.com/.../v9.9.9/...zip' }),
    )
    const r = await resolveLatestNapCatRelease({ force: true })
    expect(r.tag).toBe(PINNED_TAG) // 回退到锚点
    expect(r.fetchedAt).toBe(0)
    expect(readCache()).toBeNull()
  })

  it('网络失败且无缓存：回退锚点', async () => {
    mockThrow()
    const r = await resolveLatestNapCatRelease({ force: true })
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(r.tag).toBe(PINNED_TAG)
    expect(r.sha256).toBe(PINNED_SHA)
    expect(r.fetchedAt).toBe(0)
  })

  it('缓存命中锚点且未过期：直接复用，不触发 fetch', async () => {
    writeCache({
      tag: PINNED_TAG,
      assetName: ASSET,
      assetUrl: PINNED_URL,
      sha256: PINNED_SHA,
      size: 29425972,
      fetchedAt: Date.now(),
    })
    const r = await resolveLatestNapCatRelease()
    expect(fetchMock).not.toHaveBeenCalled()
    expect(r.assetUrl).toBe(PINNED_URL)
    expect(r.sha256).toBe(PINNED_SHA)
  })

  it('缓存未命中锚点（修前残留的伪造 digest）：跳过缓存，重试在线', async () => {
    // 修前写入的伪造 digest 缓存，即便 fetchedAt 新，也不得被静默复用
    writeCache({
      tag: PINNED_TAG,
      assetUrl: 'https://attacker.example/evil.zip',
      sha256: 'deadbeef'.repeat(8),
      size: 1,
      fetchedAt: Date.now(),
    })
    mockOk(githubJson({ tag: PINNED_TAG, sha: PINNED_SHA, url: PINNED_URL }))
    const r = await resolveLatestNapCatRelease()
    expect(fetchMock).toHaveBeenCalledTimes(1) // 缓存被跳过
    expect(r.sha256).toBe(PINNED_SHA) // 锚点 sha 覆盖伪造
    expect(r.assetUrl).toBe(PINNED_URL)
  })

  it('fetch 超时（受限网络 hang）：到点 abort 回退锚点，不永久阻塞', async () => {
    // GitHub API 在受限网络下可能 hang（不 resolve 不 reject），若无超时 doStart 会
    // 永久卡在 'resolving-release'，下载无从进入、UI 收不到进度——超时即回退锚点
    mockHang()
    const start = Date.now()
    const r = await resolveLatestNapCatRelease({ force: true, fetchTimeoutMs: 60 })
    const dur = Date.now() - start
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(r.tag).toBe(PINNED_TAG)
    expect(r.sha256).toBe(PINNED_SHA)
    expect(r.fetchedAt).toBe(0) // 回退锚点不写缓存
    expect(dur).toBeLessThan(2000) // 超时即回退，不永久挂起
  })
})

describe('getAssetDownloadUrls — 镜像回退（受限网络 CDN RST 兜底）', () => {
  it('github.com 资产：直连在前，镜像在后', () => {
    const urls = getAssetDownloadUrls({ assetUrl: PINNED_URL })
    // 直连优先（非受限网络更快），受限网络下直连被 RST 后切到镜像
    expect(urls[0]).toBe(PINNED_URL)
    expect(urls.length).toBeGreaterThan(1)
    // 镜像是「前缀 + 直连 url」形式，均以直连 url 结尾，且与直连不同
    for (const u of urls.slice(1)) {
      expect(u.endsWith(PINNED_URL)).toBe(true)
      expect(u).not.toBe(PINNED_URL)
      expect(u.length).toBeGreaterThan(PINNED_URL.length)
    }
    expect(new Set(urls).size).toBe(urls.length) // 无重复
  })

  it('非 github 资产（本地/测试地址）：仅直连，不追加镜像', () => {
    const local = 'http://127.0.0.1:8080/NapCat.Shell.zip'
    const urls = getAssetDownloadUrls({ assetUrl: local })
    expect(urls).toEqual([local])
  })

  it('缺 assetUrl：返回单元素 undefined 列表（不抛错，交由下载层拒绝）', () => {
    const urls = getAssetDownloadUrls({})
    expect(urls).toEqual([undefined])
    expect(getAssetDownloadUrls(null)).toEqual([undefined])
    expect(getAssetDownloadUrls(undefined)).toEqual([undefined])
  })
})

describe('isNapCatInstallIntact — 安装完整性校验', () => {
  function seedDir(dir, files) {
    fs.mkdirSync(dir, { recursive: true })
    for (const [name, content] of Object.entries(files)) {
      const p = path.join(dir, name)
      fs.mkdirSync(path.dirname(p), { recursive: true })
      fs.writeFileSync(p, content)
    }
  }

  it('完整安装：exe + napcat.mjs + conout-*.js 全在 → true', () => {
    const dir = path.join(tmp, 'napcat')
    seedDir(dir, {
      'NapCatWinBootMain.exe': 'fake',
      'napcat.mjs': 'fake',
      'conout-wiJ7YKRd.js': 'fake',
    })
    expect(isNapCatInstallIntact(dir)).toBe(true)
  })

  it('缺失 napcat.mjs → false', () => {
    const dir = path.join(tmp, 'napcat')
    seedDir(dir, {
      'NapCatWinBootMain.exe': 'fake',
      'conout-wiJ7YKRd.js': 'fake',
    })
    expect(isNapCatInstallIntact(dir)).toBe(false)
  })

  it('缺失 conout-*.js → false', () => {
    const dir = path.join(tmp, 'napcat')
    seedDir(dir, {
      'NapCatWinBootMain.exe': 'fake',
      'napcat.mjs': 'fake',
    })
    expect(isNapCatInstallIntact(dir)).toBe(false)
  })

  it('缺失 NapCatWinBootMain.exe → false', () => {
    const dir = path.join(tmp, 'napcat')
    seedDir(dir, {
      'napcat.mjs': 'fake',
      'conout-wiJ7YKRd.js': 'fake',
    })
    expect(isNapCatInstallIntact(dir)).toBe(false)
  })

  it('napcat.mjs 在 napcat/ 子目录 → true（兼容 Shell zip 布局）', () => {
    const dir = path.join(tmp, 'napcat')
    seedDir(dir, {
      'NapCatWinBootMain.exe': 'fake',
      'napcat/napcat.mjs': 'fake',
      'conout-wiJ7YKRd.js': 'fake',
    })
    expect(isNapCatInstallIntact(dir)).toBe(true)
  })

  it('conout-*.js 文件名含随机 hash 仍能匹配 → true', () => {
    const dir = path.join(tmp, 'napcat')
    seedDir(dir, {
      'NapCatWinBootMain.exe': 'fake',
      'napcat.mjs': 'fake',
      'conout-aBcDeF12.js': 'fake',
    })
    expect(isNapCatInstallIntact(dir)).toBe(true)
  })

  it('空目录 → false', () => {
    const dir = path.join(tmp, 'napcat')
    fs.mkdirSync(dir, { recursive: true })
    expect(isNapCatInstallIntact(dir)).toBe(false)
  })
})
