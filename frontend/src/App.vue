<template>
  <el-config-provider :locale="elementLocale">
    <router-view v-slot="{ Component, route }">
      <transition :name="pageTransitionName" :mode="pageTransitionMode">
        <component :is="Component" :key="viewKey(route)" />
      </transition>
    </router-view>
  </el-config-provider>
</template>

<script setup>
import { computed, onMounted } from 'vue'
import { useUserStore } from '@/stores/user'
import { useSettingsStore } from '@/stores/settings'
import { isElectronRuntime } from '@/utils/runtime'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import ja from 'element-plus/es/locale/lang/ja'
import en from 'element-plus/es/locale/lang/en'

const userStore = useUserStore()
const settingsStore = useSettingsStore()

const elementLocaleMap = { zh: zhCn, ja, en }
const elementLocale = computed(() => elementLocaleMap[settingsStore.uiLanguage] || zhCn)

/** Electron file:// 下禁用路由过渡，避免 out-in 中间态黑屏 */
const pageTransitionName = computed(() => (isElectronRuntime() ? '' : 'page'))
const pageTransitionMode = computed(() => (isElectronRuntime() ? undefined : 'out-in'))

function viewKey(route) {
  if (route?.name === 'Chat' || route?.name === 'QuickChat') {
    return `ChatPage-${route.params.id || ''}`
  }
  return route.path
}

onMounted(async () => {
  if (userStore.token) {
    try {
      await userStore.fetchProfile()
    } catch {
      userStore.clearAuth()
    }
  }
})
</script>
