import { ref, onBeforeUnmount } from 'vue'
import { getElectronAPI, isElectronApp } from '@/utils/electron'

export function shouldAutoOpenUpdateDialog(state) {
  return ['update-available', 'downloading', 'ready', 'installing', 'error'].includes(state)
}

/**
 * 应用自动更新状态机 composable。
 * 订阅主进程 updater:state 事件，暴露 state/info + check/download/install/openFolder actions。
 * 非 Electron 环境下 isElectron=false，actions 为 no-op。
 */
export function useAppUpdater() {
  const state = ref('idle')
  const info = ref({})
  const isElectron = ref(isElectronApp())

  /** @type {(() => void) | null} */
  let unsubscribe = null
  if (isElectron.value) {
    const api = getElectronAPI()
    unsubscribe = api?.onUpdateState?.((payload) => {
      if (!payload) return
      state.value = payload.state
      info.value = payload.info || {}
    })
  }

  onBeforeUnmount(() => {
    unsubscribe?.()
    unsubscribe = null
  })

  async function check() {
    if (!isElectron.value) return
    return getElectronAPI()?.checkForUpdates?.()
  }
  /**
   * 启动时的静默探测：只拿「有没有新版本」，不推进主进程 state 机。
   * 与 {@link check} 分开是为了不让启动提示自动打开更新对话框。
   */
  async function probe() {
    if (!isElectron.value) return null
    try {
      return (await getElectronAPI()?.probeForUpdates?.()) || null
    } catch {
      return null
    }
  }
  async function download() {
    if (!isElectron.value) return
    return getElectronAPI()?.downloadUpdate?.()
  }
  async function install() {
    if (!isElectron.value) return
    return getElectronAPI()?.installNow?.()
  }
  async function openInstallerFolder() {
    if (!isElectron.value) return
    return getElectronAPI()?.openInstallerFolder?.()
  }

  return { state, info, isElectron, check, probe, download, install, openInstallerFolder }
}
