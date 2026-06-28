<template>
  <div class="quick-chat">
    <header class="quick-chat__head">
      <div class="quick-chat__meta">
        <span class="quick-chat__avatar">
          <img v-if="characterAvatar" :src="characterAvatar" :alt="characterName" />
          <el-icon v-else :size="16"><User /></el-icon>
        </span>
        <span class="quick-chat__name">{{ headerLabel }}</span>
      </div>
      <div class="quick-chat__drag" aria-hidden="true" />
      <button
        type="button"
        class="quick-chat__close"
        title="关闭"
        @mousedown.stop
        @click.stop="closeWindow"
      >
        ×
      </button>
    </header>

    <div v-if="loading" class="quick-chat__state">
      <el-icon class="is-loading" :size="22"><Loading /></el-icon>
    </div>

    <div v-else ref="msgListRef" class="quick-chat__log">
      <template v-for="item in messageTimeline" :key="item._key">
        <div v-if="item.type === 'time'" class="quick-chat__time-divider">
          <span>{{ item.label }}</span>
        </div>
        <div
          v-else
          class="quick-chat__row"
          :class="item.role === 'user' ? 'quick-chat__row--user' : 'quick-chat__row--hero'"
        >
          <div class="quick-chat__bubble">
            <AssistantMessageContent
              v-if="item.role === 'assistant' && item.content"
              :content="item.content"
              :show-inner-thoughts="showInnerThoughts"
              variant="chat"
              tag="p"
              extra-class="quick-chat__text"
            />
            <p v-else-if="item.content" class="quick-chat__text">{{ item.content }}</p>
          </div>
        </div>
      </template>
      <div ref="scrollAnchor" class="quick-chat__anchor" />
    </div>

    <footer class="quick-chat__foot">
      <el-input
        ref="inputRef"
        v-model="inputText"
        type="textarea"
        :rows="2"
        resize="none"
        placeholder="输入消息…"
        :disabled="waitingReply || loading"
        @keydown.enter.exact.prevent="handleSend"
      />
      <el-button
        type="primary"
        :icon="Promotion"
        :loading="waitingReply"
        :disabled="!inputText.trim() || loading"
        @click="handleSend"
      />
    </footer>
  </div>
</template>

<script setup>
import { ref, computed, watch, nextTick, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { User, Loading, Promotion } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useCharactersStore } from '@/stores/characters'
import { useNotificationsStore } from '@/stores/notifications'
import { getConversation, getMessages, sendMessageStream } from '@/api/conversation'
import { PLATFORM_PROVIDER } from '@/constants/ai'
import { humanizeError } from '@/utils/errorMessage'
import { resolveMediaUrl } from '@/utils/media'
import { getElectronAPI } from '@/utils/electron'
import { useChatScroll, sleep, MIN_REPLY_DISPLAY_MS } from '@/composables/useChatScroll'
import AssistantMessageContent from '@/components/AssistantMessageContent.vue'
import { stripInnerThoughts, resolveShowInnerThoughts } from '@/utils/innerThoughtFilter'
import { formatSmartTime } from '@/utils/feedTime'

const TIME_GAP_MS = 5 * 60 * 1000

const route = useRoute()
const { t, locale } = useI18n()
const charactersStore = useCharactersStore()
const notificationsStore = useNotificationsStore()

const loading = ref(true)
const waitingReply = ref(false)
const inputText = ref('')
const messages = ref([])
const activeCharacter = ref(null)
const currentConvId = ref(null)
const msgListRef = ref(null)
const scrollAnchor = ref(null)
const inputRef = ref(null)

const { scrollToBottom } = useChatScroll(msgListRef, scrollAnchor)

const characterName = computed(() => activeCharacter.value?.name || '聊天')
const characterAvatar = computed(() => {
  const url = activeCharacter.value?.avatarUrl
  return url ? resolveMediaUrl(url) : ''
})
const showInnerThoughts = computed(() =>
  resolveShowInnerThoughts(activeCharacter.value?.settings || {}),
)
const headerLabel = computed(() => {
  if (waitingReply.value) return `${characterName.value} 正在回复中…`
  return characterName.value
})

function parseMessageTime(msg) {
  const ts = msg?.createdAt || msg?._time
  if (!ts) return Date.now()
  const ms = new Date(ts).getTime()
  return Number.isNaN(ms) ? Date.now() : ms
}

function formatTimeDivider(ms) {
  return formatSmartTime(ms, { t, locale: locale.value })
}

const messageTimeline = computed(() => {
  const items = []
  let prevMs = null
  for (const msg of messages.value) {
    const role = String(msg.role || '').toLowerCase() === 'user' ? 'user' : 'assistant'
    const visibleContent = role === 'assistant'
      ? stripInnerThoughts(msg.content, showInnerThoughts.value)
      : msg.content
    if (!visibleContent?.trim()) continue
    const ms = parseMessageTime(msg)
    if (prevMs != null && ms - prevMs > TIME_GAP_MS) {
      items.push({
        type: 'time',
        _key: `time-${ms}`,
        label: formatTimeDivider(ms),
      })
    }
    items.push({
      type: 'message',
      role,
      content: msg.content,
      _key: msg.id || msg._tempId || `${role}-${ms}`,
    })
    prevMs = ms
  }
  return items
})

let pollTimer = null

function closeWindow() {
  const api = getElectronAPI()
  void api?.closeQuickChat?.()
  window.close()
}

function normalizeMessage(msg) {
  const role = String(msg?.role || '').toLowerCase()
  return {
    ...msg,
    role: role === 'user' ? 'user' : 'assistant',
  }
}

async function resolveCharacter(charId, conv) {
  if (!charId) {
    activeCharacter.value = null
    return
  }
  let char = charactersStore.list.find((c) => c.id === charId)
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
      avatarUrl: conv.characterAvatarUrl,
    }
  }
  activeCharacter.value = char
}

async function loadMessages(convId) {
  const page = await getMessages(convId, { limit: 50 })
  messages.value = (page?.records || []).map(normalizeMessage)
}

async function loadConversation(convId) {
  loading.value = true
  currentConvId.value = convId
  notificationsStore.markConversationRead(convId)
  try {
    await charactersStore.fetchList().catch(() => [])
    const conv = await getConversation(convId)
    await resolveCharacter(conv.characterId, conv)
    await loadMessages(convId)
  } catch (err) {
    messages.value = []
    activeCharacter.value = null
    ElMessage.error(humanizeError(err, '无法加载会话'))
  } finally {
    loading.value = false
    await nextTick()
    scrollToBottom({ force: true })
    getElectronAPI()?.notifyQuickChatReady?.()
  }
}

async function drainAssistantStream(response) {
  const reader = response.body?.getReader()
  if (!reader) throw new Error('无法读取回复流')
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
        if (payload.error) throw new Error(payload.error)
        if (payload.content) fullContent += payload.content
        if (payload.replace) fullContent = payload.replace
      } catch (e) {
        if (e instanceof SyntaxError) continue
        throw e
      }
    }
  }
  return fullContent
}

async function handleSend() {
  const text = inputText.value.trim()
  const convId = currentConvId.value
  if (!text || !convId || waitingReply.value) return

  const userMsg = {
    _tempId: `u-${Date.now()}`,
    role: 'user',
    content: text,
    createdAt: new Date().toISOString(),
  }
  messages.value.push(userMsg)
  inputText.value = ''
  waitingReply.value = true
  await nextTick()
  scrollToBottom({ force: true })

  const startedAt = Date.now()
  try {
    const response = await sendMessageStream(convId, {
      provider: PLATFORM_PROVIDER,
      content: text,
    })
    if (!response.ok) {
      let errMsg = '消息发送失败'
      try {
        const body = await response.json()
        errMsg = humanizeError(body.message || body.msg, errMsg)
      } catch { /* ignore */ }
      throw new Error(errMsg)
    }
    const fullContent = await drainAssistantStream(response)
    const elapsed = Date.now() - startedAt
    if (elapsed < MIN_REPLY_DISPLAY_MS) {
      await sleep(MIN_REPLY_DISPLAY_MS - elapsed)
    }
    if (fullContent?.trim()) {
      messages.value.push({
        _tempId: `a-${Date.now()}`,
        role: 'assistant',
        content: fullContent,
        createdAt: new Date().toISOString(),
      })
    }
    await loadMessages(convId)
  } catch (err) {
    messages.value = messages.value.filter((m) => m._tempId !== userMsg._tempId)
    ElMessage.error(humanizeError(err, '消息发送失败，请稍后再试'))
  } finally {
    waitingReply.value = false
    await nextTick()
    scrollToBottom({ force: true })
  }
}

function startPolling() {
  stopPolling()
  pollTimer = window.setInterval(async () => {
    const convId = currentConvId.value
    if (!convId || waitingReply.value) return
    try {
      await loadMessages(convId)
    } catch { /* ignore */ }
  }, 10000)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

watch(
  () => route.params.id,
  (id) => {
    if (id) void loadConversation(Number(id))
  },
)

onMounted(async () => {
  const convId = route.params.id
  if (convId) await loadConversation(Number(convId))
  startPolling()
})

onUnmounted(() => {
  stopPolling()
})
</script>

<style lang="scss" scoped>
.quick-chat {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  background: var(--ly-bg-primary, #121820);
  color: var(--ly-text-primary, #f5f5f7);
}

.quick-chat__head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--ly-border-subtle, rgba(255, 255, 255, 0.08));
  background: var(--ly-bg-secondary, #171e28);
  flex-shrink: 0;
}

.quick-chat__meta {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  flex-shrink: 0;
}

.quick-chat__drag {
  flex: 1;
  align-self: stretch;
  min-width: 12px;
  -webkit-app-region: drag;
}

.quick-chat__avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  overflow: hidden;
  background: var(--ly-bg-surface, #1e2732);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
}

.quick-chat__name {
  font-size: 14px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.quick-chat__close {
  border: none;
  background: transparent;
  color: var(--ly-text-secondary, #a1a1aa);
  font-size: 22px;
  line-height: 1;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 8px;
  flex-shrink: 0;
  position: relative;
  z-index: 2;
  -webkit-app-region: no-drag;

  &:hover {
    color: var(--ly-text-primary, #f5f5f7);
    background: rgba(255, 255, 255, 0.06);
  }
}

.quick-chat__state {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}

.quick-chat__log {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.quick-chat__time-divider {
  align-self: center;
  text-align: center;
  margin: 2px 0 4px;
  flex-shrink: 0;

  span {
    display: inline-block;
    font-size: 11px;
    color: var(--ly-text-muted, #8a727c);
    padding: 3px 12px;
    border-radius: 999px;
    background: rgba(30, 39, 50, 0.75);
    border: 1px solid rgba(255, 255, 255, 0.08);
  }
}

.quick-chat__row {
  display: flex;

  &--user {
    justify-content: flex-end;
  }

  &--hero {
    justify-content: flex-start;
  }
}

.quick-chat__bubble {
  max-width: 85%;
  padding: 8px 12px;
  border-radius: 14px;
  font-size: 13px;
  line-height: 1.5;
  word-break: break-word;
}

.quick-chat__row--user .quick-chat__bubble {
  background: var(--ly-accent, #f4a6b5);
  color: #fff;
  border-bottom-right-radius: 4px;
}

.quick-chat__row--hero .quick-chat__bubble {
  background: var(--ly-chat-hero-bubble-bg, var(--ly-bg-surface, #1e2732));
  border: 1px solid var(--ly-chat-hero-bubble-border, var(--ly-border-subtle, rgba(255, 255, 255, 0.08)));
  border-bottom-left-radius: 4px;
  box-shadow: var(--ly-chat-hero-bubble-shadow, none);

  :deep(.quick-chat__text),
  :deep(.assistant-msg__speech) {
    color: var(--ly-assistant-msg-speech, var(--ly-chat-hero-bubble-text, var(--ly-text-primary, #f5f5f7)));
  }

  :deep(.assistant-msg__inner) {
    color: var(--ly-assistant-msg-inner, var(--ly-text-muted, #8a727c));
    font-style: italic;
    opacity: 0.72;
    letter-spacing: 0.01em;
  }
}

.quick-chat__text {
  margin: 0;
  white-space: pre-wrap;
}

.quick-chat__anchor {
  height: 1px;
  flex-shrink: 0;
}

.quick-chat__foot {
  display: flex;
  gap: 8px;
  align-items: flex-end;
  padding: 10px 12px 12px;
  border-top: 1px solid var(--ly-border-subtle, rgba(255, 255, 255, 0.08));
  background: var(--ly-bg-secondary, #171e28);
  flex-shrink: 0;

  :deep(.el-textarea__inner) {
    background: var(--ly-bg-surface, #1e2732);
    color: var(--ly-text-primary, #f5f5f7);
    box-shadow: none;
  }

  .el-button {
    flex-shrink: 0;
  }
}
</style>
