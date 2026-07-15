import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import App from './App.vue'
import router from './router'
import { i18n } from './i18n'
import { initElectronRuntimeConfig } from '@/utils/runtime'
import { dismissBootSplash, showBootSplashError } from '@/utils/bootSplash'
import { readToken } from './utils/secureToken'
import { installMainHttpToasts } from '@/api/installMainHttpToasts'
import { prepareAuthRoute, bootstrapAuth } from './auth/bootstrap'
import { bootstrapLauncherSession } from './auth/launcherBootstrap'
import { recoverFromStartupRouteError } from './router/startupErrorRecovery'
import * as rendererLogger from './utils/logger'
import { createStartupProfiler } from '@/utils/startupProfiler'
import './styles/theme.scss'
import './styles/global.scss'
import './styles/app-shell.scss'
import directivesPlugin from './directives'

const startupRendererProfiler = createStartupProfiler({
  prefix: 'startup-renderer',
  log: (message) => rendererLogger.info('startup', message),
})

startupRendererProfiler.mark('entry')

const isElectronRuntime = typeof window !== 'undefined' && (
  window.electronAPI?.isElectron === true
  || /Electron/i.test(window.navigator.userAgent)
)
if (isElectronRuntime) {
  document.documentElement.classList.add('is-electron')
}

// ---- 全局错误捕获（须在 initAntiDebug 之前注册） ----
// window.onerror：捕获未处理的运行时错误（同步）
window.addEventListener('error', (event) => {
  const detail = event.error?.stack || event.message || String(event.error || event.message)
  rendererLogger.error('window-error', `${event.filename || ''}:${event.lineno || 0}:${event.colno || 0}`, detail)
})

// unhandledrejection：捕获未处理的 Promise rejection
window.addEventListener('unhandledrejection', (event) => {
  const reason = event.reason
  const detail = reason instanceof Error ? (reason.stack || reason.message) : String(reason)
  rendererLogger.error('unhandled-rejection', detail)
})

const app = createApp(App)

app.config.errorHandler = (err, instance, info) => {
  const detail = `${info}: ${err?.stack || err}`
  // 保留原有内存兜底
  if (typeof window !== 'undefined') {
    window.__vueErr = (window.__vueErr || []).concat(detail)
  }
  // 落盘到全局日志
  rendererLogger.error('vue-error', detail)
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

installMainHttpToasts()

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

const LAST_ROUTE_KEY = 'lianyu-last-route'

router.afterEach((to) => {
  try {
    const token = syncToken()
    if (!token) return
    if (!to?.path || typeof to.path !== 'string') return
    if (!to.path.startsWith('/app')) return
    localStorage.setItem(LAST_ROUTE_KEY, to.path)
  } catch {
    // ignore storage failures
  }
})

router.onError((err) => {
  const msg = err?.message || String(err)
  if (recoverFromStartupRouteError(err)) return
  showBootSplashError('界面加载失败，请重启应用或重新安装。')
  if (typeof window !== 'undefined') {
    window.__vueErr = (window.__vueErr || []).concat(`router: ${msg}`)
  }
})

;(async () => {
  startupRendererProfiler.mark('iife:start')
  void initElectronRuntimeConfig()
  startupRendererProfiler.mark('initElectronRuntimeConfig:queued')

  const aux = isDesktopAuxSurface()
  if (isQuickChatSurface()) {
    window.__lianyuNavigateQuickChat = (target) => router.push(target)
  }

  if (!aux) {
    startupRendererProfiler.mark('prepareAuthRoute:start')
    try {
      await prepareAuthRoute(pinia)
    } catch {
      // keep anonymous startup path if lightweight auth bootstrap fails
    } finally {
      startupRendererProfiler.mark('prepareAuthRoute:done')
    }
  }

  app.mount('#app')
  startupRendererProfiler.mark('app:mounted')

  try {
    await router.isReady()
    startupRendererProfiler.mark('router:isReady')
  } catch {
    showBootSplashError('启动失败，请重新安装最新版本。')
    return
  } finally {
    dismissBootSplash()
    startupRendererProfiler.mark('bootSplash:dismissed')
  }

  if (aux) {
    startupRendererProfiler.mark('bootstrapLauncherSession:start')
    void bootstrapLauncherSession(pinia).finally(() => {
      startupRendererProfiler.mark('bootstrapLauncherSession:done')
    })
  } else {
    startupRendererProfiler.mark('bootstrapAuth:start')
    void readToken().then(() => bootstrapAuth(pinia)).finally(() => {
      startupRendererProfiler.mark('bootstrapAuth:done')
    })
  }

  if (aux && isQuickChatSurface()) {
    const { useUserStore } = await import('@/stores/user')
    void useUserStore(pinia).fetchProfile({ skipGlobalError: true }).catch(() => {})
  }

  // 空闲预热相邻路由 chunk：解析+编译进内存，消除首次进页的 JS parse 抖动（非 aux 表面）
  if (!aux) {
    import('@/composables/useRouteTransition').then(({ prefetchRoutesOnIdle }) => {
      prefetchRoutesOnIdle()
    }).catch(() => {})
  }
})()
