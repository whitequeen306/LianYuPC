<template>
  <div class="characters-page stagger-container">
    <header class="page-header">
      <div>
        <h1 class="page-title">角色管理</h1>
        <p class="page-desc">创建和管理你的 AI 角色，点击角色卡片即可进入对话</p>
      </div>
      <el-button type="primary" class="btn-cta" :icon="Plus" @click="showCreateDialog">
        创建角色
      </el-button>
    </header>

    <div v-if="loading" class="loading-state">
      <el-icon class="is-loading" :size="24"><Loading /></el-icon>
      <span>加载中...</span>
    </div>

    <div v-else-if="characters.length === 0" class="empty-state glass stagger-item">
      <div class="empty-icon">
        <el-icon :size="44"><User /></el-icon>
      </div>
      <h3>还没有角色</h3>
      <p>创建你的第一个 AI 角色，赋予它独特的性格和说话风格</p>
      <el-button type="primary" class="btn-cta btn-cta-lg" :icon="Plus" @click="showCreateDialog">
        创建角色
      </el-button>
    </div>

    <div v-else class="character-grid">
      <div
        v-for="(char, idx) in characters"
        :key="char.id"
        class="character-card glass stagger-item"
        :class="{ 'has-unread': unreadCountForCharacter(char.id) > 0 }"
        :style="{ animationDelay: `${idx * 0.05}s` }"
        role="button"
        tabindex="0"
        @click="startChat(char)"
        @keydown.enter="startChat(char)"
      >
        <div class="card-media">
          <img v-if="char.avatarUrl" :src="resolveMediaUrl(char.avatarUrl)" class="avatar-img" />
          <div v-else class="avatar-placeholder">
            <el-icon :size="28"><User /></el-icon>
          </div>
        </div>

        <span
          v-if="unreadCountForCharacter(char.id) > 0"
          class="character-card-unread"
          :aria-label="t('characters.unread')"
        >{{ formatBadgeCount(unreadCountForCharacter(char.id)) }}</span>

        <div class="card-body">
          <h3 class="char-name">{{ char.name }}</h3>
          <p class="char-preview">{{ lastMessageForCharacter(char.id) }}</p>

          <div v-if="getCharMetaFields(char).length" class="char-meta-fields">
            <span
              v-for="f in getCharMetaFields(char)"
              :key="f.key"
              class="meta-tag"
            >{{ f.label }}</span>
          </div>
        </div>

        <div class="card-footer" @click.stop>
          <el-button text :icon="ChatDotRound" size="small" @click="startChat(char)">对话</el-button>
          <el-button text :icon="Delete" size="small" class="btn-delete" @click="confirmDelete(char)">
            删除
          </el-button>
        </div>
      </div>
    </div>

    <!-- Create Dialog -->
    <el-dialog
      v-model="dialogVisible"
      class="character-dialog"
      title="创建角色"
      width="560px"
      destroy-on-close
      align-center
    >
      <el-form
        ref="formRef"
        :model="form"
        :rules="formRules"
        label-position="top"
        @submit.prevent="handleSubmit"
      >
        <el-form-item label="角色名称" prop="name">
          <el-input v-model="form.name" placeholder="给你的角色起个名字" />
        </el-form-item>

        <el-form-item label="动漫角色参考（可选）">
          <div class="generate-row">
            <el-input
              v-model="generatorDescription"
              placeholder="例如：时崎狂三 / 雷姆 / 三笠·阿克曼，写名字或补充几条特征都可以"
            />
            <el-button
              type="default"
              :loading="generating"
              @click="handleGenerateCharacter"
            >
              AI 生成设定
            </el-button>
          </div>
          <div class="field-hint">
            不联网检索，只基于你输入和模型常识生成，然后自动填充下方字段。
          </div>
        </el-form-item>

        <el-form-item label="性格设定（Prompt Template）" prop="promptTemplate">
          <el-input
            v-model="form.promptTemplate"
            type="textarea"
            :rows="6"
            placeholder="用自然语言描述角色的性格、说话语气、背景故事...&#10;&#10;示例：你是一个温柔体贴的邻家姐姐，说话语气温暖亲切，喜欢用「呢」「哦」等语气词..."
          />
        </el-form-item>

        <div class="form-row-3">
          <el-form-item label="年龄">
            <el-input v-model="form.age" placeholder="如 20" />
          </el-form-item>
          <el-form-item label="性别">
            <el-select v-model="form.gender" placeholder="选择性别" style="width:100%">
              <el-option label="女" value="女" />
              <el-option label="男" value="男" />
              <el-option label="其他" value="其他" />
            </el-select>
          </el-form-item>
          <el-form-item label="说话风格">
            <el-select v-model="form.speakingStyle" placeholder="选择风格" style="width:100%">
              <el-option label="温柔" value="温柔" />
              <el-option label="活泼" value="活泼" />
              <el-option label="冷静" value="冷静" />
              <el-option label="傲娇" value="傲娇" />
              <el-option label="元气" value="元气" />
              <el-option label="慵懒" value="慵懒" />
              <el-option label="成熟" value="成熟" />
              <el-option label="毒舌" value="毒舌" />
            </el-select>
          </el-form-item>
        </div>

        <el-form-item label="角色头像">
          <div
            class="avatar-upload"
            :class="{ 'is-dragging': isDragging }"
            @dragenter.prevent="isDragging = true"
            @dragover.prevent
            @dragleave.prevent="onDragLeave"
            @drop.prevent="onDrop"
          >
            <div class="avatar-preview" @click="triggerUpload">
              <img v-if="form.avatarUrl" :src="resolveMediaUrl(form.avatarUrl)" class="preview-img" />
              <template v-else>
                <el-icon :size="28"><UploadFilled /></el-icon>
                <span class="drop-hint">拖入图片</span>
              </template>
              <div v-if="isDragging" class="drop-overlay">
                <el-icon :size="32"><UploadFilled /></el-icon>
                <span>松开上传</span>
              </div>
            </div>
            <div class="avatar-actions">
              <el-button type="default" size="small" @click="triggerUpload">
                {{ form.avatarUrl ? '更换头像' : '选择图片' }}
              </el-button>
              <span class="avatar-hint">拖拽图片到左侧，或点击上传 · JPG/PNG/WebP · 最大 5MB</span>
            </div>
            <input
              ref="fileInput"
              type="file"
              accept="image/jpeg,image/png,image/webp,image/gif"
              style="display:none"
              @change="handleFileChange"
            />
          </div>
        </el-form-item>

        <el-form-item label="额外设置（JSON 格式，可选）">
          <el-input
            v-model="settingsText"
            type="textarea"
            :rows="2"
            placeholder='{"hobbies": "阅读、旅行"}  —  高级自定义字段'
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button type="default" @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" class="btn-cta" :loading="submitting" @click="handleSubmit">
          创建
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { listCharacters, createCharacter, deleteCharacter, uploadAvatar, generateCharacter } from '@/api/character'
import { listConversations, createConversation } from '@/api/conversation'
import { listNotifications } from '@/api/notification'
import { useConversationUnread } from '@/composables/useConversationUnread'
import { useNotificationsStore } from '@/stores/notifications'
import { Plus, Delete, User, Loading, ChatDotRound, UploadFilled } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { resolveMediaUrl } from '@/utils/media'

const { t } = useI18n()
const router = useRouter()
const notificationsStore = useNotificationsStore()
const {
  ingestConversations,
  ingestUnreadNotifications,
  refreshUnreadFromApi,
  unreadCountForCharacter,
  formatBadgeCount
} = useConversationUnread()

const characters = ref([])
/** characterId -> { id, lastMessage } for SINGLE conversations */
const singleConvByCharacterId = ref({})
const loading = ref(true)
const dialogVisible = ref(false)
const submitting = ref(false)
const generating = ref(false)
const formRef = ref(null)
const fileInput = ref(null)
const settingsText = ref('')
const avatarFile = ref(null)
const generatorDescription = ref('')
const isDragging = ref(false)

const initialForm = () => ({
  name: '',
  promptTemplate: '',
  avatarUrl: '',
  age: '',
  gender: '',
  speakingStyle: ''
})

const form = reactive(initialForm())

const formRules = {
  name: [{ required: true, message: '请输入角色名称', trigger: 'blur' }],
  promptTemplate: [{ required: true, message: '请输入性格设定', trigger: 'blur' }]
}

onMounted(async () => {
  await notificationsStore.init()
  await fetchCharacters()
  await refreshUnreadFromApi()
})

watch(
  () => notificationsStore.unreadCount,
  () => { refreshUnreadFromApi() }
)

watch(
  () => notificationsStore.latest,
  async () => {
    await refreshUnreadFromApi()
    try {
      const convList = await listConversations()
      ingestConversations(convList || [])
      singleConvByCharacterId.value = buildSingleConvMap(convList)
    } catch {}
  }
)

function buildSingleConvMap(convList) {
  const map = {}
  for (const c of convList || []) {
    if (c?.mode !== 'SINGLE' || !c.characterId) continue
    const prev = map[c.characterId]
    if (!prev) {
      map[c.characterId] = { id: c.id, lastMessage: c.lastMessage || '' }
      continue
    }
    if (c.lastMessage && !prev.lastMessage) {
      map[c.characterId] = { id: c.id, lastMessage: c.lastMessage }
    }
  }
  return map
}

async function fetchCharacters() {
  loading.value = true
  try {
    const [chars, convList, unreadList] = await Promise.all([
      listCharacters().catch(() => []),
      listConversations().catch(() => []),
      listNotifications({ unreadOnly: true, limit: 200 }).catch(() => [])
    ])
    characters.value = chars || []
    ingestConversations(convList || [])
    ingestUnreadNotifications(unreadList || [])
    singleConvByCharacterId.value = buildSingleConvMap(convList)
  } catch {} finally {
    loading.value = false
  }
}

function lastMessageForCharacter(characterId) {
  const preview = singleConvByCharacterId.value[characterId]?.lastMessage?.trim()
  return preview || t('characters.noMessagesYet')
}

function showCreateDialog() {
  Object.assign(form, initialForm())
  settingsText.value = ''
  avatarFile.value = null
  generatorDescription.value = ''
  isDragging.value = false
  dialogVisible.value = true
}

function triggerUpload() {
  fileInput.value?.click()
}

function applyAvatarFile(file) {
  if (!file.type.startsWith('image/')) {
    ElMessage.warning('请上传图片文件（JPG / PNG / WebP）')
    return
  }
  if (file.size > 5 * 1024 * 1024) {
    ElMessage.warning('图片大小不能超过 5MB')
    return
  }
  avatarFile.value = file
  form.avatarUrl = URL.createObjectURL(file)
}

function handleFileChange(e) {
  const file = e.target.files?.[0]
  if (file) applyAvatarFile(file)
  e.target.value = ''
}

function onDragLeave(e) {
  if (e.currentTarget?.contains(e.relatedTarget)) return
  isDragging.value = false
}

function onDrop(e) {
  isDragging.value = false
  const file = e.dataTransfer?.files?.[0]
  if (file) applyAvatarFile(file)
}

async function handleSubmit() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    const settings = {}
    if (form.age) settings.age = form.age
    if (form.gender) settings.gender = form.gender
    if (form.speakingStyle) settings.speakingStyle = form.speakingStyle
    if (settingsText.value.trim()) {
      try {
        Object.assign(settings, JSON.parse(settingsText.value))
      } catch {
        ElMessage.warning('设置 JSON 格式不正确')
        submitting.value = false
        return
      }
    }
    const finalSettings = Object.keys(settings).length ? settings : null

    const data = { name: form.name, promptTemplate: form.promptTemplate, settings: finalSettings }
    let char = await createCharacter(data)

    if (avatarFile.value && char) {
      char = await uploadAvatar(char.id, avatarFile.value)
    }

    ElMessage.success('角色已创建')
    dialogVisible.value = false
    await fetchCharacters()
  } catch {} finally {
    submitting.value = false
  }
}

async function handleGenerateCharacter() {
  const description = generatorDescription.value?.trim()
  if (!description) {
    ElMessage.warning('请先输入一个动漫角色名称或描述')
    return
  }
  generating.value = true
  try {
    const draft = await generateCharacter({ description })
    form.name = draft.name || form.name
    form.promptTemplate = draft.promptTemplate || form.promptTemplate
    form.age = draft.age && draft.age !== '未知' ? draft.age : form.age
    form.gender = draft.gender && draft.gender !== '未知' ? draft.gender : form.gender
    form.speakingStyle = draft.speakingStyle || form.speakingStyle
    ElMessage.success('角色设定已生成并填充')
  } catch {
    // 错误提示由全局 http 拦截器处理
  } finally {
    generating.value = false
  }
}

async function confirmDelete(char) {
  try {
    await ElMessageBox.confirm(
      `确定要删除角色「${char.name}」吗？此操作不可撤销。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
    await deleteCharacter(char.id)
    ElMessage.success('角色已删除')
    characters.value = characters.value.filter(c => c.id !== char.id)
  } catch {}
}

function getCharMetaFields(char) {
  const s = char.settings || {}
  const fields = []
  if (s.age) fields.push({ key: 'age', label: `${s.age}岁` })
  if (s.gender) fields.push({ key: 'gender', label: s.gender })
  if (s.speakingStyle) fields.push({ key: 'speakingStyle', label: s.speakingStyle })
  return fields
}

async function startChat(char) {
  try {
    const existing = singleConvByCharacterId.value[char.id]
    const conv = existing?.id
      ? { id: existing.id }
      : await createConversation({ characterId: char.id, mode: 'SINGLE' })
    await notificationsStore.markConversationRead(conv.id)
    await refreshUnreadFromApi()
    router.push({ path: `/chat/${conv.id}` })
  } catch {}
}
</script>

<style lang="scss" scoped>
.characters-page { max-width: 820px; }

.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: $space-8;
  animation: fadeSlideUp 0.5s ease both;
}

.page-title {
  font-size: $font-size-2xl;
  font-weight: $font-weight-bold;
  color: $color-text-primary;
  margin-bottom: $space-2;
}

.page-desc {
  font-size: $font-size-sm;
  color: $color-text-muted;
}

.loading-state,
.empty-state {
  text-align: center;
  padding: $space-16 $space-8;
  color: $color-text-muted;
}

.empty-state {
  max-width: 420px;
  margin: $space-8 auto 0;
  border-radius: $radius-xl;
}

.empty-icon {
  width: 88px;
  height: 88px;
  border-radius: $radius-xl;
  background: linear-gradient(145deg, rgba($color-pink-rgb, 0.14), rgba($color-pink-rgb, 0.04));
  border: 1px solid rgba($color-pink-rgb, 0.15);
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 0 auto $space-6;
  color: $color-pink-primary;
  box-shadow: $shadow-glow-pink;
}

.empty-state h3 {
  color: $color-text-primary;
  font-size: $font-size-xl;
  margin-bottom: $space-3;
}

.empty-state p {
  color: $color-text-secondary;
  font-size: $font-size-base;
  line-height: $line-height-relaxed;
  margin-bottom: $space-8;
}

.character-grid {
  display: grid;
  gap: $space-4;
}

.character-card {
  position: relative;
  border-radius: $radius-lg;
  padding: $space-5 $space-6;
  padding-right: $space-10;
  display: flex;
  gap: $space-5;
  align-items: flex-start;
  animation: fadeSlideUp 0.5s cubic-bezier(0.4, 0, 0.2, 1) both;
  transition: border-color $transition-fast, box-shadow $transition-fast, transform $transition-fast;
  cursor: pointer;

  &:hover {
    border-color: rgba($color-pink-rgb, 0.12);
    box-shadow: $shadow-glow-pink;
    transform: translateY(-1px);
  }

  &:focus-visible {
    outline: 2px solid rgba($color-pink-rgb, 0.35);
    outline-offset: 2px;
  }

  &.has-unread {
    border-color: rgba($color-pink-rgb, 0.22);
  }
}

.character-card-unread {
  position: absolute;
  top: $space-4;
  right: $space-4;
  min-width: 20px;
  height: 20px;
  padding: 0 6px;
  border-radius: 999px;
  background: #ff4d4f;
  color: #fff;
  font-size: 11px;
  font-weight: 700;
  line-height: 20px;
  text-align: center;
  box-shadow: 0 0 0 2px rgba($color-bg-primary, 0.92), 0 2px 6px rgba(0, 0, 0, 0.2);
  z-index: 2;
  pointer-events: none;
}

.card-media {
  flex-shrink: 0;
  width: 64px; height: 64px;
  border-radius: $radius-lg;
  overflow: hidden;
}

.avatar-img { width: 100%; height: 100%; object-fit: cover; }

.avatar-placeholder {
  width: 100%; height: 100%;
  background: rgba($color-pink-rgb, 0.08);
  display: flex;
  align-items: center;
  justify-content: center;
  color: $color-pink-primary;
}

.card-body { flex: 1; min-width: 0; }

.char-name {
  font-size: $font-size-base;
  font-weight: $font-weight-semibold;
  color: $color-text-primary;
  margin-bottom: $space-1;
}

.char-preview {
  font-size: $font-size-sm;
  color: $color-text-muted;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  margin-bottom: $space-3;
  line-height: $line-height-relaxed;
}

.form-row-3 {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr;
  gap: $space-4;
}

.generate-row {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: $space-3;
}

.char-meta-fields {
  display: flex;
  flex-wrap: wrap;
  gap: $space-2;
  margin-bottom: $space-3;
}

.meta-tag {
  display: inline-block;
  font-size: 11px;
  font-weight: $font-weight-medium;
  color: $color-pink-primary;
  background: rgba($color-pink-rgb, 0.1);
  padding: 2px 10px;
  border-radius: $radius-pill;
  border: 1px solid rgba($color-pink-rgb, 0.15);
}

.card-footer {
  display: flex;
  flex-direction: column;
  gap: $space-1;
  flex-shrink: 0;

  .btn-delete:hover { color: $color-error !important; }
}

.avatar-upload {
  display: flex;
  align-items: center;
  gap: $space-4;
  padding: $space-2;
  border-radius: $radius-md;
  transition: background $transition-fast;

  &.is-dragging {
    background: rgba($color-pink-rgb, 0.06);
  }
}

.avatar-preview {
  position: relative;
  width: 96px;
  height: 96px;
  border-radius: $radius-lg;
  border: 2px dashed rgba($color-pink-rgb, 0.25);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: $space-1;
  cursor: pointer;
  color: $color-text-muted;
  overflow: hidden;
  transition: border-color $transition-fast, background $transition-fast;
  flex-shrink: 0;

  &:hover,
  .is-dragging & {
    border-color: $color-pink-primary;
    background: rgba($color-pink-rgb, 0.04);
  }
}

.drop-hint {
  font-size: 11px;
  color: $color-text-muted;
}

.drop-overlay {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: $space-1;
  background: rgba($color-pink-rgb, 0.18);
  color: $color-pink-primary;
  font-size: $font-size-xs;
  font-weight: $font-weight-medium;
  backdrop-filter: blur(2px);
}

.preview-img { width: 100%; height: 100%; object-fit: cover; }

.avatar-hint {
  display: block;
  font-size: $font-size-xs;
  color: $color-text-secondary;
  line-height: $line-height-relaxed;
  max-width: 220px;
}

.avatar-actions {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: $space-2;
}
</style>

<style lang="scss">
.character-dialog {
  .el-form-item__label {
    color: $color-text-secondary !important;
  }

  .avatar-preview {
    background: $color-bg-secondary;
  }
}
</style>
