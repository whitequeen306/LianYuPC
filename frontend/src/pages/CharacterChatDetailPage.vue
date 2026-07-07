<template>
  <div v-if="loading" class="detail-page loading-state">
    <el-icon class="is-loading" :size="24"><Loading /></el-icon>
    <span>加载中...</span>
  </div>

  <div v-else-if="!character" class="detail-page empty-state glass">
    <div class="empty-icon">
      <el-icon :size="42"><WarningFilled /></el-icon>
    </div>
    <h3>角色不存在</h3>
    <el-button type="primary" class="btn-cta" @click="router.push('/characters')">返回角色页</el-button>
  </div>

  <div v-else class="detail-page companion-page stagger-container">
    <header class="page-header">
      <div class="header-left">
        <el-button text :icon="ArrowLeft" @click="goBack">返回</el-button>
        <div class="character-hero">
          <div class="hero-avatar">
            <img v-if="characterAvatar" :src="resolveMediaUrl(characterAvatar)" />
            <el-icon v-else :size="22"><User /></el-icon>
          </div>
          <div>
            <h1 class="page-title">{{ t('characterSettings.pageTitle', { name: character.name }) }}</h1>
            <p class="page-desc">{{ t('characterSettings.pageDesc') }}</p>
          </div>
        </div>
      </div>
    </header>

    <section class="detail-card glass stagger-item">
      <el-form label-position="top" class="detail-form">
        <div class="section-title">{{ t('characterSettings.chatBackground') }}</div>
        <el-form-item :label="t('characterSettings.useGlobalBackground')">
          <el-switch v-model="form.useGlobalChatBackground" />
        </el-form-item>
        <div class="chat-bg-section" :class="{ 'is-global': form.useGlobalChatBackground }">
          <div class="chat-bg-uploader">
            <div
              class="chat-bg-preview"
              :class="{ 'has-image': !!form.chatBackgroundImageUrl }"
              :style="chatBgPreviewStyle"
              role="button"
              tabindex="0"
              :aria-disabled="bgUploading"
              @click="onBgPreviewClick"
              @keydown.enter.prevent="onBgPreviewClick"
              @pointerdown="onBgPointerDown"
              @pointermove="onBgPointerMove"
              @pointerup="onBgPointerUp"
              @pointercancel="onBgPointerUp"
            >
              <div v-if="!form.chatBackgroundImageUrl" class="chat-bg-placeholder">点击上传背景图</div>
            </div>
            <div class="chat-bg-actions">
              <el-button size="small" :loading="bgUploading" @click="triggerBgUpload">
                {{ form.chatBackgroundImageUrl ? '更换图片' : '上传图片' }}
              </el-button>
              <el-button
                size="small"
                text
                :disabled="!form.chatBackgroundImageUrl || bgUploading"
                @click="clearBgImage"
              >
                清除图片
              </el-button>
              <span v-if="form.useGlobalChatBackground" class="chat-bg-hint chat-bg-hint--warn">
                已启用全局背景，上传图片将自动切换为角色专属背景
              </span>
              <span class="chat-bg-hint">JPG/PNG/WebP/GIF，建议横图，最大 8MB；上传后可在预览图里拖动调整显示焦点</span>
            </div>
            <input
              ref="bgFileInput"
              type="file"
              accept="image/jpeg,image/png,image/webp,image/gif"
              class="chat-bg-file-input"
              @change="handleBgFileChange"
            />
          </div>
        </div>

        <div class="section-title">{{ t('characterSettings.location') }}</div>
        <template v-if="isRealCityMode">
          <el-form-item :label="t('characterSettings.realCity')">
            <el-input
              v-model="form.city"
              :placeholder="t('cityMode.realCityPlaceholder')"
              maxlength="50"
              show-word-limit
            />
            <div class="field-hint">{{ t('characterSettings.realCityHint') }}</div>
          </el-form-item>
        </template>
        <div v-else class="city-mode-form__fictional-note">
          {{ t('characterSettings.fictionalCityLocked') }}
        </div>

        <div class="section-title">{{ t('characterSettings.behavior') }}</div>
        <div class="form-grid two-col">
          <el-form-item :label="t('characterSettings.proactiveEnabled')">
            <el-switch v-model="form.proactiveEnabled" />
          </el-form-item>
          <el-form-item :label="t('characterSettings.showInnerThoughts')">
            <el-switch v-model="form.showInnerThoughts" />
          </el-form-item>
          <el-form-item :label="t('characterSettings.blocked')">
            <el-switch v-model="form.blocked" />
          </el-form-item>
        </div>

        <div class="section-title">{{ t('characterSettings.nightRest') }}</div>
        <div class="form-grid three-col">
          <el-form-item :label="t('characterSettings.nightRestEnabled')">
            <el-switch v-model="form.doNotDisturbEnabled" />
          </el-form-item>
          <el-form-item :label="t('characterSettings.dndStart')">
            <el-time-select
              v-model="dndStart"
              start="00:00"
              step="00:30"
              end="23:30"
              :disabled="!form.doNotDisturbEnabled"
              placeholder="开始时间"
            />
          </el-form-item>
          <el-form-item :label="t('characterSettings.dndEnd')">
            <el-time-select
              v-model="dndEnd"
              start="00:00"
              step="00:30"
              end="23:30"
              :disabled="!form.doNotDisturbEnabled"
              placeholder="结束时间"
            />
          </el-form-item>
        </div>

        <div class="form-actions">
          <el-button type="primary" class="btn-cta" :loading="saving" @click="handleSave">{{ t('characterSettings.save') }}</el-button>
        </div>
      </el-form>
    </section>

    <section class="danger-card glass stagger-item">
      <div>
        <h3>清空聊天记录</h3>
        <p>删除这个角色当前的单聊会话和消息。下次聊天会自动创建新会话。</p>
      </div>
      <el-button type="danger" plain :loading="clearing" @click="handleClearConversation">清空记录</el-button>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, Loading, User, WarningFilled } from '@element-plus/icons-vue'
import { getCharacter, updateCharacter, uploadChatBackground } from '@/api/character'
import { clearConversationMessages, listConversations } from '@/api/conversation'
import { resolveMediaUrl } from '@/utils/media'
import { pickCharacterAvatarRaw } from '@/utils/characterAvatar'
import { normalizeHex } from '@/utils/themeColor'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const loading = ref(true)
const saving = ref(false)
const clearing = ref(false)
const bgUploading = ref(false)
const character = ref(null)
const bgFileInput = ref(null)
const draggingBg = ref(false)
const bgDragMoved = ref(false)

const isRealCityMode = computed(() => {
  const settings = character.value?.settings || {}
  const mode = settings.city_mode
  if (mode === 'fictional') return false
  if (mode === 'real') return true
  const legacy = settings.use_fictional_city
  return !(legacy === true || legacy === 'true')
})

const form = reactive({
  city: '',
  chatBackgroundKey: '',
  chatBackgroundImageUrl: '',
  chatBackgroundPosX: 50,
  chatBackgroundPosY: 50,
  useGlobalChatBackground: true,
  proactiveEnabled: true,
  showInnerThoughts: true,
  doNotDisturbEnabled: true,
  blocked: false,
  dndStartMinutes: 23 * 60,
  dndEndMinutes: 8 * 60
})

const dndStart = computed({
  get: () => minutesToTime(form.dndStartMinutes),
  set: value => {
    form.dndStartMinutes = timeToMinutes(value, 23 * 60)
  }
})

const dndEnd = computed({
  get: () => minutesToTime(form.dndEndMinutes),
  set: value => {
    form.dndEndMinutes = timeToMinutes(value, 8 * 60)
  }
})

const chatBgPreviewStyle = computed(() => {
  const url = resolveMediaUrl(form.chatBackgroundImageUrl || '')
  if (!url) return {}
  return {
    backgroundImage: `url("${url}")`,
    backgroundPosition: `${clampPercentage(form.chatBackgroundPosX)}% ${clampPercentage(form.chatBackgroundPosY)}%`,
    backgroundSize: 'cover',
    backgroundRepeat: 'no-repeat'
  }
})

const characterAvatar = computed(() => pickCharacterAvatarRaw(character.value, 'thumb'))

onMounted(loadCharacter)

// 路由参数变化时重新加载（从外部导航回同一页面时不依赖 onMounted）
watch(() => route.params.id, () => { loadCharacter() })

function populateForm(settings) {
  form.chatBackgroundKey = settings.chatBackgroundKey || ''
  form.chatBackgroundImageUrl = settings.chatBackgroundImageUrl || ''
  form.chatBackgroundPosX = clampPercentage(settings.chatBackgroundPosX, 50)
  form.chatBackgroundPosY = clampPercentage(settings.chatBackgroundPosY, 50)
  form.useGlobalChatBackground = settings.useGlobalChatBackground ?? true
  form.proactiveEnabled = settings.proactiveEnabled ?? true
  form.showInnerThoughts = settings.showInnerThoughts ?? true
  form.doNotDisturbEnabled = settings.doNotDisturbEnabled ?? true
  form.blocked = settings.blocked ?? false
  form.dndStartMinutes = Number.isFinite(Number(settings.dndStartMinutes)) ? Number(settings.dndStartMinutes) : 23 * 60
  form.dndEndMinutes = Number.isFinite(Number(settings.dndEndMinutes)) ? Number(settings.dndEndMinutes) : 8 * 60
  form.city = typeof settings.city === 'string' ? settings.city : ''
}

async function loadCharacter() {
  loading.value = true
  try {
    const data = await getCharacter(route.params.id)
    character.value = data
    const settings = data.settings || {}
    populateForm(settings)
  } catch {
    character.value = null
  } finally {
    loading.value = false
  }
}

async function handleSave() {
  if (!character.value) return
  if (isRealCityMode.value) {
    const city = String(form.city || '').trim()
    if (!city) {
      ElMessage.warning(t('cityMode.realCityPlaceholder'))
      return
    }
    if (city.length > 50) {
      ElMessage.warning(t('characterSettings.realCityTooLong'))
      return
    }
  }
  saving.value = true
  try {
    const settings = {
      chatBackgroundKey: sanitizeBackgroundKey(form.chatBackgroundKey),
      chatBackgroundImageUrl: form.chatBackgroundImageUrl || null,
      chatBackgroundPosX: clampPercentage(form.chatBackgroundPosX),
      chatBackgroundPosY: clampPercentage(form.chatBackgroundPosY),
      useGlobalChatBackground: form.useGlobalChatBackground,
      proactiveEnabled: form.proactiveEnabled,
      showInnerThoughts: form.showInnerThoughts,
      doNotDisturbEnabled: form.doNotDisturbEnabled,
      dndStartMinutes: clampMinutes(form.dndStartMinutes),
      dndEndMinutes: clampMinutes(form.dndEndMinutes),
      blocked: form.blocked
    }
    if (isRealCityMode.value) {
      settings.city = String(form.city || '').trim()
    }
    const updated = await updateCharacter(character.value.id, { settings })
    character.value = updated
    populateForm(updated.settings || {})
    ElMessage.success(t('characterSettings.saved'))
  } finally {
    saving.value = false
  }
}

async function handleClearConversation() {
  if (!character.value) return
  try {
    await ElMessageBox.confirm(
      `确定清空「${character.value.name}」当前的单聊记录吗？`,
      '清空聊天记录',
      { type: 'warning', confirmButtonText: '清空', cancelButtonText: '取消' }
    )
  } catch {
    return
  }

  clearing.value = true
  try {
    const conversations = await listConversations()
    const target = (conversations || []).find(
      item => item.mode === 'SINGLE' && item.characterId === character.value.id
    )
    if (!target) {
      ElMessage.info('当前没有可清空的单聊记录')
      return
    }
    await clearConversationMessages(target.id)
    ElMessage.success('聊天记录已清空')
  } finally {
    clearing.value = false
  }
}

function sanitizeBackgroundKey(value) {
  const trimmed = String(value || '').trim()
  if (!trimmed) return null
  const hex = normalizeHex(trimmed)
  return hex || trimmed
}

function goBack() {
  if (window.history.length > 1) {
    router.back()
    return
  }
  router.push('/app/characters')
}

function triggerBgUpload() {
  if (bgUploading.value) return
  if (form.useGlobalChatBackground) {
    form.useGlobalChatBackground = false
  }
  bgFileInput.value?.click()
}

function onBgPreviewClick() {
  if (bgUploading.value || bgDragMoved.value) return
  if (form.chatBackgroundImageUrl) return
  triggerBgUpload()
}

async function handleBgFileChange(e) {
  const file = e.target.files?.[0]
  e.target.value = ''
  if (!file || !character.value) return
  if (!file.type.startsWith('image/')) {
    ElMessage.warning('请上传图片文件')
    return
  }
  if (file.size > 8 * 1024 * 1024) {
    ElMessage.warning('图片大小不能超过 8MB')
    return
  }
  bgUploading.value = true
  try {
    const updated = await uploadChatBackground(character.value.id, file)
    character.value = updated
    const settings = updated.settings || {}
    form.chatBackgroundImageUrl = settings.chatBackgroundImageUrl || ''
    form.chatBackgroundPosX = clampPercentage(settings.chatBackgroundPosX, 50)
    form.chatBackgroundPosY = clampPercentage(settings.chatBackgroundPosY, 50)
    form.useGlobalChatBackground = settings.useGlobalChatBackground ?? false
    ElMessage.success('聊天背景图已上传')
  } finally {
    bgUploading.value = false
  }
}

function clearBgImage() {
  form.chatBackgroundImageUrl = ''
  form.chatBackgroundPosX = 50
  form.chatBackgroundPosY = 50
}

function onBgPointerDown(e) {
  if (!form.chatBackgroundImageUrl || form.useGlobalChatBackground || bgUploading.value) return
  if (e.button !== undefined && e.button !== 0) return
  draggingBg.value = true
  bgDragMoved.value = false
  e.currentTarget?.setPointerCapture?.(e.pointerId)
  updateBgPositionFromPointer(e)
}

function onBgPointerMove(e) {
  if (!draggingBg.value || !form.chatBackgroundImageUrl) return
  bgDragMoved.value = true
  updateBgPositionFromPointer(e)
}

function onBgPointerUp(e) {
  if (draggingBg.value) {
    e?.currentTarget?.releasePointerCapture?.(e.pointerId)
  }
  draggingBg.value = false
  window.setTimeout(() => {
    bgDragMoved.value = false
  }, 0)
}

function updateBgPositionFromPointer(e) {
  const rect = e.currentTarget?.getBoundingClientRect?.()
  if (!rect || !rect.width || !rect.height) return
  const x = ((e.clientX - rect.left) / rect.width) * 100
  const y = ((e.clientY - rect.top) / rect.height) * 100
  form.chatBackgroundPosX = clampPercentage(x)
  form.chatBackgroundPosY = clampPercentage(y)
}

function timeToMinutes(value, fallback) {
  if (!value || typeof value !== 'string' || !value.includes(':')) return fallback
  const [hour, minute] = value.split(':').map(Number)
  if (!Number.isFinite(hour) || !Number.isFinite(minute)) return fallback
  return clampMinutes(hour * 60 + minute)
}

function minutesToTime(minutes) {
  const normalized = clampMinutes(minutes)
  const hour = String(Math.floor(normalized / 60)).padStart(2, '0')
  const minute = String(normalized % 60).padStart(2, '0')
  return `${hour}:${minute}`
}

function clampMinutes(value) {
  const n = Number(value)
  if (!Number.isFinite(n)) return 0
  return Math.max(0, Math.min(23 * 60 + 59, Math.round(n)))
}

function clampPercentage(value, fallback = 50) {
  const n = Number(value)
  if (!Number.isFinite(n)) return fallback
  return Math.max(0, Math.min(100, Math.round(n)))
}
</script>

<style lang="scss" scoped>
.detail-page {
  width: 100%;
  max-width: $narrow-page-max;
  padding-bottom: 120px;
}

.loading-state,
.empty-state {
  padding: $space-6;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: $space-4;
  color: $color-text-muted;
}

.empty-state {
  border-radius: $radius-xl;
}

.empty-icon {
  width: 84px;
  height: 84px;
  border-radius: $radius-xl;
  background: rgba($color-pink-rgb, 0.08);
  display: flex;
  align-items: center;
  justify-content: center;
  color: $color-pink-primary;
}

.page-header {
  margin-bottom: $space-6;
}

.header-left {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: $space-4;
}

.character-hero {
  display: flex;
  align-items: center;
  gap: $space-4;
}

.hero-avatar {
  width: 56px;
  height: 56px;
  border-radius: $radius-lg;
  overflow: hidden;
  background: rgba($color-pink-rgb, 0.08);
  display: flex;
  align-items: center;
  justify-content: center;
  color: $color-pink-primary;

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
}

.page-title {
  font-size: $font-size-2xl;
  color: $color-text-primary;
  margin-bottom: $space-2;
}

.page-desc {
  color: $color-text-muted;
  font-size: $font-size-sm;
}

.detail-card,
.danger-card {
  border-radius: $radius-xl;
  padding: $space-6;
}

.detail-card {
  margin-bottom: $space-6;
}

.detail-form {
  display: flex;
  flex-direction: column;
  gap: $space-3;
}

.section-title {
  font-size: $font-size-base;
  font-weight: $font-weight-semibold;
  color: $color-text-primary;
  margin-top: $space-2;
}

.form-grid {
  display: grid;
  gap: $space-4;
}

.form-grid.two-col {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.form-grid.three-col {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.form-actions {
  margin-top: $space-4;
}

.chat-bg-section {
  margin-bottom: $space-2;

  &.is-global {
    opacity: 0.88;
  }
}

.chat-bg-uploader {
  display: flex;
  flex-wrap: wrap;
  gap: $space-4;
  align-items: flex-start;
  width: 100%;
}

.chat-bg-file-input {
  display: none;
}

.chat-bg-preview {
  width: min(100%, 280px);
  aspect-ratio: 16 / 9;
  border-radius: $radius-md;
  border: 1px dashed rgba($color-pink-rgb, 0.35);
  overflow: hidden;
  background: rgba($color-bg-surface, 0.5);
  cursor: pointer;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: border-color $transition-fast, box-shadow $transition-fast;

  &:hover {
    border-color: rgba($color-pink-rgb, 0.55);
    box-shadow: 0 0 0 1px rgba($color-pink-rgb, 0.12);
  }

  &.has-image {
    cursor: grab;

    &:active {
      cursor: grabbing;
    }
  }
}

.chat-bg-placeholder {
  font-size: $font-size-xs;
  color: $color-text-muted;
}

.chat-bg-actions {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: flex-start;
  gap: $space-2;
  min-width: min(100%, 240px);
}

.chat-bg-hint {
  font-size: $font-size-xs;
  color: $color-text-muted;
  line-height: $line-height-relaxed;
}

.chat-bg-hint--warn {
  color: $color-pink-primary;
}

.danger-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: $space-4;
}

.danger-card h3 {
  color: $color-text-primary;
  margin-bottom: $space-2;
}

.danger-card p {
  color: $color-text-muted;
  font-size: $font-size-sm;
}

.field-hint {
  margin-top: $space-2;
  font-size: $font-size-xs;
  color: $color-text-muted;
  line-height: $line-height-relaxed;
}

.city-mode-form__fictional-note {
  margin: 0 0 $space-4;
  padding: $space-3 $space-4;
  border-radius: $radius-md;
  background: rgba($color-pink-rgb, 0.06);
  border: 1px solid rgba($color-pink-rgb, 0.1);
  color: $color-text-secondary;
  font-size: $font-size-sm;
  line-height: $line-height-relaxed;
}

@media (max-width: 900px) {
  .form-grid.two-col,
  .form-grid.three-col,
  .danger-card {
    grid-template-columns: 1fr;
    display: grid;
  }
}
</style>
