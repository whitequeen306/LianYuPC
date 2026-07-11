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

  return { state, info, isElectron, check, download, install, openInstallerFolder }
}
