<template>
  <div class="groupchat-page">
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
        <el-tag v-else type="danger" size="small" effect="dark" @click="connectWebSocket(activeGroup?.id)">{{ t('group.disconnectedReconnect') }}</el-tag>
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
              <div class="gm-content">{{ item.content }}</div>
            </div>
          </div>
        </template>
        <div v-if="streamingChar" class="group-msg-row msg-assistant streaming">
          <div class="gm-avatar">
            <img v-if="streamingChar.avatarUrl" :src="resolveMediaUrl(streamingChar.avatarUrl)" />
            <el-icon v-else :size="16"><User /></el-icon>
          </div>
          <div class="gm-body bubble-assistant">
            <div class="gm-header">
              <span class="gm-name">{{ streamingChar.name }}</span>
              <span class="gm-typing">{{ t('group.typing') }}</span>
            </div>
            <div class="gm-content">{{ streamingChar._content }}</div>
            <span class="typing-cursor">▊</span>
          </div>
        </div>
        <div ref="groupScrollAnchor"></div>
      </div>

      <div class="group-input-area">
        <div class="input-row">
          <el-input
            v-model="groupInput"
            :placeholder="t('group.placeholderDetailed')"
            :disabled="wsStatus !== 'connected'"
            @keydown.enter.exact="sendGroupMessage"
          />
          <el-popover
            v-model:visible="mentionPopoverVisible"
            trigger="click"
            placement="top"
            width="220"
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
            <h3 class="group-card-title">{{ groupTitleLabel(g.title) }}</h3>
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
    <el-dialog v-model="dialogVisible" :title="t('group.createDialogTitle')" width="480px" destroy-on-close>
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
import { useI18n } from 'vue-i18n'
import { Client } from '@stomp/stompjs'
import { listCharacters } from '@/api/character'
import {
  listConversations,
  createGroupConversation,
  deleteConversation,
  getMessages,
  getGroupMembers,
  updateGroupTitle
} from '@/api/conversation'
import { listNotifications } from '@/api/notification'
import { PLATFORM_PROVIDER } from '@/constants/ai'
import { useProvidersStore } from '@/stores/providers'
import { useNotificationsStore } from '@/stores/notifications'
import { useConversationUnread } from '@/composables/useConversationUnread'
import GroupAvatar from '@/components/group/GroupAvatar.vue'
import { Plus, ChatDotRound, ArrowLeft, User, UserFilled, Promotion, Delete, Edit, Check, Close } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { resolveMediaUrl } from '@/utils/media'
import { dateLocaleForUi } from '@/utils/dateLocale'
import { resolveGroupDisplayTitle } from '@/utils/groupTitle'

const TIME_GAP_MS = 5 * 60 * 1000
const MAX_GROUP_MEMBERS = 4

const providersStore = useProvidersStore()
const notificationsStore = useNotificationsStore()
const {
  ingestConversations,
  ingestUnreadNotifications,
  refreshUnreadFromApi,
  unreadCountForGroup,
  formatBadgeCount
} = useConversationUnread()
const { t, locale } = useI18n()

const characters = ref([])
const groups = ref([])
const activeGroup = ref(null)
const groupMembers = ref([])
const groupMessages = ref([])
const groupInput = ref('')
const mentionPopoverVisible = ref(false)
const speakingCharId = ref(null)
const streamingChar = ref(null)
const wsStatus = ref('disconnected')
const groupMsgListRef = ref(null)
const groupScrollAnchor = ref(null)

const dialogVisible = ref(false)
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

let stompClient = null
let msgCounter = 0

function groupTitleLabel(title) {
  return resolveGroupDisplayTitle(title, t('group.untitled'))
}

const displayGroupTitle = computed(() => groupTitleLabel(activeGroup.value?.title))

const groupMessageTimeline = computed(() => {
  const items = []
  let prevMs = null
  for (const msg of groupMessages.value) {
    const ms = parseMessageTime(msg)
    if (prevMs != null && ms - prevMs > TIME_GAP_MS) {
      items.push({
        type: 'time',
        _key: `time-${ms}`,
        label: formatTimeDivider(ms)
      })
    }
    items.push({ type: 'message', ...msg })
    prevMs = ms
  }
  return items
})

onMounted(async () => {
  await providersStore.fetchVaults()
  await notificationsStore.init()
  characters.value = await listCharacters().catch(() => []) || []
  await refreshGroupsList()
})

onUnmounted(() => {
  disconnectWebSocket()
})

watch(
  () => notificationsStore.unreadCount,
  () => {
    refreshUnreadFromApi()
  }
)

watch(
  () => notificationsStore.latest,
  () => {
    refreshUnreadFromApi()
  },
  { deep: true }
)

function disconnectWebSocket() {
  if (stompClient) {
    stompClient.deactivate()
    stompClient = null
  }
  wsStatus.value = 'disconnected'
}

function buildBrokerUrl() {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${window.location.host}/ws`
}

function connectWebSocket(groupId) {
  if (!groupId) return
  disconnectWebSocket()

  const token = localStorage.getItem('lianyu-token')
  wsStatus.value = 'connecting'

  stompClient = new Client({
    brokerURL: buildBrokerUrl(),
    connectHeaders: { token },
    reconnectDelay: 5000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,

    onConnect: () => {
      wsStatus.value = 'connected'
      stompClient.subscribe(`/topic/group/${groupId}`, (message) => {
        try {
          const body = JSON.parse(message.body)
          handleGroupEvent(body)
        } catch {}
      })
    },

    onDisconnect: () => {
      wsStatus.value = 'disconnected'
    },

    onWebSocketError: () => {
      wsStatus.value = 'disconnected'
    },

    onStompError: (frame) => {
      console.warn('STOMP error:', frame.headers['message'])
      wsStatus.value = 'disconnected'
    }
  })

  stompClient.activate()
}

function handleGroupEvent(body) {
  const type = body.type

  if (type === 'CHARACTER_MESSAGE') {
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
      _charAvatar: member?.avatarUrl || null,
      content: body.content,
      _time: new Date().toISOString()
    })
    streamingChar.value = null
    speakingCharId.value = null
    nextTick(() => scrollGroupBottom())
  } else if (type === 'CHARACTER_START') {
    speakingCharId.value = body.characterId
    const member = groupMembers.value.find(m => m.id === body.characterId)
    if (member) {
      streamingChar.value = { ...member, _content: '' }
    }
  } else if (type === 'CHARACTER_CHUNK') {
    if (streamingChar.value) {
      streamingChar.value._content += (body.content || '')
      nextTick(() => scrollGroupBottom())
    }
  } else if (type === 'CHARACTER_END') {
    if (streamingChar.value) {
      groupMessages.value.push({
        _key: 'g' + (++msgCounter),
        role: 'assistant',
        characterId: streamingChar.value.id,
        _charName: streamingChar.value.name,
        _charAvatar: streamingChar.value.avatarUrl,
        content: streamingChar.value._content,
        _time: new Date().toISOString()
      })
      if (activeGroup.value?.id === body.conversationId) {
        notificationsStore.markConversationRead(body.conversationId)
        refreshUnreadFromApi()
      }
    }
    streamingChar.value = null
    speakingCharId.value = null
    nextTick(() => scrollGroupBottom())
  } else if (type === 'TURN_INTERRUPTED' || type === 'TURN_COMPLETE') {
    streamingChar.value = null
    speakingCharId.value = null
  } else if (type === 'ERROR') {
    streamingChar.value = null
    speakingCharId.value = null
    ElMessage.error(body.content || t('group.replyFailed'))
  }
}

async function sendGroupMessage() {
  const text = groupInput.value.trim()
  if (!text || wsStatus.value !== 'connected' || !stompClient || !activeGroup.value) return

  const userStore = await (async () => {
    const { useUserStore } = await import('@/stores/user')
    return useUserStore()
  })()

  stompClient.publish({
    destination: `/app/group/${activeGroup.value.id}/send`,
    body: JSON.stringify({
      provider: providersStore.vaults[0]?.provider || PLATFORM_PROVIDER,
      model: providersStore.vaults[0]?.modelDefault || undefined,
      content: text
    })
  })

  groupMessages.value.push({
    _key: 'g' + (++msgCounter),
    role: 'user',
    _charName: userStore.displayName,
    content: text,
    _time: new Date().toISOString()
  })

  groupInput.value = ''
  nextTick(() => scrollGroupBottom())
}

async function handleCreateGroup() {
  if (selectedCharIds.value.length < 2 || selectedCharIds.value.length > MAX_GROUP_MEMBERS) return
  creatingGroup.value = true
  try {
    const memberIds = [...selectedCharIds.value]
    const conv = await createGroupConversation({
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
    listConversations().catch(() => []),
    listNotifications({ unreadOnly: true, limit: 200 }).catch(() => [])
  ])
  const groupList = (convList || []).filter(c => c.mode === 'GROUP')
  groups.value = groupList
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

async function openGroup(group, memberIds) {
  activeGroup.value = group
  if (group?.id) {
    await notificationsStore.markConversationRead(group.id)
    await refreshUnreadFromApi()
  }

  try {
    const page = await getMessages(group.id, { limit: 50 })
    const msgs = page?.records || []
    groupMembers.value = await loadGroupMembers(group.id, memberIds)
    groupMembersCache.value = { ...groupMembersCache.value, [group.id]: groupMembers.value }
    const seenChars = new Map(groupMembers.value.map(m => [m.id, m]))
    groupMessages.value = msgs.map(m => ({
      ...m,
      _key: 'g' + (++msgCounter),
      _charName: (m.role || '').toLowerCase() === 'user' ? t('group.me') : (seenChars.get(m.characterId)?.name || t('group.roleFallback')),
      _charAvatar: seenChars.get(m.characterId)?.avatarUrl || null,
      _time: m.createdAt
    }))
  } catch {
    groupMembers.value = await loadGroupMembers(group.id, memberIds)
    groupMembersCache.value = { ...groupMembersCache.value, [group.id]: groupMembers.value }
    groupMessages.value = []
  }
  connectWebSocket(group.id)
  await nextTick()
  scrollGroupBottom()
}

function leaveGroup() {
  cancelEditGroupTitle()
  disconnectWebSocket()
  activeGroup.value = null
  groupMembers.value = []
  groupMessages.value = []
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
    const updated = await updateGroupTitle(group.id, nextTitle || null)
    const title = updated?.title ?? (nextTitle || null)
    activeGroup.value = { ...group, title }
    const idx = groups.value.findIndex(g => g.id === group.id)
    if (idx >= 0) {
      groups.value[idx] = { ...groups.value[idx], title }
    }
    ElMessage.success(t('group.renameSuccess'))
    cancelEditGroupTitle()
  } catch (e) {
    ElMessage.error(e?.message || t('group.renameFailed'))
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
    await deleteConversation(group.id)
    const nextCache = { ...groupMembersCache.value }
    delete nextCache[group.id]
    groupMembersCache.value = nextCache
    groups.value = groups.value.filter(g => g.id !== group.id)
    ElMessage.success(t('group.deleteSuccess'))
    leaveGroup()
  } catch (e) {
    ElMessage.error(e?.message || t('group.deleteFailed'))
  } finally {
    deletingGroup.value = false
  }
}

function scrollGroupBottom() {
  groupScrollAnchor.value?.scrollIntoView({ behavior: 'smooth' })
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
  const d = new Date(ms)
  const loc = dateLocaleForUi(locale.value)
  return d.toLocaleTimeString(loc, { hour: '2-digit', minute: '2-digit', hour12: false })
}

function isUserMessage(msg) {
  return (msg.role || '').toLowerCase() === 'user'
}
</script>

<style lang="scss" scoped>
.groupchat-page {
  max-width: 900px;
  height: calc(100vh - #{$header-height} - #{$space-6} * 2);
  display: flex;
  flex-direction: column;
  margin: -$space-6;
  margin-left: -$space-8;
  margin-right: -$space-8;
}

.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  padding: $space-6 $space-8;
  animation: fadeSlideUp 0.5s ease both;
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

.group-card-title {
  font-size: $font-size-base;
  font-weight: $font-weight-semibold;
  color: $color-text-primary;
  margin-bottom: $space-2;
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
.group-header {
  display: flex;
  align-items: center;
  gap: $space-4;
  padding: $space-3 $space-5;
  flex-shrink: 0;

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
  flex: 1;
  overflow-y: auto;
  padding: $space-4 $space-6;
  display: flex;
  flex-direction: column;
  gap: $space-3;
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
  color: rgba($color-text-inverse, 0.75);
  opacity: 1;
}

.gm-typing {
  font-size: 11px;
  color: $color-pink-primary;
  font-style: italic;
}

.gm-content {
  font-size: $font-size-sm;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;

  .bubble-user & {
    color: $color-text-inverse;
  }

  .bubble-assistant & {
    color: $color-text-primary;
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
  padding: $space-3 $space-5;
  border-top: 1px solid rgba($color-pink-rgb, 0.06);
  background: rgba($color-bg-secondary, 0.4);

  .input-row {
    display: flex;
    gap: $space-2;
    align-items: center;
  }
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
