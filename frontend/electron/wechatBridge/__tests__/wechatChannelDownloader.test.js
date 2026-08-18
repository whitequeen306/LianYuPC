import { describe, it, expect, vi, afterEach } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'

vi.mock('electron', () => ({
  app: { getPath: () => `${process.env.TEMP || process.env.TMPDIR || '/tmp'}/lianyu-wechat-dl-test` },
  net: { request: () => { throw new Error('net.request should be injected in tests') } },
}))

vi.mock('extract-zip', () => ({ default: vi.fn() }))

import { ensureWechatChannelRuntime, wipeWechatChannelInstall } from '../wechatChannelDownloader.js'
import {
  getWechatChannelVersionDir,
  getWechatChannelPartPath,
  getWechatChannelRoot,
  writeWechatChannelInstalledMeta,
  readWechatChannelInstalledMeta,
} from '../wechatChannelRelease.js'

const userData = `${process.env.TEMP || process.env.TMPDIR || '/tmp'}/lianyu-wechat-dl-test`
const sha = crypto.createHash('sha256').update('zip-bytes').digest('hex')
const manifest = {
  version: '0.1.0',
  url: 'WechatChannel-win-x64-0.1.0.zip',
  sha256: sha,
  size: 9,
}

function plantIntactInstall(version = '0.1.0') {
  const dir = getWechatChannelVersionDir(version)
  fs.mkdirSync(dir, { recursive: true })
  fs.writeFileSync(path.join(dir, 'node.exe'), 'mz')
  fs.writeFileSync(path.join(dir, 'host.mjs'), '// host')
}

afterEach(() => {
  fs.rmSync(path.join(userData, 'wechat-channel'), { recursive: true, force: true })
})

describe('ensureWechatChannelRuntime', () => {
  it('skips download when install is intact and sha matches', async () => {
    plantIntactInstall()
    writeWechatChannelInstalledMeta({
      version: '0.1.0',
      sha256: sha,
      cwdRelPath: 'v0.1.0',
      size: 9,
    })
    const downloadFn = vi.fn()
    const extractZip = vi.fn()
    const result = await ensureWechatChannelRuntime({
      manifest,
      downloadUrl: 'https://api.lianyu.test/WechatChannel-win-x64-0.1.0.zip',
      downloadFn,
      extractZip,
    })
    expect(result.skipped).toBe(true)
    expect(downloadFn).not.toHaveBeenCalled()
    expect(extractZip).not.toHaveBeenCalled()
  })

  it('rejects sha256 mismatch and deletes the part file', async () => {
    const downloadFn = vi.fn(async ({ destPath }) => {
      fs.mkdirSync(path.dirname(destPath), { recursive: true })
      fs.writeFileSync(destPath, 'tampered!')
      return { received: 9, total: 9 }
    })
    await expect(ensureWechatChannelRuntime({
      manifest,
      downloadUrl: 'https://api.lianyu.test/x.zip',
      downloadFn,
      extractZip: vi.fn(),
    })).rejects.toThrow(/sha256 mismatch/)
    expect(fs.existsSync(getWechatChannelPartPath())).toBe(false)
  })

  it('extracts into version dir and writes meta on success', async () => {
    const payload = Buffer.from('zip-bytes')
    const downloadFn = vi.fn(async ({ destPath }) => {
      fs.mkdirSync(path.dirname(destPath), { recursive: true })
      fs.writeFileSync(destPath, payload)
      return { received: payload.length, total: payload.length }
    })
    const extractZip = vi.fn(async (_zip, { dir }) => {
      fs.mkdirSync(dir, { recursive: true })
      fs.writeFileSync(path.join(dir, 'node.exe'), 'mz')
      fs.writeFileSync(path.join(dir, 'host.mjs'), '// host')
    })
    const result = await ensureWechatChannelRuntime({
      manifest: { ...manifest, size: payload.length },
      downloadUrl: 'https://api.lianyu.test/x.zip',
      downloadFn,
      extractZip,
    })
    expect(result.skipped).toBe(false)
    expect(fs.existsSync(path.join(getWechatChannelVersionDir('0.1.0'), 'host.mjs'))).toBe(true)
    const meta = readWechatChannelInstalledMeta()
    expect(meta.version).toBe('0.1.0')
    expect(meta.sha256).toBe(sha)
  })

  it('wipe keeps credentials and sync state', () => {
    const root = getWechatChannelRoot()
    fs.mkdirSync(root, { recursive: true })
    fs.writeFileSync(path.join(root, 'credentials.json'), '{"botToken":"x"}')
    fs.writeFileSync(path.join(root, 'sync-state.json'), '{"getUpdatesBuf":"1"}')
    fs.writeFileSync(path.join(root, 'installed.json'), '{}')
    wipeWechatChannelInstall()
    expect(fs.existsSync(path.join(root, 'credentials.json'))).toBe(true)
    expect(fs.existsSync(path.join(root, 'sync-state.json'))).toBe(true)
    expect(fs.existsSync(path.join(root, 'installed.json'))).toBe(false)
  })
})
