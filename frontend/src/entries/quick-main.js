import { createApp, h } from 'vue'
import { createPinia } from 'pinia'
import { createRouter, createWebHashHistory, RouterView } from 'vue-router'
import QuickChatLayout from '@/layouts/QuickChatLayout.vue'
import QuickChatPage from '@/pages/QuickChatPage.vue'
import { i18n } from '@/i18n'
import { initElectronRuntimeConfig } from '@/utils/runtime'
import { readToken } from '@/utils/secureToken'
import { bootstrapLauncherSession } from '@/auth/launcherBootstrap'
import { useSettingsStore } from '@/stores/settings'
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
// runtime-only Vue 不支持字符串 template，须用 render + RouterView
const app = createApp({ render: () => h(RouterView) })

app.config.errorHandler = (err, _instance, info) => {
  console.error('[quick-chat]', `${info}: ${err?.stack || err}`)
}

app.use(pinia)
app.use(i18n)
app.use(router)

const settingsStore = useSettingsStore(pinia)
settingsStore.initLanguage()
settingsStore.initAppearance()

void initElectronRuntimeConfig()
app.mount('#app')
window.__lianyuNavigateQuickChat = (target) => router.push(target)
void readToken().then(() => bootstrapLauncherSession(pinia))
