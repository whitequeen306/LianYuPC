import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { createRouter, createWebHashHistory } from 'vue-router'
import QuickChatLayout from '@/layouts/QuickChatLayout.vue'
import QuickChatPage from '@/pages/QuickChatPage.vue'
import { i18n } from '@/i18n'
import { initElectronRuntimeConfig } from '@/utils/runtime'
import { readToken } from '@/utils/secureToken'
import { bootstrapLauncherSession } from '@/auth/launcherBootstrap'
import { useSettingsStore } from '@/stores/settings'
import { getElectronAPI } from '@/utils/electron'
import '@/styles/theme.scss'

document.documentElement.classList.add('is-electron')
window.__lianyuAuxSurface = 'quick'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    {
      path: '/quick',
      component: QuickChatLayout,
      children: [
        {
          path: 'chat/:id',
          name: 'QuickChat',
          component: QuickChatPage,
        },
      ],
    },
  ],
})

const pinia = createPinia()
const app = createApp({ template: '<router-view />' })

app.config.errorHandler = (err, _instance, info) => {
  console.error('[quick-chat]', `${info}: ${err?.stack || err}`)
}

app.use(pinia)
app.use(i18n)
app.use(router)

const settingsStore = useSettingsStore(pinia)
settingsStore.initLanguage()
settingsStore.initAppearance()

;(async () => {
  void initElectronRuntimeConfig()
  await readToken()
  app.mount('#app')
  window.__lianyuNavigateQuickChat = (target) => router.push(target)
  void bootstrapLauncherSession(pinia)
})()
