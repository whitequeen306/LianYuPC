import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { getElectronAPI, isElectronApp } from '@/utils/electron'
import { useNotificationsStore } from '@/stores/notifications'
import {
  registerAgentTools,
  agentBridgeHeartbeat,
  unregisterAgentBridge,
  postAgentToolResult,
} from '@/api/agentBridge'

const HEARTBEAT_INTERVAL_MS = 30_000

/**
 * Agent 工具桥（渲染端协调者）：
 * - 监听 Electron 主进程 MCP 服务状态；running 时把工具清单注册到云端并开始心跳
 * - 经 STOMP /user/queue/agent-tools 接收云端下发的工具调用 → IPC 转交主进程本地执行 → REST 回传结果
 */
export const useAgentBridgeStore = defineStore('agentBridge', () => {
  const mcpState = ref('stopped') // stopped | starting | running | error
  const mcpError = ref('')
  const tools = ref([])
  const registered = ref(false)
  const settings = ref(null)
  const engineStatus = ref({ installed: false, version: '', exePath: '' })
  const engineProgress = ref(null)

  let heartbeatTimer = null
  let statusUnsub = null
  let engineProgressUnsub = null
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

  /** 云端下发的工具调用：本地执行后回传（永不抛异常，失败也要回包让模型继续） */
  async function handleToolCallMessage(message) {
    if (!message || message.type !== 'tool_call' || !message.requestId) return
    const api = getElectronAPI()
    let result = null
    try {
      let args = {}
      try {
        args = message.arguments ? JSON.parse(message.arguments) : {}
      } catch {
        args = {}
      }
      result = await api?.mcpCallTool?.(message.name, args)
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
    }
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
    if (engineProgressUnsub) {
      engineProgressUnsub()
      engineProgressUnsub = null
    }
    inited = false
  }

  return {
    mcpState, mcpError, tools, registered, online, settings,
    engineStatus, engineProgress,
    init, dispose, refreshSettings, updateSettings, refreshEngineStatus, installEngine,
    // 导出供测试
    handleMcpStatus, handleToolCallMessage,
  }
})
