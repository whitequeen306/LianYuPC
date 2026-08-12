import { describe, it, expect, vi, afterEach } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'

vi.mock('electron', () => ({
  app: { getPath: () => `${process.env.TEMP || process.env.TMPDIR || '/tmp'}/lianyu-engine-dl-test` },
  net: { request: () => { throw new Error('net.request should be injected in tests') } },
}))

vi.mock('extract-zip', () => ({ default: vi.fn() }))

import { ensureManagedEngine } from '../mcp/engineDownloader.js'
import { getEngineVersionDir, getEnginePartPath, readInstalledMeta } from '../mcp/engineRelease.js'

const userData = `${process.env.TEMP || process.env.TMPDIR || '/tmp'}/lianyu-engine-dl-test`

const sha = crypto.createHash('sha256').update('zip-bytes').digest('hex')
const manifest = {
  version: '0.1.0',
  url: 'AgentEngine-hosted-win-x64-0.1.0.zip',
  sha256: sha,
  size: 9,
}

function plantIntactInstall(version = '0.1.0') {
  const dir = getEngineVersionDir(version)
  fs.mkdirSync(path.join(dir, '_internal'), { recursive: true })
  fs.writeFileSync(path.join(dir, 'AgentEngine.exe'), 'mz')
}

afterEach(() => {
  fs.rmSync(path.join(userData, 'mcp-engine'), { recursive: true, force: true })
})

describe('ensureManagedEngine', () => {
  it('skips download when install is intact and sha matches', async () => {
    plantIntactInstall()
    const { writeInstalledMeta } = await import('../mcp/engineRelease.js')
    writeInstalledMeta({
      version: '0.1.0',
      sha256: sha,
      exeRelPath: 'v0.1.0/AgentEngine.exe',
      size: 9,
    })
    const downloadFn = vi.fn()
    const extractZip = vi.fn()
    const result = await ensureManagedEngine({
      manifest,
      downloadUrl: 'https://api.lianyu.test/AgentEngine-hosted-win-x64-0.1.0.zip',
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
    await expect(ensureManagedEngine({
      manifest,
      downloadUrl: 'https://api.lianyu.test/x.zip',
      downloadFn,
      extractZip: vi.fn(),
    })).rejects.toThrow(/sha256 mismatch/)
    expect(fs.existsSync(getEnginePartPath())).toBe(false)
  })

  it('extracts into version dir and writes meta on success', async () => {
    const payload = Buffer.from('zip-bytes')
    const downloadFn = vi.fn(async ({ destPath }) => {
      fs.mkdirSync(path.dirname(destPath), { recursive: true })
      fs.writeFileSync(destPath, payload)
      return { received: payload.length, total: payload.length }
    })
    const extractZip = vi.fn(async (_zip, { dir }) => {
      fs.mkdirSync(path.join(dir, '_internal'), { recursive: true })
      fs.writeFileSync(path.join(dir, 'AgentEngine.exe'), 'mz')
    })
    const result = await ensureManagedEngine({
      manifest: { ...manifest, size: payload.length },
      downloadUrl: 'https://api.lianyu.test/x.zip',
      downloadFn,
      extractZip,
    })
    expect(result.skipped).toBe(false)
    expect(result.version).toBe('0.1.0')
    expect(fs.existsSync(path.join(getEngineVersionDir('0.1.0'), 'AgentEngine.exe'))).toBe(true)
    const meta = readInstalledMeta()
    expect(meta.sha256).toBe(sha)
    expect(meta.exeRelPath).toBe('v0.1.0/AgentEngine.exe')
    expect(fs.existsSync(getEnginePartPath())).toBe(false)
  })
})
