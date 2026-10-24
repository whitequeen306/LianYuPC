import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

// 可控假 electronAPI + 假后端 API；holder 暴露注册的回调供测试触发主进程推送。
const { holder } = vi.hoisted(() => ({
  holder: { api: null, toolsHandler: null },
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

vi.mock('@/composables/useActiveChatContext', () => ({
  activeChatActor: { value: null },
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
  resolveMcpActorIdentity: (message) => ({
    characterId: message?.characterId ?? null,
    name: String(message?.characterName || '').trim(),
    avatarUrl: message?.characterAvatarUrl || '',
    avatarThumbUrl: '',
  }),
}))

import { useAgentBridgeStore } from '@/stores/agentBridge'

function makeApi(overrides = {}) {
  const api = {
    isElectron: true,
    getCapabilitySettings: vi.fn(async () => ({ enabled: true })),
    getCapabilityStatus: vi.fn(async () => ({
      enabled: true,
      tools: ['desk_click', 'desk_screen_read'],
    })),
    setCapabilitySettings: vi.fn(async (p) => ({ settings: { enabled: p.enabled } })),
    capabilityExec: vi.fn(async () => ({
      ok: true,
      text: '已点击「播放」',
      errorCode: '',
      images: [],
      policy: { level: 'R2', confirmed: false },
    })),
    onCapabilityAudit: vi.fn(() => () => {}),
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

describe('agentBridge store · 能力注册', () => {
  it('registers desk_* names to the cloud when the switch is on', async () => {
    const enabled = await store.refreshCapabilitySettings()

    expect(enabled).toBe(true)
    expect(registerAgentTools).toHaveBeenCalledWith([
      expect.objectContaining({ name: 'desk_click' }),
      expect.objectContaining({ name: 'desk_screen_read' }),
    ])
    expect(store.registered).toBe(true)
    expect(store.capabilityTools).toEqual(['desk_click', 'desk_screen_read'])
  })

  it('does not register anything when the switch is off', async () => {
    holder.api.getCapabilitySettings = vi.fn(async () => ({ enabled: false }))
    const enabled = await store.refreshCapabilitySettings()

    expect(enabled).toBe(false)
    expect(registerAgentTools).not.toHaveBeenCalled()
    expect(store.registered).toBe(false)
  })

  it('re-registers when the backend forgot the session (heartbeat says registered:false)', async () => {
    await store.refreshCapabilitySettings()
    expect(registerAgentTools).toHaveBeenCalledTimes(1)

    agentBridgeHeartbeat.mockResolvedValueOnce({ registered: false })
    await vi.advanceTimersByTimeAsync(30_000)

    expect(registerAgentTools).toHaveBeenCalledTimes(2)
  })

  it('unregisters when the user turns the switch off', async () => {
    await store.refreshCapabilitySettings()
    expect(store.registered).toBe(true)

    holder.api.getCapabilitySettings = vi.fn(async () => ({ enabled: false }))
    const on = await store.setCapabilityEnabled(false)

    expect(on).toBe(false)
    expect(unregisterAgentBridge).toHaveBeenCalled()
    expect(store.registered).toBe(false)
  })

  it('stops the heartbeat on dispose', async () => {
    await store.refreshCapabilitySettings()
    store.dispose()
    agentBridgeHeartbeat.mockClear()

    await vi.advanceTimersByTimeAsync(90_000)
    expect(agentBridgeHeartbeat).not.toHaveBeenCalled()
  })
})

describe('agentBridge store · 动作转交', () => {
  const message = (over = {}) => ({
    type: 'tool_call',
    requestId: 'desk-7-abcd#1',
    sessionId: 'desk-7-abcd',
    name: 'desk_click',
    arguments: '{"element_id":5,"label":"播放","evidence":"user_request"}',
    danger: 'R2',
    timeoutMs: 4000,
    characterId: 42,
    characterName: '小雨',
    ...over,
  })

  it('routes every action to capabilityExec (no engine left to call)', async () => {
    await store.handleToolCallMessage(message())

    expect(holder.api.capabilityExec).toHaveBeenCalledWith(expect.objectContaining({
      stepId: 'desk-7-abcd#1',
      sessionId: 'desk-7-abcd',
      tool: 'desk_click',
      danger: 'R2',
      timeoutMs: 4000,
    }))
    expect(postAgentToolResult).toHaveBeenCalledWith(expect.objectContaining({
      requestId: 'desk-7-abcd#1',
      ok: true,
      content: '已点击「播放」',
      error: '',
      policy: { level: 'R2', confirmed: false },
    }))
  })

  it('forwards the machine-readable error code instead of the Chinese text', async () => {
    holder.api.capabilityExec = vi.fn(async () => ({
      ok: false,
      text: '这一步被拦下了',
      errorCode: 'window_not_found',
    }))

    await store.handleToolCallMessage(message({ name: 'desk_window_focus' }))

    expect(postAgentToolResult).toHaveBeenCalledWith(expect.objectContaining({
      requestId: 'desk-7-abcd#1',
      ok: false,
      error: 'window_not_found',
    }))
  })

  it('always answers the bridge even if the IPC throws', async () => {
    holder.api.capabilityExec = vi.fn(async () => { throw new Error('boom') })

    await store.handleToolCallMessage(message())

    expect(postAgentToolResult).toHaveBeenCalledWith(expect.objectContaining({
      requestId: 'desk-7-abcd#1',
      ok: false,
      error: 'action_failed',
    }))
  })

  it('keeps the task alive after a step (气泡不闪) and sweeps it after idle', async () => {
    vi.useFakeTimers()
    try {
      holder.api.capabilityBanner = vi.fn(async () => ({ ok: true }))
      await store.handleToolCallMessage(message())
      // 每步结束不再立刻删除：多步任务的气泡要一直挂着
      expect(store.activeTasks['desk-7-abcd#1']).toBeTruthy()
      // 空闲超时后才收，并通知主进程收横幅
      vi.advanceTimersByTime(9000)
      expect(store.activeTasks['desk-7-abcd#1']).toBeUndefined()
      expect(holder.api.capabilityBanner).toHaveBeenCalledWith({ taskDone: true })
    } finally {
      vi.useRealTimers()
    }
  })
})

describe('agentBridge store · 进度气泡', () => {
  it('appends progress text for a running task and dedupes repeats', async () => {
    const pending = store.handleToolCallMessage({
      type: 'tool_call', requestId: 'r1', name: 'desk_click', arguments: '{}',
      characterId: 9, characterName: '小雨',
    })
    // 让 task 先登记（capabilityExec 尚未 resolve）
    let release
    holder.api.capabilityExec = vi.fn(() => new Promise((resolve) => { release = resolve }))
    expect(store.taskForCharacter(9)?.requestId).toBe('r1')

    const before = store.activeTasks.r1
    expect(before).toBeTruthy()

    store.handleProgressMessage({ requestId: 'r1', text: '我点一下' })
    store.handleProgressMessage({ requestId: 'r1', text: '我点一下' })
    expect(store.activeTasks.r1.updates).toHaveLength(1)

    store.handleProgressMessage({ requestId: 'r1', text: '好了' })
    expect(store.activeTasks.r1.updates.map((u) => u.text)).toEqual(['我点一下', '好了'])

    release?.({ ok: true, text: 'ok', errorCode: '' })
    await pending
  })

  it('ignores progress for unknown tasks', () => {
    store.handleProgressMessage({ requestId: 'nope', text: 'hi' })
    expect(Object.keys(store.activeTasks)).toHaveLength(0)
  })

  it('forwards the current step to the desktop banner and ends the task explicitly', async () => {
    vi.useFakeTimers()
    try {
      let release
      holder.api.capabilityExec = vi.fn(() => new Promise((resolve) => { release = resolve }))
      holder.api.capabilityBanner = vi.fn(async () => ({ ok: true }))

      const pending = store.handleToolCallMessage({
        type: 'tool_call', requestId: 'r3', name: 'desk_app_launch', arguments: '{}',
        characterId: 9, characterName: '小雨',
      })

      store.handleProgressMessage({ requestId: 'r3', text: '启动网易云音乐' })
      // 任务进行中：带 taskActive，主进程据此让横幅常驻
      expect(holder.api.capabilityBanner).toHaveBeenCalledWith({ taskActive: true, text: '启动网易云音乐' })

      release?.({ ok: true, text: 'ok', errorCode: '' })
      await pending
      // 任务结束用 taskDone（不是 hide），并要等空闲兜底超时才会发
      vi.advanceTimersByTime(9000)
      expect(holder.api.capabilityBanner).toHaveBeenCalledWith({ taskDone: true })
    } finally {
      vi.useRealTimers()
    }
  })
})
