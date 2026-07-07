<template>
  <el-dialog
    v-model="visible"
    title="应用更新"
    :width="dialogWidth"
    :close-on-click-modal="!busy"
    :close-on-press-escape="!busy"
    destroy-on-close
  >
    <div class="upd-dialog">
      <div class="upd-dialog__headline">{{ headline }}</div>
      <p class="upd-dialog__hint">{{ hint }}</p>

      <div v-if="state === 'downloading'" class="upd-progress">
        <div class="upd-progress__meta">
          <span>{{ progressPct }}%</span>
          <span>{{ transferredText }} / {{ totalText }}</span>
        </div>
        <div class="upd-progress__track">
          <div class="upd-progress__bar" :style="{ width: progressPct + '%' }" />
        </div>
        <div class="upd-progress__meta upd-progress__meta--muted">
          <span>{{ speedText }}</span>
          <span>{{ etaText }}</span>
        </div>
      </div>

      <div v-if="state === 'installing'" class="upd-installing">
        {{ info.message || '正在后台安装更新，请稍候。' }}
      </div>

      <div v-if="state === 'error'" class="upd-error">
        {{ info.errorMessage || '更新失败，请稍后重试。' }}
      </div>
    </div>

    <template #footer>
      <el-button v-if="canClose" @click="visible = false">下次再说</el-button>
      <el-button v-if="state === 'update-available'" type="primary" class="btn-cta" @click="download">
        立即下载
      </el-button>
      <el-button v-if="state === 'ready'" type="primary" class="btn-cta" @click="install">
        安装并重启
      </el-button>
      <el-button v-if="state === 'error'" type="primary" class="btn-cta" @click="download">
        重试下载
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useAppUpdater } from '@/composables/useAppUpdater'
import { useResponsiveDialogWidth } from '@/composables/useResponsiveDialogWidth'

const { state, info, download, install } = useAppUpdater()
const dialogWidth = useResponsiveDialogWidth(460)
const visible = ref(false)

const busy = computed(() => ['checking', 'downloading', 'installing'].includes(state.value))
const canClose = computed(() => !busy.value && state.value !== 'installing')
const progressPct = computed(() => Math.max(0, Math.min(100, Math.round(info.value.percent || 0))))

const headline = computed(() => {
  switch (state.value) {
    case 'checking': return '正在检查更新'
    case 'update-available': return `发现新版本 v${info.value.version || ''}`
    case 'downloading': return `正在下载 v${info.value.version || ''}`
    case 'ready': return `v${info.value.version || ''} 已下载完成`
    case 'installing': return '正在安装更新'
    case 'error': return '更新失败'
    default: return '应用更新'
  }
})

const hint = computed(() => {
  switch (state.value) {
    case 'update-available': return '建议立即更新以获得最新修复。下载完成后可一键安装并重启。'
    case 'downloading': return '下载期间可以停留在当前页面，请保持网络连接。'
    case 'ready': return '安装会关闭当前应用并在后台完成，期间请不要手动重新打开。'
    case 'installing': return '应用即将关闭并后台安装，完成后会自动重新启动。'
    case 'error': return '请检查网络后重试。如果多次失败，可以稍后再试。'
    default: return '正在与更新源通信。'
  }
})

const transferredText = computed(() => formatBytes(info.value.transferred || 0))
const totalText = computed(() => formatBytes(info.value.total || 0))
const speedText = computed(() => `${formatBytes(info.value.speedBytesPerSec || 0)}/s`)
const etaText = computed(() => {
  const seconds = Number(info.value.etaSeconds || 0)
  if (seconds <= 0) return '即将完成'
  if (seconds < 60) return `约 ${seconds} 秒`
  return `约 ${Math.ceil(seconds / 60)} 分钟`
})

watch(state, (next) => {
  if (['checking', 'update-available', 'downloading', 'ready', 'installing', 'error'].includes(next)) {
    visible.value = true
  }
})

function showDialog() {
  visible.value = true
}

onMounted(() => {
  window.addEventListener('lianyu:show-update-dialog', showDialog)
})

onBeforeUnmount(() => {
  window.removeEventListener('lianyu:show-update-dialog', showDialog)
})

function formatBytes(bytes) {
  const value = Number(bytes || 0)
  if (value <= 0) return '0 B'
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`
  return `${(value / 1024 / 1024).toFixed(1)} MB`
}
</script>

<style lang="scss" scoped>
.upd-dialog {
  display: flex;
  flex-direction: column;
  gap: $space-4;
}

.upd-dialog__headline {
  color: $color-text-primary;
  font-size: $font-size-lg;
  font-weight: $font-weight-semibold;
}

.upd-dialog__hint,
.upd-installing,
.upd-error {
  color: $color-text-secondary;
  font-size: $font-size-sm;
  line-height: $line-height-normal;
}

.upd-error {
  color: $color-error;
}

.upd-progress {
  display: flex;
  flex-direction: column;
  gap: $space-2;
}

.upd-progress__meta {
  display: flex;
  justify-content: space-between;
  gap: $space-3;
  color: $color-text-secondary;
  font-size: $font-size-xs;
}

.upd-progress__meta--muted {
  color: $color-text-muted;
}

.upd-progress__track {
  height: 8px;
  border-radius: $radius-full;
  background: rgba($color-pink-rgb, 0.12);
  overflow: hidden;
}

.upd-progress__bar {
  height: 100%;
  border-radius: $radius-full;
  background: linear-gradient(90deg, $color-pink-primary, $color-pink-light);
  transition: width 0.24s cubic-bezier(0.23, 1, 0.32, 1);
}
</style>
