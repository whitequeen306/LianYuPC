<template>
  <div class="qq-chat-page stagger-container">
    <button class="page-back" type="button" @click="goBack">
      <el-icon><ArrowLeft /></el-icon>
      <span>{{ t('common.back') }}</span>
    </button>
    <header class="page-header">
      <h1 class="page-title">{{ t('routes.qqChat') }}</h1>
      <p class="page-desc">{{ t('qqChat.desc') }}</p>
    </header>

    <!-- ① 状态条：一句话说清「现在能不能用」，不能就告诉用户卡在哪一步 -->
    <div class="glass qq-status" :class="`qq-status--${status.tone}`">
      <div class="qq-status__text">
        <div class="qq-status__title">{{ status.title }}</div>
        <div class="qq-status__hint">{{ status.hint }}</div>
      </div>
      <el-button
        v-if="status.action"
        :type="status.tone === 'warn' ? 'warning' : 'primary'"
        size="small"
        @click="status.action.to()"
      >{{ status.action.label }}</el-button>
    </div>

    <!-- ② 扫码连接：用自己的机器人，扫一下就好 -->
    <section class="section stagger-item">
      <div class="section-header">
        <div>
          <h2 class="section-title">{{ t('qqChat.bindTitle') }}</h2>
          <p class="section-desc">{{ t('qqChat.bindHint') }}</p>
        </div>
      </div>
      <div class="glass qq-card">
        <ol class="qq-steps">
          <li>{{ t('qqChat.step1') }}</li>
          <li>{{ t('qqChat.step2') }}</li>
          <li>{{ t('qqChat.step3') }}</li>
        </ol>

        <div class="qq-row">
          <div class="qq-row__label">
            <div class="qq-row__title">{{ t('qqChat.character') }}</div>
            <div class="qq-row__hint">{{ t('qqChat.characterHint') }}</div>
          </div>
          <el-select
            v-model="characterId"
            :placeholder="t('qqChat.characterPlaceholder')"
            :loading="characterLoading"
            filterable
            class="qq-select"
          >
            <el-option
              v-for="opt in characterOptions"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </div>

        <div class="qq-row">
          <div class="qq-row__label">
            <div class="qq-row__title">{{ t('qqChat.qrTitle') }}</div>
            <div class="qq-row__hint">{{ t('qqChat.qrHint') }}</div>
          </div>
          <el-button
            type="primary"
            :loading="starting"
            :disabled="!characterId"
            @click="onStartQr"
          >{{ qrImage ? t('qqChat.qrRegenerate') : t('qqChat.qrGenerate') }}</el-button>
        </div>

        <div v-if="qrImage" class="qq-qr">
          <img class="qq-qr__image" :src="qrImage" :alt="t('qqChat.qrTitle')" />
          <div class="qq-qr__meta">
            <span class="qq-qr__state" :class="`qq-qr__state--${qrState}`">
              {{ qrStateText }}
            </span>
            <span>{{ t('qqChat.expiresIn', { time: countdownText }) }}</span>
          </div>
        </div>
      </div>
    </section>

    <!-- ③ 已连接的机器人：显示是谁在回话，表情包频率就地可调 -->
    <section class="section stagger-item">
      <div class="section-header">
        <div>
          <h2 class="section-title">{{ t('qqChat.accountsTitle') }}</h2>
          <p class="section-desc">{{ t('qqChat.accountsHint') }}</p>
        </div>
        <el-button text :icon="RefreshRight" :loading="loadingList" @click="loadAccounts">
          {{ t('qqChat.refresh') }}
        </el-button>
      </div>
      <div class="glass qq-card">
        <p v-if="!accounts.length" class="qq-empty">{{ t('qqChat.accountsEmpty') }}</p>
        <div v-for="item in accounts" :key="item.appId" class="qq-binding">
          <div class="qq-binding__head">
            <div>
              <div class="qq-row__title">{{ item.botName || item.appId }}</div>
              <div class="qq-row__hint">
                <template v-if="item.characterName">{{ t('qqChat.roleLabel', { name: item.characterName }) }}</template>
                <template v-else>{{ t('qqChat.accountNoCharacter') }}</template>
              </div>
            </div>
            <div class="qq-binding__actions">
              <el-select
                :model-value="item.characterId"
                size="small"
                :placeholder="t('qqChat.accountBindCharacter')"
                class="qq-select--sm"
                @update:model-value="value => onBindCharacter(item, value)"
              >
                <el-option
                  v-for="opt in characterOptions"
                  :key="opt.value"
                  :label="opt.label"
                  :value="opt.value"
                />
              </el-select>
              <el-button
                type="danger"
                plain
                size="small"
                :loading="removingAppId === item.appId"
                @click="onDisconnect(item)"
              >{{ t('qqChat.disconnect') }}</el-button>
            </div>
          </div>
          <div v-if="item.characterId" class="qq-binding__freq">
            <div class="qq-binding__freq-label">
              <span>{{ t('qqChat.stickerFreq') }}</span>
              <span class="qq-row__hint">{{ t('qqChat.stickerFreqHint') }}</span>
            </div>
            <el-radio-group
              :model-value="frequencyOf(item.characterId)"
              size="small"
              :disabled="savingFrequencyId === item.characterId"
              @update:model-value="value => onChangeFrequency(item, value)"
            >
              <el-radio-button value="never">{{ t('characterSettings.stickerNever') }}</el-radio-button>
              <el-radio-button value="normal">{{ t('characterSettings.stickerNormal') }}</el-radio-button>
              <el-radio-button value="frequent">{{ t('characterSettings.stickerFrequent') }}</el-radio-button>
            </el-radio-group>
          </div>
        </div>
      </div>
    </section>

    <!-- ④ 常见问题：不回话、能不能主动找、群聊、审核，都是官方口径 -->
    <section class="section stagger-item">
      <div class="section-header">
        <div>
          <h2 class="section-title">{{ t('qqChat.faqTitle') }}</h2>
        </div>
      </div>
      <el-collapse class="glass qq-faq">
        <el-collapse-item :title="t('qqChat.faqNoReplyQ')" name="1">
          <p>{{ t('qqChat.faqNoReplyA') }}</p>
        </el-collapse-item>
        <el-collapse-item :title="t('qqChat.faqProactiveQ')" name="2">
          <p>{{ t('qqChat.faqProactiveA') }}</p>
        </el-collapse-item>
        <el-collapse-item :title="t('qqChat.faqGroupQ')" name="3">
          <p>{{ t('qqChat.faqGroupA') }}</p>
        </el-collapse-item>
        <el-collapse-item :title="t('qqChat.faqAuditQ')" name="4">
          <p>{{ t('qqChat.faqAuditA') }}</p>
        </el-collapse-item>
      </el-collapse>
    </section>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, RefreshRight } from '@element-plus/icons-vue'
import QRCode from 'qrcode'
import { listCharacters, updateCharacter } from '@/api/character'
import { listVaults } from '@/api/ai'
import {
  bindQqBotCharacter,
  listQqBotAccounts,
  pollQqBotTask,
  removeQqBotAccount,
  startQqBotTask,
} from '@/api/qq'

const router = useRouter()
const { t } = useI18n()

const characters = ref([])
const characterOptions = ref([])
const characterLoading = ref(false)
const characterId = ref('')
const starting = ref(false)
const accounts = ref([])
const loadingList = ref(false)
const removingAppId = ref(null)
const savingFrequencyId = ref(null)
const hasModel = ref(true)
const now = ref(Date.now())

// 扫码状态：idle → waiting → done / expired
const qrImage = ref('')
const qrState = ref('idle')
const qrTask = ref(null)
let tickTimer = null
let pollTimer = null

const POLL_MS = 2000

const countdownText = computed(() => {
  if (!qrTask.value?.ttlSeconds) return '--:--'
  const expiredAt = (qrTask.value.createdAt || 0) + qrTask.value.ttlSeconds * 1000
  const left = Math.max(0, expiredAt - now.value)
  const mm = String(Math.floor(left / 60000)).padStart(2, '0')
  const ss = String(Math.floor((left % 60000) / 1000)).padStart(2, '0')
  return `${mm}:${ss}`
})

const qrStateText = computed(() => {
  if (qrState.value === 'done') return t('qqChat.qrDone')
  if (qrState.value === 'expired') return t('qqChat.qrExpired')
  return t('qqChat.qrWaiting')
})

// 状态条是三段式：没模型 → 没连机器人 → 已连上。先说卡点，再说下一步该点哪。
const status = computed(() => {
  if (!hasModel.value) {
    return {
      tone: 'warn',
      title: t('qqChat.statusNoModel'),
      hint: t('qqChat.statusNoModelHint'),
      action: { label: t('qqChat.statusNoModelAction'), to: () => router.push('/app/settings') },
    }
  }
  const bound = accounts.value.filter((item) => item.characterId)
  if (!bound.length) {
    return {
      tone: 'idle',
      title: t('qqChat.statusIdle'),
      hint: t('qqChat.statusIdleHint'),
      action: { label: t('qqChat.statusIdleAction'), to: () => onStartQr() },
    }
  }
  const names = bound.map((item) => item.characterName).filter(Boolean).join('、')
  return {
    tone: 'ok',
    title: t('qqChat.statusOk', { names }),
    hint: t('qqChat.statusOkHint'),
    action: null,
  }
})

function goBack() {
  router.back()
}

function formatTime(value) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  const pad = (n) => String(n).padStart(2, '0')
  return `${date.getMonth() + 1}/${date.getDate()} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

function frequencyOf(id) {
  const character = characters.value.find((item) => item.id === id)
  const value = character?.settings?.stickerFrequency
  return ['never', 'frequent'].includes(value) ? value : 'normal'
}

async function loadCharacters() {
  characterLoading.value = true
  try {
    const list = await listCharacters({ silent: true })
    characters.value = Array.isArray(list) ? list : []
    characterOptions.value = characters.value.map((c) => ({ value: c.id, label: c.name }))
    if (!characterId.value && characterOptions.value.length) {
      characterId.value = characterOptions.value[0].value
    }
  } catch {
    characters.value = []
    characterOptions.value = []
  } finally {
    characterLoading.value = false
  }
}

async function loadModelState() {
  try {
    const vaults = await listVaults()
    hasModel.value = Array.isArray(vaults) ? vaults.length > 0 : true
  } catch {
    // 查不到就别吓用户：状态条退回「没连接」这一档
    hasModel.value = true
  }
}

async function loadAccounts() {
  loadingList.value = true
  try {
    const list = await listQqBotAccounts({ silent: true })
    accounts.value = Array.isArray(list) ? list : []
  } catch {
    accounts.value = []
  } finally {
    loadingList.value = false
  }
}

function stopPolling() {
  if (pollTimer) clearTimeout(pollTimer)
  pollTimer = null
}

async function pollOnce(taskId) {
  let result = null
  try {
    result = await pollQqBotTask(taskId)
  } catch {
    // 网络抖一下就再试，不打断用户
    pollTimer = setTimeout(() => pollOnce(taskId), POLL_MS)
    return
  }
  if (!result) return
  if (result.status === 'pending') {
    pollTimer = setTimeout(() => pollOnce(taskId), POLL_MS)
    return
  }
  if (result.status === 'expired' || result.status === 'unknown') {
    // 腾讯那边 task_id 大约两分钟就换一次，用户拿着旧码扫只会看到「无效」。
    // 只要面板还开着、还在等扫码，就自动换一张新的，而不是让用户对着死码发呆。
    if (qrState.value === 'waiting') {
      await renewQr()
    } else {
      qrState.value = 'expired'
    }
    return
  }
  if (result.status === 'completed') {
    qrState.value = 'done'
    // 扫码时已经选了角色：直接绑上，用户不用再点一次。
    if (result.appId && characterId.value) {
      try {
        await bindQqBotCharacter(result.appId, characterId.value)
      } catch (err) {
        ElMessage.error(err?.message || t('qqChat.errorGeneric'))
      }
    }
    await loadAccounts()
    ElMessage.success(t('qqChat.qrDoneName', { name: result.botName || result.appId }))
  }
}

/**
 * 换一张新码（腾讯 ~2 分钟轮换 task_id，过期后旧码扫不出来）。
 * 失败时退回「已过期」状态，让用户看到明确的结论而不是空等。
 */
async function renewQr() {
  stopPolling()
  try {
    const task = await startQqBotTask('yuni-pc')
    if (!task?.qrUrl) throw new Error('no_qr')
    qrTask.value = { ...task, createdAt: Date.now() }
    qrImage.value = await QRCode.toDataURL(task.qrUrl, { width: 280, margin: 1 })
    qrState.value = 'waiting'
    pollOnce(task.taskId)
  } catch {
    qrState.value = 'expired'
  }
}

async function onStartQr() {
  if (!characterId.value) {
    ElMessage.warning(t('qqChat.characterPlaceholder'))
    return
  }
  starting.value = true
  stopPolling()
  try {
    const task = await startQqBotTask('yuni-pc')
    if (!task?.qrUrl) throw new Error('no_qr')
    qrTask.value = { ...task, createdAt: Date.now() }
    qrState.value = 'waiting'
    qrImage.value = await QRCode.toDataURL(task.qrUrl, { width: 280, margin: 1 })
    pollOnce(task.taskId)
  } catch (err) {
    ElMessage.error(err?.message || t('qqChat.errorGeneric'))
  } finally {
    starting.value = false
  }
}

async function onBindCharacter(item, value) {
  if (!item?.appId || !value) return
  try {
    await bindQqBotCharacter(item.appId, value)
    await loadAccounts()
    ElMessage.success(t('qqChat.bindCharacterDone'))
  } catch (err) {
    ElMessage.error(err?.message || t('qqChat.errorGeneric'))
  }
}

// 后端 update 会 mergeSettings，所以只送这一个键，不会冲掉背景/免打扰那些设置。
async function onChangeFrequency(item, value) {
  if (!item?.characterId || frequencyOf(item.characterId) === value) return
  savingFrequencyId.value = item.characterId
  try {
    const updated = await updateCharacter(item.characterId, { settings: { stickerFrequency: value } })
    const index = characters.value.findIndex((c) => c.id === item.characterId)
    if (index >= 0 && updated?.settings) {
      characters.value[index] = { ...characters.value[index], settings: updated.settings }
    }
    ElMessage.success(t('qqChat.stickerFreqSaved'))
  } catch (err) {
    ElMessage.error(err?.message || t('qqChat.errorGeneric'))
  } finally {
    savingFrequencyId.value = null
  }
}

async function onDisconnect(item) {
  try {
    await ElMessageBox.confirm(
      t('qqChat.disconnectConfirm', { name: item.botName || item.appId }),
      t('qqChat.disconnect'),
      { type: 'warning' },
    )
  } catch {
    return
  }
  removingAppId.value = item.appId
  try {
    await removeQqBotAccount(item.appId)
    ElMessage.success(t('qqChat.disconnectDone'))
    await loadAccounts()
  } catch (err) {
    ElMessage.error(err?.message || t('qqChat.errorGeneric'))
  } finally {
    removingAppId.value = null
  }
}

onMounted(() => {
  loadCharacters()
  loadModelState()
  loadAccounts()
  tickTimer = setInterval(() => {
    now.value = Date.now()
  }, 1000)
})

onBeforeUnmount(() => {
  if (tickTimer) clearInterval(tickTimer)
  tickTimer = null
  stopPolling()
})
</script>

<style scoped>
.qq-chat-page {
  padding-bottom: 48px;
}

.qq-status {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 18px;
  margin-bottom: 20px;
  border-left: 3px solid transparent;
}

.qq-status--warn {
  border-left-color: #e6a23c;
}

.qq-status--idle {
  border-left-color: rgba(var(--accent-rgb, 120, 120, 255), 0.7);
}

.qq-status--ok {
  border-left-color: #67c23a;
}

.qq-status__title {
  font-size: 14px;
  font-weight: 600;
}

.qq-status__hint {
  font-size: 12.5px;
  opacity: 0.66;
  margin-top: 4px;
}

.qq-card {
  padding: 18px 20px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.qq-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.qq-row__title {
  font-size: 14px;
  font-weight: 600;
}

.qq-row__hint {
  font-size: 12px;
  opacity: 0.62;
  margin-top: 2px;
}

.qq-select {
  width: 220px;
}

.qq-select--sm {
  width: 130px;
}

.qq-qr {
  border: 1px dashed rgba(var(--accent-rgb, 120, 120, 255), 0.35);
  border-radius: 12px;
  padding: 16px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
}

/* 二维码用浅底：深色主题下直接放透明 PNG 会扫不出来 */
.qq-qr__image {
  width: 280px;
  height: 280px;
  border-radius: 10px;
  background: #fff;
  padding: 8px;
}

.qq-qr__meta {
  display: flex;
  align-items: center;
  gap: 16px;
  font-size: 12px;
  opacity: 0.72;
}

.qq-qr__state--waiting {
  color: #e6a23c;
}

.qq-qr__state--done {
  color: #67c23a;
}

.qq-qr__state--expired {
  color: #f56c6c;
}

.qq-steps {
  counter-reset: qq-step;
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 12.5px;
  line-height: 1.7;
}

.qq-steps li {
  counter-increment: qq-step;
  display: flex;
  gap: 8px;
  align-items: flex-start;
  opacity: 0.82;
}

/* 序号自己画：全局 reset 把 ol 的 marker 关了 */
.qq-steps li::before {
  content: counter(qq-step);
  flex: 0 0 auto;
  width: 16px;
  height: 16px;
  margin-top: 2px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.1);
  font-size: 10px;
  line-height: 16px;
  text-align: center;
  opacity: 0.9;
}

.qq-empty {
  font-size: 13px;
  opacity: 0.6;
  margin: 0;
}

.qq-binding {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 14px 0;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
}

.qq-binding:first-of-type {
  border-top: none;
}

.qq-binding__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.qq-binding__actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.qq-binding__freq {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

/* 不让 radio-group 被挤压：挤压时最后一个按钮的文字会溢出色块 */
.qq-binding__freq :deep(.el-radio-group) {
  flex: 0 0 auto;
}

.qq-binding__freq :deep(.el-radio-button__inner) {
  padding: 7px 13px;
  white-space: nowrap;
}

.qq-binding__freq-label {
  display: flex;
  flex-direction: column;
  font-size: 13px;
}

.qq-faq {
  padding: 4px 18px;
}

.qq-faq :deep(.el-collapse-item__header) {
  font-size: 13.5px;
}

.qq-faq :deep(.el-collapse-item__content) {
  font-size: 12.5px;
  line-height: 1.8;
  opacity: 0.8;
}
</style>
