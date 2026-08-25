import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { getElectronAPI, isElectronApp } from '@/utils/electron'
import { useNotificationsStore } from '@/stores/notifications'
import { useCharactersStore } from '@/stores/characters'
import { useSettingsStore } from '@/stores/settings'
import { resolveMcpActorIdentity, resolveMcpControlActor } from '@/utils/mcpControlActor'
import { activeChatActor } from '@/composables/useActiveChatContext'
import {
  registerAgentTools,
  agentBridgeHeartbeat,
  unregisterAgentBridge,
  postAgentToolResult,
} from '@/api/agentBridge'
import { parseAgentToolArguments } from '@/utils/parseAgentToolArguments'

const HEARTBEAT_INTERVAL_MS = 30_000
const MAX_TASK_UPDATES = 30
const MCP_RESTART_WAIT_MS = 20_000

function mcpRestartLikely(mcpState, engineProgress) {
  if (mcpState === 'starting') return true
  const phase = engineProgress?.phase
  return !!phase && phase !== 'done' && phase !== 'error' && phase !== 'skipped'
}

function isTransientMcpDown(error) {
  return String(error || '').includes('MCP 服务未运行')
}

/**
 * Agent 工具桥（渲染端协调者）：
 * - 监听 Electron 主进程 MCP 服务状态；running 时把工具清单注册到云端并开始心跳
 * - 经 STOMP /user/queue/agent-tools 接收云端下发的工具调用 → IPC 转交主进程本地执行 → REST 回传结果
 * - 维护 activeTasks：任务执行期间引擎经 MCP progress 上报的现场解说，
 *   聊天页把它渲染成角色的进度小气泡（任务结束即消失，不落库）。
 */
export const useAgentBridgeStore = defineStore('agentBridge', () => {
  const mcpState = ref('stopped') // stopped | starting | running | error
  const mcpError = ref('')
  const tools = ref([])
  const registered = ref(false)
  const settings = ref(null)
  const engineStatus = ref({ installed: false, version: '', exePath: '' })
  const engineProgress = ref(null)
  // requestId → { requestId, name, instruction, actor, updates: [{ ts, text }], startedAt }
  const activeTasks = ref({})

  let heartbeatTimer = null
  let statusUnsub = null
  let engineProgressUnsub = null
  let progressUnsub = null
  let inited = false

  const online = computed(() => mcpState.value === 'running' && registered.value)

  function stopHeartbeat() {
    if (heartbeatTimer) {
      clearInterval(heartbeatTimer)
      heartbeatTimer = null
    }
  }

  function startHeartbeat() {
    stopHeartbeat()
    heartbeatTimer = setInterval(async () => {
      try {
        const res = await agentBridgeHeartbeat()
        if (res && res.registered === false) {
          // 后端重启丢了会话 → 重新注册
          await registerToolsToCloud()
        }
      } catch {
        // 网络抖动忽略，下轮心跳重试
      }
    }, HEARTBEAT_INTERVAL_MS)
  }

  async function registerToolsToCloud() {
    const api = getElectronAPI()
    try {
      const list = await api?.listMcpTools?.()
      if (!Array.isArray(list) || list.length === 0) {
        registered.value = false
        return
      }
      await registerAgentTools(list)
      registered.value = true
      startHeartbeat()
    } catch (e) {
      registered.value = false
      console.warn('[agentBridge] register tools failed', e)
    }
  }

  async function unregisterFromCloud() {
    stopHeartbeat()
    if (!registered.value) return
    registered.value = false
    try {
      await unregisterAgentBridge()
    } catch {
      // 后端心跳过期兜底
    }
  }

  async function handleMcpStatus(status) {
    mcpState.value = status?.state || 'stopped'
    mcpError.value = status?.error || ''
    tools.value = Array.isArray(status?.tools) ? status.tools : []
    if (mcpState.value === 'running') {
      await registerToolsToCloud()
    } else {
      await unregisterFromCloud()
    }
  }

  function waitForMcpRunning(timeoutMs) {
    if (mcpState.value === 'running') return Promise.resolve(true)
    return new Promise((resolve) => {
      const deadline = Date.now() + timeoutMs
      const timer = setInterval(() => {
        if (mcpState.value === 'running') {
          clearInterval(timer)
          resolve(true)
          return
        }
        if (Date.now() >= deadline) {
          clearInterval(timer)
          resolve(mcpState.value === 'running')
        }
      }, 200)
    })
  }

  /** 云端下发的工具调用：本地执行后回传（永不抛异常，失败也要回包让模型继续） */
  async function handleToolCallMessage(message) {
    if (!message || message.type !== 'tool_call' || !message.requestId) return
    const api = getElectronAPI()
    let result = null
    const args = parseAgentToolArguments(message.arguments)
    const task = {
      requestId: message.requestId,
      name: message.name || '',
      instruction: typeof args.instruction === 'string' ? args.instruction : '',
      actor: resolveMcpActorIdentity(message, {
        characters: useCharactersStore().list,
        currentCharacter: activeChatActor.value,
      }),
      updates: [],
      startedAt: Date.now(),
    }
    activeTasks.value = { ...activeTasks.value, [message.requestId]: task }
    try {
      if (mcpRestartLikely(mcpState.value, engineProgress.value) && mcpState.value !== 'running') {
        await waitForMcpRunning(MCP_RESTART_WAIT_MS)
      }
      result = await api?.mcpCallTool?.(message.name, args, resolveMcpControlActor(message, {
        characters: useCharactersStore().list,
        currentCharacter: activeChatActor.value,
        theme: useSettingsStore().theme,
      }), message.requestId)
      if (result?.ok === false && isTransientMcpDown(result.error)
          && mcpRestartLikely(mcpState.value, engineProgress.value)) {
        const recovered = await waitForMcpRunning(MCP_RESTART_WAIT_MS)
        if (recovered) {
          result = await api?.mcpCallTool?.(message.name, args, resolveMcpControlActor(message, {
            characters: useCharactersStore().list,
            currentCharacter: activeChatActor.value,
            theme: useSettingsStore().theme,
          }), message.requestId)
        }
      }
    } catch (e) {
      result = { ok: false, error: e?.message || '本地调用异常' }
    }
    try {
      await postAgentToolResult({
        requestId: message.requestId,
        ok: result?.ok === true,
        content: result?.content || '',
        error: result?.error || '',
      })
    } catch (e) {
      console.warn('[agentBridge] post result failed', e)
    } finally {
      const next = { ...activeTasks.value }
      delete next[message.requestId]
      activeTasks.value = next
    }
  }

  /** 引擎经 MCP notifications/progress 上报的现场解说（progressToken = requestId） */
  function handleProgressMessage(params) {
    const requestId = params?.requestId
    const text = typeof params?.message === 'string' ? params.message.trim() : ''
    if (!requestId || !text) return
    const task = activeTasks.value[requestId]
    if (!task) return
    const last = task.updates[task.updates.length - 1]
    if (last && last.text === text) return
    const updates = [...task.updates, { ts: Date.now(), text }].slice(-MAX_TASK_UPDATES)
    activeTasks.value = {
      ...activeTasks.value,
      [requestId]: { ...task, updates },
    }
  }

  /** 指定角色当前正在执行的任务（聊天页进度气泡用）；取最近开始的一个 */
  function taskForCharacter(characterId) {
    if (characterId == null) return null
    const id = Number(characterId)
    const list = Object.values(activeTasks.value)
      .filter((t) => Number(t.actor?.characterId) === id)
      .sort((a, b) => b.startedAt - a.startedAt)
    return list[0] || null
  }

  async function refreshEngineStatus() {
    const api = getElectronAPI()
    engineStatus.value = (await api?.getEngineStatus?.()) || { installed: false, version: '', exePath: '' }
    return engineStatus.value
  }

  async function installEngine() {
    const api = getElectronAPI()
    const res = await api?.installEngine?.()
    if (res?.status) engineStatus.value = res.status
    else await refreshEngineStatus()
    return res
  }

  async function refreshSettings() {
    const api = getElectronAPI()
    settings.value = (await api?.getMcpSettings?.()) || null
    return settings.value
  }

  async function updateSettings(partial) {
    const api = getElectronAPI()
    const res = await api?.setMcpSettings?.(partial)
    if (res?.settings) settings.value = res.settings
    return settings.value
  }

  async function init() {
    if (inited || !isElectronApp()) return
    const api = getElectronAPI()
    if (!api?.onMcpStatus) return
    inited = true

    const notificationsStore = useNotificationsStore()
    notificationsStore.setAgentToolsHandler((message) => {
      void handleToolCallMessage(message)
    })

    statusUnsub = api.onMcpStatus((status) => {
      void handleMcpStatus(status)
    })
    if (api.onMcpProgress) {
      progressUnsub = api.onMcpProgress((params) => {
        handleProgressMessage(params)
      })
    }
    if (api.onEngineProgress) {
      engineProgressUnsub = api.onEngineProgress((progress) => {
        engineProgress.value = progress || null
      })
    }

    void refreshSettings()
    void refreshEngineStatus()
    try {
      const status = await api.getMcpStatus?.()
      if (status) await handleMcpStatus(status)
    } catch {
      // 初始状态获取失败不阻塞
    }
  }

  function dispose() {
    stopHeartbeat()
    if (statusUnsub) {
      statusUnsub()
      statusUnsub = null
    }
    if (progressUnsub) {
      progressUnsub()
      progressUnsub = null
    }
    if (engineProgressUnsub) {
      engineProgressUnsub()
      engineProgressUnsub = null
    }
    inited = false
  }

  return {
    mcpState, mcpError, tools, registered, online, settings,
    engineStatus, engineProgress, activeTasks,
    init, dispose, refreshSettings, updateSettings, refreshEngineStatus, installEngine,
    taskForCharacter,
    // 导出供测试
    handleMcpStatus, handleToolCallMessage, handleProgressMessage,
  }
})
