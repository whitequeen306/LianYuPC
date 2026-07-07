<template>
  <div class="groupchat-page" :class="{ 'groupchat-page--active': !!activeGroup }">
    <header class="page-header" v-if="!activeGroup">
      <div>
        <h1 class="page-title">{{ t('group.title') }}</h1>
        <p class="page-desc">{{ t('group.desc') }}</p>
      </div>
      <div class="header-actions">
        <el-button type="primary" class="btn-cta" :icon="Plus" @click="showCreateDialog">{{ t('group.create') }}</el-button>
      </div>
    </header>

    <!-- Active group chat -->
    <template v-if="activeGroup">
      <header class="group-header glass-strong">
        <el-button text :icon="ArrowLeft" @click="leaveGroup">{{ t('common.back') }}</el-button>
        <div class="group-title-area">
          <div class="group-title-row">
            <template v-if="editingGroupTitle">
              <el-input
                ref="groupTitleInputRef"
                v-model="editGroupTitleDraft"
                size="small"
                maxlength="64"
                :placeholder="t('group.renamePlaceholder')"
                class="group-title-input"
                @keydown.enter.prevent="saveGroupTitle"
                @keydown.esc="cancelEditGroupTitle"
              />
              <el-button
                text
                type="primary"
                :icon="Check"
                :loading="savingGroupTitle"
                :title="t('common.save')"
                @click="saveGroupTitle"
              />
              <el-button
                text
                :icon="Close"
                :disabled="savingGroupTitle"
                :title="t('common.cancel')"
                @click="cancelEditGroupTitle"
              />
            </template>
            <template v-else>
              <h2>{{ displayGroupTitle }}</h2>
              <el-button
                text
                class="group-title-edit-btn"
                :icon="Edit"
                :title="t('group.rename')"
                @click="startEditGroupTitle"
              />
            </template>
          </div>
          <span class="member-count">{{ t('group.memberCount', { count: groupMembers.length }) }}</span>
        </div>
        <GroupAvatar :members="groupMembers" :size="36" class="group-header-avatar" />
        <el-tag v-if="wsStatus === 'connected'" type="success" size="small" effect="dark">{{ t('group.connected') }}</el-tag>
        <el-tag v-else-if="wsStatus === 'connecting'" type="warning" size="small" effect="dark">{{ t('group.connecting') }}</el-tag>
        <el-tag
          v-else
          type="danger"
          size="small"
          effect="dark"
          class="ws-reconnect-tag"
          @click="reconnectWebSocket"
        >{{ t('group.disconnectedReconnect') }}</el-tag>
        <el-button
          type="danger"
          text
          :icon="Delete"
          :loading="deletingGroup"
          :title="t('group.delete')"
          @click="handleDeleteGroup"
        />
      </header>

      <div class="group-messages" ref="groupMsgListRef">
        <button
          v-if="isUserScrolledUp"
          type="button"
          class="group-scroll-bottom"
          :title="t('chat.scrollToBottom')"
          @click="jumpToBottom"
        >
          <el-icon :size="18"><ArrowDown /></el-icon>
        </button>
        <div v-if="loadingOlder" class="group-load-more">{{ t('common.loading') }}</div>
        <div v-else-if="hasMoreOlder" class="group-load-more group-load-more--hint">{{ t('chat.loadMore') }}</div>
        <template v-for="item in groupMessageTimeline" :key="item._key">
          <div v-if="item.type === 'time'" class="msg-time-divider">
            <span>{{ item.label }}</span>
          </div>
          <div
            v-else
            class="group-msg-row"
            :class="isUserMessage(item) ? 'msg-user' : 'msg-assistant'"
          >
            <div v-if="!isUserMessage(item)" class="gm-avatar">
              <img v-if="item._charAvatar" :src="resolveMediaUrl(item._charAvatar)" />
              <el-icon v-else :size="16"><User /></el-icon>
            </div>
            <div class="gm-body" :class="isUserMessage(item) ? 'bubble-user' : 'bubble-assistant'">
              <div v-if="!isUserMessage(item)" class="gm-header">
                <span class="gm-name">{{ item._charName || item.characterName }}</span>
              </div>
              <div v-if="isUserMessage(item)" class="gm-content">{{ item.content }}</div>
              <AssistantMessageContent
                v-else
                :content="item.content"
                :show-inner-thoughts="item._showInnerThoughts"
                variant="group"
                tag="div"
                extra-class="gm-content"
              />
            </div>
          </div>
        </template>
        <div ref="groupScrollAnchor"></div>
      </div>

      <div class="group-input-area">
        <button
          v-if="wsStatus !== 'connected'"
          type="button"
          class="ws-status-hint"
          :disabled="wsStatus === 'connecting'"
          @click="reconnectWebSocket"
        >
          {{ wsStatus === 'connecting' ? t('group.connecting') : t('group.disconnectedReconnect') }}
        </button>
        <div class="input-row">
          <el-input
            v-model="groupInput"
            ref="groupInputRef"
            :placeholder="t('group.placeholderDetailed')"
            :disabled="wsStatus !== 'connected'"
            @keydown.enter.exact="sendGroupMessage"
          />
          <div class="mention-entry">
            <transition name="mention-hint-fade">
              <OnboardingHintBubble
                v-if="showMentionHint && activeGroup"
                placement="top"
                :close-label="t('common.cancel')"
                @dismiss="dismissMentionHint"
              >
                {{ t('onboarding.groupMentionHint') }}
              </OnboardingHintBubble>
            </transition>
            <el-popover
              v-model:visible="mentionPopoverVisible"
              trigger="click"
              placement="top"
              width="220"
              @show="dismissMentionHint"
            >
              <template #reference>
                <el-button
                  :icon="UserFilled"
                  :disabled="wsStatus !== 'connected' || groupMembers.length === 0"
                  :title="t('group.mentionTitle')"
                />
              </template>
            <div class="mention-list">
              <el-button
                v-for="m in groupMembers"
                :key="m.id"
                text
                class="mention-item"
                @click="insertMention(m.name)"
              >
                @{{ m.name }}
              </el-button>
            </div>
          </el-popover>
          </div>
          <el-button
            type="primary"
            :icon="Promotion"
            :disabled="!groupInput.trim() || wsStatus !== 'connected'"
            @click="sendGroupMessage"
          />
        </div>
      </div>
    </template>

    <!-- Group list (like character page) -->
    <div v-else-if="groups.length > 0" class="group-list-page">
      <div class="group-grid">
        <div
          v-for="(g, idx) in groups"
          :key="g.id"
          class="group-card glass stagger-item"
          :class="{ 'has-unread': unreadCountForGroup(g.id) > 0 }"
          :style="{ animationDelay: `${idx * 0.05}s` }"
          role="button"
          tabindex="0"
          @click="openGroup(g)"
          @keydown.enter="openGroup(g)"
        >
          <div class="group-card-avatar-wrap">
            <GroupAvatar :members="membersForGroup(g.id)" :size="64" />
            <span
              v-if="unreadCountForGroup(g.id) > 0"
              class="group-card-unread"
              aria-label="有未读消息"
            >{{ formatBadgeCount(unreadCountForGroup(g.id)) }}</span>
          </div>
          <div class="group-card-body">
            <div class="group-card-title-row">
              <h3 class="group-card-title">{{ groupTitleLabel(g.title) }}</h3>
              <el-button
                text
                class="group-card-edit-btn"
                :icon="Edit"
                :title="t('group.rename')"
                :aria-label="t('group.rename')"
                @click.stop="promptRenameGroup(g)"
              />
            </div>
            <p class="group-card-preview">
              {{ g.lastMessage || t('group.noMessagesYet') }}
            </p>
            <span class="group-card-meta">
              {{ t('group.memberCount', { count: membersForGroup(g.id).length }) }}
            </span>
          </div>
        </div>
      </div>
    </div>

    <div v-else class="empty-state-full glass">
      <div class="empty-icon">
        <el-icon :size="40"><ChatDotRound /></el-icon>
      </div>
      <h3>{{ t('group.featureTitle') }}</h3>
      <p>{{ t('group.featureDesc') }}</p>
      <el-button type="primary" class="btn-cta btn-cta-lg" :icon="Plus" @click="showCreateDialog">
        {{ t('group.create') }}
      </el-button>
    </div>

    <!-- Create Group Dialog -->
    <el-dialog v-model="dialogVisible" :title="t('group.createDialogTitle')" :width="dialogWidth" destroy-on-close>
      <el-form label-position="top" @submit.prevent="handleCreateGroup">
        <el-form-item :label="t('group.selectMembers')">
          <el-select
            v-model="selectedCharIds"
            multiple
            :multiple-limit="MAX_GROUP_MEMBERS"
            :placeholder="t('group.selectMembersPlaceholder')"
            style="width:100%"
            filterable
          >
            <el-option
              v-for="c in characters"
              :key="c.id"
              :label="c.name"
              :value="c.id"
            />
          </el-select>
          <p class="member-limit-hint">{{ t('group.memberLimitHint', { max: MAX_GROUP_MEMBERS, count: selectedCharIds.length }) }}</p>
        </el-form-item>
        <el-form-item :label="t('group.groupTitleOptional')">
          <el-input v-model="groupTitle" :placeholder="t('group.groupTitlePlaceholder')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button type="default" @click="dialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button
          type="primary"
          class="btn-cta"
          :disabled="selectedCharIds.length < 2"
          :loading="creatingGroup"
          @click="handleCreateGroup"
        >
          {{ t('common.create') }}
        </el-button>
      </template>
    </el-dialog>

  </div>
</template>

<script setup>
import { ref, computed, nextTick, onMounted, onUnmounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { storeToRefs } from 'pinia'
import { useI18n } from 'vue-i18n'
import {
  getMessages,
  getGroupMembers
} from '@/api/conversation'
import { useCharactersStore } from '@/stores/characters'
import { useConversationsStore } from '@/stores/conversations'
import { listNotifications } from '@/api/notification'
import { PLATFORM_PROVIDER } from '@/constants/ai'
import { useProvidersStore } from '@/stores/providers'
import { useNotificationsStore } from '@/stores/notifications'
import { useLayoutStore } from '@/stores/layout'
import { useConversationUnread } from '@/composables/useConversationUnread'
import { useResponsiveDialogWidth } from '@/composables/useResponsiveDialogWidth'
import GroupAvatar from '@/components/group/GroupAvatar.vue'
import OnboardingHintBubble from '@/components/OnboardingHintBubble.vue'
import { useOnboardingHint } from '@/composables/useOnboardingHint'
import { Plus, ChatDotRound, ArrowLeft, ArrowDown, User, UserFilled, Promotion, Delete, Edit, Check, Close } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { resolveMediaUrl } from '@/utils/media'
import { pickCharacterAvatarRaw } from '@/utils/characterAvatar'
import { humanizeError } from '@/utils/errorMessage'
import { formatSmartTime } from '@/utils/feedTime'
import { resolveGroupDisplayTitle } from '@/utils/groupTitle'
import { useChatScroll, sleep, MIN_REPLY_DISPLAY_MS } from '@/composables/useChatScroll'
import AssistantMessageContent from '@/components/AssistantMessageContent.vue'
import { stripInnerThoughts, resolveShowInnerThoughts } from '@/utils/innerThoughtFilter'

const TIME_GAP_MS = 5 * 60 * 1000
const MAX_GROUP_MEMBERS = 4

const providersStore = useProvidersStore()
const notificationsStore = useNotificationsStore()
const { wsStatus } = storeToRefs(notificationsStore)
const layoutStore = useLayoutStore()
const {
  ingestConversations,
  ingestUnreadNotifications,
  refreshUnreadFromApi,
  unreadCountForGroup,
  formatBadgeCount
} = useConversationUnread()
const { t, locale } = useI18n()
const route = useRoute()
const { visible: showMentionHint, dismiss: dismissMentionHint } = useOnboardingHint('group-mention')

const charactersStore = useCharactersStore()
const conversationsStore = useConversationsStore()
const { list: characters } = storeToRefs(charactersStore)
const groups = computed(() => conversationsStore.groups)
const activeGroup = ref(null)
const groupMembers = ref([])
const groupMessages = ref([])
const groupInput = ref('')
const groupInputRef = ref(null)
const mentionPopoverVisible = ref(false)
const speakingCharId = ref(null)
const streamingChar = ref(null)
const groupMsgListRef = ref(null)
const groupScrollAnchor = ref(null)
const oldestLoadedSeq = ref(null)
const hasMoreOlder = ref(false)
const loadingOlder = ref(false)
const GROUP_MESSAGE_PAGE_SIZE = 50
const { isUserScrolledUp, scrollToBottom: scrollGroupBottom, jumpToBottom } = useChatScroll(
  groupMsgListRef,
  groupScrollAnchor,
  {
    hasMoreOlder,
    loadingOlder,
    onReachTop: () => { void loadOlderGroupMessages() },
  }
)
let lastUserSendAt = 0
let firstReplyAfterUserPending = false

const dialogVisible = ref(false)
const dialogWidth = useResponsiveDialogWidth(480)
const selectedCharIds = ref([])
const groupTitle = ref('')
const creatingGroup = ref(false)
const deletingGroup = ref(false)
const editingGroupTitle = ref(false)
const editGroupTitleDraft = ref('')
const savingGroupTitle = ref(false)
const groupTitleInputRef = ref(null)

/** @type {import('vue').Ref<Record<number, Array>>} */
const groupMembersCache = ref({})

let msgCounter = 0

function memberAvatar(member) {
  return pickCharacterAvatarRaw(member, 'thumb')
}

function groupTitleLabel(title) {
  return resolveGroupDisplayTitle(title, t('group.untitled'))
}

const displayGroupTitle = computed(() => groupTitleLabel(activeGroup.value?.title))

const groupMessageTimeline = computed(() => {
  const settingsByCharId = {}
  for (const member of groupMembers.value) {
    settingsByCharId[member.id] = member.settings || {}
  }
  const items = []
  let prevMs = null
  for (const msg of groupMessages.value) {
    let content = msg.content
    if (!isUserMessage(msg)) {
      content = stripInnerThoughts(content, resolveShowInnerThoughts(settingsByCharId[msg.characterId] || {}))
      if (!content) continue
    }
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
      content,
      _showInnerThoughts: isUserMessage(msg)
        ? true
        : resolveShowInnerThoughts(settingsByCharId[msg.characterId] || {})
    })
    prevMs = ms
  }
  return items
})

async function openGroupFromRouteQuery() {
  const raw = route.query.groupId
  if (raw == null || raw === '') return
  const groupId = Number(raw)
  if (!Number.isFinite(groupId)) return
  const group = groups.value.find((item) => item.id === groupId)
  if (group) {
    await openGroup(group)
  }
}

onMounted(async () => {
  await providersStore.fetchVaults()
  await notificationsStore.init()
  await charactersStore.fetchList()
  await refreshGroupsList()
  await openGroupFromRouteQuery()
})

onUnmounted(() => {
  layoutStore.setGroupChatSession(false)
  notificationsStore.unsubscribeGroupChat()
})

watch(
  () => notificationsStore.latest?.length ?? 0,
  () => {
    refreshUnreadFromApi()
  }
)

watch(activeGroup, (group) => {
  layoutStore.setGroupChatSession(!!group)
}, { immediate: true })

function reconnectWebSocket() {
  if (wsStatus.value === 'connecting') return
  notificationsStore.reconnectWebSocket()
  if (activeGroup.value?.id) {
    notificationsStore.subscribeGroupChat(activeGroup.value.id, handleGroupEvent)
  }
}

function connectWebSocket(groupId) {
  if (!groupId) return
  notificationsStore.subscribeGroupChat(groupId, handleGroupEvent)
}

async function appendCharacterMessage(body) {
  if (firstReplyAfterUserPending && lastUserSendAt > 0) {
    const elapsed = Date.now() - lastUserSendAt
    if (elapsed < MIN_REPLY_DISPLAY_MS) {
      await sleep(MIN_REPLY_DISPLAY_MS - elapsed)
    }
    firstReplyAfterUserPending = false
  }
  if (activeGroup.value?.id === body.conversationId) {
    notificationsStore.markConversationRead(body.conversationId)
    refreshUnreadFromApi()
  }
  const member = groupMembers.value.find(m => m.id === body.characterId)
  groupMessages.value.push({
    _key: 'g' + (++msgCounter),
    role: 'assistant',
    characterId: body.characterId,
    _charName: body.characterName || member?.name || '角色',
    _charAvatar: memberAvatar(member) || null,
    content: body.content,
    _time: new Date().toISOString()
  })
  streamingChar.value = null
  speakingCharId.value = null
  await nextTick()
  scrollGroupBottom()
}

function appendUserMessageFromWs(body) {
  const content = typeof body.content === 'string' ? body.content.trim() : ''
  if (!content) return
  const recentUser = groupMessages.value.slice(-5).some(m =>
    m.role === 'user' && m.content === content
  )
  if (recentUser) return
  groupMessages.value.push({
    _key: 'g' + (++msgCounter),
    role: 'user',
    content,
    _time: new Date().toISOString()
  })
  void nextTick().then(() => scrollGroupBottom())
}

function handleGroupEvent(body) {
  const type = body.type

  if (type === 'CHARACTER_MESSAGE') {
    void appendCharacterMessage(body)
  } else if (type === 'USER_MESSAGE') {
    appendUserMessageFromWs(body)
  } else if (type === 'TURN_INTERRUPTED' || type === 'TURN_COMPLETE') {
    streamingChar.value = null
    speakingCharId.value = null
  } else if (type === 'TURN_ERROR') {
    streamingChar.value = null
    speakingCharId.value = null
    ElMessage.error(humanizeError(body.content, t('group.replyFailed')))
  } else if (type === 'ERROR') {
    streamingChar.value = null
    speakingCharId.value = null
    ElMessage.error(humanizeError(body.content, t('group.replyFailed')))
  }
}

async function sendGroupMessage() {
  const text = groupInput.value.trim()
  if (!text || wsStatus.value !== 'connected' || !activeGroup.value) return

  const userStore = await (async () => {
    const { useUserStore } = await import('@/stores/user')
    return useUserStore()
  })()

  const sent = notificationsStore.publishGroupMessage(activeGroup.value.id, {
    provider: providersStore.vaults[0]?.provider || PLATFORM_PROVIDER,
    model: providersStore.vaults[0]?.modelDefault || undefined,
    content: text
  })
  if (!sent) return

  groupMessages.value.push({
    _key: 'g' + (++msgCounter),
    role: 'user',
    _charName: userStore.displayName,
    content: text,
    _time: new Date().toISOString()
  })

  groupInput.value = ''
  lastUserSendAt = Date.now()
  firstReplyAfterUserPending = true
  await nextTick()
  const ta = groupInputRef.value?.$el?.querySelector('textarea')
    || groupInputRef.value?.$el?.getElementsByTagName('textarea')?.[0]
  if (ta) ta.focus()
  scrollGroupBottom({ force: true })
}

async function handleCreateGroup() {
  if (selectedCharIds.value.length < 2 || selectedCharIds.value.length > MAX_GROUP_MEMBERS) return
  creatingGroup.value = true
  try {
    const memberIds = [...selectedCharIds.value]
    const conv = await conversationsStore.createGroup({
      characterIds: memberIds,
      title: groupTitle.value || undefined
    })
    dialogVisible.value = false
    selectedCharIds.value = []
    groupTitle.value = ''
    await refreshGroupsList()
    openGroup(conv, memberIds)
  } catch {} finally {
    creatingGroup.value = false
  }
}

function resolveMemberChars(ids) {
  return ids.map(id => {
    const c = characters.value.find(x => x.id === id)
    return c || { id, name: t('group.roleIndex', { id }) }
  })
}

async function loadGroupMembers(groupId, fallbackIds) {
  try {
    const members = await getGroupMembers(groupId)
    if (members?.length) return members
  } catch {}
  return fallbackIds?.length ? resolveMemberChars(fallbackIds) : []
}

async function refreshGroupsList() {
  const [convList, unreadList] = await Promise.all([
    conversationsStore.fetchList({ force: true, silent: true }).catch(() => []),
    listNotifications({ unreadOnly: true, limit: 200 }, { silent: true }).catch(() => [])
  ])
  const groupList = (convList || []).filter(c => c.mode === 'GROUP')
  ingestConversations(convList || [])
  ingestUnreadNotifications(unreadList || [])

  const cache = { ...groupMembersCache.value }
  await Promise.all(
    groupList.map(async (g) => {
      try {
        cache[g.id] = await getGroupMembers(g.id) || []
      } catch {
        cache[g.id] = []
      }
    })
  )
  groupMembersCache.value = cache
}

function membersForGroup(groupId) {
  return groupMembersCache.value[groupId] || []
}

function mapGroupMessages(msgs, seenChars) {
  return msgs.map(m => ({
    ...m,
    _key: 'g' + (++msgCounter),
    _charName: (m.role || '').toLowerCase() === 'user' ? t('group.me') : (seenChars.get(m.characterId)?.name || t('group.roleFallback')),
    _charAvatar: memberAvatar(seenChars.get(m.characterId)) || null,
    _time: m.createdAt
  }))
}

async function loadRecentGroupMessages(groupId, seenChars) {
  const page = await getMessages(groupId, { limit: GROUP_MESSAGE_PAGE_SIZE })
  const batch = page?.records || []
  groupMessages.value = mapGroupMessages(batch, seenChars)
  hasMoreOlder.value = !!page?.hasMore
  oldestLoadedSeq.value = page?.nextBeforeSeq ?? (batch[0]?.seq ?? null)
  if (!batch.length) {
    hasMoreOlder.value = false
    oldestLoadedSeq.value = null
  }
}

async function loadOlderGroupMessages() {
  const group = activeGroup.value
  if (!group?.id || loadingOlder.value || !hasMoreOlder.value) return
  const beforeSeq = oldestLoadedSeq.value
  if (beforeSeq == null) return

  loadingOlder.value = true
  const el = groupMsgListRef.value
  const prevHeight = el?.scrollHeight ?? 0
  const seenChars = new Map(groupMembers.value.map(m => [m.id, m]))

  try {
    const page = await getMessages(group.id, { limit: GROUP_MESSAGE_PAGE_SIZE, beforeSeq })
    const batch = page?.records || []
    if (!batch.length) {
      hasMoreOlder.value = false
      return
    }
    const existingIds = new Set(groupMessages.value.map(m => m.id).filter(Boolean))
    const toPrepend = batch.filter(m => !m.id || !existingIds.has(m.id))
    if (toPrepend.length) {
      groupMessages.value = [...mapGroupMessages(toPrepend, seenChars), ...groupMessages.value]
    }
    hasMoreOlder.value = !!page?.hasMore
    oldestLoadedSeq.value = page?.nextBeforeSeq ?? (toPrepend[0]?.seq ?? oldestLoadedSeq.value)
    await nextTick()
    if (el) {
      el.scrollTop += el.scrollHeight - prevHeight
    }
  } catch {
    // ignore
  } finally {
    loadingOlder.value = false
  }
}

async function openGroup(group, memberIds) {
  activeGroup.value = group
  oldestLoadedSeq.value = null
  hasMoreOlder.value = false
  loadingOlder.value = false
  if (group?.id) {
    await notificationsStore.markConversationRead(group.id)
    await refreshUnreadFromApi()
  }

  try {
    groupMembers.value = await loadGroupMembers(group.id, memberIds)
    groupMembersCache.value = { ...groupMembersCache.value, [group.id]: groupMembers.value }
    const seenChars = new Map(groupMembers.value.map(m => [m.id, m]))
    await loadRecentGroupMessages(group.id, seenChars)
  } catch (e) {
    console.warn('[group] openGroup load failed', e)
    ElMessage.error('群聊消息加载失败，请稍后重试')
  }
  connectWebSocket(group.id)
  await nextTick()
  scrollGroupBottom({ force: true })
}

function leaveGroup() {
  cancelEditGroupTitle()
  notificationsStore.unsubscribeGroupChat()
  activeGroup.value = null
  groupMembers.value = []
  groupMessages.value = []
  oldestLoadedSeq.value = null
  hasMoreOlder.value = false
  loadingOlder.value = false
  refreshUnreadFromApi()
}

function startEditGroupTitle() {
  if (!activeGroup.value) return
  editGroupTitleDraft.value = activeGroup.value.title?.trim() || ''
  editingGroupTitle.value = true
  nextTick(() => {
    const el = groupTitleInputRef.value
    if (el?.focus) {
      el.focus()
    } else if (el?.input?.focus) {
      el.input.focus()
    }
  })
}

function cancelEditGroupTitle() {
  editingGroupTitle.value = false
  editGroupTitleDraft.value = ''
}

async function promptRenameGroup(group) {
  if (!group?.id) return
  try {
    const { value } = await ElMessageBox.prompt(
      t('group.renamePlaceholder'),
      t('group.rename'),
      {
        confirmButtonText: t('common.save'),
        cancelButtonText: t('common.cancel'),
        inputValue: (group.title || '').trim(),
        inputPlaceholder: t('group.groupTitlePlaceholder'),
        inputMaxlength: 64
      }
    )
    const nextTitle = (value ?? '').trim()
    const currentTitle = (group.title || '').trim()
    if (nextTitle === currentTitle) return

    const updated = await conversationsStore.renameGroup(group.id, nextTitle || null)
    const title = updated?.title ?? (nextTitle || null)
    if (activeGroup.value?.id === group.id) {
      activeGroup.value = { ...activeGroup.value, title }
    }
    ElMessage.success(t('group.renameSuccess'))
  } catch (e) {
    if (e === 'cancel' || e === 'close') return
    ElMessage.error(humanizeError(e, t('group.renameFailed')))
  }
}

async function saveGroupTitle() {
  const group = activeGroup.value
  if (!group?.id || savingGroupTitle.value) return

  const nextTitle = editGroupTitleDraft.value.trim()
  const currentTitle = (group.title || '').trim()
  if (nextTitle === currentTitle) {
    cancelEditGroupTitle()
    return
  }

  savingGroupTitle.value = true
  try {
    const updated = await conversationsStore.renameGroup(group.id, nextTitle || null)
    const title = updated?.title ?? (nextTitle || null)
    activeGroup.value = { ...group, title }
    ElMessage.success(t('group.renameSuccess'))
    cancelEditGroupTitle()
  } catch (e) {
    ElMessage.error(humanizeError(e, t('group.renameFailed')))
  } finally {
    savingGroupTitle.value = false
  }
}

async function handleDeleteGroup() {
  const group = activeGroup.value
  if (!group?.id || deletingGroup.value) return
  const title = groupTitleLabel(group.title)
  try {
    await ElMessageBox.confirm(
      t('group.deleteConfirm', { title }),
      t('group.deleteTitle'),
      { type: 'warning', confirmButtonText: t('group.delete'), cancelButtonText: t('common.cancel') }
    )
  } catch {
    return
  }

  deletingGroup.value = true
  try {
    await conversationsStore.remove(group.id)
    const nextCache = { ...groupMembersCache.value }
    delete nextCache[group.id]
    groupMembersCache.value = nextCache
    ElMessage.success(t('group.deleteSuccess'))
    leaveGroup()
  } catch (e) {
    ElMessage.error(humanizeError(e, t('group.deleteFailed')))
  } finally {
    deletingGroup.value = false
  }
}

function showCreateDialog() {
  selectedCharIds.value = []
  groupTitle.value = ''
  dialogVisible.value = true
}

function insertMention(name) {
  if (!name) return
  const mention = `@${name}`
  if (groupInput.value.includes(mention)) {
    mentionPopoverVisible.value = false
    return
  }
  const prefix = groupInput.value.trim()
  groupInput.value = prefix ? `${prefix} ${mention} ` : `${mention} `
  mentionPopoverVisible.value = false
}

function parseMessageTime(msg) {
  const ts = msg?.createdAt || msg?._time
  if (!ts) return Date.now()
  const ms = new Date(ts).getTime()
  return Number.isNaN(ms) ? Date.now() : ms
}

function formatTimeDivider(ms) {
  return formatSmartTime(ms, { t, locale: locale.value })
}

function isUserMessage(msg) {
  return (msg.role || '').toLowerCase() === 'user'
}
</script>

<style lang="scss" scoped>
.groupchat-page {
  width: 100%;
  max-width: none;
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: calc(100vh - #{$header-height} - #{$app-dock-offset} - #{$space-5});

  &--active {
    flex: 1;
    min-height: 0;
    max-height: calc(100vh - #{$header-height} - #{$app-dock-offset} - #{$space-5});
    overflow: hidden;
    display: flex;
    flex-direction: column;
    background: $color-bg-primary;
  }
}

.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  padding: $space-6 $space-8;
}

.header-actions {
  display: flex;
  gap: $space-3;
}

.page-title {
  font-size: $font-size-2xl;
  font-weight: $font-weight-bold;
  color: $color-text-primary;
  margin-bottom: $space-2;
}

.page-desc { font-size: $font-size-sm; color: $color-text-muted; }

.group-list-page {
  flex: 1;
  overflow-y: auto;
  padding: 0 $space-8 $space-8;
}

.group-grid {
  display: grid;
  gap: $space-4;
}

.group-card {
  position: relative;
  display: flex;
  gap: $space-5;
  align-items: flex-start;
  padding: $space-5 $space-6;
  border-radius: $radius-lg;
  cursor: pointer;
  transition: border-color $transition-fast, box-shadow $transition-fast, transform $transition-fast;
  animation: fadeSlideUp 0.5s cubic-bezier(0.4, 0, 0.2, 1) both;

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

.group-card-avatar-wrap {
  position: relative;
  flex-shrink: 0;
}

.group-card-unread {
  position: absolute;
  top: -4px;
  right: -4px;
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
}

.group-card-body {
  flex: 1;
  min-width: 0;
}

.group-card-title-row {
  display: flex;
  align-items: center;
  gap: $space-1;
  width: 100%;
  min-width: 0;
  margin-bottom: $space-2;
}

.group-card-title {
  font-size: $font-size-base;
  font-weight: $font-weight-semibold;
  color: $color-text-primary;
  margin: 0;
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.group-card-edit-btn {
  flex-shrink: 0;
  padding: 2px;
  color: $color-text-muted;

  &:hover {
    color: $color-text-primary;
  }
}

.group-card-preview {
  font-size: $font-size-sm;
  color: $color-text-muted;
  line-height: 1.5;
  margin-bottom: $space-2;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.group-card-meta {
  font-size: $font-size-xs;
  color: $color-text-muted;
  opacity: 0.85;
}

.empty-state-full {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: $color-text-muted;
  border-radius: $radius-lg;
  margin: $space-8;

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

// Group header
.ws-reconnect-tag {
  cursor: pointer;
}

.group-header {
  display: flex;
  align-items: center;
  gap: $space-4;
  padding: $space-3 $space-5;
  flex-shrink: 0;
  z-index: 2;
  background: rgba($color-bg-primary, 0.96);
  border-bottom: 1px solid rgba($color-pink-rgb, 0.08);

.group-title-area {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  flex: 1;
  min-width: 0;
}

.group-title-row {
  display: flex;
  align-items: center;
  gap: $space-1;
  width: 100%;
  min-width: 0;

  h2 {
    font-size: $font-size-base;
    font-weight: $font-weight-semibold;
    color: $color-text-primary;
    margin: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.group-title-input {
  flex: 1;
  min-width: 120px;
  max-width: 280px;
}

.group-title-edit-btn {
  flex-shrink: 0;
  color: $color-text-muted;

  &:hover {
    color: $color-text-primary;
  }
}

.member-count {
  font-size: $font-size-xs;
  color: $color-text-muted;
}
}

.member-avatars {
  display: flex;
  gap: 2px;
}

.member-dot {
  width: 28px; height: 28px;
  border-radius: 50%;
  overflow: hidden;
  background: rgba($color-pink-rgb, 0.1);
  display: flex;
  align-items: center;
  justify-content: center;
  border: 2px solid transparent;
  transition: all $transition-fast;
  color: $color-pink-primary;

  img { width: 100%; height: 100%; object-fit: cover; }

  &.speaking {
    border-color: $color-pink-primary;
    box-shadow: 0 0 8px rgba($color-pink-rgb, 0.35);
  }
}

// Messages
.group-messages {
  position: relative;
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: $space-4 $space-6;
  display: flex;
  flex-direction: column;
  gap: $space-3;
}

.group-load-more {
  align-self: center;
  padding: $space-2 $space-4;
  font-size: $font-size-sm;
  color: $color-text-secondary;
  text-align: center;

  &--hint {
    opacity: 0.75;
  }
}

.group-scroll-bottom {
  position: sticky;
  bottom: $space-3;
  align-self: center;
  z-index: 5;
  width: 40px;
  height: 40px;
  border-radius: 50%;
  border: 1px solid var(--ly-chat-scroll-border);
  background: var(--ly-chat-scroll-bg);
  color: var(--ly-chat-scroll-color);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  backdrop-filter: blur(8px);
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.2);
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

.group-msg-row {
  display: flex;
  gap: $space-3;
  max-width: 80%;

  &.streaming { opacity: 0.8; }
}

.msg-user {
  align-self: flex-end;
  flex-direction: row-reverse;
}

.msg-assistant {
  align-self: flex-start;
}

.gm-avatar {
  width: 36px; height: 36px;
  border-radius: 50%;
  overflow: hidden;
  background: rgba($color-pink-rgb, 0.08);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  color: $color-pink-primary;
  align-self: flex-end;
  margin-bottom: 2px;

  img { width: 100%; height: 100%; object-fit: cover; }
}

.gm-body {
  min-width: 0;
  padding: $space-3 $space-4;
  border-radius: $radius-lg;

  &.bubble-user {
    background: var(--ly-chat-user-bubble-bg, $color-pink-primary);
    color: var(--ly-chat-user-bubble-text, $color-text-inverse);
    border: 1px solid var(--ly-chat-user-bubble-border, transparent);
    border-bottom-right-radius: $radius-sm;
  }

  &.bubble-assistant {
    background: var(--ly-chat-assistant-bubble-bg, rgba($color-bg-surface, 0.9));
    border: 1px solid var(--ly-chat-assistant-bubble-border, rgba($color-pink-rgb, 0.12));
    border-bottom-left-radius: $radius-sm;
    box-shadow: 0 1px 4px rgba(0, 0, 0, 0.04);
  }
}

.gm-header {
  display: flex;
  gap: $space-3;
  align-items: baseline;
  margin-bottom: 2px;

  &.gm-header-user {
    justify-content: flex-end;
  }
}

.gm-name {
  font-size: $font-size-xs;
  font-weight: $font-weight-semibold;
  color: $color-pink-primary;
}

.gm-time {
  font-size: 11px;
  color: $color-text-muted;
  opacity: 0.5;
}

.bubble-user .gm-time {
  color: var(--ly-chat-user-bubble-time, rgba($color-text-inverse, 0.75));
  opacity: 1;
}

.gm-typing {
  font-size: 11px;
  color: $color-pink-primary;
  font-style: italic;
}

.gm-content,
:deep(.gm-content) {
  font-size: $font-size-sm;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;

  .bubble-user & {
    color: var(--ly-chat-user-bubble-text, $color-text-inverse);
  }

  .bubble-assistant & {
    color: var(--ly-chat-assistant-bubble-text, $color-text-primary);
  }
}

.typing-cursor {
  color: $color-pink-primary;
  animation: blink 1s step-end infinite;
  font-weight: $font-weight-bold;
}

@keyframes blink {
  50% { opacity: 0; }
}

// Input
.group-input-area {
  flex-shrink: 0;
  z-index: 2;
  position: relative;
  overflow: visible;
  padding: $space-3 $space-5;
  border-top: 1px solid var(--ly-chat-input-border, rgba($color-pink-rgb, 0.06));
  background: var(--ly-chat-input-bg, rgba($color-bg-secondary, 0.96));

  .ws-status-hint {
    display: block;
    width: 100%;
    margin: 0 0 $space-2;
    padding: 0;
    border: none;
    background: transparent;
    text-align: left;
    font-size: $font-size-xs;
    color: $color-text-muted;
    cursor: pointer;

    &:disabled {
      cursor: default;
      opacity: 0.7;
    }

    &:not(:disabled):hover {
      color: $color-pink-primary;
    }
  }

  .input-row {
    display: flex;
    gap: $space-2;
    align-items: center;
  }
}

.mention-entry {
  position: relative;
  overflow: visible;
  z-index: 5;

  .mention-hint-fade-enter-from,
  .mention-hint-fade-leave-to {
    transform: translateX(-50%) translateY(4px);
  }
}

.mention-hint-fade-enter-active,
.mention-hint-fade-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}

.mention-hint-fade-enter-from,
.mention-hint-fade-leave-to {
  opacity: 0;
  transform: translateY(4px);
}

.mention-list {
  display: flex;
  flex-direction: column;
  max-height: 220px;
  overflow-y: auto;
}

.mention-item {
  justify-content: flex-start;
  width: 100%;
}

// Join dialog
.group-list {
  display: flex;
  flex-direction: column;
  gap: $space-3;
}

.group-item {
  display: flex;
  gap: $space-4;
  padding: $space-4;
  border-radius: $radius-md;
  cursor: pointer;
  transition: all $transition-fast;

  &:hover {
    border-color: rgba($color-pink-rgb, 0.18);
    box-shadow: $shadow-glow-pink;
  }
}

.gi-icon {
  width: 40px; height: 40px;
  border-radius: $radius-md;
  background: rgba($color-pink-rgb, 0.08);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  color: $color-pink-primary;
}

.gi-body {
  display: flex;
  flex-direction: column;
}

.gi-title {
  font-size: $font-size-sm;
  font-weight: $font-weight-medium;
  color: $color-text-primary;
}

.gi-meta {
  font-size: $font-size-xs;
  color: $color-text-muted;
}

.no-data {
  text-align: center;
  padding: $space-8;
  color: $color-text-muted;
  font-size: $font-size-sm;
}

.member-limit-hint {
  margin: $space-2 0 0;
  font-size: $font-size-xs;
  color: $color-text-muted;
}
</style>
