import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import os from 'os'
import path from 'path'
import fs from 'fs'
import crypto from 'crypto'
import http from 'http'

// vi.mock 工厂被提升到顶部，无法直接闭包普通 const；用 vi.hoisted 拿到可被
// 工厂与测试体共享的 holder。electron→app.getPath 指向每用例临时目录，
// 使下载落盘 (.zip.part) 与安装根都在隔离的 tmp 下，互不污染。
const { state } = vi.hoisted(() => ({ state: { userData: '' } }))
vi.mock('electron', () => ({ app: { getPath: () => state.userData } }))

// 解压是第三方能力（extract-zip），非本模块编排职责；mock 为 noop 以免在单测里
// 构造真实 zip。状态机测试聚焦下载/校验/进度/中断/看门狗；真实解压由
// smokeNapcat.mjs 端到端覆盖。通过 holder 暴露 vi.fn 以断言"是否进入解压"。
const { extractMock } = vi.hoisted(() => ({ extractMock: { fn: null } }))
vi.mock('extract-zip', () => ({ default: (...args) => extractMock.fn?.(...args) }))

import { downloadAndExtractNapCat } from '../napcatDownloader.js'
import { getNapCatInstallRoot, wipeNapCatInstall } from '../napcatRelease.js'

let tmp, installRoot, server, sockets

/** 启动本地服务器：stall 模式发一块后永不结束（模拟网络 stall）；serve 模式整包返回。 */
function startServer({ buf, stall }) {
  const set = new Set()
  sockets = set // 供 stopServer 统一 destroy
  return new Promise((resolve) => {
    server = http.createServer((req, res) => {
      if (stall) {
        res.writeHead(200, { 'Content-Type': 'application/zip' })
        res.write(buf.subarray(0, Math.min(8192, buf.length)))
        return // 不 res.end() → 流不结束
      }
      res.writeHead(200, {
        'Content-Type': 'application/zip',
        'Content-Length': String(buf.length),
      })
      res.end(buf)
    })
    server.on('connection', (s) => {
      set.add(s)
      // 闭包捕获本服务器的 set，而非模块级 sockets——stopServer 置空 sockets 后，
      // 残留的 'close' 回调仍能安全 delete，避免空引用（修复 unhandled error）
      s.on('close', () => set.delete(s))
    })
    server.listen(0, '127.0.0.1', () => resolve({ port: server.address().port }))
  })
}

async function stopServer() {
  if (sockets) for (const s of sockets) s.destroy()
  sockets = null
  if (server) {
    await new Promise((r) => server.close(() => r()))
    server = null
  }
}

beforeEach(() => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'lianyu-napcat-dl-'))
  state.userData = tmp
  installRoot = getNapCatInstallRoot()
  server = null
  sockets = null
  extractMock.fn = vi.fn(async () => {})
})
afterEach(async () => {
  await stopServer()
  fs.rmSync(tmp, { recursive: true, force: true })
})

function sha256hex(buf) {
  return crypto.createHash('sha256').update(buf).digest('hex')
}

describe('downloadAndExtractNapCat 状态机', () => {
  it('release 缺 assetUrl/sha256 时立即拒绝（不发请求、不触盘）', async () => {
    await expect(
      downloadAndExtractNapCat({ release: { assetUrl: 'http://127.0.0.1:1/x' } })
    ).rejects.toThrow(/missing assetUrl\/sha256/)
    await expect(
      downloadAndExtractNapCat({ release: { sha256: '0'.repeat(64) } })
    ).rejects.toThrow(/missing assetUrl\/sha256/)
  })

  it('已安装则跳过：仅发 done{skipped:true}，不发起请求', async () => {
    fs.mkdirSync(installRoot, { recursive: true })
    fs.writeFileSync(path.join(installRoot, 'NapCatWinBootMain.exe'), '') // 占位入口
    const phases = []
    const r = await downloadAndExtractNapCat({
      release: { assetUrl: 'http://127.0.0.1:1/never-hit', sha256: '0'.repeat(64), size: 1 },
      onProgress: (p) => phases.push(p),
    })
    expect(r.skipped).toBe(true)
    expect(phases).toHaveLength(1)
    expect(phases[0]).toMatchObject({ phase: 'done', skipped: true })
  })

  it('sha256 不符：删档并抛 mismatch（不进入解压）', async () => {
    const buf = Buffer.alloc(2048, 7)
    const { port } = await startServer({ buf })
    const release = {
      assetUrl: `http://127.0.0.1:${port}/n.zip`,
      sha256: '0'.repeat(64), // 故意错误
      size: buf.length,
    }
    await expect(downloadAndExtractNapCat({ release })).rejects.toThrow(/sha256 mismatch/)
    expect(extractMock.fn).not.toHaveBeenCalled()
    expect(fs.existsSync(`${installRoot}.zip.part`)).toBe(false) // 校验失败删档
  })

  it('成功路径：downloading→extracting→done{skipped:false}，进度形状与 .part 清理', async () => {
    const buf = Buffer.alloc(4096, 9)
    const { port } = await startServer({ buf })
    const release = {
      assetUrl: `http://127.0.0.1:${port}/n.zip`,
      sha256: sha256hex(buf),
      size: buf.length,
    }
    const phases = []
    const r = await downloadAndExtractNapCat({ release, onProgress: (p) => phases.push(p) })
    expect(r.skipped).toBe(false)
    const seq = phases.map((p) => p.phase)
    expect(seq).toContain('downloading')
    expect(seq).toContain('extracting')
    expect(seq[seq.length - 1]).toBe('done')
    expect(phases[phases.length - 1]).toMatchObject({ phase: 'done', skipped: false })
    // 进度形状：初始 downloading 携带 total、percent 有界（localhost 即瞬时也至少一次）
    const dl = phases.filter((p) => p.phase === 'downloading')
    expect(dl.length).toBeGreaterThanOrEqual(1)
    expect(dl[0].total).toBe(buf.length)
    for (const p of dl) expect(p.percent).toBeLessThanOrEqual(100)
    expect(extractMock.fn).toHaveBeenCalledTimes(1)
    expect(fs.existsSync(`${installRoot}.zip.part`)).toBe(false)
  })

  it('外部 signal abort：快速拒绝且非 stall 路径', async () => {
    const buf = Buffer.alloc(8192, 1)
    const { port } = await startServer({ buf, stall: true })
    const release = {
      assetUrl: `http://127.0.0.1:${port}/n.zip`,
      sha256: '0'.repeat(64),
      size: 999999,
    }
    const ctrl = new AbortController()
    setTimeout(() => ctrl.abort(), 300)
    const start = Date.now()
    let err
    try {
      await downloadAndExtractNapCat({ release, signal: ctrl.signal })
    } catch (e) {
      err = e
    }
    const dur = Date.now() - start
    expect(err).toBeTruthy()
    expect(String(err?.message || err)).not.toMatch(/stalled/)
    expect(dur).toBeLessThan(4000)
    // 中断后 .part 句柄释放：可删除（不抛 EBUSY）
    expect(() => fs.rmSync(`${installRoot}.zip.part`, { force: true })).not.toThrow()
  })

  it('无进度看门狗：stallMs 后抛 stalled', async () => {
    const buf = Buffer.alloc(8192, 1)
    const { port } = await startServer({ buf, stall: true })
    const release = {
      assetUrl: `http://127.0.0.1:${port}/n.zip`,
      sha256: '0'.repeat(64),
      size: 999999,
    }
    const start = Date.now()
    let err
    try {
      await downloadAndExtractNapCat({ release, stallMs: 200 })
    } catch (e) {
      err = e
    }
    const dur = Date.now() - start
    expect(err).toBeTruthy()
    expect(String(err?.message || err)).toMatch(/stalled/)
    expect(dur).toBeLessThan(4000)
  })
})
