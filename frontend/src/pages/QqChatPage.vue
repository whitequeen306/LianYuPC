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

    <!-- ② 连接：找到机器人 → 发码 → 完成 -->
    <section class="section stagger-item">
      <div class="section-header">
        <div>
          <h2 class="section-title">{{ t('qqChat.bindTitle') }}</h2>
          <p class="section-desc">{{ t('qqChat.bindHint') }}</p>
        </div>
      </div>
      <div class="glass qq-card">
        <div class="qq-bot">
          <div class="qq-bot__avatar">予</div>
          <div class="qq-bot__text">
            <div class="qq-row__title">{{ t('qqChat.botName') }}</div>
            <div class="qq-row__hint">{{ t('qqChat.botHint') }}</div>
          </div>
        </div>

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
            <div class="qq-row__title">{{ t('qqChat.codeTitle') }}</div>
            <div class="qq-row__hint">{{ t('qqChat.codeHint') }}</div>
          </div>
          <el-button
            type="primary"
            :loading="issuing"
            :disabled="!characterId"
            @click="onIssue"
          >{{ bindCode ? t('qqChat.regenerate') : t('qqChat.generate') }}</el-button>
        </div>

        <div v-if="bindCode" class="qq-code">
          <div class="qq-code__value">{{ bindCode.code }}</div>
          <div class="qq-code__meta">
            <span>{{ t('qqChat.character') }}：{{ bindCode.characterName }}</span>
            <span>{{ t('qqChat.expiresIn', { time: countdownText }) }}</span>
            <el-button text type="primary" @click="copyCode">{{ copied ? t('qqChat.copied') : t('qqChat.copy') }}</el-button>
          </div>
        </div>
      </div>
    </section>

    <!-- ③ 已连接的角色：显示是谁在回话，并把表情包频率就地可调 -->
    <section class="section stagger-item">
      <div class="section-header">
        <div>
          <h2 class="section-title">{{ t('qqChat.listTitle') }}</h2>
          <p class="section-desc">{{ t('qqChat.listHint') }}</p>
        </div>
        <el-button text :icon="RefreshRight" :loading="loadingList" @click="loadBindings">
          {{ t('qqChat.refresh') }}
        </el-button>
      </div>
      <div class="glass qq-card">
        <p v-if="!bindings.length" class="qq-empty">{{ t('qqChat.listEmpty') }}</p>
        <div v-for="item in bindings" :key="item.id" class="qq-binding">
          <div class="qq-binding__head">
            <div>
              <div class="qq-row__title">{{ item.characterName }}</div>
              <div class="qq-row__hint">
                {{ channelLabel(item.channel) }} · {{ maskExternalId(item.externalId) }}
                <template v-if="item.updatedAt"> · {{ t('qqChat.boundAt', { time: formatTime(item.updatedAt) }) }}</template>
              </div>
            </div>
            <el-button type="danger" plain size="small" :loading="removingId === item.id" @click="onUnbind(item)">
              {{ t('qqChat.unbind') }}
            </el-button>
          </div>
          <div class="qq-binding__freq">
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
import { listCharacters, updateCharacter } from '@/api/character'
import { listVaults } from '@/api/ai'
import { issueQqBindCode, listQqBindings, unbindQq } from '@/api/qq'

const router = useRouter()
const { t } = useI18n()

const characters = ref([])
const characterOptions = ref([])
const characterLoading = ref(false)
const characterId = ref('')
const issuing = ref(false)
const bindCode = ref(null)
const copied = ref(false)
const bindings = ref([])
const loadingList = ref(false)
const removingId = ref(null)
const savingFrequencyId = ref(null)
const hasModel = ref(true)
const now = ref(Date.now())
let tickTimer = null

// 绑定码只有 10 分钟，倒计时让用户知道「现在就得发」。
const countdownText = computed(() => {
  if (!bindCode.value?.expiresAt) return '--:--'
  const left = Math.max(0, new Date(bindCode.value.expiresAt).getTime() - now.value)
  const mm = String(Math.floor(left / 60000)).padStart(2, '0')
  const ss = String(Math.floor((left % 60000) / 1000)).padStart(2, '0')
  return `${mm}:${ss}`
})

// 状态条是三段式：没模型 → 没绑定 → 已连上。先说卡点，再说下一步该点哪。
const status = computed(() => {
  if (!hasModel.value) {
    return {
      tone: 'warn',
      title: t('qqChat.statusNoModel'),
      hint: t('qqChat.statusNoModelHint'),
      action: { label: t('qqChat.statusNoModelAction'), to: () => router.push('/app/settings') },
    }
  }
  if (!bindings.value.length) {
    return {
      tone: 'idle',
      title: t('qqChat.statusIdle'),
      hint: t('qqChat.statusIdleHint'),
      action: { label: t('qqChat.statusIdleAction'), to: () => onIssue() },
    }
  }
  const names = bindings.value.map((item) => item.characterName).filter(Boolean).join('、')
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

function channelLabel(channel) {
  if (channel === 'qqbot-group') return t('qqChat.channelGroup')
  return t('qqChat.channelC2c')
}

// openid 是腾讯给的标识，不是 QQ 号；只露头尾够用户认出是哪一个。
function maskExternalId(externalId = '') {
  if (externalId.length <= 8) return externalId
  return `${externalId.slice(0, 4)}***${externalId.slice(-4)}`
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
    // 查不到就别吓用户：状态条退回「没绑定」这一档
    hasModel.value = true
  }
}

async function loadBindings() {
  loadingList.value = true
  try {
    const list = await listQqBindings({ silent: true })
    bindings.value = Array.isArray(list) ? list : []
  } catch {
    bindings.value = []
  } finally {
    loadingList.value = false
  }
}

async function onIssue() {
  if (!characterId.value) {
    ElMessage.warning(t('qqChat.characterPlaceholder'))
    return
  }
  issuing.value = true
  try {
    bindCode.value = await issueQqBindCode(characterId.value)
    copied.value = false
    now.value = Date.now()
  } catch (err) {
    ElMessage.error(err?.message || t('qqChat.errorGeneric'))
  } finally {
    issuing.value = false
  }
}

async function copyCode() {
  if (!bindCode.value?.code) return
  try {
    await navigator.clipboard.writeText(bindCode.value.code)
    copied.value = true
  } catch {
    copied.value = false
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

async function onUnbind(item) {
  try {
    await ElMessageBox.confirm(
      t('qqChat.unbindConfirm', { name: item.characterName }),
      t('qqChat.unbind'),
      { type: 'warning' },
    )
  } catch {
    return
  }
  removingId.value = item.id
  try {
    await unbindQq(item.id)
    ElMessage.success(t('qqChat.unbindDone'))
    await loadBindings()
  } catch (err) {
    ElMessage.error(err?.message || t('qqChat.errorGeneric'))
  } finally {
    removingId.value = null
  }
}

onMounted(() => {
  loadCharacters()
  loadModelState()
  loadBindings()
  tickTimer = setInterval(() => {
    now.value = Date.now()
  }, 1000)
})

onBeforeUnmount(() => {
  if (tickTimer) clearInterval(tickTimer)
  tickTimer = null
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

.qq-bot {
  display: flex;
  align-items: center;
  gap: 14px;
}

.qq-bot__avatar {
  width: 44px;
  height: 44px;
  border-radius: 14px;
  display: grid;
  place-items: center;
  font-size: 18px;
  font-weight: 700;
  background: rgba(var(--accent-rgb, 120, 120, 255), 0.18);
  color: rgb(var(--accent-rgb, 120, 120, 255));
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

.qq-code {
  border: 1px dashed rgba(var(--accent-rgb, 120, 120, 255), 0.35);
  border-radius: 12px;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.qq-code__value {
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 34px;
  letter-spacing: 8px;
  font-weight: 700;
}

.qq-code__meta {
  display: flex;
  align-items: center;
  gap: 16px;
  font-size: 12px;
  opacity: 0.72;
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
