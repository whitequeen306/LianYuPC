import { describe, it, expect, beforeAll, afterAll, vi } from 'vitest'
import fs from 'fs'
import os from 'os'
import path from 'path'

// 真实网络 e2e：验证 NapCat 下载链路在受限网络（GitHub CDN RST）下能靠镜像兜底下完。
// 与单测的区别：不 stub fetch（用 Node 原生 undici，不走系统代理 → 直连 GitHub 触发 GFW RST），
// 真实拉 29MB Shell 整包 + sha256 锚点校验。mock 仅 electron（app.getPath→tmp）与 extract-zip
// （不解压真 zip，聚焦验证下载完整性；sha256 通过即 finalizeDownload 调 extract）。
const { state } = vi.hoisted(() => ({ state: { userData: '' } }))
vi.mock('electron', () => ({ app: { getPath: () => state.userData } }))
const { extractMock } = vi.hoisted(() => ({ extractMock: { fn: null } }))
vi.mock('extract-zip', () => ({ default: (...args) => extractMock.fn?.(...args) }))

import { downloadFromCandidates } from '../napcatHost.js'
import { resolveLatestNapCatRelease, getAssetDownloadUrls, locateNapCatEntry, getNapCatInstallRoot } from '../napcatRelease.js'

const PINNED_SHA = '628621ac6333b7c016c1ef213495af39c31ce9c4ce2b8b041ec47b0d8557a3e1'

let tmp
beforeAll(() => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'lianyu-net-e2e-'))
  state.userData = tmp
  extractMock.fn = vi.fn(async () => {})
})
afterAll(() => {
  try { fs.rmSync(tmp, { recursive: true, force: true }) } catch { /* ignore */ }
})

describe('NapCat 下载链路 e2e（真实网络 / GFW RST → 镜像兜底）', () => {
  it('resolveLatestNapCatRelease：在线解析或回退锚点，最终拿到可信 release', async () => {
    // GitHub API 在受限网络下可能超时/RST，但 12s 超时回退锚点保证拿到 release
    const r = await resolveLatestNapCatRelease({ force: true, fetchTimeoutMs: 15000 })
    expect(r.tag).toBe('v4.18.7')
    expect(r.sha256).toBe(PINNED_SHA)
    expect(r.assetUrl).toMatch(/github\.com.*Shell\.zip$/)
    expect(r.size).toBe(29425972)
  }, 60000)

  it('getAssetDownloadUrls：直连在前，ghproxy 镜像在后', () => {
    const r = getAssetDownloadUrls({ assetUrl: 'https://github.com/NapNeko/NapCatQQ/releases/download/v4.18.7/NapCat.Shell.zip' })
    expect(r.length).toBeGreaterThanOrEqual(2)
    expect(r[0]).toMatch(/^https:\/\/github\.com\//)
    expect(r.some((u) => u.includes('ghproxy.net'))).toBe(true)
  })

  it('downloadFromCandidates：真实下载 Shell，sha256 锚点校验通过（直连失败则切镜像）', async () => {
    const release = await resolveLatestNapCatRelease({ force: true, fetchTimeoutMs: 15000 })
    const phases = []
    const t0 = Date.now()
    await downloadFromCandidates(release, (p) => phases.push(p), null)
    const dur = Date.now() - t0

    // sha256 校验通过才会进入 extract（finalizeDownload 在校验后调 extract）
    expect(extractMock.fn).toHaveBeenCalledTimes(1)
    // 进度相位序列：至少经历 downloading，最终 done
    const seq = phases.map((p) => p.phase)
    expect(seq).toContain('downloading')
    expect(seq[seq.length - 1]).toBe('done')
    // done 携带的 total 与锚点 size 一致（证明下的是完整 29MB Shell，非残片）
    const done = phases.find((p) => p.phase === 'done')
    expect(done).toBeTruthy()
    // 落盘的 .part 在校验+解压后已清理
    const installRoot = getNapCatInstallRoot()
    expect(fs.existsSync(`${installRoot}.zip.part`)).toBe(false)
    console.log(`[e2e] 下载耗时 ${dur}ms，相位序列: ${seq.join('→')}`)
    console.log(`[e2e] 进度采样:`, phases.filter((p) => p.phase === 'downloading').slice(-3))
  }, 300000)
})
