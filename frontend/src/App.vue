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
const pageTransitionName = computed(() => (isElectron ? '' : 'page'))
const pageTransitionMode = computed(() => (isElectron ? undefined : 'out-in'))
const showElectronCaptionDrag = computed(() => isElectron && !isLauncherSurface.value)

function syncElectronCaptionClass(enabled) {
  document.body.classList.toggle('electron-app', !!enabled)
}

function viewKey(route) {
  if (route?.name === 'Chat' || route?.name === 'QuickChat') {
    return `ChatPage-${route.params.id || ''}`
  }
  return route.path
}

onMounted(async () => {
  if (isElectron) {
    syncElectronCaptionClass(!isLauncherSurface.value)
    const height = await getElectronAPI()?.getCaptionBarHeight?.()
    if (height) {
      document.documentElement.style.setProperty('--electron-caption-height', `${height}px`)
    }
  }

  if (userStore.token) {
    try {
      await userStore.fetchProfile()
    } catch {
      userStore.clearAuth()
    }
  }
})

watch(isLauncherSurface, (launcher) => {
  if (!isElectron) return
  syncElectronCaptionClass(!launcher)
})
</script>
