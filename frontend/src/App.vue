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
    <AppUpdateDialog v-if="isElectron" />
  </el-config-provider>
</template>

<script setup>
import { computed, onMounted, watch, onBeforeUnmount } from 'vue'
import { useRoute } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { useSettingsStore } from '@/stores/settings'
import { isElectronRuntime } from '@/utils/runtime'
import { getElectronAPI } from '@/utils/electron'
import { syncElectronTitleBar } from '@/utils/electronCaption'
import { ElMessageBox } from 'element-plus'
import AppUpdateDialog from '@/components/AppUpdateDialog.vue'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import ja from 'element-plus/es/locale/lang/ja'
import en from 'element-plus/es/locale/lang/en'

const userStore = useUserStore()
const settingsStore = useSettingsStore()
const route = useRoute()

const elementLocaleMap = { zh: zhCn, ja, en }
const elementLocale = computed(() => elementLocaleMap[settingsStore.uiLanguage] || zhCn)

/** 路由过渡：桌面端与 web 端统一启用 out-in 淡入淡出。
 *  早期 Electron file:// 下曾因 out-in 中间态 #app 高度塌缩露黑底而禁用；
 *  现已给 #app 补 min-height:100vh + 稳定背景兜底，中间态显示背景色而非黑屏。 */
const isElectron = isElectronRuntime()
const isLauncherSurface = computed(() => route.name === 'Launcher' || route.name === 'LauncherPick')
const isQuickChatSurface = computed(() => route.path.startsWith('/quick'))
const usesAppHeader = computed(() => route.path.startsWith('/app'))
const pageTransitionName = computed(() => 'page')
const pageTransitionMode = computed(() => 'out-in')
const usesIntegratedCaption = computed(() => {
  const name = route.name
  if (name === 'Landing' || name === 'Login' || name === 'Register') return true
  // immersive 单聊隐藏 AppHeader，由 gal-header 或 electron-caption-drag 接管
  if (usesAppHeader.value && route.meta.immersive !== true) return true
  return false
})
/** 主界面 / 落地页 / 登录注册由页面顶栏充当标题栏，不再单独顶出拖拽条 */
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

/** QQ 桥接掉线/被踢弹窗（软件内 ElMessageBox 弹窗）
 *  main.js 通过 IPC desktop:qq-bridge-alert 推送（同时会发系统通知，此处仅处理软件内弹窗）。
 *  同类型弹窗 30s 防抖，避免重启期间 WS 抖动刷屏。 */
const qqAlertTitles = {
  kicked: 'QQ 已掉线',
  disconnected: 'QQ 桥接掉线',
  not_logged_in: 'QQ 未登录',
  restart_failed: 'NapCat 重启失败',
}
let qqAlertLastTs = {}
let qqAlertUnsub = null
function handleQqBridgeAlert({ type, message }) {
  if (!type || !message) return
  const now = Date.now()
  if (qqAlertLastTs[type] && now - qqAlertLastTs[type] < 30000) return
  qqAlertLastTs[type] = now
  ElMessageBox.alert(message, qqAlertTitles[type] || 'QQ 桥接提醒', {
    confirmButtonText: '知道了',
    type: type === 'restart_failed' ? 'error' : 'warning',
  }).catch(() => {})
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
    // QQ 桥接掉线/被踢弹窗
    qqAlertUnsub = api?.onQqBridgeAlert?.(handleQqBridgeAlert)
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

onBeforeUnmount(() => {
  qqAlertUnsub?.()
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
