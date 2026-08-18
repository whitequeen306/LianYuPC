import { describe, it, expect, vi, afterEach } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

vi.mock('electron', () => ({
  app: { getPath: () => `${process.env.TEMP || process.env.TMPDIR || '/tmp'}/lianyu-wechat-rel-test` },
}))

import {
  parseWechatChannelLatestYml,
  isSafeWechatChannelAssetFilename,
  resolveWechatChannelAssetUrl,
  findWechatChannelLaunch,
  isWechatChannelInstallIntact,
  writeWechatChannelInstalledMeta,
  readWechatChannelInstalledMeta,
  getWechatChannelVersionDir,
  getWechatChannelRoot,
} from '../wechatChannelRelease.js'

afterEach(() => {
  fs.rmSync(getWechatChannelRoot(), { recursive: true, force: true })
})

describe('parseWechatChannelLatestYml', () => {
  const sha = 'a'.repeat(64)
  const good = [
    'version: 0.1.0',
    'url: WechatChannel-win-x64-0.1.0.zip',
    `sha256: ${sha}`,
    'size: 4242',
  ].join('\n')

  it('parses a valid manifest', () => {
    expect(parseWechatChannelLatestYml(good)).toEqual({
      version: '0.1.0',
      url: 'WechatChannel-win-x64-0.1.0.zip',
      sha256: sha,
      size: 4242,
    })
  })

  it('rejects path traversal and absolute urls in url', () => {
    expect(isSafeWechatChannelAssetFilename('../evil.zip')).toBe(false)
    expect(isSafeWechatChannelAssetFilename('https://evil.example/x.zip')).toBe(false)
    expect(isSafeWechatChannelAssetFilename('WechatChannel-win-x64-0.1.0.zip')).toBe(true)
    const traversed = good.replace(
      'WechatChannel-win-x64-0.1.0.zip',
      '../WechatChannel-win-x64-0.1.0.zip',
    )
    expect(parseWechatChannelLatestYml(traversed)).toBeNull()
  })

  it('rejects bad version / sha / oversized size', () => {
    expect(parseWechatChannelLatestYml(good.replace('0.1.0', 'v0.1.0'))).toBeNull()
    expect(parseWechatChannelLatestYml(good.replace(sha, 'abc'))).toBeNull()
    expect(parseWechatChannelLatestYml(good.replace('4242', '999999999999'))).toBeNull()
  })
})

describe('resolveWechatChannelAssetUrl', () => {
  it('resolves relative filename against the manifest url', () => {
    const url = resolveWechatChannelAssetUrl(
      'WechatChannel-win-x64-0.1.0.zip',
      'https://api.lianyu.test/api/public/files/updates/wechat-channel-latest.yml',
    )
    expect(url).toBe('https://api.lianyu.test/api/public/files/updates/WechatChannel-win-x64-0.1.0.zip')
  })

  it('uses updateOrigin for relative names', () => {
    const url = resolveWechatChannelAssetUrl(
      'WechatChannel-win-x64-0.1.0.zip',
      'https://api.lianyu.test/api/public/files/updates/wechat-channel-latest.yml',
      'https://cdn.lianyu.test',
    )
    expect(url).toBe('https://cdn.lianyu.test/WechatChannel-win-x64-0.1.0.zip')
  })

  it('throws on unsafe filenames', () => {
    expect(() => resolveWechatChannelAssetUrl('../x.zip', 'https://api.lianyu.test/wechat-channel-latest.yml')).toThrow()
  })
})

describe('findWechatChannelLaunch / intact', () => {
  it('finds node.exe and host.mjs at zip root', () => {
    const root = getWechatChannelVersionDir('0.1.0')
    fs.mkdirSync(root, { recursive: true })
    fs.writeFileSync(path.join(root, 'node.exe'), 'mz')
    fs.writeFileSync(path.join(root, 'host.mjs'), '// host')
    expect(findWechatChannelLaunch(root)?.hostPath).toBe(path.join(root, 'host.mjs'))
    expect(isWechatChannelInstallIntact(root)).toBe(true)
  })

  it('finds launch one directory down', () => {
    const root = getWechatChannelVersionDir('0.1.0')
    const sub = path.join(root, 'payload')
    fs.mkdirSync(sub, { recursive: true })
    fs.writeFileSync(path.join(sub, 'node.exe'), 'mz')
    fs.writeFileSync(path.join(sub, 'host.mjs'), '// host')
    expect(findWechatChannelLaunch(root)?.cwd).toBe(sub)
    expect(isWechatChannelInstallIntact(root)).toBe(true)
  })

  it('is not intact without host.mjs', () => {
    const root = getWechatChannelVersionDir('0.1.0')
    fs.mkdirSync(root, { recursive: true })
    fs.writeFileSync(path.join(root, 'node.exe'), 'mz')
    expect(isWechatChannelInstallIntact(root)).toBe(false)
  })
})

describe('installed meta', () => {
  it('round-trips version and sha256', () => {
    fs.mkdirSync(getWechatChannelRoot(), { recursive: true })
    writeWechatChannelInstalledMeta({
      version: '0.1.0',
      sha256: 'B'.repeat(64),
      cwdRelPath: 'v0.1.0',
      size: 12,
    })
    const meta = readWechatChannelInstalledMeta()
    expect(meta.version).toBe('0.1.0')
    expect(meta.sha256).toBe('b'.repeat(64))
    expect(meta.cwdRelPath).toBe('v0.1.0')
  })
})

describe('api-gateway wechat channel updates', () => {
  const nginx = fs.readFileSync(
    path.join(path.dirname(fileURLToPath(import.meta.url)), '../../../../deploy/api-gateway/nginx.conf'),
    'utf8',
  )

  it('proxies the wechat channel manifest and zip, and still 404s unknown updates', () => {
    expect(nginx).toContain('location = /api/public/files/updates/wechat-channel-latest.yml')
    expect(nginx).toContain('location ~ ^/api/public/files/updates/WechatChannel-win-x64-[0-9]+\\.[0-9]+\\.[0-9]+\\.zip$')
    expect(nginx).toMatch(/location \/api\/public\/files\/updates\/ \{\s+return 404;/)
  })
})
