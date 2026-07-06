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

    <div v-if="state === 'downloading'" class="upd-progress">
      <div class="upd-progress__bar" :style="{ width: progressPct + '%' }" />
    </div>

    <a
      v-if="state === 'error'"
      class="upd-fallback"
      href="#"
      @click.prevent="openReleases"
    >前往 GitHub 手动下载</a>

    <el-dialog
      v-model="dialogVisible"
      title="发现新版本"
      :width="dialogWidth"
      destroy-on-close
    >
      <p class="upd-dialog-line">发现新版本 <span class="upd-version">v{{ info.version }}</span></p>
      <p class="upd-dialog-hint">建议立即更新以获得最新修复。更新将下载安装包并自动重启应用。</p>
      <template #footer>
        <el-button @click="dialogVisible = false">下次再说</el-button>
        <el-button type="primary" class="btn-cta" :loading="state === 'downloading'" @click="startDownload">
          立即更新
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useAppUpdater } from '@/composables/useAppUpdater'
import { useResponsiveDialogWidth } from '@/composables/useResponsiveDialogWidth'

const { state, info, check, download, install } = useAppUpdater()
const dialogWidth = useResponsiveDialogWidth(420)
const dialogVisible = ref(false)

const RELEASES_URL = 'https://github.com/whitequeen306/LianYuPC/releases/latest'

const busy = computed(() => ['checking', 'downloading', 'verifying', 'installing'].includes(state.value))

const btnText = computed(() => {
  switch (state.value) {
    case 'checking': return '检查中…'
    case 'no-update': return '已是最新'
    case 'downloading': return `下载中 ${Math.round(info.value.percent || 0)}%`
    case 'verifying': return '校验中…'
    case 'ready': return '安装并重启'
    case 'error': return '更新失败·重试'
    default: return '检查更新'
  }
})

const btnClass = computed(() => ({
  'upd-btn--primary': state.value === 'ready',
  'upd-btn--error': state.value === 'error',
  'upd-btn--success': state.value === 'no-update',
}))

const tooltip = computed(() => state.value === 'error' ? (info.value.errorMessage || '更新失败') : '')

const progressPct = computed(() => Math.max(0, Math.min(100, info.value.percent || 0)))

watch(state, (s) => {
  if (s === 'update-available') {
    dialogVisible.value = true
  }
  if (s === 'ready') {
    ElMessage.success('更新已下载完成，点击「安装并重启」生效')
  }
})

async function onClick() {
  if (state.value === 'ready') {
    await install()
    return
  }
  if (state.value === 'update-available') {
    dialogVisible.value = true
    return
  }
  if (state.value === 'error' || state.value === 'idle' || state.value === 'no-update') {
    await check()
  }
}

async function startDownload() {
  dialogVisible.value = false
  await download()
}

function openReleases() {
  window.open(RELEASES_URL, '_blank', 'noopener,noreferrer')
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
  &--error { color: $color-error; border-color: rgba($color-error, 0.4); }
  &--success { color: $color-success; border-color: rgba($color-success, 0.3); }
}

.upd-spin {
  width: 10px; height: 10px;
  border: 2px solid rgba($color-pink-rgb, 0.3);
  border-top-color: $color-pink-primary;
  border-radius: $radius-full;
  animation: upd-spin 0.6s linear infinite;
}
@keyframes upd-spin { to { transform: rotate(360deg); } }

.upd-progress {
  width: 120px;
  height: 4px;
  border-radius: $radius-full;
  background: rgba($color-pink-rgb, 0.12);
  overflow: hidden;
  &__bar {
    height: 100%;
    background: linear-gradient(90deg, $color-pink-primary, $color-pink-light);
    transition: width 0.2s cubic-bezier(0.23, 1, 0.32, 1);
  }
}

.upd-fallback {
  font-size: $font-size-xs;
  color: $color-text-muted;
  text-decoration: underline;
  &:hover { color: $color-pink-primary; }
}

.upd-dialog-line {
  font-size: $font-size-base;
  color: $color-text-primary;
  margin-bottom: $space-2;
}
.upd-version {
  font-family: $font-mono;
  color: $color-pink-primary;
}
.upd-dialog-hint {
  font-size: $font-size-sm;
  color: $color-text-muted;
  line-height: $line-height-normal;
}
</style>
