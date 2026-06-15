import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getElectronAPI } from '@/utils/electron'
import { DEFAULT_PET_ID } from '@/constants/petCatalog'

const STORAGE_CLOSE_TO_TRAY = 'lianyu-close-to-tray'
const STORAGE_SHOW_LAUNCHER = 'lianyu-show-launcher'
const STORAGE_SHOW_DESKTOP_PET = 'lianyu-show-desktop-pet'
const STORAGE_LAUNCH_AT_LOGIN = 'lianyu-launch-at-login'
const STORAGE_LAUNCHER_PET = 'lianyu-launcher-pet'
const STORAGE_ALLOW_SCREEN_OBSERVE = 'lianyu-allow-screen-observe'

function readBool(key, fallback) {
  const raw = localStorage.getItem(key)
  if (raw === 'true') return true
  if (raw === 'false') return false
  return fallback
}

export const useDesktopStore = defineStore('desktop', () => {
  const closeToTray = ref(readBool(STORAGE_CLOSE_TO_TRAY, true))
  const showDesktopPet = ref(
    readBool(STORAGE_SHOW_DESKTOP_PET, readBool(STORAGE_SHOW_LAUNCHER, true))
  )
  const showLauncherLogo = ref(readBool(STORAGE_SHOW_LAUNCHER, showDesktopPet.value))
  const launchAtLogin = ref(readBool(STORAGE_LAUNCH_AT_LOGIN, false))
  const allowScreenObserve = ref(readBool(STORAGE_ALLOW_SCREEN_OBSERVE, false))
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
      showDesktopPet.value = settings.showDesktopPet !== false
      showLauncherLogo.value = showDesktopPet.value
      launchAtLogin.value = settings.launchAtLogin === true
      allowScreenObserve.value = settings.allowScreenObserve === true
      launcherPetId.value = settings.launcherPetId || DEFAULT_PET_ID
      localStorage.setItem(STORAGE_CLOSE_TO_TRAY, String(closeToTray.value))
      localStorage.setItem(STORAGE_SHOW_DESKTOP_PET, String(showDesktopPet.value))
      localStorage.setItem(STORAGE_SHOW_LAUNCHER, String(showDesktopPet.value))
      localStorage.setItem(STORAGE_LAUNCH_AT_LOGIN, String(launchAtLogin.value))
      localStorage.setItem(STORAGE_ALLOW_SCREEN_OBSERVE, String(allowScreenObserve.value))
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
    if (partial.showDesktopPet != null) {
      showDesktopPet.value = partial.showDesktopPet
      showLauncherLogo.value = partial.showDesktopPet
      localStorage.setItem(STORAGE_SHOW_DESKTOP_PET, String(partial.showDesktopPet))
      localStorage.setItem(STORAGE_SHOW_LAUNCHER, String(partial.showDesktopPet))
    } else if (partial.showLauncherLogo != null) {
      showDesktopPet.value = partial.showLauncherLogo
      showLauncherLogo.value = partial.showLauncherLogo
      localStorage.setItem(STORAGE_SHOW_DESKTOP_PET, String(partial.showLauncherLogo))
      localStorage.setItem(STORAGE_SHOW_LAUNCHER, String(partial.showLauncherLogo))
    }
    if (partial.launchAtLogin != null) {
      launchAtLogin.value = partial.launchAtLogin
      localStorage.setItem(STORAGE_LAUNCH_AT_LOGIN, String(partial.launchAtLogin))
    }
    if (partial.allowScreenObserve != null) {
      allowScreenObserve.value = partial.allowScreenObserve
      localStorage.setItem(STORAGE_ALLOW_SCREEN_OBSERVE, String(partial.allowScreenObserve))
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
    showDesktopPet,
    showLauncherLogo,
    allowScreenObserve,
    launchAtLogin,
    launcherPetId,
    windowKind,
    loaded,
    syncFromMain,
    persist,
    detectWindowKind,
  }
})
