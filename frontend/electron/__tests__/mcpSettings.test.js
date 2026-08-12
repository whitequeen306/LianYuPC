import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('electron', () => ({
  app: { getPath: () => '/tmp/lianyu-test' },
}))

const files = new Map()
vi.mock('fs', () => ({
  default: {
    readFileSync: (p) => {
      if (files.has(p)) return files.get(p)
      throw new Error('ENOENT')
    },
    writeFileSync: (p, data) => { files.set(p, data) },
    mkdirSync: () => {},
  },
  readFileSync: (p) => {
    if (files.has(p)) return files.get(p)
    throw new Error('ENOENT')
  },
  writeFileSync: (p, data) => { files.set(p, data) },
  mkdirSync: () => {},
}))

import { normalizeMcpSettings, readMcpSettings, writeMcpSettings, DEFAULTS } from '../mcp/mcpSettings.js'

beforeEach(() => {
  files.clear()
})

describe('mcpSettings', () => {
  it('defaults to disabled + managed engine (not demo)', () => {
    expect(DEFAULTS.enabled).toBe(false)
    expect(DEFAULTS.useDemoServer).toBe(false)
    const s = readMcpSettings()
    expect(s).toEqual({ enabled: false, useDemoServer: false, command: '', args: [], cwd: '' })
  })

  it('normalizes enabled/useDemoServer strictly to booleans', () => {
    expect(normalizeMcpSettings({ enabled: 'yes' }).enabled).toBe(false)
    expect(normalizeMcpSettings({ enabled: true }).enabled).toBe(true)
    expect(normalizeMcpSettings({}).useDemoServer).toBe(false)
    expect(normalizeMcpSettings({ useDemoServer: true }).useDemoServer).toBe(true)
    expect(normalizeMcpSettings({ useDemoServer: false }).useDemoServer).toBe(false)
  })

  it('trims command and filters/limits args', () => {
    const s = normalizeMcpSettings({
      command: '  C:/engine.exe  ',
      args: ['  --hosted  ', '', 123, 'x'.repeat(600)],
    })
    expect(s.command).toBe('C:/engine.exe')
    // empty dropped, number stringified, oversized dropped
    expect(s.args).toEqual(['--hosted', '123'])
  })

  it('caps args count at 16', () => {
    const many = Array.from({ length: 30 }, (_, i) => `a${i}`)
    expect(normalizeMcpSettings({ args: many }).args).toHaveLength(16)
  })

  it('round-trips through write/read (incl. cwd for source-run python -m)', () => {
    writeMcpSettings({
      enabled: true,
      useDemoServer: false,
      command: 'python',
      args: ['-m', 'agent_assistant.hosted.mcp_server'],
      cwd: 'C:/AgentAssistant',
    })
    const s = readMcpSettings()
    expect(s).toEqual({
      enabled: true,
      useDemoServer: false,
      command: 'python',
      args: ['-m', 'agent_assistant.hosted.mcp_server'],
      cwd: 'C:/AgentAssistant',
    })
  })

  it('trims cwd and drops oversized values', () => {
    expect(normalizeMcpSettings({ cwd: '  C:/foo  ' }).cwd).toBe('C:/foo')
    expect(normalizeMcpSettings({ cwd: 'x'.repeat(2000) }).cwd).toBe('')
    expect(normalizeMcpSettings({ cwd: 123 }).cwd).toBe('')
  })

  it('merges partial writes over existing settings', () => {
    writeMcpSettings({ enabled: true, command: 'engine' })
    writeMcpSettings({ command: 'engine2' })
    const s = readMcpSettings()
    expect(s.enabled).toBe(true)
    expect(s.command).toBe('engine2')
  })
})
