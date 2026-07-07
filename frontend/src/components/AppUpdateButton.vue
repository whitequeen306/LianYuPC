<template>
  <div class="app-update-button">
    <button
      type="button"
      class="upd-btn"
      :class="btnClass"
      :disabled="busy"
      :title="tooltip"
      @click="onClick"
    >
      <span v-if="busy" class="upd-spin" />
      {{ btnText }}
    </button>
  </div>
</template>

<script setup>
import { computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useAppUpdater } from '@/composables/useAppUpdater'

const { state, check } = useAppUpdater()

const busy = computed(() => ['checking', 'downloading', 'verifying', 'installing'].includes(state.value))

const btnText = computed(() => {
  switch (state.value) {
    case 'checking': return '检查中…'
    default: return '检查更新'
  }
})

const btnClass = computed(() => ({
  'upd-btn--primary': state.value === 'update-available' || state.value === 'ready',
}))

const tooltip = computed(() => busy.value ? '更新流程正在进行中' : '检查是否有新版本')

watch(state, (s) => {
  if (s === 'no-update') {
    ElMessage.success('已是最新版本')
  }
})

async function onClick() {
  if (['update-available', 'ready', 'error'].includes(state.value)) {
    window.dispatchEvent(new CustomEvent('lianyu:show-update-dialog'))
    return
  }
  if (state.value === 'idle' || state.value === 'no-update') {
    await check()
  }
}
</script>

<style lang="scss" scoped>
.app-update-button {
  display: inline-flex;
  align-items: center;
  gap: $space-2;
  margin-left: $space-3;
}

.upd-btn {
  display: inline-flex;
  align-items: center;
  gap: $space-1;
  padding: 4px 14px;
  border: 1px solid rgba($color-pink-rgb, 0.25);
  border-radius: $radius-pill;
  background: rgba(var(--ly-bg-surface-rgb), 0.35);
  color: $color-text-secondary;
  font-size: $font-size-xs;
  cursor: pointer;
  transition: all $transition-fast;
  &:hover { color: $color-pink-primary; border-color: rgba($color-pink-rgb, 0.45); }
  &:disabled { cursor: not-allowed; opacity: 0.6; }

  &--primary {
    background: linear-gradient(135deg, $color-pink-primary 0%, $color-pink-dark 100%);
    color: $color-text-inverse;
    border-color: transparent;
    box-shadow: $shadow-glow-pink;
    &:hover { color: $color-text-inverse; }
  }
}

.upd-spin {
  width: 10px; height: 10px;
  border: 2px solid rgba($color-pink-rgb, 0.3);
  border-top-color: $color-pink-primary;
  border-radius: $radius-full;
  animation: upd-spin 0.6s cubic-bezier(0.23, 1, 0.32, 1) infinite;
}
@keyframes upd-spin { to { transform: rotate(360deg); } }
</style>
