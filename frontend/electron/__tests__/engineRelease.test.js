import { describe, it, expect, vi, afterEach } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

vi.mock('electron', () => ({
  app: { getPath: () => `${process.env.TEMP || process.env.TMPDIR || '/tmp'}/lianyu-engine-rel-test` },
}))

import {
  parseAgentLatestYml,
  isSafeEngineAssetFilename,
  resolveEngineAssetUrl,
  findEngineExe,
  isEngineInstallIntact,
  writeInstalledMeta,
  readInstalledMeta,
  getEngineVersionDir,
  getEngineRoot,
} from '../mcp/engineRelease.js'

afterEach(() => {
  fs.rmSync(getEngineRoot(), { recursive: true, force: true })
})

describe('parseAgentLatestYml', () => {
  const sha = 'a'.repeat(64)
  const good = [
    'version: 0.1.0',
    'url: AgentEngine-hosted-win-x64-0.1.0.zip',
    `sha256: ${sha}`,
    'size: 66838221',
  ].join('\n')

  it('parses a valid manifest', () => {
    expect(parseAgentLatestYml(good)).toEqual({
      version: '0.1.0',
      url: 'AgentEngine-hosted-win-x64-0.1.0.zip',
      sha256: sha,
      size: 66838221,
    })
  })

  it('rejects path traversal and absolute urls in url', () => {
    expect(isSafeEngineAssetFilename('../evil.zip')).toBe(false)
    expect(isSafeEngineAssetFilename('https://evil.example/x.zip')).toBe(false)
    expect(isSafeEngineAssetFilename('AgentEngine-hosted-win-x64-0.1.0.zip')).toBe(true)
    const traversed = good.replace(
      'AgentEngine-hosted-win-x64-0.1.0.zip',
      '../AgentEngine-hosted-win-x64-0.1.0.zip',
    )
    expect(parseAgentLatestYml(traversed)).toBeNull()
  })

  it('rejects bad version / sha / oversized size', () => {
    expect(parseAgentLatestYml(good.replace('0.1.0', 'v0.1.0'))).toBeNull()
    expect(parseAgentLatestYml(good.replace(sha, 'abc'))).toBeNull()
    expect(parseAgentLatestYml(good.replace('66838221', '999999999999'))).toBeNull()
  })
})

describe('resolveEngineAssetUrl', () => {
  it('resolves relative filename against the manifest url', () => {
    const url = resolveEngineAssetUrl(
      'AgentEngine-hosted-win-x64-0.1.0.zip',
      'https://api.lianyu.test/api/public/files/updates/agent-latest.yml',
    )
    expect(url).toBe('https://api.lianyu.test/api/public/files/updates/AgentEngine-hosted-win-x64-0.1.0.zip')
  })

  it('uses updateOrigin for relative names', () => {
    const url = resolveEngineAssetUrl(
      'AgentEngine-hosted-win-x64-0.1.0.zip',
      'https://api.lianyu.test/api/public/files/updates/agent-latest.yml',
      'https://cdn.lianyu.test',
    )
    expect(url).toBe('https://cdn.lianyu.test/AgentEngine-hosted-win-x64-0.1.0.zip')
  })

  it('throws on unsafe filenames', () => {
    expect(() => resolveEngineAssetUrl('../x.zip', 'https://api.lianyu.test/agent-latest.yml')).toThrow()
  })
})

describe('findEngineExe / intact', () => {
  it('finds exe at zip root next to _internal', () => {
    const root = getEngineVersionDir('0.1.0')
    fs.mkdirSync(path.join(root, '_internal'), { recursive: true })
    fs.writeFileSync(path.join(root, 'AgentEngine.exe'), 'mz')
    expect(findEngineExe(root)).toBe(path.join(root, 'AgentEngine.exe'))
    expect(isEngineInstallIntact(root)).toBe(true)
  })

  it('finds exe one directory down and ignores _internal', () => {
    const root = getEngineVersionDir('0.1.0')
    fs.mkdirSync(path.join(root, '_internal'), { recursive: true })
    fs.mkdirSync(path.join(root, 'AgentEngine', '_internal'), { recursive: true })
    fs.writeFileSync(path.join(root, 'AgentEngine', 'AgentEngine.exe'), 'mz')
    expect(findEngineExe(root)).toBe(path.join(root, 'AgentEngine', 'AgentEngine.exe'))
    expect(isEngineInstallIntact(root)).toBe(true)
  })

  it('is not intact without _internal', () => {
    const root = getEngineVersionDir('0.1.0')
    fs.mkdirSync(root, { recursive: true })
    fs.writeFileSync(path.join(root, 'AgentEngine.exe'), 'mz')
    expect(isEngineInstallIntact(root)).toBe(false)
  })
})

describe('installed meta', () => {
  it('round-trips version and sha256', () => {
    fs.mkdirSync(getEngineRoot(), { recursive: true })
    writeInstalledMeta({
      version: '0.1.0',
      sha256: 'B'.repeat(64),
      exeRelPath: 'v0.1.0/AgentEngine.exe',
      size: 12,
    })
    const meta = readInstalledMeta()
    expect(meta.version).toBe('0.1.0')
    expect(meta.sha256).toBe('b'.repeat(64))
    expect(meta.exeRelPath).toBe('v0.1.0/AgentEngine.exe')
  })
})
