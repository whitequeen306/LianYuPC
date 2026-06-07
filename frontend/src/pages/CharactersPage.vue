<template>
  <div class="characters-page companion-page stagger-container">
    <header class="page-header">
      <div>
        <h1 class="page-title">我的羁绊</h1>
        <p class="page-desc">点开头像继续对话，或创建属于你的角色</p>
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

    <div v-else class="characters-layout" @mouseleave="hoveredCharacterId = null">
      <div class="characters-main">
        <div class="character-grid">
          <div
            v-for="(char, idx) in characters"
            :key="char.id"
            class="character-card glass stagger-item"
            :class="{
              'has-unread': unreadCountForCharacter(char.id) > 0,
              'is-active': hoveredCharacterId === char.id
            }"
            :style="{ animationDelay: `${idx * 0.05}s` }"
            role="button"
            tabindex="0"
            @click="startChat(char)"
            @keydown.enter="startChat(char)"
            @mouseenter="setHoveredCharacter(char.id)"
            @focusin="setHoveredCharacter(char.id)"
            @focusout="onCardFocusOut(char.id, $event)"
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
      </div>

      <aside class="characters-spotlight" aria-live="polite">
        <div v-if="spotlightCharacter" class="character-spotlight glass">
          <div class="character-spotlight__glow" aria-hidden="true" />
          <div class="character-spotlight__portrait">
            <img
              v-if="spotlightCharacter.avatarUrl"
              :src="resolveMediaUrl(spotlightCharacter.avatarUrl)"
              :alt="spotlightCharacter.name"
              class="character-spotlight__img"
            />
            <div v-else class="character-spotlight__fallback">
              <el-icon :size="48"><User /></el-icon>
            </div>
            <div class="character-spotlight__vignette" aria-hidden="true" />
          </div>
          <div class="character-spotlight__body">
            <span class="character-spotlight__eyebrow">{{ t('characters.lastLineEyebrow') }}</span>
            <h3 class="character-spotlight__name">{{ spotlightCharacter.name }}</h3>
            <blockquote class="character-spotlight__quote">
              <p>{{ spotlightLastLine }}</p>
            </blockquote>
            <el-button type="primary" class="character-spotlight__cta" @click="startChat(spotlightCharacter)">
              {{ t('characters.continueChat') }}
            </el-button>
          </div>
        </div>

        <div v-else class="character-spotlight character-spotlight--hint glass">
          <div class="character-spotlight__hint-icon">
            <el-icon :size="28"><ChatDotRound /></el-icon>
          </div>
          <p>{{ t('characters.hoverHint') }}</p>
        </div>
      </aside>
    </div>

    <!-- Create Dialog -->
    <el-dialog
      v-model="dialogVisible"
      class="character-dialog"
      title="创建角色"
      :width="dialogWidth"
      destroy-on-close
      align-center
      top="5vh"
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

        <el-form-item label="你的所在城市" prop="city">
          <el-input v-model="form.city" placeholder="如 上海、北京、广州" />
          <div class="field-hint">
            用于精确计算当地时间与天气，角色主动问候和天气相关对话会参考此城市。
          </div>
        </el-form-item>

        <el-form-item>
          <el-checkbox v-model="form.useFictionalCity">
            角色有自己的虚构城市（如动漫中的城市）
          </el-checkbox>
        </el-form-item>
        <el-form-item v-if="form.useFictionalCity" label="角色虚构城市" prop="fictionalCity">
          <el-input v-model="form.fictionalCity" placeholder="如 天宫市、冬木市、学园都市" />
          <div class="field-hint">
            角色对话中提到城市时，会优先使用此虚构城市；否则使用你的所在城市。
          </div>
        </el-form-item>

        <el-form-item label="动漫角色参考（可选）">
          <div class="generate-row">
            <el-input
              v-model="generatorDescription"
              placeholder="例如：动漫《约会大作战》的时崎狂三"
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
import { ref, reactive, onMounted, watch, computed } from 'vue'
import { storeToRefs } from 'pinia'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { uploadAvatar, generateCharacter } from '@/api/character'
import { createConversation } from '@/api/conversation'
import { useCharactersStore } from '@/stores/characters'
import { useCharacterSquareStore } from '@/stores/characterSquare'
import { useConversationsStore } from '@/stores/conversations'
import { listNotifications } from '@/api/notification'
import { useConversationUnread } from '@/composables/useConversationUnread'
import { useResponsiveDialogWidth } from '@/composables/useResponsiveDialogWidth'
import { useNotificationsStore } from '@/stores/notifications'
import { Plus, Delete, User, Loading, ChatDotRound, UploadFilled } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { resolveMediaUrl } from '@/utils/media'
import { fixUtf8Mojibake } from '@/utils/textEncoding'
import { listCharacterStates } from '@/api/characterState'
import { getSavedUserCity, saveUserCity } from '@/utils/userCity'

const { t } = useI18n()
const router = useRouter()
const notificationsStore = useNotificationsStore()
const charactersStore = useCharactersStore()
const characterSquareStore = useCharacterSquareStore()
const conversationsStore = useConversationsStore()
const { list: characters, loading: storeLoading } = storeToRefs(charactersStore)
const dialogWidth = useResponsiveDialogWidth(560)
const loading = computed({
  get: () => storeLoading.value,
  set: (v) => { storeLoading.value = v }
})
const {
  ingestConversations,
  ingestUnreadNotifications,
  refreshUnreadFromApi,
  unreadCountForCharacter,
  formatBadgeCount
} = useConversationUnread()

/** characterId -> { id, lastMessage, lastCharacterMessage } for SINGLE conversations */
const singleConvByCharacterId = ref({})
const emotionByCharacterId = ref({})
const hoveredCharacterId = ref(null)
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
  city: getSavedUserCity(),
  promptTemplate: '',
  avatarUrl: '',
  age: '',
  gender: '',
  speakingStyle: '',
  useFictionalCity: false,
  fictionalCity: ''
})

const form = reactive(initialForm())

const formRules = {
  name: [{ required: true, message: '请输入角色名称', trigger: 'blur' }],
  city: [{ required: true, message: '请填写你的所在城市', trigger: 'blur' }],
  promptTemplate: [{ required: true, message: '请输入性格设定', trigger: 'blur' }]
}

const spotlightCharacter = computed(() => {
  if (!hoveredCharacterId.value) return null
  return characters.value.find(c => c.id === hoveredCharacterId.value) || null
})

const spotlightLastLine = computed(() => {
  if (!hoveredCharacterId.value) return ''
  return lastCharacterLineForCharacter(hoveredCharacterId.value)
})

onMounted(async () => {
  void notificationsStore.init()
  await fetchCharacters()
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
      const convList = await conversationsStore.fetchList()
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
    const entry = {
      id: c.id,
      lastMessage: c.lastMessage || '',
      lastCharacterMessage: c.lastCharacterMessage || ''
    }
    if (!prev) {
      map[c.characterId] = entry
      continue
    }
    if (entry.lastMessage && !prev.lastMessage) {
      prev.lastMessage = entry.lastMessage
    }
    if (entry.lastCharacterMessage && !prev.lastCharacterMessage) {
      prev.lastCharacterMessage = entry.lastCharacterMessage
    }
  }
  return map
}

function buildEmotionMap(states) {
  const map = {}
  for (const state of states || []) {
    if (state?.characterId) {
      map[state.characterId] = state
    }
  }
  return map
}

async function loadCharacterStates() {
  try {
    const states = await listCharacterStates({ silent: true })
    emotionByCharacterId.value = buildEmotionMap(Array.isArray(states) ? states : [])
  } catch {
    emotionByCharacterId.value = {}
  }
}

async function fetchCharacters() {
  loading.value = true
  try {
    const [, convList, unreadList] = await Promise.all([
      charactersStore.fetchList({ force: true }),
      conversationsStore.fetchList({ force: true, silent: true }).catch(() => []),
      listNotifications({ unreadOnly: true, limit: 200 }, { silent: true }).catch(() => [])
    ])
    ingestConversations(convList || [])
    ingestUnreadNotifications(unreadList || [])
    singleConvByCharacterId.value = buildSingleConvMap(convList)
    await loadCharacterStates()
  } catch {} finally {
    loading.value = false
  }
}

function lastMessageForCharacter(characterId) {
  const preview = singleConvByCharacterId.value[characterId]?.lastMessage?.trim()
  return preview || t('characters.noMessagesYet')
}

function lastCharacterLineForCharacter(characterId) {
  const line = singleConvByCharacterId.value[characterId]?.lastCharacterMessage?.trim()
  if (line) return line
  const status = emotionByCharacterId.value[characterId]?.statusText?.trim()
  if (status) return status
  return t('characters.noCharacterLineYet')
}

function setHoveredCharacter(characterId) {
  hoveredCharacterId.value = characterId
}

function onCardFocusOut(characterId, event) {
  const next = event.relatedTarget
  if (next && event.currentTarget?.contains(next)) return
  if (next && event.currentTarget?.closest('.characters-layout')?.contains(next)) return
  if (hoveredCharacterId.value === characterId) {
    hoveredCharacterId.value = null
  }
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
    if (form.city?.trim()) {
      settings.city = form.city.trim()
      saveUserCity(settings.city)
    }
    if (form.useFictionalCity && form.fictionalCity?.trim()) {
      settings.fictional_city = form.fictionalCity.trim()
    }
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
    const data = {
      name: form.name,
      promptTemplate: form.promptTemplate,
      settings
    }
    let char = await charactersStore.create(data)

    if (avatarFile.value && char) {
      char = await uploadAvatar(char.id, avatarFile.value)
      charactersStore.upsertLocal(char)
    }

    ElMessage.success('角色已创建')
    dialogVisible.value = false
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
    form.gender = fixUtf8Mojibake(draft.gender && draft.gender !== '未知' ? draft.gender : form.gender)
    form.speakingStyle = fixUtf8Mojibake(draft.speakingStyle || form.speakingStyle)
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
    await charactersStore.remove(char.id)
    conversationsStore.invalidate()
    characterSquareStore.invalidateAll()
    ElMessage.success('角色已删除')
  } catch {}
}

function getCharMetaFields(char) {
  const s = char.settings || {}
  const fields = []
  if (s.city) fields.push({ key: 'city', label: fixUtf8Mojibake(s.city) })
  if (s.age) fields.push({ key: 'age', label: `${fixUtf8Mojibake(String(s.age))}岁` })
  if (s.gender) fields.push({ key: 'gender', label: fixUtf8Mojibake(s.gender) })
  if (s.speakingStyle) fields.push({ key: 'speakingStyle', label: fixUtf8Mojibake(s.speakingStyle) })
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
.characters-page {
  width: 100%;
}

.characters-layout {
  display: grid;
  gap: $space-6;

  @media (min-width: 1024px) {
    grid-template-columns: minmax(0, 1fr) minmax(280px, 340px);
    gap: $space-8;
    align-items: start;
  }
}

.characters-main {
  min-width: 0;
}

.characters-spotlight {
  min-width: 0;

  @media (min-width: 1024px) {
    position: sticky;
    top: $space-4;
  }
}

.character-spotlight {
  position: relative;
  overflow: hidden;
  border-radius: $radius-xl;
  transition: opacity $transition-base, transform $transition-base;

  &__glow {
    position: absolute;
    inset: 0;
    background: radial-gradient(
      ellipse 80% 55% at 50% 16%,
      rgba($color-pink-rgb, 0.16),
      transparent 70%
    );
    pointer-events: none;
  }

  &__portrait {
    position: relative;
    height: clamp(260px, 38vh, 360px);
    overflow: hidden;
  }

  &__img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    object-position: center 12%;
    filter: saturate(1.05);
  }

  &__fallback {
    width: 100%;
    height: 100%;
    display: flex;
    align-items: center;
    justify-content: center;
    background: linear-gradient(180deg, rgba($color-pink-rgb, 0.12), rgba(10, 10, 16, 0.92));
    color: $color-pink-primary;
  }

  &__vignette {
    position: absolute;
    inset: 0;
    background: linear-gradient(
      180deg,
      transparent 38%,
      rgba(8, 8, 14, 0.55) 72%,
      rgba(8, 8, 14, 0.96) 100%
    );
    pointer-events: none;
  }

  &__body {
    position: relative;
    margin-top: -$space-10;
    padding: 0 $space-5 $space-6;
    z-index: 1;
  }

  &__eyebrow {
    display: block;
    font-size: $font-size-xs;
    letter-spacing: 0.16em;
    text-transform: uppercase;
    color: $color-pink-primary;
    margin-bottom: $space-2;
  }

  &__name {
    margin: 0 0 $space-4;
    font-size: $font-size-xl;
    font-weight: $font-weight-medium;
    color: $color-text-primary;
  }

  &__quote {
    margin: 0 0 $space-5;
    padding: $space-4 $space-4 $space-4 $space-5;
    border-left: 2px solid rgba($color-pink-rgb, 0.42);
    border-radius: 0 $radius-md $radius-md 0;
    background: rgba(8, 8, 14, 0.42);
    backdrop-filter: blur(8px);

    p {
      margin: 0;
      font-size: $font-size-sm;
      line-height: $line-height-relaxed;
      color: rgba(255, 255, 255, 0.9);
      font-style: italic;
      display: -webkit-box;
      -webkit-line-clamp: 6;
      -webkit-box-orient: vertical;
      overflow: hidden;
      white-space: pre-wrap;
      word-break: break-word;
    }
  }

  &__cta {
    width: 100%;
    border-radius: $radius-pill;
  }

  &--hint {
    padding: $space-10 $space-6;
    text-align: center;
    min-height: 280px;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: $space-4;

    p {
      margin: 0;
      max-width: 22ch;
      font-size: $font-size-sm;
      line-height: $line-height-relaxed;
      color: $color-text-muted;
    }
  }

  &__hint-icon {
    width: 56px;
    height: 56px;
    border-radius: $radius-full;
    display: flex;
    align-items: center;
    justify-content: center;
    color: $color-pink-primary;
    background: rgba($color-pink-rgb, 0.1);
    border: 1px solid rgba($color-pink-rgb, 0.14);
  }
}

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

  &.is-active {
    border-color: rgba($color-pink-rgb, 0.28);
    box-shadow: $shadow-glow-pink;
    transform: translateY(-1px);
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

@media (max-width: 480px) {
  .form-row-3 {
    grid-template-columns: 1fr;
  }
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
  .el-dialog {
    max-height: 90vh;
    display: flex;
    flex-direction: column;
    overflow: hidden;
  }
  .el-dialog__body {
    flex: 1;
    overflow-y: auto;
    max-height: calc(90vh - 96px);
    padding: var(--el-dialog-body-padding);
  }
  .el-form-item__label {
    color: $color-text-secondary !important;
  }

  .avatar-preview {
    background: $color-bg-secondary;
  }
}
</style>
