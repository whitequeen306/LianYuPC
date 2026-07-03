import { createApp } from 'vue'
import { createPinia } from 'pinia'
import LauncherPage from '@/pages/LauncherPage.vue'
import { i18n } from '@/i18n'
import { initElectronRuntimeConfig } from '@/utils/runtime'
import { getElectronAPI } from '@/utils/electron'
import { bootstrapLauncherSession, applyLauncherAuthSession } from '@/auth/launcherBootstrap'

document.documentElement.classList.add('is-electron')
window.__lianyuAuxSurface = 'launcher'

const pinia = createPinia()
const app = createApp(LauncherPage)

app.config.errorHandler = (err, _instance, info) => {
  console.error('[launcher]', `${info}: ${err?.stack || err}`)
}

app.use(pinia)
app.use(i18n)

void initElectronRuntimeConfig()
app.mount('#app')
void bootstrapLauncherSession(pinia)
getElectronAPI()?.onAuthSessionUpdated?.((session) => {
  if (session?.token) {
    applyLauncherAuthSession(pinia, session)
  }
})
