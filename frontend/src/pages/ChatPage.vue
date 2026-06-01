<template>
  <div class="chat-page">
    <main class="chat-main" :style="chatBackgroundStyle">
      <header v-if="activeCharacter" class="chat-header glass-strong">
        <el-button text :icon="ArrowLeft" @click="goBack">返回</el-button>
        <div class="chat-header-center">
          <div class="header-avatar">
            <img v-if="activeCharacter.avatarUrl" :src="resolveMediaUrl(activeCharacter.avatarUrl)" />
            <el-icon v-else :size="20"><User /></el-icon>
          </div>
          <h2 class="header-name">{{ activeCharacter.name }}</h2>
        </div>
      </header>

      <div v-if="!currentConvId" class="chat-empty">
        <div class="empty-icon">
          <el-icon :size="40"><ChatDotRound /></el-icon>
        </div>
        <p>请从角色页选择角色开始聊天</p>
        <el-button type="primary" class="btn-cta" @click="goBack">前往角色</el-button>
      </div>

      <template v-else>
        <div v-if="isBlocked" class="blocked-banner">
          当前角色已被拉黑，无法继续发送消息。请前往聊天详情调整设置。
        </div>
        <div class="message-list" ref="msgListRef">
          <div
            v-for="msg in messages"
            v-show="msg.role !== 'assistant' || msg.content"
            :key="msg.id || msg._tempId"
            class="message-row"
            :class="msg.role === 'user' ? 'msg-user' : 'msg-assistant'"
          >
            <div v-if="msg.role === 'assistant' && msg.content" class="msg-avatar msg-avatar-other">
              <img v-if="activeCharacter?.avatarUrl" :src="resolveMediaUrl(activeCharacter.avatarUrl)" />
              <el-icon v-else :size="14"><User /></el-icon>
            </div>
            <div class="msg-bubble" :class="{
              'bubble-user': msg.role === 'user',
              'bubble-assistant': msg.role === 'assistant'
            }">
              <div v-if="msg.imageUrl" class="msg-image-wrap">
                <img :src="resolveMediaUrl(msg.imageUrl)" alt="" class="msg-image" />
              </div>
              <div v-if="msg.content" class="msg-content" v-text="msg.content"></div>
              <div class="msg-meta">
                <span class="msg-time">{{ formatTime(msg.createdAt) }}</span>
                <span v-if="msg.tokens" class="msg-tokens">{{ msg.tokens }} tokens</span>
              </div>
            </div>
            <div v-if="msg.role === 'user'" class="msg-avatar msg-avatar-user">
              <img v-if="userStore.avatarUrl" :src="resolveMediaUrl(userStore.avatarUrl)" alt="" />
              <el-icon v-else :size="14"><User /></el-icon>
            </div>
          </div>

          <div ref="scrollAnchor"></div>
        </div>

        <div class="message-input-area">
          <div v-if="pendingImageUrl" class="image-preview-row">
            <div class="image-preview">
              <img :src="resolveMediaUrl(pendingImageUrl)" alt="" />
              <el-button
                class="image-preview-remove"
                circle
                size="small"
                :icon="Close"
                @click="clearPendingImage"
              />
            </div>
          </div>
          <div class="input-row">
            <input
              ref="fileInputRef"
              type="file"
              accept="image/jpeg,image/png,image/webp,image/gif"
              class="hidden-file-input"
              @change="handleImageSelect"
            />
            <el-button
              :icon="Picture"
              :disabled="waitingReply || isBlocked || uploadingImage"
              @click="triggerImageSelect"
            />
            <el-input
              v-model="inputText"
              type="textarea"
              :rows="1"
              :autosize="{ minRows: 1, maxRows: 4 }"
              :placeholder="t('chat.placeholder')"
              @keydown.enter.exact.prevent="handleSend"
              :disabled="waitingReply || isBlocked"
            />
            <el-button
              type="primary"
              :icon="Promotion"
              :disabled="(!inputText.trim() && !pendingImageUrl) || waitingReply || isBlocked || uploadingImage"
              @click="handleSend"
            />
          </div>
          <div class="input-toolbar">
            <el-select v-model="currentProvider" size="small" placeholder="Provider" style="width:160px">
              <el-option
                :label="PLATFORM_PROVIDER_LABEL"
                :value="PLATFORM_PROVIDER"
              />
              <el-option
                v-for="v in providersStore.vaults"
                :key="v.provider"
                :label="v.provider"
                :value="v.provider"
              />
            </el-select>
            <el-select
              v-model="currentModel"
              size="small"
              placeholder="Model"
              style="width:200px"
              :allow-create="!isPlatformSelected"
              filterable
              :disabled="isPlatformSelected"
            >
              <el-option
                v-for="m in availableModels"
                :key="m.id || m"
                :label="m.name || m.id || m"
                :value="m.id || m"
              />
            </el-select>
          </div>
        </div>
      </template>
    </main>
  </div>
</template>

<script setup>
import { ref, computed, watch, nextTick, onMounted, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'

const { t } = useI18n()
import { useProvidersStore } from '@/stores/providers'
import { useNotificationsStore } from '@/stores/notifications'
import { useUserStore } from '@/stores/user'
import { useSettingsStore } from '@/stores/settings'
import { listCharacters } from '@/api/character'
import { getConversation, getMessages, sendMessageStream, uploadChatImage } from '@/api/conversation'
import { fetchModels } from '@/api/ai'
import { ArrowLeft, User, ChatDotRound, Promotion, Picture, Close } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { resolveMediaUrl } from '@/utils/media'
import { PLATFORM_PROVIDER, PLATFORM_MODEL, PLATFORM_PROVIDER_LABEL } from '@/constants/ai'
import { normalizeHex } from '@/utils/themeColor'

const route = useRoute()
const router = useRouter()
const providersStore = useProvidersStore()
const notificationsStore = useNotificationsStore()
const userStore = useUserStore()
const settingsStore = useSettingsStore()

const characters = ref([])
const messages = ref([])
const currentConvId = ref(null)
const activeCharacter = ref(null)
const msgListRef = ref(null)
const scrollAnchor = ref(null)
const fileInputRef = ref(null)

const inputText = ref('')
const pendingImageUrl = ref('')
const uploadingImage = ref(false)
const waitingReply = ref(false)
const currentProvider = ref('')
const currentModel = ref('')

const availableModels = ref([])
let conversationPollTimer = null

const isPlatformSelected = computed(() => currentProvider.value === PLATFORM_PROVIDER)
const activeSettings = computed(() => activeCharacter.value?.settings || {})
const isBlocked = computed(() => activeSettings.value.blocked === true)

function clampPercentage(value, fallback = 50) {
  const n = Number(value)
  if (!Number.isFinite(n)) return fallback
  return Math.max(0, Math.min(100, Math.round(n)))
}

const chatBackgroundStyle = computed(() => {
  const useGlobal = activeSettings.value.useGlobalChatBackground !== false
  const imageUrl = !useGlobal
    ? resolveMediaUrl(activeSettings.value.chatBackgroundImageUrl || '')
    : ''
  const posX = clampPercentage(activeSettings.value.chatBackgroundPosX, 50)
  const posY = clampPercentage(activeSettings.value.chatBackgroundPosY, 50)
  if (imageUrl) {
    return {
      backgroundImage: `linear-gradient(180deg, rgba(var(--ly-bg-surface-rgb), 0.48) 0%, rgba(var(--ly-bg-surface-rgb), 0.78) 100%), url("${imageUrl}")`,
      backgroundSize: 'cover',
      backgroundPosition: `${posX}% ${posY}%`,
      backgroundRepeat: 'no-repeat'
    }
  }
  const backgroundKey = useGlobal
    ? settingsStore.getChatBackground(activeCharacter.value?.id)
    : activeSettings.value.chatBackgroundKey || settingsStore.getChatBackground(activeCharacter.value?.id)
  const normalized = normalizeHex(backgroundKey)
  return normalized
    ? {
        background: `linear-gradient(180deg, ${normalized}22 0%, rgba(var(--ly-bg-surface-rgb), 0.72) 100%)`
      }
    : {}
})

onMounted(async () => {
  await providersStore.fetchVaults()
  if (userStore.isLoggedIn) {
    await userStore.fetchProfile().catch(() => {})
  }
  characters.value = (await listCharacters().catch(() => [])) || []

  currentProvider.value = PLATFORM_PROVIDER
  currentModel.value = PLATFORM_MODEL
  loadModels(PLATFORM_PROVIDER)

  const convId = route.params.id
  if (convId) {
    await loadConversation(Number(convId))
  }
  startConversationPolling()
})

watch(() => route.params.id, async (id) => {
  if (id) {
    await loadConversation(Number(id))
  }
})

onUnmounted(() => {
  stopConversationPolling()
})

watch(currentProvider, (p) => {
  if (!p) return
  if (p === PLATFORM_PROVIDER) {
    currentModel.value = PLATFORM_MODEL
    loadModels(p)
    return
  }
  const vault = providersStore.vaults.find(v => v.provider === p)
  currentModel.value = vault?.modelDefault || ''
  loadModels(p)
})

watch(currentConvId, () => {
  if (currentConvId.value) {
    pollCurrentConversationMessages(true)
  }
})

async function loadModels(provider) {
  try {
    const list = await fetchModels(provider)
    availableModels.value = list || []
  } catch { availableModels.value = [] }
}

function goBack() {
  router.push('/characters')
}

async function loadAllMessages(convId) {
  const all = []
  let beforeSeq = null
  let hasMore = true
  while (hasMore) {
    const page = await getMessages(convId, { limit: 50, beforeSeq })
    const batch = page?.records || []
    all.unshift(...batch)
    hasMore = !!page?.hasMore
    beforeSeq = page?.nextBeforeSeq ?? null
    if (!hasMore || beforeSeq == null) {
      break
    }
  }
  return all
}

async function loadConversation(convId) {
  currentConvId.value = convId
  notificationsStore.markConversationRead(convId)
  try {
    const conv = await getConversation(convId)
    await resolveActiveCharacter(conv.characterId, conv)
    const serverMessages = await loadAllMessages(convId)
    messages.value = serverMessages.map(normalizeMessageRole)
  } catch {
    messages.value = []
    activeCharacter.value = null
  }
  await nextTick()
  scrollToBottom()
}

async function resolveActiveCharacter(charId, conv) {
  if (!charId) {
    activeCharacter.value = null
    return
  }
  let char = characters.value.find(c => c.id === charId)
  if (!char) {
    try {
      const { getCharacter } = await import('@/api/character')
      char = await getCharacter(charId)
    } catch {
      char = null
    }
  }
  if (!char && conv) {
    char = {
      id: charId,
      name: conv.characterName || conv.title || '角色',
      avatarUrl: conv.characterAvatarUrl
    }
  }
  activeCharacter.value = char
  const backgroundKey = char?.settings?.chatBackgroundKey
  if (char?.id && backgroundKey) {
    settingsStore.setChatBackground(char.id, backgroundKey)
  }
}

function startConversationPolling() {
  stopConversationPolling()
  conversationPollTimer = setInterval(() => {
    pollCurrentConversationMessages(false)
  }, 10000)
}

function stopConversationPolling() {
  if (conversationPollTimer) {
    clearInterval(conversationPollTimer)
    conversationPollTimer = null
  }
}

async function pollCurrentConversationMessages(force) {
  const convId = currentConvId.value
  if (!convId || waitingReply.value) {
    return
  }
  try {
    const page = await getMessages(convId, { limit: 50 })
    const serverMessages = page?.records || []
    const normalized = serverMessages.map(normalizeMessageRole)
    const changed =
      force ||
      normalized.length !== messages.value.length ||
      normalized.at(-1)?.id !== messages.value.at(-1)?.id
    if (changed) {
      messages.value = normalized
      notificationsStore.markConversationRead(convId)
      await nextTick()
      scrollToBottom()
    }
  } catch {
    // ignore polling errors
  }
}

async function handleSend() {
  const text = inputText.value.trim()
  const imageUrl = pendingImageUrl.value
  if ((!text && !imageUrl) || waitingReply.value || isBlocked.value) return

  if (!currentProvider.value) {
    currentProvider.value = PLATFORM_PROVIDER
  }
  if (currentProvider.value !== PLATFORM_PROVIDER && !currentModel.value) {
    ElMessage.warning('使用自定义 AI 配置时必须选择或填写模型名称')
    return
  }

  inputText.value = ''
  pendingImageUrl.value = ''

  const userMsg = {
    _tempId: 'u' + Date.now(),
    role: 'user',
    content: text || (imageUrl ? '（用户发送了一张图片）' : ''),
    imageUrl: imageUrl || undefined,
    createdAt: new Date().toISOString()
  }
  messages.value.push(userMsg)
  await nextTick()
  scrollToBottom()

  waitingReply.value = true

  try {
    const sendConvId = currentConvId.value
    const response = await sendMessageStream(sendConvId, {
      provider: currentProvider.value,
      model: currentProvider.value === PLATFORM_PROVIDER ? undefined : (currentModel.value || undefined),
      content: text || undefined,
      imageUrl: imageUrl || undefined
    })

    if (!response.ok) {
      let errMsg = '消息发送失败'
      try {
        const errBody = await response.json()
        errMsg = errBody.message || errBody.msg || errMsg
      } catch { /* ignore */ }
      throw new Error(errMsg)
    }

    await drainAssistantStream(response)

    if (currentConvId.value === sendConvId) {
      await pollCurrentConversationMessages(true)
    }
  } catch (err) {
    ElMessage.error(err.message || '消息发送失败')
    messages.value = messages.value.filter(m => m._tempId !== userMsg._tempId)
  } finally {
    waitingReply.value = false
    await nextTick()
    scrollToBottom()
  }
}

/** 静默消费 SSE，完整回复落库后再由 poll 拉取展示 */
async function drainAssistantStream(response) {
  const reader = response.body?.getReader()
  if (!reader) {
    throw new Error('无法读取回复流')
  }
  const decoder = new TextDecoder()
  let buffer = ''

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const parts = buffer.split('\n')
    buffer = parts.pop() || ''

    for (const line of parts) {
      const trimmed = line.trim()
      if (!trimmed.startsWith('data:')) continue
      const data = trimmed.slice(5).trim()
      if (!data || data === '[DONE]') continue
      try {
        const payload = JSON.parse(data)
        if (payload.error) {
          throw new Error(payload.error)
        }
      } catch (e) {
        if (e instanceof SyntaxError) continue
        throw e
      }
    }
  }
}

function triggerImageSelect() {
  fileInputRef.value?.click()
}

function clearPendingImage() {
  pendingImageUrl.value = ''
  if (fileInputRef.value) {
    fileInputRef.value.value = ''
  }
}

async function handleImageSelect(event) {
  const file = event.target.files?.[0]
  if (!file) return
  if (file.size > 5 * 1024 * 1024) {
    ElMessage.warning(t('chat.imageTooLarge'))
    event.target.value = ''
    return
  }
  uploadingImage.value = true
  try {
    const result = await uploadChatImage(file)
    pendingImageUrl.value = result?.imageUrl || ''
    if (!pendingImageUrl.value) {
      throw new Error(t('chat.imageUploadFailed'))
    }
  } catch (err) {
    ElMessage.error(err.message || t('chat.imageUploadFailed'))
    clearPendingImage()
  } finally {
    uploadingImage.value = false
    if (fileInputRef.value) {
      fileInputRef.value.value = ''
    }
  }
}

function scrollToBottom() {
  scrollAnchor.value?.scrollIntoView({ behavior: 'smooth' })
}

function normalizeMessageRole(msg) {
  if (!msg) return msg
  const role = String(msg.role || '').toLowerCase()
  return {
    ...msg,
    role: role === 'assistant' ? 'assistant' : role === 'user' ? 'user' : role
  }
}

function formatTime(ts) {
  if (!ts) return ''
  const d = new Date(ts)
  return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}
</script>

<style lang="scss" scoped>
.chat-page {
  display: flex;
  flex-direction: column;
  height: calc(100vh - #{$header-height} - #{$space-6} * 2);
  margin: -$space-6;
  margin-left: -$space-8;
  margin-right: -$space-8;
  max-width: 900px;
}

.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  background: rgba($color-bg-primary, 0.3);
  border-radius: $radius-lg;
  overflow: hidden;
  border: 1px solid rgba($color-pink-rgb, 0.06);
}

.chat-header {
  display: flex;
  align-items: center;
  gap: $space-3;
  padding: $space-3 $space-4;
  border-bottom: 1px solid rgba($color-pink-rgb, 0.06);
  flex-shrink: 0;
}

.chat-header-center {
  display: flex;
  align-items: center;
  gap: $space-3;
  min-width: 0;
}

.header-avatar {
  width: 40px;
  height: 40px;
  border-radius: $radius-md;
  overflow: hidden;
  background: rgba($color-pink-rgb, 0.08);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  color: $color-pink-primary;

  img { width: 100%; height: 100%; object-fit: cover; }
}

.header-name {
  font-size: $font-size-lg;
  font-weight: $font-weight-semibold;
  color: $color-text-primary;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.chat-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: $color-text-muted;

  .empty-icon {
    width: 80px; height: 80px;
    border-radius: $radius-xl;
    background: rgba($color-pink-rgb, 0.06);
    display: flex;
    align-items: center;
    justify-content: center;
    margin-bottom: $space-6;
    color: $color-pink-primary;
  }

  h3 { color: $color-text-primary; margin-bottom: $space-2; }
  p { font-size: $font-size-sm; }
}

.blocked-banner {
  margin: $space-4 $space-6 0;
  padding: $space-3 $space-4;
  border-radius: $radius-md;
  background: rgba($color-error, 0.12);
  border: 1px solid rgba($color-error, 0.18);
  color: $color-text-primary;
  font-size: $font-size-sm;
}

.message-list {
  flex: 1;
  overflow-y: auto;
  padding: $space-4 $space-6;
  display: flex;
  flex-direction: column;
  gap: $space-3;
}

.message-row {
  display: flex;
  gap: $space-2;
  max-width: 80%;
}

.msg-user {
  align-self: flex-end;
  flex-direction: row;
  justify-content: flex-end;
}

.msg-assistant { align-self: flex-start; }

.msg-avatar {
  width: 30px; height: 30px;
  border-radius: 50%;
  overflow: hidden;
  background: rgba($color-pink-rgb, 0.1);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  color: $color-pink-primary;
  align-self: flex-end;
  margin-bottom: 2px;

  img { width: 100%; height: 100%; object-fit: cover; }
}

.msg-avatar-user {
  margin-left: $space-2;
  margin-right: 0;
}

.msg-avatar-other {
  margin-right: $space-2;
  margin-left: 0;
}

.msg-bubble {
  padding: $space-3 $space-4;
  border-radius: $radius-lg;

  &.bubble-user {
    background: $color-pink-primary;
    color: $color-text-inverse;
    border-bottom-right-radius: $radius-sm;
  }

  &.bubble-assistant {
    background: rgba($color-bg-surface, 0.9);
    border: 1px solid rgba($color-pink-rgb, 0.12);
    border-bottom-left-radius: $radius-sm;
  }
}

.msg-content {
  font-size: $font-size-sm;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;

  .bubble-user & { color: $color-text-inverse; }
  .bubble-assistant & { color: $color-text-primary; }
}

.msg-image-wrap {
  margin-bottom: $space-2;
}

.msg-image {
  display: block;
  max-width: 220px;
  max-height: 220px;
  border-radius: $radius-md;
  object-fit: cover;
}

.msg-meta {
  display: flex;
  gap: $space-3;
  margin-top: $space-1;
}

.msg-time, .msg-tokens {
  font-size: $font-size-xs;
  opacity: 0.6;

  .bubble-user & { color: rgba($color-text-inverse, 0.7); }
  .bubble-assistant & { color: $color-text-muted; }
}

// --- Message Input ---
.message-input-area {
  padding: $space-3 $space-4;
  border-top: 1px solid rgba($color-pink-rgb, 0.06);
  background: rgba($color-bg-secondary, 0.4);
}

.input-row {
  display: flex;
  gap: $space-2;
  align-items: flex-end;
}

.hidden-file-input {
  display: none;
}

.image-preview-row {
  margin-bottom: $space-2;
}

.image-preview {
  position: relative;
  display: inline-block;

  img {
    display: block;
    width: 72px;
    height: 72px;
    object-fit: cover;
    border-radius: $radius-md;
    border: 1px solid rgba($color-pink-rgb, 0.15);
  }
}

.image-preview-remove {
  position: absolute;
  top: -8px;
  right: -8px;
}

.input-toolbar {
  display: flex;
  gap: $space-2;
  margin-top: $space-2;
  align-items: center;
}

.no-provider-hint {
  display: flex;
  align-items: center;
  gap: $space-1;
  font-size: $font-size-xs;
  color: $color-warning;
  white-space: nowrap;

  a {
    color: $color-pink-primary;
    text-decoration: underline;
    &:hover { color: $color-pink-light; }
  }
}

</style>
