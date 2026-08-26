import { describe, it, expect } from 'vitest'
import { resolveMcpLaunchTarget, engineEnvFromCredentials, createMcpHost, MCP_USER_CANCELLED_CONTENT, MCP_SUPERSEDED_CONTENT, isMcpCancelledError, toolIsDangerous, looksLikePythonCommand, pickRealPython, sourceInterpreterEnv, describeMcpChildExit, isMcpChildCrash } from '../mcp/mcpHost.js'

describe('resolveMcpLaunchTarget', () => {
  it('uses local source command when useLocalSource is true', () => {
    const target = resolveMcpLaunchTarget(
      { useLocalSource: true, command: 'python', args: ['-m', 'agent_assistant.hosted.mcp_server'], cwd: 'C:/src' },
      { resolveManagedEngine: () => ({ command: 'C:/managed/AgentEngine.exe' }) },
    )
    expect(target.command).toBe('python')
    expect(target.cwd).toBe('C:/src')
    expect(target.needsModelCredentials).toBe(true)
  })

  it('ignores leftover command when using the official engine', () => {
    const target = resolveMcpLaunchTarget(
      { useLocalSource: false, command: 'python', cwd: 'C:/src' },
      { resolveManagedEngine: () => ({ command: 'C:/managed/AgentEngine.exe', args: [], cwd: 'C:/managed' }) },
    )
    expect(target.command).toBe('C:/managed/AgentEngine.exe')
    expect(target.cwd).toBe('C:/managed')
  })

  it('errors when local source has no command', () => {
    const target = resolveMcpLaunchTarget(
      { useLocalSource: true, command: '' },
      { resolveManagedEngine: () => ({ command: 'C:/managed/AgentEngine.exe' }) },
    )
    expect(target.error).toMatch(/Python/)
  })

  it('falls back to the downloaded managed engine', () => {
    const target = resolveMcpLaunchTarget(
      { useLocalSource: false, command: '' },
      { resolveManagedEngine: () => ({ command: 'C:/managed/AgentEngine.exe', args: [], cwd: 'C:/managed' }) },
    )
    expect(target.command).toBe('C:/managed/AgentEngine.exe')
    expect(target.cwd).toBe('C:/managed')
  })

  it('errors when the managed engine is missing', () => {
    const target = resolveMcpLaunchTarget(
      { useLocalSource: false, command: '' },
      { resolveManagedEngine: () => null },
    )
    expect(target.error).toMatch(/未安装/)
  })

  it('marks official and local targets as needing model credentials', () => {
    const managed = resolveMcpLaunchTarget(
      { useLocalSource: false, command: '' },
      { resolveManagedEngine: () => ({ command: 'C:/managed/AgentEngine.exe' }) },
    )
    expect(managed.needsModelCredentials).toBe(true)
    const local = resolveMcpLaunchTarget(
      { useLocalSource: true, command: 'python', args: [] },
      {},
    )
    expect(local.needsModelCredentials).toBe(true)
  })
})

describe('python source helpers', () => {
  it('recognizes python launchers', () => {
    expect(looksLikePythonCommand('python')).toBe(true)
    expect(looksLikePythonCommand('C:/Python311/python.exe')).toBe(true)
    expect(looksLikePythonCommand('py')).toBe(true)
    expect(looksLikePythonCommand('C:/managed/AgentEngine.exe')).toBe(false)
  })

  it('skips the WindowsApps python stub', () => {
    expect(pickRealPython(
      'C:\\Users\\hp\\AppData\\Local\\Microsoft\\WindowsApps\\python.exe\r\nC:\\Users\\hp\\AppData\\Local\\Programs\\Python\\Python311\\python.exe\r\n',
      'python',
    )).toBe('C:\\Users\\hp\\AppData\\Local\\Programs\\Python\\Python311\\python.exe')
  })

  it('sets unbuffered UTF-8 env for source runs', () => {
    const env = sourceInterpreterEnv('C:/src', {})
    expect(env.PYTHONUNBUFFERED).toBe('1')
    expect(env.PYTHONUTF8).toBe('1')
    expect(env.PYTHONPATH).toMatch(/C:\/src/)
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
    expect(MCP_SUPERSEDED_CONTENT).toMatch(/新的电脑任务/)
    expect(isMcpCancelledError({ cancelled: true })).toBe(true)
    expect(isMcpCancelledError(new Error('nope'))).toBe(false)
  })

  it('cancelActiveCall is a no-op when idle', () => {
    const host = createMcpHost({
      getSettings: () => ({ enabled: false, useLocalSource: false, command: '' }),
      requestConfirm: async () => true,
      broadcast: () => {},
    })
    expect(host.cancelActiveCall()).toBe(false)
  })
})

describe('describeMcpChildExit', () => {
  it('maps Windows access-violation exits to a restart message', () => {
    expect(isMcpChildCrash('MCP server exited (code=3221225477)')).toBe(true)
    expect(describeMcpChildExit('MCP server exited (code=3221225477)')).toMatch(/崩溃/)
    expect(describeMcpChildExit('MCP server exited (code=-1073741819)')).toMatch(/自动重启/)
  })

  it('passes through unrelated errors', () => {
    expect(describeMcpChildExit('握手失败：timeout')).toBe('握手失败：timeout')
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
    expect(fn).toMatch(/superseded/)
    expect(fn).toMatch(/callGeneration/)
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
