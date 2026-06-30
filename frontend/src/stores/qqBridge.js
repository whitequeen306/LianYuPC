import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getElectronAPI } from '@/utils/electron'

// 运行态：进程已就绪或终态；过渡态：下载/启动中。轮询快照的过渡态不应覆盖已就绪态
// （避免启动窗口内快照与 running 推送竞态导致的回退闪烁）；主动回填不走此守卫。
const HOST_SETTLED = new Set(['running', 'ready', 'connected', 'error', 'stopped'])
const HOST_TRANSIENT = new Set(['starting', 'downloading'])

/**
 * QQ 桥接 + 自管托管状态（B 方案，渲染侧）。
 * 与 stores/desktop.js 同构：syncFromMain 拉取设置/状态并订阅主进程推送，
 * persist 走 setQqBridgeSettings；dispose 解绑订阅（页面卸载时调用）。
 * 非桌面端（浏览器）下所有 IPC 为 no-op，store 仍可安全使用。
 */
export const useQqBridgeStore = defineStore('qqBridge', () => {
  const settings = ref(null)
  const bridgeStatus = ref({ state: 'stopped', selfId: '' })
  const hostStatus = ref({ state: 'stopped', webui: null, version: '', config: null })
  const downloadProgress = ref(null)
  const loaded = ref(false)

  let offBridge = null
  let offHost = null
  let offDownload = null
  let pollTimer = null
  let dlClearTimer = null

  async function syncFromMain() {
    const api = getElectronAPI()
    try {
      if (api?.getQqBridgeSettings) settings.value = await api.getQqBridgeSettings()
      if (api?.getQqBridgeStatus) bridgeStatus.value = await api.getQqBridgeStatus()
      if (api?.getQqHostStatus) hostStatus.value = await api.getQqHostStatus()
    } finally {
      loaded.value = true
    }
    // 幂等订阅：先解绑旧回调再绑新的，避免重复推送
    dispose()
    offBridge = api?.onQqBridgeStatus?.((s) => {
      if (s) bridgeStatus.value = s
    })
    offHost = api?.onQqHostStatus?.((s) => {
      if (s) hostStatus.value = s
      // 进入终态（running/error/stopped）时清空残留下载进度：下载失败/中断时
      // 进度会卡在 downloading/extracting（下载通道不发 error 相位，靠状态通道兜底置空）
      if (s?.state === 'running' || s?.state === 'error' || s?.state === 'stopped') {
        if (dlClearTimer) { clearTimeout(dlClearTimer); dlClearTimer = null }
        downloadProgress.value = null
      }
    })
    offDownload = api?.onQqHostDownload?.((p) => {
      // 新进度到达先取消未触发的清空定时，避免旧定时清掉新进度
      if (dlClearTimer) { clearTimeout(dlClearTimer); dlClearTimer = null }
      downloadProgress.value = p
      if (p?.phase === 'done') {
        // 下载/解压完成短暂保留进度，随后清空避免残留
        dlClearTimer = setTimeout(() => {
          dlClearTimer = null
          downloadProgress.value = null
        }, 1500)
      }
    })
    // 订阅就绪后开启兜底轮询（纠正漏推/丢推），dispose 时停止
    startPoll()
  }

  // 兜底对账：主进程推送是主路径，但漏推/丢推/错时挂载会让 UI 失步。
  // 每 5s 主动拉一次状态纠正；仅在桌面端运行，dispose 时停。
  // guardDowngrade：轮询路径置 true——快照把已就绪态回退到过渡态时跳过 hostStatus
  // （避免与 running 推送竞态闪烁）；主动回填（startHost 等）置 false，直接以主进程为准。
  async function refreshStatus({ guardDowngrade = false } = {}) {
    const api = getElectronAPI()
    if (!api) return
    try {
      if (api.getQqBridgeStatus) bridgeStatus.value = await api.getQqBridgeStatus()
      if (api.getQqHostStatus) {
        const incoming = await api.getQqHostStatus()
        const cur = hostStatus.value?.state
        if (guardDowngrade && HOST_TRANSIENT.has(incoming?.state) && HOST_SETTLED.has(cur)) {
          return // 过渡态快照不覆盖已就绪态，留给推送/下次轮询纠正
        }
        hostStatus.value = incoming
        // 兜底清进度：进入终态 running/error 时清残留下载进度，与 onHost 订阅
        // 回调互补——防止下载完成/失败的推送丢失时进度卡死模态弹窗。
        // 不含 stopped：主动回填路径（startHost 等）刚发起时主进程可能仍报
        // stopped（启动竞态），清掉会误清乐观 preparing；stopped 真实终态由
        // onHost 订阅推送负责清空（订阅回调见 onQqHostStatus 注册处）。
        if (incoming?.state === 'running' || incoming?.state === 'error') {
          if (dlClearTimer) { clearTimeout(dlClearTimer); dlClearTimer = null }
          downloadProgress.value = null
        }
      }
    } catch {
      /* ignore — 推送仍会对账 */
    }
  }

  function startPoll() {
    stopPoll()
    if (!getElectronAPI()) return // 浏览器/非桌面端不轮询
    pollTimer = setInterval(() => {
      refreshStatus({ guardDowngrade: true }).catch(() => {})
    }, 5000)
  }

  function stopPoll() {
    if (pollTimer) {
      clearInterval(pollTimer)
      pollTimer = null
    }
  }

  function dispose() {
    stopPoll()
    if (dlClearTimer) { clearTimeout(dlClearTimer); dlClearTimer = null }
    try {
      offBridge?.()
    } catch {
      /* ignore */
    }
    offBridge = null
    try {
      offHost?.()
    } catch {
      /* ignore */
    }
    offHost = null
    try {
      offDownload?.()
    } catch {
      /* ignore */
    }
    offDownload = null
  }

  async function setSettings(partial) {
    const api = getElectronAPI()
    if (!api?.setQqBridgeSettings) return null
    const res = await api.setQqBridgeSettings(partial)
    if (res?.settings) settings.value = res.settings
    return res
  }

  async function startBridge() {
    return getElectronAPI()?.startQqBridge?.()
  }

  async function stopBridge() {
    return getElectronAPI()?.stopQqBridge?.()
  }

  async function startHost(override) {
    const api = getElectronAPI()
    // 已安装（napcatVersion 有持久化值）则仅启动、不弹下载窗——避免「停止托管后再点
    // 启动却弹下载进度」的误判：entry 存在时 startNapCatHost 不下载，乐观 preparing
    // 会卡住模态弹窗。未安装才设乐观下载进度给用户即时反馈；reinstall 路径强制重下，
    // 在 reinstallHost 里另设乐观进度。启动中的 loading 由 actionInFlight 提供。
    const installed = !!settings.value?.hosting?.napcatVersion
    if (!installed) {
      if (dlClearTimer) { clearTimeout(dlClearTimer); dlClearTimer = null }
      downloadProgress.value = { phase: 'preparing', percent: 0 }
    }
    let res
    try {
      res = api?.startQqHost ? await api.startQqHost(override) : undefined
    } catch (e) {
      // IPC 异常（罕见）：清乐观进度，避免模态弹窗卡死；返回统一 {ok:false} 供 UI 提示
      downloadProgress.value = null
      refreshStatus().catch(() => {})
      return { ok: false, reason: 'exception', error: e?.message || String(e) }
    }
    if (res && res.ok === false) downloadProgress.value = null
    // 主动回填：不等推送，立即对账时序窗口（推送仍是主路径）
    refreshStatus().catch(() => {})
    return res
  }

  async function stopHost() {
    const api = getElectronAPI()
    const res = api?.stopQqHost ? await api.stopQqHost() : undefined
    refreshStatus().catch(() => {})
    return res
  }

  async function reinstallHost() {
    const api = getElectronAPI()
    // 同 startHost：乐观进度，点击即显示，覆盖/清空策略一致
    if (dlClearTimer) { clearTimeout(dlClearTimer); dlClearTimer = null }
    downloadProgress.value = { phase: 'preparing', percent: 0 }
    let res
    try {
      res = api?.reinstallQqHost ? await api.reinstallQqHost() : undefined
    } catch (e) {
      downloadProgress.value = null
      refreshStatus().catch(() => {})
      return { ok: false, reason: 'exception', error: e?.message || String(e) }
    }
    if (res && res.ok === false) downloadProgress.value = null
    refreshStatus().catch(() => {})
    return res
  }

  async function openLoginWindow() {
    return getElectronAPI()?.openQqLoginWindow?.()
  }

  // 按角色自动获取/绑定会话号：主进程 find/create 该角色 SINGLE 会话并写回 binding，再刷新本地镜像
  async function resolveConversation(characterId) {
    const api = getElectronAPI()
    if (!api?.resolveQqBridgeConversation) return { ok: false, reason: 'no_api' }
    const res = await api.resolveQqBridgeConversation(characterId)
    if (res?.ok) {
      const fresh = await api.getQqBridgeSettings?.()
      if (fresh) settings.value = fresh
    }
    return res
  }

  // 查看桥接日志：读 startup.log 尾部 300 行
  async function getLogs() {
    const api = getElectronAPI()
    if (!api?.getQqBridgeLogs) return { ok: false, reason: 'no_api', lines: [] }
    return api.getQqBridgeLogs()
  }

  return {
    settings,
    bridgeStatus,
    hostStatus,
    downloadProgress,
    loaded,
    syncFromMain,
    dispose,
    setSettings,
    startBridge,
    stopBridge,
    startHost,
    stopHost,
    reinstallHost,
    openLoginWindow,
    resolveConversation,
    getLogs,
  }
})
