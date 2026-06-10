<template>
  <div class="chat-page gal-chat" :class="{ 'gal-chat--compact': isCompact }">
    <div v-if="currentConvId" class="gal-scene">
      <div class="gal-bg-wrap">
        <div ref="galBgRef" class="gal-bg" :style="galBgStyle">
          <img
            v-if="customBackgroundUrl"
            :src="customBackgroundUrl"
            class="gal-bg__img"
            :alt="activeCharacter?.name"
          />
          <div v-else class="gal-bg__ambient" />
        </div>
        <div class="gal-bg-vignette" />
        <div class="gal-bg-floor" />
      </div>

      <header v-if="activeCharacter && !isCompact" class="gal-header">
        <button type="button" class="gal-header__back" @click="goBack">
          <el-icon :size="18"><ArrowLeft /></el-icon>
        </button>
        <div class="gal-header__meta">
          <h2 class="gal-header__name" :class="{ 'is-typing': awaitingOpening }">{{ headerTitle }}</h2>
          <EmotionBadge
            v-if="emotionState"
            :current-emotion="emotionState.currentEmotion"
            :emotion-intensity="emotionState.emotionIntensity"
            :status-text="emotionState.statusText"
          />
        </div>
        <button
          v-if="activeCharacter?.id"
          type="button"
          class="gal-header__settings"
          :title="t('characters.settings')"
          @click="openCharacterSettings"
        >
          <el-icon :size="18"><Setting /></el-icon>
        </button>
      </header>

      <div v-if="isCompact && activeCharacter?.id" class="gal-compact-toolbar">
        <button
          type="button"
          class="gal-compact-toolbar__settings"
          :title="t('characters.settings')"
          @click="openCharacterSettings"
        >
          <el-icon :size="16"><Setting /></el-icon>
          <span>{{ t('characters.settings') }}</span>
        </button>
      </div>

      <div v-if="isBlocked" class="blocked-banner">
        当前角色已被拉黑，无法继续发送消息。请前往角色设置调整。
      </div>

      <div class="gal-log" ref="msgListRef">
        <button
          v-if="isUserScrolledUp"
          type="button"
          class="gal-scroll-bottom"
          :title="t('chat.scrollToBottom')"
          @click="jumpToBottom"
        >
          <el-icon :size="18"><ArrowDown /></el-icon>
        </button>
        <template v-for="item in messageTimeline" :key="item._key">
          <div v-if="item.type === 'time'" class="msg-time-divider">
            <span>{{ item.label }}</span>
          </div>
          <div
            v-else
            class="gal-log__item"
            :class="item.role === 'user' ? 'gal-log__item--user' : 'gal-log__item--hero'"
          >
          <div
            v-if="item.role !== 'user'"
            class="gal-log__avatar gal-log__avatar--hero"
            aria-hidden="true"
          >
            <img
              v-if="characterAvatarUrl"
              :src="resolveMediaUrl(characterAvatarUrl)"
              :alt="activeCharacter?.name"
            />
            <el-icon v-else :size="18"><User /></el-icon>
          </div>

          <div v-if="item.role === 'user'" class="gal-user-choice">
            <div v-if="item.imageUrl" class="gal-user-choice__image">
              <img :src="resolveMediaUrl(item.imageUrl)" alt="" />
            </div>
            <p v-if="item.content" class="gal-user-choice__text">{{ item.content }}</p>
            <span class="gal-user-choice__time">{{ formatTime(item.createdAt) }}</span>
          </div>

          <div v-else class="gal-dialogue">
            <div class="gal-dialogue__nameplate">
              <span class="gal-dialogue__name">{{ activeCharacter?.name }}</span>
              <span v-if="!item._streamGroupId" class="gal-dialogue__time">{{ formatTime(item.createdAt) }}</span>
            </div>
            <div v-if="item.imageUrl" class="gal-dialogue__image">
              <img :src="resolveMediaUrl(item.imageUrl)" alt="" />
            </div>
            <p v-if="item.content" class="gal-dialogue__text">{{ item.content }}</p>
          </div>

          <div
            v-if="item.role === 'user'"
            class="gal-log__avatar gal-log__avatar--user"
            aria-hidden="true"
          >
            <img
              v-if="userStore.avatarUrl"
              :src="resolveMediaUrl(userStore.avatarUrl)"
              alt=""
            />
            <el-icon v-else :size="18"><UserFilled /></el-icon>
          </div>
          </div>
        </template>
        <div ref="scrollAnchor" />
      </div>

      <div class="gal-input glass-strong">
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
            ref="inputTextRef"
            type="textarea"
            :rows="1"
            :autosize="{ minRows: 1, maxRows: 3 }"
            :placeholder="t('chat.placeholder')"
            @keydown.enter.exact.prevent="handleSend"
            :disabled="isBlocked"
          />
          <el-button
            type="primary"
            :icon="Promotion"
            :disabled="(!inputText.trim() && !pendingImageUrl) || waitingReply || isBlocked || uploadingImage"
            @click="handleSend"
          />
        </div>
        <div v-if="!isCompact" class="input-toolbar">
          <el-select v-model="currentProvider" size="small" placeholder="Provider" class="toolbar-select">
            <el-option :label="PLATFORM_PROVIDER_LABEL" :value="PLATFORM_PROVIDER" />
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
            class="toolbar-select toolbar-select--wide"
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
    </div>

    <div v-else class="chat-empty-scene">
      <div class="empty-icon">
        <el-icon :size="40"><ChatDotRound /></el-icon>
      </div>
      <p>请从角色页选择角色开始聊天</p>
      <el-button type="primary" class="btn-cta" @click="goBack">前往角色</el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, nextTick, onMounted, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import gsap from 'gsap'

const { t, locale } = useI18n()
import { useProvidersStore } from '@/stores/providers'
import { useNotificationsStore } from '@/stores/notifications'
import { useUserStore } from '@/stores/user'
import { useSettingsStore } from '@/stores/settings'
import { useCharactersStore } from '@/stores/characters'
import { humanizeError } from '@/utils/errorMessage'
import { getConversation, getMessages, sendMessageStream, uploadChatImage } from '@/api/conversation'
import { fetchModels } from '@/api/ai'
import { ArrowLeft, ArrowDown, ChatDotRound, Promotion, Picture, Close, User, UserFilled, Setting } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { resolveMediaUrl } from '@/utils/media'
import { PLATFORM_PROVIDER, PLATFORM_MODEL, PLATFORM_PROVIDER_LABEL } from '@/constants/ai'
import { normalizeHex } from '@/utils/themeColor'
import EmotionBadge from '@/components/EmotionBadge.vue'
import { getCharacterState } from '@/api/characterState'
import { setActiveChatConversationId, setActiveChatRefreshHandler } from '@/composables/useActiveChatContext'
import { splitAssistantReply, resolveMaxRepliesPerTurn } from '@/utils/assistantReplySplit'
import { getElectronAPI } from '@/utils/electron'
import { useChatScroll, sleep, MIN_REPLY_DISPLAY_MS } from '@/composables/useChatScroll'
import { dateLocaleForUi } from '@/utils/dateLocale'
import { stripInnerThoughts, resolveShowInnerThoughts } from '@/utils/innerThoughtFilter'

const TIME_GAP_MS = 5 * 60 * 1000

const route = useRoute()
const router = useRouter()
const providersStore = useProvidersStore()
const notificationsStore = useNotificationsStore()
const userStore = useUserStore()
const settingsStore = useSettingsStore()

const charactersStore = useCharactersStore()
const messages = ref([])
const currentConvId = ref(null)
const activeCharacter = ref(null)
const emotionState = ref(null)
const msgListRef = ref(null)
const scrollAnchor = ref(null)
const { isUserScrolledUp, scrollToBottom, jumpToBottom } = useChatScroll(msgListRef, scrollAnchor)
const fileInputRef = ref(null)
const galBgRef = ref(null)

    const inputText = ref('')
    const inputTextRef = ref(null)
    const pendingImageUrl = ref('')
const uploadingImage = ref(false)
const waitingReply = ref(false)
const awaitingOpening = ref(false)
const currentProvider = ref('')
const currentModel = ref('')
let restoringProviderPref = false

const availableModels = ref([])
let conversationPollTimer = null

/** 按角色隔离的 API Provider 选择记忆 */
const PER_CHAR_PROVIDER_KEY = 'lianyu-char-provider'
function loadCharProviderPref(charId) {
  if (!charId) return null
  try {
    const raw = localStorage.getItem(PER_CHAR_PROVIDER_KEY)
    if (!raw) return null
    const map = JSON.parse(raw)
    return map[charId] || null
  } catch { return null }
}
function saveCharProviderPref(charId, provider, model) {
  if (!charId) return
  try {
    const raw = localStorage.getItem(PER_CHAR_PROVIDER_KEY)
    const map = raw ? JSON.parse(raw) : {}
    map[charId] = { provider, model }
    localStorage.setItem(PER_CHAR_PROVIDER_KEY, JSON.stringify(map))
  } catch { /* ignore */ }
}

/** 根据 activeCharacter 恢复该角色的 API Provider 选择记忆 */
function restoreCharProviderPref() {
  const charId = activeCharacter.value?.id
  if (!charId) {
    currentProvider.value = PLATFORM_PROVIDER
    currentModel.value = PLATFORM_MODEL
    loadModels(PLATFORM_PROVIDER)
    return
  }
  const saved = loadCharProviderPref(charId)
  restoringProviderPref = true
  if (saved && saved.provider) {
    currentProvider.value = saved.provider
    currentModel.value = saved.model || ''
    if (saved.provider !== PLATFORM_PROVIDER) {
      loadModels(saved.provider)
    } else {
      currentModel.value = PLATFORM_MODEL
      loadModels(PLATFORM_PROVIDER)
    }
  } else {
    currentProvider.value = PLATFORM_PROVIDER
    currentModel.value = PLATFORM_MODEL
    loadModels(PLATFORM_PROVIDER)
  }
  // nextTick 后重置 flag，此时 watcher 中的 model 覆盖已执行完毕
  nextTick(() => { restoringProviderPref = false })
}
let fastPollTimer = null
let fastPollStartedAt = 0
const FAST_POLL_MS = 2000
const FAST_POLL_MAX_MS = 90000
const NORMAL_POLL_MS = 10000
let assistantLineCount = 0
let skipBounceOnce = false
let bounceTween = null

const isPlatformSelected = computed(() => currentProvider.value === PLATFORM_PROVIDER)
const isCompact = computed(() => route.meta.compact === true)
const activeSettings = computed(() => activeCharacter.value?.settings || {})
const isBlocked = computed(() => activeSettings.value.blocked === true)
const showInnerThoughts = computed(() => resolveShowInnerThoughts(activeSettings.value))

const headerTitle = computed(() => {
  const name = activeCharacter.value?.name
  if (!name) return ''
  if (awaitingOpening.value) {
    return t('chat.typing', { name })
  }
  return name
})

const characterAvatarUrl = computed(() => activeCharacter.value?.avatarUrl || '')

const messageTimeline = computed(() => {
  const items = []
  let prevMs = null
  for (const msg of messages.value) {
    const displayContent = msg.role === 'assistant'
      ? stripInnerThoughts(msg.content, showInnerThoughts.value)
      : msg.content
    if (msg.role === 'assistant' && !displayContent) continue
    const ms = parseMessageTime(msg)
    if (prevMs != null && ms - prevMs > TIME_GAP_MS) {
      items.push({
        type: 'time',
        _key: `time-${ms}`,
        label: formatTimeDivider(ms)
      })
    }
    items.push({
      type: 'message',
      ...msg,
      content: displayContent,
      _key: msg.id || msg._tempId
    })
    prevMs = ms
  }
  return items
})

function clampPercentage(value, fallback = 50) {
  const n = Number(value)
  if (!Number.isFinite(n)) return fallback
  return Math.max(0, Math.min(100, Math.round(n)))
}

const customBackgroundUrl = computed(() => {
  const useGlobal = activeSettings.value.useGlobalChatBackground !== false
  if (useGlobal) return ''
  return resolveMediaUrl(activeSettings.value.chatBackgroundImageUrl || '')
})

const galBgStyle = computed(() => {
  if (customBackgroundUrl.value) {
    const posX = clampPercentage(activeSettings.value.chatBackgroundPosX, 50)
    const posY = clampPercentage(activeSettings.value.chatBackgroundPosY, 50)
    return { '--gal-bg-pos': `${posX}% ${posY}%` }
  }
  const useGlobal = activeSettings.value.useGlobalChatBackground !== false
  const backgroundKey = useGlobal
    ? settingsStore.getChatBackground(activeCharacter.value?.id)
    : activeSettings.value.chatBackgroundKey || settingsStore.getChatBackground(activeCharacter.value?.id)
  const normalized = normalizeHex(backgroundKey)
  if (normalized) {
    return {
      '--gal-accent': normalized,
      '--gal-accent-soft': `${normalized}2e`
    }
  }
  return {}
})

watch(
  () => messages.value.filter(m => m.role === 'assistant' && m.content).length,
  (count) => {
    if (skipBounceOnce) {
      assistantLineCount = count
      skipBounceOnce = false
      return
    }
    if (count > assistantLineCount) {
      bounceCharacterBg()
    }
    assistantLineCount = count
  }
)

function bounceCharacterBg() {
  const el = galBgRef.value
  if (!el) return
  bounceTween?.kill()
  bounceTween = gsap.fromTo(
    el,
    { scale: 1.018, y: -4 },
    { scale: 1, y: 0, duration: 0.55, ease: 'elastic.out(1, 0.75)' }
  )
}

onMounted(async () => {
  await providersStore.fetchVaults()
  if (userStore.isLoggedIn) {
    await userStore.fetchProfile().catch(() => {})
  }
  await charactersStore.fetchList().catch(() => [])

  const convId = route.params.id
  if (convId) {
    skipBounceOnce = true
    await loadConversation(Number(convId))
  }

  // 恢复该角色上次选择的 API Provider（按角色隔离，新角色默认走平台）
  restoreCharProviderPref()
  startConversationPolling()
  setActiveChatRefreshHandler(refreshActiveChatMessages)
})

watch(() => route.params.id, async (id) => {
  if (id) {
    skipBounceOnce = true
    await loadConversation(Number(id))
    // 切换到不同角色时恢复该角色的 API 选择记忆
    restoreCharProviderPref()
  }
})

onUnmounted(() => {
  setActiveChatConversationId(null)
  setActiveChatRefreshHandler(null)
  stopConversationPolling()
  bounceTween?.kill()
})

watch(currentProvider, (p) => {
  if (!p) return
  if (p === PLATFORM_PROVIDER) {
    if (!restoringProviderPref) currentModel.value = PLATFORM_MODEL
    loadModels(p)
    return
  }
  const vault = providersStore.vaults.find(v => v.provider === p)
  if (!restoringProviderPref) {
    currentModel.value = vault?.modelDefault || ''
  }
  loadModels(p)
})

watch(currentModel, (m) => {
  const charId = activeCharacter.value?.id
  if (charId && currentProvider.value) {
    saveCharProviderPref(charId, currentProvider.value, m || '')
  }
})

watch(currentConvId, (id) => {
  setActiveChatConversationId(id)
  if (id) {
    pollCurrentConversationMessages(true)
  }
}, { immediate: true })

async function loadModels(provider) {
  try {
    const list = await fetchModels(provider)
    availableModels.value = list || []
  } catch { availableModels.value = [] }
}

function goBack() {
  if (isCompact.value) {
    getElectronAPI()?.closeQuickChat?.()
    return
  }
  router.push('/app/characters')
}

async function loadEmotionState() {
  if (!activeCharacter.value?.id) return
  try {
    emotionState.value = await getCharacterState(activeCharacter.value.id, { silent: true })
  } catch {
    emotionState.value = null
  }
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
    awaitingOpening.value = false
    stopFastPolling()
  }
  await nextTick()
  scrollToBottom({ force: true })
  syncAwaitingOpening()
  if (awaitingOpening.value) {
    startFastPolling()
    scheduleOpeningPollBurst()
  } else {
    stopFastPolling()
  }
}

async function resolveActiveCharacter(charId, conv) {
  if (!charId) {
    activeCharacter.value = null
    return
  }
  let char = charactersStore.list.find(c => c.id === charId)
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
  loadEmotionState()
}

function refreshActiveChatMessages() {
  void pollCurrentConversationMessages(true)
}

function syncAwaitingOpening() {
  awaitingOpening.value =
    !!currentConvId.value &&
    !waitingReply.value &&
    !messages.value.some(m => m.role === 'assistant' && m.content)
}

function scheduleOpeningPollBurst() {
  window.setTimeout(() => pollCurrentConversationMessages(true), 600)
  window.setTimeout(() => pollCurrentConversationMessages(true), 1500)
  window.setTimeout(() => pollCurrentConversationMessages(true), 3500)
}

function startFastPolling() {
  stopFastPolling()
  fastPollStartedAt = Date.now()
  fastPollTimer = window.setInterval(() => {
    if (Date.now() - fastPollStartedAt > FAST_POLL_MAX_MS) {
      stopFastPolling()
      syncAwaitingOpening()
      return
    }
    if (messages.value.some(m => m.role === 'assistant' && m.content) || waitingReply.value) {
      stopFastPolling()
      syncAwaitingOpening()
      return
    }
    pollCurrentConversationMessages(false)
  }, FAST_POLL_MS)
}

function stopFastPolling() {
  if (fastPollTimer) {
    clearInterval(fastPollTimer)
    fastPollTimer = null
  }
}

function startConversationPolling() {
  stopConversationPolling()
  conversationPollTimer = window.setInterval(() => {
    pollCurrentConversationMessages(false)
  }, NORMAL_POLL_MS)
}

function stopConversationPolling() {
  stopFastPolling()
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
    const page = await getMessages(convId, { limit: 50 }, { silent: true })
    const serverMessages = page?.records || []
    const normalized = serverMessages.map(normalizeMessageRole)
    const changed =
      force ||
      normalized.length !== messages.value.length ||
      normalized.at(-1)?.id !== messages.value.at(-1)?.id
    if (changed) {
      skipBounceOnce = true
      // 保留本地 push 后服务端尚未持久化的用户消息，防止竞态条件导致消息"闪现"消失又出现
      const localPending = messages.value.filter(m => m._tempId && m._tempId.startsWith('u'))
      const stillPending = localPending.filter(m => {
        // 通过 content+createdAt 近似匹配判断服务端是否已持久化该消息
        return !normalized.some(s =>
          s.role === 'user' && s.content === m.content && Math.abs(new Date(s.createdAt).getTime() - new Date(m.createdAt).getTime()) < 5000
        )
      })
      if (stillPending.length > 0) {
        // 将尚未持久化的本地消息插入到服务端列表末尾之前（保持时间顺序，倒序插入保持正确位置）
        const merged = [...normalized]
        const insertAfter = merged.findLastIndex(m => m.role === 'user')
        const pos = insertAfter >= 0 ? insertAfter + 1 : merged.length
        merged.splice(pos, 0, ...stillPending)
        messages.value = merged
      } else {
        messages.value = normalized
      }
      notificationsStore.markConversationRead(convId)
      syncAwaitingOpening()
      if (!awaitingOpening.value) {
        stopFastPolling()
      }
      await nextTick()
      scrollToBottom()
    }
  } catch {
    // ignore polling errors
  }
}

function focusChatInput() {
  if (isBlocked.value) return
  const el = inputTextRef.value
  if (!el) return
  if (typeof el.focus === 'function') {
    el.focus()
    return
  }
  const ta = el.$el?.querySelector?.('textarea')
    || el.$el?.getElementsByTagName?.('textarea')?.[0]
  ta?.focus()
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

  // 先 push 用户消息到列表，再清空输入框，避免视觉空档
  const userMsg = {
    _tempId: 'u' + Date.now(),
    role: 'user',
    content: text || (imageUrl ? '（用户发送了一张图片）' : ''),
    imageUrl: imageUrl || undefined,
    createdAt: new Date().toISOString()
  }
  messages.value.push(userMsg)

  inputText.value = ''
  pendingImageUrl.value = ''
  await nextTick()
  focusChatInput()
  scrollToBottom({ force: true })

  waitingReply.value = true
  const streamGroupId = 'stream-' + Date.now()
  const streamCreatedAt = new Date().toISOString()
  const sendStartedAt = Date.now()

  try {
    const sendConvId = currentConvId.value
    const response = await sendMessageStream(sendConvId, {
      provider: currentProvider.value,
      model: currentProvider.value === PLATFORM_PROVIDER ? undefined : (currentModel.value || undefined),
      content: text || undefined,
      imageUrl: imageUrl || undefined
    })

    if (!response.ok) {
      let errMsg = '消息发送失败，请稍后再试'
      try {
        const errBody = await response.json()
        errMsg = humanizeError(errBody.message || errBody.msg, errMsg)
      } catch { /* ignore */ }
      throw new Error(errMsg)
    }

    const fullContent = await drainAssistantStream(response)
    const elapsed = Date.now() - sendStartedAt
    if (elapsed < MIN_REPLY_DISPLAY_MS) {
      await sleep(MIN_REPLY_DISPLAY_MS - elapsed)
    }

    if (currentConvId.value === sendConvId && fullContent?.trim()) {
      syncStreamingAssistantBubbles(fullContent, streamGroupId, streamCreatedAt)
      await nextTick()
      scrollToBottom()
      skipBounceOnce = true
      await pollCurrentConversationMessages(true)
    }
  } catch (err) {
    ElMessage.error(humanizeError(err, '消息发送失败，请稍后再试'))
    messages.value = messages.value.filter(
      m => m._tempId !== userMsg._tempId && m._streamGroupId !== streamGroupId
    )
  } finally {
    waitingReply.value = false
    await nextTick()
    focusChatInput()
    scrollToBottom()
    loadEmotionState()
  }
}

async function drainAssistantStream(response) {
  const reader = response.body?.getReader()
  if (!reader) {
    throw new Error('无法读取回复流')
  }
  const decoder = new TextDecoder()
  let buffer = ''
  let fullContent = ''

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
        if (payload.content) {
          fullContent += payload.content
        }
      } catch (e) {
        if (e instanceof SyntaxError) continue
        throw e
      }
    }
  }
  return fullContent
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
    ElMessage.error(humanizeError(err, t('chat.imageUploadFailed')))
    clearPendingImage()
  } finally {
    uploadingImage.value = false
    if (fileInputRef.value) {
      fileInputRef.value.value = ''
    }
  }
}

function syncStreamingAssistantBubbles(fullContent, streamGroupId, createdAt) {
  const maxReplies = resolveMaxRepliesPerTurn(activeCharacter.value)
  const pieces = splitAssistantReply(fullContent, maxReplies)
  const rest = messages.value.filter(m => m._streamGroupId !== streamGroupId)
  if (pieces.length === 0) {
    messages.value = rest
    return
  }
  const streamMsgs = pieces
    .map((content, i) => ({
      _tempId: `${streamGroupId}-${i}`,
      _streamGroupId: streamGroupId,
      role: 'assistant',
      content: stripInnerThoughts(content, showInnerThoughts.value),
      createdAt
    }))
    .filter(m => m.content)
  messages.value = [...rest, ...streamMsgs]
}

function openCharacterSettings() {
  const charId = activeCharacter.value?.id
  if (!charId) return
  router.push(`/app/characters/${charId}/detail`)
}

function normalizeMessageRole(msg) {
  if (!msg) return msg
  const role = String(msg.role || '').toLowerCase()
  return {
    ...msg,
    role: role === 'assistant' ? 'assistant' : role === 'user' ? 'user' : role
  }
}

function parseMessageTime(msg) {
  const ts = msg?.createdAt || msg?._time
  if (!ts) return Date.now()
  const ms = new Date(ts).getTime()
  return Number.isNaN(ms) ? Date.now() : ms
}

function formatTimeDivider(ms) {
  const d = new Date(ms)
  const loc = dateLocaleForUi(locale.value)
  return d.toLocaleTimeString(loc, { hour: '2-digit', minute: '2-digit', hour12: false })
}

function formatTime(ts) {
  if (!ts) return ''
  const d = new Date(ts)
  return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}
</script>

<style lang="scss" scoped>
.chat-page {
  height: 100%;
  min-height: 0;
  flex: 1;
  max-width: none;
  overflow: hidden;
}

.gal-scene {
  position: relative;
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
  background: #0a0a12;
}

.gal-bg-wrap {
  position: absolute;
  inset: 0;
  overflow: hidden;
  pointer-events: none;
}

.gal-bg {
  position: absolute;
  inset: -2%;
  transform-origin: center center;
  will-change: transform;

  &__img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    object-position: var(--gal-bg-pos, center center);
    filter: saturate(1.02);
  }

  &__ambient {
    width: 100%;
    height: 100%;
    background:
      radial-gradient(ellipse 85% 55% at 50% 18%, var(--gal-accent-soft, rgba($color-pink-rgb, 0.14)), transparent 62%),
      radial-gradient(ellipse 50% 35% at 82% 72%, rgba($color-pink-rgb, 0.07), transparent 58%),
      radial-gradient(ellipse 40% 30% at 12% 65%, rgba(120, 140, 200, 0.06), transparent 55%),
      linear-gradient(180deg, #14141f 0%, #0a0a12 52%, #07070e 100%);
  }
}

.gal-bg-vignette {
  position: absolute;
  inset: 0;
  background:
    radial-gradient(ellipse 90% 70% at 50% 22%, transparent 0%, rgba(8, 8, 14, 0.35) 55%, rgba(8, 8, 14, 0.82) 100%),
    linear-gradient(180deg, rgba(8, 8, 14, 0.15) 0%, rgba(8, 8, 14, 0.55) 52%, rgba(8, 8, 14, 0.92) 100%);
}

.gal-bg-floor {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  height: 42%;
  background: linear-gradient(180deg, transparent, rgba(8, 8, 14, 0.75));
}

.gal-header {
  position: sticky;
  top: 0;
  z-index: 5;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: $space-3;
  padding: calc(#{$space-3} + env(safe-area-inset-top, 0px)) $space-4 $space-3;
  background: linear-gradient(180deg, rgba(8, 8, 14, 0.72), transparent);
}

.gal-header__back {
  width: 36px;
  height: 36px;
  border-radius: $radius-full;
  display: flex;
  align-items: center;
  justify-content: center;
  color: $color-text-primary;
  background: rgba(255, 255, 255, 0.08);
  border: 1px solid rgba(255, 255, 255, 0.08);
  flex-shrink: 0;
}

.gal-header__meta {
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.gal-header__settings {
  width: 36px;
  height: 36px;
  border-radius: $radius-full;
  display: flex;
  align-items: center;
  justify-content: center;
  color: $color-text-primary;
  background: rgba(255, 255, 255, 0.08);
  border: 1px solid rgba(255, 255, 255, 0.08);
  flex-shrink: 0;
  cursor: pointer;
}

.gal-header__name {
  margin: 0;
  font-size: $font-size-lg;
  font-weight: $font-weight-semibold;
  color: $color-text-primary;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  text-shadow: 0 1px 12px rgba(0, 0, 0, 0.45);

  &.is-typing {
    font-size: $font-size-sm;
    font-weight: $font-weight-medium;
    color: $color-text-muted;
  }
}

.blocked-banner {
  position: relative;
  z-index: 3;
  margin: 0 $space-4;
  padding: $space-3 $space-4;
  border-radius: $radius-md;
  background: rgba($color-error, 0.16);
  border: 1px solid rgba($color-error, 0.22);
  color: $color-text-primary;
  font-size: $font-size-sm;
}

.gal-log {
  position: relative;
  z-index: 2;
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: $space-2 $space-4 $space-4;
  display: flex;
  flex-direction: column;
  gap: $space-4;
  /* 仅顶部淡入；底部不做 mask，避免滚到最新消息时被渐变遮暗 */
  mask-image: linear-gradient(180deg, transparent 0%, #000 8%, #000 100%);
  -webkit-mask-image: linear-gradient(180deg, transparent 0%, #000 8%, #000 100%);
}

.msg-time-divider {
  align-self: center;
  text-align: center;
  margin: $space-1 0 $space-2;
  flex-shrink: 0;

  span {
    display: inline-block;
    font-size: 11px;
    color: $color-text-muted;
    padding: 3px 12px;
    border-radius: $radius-full;
    background: rgba($color-bg-surface, 0.75);
    border: 1px solid rgba($color-pink-rgb, 0.08);
  }
}

.gal-scroll-bottom {
  position: sticky;
  bottom: $space-3;
  align-self: center;
  z-index: 5;
  width: 40px;
  height: 40px;
  border-radius: 50%;
  border: 1px solid rgba(255, 255, 255, 0.2);
  background: rgba(0, 0, 0, 0.45);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  backdrop-filter: blur(8px);
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.2);
  transition: transform 0.2s ease, opacity 0.2s ease;

  &:hover {
    transform: translateY(-2px);
    opacity: 0.95;
  }
}

.gal-log__item {
  display: flex;
  align-items: flex-end;
  gap: $space-3;
  width: 100%;
}

.gal-log__item--user {
  flex-direction: row;
}

.gal-log__item--hero {
  flex-direction: row;
}

.gal-log__avatar {
  width: 40px;
  height: 40px;
  flex-shrink: 0;
  border-radius: 50%;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 2px;
  background: rgba($color-pink-rgb, 0.1);
  border: 1px solid rgba($color-pink-rgb, 0.2);
  color: $color-pink-primary;

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  &--user {
    background: rgba($color-pink-rgb, 0.16);
  }
}

.gal-user-choice {
  flex: 1;
  min-width: 0;
  padding: $space-3 $space-5;
  border-radius: $radius-lg;
  background: linear-gradient(
    180deg,
    rgba($color-pink-rgb, 0.14) 0%,
    rgba($color-pink-rgb, 0.08) 100%
  );
  border: 1px solid rgba($color-pink-rgb, 0.24);
  backdrop-filter: blur(10px);
  box-shadow: 0 6px 24px rgba(0, 0, 0, 0.18);

  &__text {
    margin: 0;
    font-size: $font-size-base;
    line-height: $line-height-relaxed;
    color: $color-text-primary;
    white-space: pre-wrap;
    word-break: break-word;
  }

  &__image img {
    display: block;
    max-width: min(100%, 220px);
    max-height: 220px;
    border-radius: $radius-md;
    margin-bottom: $space-3;
  }

  &__time {
    display: block;
    margin-top: $space-2;
    font-size: 10px;
    color: rgba(255, 255, 255, 0.45);
    text-align: right;
  }
}

.gal-dialogue {
  flex: 1;
  min-width: 0;
  padding: $space-4 $space-5 $space-5;
  border-radius: $radius-lg;
  background: linear-gradient(
    180deg,
    rgba(12, 12, 20, 0.72) 0%,
    rgba(12, 12, 20, 0.88) 100%
  );
  border: 1px solid rgba($color-pink-rgb, 0.22);
  box-shadow:
    0 8px 32px rgba(0, 0, 0, 0.35),
    inset 0 1px 0 rgba(255, 255, 255, 0.06);
  backdrop-filter: blur(12px);
  animation: galLineIn 0.38s cubic-bezier(0.22, 1, 0.36, 1) both;

  &__nameplate {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: $space-3;
    margin-bottom: $space-3;
    padding-bottom: $space-2;
    border-bottom: 1px solid rgba($color-pink-rgb, 0.15);
  }

  &__name {
    font-size: $font-size-sm;
    font-weight: $font-weight-semibold;
    letter-spacing: 0.08em;
    color: $color-pink-light;
  }

  &__time {
    font-size: 10px;
    color: rgba(255, 255, 255, 0.38);
  }

  &__text {
    margin: 0;
    font-size: $font-size-base;
    line-height: $line-height-relaxed;
    color: rgba(255, 255, 255, 0.92);
    white-space: pre-wrap;
    word-break: break-word;
  }

  &__image img {
    display: block;
    max-width: 100%;
    max-height: 220px;
    border-radius: $radius-md;
    margin-bottom: $space-3;
    object-fit: cover;
  }
}

@keyframes galLineIn {
  from {
    opacity: 0;
    transform: translateY(12px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.gal-input {
  position: relative;
  z-index: 3;
  flex-shrink: 0;
  padding: $space-3 $space-4 calc(#{$space-3} + env(safe-area-inset-bottom, 0px));
  border-top: 1px solid rgba($color-pink-rgb, 0.12);
  background: rgba(10, 10, 16, 0.82) !important;
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
  flex-wrap: wrap;
}

.toolbar-select {
  width: min(160px, 45vw);
}

.toolbar-select--wide {
  width: min(200px, 55vw);
}

.chat-empty-scene {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: $color-text-muted;

  .empty-icon {
    width: 80px;
    height: 80px;
    border-radius: $radius-xl;
    background: rgba($color-pink-rgb, 0.06);
    display: flex;
    align-items: center;
    justify-content: center;
    margin-bottom: $space-6;
    color: $color-pink-primary;
  }

  p {
    font-size: $font-size-sm;
    margin-bottom: $space-4;
  }
}

@media (prefers-reduced-motion: reduce) {
  .gal-bg {
    transition: none !important;
  }

  .gal-dialogue {
    animation: none;
  }
}

.gal-compact-toolbar {
  position: relative;
  z-index: 3;
  display: flex;
  justify-content: flex-end;
  padding: 6px 10px 0;
}

.gal-compact-toolbar__settings {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  border-radius: $radius-full;
  border: 1px solid rgba(255, 255, 255, 0.1);
  background: rgba(0, 0, 0, 0.35);
  color: $color-text-muted;
  font-size: 12px;
  cursor: pointer;
}

.gal-chat--compact {
  .gal-scene {
    min-height: 100%;
  }

  .gal-log {
    padding: 8px 10px 12px;
    mask-image: none;
    -webkit-mask-image: none;
  }

  .gal-bg-vignette {
    background:
      radial-gradient(ellipse 90% 70% at 50% 22%, transparent 0%, rgba(8, 8, 14, 0.25) 55%, rgba(8, 8, 14, 0.5) 100%),
      linear-gradient(180deg, rgba(8, 8, 14, 0.1) 0%, rgba(8, 8, 14, 0.35) 100%);
  }

  .gal-bg-floor {
    height: 28%;
    background: linear-gradient(180deg, transparent, rgba(8, 8, 14, 0.45));
  }

  .gal-input {
    padding: 8px 10px 10px;
  }

  .gal-user-choice,
  .gal-dialogue {
    max-width: 100%;
  }
}
</style>
