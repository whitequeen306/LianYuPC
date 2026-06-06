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
        <router-view />
      </main>
    </div>
    <AppDock v-if="!hideDock" />
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import AppHeader from '@/components/AppHeader.vue'
import AppDock from '@/components/AppDock.vue'
import AppShellAtmosphere from '@/components/AppShellAtmosphere.vue'
import { useNotificationsStore } from '@/stores/notifications'
import { useLayoutStore } from '@/stores/layout'
import { useDesktopCloseHint } from '@/composables/useDesktopCloseHint'

const route = useRoute()
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
</script>

<style lang="scss" scoped>
.app-layout {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  position: relative;
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

.app-content {
  flex: 1;
  width: 100%;
  max-width: $max-content-width;
  margin: 0 auto;
  padding: $space-5 $space-5 calc(#{$app-dock-offset} + env(safe-area-inset-bottom, 0px));
}

.app-layout--immersive .app-content {
  max-width: none;
  padding: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  min-height: 0;
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

</style>
