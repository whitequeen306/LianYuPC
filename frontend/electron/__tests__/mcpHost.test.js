import { describe, it, expect } from 'vitest'
import { resolveMcpLaunchTarget, engineEnvFromCredentials, createMcpHost, MCP_USER_CANCELLED_CONTENT, isMcpCancelledError, toolIsDangerous } from '../mcp/mcpHost.js'

describe('resolveMcpLaunchTarget', () => {
  it('prefers the demo server when useDemoServer is true', () => {
    const target = resolveMcpLaunchTarget(
      { useDemoServer: true, command: 'C:/custom.exe' },
      {
        resolveDemoServerCommand: () => ({ command: 'electron', args: ['demo.cjs'], env: { ELECTRON_RUN_AS_NODE: '1' } }),
        resolveManagedEngine: () => ({ command: 'C:/managed/AgentEngine.exe', args: [], cwd: 'C:/managed' }),
      },
    )
    expect(target.command).toBe('electron')
    expect(target.args).toEqual(['demo.cjs'])
    expect(target.env.ELECTRON_RUN_AS_NODE).toBe('1')
  })

  it('uses a custom command over the managed engine', () => {
    const target = resolveMcpLaunchTarget(
      { useDemoServer: false, command: 'python', args: ['-m', 'agent_assistant.hosted.mcp_server'], cwd: 'C:/src' },
      { resolveManagedEngine: () => ({ command: 'C:/managed/AgentEngine.exe' }) },
    )
    expect(target.command).toBe('python')
    expect(target.cwd).toBe('C:/src')
  })

  it('falls back to the downloaded managed engine', () => {
    const target = resolveMcpLaunchTarget(
      { useDemoServer: false, command: '' },
      { resolveManagedEngine: () => ({ command: 'C:/managed/AgentEngine.exe', args: [], cwd: 'C:/managed' }) },
    )
    expect(target.command).toBe('C:/managed/AgentEngine.exe')
    expect(target.cwd).toBe('C:/managed')
  })

  it('errors when the managed engine is missing', () => {
    const target = resolveMcpLaunchTarget(
      { useDemoServer: false, command: '' },
      { resolveManagedEngine: () => null },
    )
    expect(target.error).toMatch(/未安装/)
  })

  it('marks managed/custom targets as needing model credentials, demo not', () => {
    const demo = resolveMcpLaunchTarget(
      { useDemoServer: true },
      { resolveDemoServerCommand: () => ({ command: 'electron', args: [] }) },
    )
    expect(demo.needsModelCredentials).toBe(false)
    const managed = resolveMcpLaunchTarget(
      { useDemoServer: false, command: '' },
      { resolveManagedEngine: () => ({ command: 'C:/managed/AgentEngine.exe' }) },
    )
    expect(managed.needsModelCredentials).toBe(true)
    const custom = resolveMcpLaunchTarget(
      { useDemoServer: false, command: 'python', args: [] },
      {},
    )
    expect(custom.needsModelCredentials).toBe(true)
  })
})

describe('engineEnvFromCredentials', () => {
  it('maps available credentials to DEEPSEEK_* env', () => {
    const env = engineEnvFromCredentials({
      available: true,
      baseUrl: 'https://api.deepseek.com/v1',
      model: 'deepseek-v4-flash',
      apiKey: 'sk-x',
    })
    expect(env).toEqual({
      DEEPSEEK_API_KEY: 'sk-x',
      DEEPSEEK_BASE_URL: 'https://api.deepseek.com/v1',
      DEEPSEEK_MODEL: 'deepseek-v4-flash',
    })
  })

  it('returns empty env when unavailable or malformed', () => {
    expect(engineEnvFromCredentials({ available: false, apiKey: 'sk-x' })).toEqual({})
    expect(engineEnvFromCredentials(null)).toEqual({})
    expect(engineEnvFromCredentials({})).toEqual({})
  })

  it('skips empty fields so inherited env survives', () => {
    const env = engineEnvFromCredentials({ available: true, apiKey: 'sk-x', baseUrl: '', model: '' })
    expect(env).toEqual({ DEEPSEEK_API_KEY: 'sk-x' })
  })
})

describe('mcpHost cancel helpers', () => {
  it('exposes the in-character cancel payload', () => {
    expect(MCP_USER_CANCELLED_CONTENT).toMatch(/Esc/)
    expect(MCP_USER_CANCELLED_CONTENT).toMatch(/咦/)
    expect(isMcpCancelledError({ cancelled: true })).toBe(true)
    expect(isMcpCancelledError(new Error('nope'))).toBe(false)
  })

  it('cancelActiveCall is a no-op when idle', () => {
    const host = createMcpHost({
      getSettings: () => ({ enabled: false, useDemoServer: false, command: '' }),
      requestConfirm: async () => true,
      broadcast: () => {},
    })
    expect(host.cancelActiveCall()).toBe(false)
  })
})

describe('callTool requestId binding', () => {
  it('does not redeclare requestId inside the same try block (TDZ crash in 0.2.361)', async () => {
    const { readFileSync } = await import('node:fs')
    const { fileURLToPath } = await import('node:url')
    const src = readFileSync(fileURLToPath(new URL('../mcp/mcpHost.js', import.meta.url)), 'utf8')
    const start = src.indexOf('async function callTool')
    const end = src.indexOf('\n  function cancelActiveCall')
    const fn = src.slice(start, end)
    const bindings = fn.match(/\b(?:const|let) requestId\b/g) || []
    expect(bindings).toHaveLength(1)
    expect(fn).toMatch(/\bmcpRequestId\b/)
  })
})

describe('toolIsDangerous', () => {
  it('never pre-confirms computer_task even when annotations are missing', () => {
    expect(toolIsDangerous('computer_task', {})).toBe(false)
    expect(toolIsDangerous('computer_task', { destructiveHint: true })).toBe(false)
  })

  it('treats missing destructiveHint as dangerous for other tools', () => {
    expect(toolIsDangerous('run_command', {})).toBe(true)
    expect(toolIsDangerous('run_command', { destructiveHint: false })).toBe(false)
    expect(toolIsDangerous('read_file', { readOnlyHint: true })).toBe(false)
  })
})
