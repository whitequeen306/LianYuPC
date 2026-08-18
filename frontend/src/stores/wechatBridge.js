import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getElectronAPI } from '@/utils/electron'

export const useWechatBridgeStore = defineStore('wechatBridge', () => {
  const settings = ref(null)
  const hostStatus = ref({
    state: 'stopped',
    running: false,
    loggedIn: false,
    installed: false,
    version: '',
    qrDataUrl: '',
    qrText: '',
    lastError: '',
  })
  const downloadProgress = ref(null)
  const loaded = ref(false)

  let offHost = null
  let offDownload = null
  let dlClearTimer = null

  async function syncFromMain() {
    const api = getElectronAPI()
    try {
      if (api?.getWechatBridgeSettings) settings.value = await api.getWechatBridgeSettings()
      if (api?.getWechatHostStatus) hostStatus.value = await api.getWechatHostStatus()
    } finally {
      loaded.value = true
    }
    dispose()
    offHost = api?.onWechatHostStatus?.((s) => {
      if (s) hostStatus.value = { ...hostStatus.value, ...s }
    })
    offDownload = api?.onWechatHostDownload?.((p) => {
      downloadProgress.value = p
      if (p?.phase === 'done' || p?.phase === 'error') {
        clearTimeout(dlClearTimer)
        dlClearTimer = setTimeout(() => { downloadProgress.value = null }, 1200)
      }
    })
  }

  function dispose() {
    offHost?.()
    offDownload?.()
    offHost = null
    offDownload = null
    clearTimeout(dlClearTimer)
  }

  async function setSettings(partial) {
    const api = getElectronAPI()
    const res = await api?.setWechatBridgeSettings?.(partial)
    if (res?.settings) settings.value = res.settings
    return res
  }

  async function startHost() {
    const res = await getElectronAPI()?.startWechatHost?.()
    await refreshStatus()
    return res
  }

  async function stopHost() {
    const res = await getElectronAPI()?.stopWechatHost?.()
    await refreshStatus()
    return res
  }

  async function reinstallHost() {
    return getElectronAPI()?.reinstallWechatHost?.()
  }

  async function requestLogin() {
    return getElectronAPI()?.openWechatLogin?.()
  }

  async function refreshStatus() {
    const api = getElectronAPI()
    if (api?.getWechatHostStatus) hostStatus.value = await api.getWechatHostStatus()
    if (api?.getWechatBridgeSettings) settings.value = await api.getWechatBridgeSettings()
  }

  return {
    settings,
    hostStatus,
    downloadProgress,
    loaded,
    syncFromMain,
    dispose,
    setSettings,
    startHost,
    stopHost,
    reinstallHost,
    requestLogin,
    refreshStatus,
  }
})
