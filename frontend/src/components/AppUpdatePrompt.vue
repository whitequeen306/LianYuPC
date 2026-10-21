<template>
  <el-dialog
    v-model="visible"
    :title="title"
    :width="dialogWidth"
    :close-on-click-modal="false"
    :close-on-press-escape="!submitting"
    append-to-body
    destroy-on-close
    @close="onDismissed"
  >
    <div class="upd-prompt">
      <p class="upd-prompt__text">{{ message }}</p>
      <p class="upd-prompt__note">
        选择「前去更新」会跳到「关于」页并开始下载安装包；安装包下载完成后双击即可完成更新。
      </p>
    </div>

    <template #footer>
      <el-button :disabled="submitting" @click="later">稍后再装</el-button>
      <el-button type="primary" :loading="submitting" class="btn-cta" @click="goUpdate">
        前去更新
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
/**
 * 启动时的「请更新至最新版本」提示。
 *
 * 与 `AppUpdateDialog` 的分工：
 * - 本组件只负责**问一句**（稍后再装 / 前去更新），不推进更新状态机；
 * - 用户点「前去更新」后才调用 download()，由 `AppUpdateDialog` 接管进度与结果展示。
 *
 * 判定规则都在 `useUpdatePrompt.js` 里（纯函数 + 进程内状态），这里只做交互。
 */
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { useAppUpdater } from '@/composables/useAppUpdater'
import { useResponsiveDialogWidth } from '@/composables/useResponsiveDialogWidth'
import {
  UPDATE_PAGE_PATH,
  buildPromptMessage,
  buildPromptTitle,
  isProbed,
  markProbed,
  shouldPromptForUpdate,
  snoozeUpdatePrompt,
} from '@/composables/useUpdatePrompt'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const { state, isElectron, probe, download } = useAppUpdater()
const dialogWidth = useResponsiveDialogWidth(460)

const visible = ref(false)
const submitting = ref(false)
/** 已经发起过下载：不再重复触发，也不让关闭动作被误判成「稍后再装」 */
const started = ref(false)
const latestVersion = ref('')
const currentVersion = ref('')

const title = computed(() => buildPromptTitle(latestVersion.value))
const message = computed(() => buildPromptMessage(latestVersion.value, currentVersion.value))

/** 更新流程已经在跑（下载中/已就绪）就别再问一遍 */
function isUpdateFlowActive() {
  return ['downloading', 'ready', 'installing'].includes(state.value)
}

async function runProbe() {
  if (!isElectron.value) return
  if (isProbed()) return
  if (!userStore.isLoggedIn) return
  markProbed()
  if (isUpdateFlowActive()) return
  const result = await probe()
  if (!shouldPromptForUpdate(result)) return
  latestVersion.value = result.version || ''
  currentVersion.value = result.currentVersion || ''
  visible.value = true
}

function later() {
  submitting.value = false
  visible.value = false
}

function onDismissed() {
  // 点 X / 点遮罩关闭等同「稍后再装」：这一轮不再弹，下次启动再说。
  // 已经点了「前去更新」就不能算 snooze —— 否则会把正在进行的下载从提示层面关掉。
  if (!submitting.value && !started.value) snoozeUpdatePrompt()
}

async function goUpdate() {
  if (submitting.value || started.value) return
  submitting.value = true
  started.value = true
  try {
    // 更新页在 /app 下：只有已经进主界面时才跳，避免把登录页用户推到登录墙
    if (route.path.startsWith('/app')) {
      await router.push(UPDATE_PAGE_PATH).catch(() => {})
    }
  } finally {
    submitting.value = false
    // 立刻关掉提示框。
    //
    // 以前这里是 `await download()` 之后才关 —— 而 `updater:download` 是
    // **等整个安装包下完才 resolve** 的，于是用户要对着一个转圈的按钮等上几分钟
    // （2026-10-21 实测 166MB 约 6 分钟），期间进度弹窗被它盖在下面，看着像卡死。
    // 现在进度完全交给 `AppUpdateDialog`（它监听主进程的 updater:state），
    // 这里只负责发起，不等结果。
    visible.value = false
  }
  void download()
}

// 启动时若还没登录，等登录后再探测；已登录则立即探测
watch(
  () => userStore.isLoggedIn,
  (loggedIn) => { if (loggedIn) runProbe() },
  { immediate: true },
)
</script>

<style lang="scss" scoped>
.upd-prompt {
  display: flex;
  flex-direction: column;
  gap: $space-3;
}

.upd-prompt__text {
  margin: 0;
  color: $color-text-primary;
  font-size: $font-size-base;
  line-height: $line-height-normal;
}

.upd-prompt__note {
  margin: 0;
  color: $color-text-muted;
  font-size: $font-size-xs;
  line-height: $line-height-normal;
}
</style>
