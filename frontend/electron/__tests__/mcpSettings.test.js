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

import { normalizeMcpSettings, readMcpSettings, writeMcpSettings, DEFAULTS, SOURCE_ENGINE_DEFAULT_ARGS } from '../mcp/mcpSettings.js'

beforeEach(() => {
  files.clear()
})

describe('mcpSettings', () => {
  it('defaults to disabled + official engine + confirm every ask', () => {
    expect(DEFAULTS.enabled).toBe(false)
    expect(DEFAULTS.useLocalSource).toBe(false)
    expect(DEFAULTS.autoApprove).toBe(false)
    expect(SOURCE_ENGINE_DEFAULT_ARGS).toEqual(['-m', 'agent_assistant.hosted.mcp_server'])
    const s = readMcpSettings()
    expect(s).toEqual({
      enabled: false, autoApprove: false, useLocalSource: false, command: '', args: [], cwd: '',
    })
  })

  it('normalizes enabled/useLocalSource strictly to booleans', () => {
    expect(normalizeMcpSettings({ enabled: 'yes' }).enabled).toBe(false)
    expect(normalizeMcpSettings({ enabled: true }).enabled).toBe(true)
    expect(normalizeMcpSettings({}).useLocalSource).toBe(false)
    expect(normalizeMcpSettings({ useLocalSource: true, command: 'python' }).useLocalSource).toBe(true)
    expect(normalizeMcpSettings({ useLocalSource: false, command: 'python' }).useLocalSource).toBe(false)
  })

  it('migrates old demo-on files to official even if command is set', () => {
    const s = normalizeMcpSettings({
      useDemoServer: true,
      command: 'python',
      cwd: 'C:/src',
    })
    expect(s.useLocalSource).toBe(false)
    expect(s).not.toHaveProperty('useDemoServer')
  })

  it('migrates old custom command (no demo) to local source', () => {
    expect(normalizeMcpSettings({
      useDemoServer: false,
      command: 'python',
    }).useLocalSource).toBe(true)
  })

  it('normalizes autoApprove strictly to boolean (default false)', () => {
    expect(normalizeMcpSettings({}).autoApprove).toBe(false)
    expect(normalizeMcpSettings({ autoApprove: true }).autoApprove).toBe(true)
    expect(normalizeMcpSettings({ autoApprove: 'yes' }).autoApprove).toBe(false)
    expect(normalizeMcpSettings({ autoApprove: 1 }).autoApprove).toBe(false)
  })

  it('round-trips autoApprove and keeps it across partial writes', () => {
    writeMcpSettings({ autoApprove: true })
    expect(readMcpSettings().autoApprove).toBe(true)
    writeMcpSettings({ command: 'engine' })
    expect(readMcpSettings().autoApprove).toBe(true)
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
      useLocalSource: true,
      command: 'python',
      args: ['-m', 'agent_assistant.hosted.mcp_server'],
      cwd: 'C:/AgentAssistant',
    })
    const s = readMcpSettings()
    expect(s).toEqual({
      enabled: true,
      autoApprove: false,
      useLocalSource: true,
      command: 'python',
      args: ['-m', 'agent_assistant.hosted.mcp_server'],
      cwd: 'C:/AgentAssistant',
    })
  })

  it('keeps stored python path when switching back to official', () => {
    writeMcpSettings({
      useLocalSource: true,
      command: 'python',
      args: ['-m', 'agent_assistant.hosted.mcp_server'],
      cwd: 'C:/AgentAssistant',
    })
    writeMcpSettings({ useLocalSource: false })
    const s = readMcpSettings()
    expect(s.useLocalSource).toBe(false)
    expect(s.command).toBe('python')
    expect(s.cwd).toBe('C:/AgentAssistant')
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
