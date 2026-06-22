import { ref, computed } from 'vue'
import { listNotifications } from '@/api/notification'

const conversationModeById = ref({})
const unreadByCharacterId = ref({})
const unreadByGroupId = ref({})

/**
 * 按会话类型拆分未读：单聊归角色卡片，群聊归群聊卡片（避免群消息误标到角色上）。
 * 模块级共享状态，角色页 / 快速选角等可同步红点计数。
 */
export function useConversationUnread() {
  function ingestConversations(convList) {
    const modes = { ...conversationModeById.value }
    for (const c of convList || []) {
      if (c?.id != null) {
        modes[c.id] = c.mode
      }
    }
    conversationModeById.value = modes
  }

  function ingestUnreadNotifications(unreadList) {
    const charMap = {}
    const groupMap = {}
    const modes = conversationModeById.value

    for (const n of unreadList || []) {
      if (n?.read) continue
      const convId = n.conversationId
      if (convId == null) continue

      const mode = modes[convId]
      if (mode === 'GROUP') {
        groupMap[convId] = (groupMap[convId] || 0) + 1
      } else if (n.characterId != null) {
        charMap[n.characterId] = (charMap[n.characterId] || 0) + 1
      }
    }

    unreadByCharacterId.value = charMap
    unreadByGroupId.value = groupMap
  }

  async function refreshUnreadFromApi() {
    try {
      const list = await listNotifications({ unreadOnly: true, limit: 200 }, { silent: true })
      ingestUnreadNotifications(list || [])
    } catch (e) {
      console.warn('[unread] refreshUnreadFromApi failed', e)
    }
  }

  const totalGroupUnread = computed(() =>
    Object.values(unreadByGroupId.value).reduce((sum, n) => sum + Number(n || 0), 0)
  )

  function unreadCountForGroup(groupId) {
    return Number(unreadByGroupId.value?.[groupId] || 0)
  }

  function unreadCountForCharacter(characterId) {
    return Number(unreadByCharacterId.value?.[characterId] || 0)
  }

  function formatBadgeCount(count) {
    const n = Number(count || 0)
    if (n <= 0) return ''
    return n > 99 ? '99+' : String(n)
  }

  return {
    conversationModeById,
    unreadByCharacterId,
    unreadByGroupId,
    totalGroupUnread,
    ingestConversations,
    ingestUnreadNotifications,
    refreshUnreadFromApi,
    unreadCountForGroup,
    unreadCountForCharacter,
    formatBadgeCount
  }
}
