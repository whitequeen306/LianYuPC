<template>
  <div
    class="app-layout"
    :class="{
      'app-layout--immersive': isImmersive,
      'app-layout--dock-hidden': hideDock,
      'app-layout--group-session': layoutStore.groupChatSession,
    }"
  >
    <AppShellAtmosphere />
    <div class="app-main">
      <AppHeader v-if="!isImmersive" />
      <main class="app-content">
        <router-view v-slot="{ Component }">
          <component :is="Component" v-if="Component" />
          <section v-else class="app-recovery glass">
            <h2>页面加载失败</h2>
            <p>主内容未能显示。可尝试重新登录，或安装最新客户端后重试。</p>
            <div class="app-recovery__actions">
              <el-button type="primary" @click="reloadApp">重新加载</el-button>
              <el-button @click="forceLogout">退出并重新登录</el-button>
            </div>
          </section>
        </router-view>
      </main>
    </div>
    <AppDock v-if="!hideDock" />
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppHeader from '@/components/AppHeader.vue'
import AppDock from '@/components/AppDock.vue'
import AppShellAtmosphere from '@/components/AppShellAtmosphere.vue'
import { useNotificationsStore } from '@/stores/notifications'
import { useLayoutStore } from '@/stores/layout'
import { useDesktopCloseHint } from '@/composables/useDesktopCloseHint'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const notificationsStore = useNotificationsStore()
const layoutStore = useLayoutStore()
useDesktopCloseHint()

const isImmersive = computed(() => route.meta.immersive === true)
const hideDock = computed(() => route.meta.hideDock === true || isImmersive.value)

onMounted(() => {
  notificationsStore.init()
})

onUnmounted(() => {
  notificationsStore.dispose()
})

function reloadApp() {
  window.location.reload()
}

async function forceLogout() {
  await userStore.clearAuth({ keepUsername: true })
  await router.replace('/login')
}
</script>

<style lang="scss" scoped>
.app-layout {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  position: relative;
}

.app-layout--immersive {
  min-height: calc(100vh - var(--electron-caption-height, 0px));
  height: calc(100vh - var(--electron-caption-height, 0px));
}

.app-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  position: relative;
  z-index: 1;
}

.app-layout--immersive .app-main {
  flex: 1;
  min-height: 0;
}

.app-content {
  flex: 1;
  width: 100%;
  max-width: $max-content-width;
  margin: 0 auto;
  padding: $space-5 $space-5 $space-5;
}

.app-layout--immersive .app-content {
  max-width: none;
  padding: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  min-height: 0;
  flex: 1;
}

.app-layout--dock-hidden .app-content {
  padding-bottom: $space-5;
}

.app-layout--group-session .app-content {
  display: flex;
  flex-direction: column;
  min-height: 0;
  flex: 1;
  overflow: hidden;
  max-width: none;
  padding-top: 0;
  padding-left: 0;
  padding-right: 0;
}

.app-recovery {
  margin-top: $space-8;
  padding: $space-8;
  border-radius: $radius-lg;
  text-align: center;

  h2 {
    margin-bottom: $space-3;
    font-size: $font-size-lg;
    color: $color-text-primary;
  }

  p {
    margin-bottom: $space-6;
    color: $color-text-secondary;
    line-height: $line-height-relaxed;
  }

  &__actions {
    display: flex;
    justify-content: center;
    gap: $space-3;
    flex-wrap: wrap;
  }
}

</style>
