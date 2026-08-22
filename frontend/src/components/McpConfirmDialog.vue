<template>
  <el-dialog
    v-model="visible"
    :title="dialogTitle"
    width="420px"
    :close-on-click-modal="false"
    :close-on-press-escape="true"
    :show-close="false"
    align-center
    class="mcp-confirm-dialog"
    @close="onDialogClose"
  >
    <div class="mcp-confirm">
      <p class="mcp-confirm__message">{{ current?.message || '本地服务请求执行操作' }}</p>
      <div v-if="current?.toolName" class="mcp-confirm__row">
        <span class="mcp-confirm__label">工具</span>
        <code class="mcp-confirm__code">{{ current.toolName }}</code>
      </div>
      <div v-if="current?.args" class="mcp-confirm__row">
        <span class="mcp-confirm__label">参数</span>
        <code class="mcp-confirm__code">{{ current.args }}</code>
      </div>
      <p class="mcp-confirm__hint">{{ countdown }}s 内未确认将自动拒绝</p>
    </div>
    <template #footer>
      <el-button type="default" @click="respond(false)">拒绝</el-button>
      <el-button type="primary" class="btn-cta" @click="respond(true)">允许执行</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { getElectronAPI } from '@/utils/electron'

const visible = ref(false)
const current = ref(null)
const countdown = ref(0)
const queue = []
let unsub = null
let countdownTimer = null

const dialogTitle = computed(() =>
  current.value?.kind === 'elicit' ? '本地服务请求确认' : '危险操作确认'
)

function stopCountdown() {
  if (countdownTimer) {
    clearInterval(countdownTimer)
    countdownTimer = null
  }
}

function showNext() {
  stopCountdown()
  const next = queue.shift()
  if (!next) {
    current.value = null
    visible.value = false
    return
  }
  current.value = next
  visible.value = true
  // 比主进程超时（45s）提前 5s 收尾，避免用户点击时主进程已默认拒绝
  countdown.value = Math.max(5, Math.floor((next.timeoutMs || 45000) / 1000) - 5)
  countdownTimer = setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0) {
      respond(false)
    }
  }, 1000)
}

function respond(approved) {
  const item = current.value
  stopCountdown()
  current.value = null
  if (item) {
    getElectronAPI()?.respondMcpConfirm?.(item.id, approved)
  }
  showNext()
}

function onDialogClose() {
  if (current.value) respond(false)
}

onMounted(() => {
  const api = getElectronAPI()
  if (!api?.onMcpConfirmRequest) return
  unsub = api.onMcpConfirmRequest((payload) => {
    if (!payload?.id) return
    queue.push(payload)
    if (!visible.value) showNext()
  })
})

onBeforeUnmount(() => {
  stopCountdown()
  if (unsub) unsub()
})
</script>

<style lang="scss" scoped>
.mcp-confirm {
  display: flex;
  flex-direction: column;
  gap: $space-3;
}

.mcp-confirm__message {
  margin: 0;
  color: $color-text-primary;
  font-size: $font-size-base;
  line-height: 1.6;
}

.mcp-confirm__row {
  display: flex;
  align-items: baseline;
  gap: $space-2;
}

.mcp-confirm__label {
  flex-shrink: 0;
  color: $color-text-secondary;
  font-size: $font-size-sm;
}

.mcp-confirm__code {
  padding: 2px $space-2;
  border-radius: $radius-sm;
  background: rgba(var(--ly-bg-surface-rgb), 0.6);
  color: $color-pink-primary;
  font-size: $font-size-sm;
  word-break: break-all;
}

.mcp-confirm__hint {
  margin: 0;
  color: $color-text-secondary;
  font-size: $font-size-xs;
}
</style>
