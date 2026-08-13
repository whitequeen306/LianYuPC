import { describe, it, expect } from 'vitest'
import { resolveMcpLaunchTarget, engineEnvFromCredentials } from '../mcp/mcpHost.js'

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
