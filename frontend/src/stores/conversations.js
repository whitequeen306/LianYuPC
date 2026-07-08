import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import {
  listConversations,
  getConversation,
  createGroupConversation,
  deleteConversation,
  updateGroupTitle
} from '@/api/conversation'

const STALE_MS = 60_000

export const useConversationsStore = defineStore('conversations', () => {
  const list = ref([])
  const loading = ref(false)
  let lastFetchedAt = 0

  function conversationSortTime(conversation) {
    const raw = conversation?.updatedAt || conversation?.lastMessageAt || conversation?.createdAt || null
    if (!raw) return 0
    const ms = new Date(raw).getTime()
    return Number.isFinite(ms) ? ms : 0
  }

  const groups = computed(() => (list.value || []).filter(c => c.mode === 'GROUP'))
  const singles = computed(() => (list.value || []).filter(c => c.mode === 'SINGLE'))

  function invalidate() {
    lastFetchedAt = 0
  }

  function isFresh() {
    return list.value.length > 0 && Date.now() - lastFetchedAt < STALE_MS
  }

  async function fetchList({ force = false, silent = true } = {}) {
    if (!force && isFresh()) {
      return list.value
    }
    loading.value = true
    try {
      const data = await listConversations({ silent })
      list.value = Array.isArray(data) ? data : []
      lastFetchedAt = Date.now()
      return list.value
    } finally {
      loading.value = false
    }
  }

  function patchConversation(id, patch) {
    const idx = list.value.findIndex(c => c.id === id)
    if (idx >= 0) {
      list.value[idx] = { ...list.value[idx], ...patch }
      lastFetchedAt = Date.now()
    }
  }

  function patchRealtimeSummary(id, patch) {
    const idx = list.value.findIndex(c => c.id === id)
    if (idx < 0) return
    const updated = { ...list.value[idx], ...patch }
    const next = [...list.value]
    next.splice(idx, 1)

    const updatedMs = conversationSortTime(updated)
    const insertAt = next.findIndex(item => conversationSortTime(item) < updatedMs)
    if (insertAt === -1) {
      next.push(updated)
    } else {
      next.splice(insertAt, 0, updated)
    }

    list.value = next
    lastFetchedAt = Date.now()
  }

  async function refreshConversationSummary(id) {
    if (!id) return null
    const conversation = await getConversation(id)
    if (!conversation?.id) return null
    patchRealtimeSummary(id, {
      lastMessage: conversation.lastMessage ?? null,
      lastCharacterMessage: conversation.lastCharacterMessage ?? null,
      updatedAt: conversation.updatedAt || conversation.lastMessageAt || conversation.createdAt || null,
    })
    return conversation
  }

  function removeLocal(id) {
    list.value = list.value.filter(c => c.id !== id)
    lastFetchedAt = Date.now()
  }

  function prepend(conversation) {
    if (!conversation?.id) return
    list.value = [conversation, ...list.value.filter(c => c.id !== conversation.id)]
    lastFetchedAt = Date.now()
  }

  async function createGroup(payload) {
    const created = await createGroupConversation(payload)
    if (created) {
      prepend(created)
    }
    return created
  }

  async function remove(id) {
    await deleteConversation(id)
    removeLocal(id)
  }

  async function renameGroup(id, title) {
    const updated = await updateGroupTitle(id, title)
    patchConversation(id, { title: updated?.title ?? title ?? null })
    return updated
  }

  return {
    list,
    groups,
    singles,
    loading,
    invalidate,
    fetchList,
    patchConversation,
    patchRealtimeSummary,
    refreshConversationSummary,
    removeLocal,
    prepend,
    createGroup,
    remove,
    renameGroup
  }
})
