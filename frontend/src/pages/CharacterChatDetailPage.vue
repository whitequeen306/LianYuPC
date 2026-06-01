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

  <div v-else class="detail-page stagger-container">
    <header class="page-header">
      <div class="header-left">
        <el-button text :icon="ArrowLeft" @click="router.push('/characters')">返回</el-button>
        <div class="character-hero">
          <div class="hero-avatar">
            <img v-if="character.avatarUrl" :src="resolveMediaUrl(character.avatarUrl)" />
            <el-icon v-else :size="22"><User /></el-icon>
          </div>
          <div>
            <h1 class="page-title">{{ character.name }} · 聊天详情</h1>
            <p class="page-desc">配置这个角色的聊天背景、主动消息开关、免打扰与拉黑状态。</p>
          </div>
        </div>
      </div>
    </header>

    <section class="detail-card glass stagger-item">
      <el-form label-position="top" class="detail-form">
        <div class="section-title">聊天背景</div>
        <div class="form-grid two-col">
          <el-form-item label="使用全局背景">
            <el-switch v-model="form.useGlobalChatBackground" />
          </el-form-item>
          <el-form-item label="聊天背景图（上传图片）">
            <div class="chat-bg-uploader" :class="{ disabled: form.useGlobalChatBackground }">
              <div
                class="chat-bg-preview"
                :style="chatBgPreviewStyle"
                @click="triggerBgUpload"
                @pointerdown.prevent="onBgPointerDown"
                @pointermove.prevent="onBgPointerMove"
                @pointerup="onBgPointerUp"
                @pointercancel="onBgPointerUp"
                @pointerleave="onBgPointerUp"
              >
                <div v-if="!form.chatBackgroundImageUrl" class="chat-bg-placeholder">点击上传背景图</div>
              </div>
              <div class="chat-bg-actions">
                <el-button size="small" :disabled="form.useGlobalChatBackground || bgUploading" @click="triggerBgUpload">
                  {{ form.chatBackgroundImageUrl ? '更换图片' : '上传图片' }}
                </el-button>
                <el-button size="small" text :disabled="form.useGlobalChatBackground || !form.chatBackgroundImageUrl || bgUploading" @click="clearBgImage">
                  清除图片
                </el-button>
                <span class="chat-bg-hint">JPG/PNG/WebP/GIF，建议横图，最大 5MB；可在预览图里拖动调整显示焦点</span>
              </div>
              <input
                ref="bgFileInput"
                type="file"
                accept="image/jpeg,image/png,image/webp,image/gif"
                style="display:none"
                @change="handleBgFileChange"
              />
            </div>
          </el-form-item>
        </div>

        <div class="section-title">消息与状态</div>
        <div class="form-grid two-col">
          <el-form-item label="允许主动消息">
            <el-switch v-model="form.proactiveEnabled" />
          </el-form-item>
          <el-form-item label="拉黑角色">
            <el-switch v-model="form.blocked" />
          </el-form-item>
        </div>

        <div class="section-title">免打扰</div>
        <div class="form-grid three-col">
          <el-form-item label="启用免打扰">
            <el-switch v-model="form.doNotDisturbEnabled" />
          </el-form-item>
          <el-form-item label="开始时间">
            <el-time-select
              v-model="dndStart"
              start="00:00"
              step="00:30"
              end="23:30"
              :disabled="!form.doNotDisturbEnabled"
              placeholder="开始时间"
            />
          </el-form-item>
          <el-form-item label="结束时间">
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
          <el-button type="primary" class="btn-cta" :loading="saving" @click="handleSave">保存设置</el-button>
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
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, Loading, User, WarningFilled } from '@element-plus/icons-vue'
import { getCharacter, updateCharacter, uploadChatBackground } from '@/api/character'
import { deleteConversation, listConversations } from '@/api/conversation'
import { resolveMediaUrl } from '@/utils/media'
import { normalizeHex } from '@/utils/themeColor'

const route = useRoute()
const router = useRouter()
const loading = ref(true)
const saving = ref(false)
const clearing = ref(false)
const bgUploading = ref(false)
const character = ref(null)
const bgFileInput = ref(null)
const draggingBg = ref(false)

const form = reactive({
  chatBackgroundKey: '',
  chatBackgroundImageUrl: '',
  chatBackgroundPosX: 50,
  chatBackgroundPosY: 50,
  useGlobalChatBackground: true,
  proactiveEnabled: true,
  doNotDisturbEnabled: false,
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

onMounted(loadCharacter)

async function loadCharacter() {
  loading.value = true
  try {
    const data = await getCharacter(route.params.id)
    character.value = data
    const settings = data.settings || {}
    form.chatBackgroundKey = settings.chatBackgroundKey || ''
    form.chatBackgroundImageUrl = settings.chatBackgroundImageUrl || ''
    form.chatBackgroundPosX = clampPercentage(settings.chatBackgroundPosX, 50)
    form.chatBackgroundPosY = clampPercentage(settings.chatBackgroundPosY, 50)
    form.useGlobalChatBackground = settings.useGlobalChatBackground ?? true
    form.proactiveEnabled = settings.proactiveEnabled ?? true
    form.doNotDisturbEnabled = settings.doNotDisturbEnabled ?? false
    form.blocked = settings.blocked ?? false
    form.dndStartMinutes = Number.isFinite(Number(settings.dndStartMinutes)) ? Number(settings.dndStartMinutes) : 23 * 60
    form.dndEndMinutes = Number.isFinite(Number(settings.dndEndMinutes)) ? Number(settings.dndEndMinutes) : 8 * 60
  } catch {
    character.value = null
  } finally {
    loading.value = false
  }
}

async function handleSave() {
  if (!character.value) return
  saving.value = true
  try {
    const settings = {
      chatBackgroundKey: sanitizeBackgroundKey(form.chatBackgroundKey),
      chatBackgroundImageUrl: form.chatBackgroundImageUrl || null,
      chatBackgroundPosX: clampPercentage(form.chatBackgroundPosX),
      chatBackgroundPosY: clampPercentage(form.chatBackgroundPosY),
      useGlobalChatBackground: form.useGlobalChatBackground,
      proactiveEnabled: form.proactiveEnabled,
      doNotDisturbEnabled: form.doNotDisturbEnabled,
      dndStartMinutes: clampMinutes(form.dndStartMinutes),
      dndEndMinutes: clampMinutes(form.dndEndMinutes),
      blocked: form.blocked
    }
    const updated = await updateCharacter(character.value.id, { settings })
    character.value = updated
    ElMessage.success('聊天详情已保存')
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
    await deleteConversation(target.id)
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

function triggerBgUpload() {
  if (form.useGlobalChatBackground || bgUploading.value) return
  bgFileInput.value?.click()
}

async function handleBgFileChange(e) {
  const file = e.target.files?.[0]
  e.target.value = ''
  if (!file || !character.value) return
  if (!file.type.startsWith('image/')) {
    ElMessage.warning('请上传图片文件')
    return
  }
  if (file.size > 5 * 1024 * 1024) {
    ElMessage.warning('图片大小不能超过 5MB')
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
  if (form.useGlobalChatBackground || !form.chatBackgroundImageUrl) return
  draggingBg.value = true
  updateBgPositionFromPointer(e)
}

function onBgPointerMove(e) {
  if (!draggingBg.value || form.useGlobalChatBackground || !form.chatBackgroundImageUrl) return
  updateBgPositionFromPointer(e)
}

function onBgPointerUp() {
  draggingBg.value = false
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
  max-width: 980px;
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

.chat-bg-uploader {
  display: flex;
  gap: $space-3;
  align-items: center;
  width: 100%;

  &.disabled {
    opacity: 0.55;
    pointer-events: none;
  }
}

.chat-bg-preview {
  width: 168px;
  height: 94px;
  border-radius: $radius-md;
  border: 1px dashed rgba($color-pink-rgb, 0.35);
  overflow: hidden;
  background: rgba($color-bg-surface, 0.5);
  cursor: pointer;
  touch-action: none;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;

  &:active {
    cursor: grabbing;
  }
}

.chat-bg-placeholder {
  font-size: $font-size-xs;
  color: $color-text-muted;
}

.chat-bg-actions {
  display: flex;
  flex-direction: column;
  gap: $space-2;
}

.chat-bg-hint {
  font-size: $font-size-xs;
  color: $color-text-muted;
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

@media (max-width: 900px) {
  .form-grid.two-col,
  .form-grid.three-col,
  .danger-card {
    grid-template-columns: 1fr;
    display: grid;
  }
}
</style>
