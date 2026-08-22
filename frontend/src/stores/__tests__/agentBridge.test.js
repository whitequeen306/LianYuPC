import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

// 可控假 electronAPI + 假后端 API；holder 暴露注册的回调供测试触发主进程推送。
const { holder } = vi.hoisted(() => ({
  holder: { api: null, statusCb: null, toolsHandler: null },
}))

vi.mock('@/utils/electron', () => ({
  getElectronAPI: () => holder.api,
  isElectronApp: () => true,
}))

vi.mock('@/stores/notifications', () => ({
  useNotificationsStore: () => ({
    setAgentToolsHandler: (handler) => { holder.toolsHandler = handler },
  }),
}))

vi.mock('@/stores/characters', () => ({
  useCharactersStore: () => ({ list: [] }),
}))

vi.mock('@/stores/settings', () => ({
  useSettingsStore: () => ({ theme: 'dark' }),
}))

const registerAgentTools = vi.fn(async () => ({}))
const agentBridgeHeartbeat = vi.fn(async () => ({ registered: true }))
const unregisterAgentBridge = vi.fn(async () => ({}))
const postAgentToolResult = vi.fn(async () => ({}))
vi.mock('@/api/agentBridge', () => ({
  registerAgentTools: (...args) => registerAgentTools(...args),
  agentBridgeHeartbeat: (...args) => agentBridgeHeartbeat(...args),
  unregisterAgentBridge: (...args) => unregisterAgentBridge(...args),
  postAgentToolResult: (...args) => postAgentToolResult(...args),
}))

vi.mock('@/utils/mcpControlActor', () => ({
  resolveMcpControlActor: (message) => ({
    name: message?.characterName || '角色',
    avatarUrl: '',
    caption: `${message?.characterName || '角色'}正在操控你的电脑，按 Esc 取消`,
    theme: 'dark',
  }),
}))

import { useAgentBridgeStore } from '@/stores/agentBridge'

function makeApi(overrides = {}) {
  holder.statusCb = null
  const api = {
    isElectron: true,
    getMcpSettings: vi.fn(async () => ({ enabled: true, useDemoServer: true, command: '', args: [] })),
    getMcpStatus: vi.fn(async () => ({ state: 'stopped', tools: [], error: '' })),
    onMcpStatus: vi.fn((cb) => { holder.statusCb = cb; return () => { holder.statusCb = null } }),
    listMcpTools: vi.fn(async () => [
      { name: 'computer_task', description: 'do things', inputSchema: { type: 'object' }, dangerous: false },
    ]),
    mcpCallTool: vi.fn(async () => ({ ok: true, content: '已打开 网易云音乐' })),
    setMcpSettings: vi.fn(async (p) => ({ settings: p })),
    ...overrides,
  }
  holder.api = api
  return api
}

let store

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  vi.useFakeTimers()
  makeApi()
  store = useAgentBridgeStore()
})

afterEach(() => {
  store.dispose()
  vi.useRealTimers()
  holder.api = null
})

describe('agentBridge store', () => {
  it('registers tools to cloud when MCP becomes running', async () => {
    await store.handleMcpStatus({ state: 'running', tools: [{ name: 'computer_task' }], error: '' })
    expect(registerAgentTools).toHaveBeenCalledTimes(1)
    expect(registerAgentTools).toHaveBeenCalledWith([
      { name: 'computer_task', description: 'do things', inputSchema: { type: 'object' }, dangerous: false },
    ])
    expect(store.registered).toBe(true)
    expect(store.online).toBe(true)
  })

  it('unregisters when MCP stops', async () => {
    await store.handleMcpStatus({ state: 'running', tools: [], error: '' })
    await store.handleMcpStatus({ state: 'stopped', tools: [], error: '' })
    expect(unregisterAgentBridge).toHaveBeenCalledTimes(1)
    expect(store.registered).toBe(false)
    expect(store.online).toBe(false)
  })

  it('executes a dispatched tool call locally and posts the result back', async () => {
    await store.handleToolCallMessage({
      type: 'tool_call',
      requestId: 'req-1',
      name: 'computer_task',
      arguments: '{"instruction":"打开网易云"}',
    })
    expect(holder.api.mcpCallTool).toHaveBeenCalledWith(
      'computer_task',
      { instruction: '打开网易云' },
      expect.objectContaining({
        name: expect.any(String),
        caption: expect.stringMatching(/Esc/),
      }),
    )
    expect(postAgentToolResult).toHaveBeenCalledWith({
      requestId: 'req-1',
      ok: true,
      content: '已打开 网易云音乐',
      error: '',
    })
  })

  it('posts an error result when local execution throws', async () => {
    holder.api.mcpCallTool = vi.fn(async () => { throw new Error('boom') })
    await store.handleToolCallMessage({ type: 'tool_call', requestId: 'req-2', name: 'x', arguments: '{}' })
    expect(postAgentToolResult).toHaveBeenCalledWith(
      expect.objectContaining({ requestId: 'req-2', ok: false, error: 'boom' }),
    )
  })

  it('tolerates malformed argument json (falls back to empty object)', async () => {
    await store.handleToolCallMessage({ type: 'tool_call', requestId: 'req-3', name: 'x', arguments: 'not-json' })
    expect(holder.api.mcpCallTool).toHaveBeenCalledWith(
      'x',
      {},
      expect.objectContaining({ caption: expect.any(String) }),
    )
  })

  it('ignores non tool_call messages', async () => {
    await store.handleToolCallMessage({ type: 'something_else', requestId: 'r' })
    expect(holder.api.mcpCallTool).not.toHaveBeenCalled()
  })

  it('wires the STOMP handler and status listener on init', async () => {
    await store.init()
    expect(typeof holder.toolsHandler).toBe('function')
    expect(holder.api.onMcpStatus).toHaveBeenCalled()
  })

  it('re-registers when heartbeat reports the session was lost', async () => {
    await store.handleMcpStatus({ state: 'running', tools: [], error: '' })
    expect(registerAgentTools).toHaveBeenCalledTimes(1)
    agentBridgeHeartbeat.mockResolvedValueOnce({ registered: false })

    await vi.advanceTimersByTimeAsync(30_000)
    // allow the async re-register chain to settle
    await Promise.resolve()
    expect(agentBridgeHeartbeat).toHaveBeenCalled()
    expect(registerAgentTools).toHaveBeenCalledTimes(2)
  })
})
