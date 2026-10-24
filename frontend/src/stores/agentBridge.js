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

/**
 * 桌面能力桥（渲染端协调者）。
 *
 * 它只做三件事，别的一概不做：
 * 1. **登记在线**：把主进程的 `desk_*` 能力名注册到云端 + 30s 心跳
 *    （Java 只按 `desk_` 前缀判断「桌面能力在线」，schema 由 Python 持有）；
 * 2. **转交执行**：经 STOMP `/user/queue/agent-tools` 收到动作 → IPC `capability:exec`
 *    → 主进程执行 → REST 回传结果（永不抛异常，失败也要回包让模型继续）；
 * 3. **渲染进度**：把 `/user/queue/agent-tool-progress` 的现场解说过滤进 `activeTasks`，
 *    聊天页据此显示角色的进度小气泡（任务结束即消失，不落库）。
 *
 * 历史上这里还管过「外借 MCP 引擎」的拉起/重启/下载，那套逻辑已随引擎一起删除：
 * 现在没有子进程要等，也就没有「等引擎重启」这种状态。
 */
export const useAgentBridgeStore = defineStore('agentBridge', () => {
  const registered = ref(false)
  const capabilityEnabled = ref(false)
  const capabilityTools = ref([])
  const capabilitySettings = ref(null)
  // requestId → { requestId, name, instruction, actor, updates: [{ ts, text }], startedAt }
  const activeTasks = ref({})

  let heartbeatTimer = null
  let toolsHandlerUnsub = null
  let progressUnsub = null
  let inited = false

  const online = computed(() => registered.value === true)

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
          await registerCapabilitiesToCloud()
        }
      } catch {
        // 网络抖动忽略，下轮心跳重试
      }
    }, HEARTBEAT_INTERVAL_MS)
  }

  /** 把桌面能力名注册到云端（整体替换语义）。 */
  async function registerCapabilitiesToCloud() {
    const api = getElectronAPI()
    try {
      const status = await api?.getCapabilityStatus?.()
      const names = Array.isArray(status?.tools) ? status.tools : []
      capabilityTools.value = names
      if (!names.length) {
        registered.value = false
        return false
      }
      await registerAgentTools(names.map((name) => ({
        name,
        description: `桌面能力 ${name}`,
        inputSchema: { type: 'object', properties: {} },
        // 危险等级由主进程 policy 判定，不上报云端（避免等于把策略写给模型看）
        dangerous: false,
      })))
      registered.value = true
      startHeartbeat()
      return true
    } catch (e) {
      registered.value = false
      console.warn('[agentBridge] register capabilities failed', e)
      return false
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

  async function refreshCapabilitySettings() {
    const api = getElectronAPI()
    try {
      capabilitySettings.value = (await api?.getCapabilitySettings?.()) || null
      capabilityEnabled.value = capabilitySettings.value?.enabled === true
    } catch {
      capabilityEnabled.value = false
    }
    if (capabilityEnabled.value) {
      await registerCapabilitiesToCloud()
    }
    return capabilityEnabled.value
  }

  /** 设置页开关：写主进程配置，并按开关状态注册/注销。 */
  async function setCapabilityEnabled(enabled) {
    const api = getElectronAPI()
    const res = await api?.setCapabilitySettings?.({ enabled: enabled === true })
    if (res?.settings) capabilitySettings.value = res.settings
    capabilityEnabled.value = enabled === true
    if (capabilityEnabled.value) {
      await registerCapabilitiesToCloud()
    } else {
      await refreshCapabilitySettings()
      if (!capabilityEnabled.value) await unregisterFromCloud()
    }
    return capabilityEnabled.value
  }

  /** 云端下发的动作：本地执行后回传（永不抛异常，失败也要回包让模型继续）。 */
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
      result = await api?.capabilityExec?.({
        stepId: message.requestId,
        sessionId: message.sessionId || '',
        tool: message.name,
        args,
        timeoutMs: message.timeoutMs,
        danger: message.danger,
        evidence: message.evidence,
        actor: resolveMcpControlActor(message, {
          characters: useCharactersStore().list,
          currentCharacter: activeChatActor.value,
          theme: useSettingsStore().theme,
        }),
      })
    } catch (e) {
      result = { ok: false, errorCode: 'action_failed', text: e?.message || '本地调用异常' }
    }
    try {
      await postAgentToolResult({
        requestId: message.requestId,
        ok: result?.ok === true,
        content: result?.text || result?.content || '',
        // 能力层回的是机器可读错误码（Java 透传给 Python 做文案翻译）
        error: result?.errorCode || result?.error || '',
        images: Array.isArray(result?.images) ? result.images : undefined,
        policy: result?.policy && typeof result.policy === 'object' ? result.policy : undefined,
      })
    } catch (e) {
      console.warn('[agentBridge] post result failed', e)
    } finally {
      const next = { ...activeTasks.value }
      delete next[message.requestId]
      activeTasks.value = next
      // 任务收尾：没有正在跑的任务了就把顶部指示条收掉（还有别的任务就让它继续留着）
      if (Object.keys(next).length === 0) {
        try {
          getElectronAPI()?.capabilityBanner?.({ hide: true })
        } catch {
          /* ignore */
        }
      }
    }
  }

  /** 每步的现场解说（progressToken = requestId）。 */
  function handleProgressMessage(params) {
    const requestId = params?.requestId
    const text = typeof params?.message === 'string'
      ? params.message.trim()
      : (typeof params?.text === 'string' ? params.text.trim() : '')
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
    // 桌面顶部指示条同步换文案：「{角色名}正在操控你的电脑」+ 当前这一步在干什么。
    // 纯 IPC，主进程会兜底（未开开关 / 窗口没起来都只是 no-op），失败不影响任务本身。
    try {
      getElectronAPI()?.capabilityBanner?.({ text })
    } catch {
      /* ignore */
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

  async function init() {
    if (inited || !isElectronApp()) return
    const api = getElectronAPI()
    if (!api?.onCapabilityAudit && !api?.getCapabilitySettings) return
    inited = true

    const notificationsStore = useNotificationsStore()
    notificationsStore.setAgentToolsHandler((message) => {
      void handleToolCallMessage(message)
    })

    if (api.onCapabilityAudit) {
      toolsHandlerUnsub = api.onCapabilityAudit(() => {
        // 「刚才做了什么」面板由 AboutPage 自己拉取；这里只保证不吞事件
      })
    }
    await refreshCapabilitySettings()
  }

  function dispose() {
    stopHeartbeat()
    if (toolsHandlerUnsub) {
      toolsHandlerUnsub()
      toolsHandlerUnsub = null
    }
    if (progressUnsub) {
      progressUnsub()
      progressUnsub = null
    }
    inited = false
  }

  return {
    registered, online, activeTasks,
    capabilityEnabled, capabilityTools, capabilitySettings,
    init, dispose,
    refreshCapabilitySettings, setCapabilityEnabled,
    registerCapabilitiesToCloud, unregisterFromCloud,
    taskForCharacter,
    // 导出供测试
    handleToolCallMessage, handleProgressMessage,
  }
})
