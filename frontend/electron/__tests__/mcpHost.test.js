import { describe, it, expect } from 'vitest'
import { resolveMcpLaunchTarget } from '../mcp/mcpHost.js'

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
})
