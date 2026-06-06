<template>
  <div class="quick-chat-layout">
    <header v-if="showBar" class="quick-chat-bar glass-strong">
      <div class="quick-chat-bar__meta">
        <span class="quick-chat-bar__title">{{ barTitle }}</span>
      </div>
      <div class="quick-chat-bar__actions">
        <el-button text size="small" @click="expandToMain">展开</el-button>
        <el-button text size="small" @click="closeWindow">关闭</el-button>
      </div>
    </header>
    <main class="quick-chat-layout__body">
      <router-view />
    </main>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { getElectronAPI } from '@/utils/electron'

const route = useRoute()

const showBar = computed(() => route.name === 'QuickChat')
const barTitle = computed(() => route.meta?.quickTitle || '快速聊天')

function expandToMain() {
  const id = route.params.id
  getElectronAPI()?.openMainWindow?.(id ? `#/app/chat/${id}` : '#/app')
  getElectronAPI()?.closeQuickChat?.()
}

function closeWindow() {
  getElectronAPI()?.closeQuickChat?.()
}
</script>

<style lang="scss" scoped>
.quick-chat-layout {
  display: flex;
  flex-direction: column;
  min-height: calc(100vh - var(--electron-caption-height, 0px));
  background: #0a0a10;
}

.quick-chat-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
  flex-shrink: 0;
}

.quick-chat-bar__title {
  font-size: 14px;
  font-weight: 600;
  color: #f5f5f7;
}

.quick-chat-bar__actions {
  display: flex;
  gap: 4px;
}

.quick-chat-layout__body {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}
</style>
