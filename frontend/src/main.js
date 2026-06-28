import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import App from './App.vue'
import router from './router'
import { i18n } from './i18n'
import { initAntiDebug } from './utils/antiDebug'
import { initElectronRuntimeConfig } from '@/utils/runtime'
import { prepareAuthRoute, bootstrapAuth } from './auth/bootstrap'
import { bootstrapLauncherSession } from './auth/launcherBootstrap'
import './styles/theme.scss'
import './styles/global.scss'
import './styles/app-shell.scss'
import directivesPlugin from './directives'

const isElectronRuntime = typeof window !== 'undefined' && (
  window.electronAPI?.isElectron === true
  || /Electron/i.test(window.navigator.userAgent)
)
if (isElectronRuntime) {
  document.documentElement.classList.add('is-electron')
}

const app = createApp(App)

app.config.errorHandler = (err, instance, info) => {
  const detail = `${info}: ${err?.stack || err}`
  console.error('[VUE-ERROR]', detail)
  if (typeof window !== 'undefined') {
    window.__vueErr = (window.__vueErr || []).concat(detail)
  }
}

const pinia = createPinia()

app.use(pinia)
app.use(i18n)
app.use(router)
app.use(ElementPlus, { size: 'default' })
app.use(directivesPlugin)

import { useSettingsStore } from '@/stores/settings'
const settingsStore = useSettingsStore(pinia)
settingsStore.initLanguage()
settingsStore.initAppearance()

initAntiDebug()

function isLauncherOnlySurface() {
  const hash = (window.location.hash.replace(/^#/, '') || '/').split('?')[0]
  return hash === '/launcher' || hash.startsWith('/launcher/')
}

function isQuickChatSurface() {
  const hash = (window.location.hash.replace(/^#/, '') || '/').split('?')[0]
  return hash.startsWith('/quick/')
}

function isDesktopAuxSurface() {
  return isLauncherOnlySurface() || isQuickChatSurface()
}

;(async () => {
  await initElectronRuntimeConfig()
  if (isDesktopAuxSurface()) {
    await bootstrapLauncherSession(pinia)
  } else {
    await prepareAuthRoute(pinia)
  }
  if (isQuickChatSurface()) {
    window.__lianyuNavigateQuickChat = (target) => router.push(target)
  }
  app.mount('#app')
  if (!isDesktopAuxSurface()) {
    await bootstrapAuth(pinia)
  } else if (isQuickChatSurface()) {
    const { useUserStore } = await import('@/stores/user')
    void useUserStore(pinia).fetchProfile({ skipGlobalError: true }).catch(() => {})
  }
})()
