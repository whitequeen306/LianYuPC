<template>
  <el-config-provider :locale="elementLocale">
    <div
      v-if="showElectronCaptionDrag"
      class="electron-caption-drag"
      aria-hidden="true"
    />
    <router-view v-slot="{ Component, route }">
      <transition :name="pageTransitionName" :mode="pageTransitionMode">
        <component :is="Component" :key="viewKey(route)" />
      </transition>
    </router-view>
  </el-config-provider>
</template>

<script setup>
import { computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { useSettingsStore } from '@/stores/settings'
import { isElectronRuntime } from '@/utils/runtime'
import { getElectronAPI } from '@/utils/electron'
import { syncElectronTitleBar } from '@/utils/electronCaption'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import ja from 'element-plus/es/locale/lang/ja'
import en from 'element-plus/es/locale/lang/en'

const userStore = useUserStore()
const settingsStore = useSettingsStore()
const route = useRoute()

const elementLocaleMap = { zh: zhCn, ja, en }
const elementLocale = computed(() => elementLocaleMap[settingsStore.uiLanguage] || zhCn)

/** Electron file:// 下禁用路由过渡，避免 out-in 中间态黑屏 */
const isElectron = isElectronRuntime()
const isLauncherSurface = computed(() => route.name === 'Launcher' || route.name === 'LauncherPick')
const isQuickChatSurface = computed(() => route.path.startsWith('/quick'))
const usesAppHeader = computed(() => route.path.startsWith('/app'))
const pageTransitionName = computed(() => (isElectron ? '' : 'page'))
const pageTransitionMode = computed(() => (isElectron ? undefined : 'out-in'))
const usesIntegratedCaption = computed(() => {
  const name = route.name
  if (name === 'Landing' || name === 'Login' || name === 'Register') return true
  // immersive 单聊隐藏 AppHeader，由 gal-header 或 electron-caption-drag 接管
  if (usesAppHeader.value && route.meta.immersive !== true) return true
  return false
})
/** 主界面 / 落地页 / 登录注册由页面顶栏充当标题栏，不再单独顶出拖拽条 */
const showElectronCaptionDrag = computed(() => (
  isElectron && !isLauncherSurface.value && !isQuickChatSurface.value && !usesIntegratedCaption.value
))

function syncElectronChrome() {
  if (!isElectron) return
  syncElectronTitleBar({
    routeName: route.name,
    routePath: route.path,
    theme: settingsStore.theme,
  })
}

function syncElectronCaptionClass(enabled) {
  document.body.classList.toggle('electron-app', !!enabled)
}

function viewKey(route) {
  if (route?.name === 'Chat' || route?.name === 'QuickChat') {
    return `ChatPage-${route.params.id || ''}`
  }
  return route.path
}

function applyCaptionMetrics(metrics) {
  if (!metrics) return
  const { height, controlsWidth } = metrics
  if (height) {
    document.documentElement.style.setProperty('--electron-caption-height', `${height}px`)
  }
  if (controlsWidth) {
    document.documentElement.style.setProperty('--electron-caption-controls-width', `${controlsWidth}px`)
  }
}

onMounted(async () => {
  if (isElectron) {
    syncElectronCaptionClass(!isLauncherSurface.value && !isQuickChatSurface.value)
    const api = getElectronAPI()
    const metrics = (await api?.getCaptionMetrics?.()) || {}
    if (!metrics.height) {
      const height = await api?.getCaptionBarHeight?.()
      if (height) metrics.height = height
    }
    applyCaptionMetrics(metrics)
    api?.onCaptionMetrics?.(applyCaptionMetrics)
    syncElectronChrome()
  }

  if (userStore.isLoggedIn && !userStore.userId && !isLauncherSurface.value) {
    try {
      await userStore.fetchProfile({ skipGlobalError: true })
    } catch (err) {
      const msg = err?.message || ''
      if (/401|登录已过期|unauthorized/i.test(msg)) {
        await userStore.clearAuth({ keepUsername: true })
      }
    }
  }
})

watch(isLauncherSurface, (launcher) => {
  if (!isElectron) return
  syncElectronCaptionClass(!launcher && !isQuickChatSurface.value)
})

watch(isQuickChatSurface, (quick) => {
  if (!isElectron) return
  syncElectronCaptionClass(!quick && !isLauncherSurface.value)
})

watch(
  () => [route.name, route.path, settingsStore.theme],
  () => syncElectronChrome(),
)
</script>
