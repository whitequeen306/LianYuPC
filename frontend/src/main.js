import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import App from './App.vue'
import router from './router'
import { i18n } from './i18n'
import './styles/theme.scss'
import './styles/global.scss'

document.documentElement.classList.add('dark')

const app = createApp(App)
const pinia = createPinia()

// Register all Element Plus icons globally
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.use(pinia)
app.use(i18n)
app.use(router)
app.use(ElementPlus, { size: 'default' })

import { useSettingsStore } from '@/stores/settings'
const settingsStore = useSettingsStore(pinia)
settingsStore.initLanguage()
settingsStore.initTheme()

app.mount('#app')
