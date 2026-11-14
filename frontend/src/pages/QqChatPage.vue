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

    <section class="section stagger-item">
      <div class="section-header">
        <div>
          <h2 class="section-title">{{ t('qqChat.bindTitle') }}</h2>
          <p class="section-desc">{{ t('qqChat.bindHint') }}</p>
        </div>
      </div>
      <div class="glass qq-card">
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
          <ol class="qq-steps">
            <li>{{ t('qqChat.step1') }}</li>
            <li>{{ t('qqChat.step2') }}</li>
            <li>{{ t('qqChat.step3') }}</li>
          </ol>
        </div>
      </div>
    </section>

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
          <div>
            <div class="qq-row__title">{{ item.characterName }}</div>
            <div class="qq-row__hint">
              {{ channelLabel(item.channel) }} · {{ maskExternalId(item.externalId) }}
            </div>
          </div>
          <el-button type="danger" plain size="small" :loading="removingId === item.id" @click="onUnbind(item)">
            {{ t('qqChat.unbind') }}
          </el-button>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, RefreshRight } from '@element-plus/icons-vue'
import { listCharacters } from '@/api/character'
import { issueQqBindCode, listQqBindings, unbindQq } from '@/api/qq'

const router = useRouter()
const { t } = useI18n()

const characterOptions = ref([])
const characterLoading = ref(false)
const characterId = ref('')
const issuing = ref(false)
const bindCode = ref(null)
const copied = ref(false)
const bindings = ref([])
const loadingList = ref(false)
const removingId = ref(null)
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

async function loadCharacters() {
  characterLoading.value = true
  try {
    const chars = await listCharacters({ silent: true })
    characterOptions.value = (Array.isArray(chars) ? chars : []).map((c) => ({
      value: c.id,
      label: c.name,
    }))
    if (!characterId.value && characterOptions.value.length) {
      characterId.value = characterOptions.value[0].value
    }
  } catch {
    characterOptions.value = []
  } finally {
    characterLoading.value = false
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
  if (!characterId.value) return
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
  margin: 0;
  padding-left: 18px;
  font-size: 12.5px;
  line-height: 1.9;
  opacity: 0.8;
}

.qq-empty {
  font-size: 13px;
  opacity: 0.6;
  margin: 0;
}

.qq-binding {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 0;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
}

.qq-binding:first-of-type {
  border-top: none;
}
</style>
