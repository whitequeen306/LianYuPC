import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getElectronAPI } from '@/utils/electron'
import { DEFAULT_PET_ID } from '@/constants/petCatalog'

const STORAGE_CLOSE_TO_TRAY = 'lianyu-close-to-tray'
const STORAGE_SHOW_LAUNCHER = 'lianyu-show-launcher'
const STORAGE_LAUNCH_AT_LOGIN = 'lianyu-launch-at-login'
const STORAGE_LAUNCHER_PET = 'lianyu-launcher-pet'

function readBool(key, fallback) {
  const raw = localStorage.getItem(key)
  if (raw === 'true') return true
  if (raw === 'false') return false
  return fallback
}

export const useDesktopStore = defineStore('desktop', () => {
  const closeToTray = ref(readBool(STORAGE_CLOSE_TO_TRAY, true))
  const showLauncherLogo = ref(readBool(STORAGE_SHOW_LAUNCHER, true))
  const launchAtLogin = ref(readBool(STORAGE_LAUNCH_AT_LOGIN, false))
  const launcherPetId = ref(localStorage.getItem(STORAGE_LAUNCHER_PET) || DEFAULT_PET_ID)
  const windowKind = ref('main')
  const loaded = ref(false)

  async function syncFromMain() {
    const api = getElectronAPI()
    if (!api?.getDesktopSettings) {
      loaded.value = true
      return
    }
    try {
      const settings = await api.getDesktopSettings()
      closeToTray.value = settings.closeToTray !== false
      showLauncherLogo.value = settings.showLauncherLogo !== false
      launchAtLogin.value = settings.launchAtLogin === true
      launcherPetId.value = settings.launcherPetId || DEFAULT_PET_ID
      localStorage.setItem(STORAGE_CLOSE_TO_TRAY, String(closeToTray.value))
      localStorage.setItem(STORAGE_SHOW_LAUNCHER, String(showLauncherLogo.value))
      localStorage.setItem(STORAGE_LAUNCH_AT_LOGIN, String(launchAtLogin.value))
      localStorage.setItem(STORAGE_LAUNCHER_PET, launcherPetId.value)
    } finally {
      loaded.value = true
    }
  }

  async function persist(partial) {
    if (partial.closeToTray != null) {
      closeToTray.value = partial.closeToTray
      localStorage.setItem(STORAGE_CLOSE_TO_TRAY, String(partial.closeToTray))
    }
    if (partial.showLauncherLogo != null) {
      showLauncherLogo.value = partial.showLauncherLogo
      localStorage.setItem(STORAGE_SHOW_LAUNCHER, String(partial.showLauncherLogo))
    }
    if (partial.launchAtLogin != null) {
      launchAtLogin.value = partial.launchAtLogin
      localStorage.setItem(STORAGE_LAUNCH_AT_LOGIN, String(partial.launchAtLogin))
    }
    if (partial.launcherPetId != null) {
      launcherPetId.value = partial.launcherPetId
      localStorage.setItem(STORAGE_LAUNCHER_PET, partial.launcherPetId)
    }
    const api = getElectronAPI()
    if (api?.setDesktopSettings) {
      await api.setDesktopSettings(partial)
    }
  }

  async function detectWindowKind() {
    const api = getElectronAPI()
    if (!api?.getWindowKind) return
    windowKind.value = await api.getWindowKind()
  }

  return {
    closeToTray,
    showLauncherLogo,
    launchAtLogin,
    launcherPetId,
    windowKind,
    loaded,
    syncFromMain,
    persist,
    detectWindowKind,
  }
})
