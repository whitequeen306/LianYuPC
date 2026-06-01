<template>
  <div class="app-layout" :class="{ 'sidebar-collapsed': settingsStore.sidebarCollapsed }">
    <AppSidebar />
    <div class="app-main">
      <AppHeader />
      <main class="app-content">
        <router-view />
      </main>
    </div>
  </div>
</template>

<script setup>
import { onMounted, onUnmounted } from 'vue'
import AppSidebar from '@/components/AppSidebar.vue'
import AppHeader from '@/components/AppHeader.vue'
import { useSettingsStore } from '@/stores/settings'
import { useNotificationsStore } from '@/stores/notifications'

const settingsStore = useSettingsStore()
const notificationsStore = useNotificationsStore()

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
  min-height: 100vh;
  position: relative;
}

.app-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  margin-left: $sidebar-width;
  transition: margin-left $transition-slow;
  min-width: 0;
}

.app-content {
  flex: 1;
  padding: $space-6 $space-8;
  max-width: $max-content-width;
  width: 100%;
  margin: 0 auto;
  position: relative;
  z-index: 1;
}

.sidebar-collapsed .app-main {
  margin-left: $sidebar-collapsed;
}
</style>
