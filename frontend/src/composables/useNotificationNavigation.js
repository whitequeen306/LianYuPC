import router from '@/router'
import { createConversation } from '@/api/conversation'
import { useNotificationsStore } from '@/stores/notifications'
import { useConversationsStore } from '@/stores/conversations'

function inferNotificationType(notification) {
  const type = (notification?.type || '').trim()
  if (type) return type

  const title = notification?.title || ''
  if (/发布了|评论了/.test(title)) return 'MOMENT_NEW'
  if (/日记/.test(title)) return 'DIARY_NEW'
  if (/在群聊/.test(title)) return 'GROUP_MESSAGE'
  if (/给你发来消息|发来消息/.test(title)) return 'PROACTIVE_MESSAGE'
  return 'MESSAGE'
}

async function openSingleChatByCharacterId(characterId) {
  if (!characterId) return null
  const conversationsStore = useConversationsStore()
  try {
    const convs = await conversationsStore.fetchList()
    const existing = (convs || []).find((c) => c.mode === 'SINGLE' && c.characterId === characterId)
    const convId = existing?.id ?? (await createConversation({ characterId, mode: 'SINGLE' })).id
    await router.push({ path: `/app/chat/${convId}` })
    return convId
  } catch {
    await router.push('/app/characters')
    return null
  }
}

/** 构建桌面/浏览器通知点击用的 hash 路径 */
export function buildNotificationHash(notification) {
  if (!notification) return '#/app'

  const type = inferNotificationType(notification)
  const conversationId = notification.conversationId
  const characterId = notification.characterId

  if (type === 'MOMENT_NEW' || type === 'MOMENT_COMMENT') {
    const query = characterId ? `?characterId=${characterId}` : ''
    return `#/app/moments${query}`
  }

  if (type.startsWith('DIARY')) {
    const query = characterId ? `?characterId=${characterId}` : ''
    return `#/app/diary${query}`
  }

  if (type === 'GROUP_MESSAGE') {
    const query = conversationId ? `?groupId=${conversationId}` : ''
    return `#/app/group-chat${query}`
  }

  if (conversationId) return `#/app/chat/${conversationId}`
  return '#/app'
}

/** 根据通知类型跳转到对应页面 */
export async function navigateToNotification(notification) {
  if (!notification) return

  const type = inferNotificationType(notification)
  const conversationId = notification.conversationId
  const characterId = notification.characterId
  const notificationsStore = useNotificationsStore()

  const isFeedNotification =
    type === 'MOMENT_NEW' ||
    type === 'MOMENT_COMMENT' ||
    type.startsWith('DIARY')
  if (isFeedNotification && notification.id != null) {
    await notificationsStore.markNotificationsByIds([notification.id])
  } else if (conversationId) {
    await notificationsStore.markConversationRead(conversationId)
  }

  if (type === 'MOMENT_NEW' || type === 'MOMENT_COMMENT') {
    await router.push({
      path: '/app/moments',
      query: characterId ? { characterId: String(characterId) } : {}
    })
    return
  }

  if (type.startsWith('DIARY')) {
    await router.push({
      path: '/app/diary',
      query: characterId ? { characterId: String(characterId) } : {}
    })
    return
  }

  if (type === 'GROUP_MESSAGE') {
    await router.push({
      path: '/app/group-chat',
      query: conversationId ? { groupId: String(conversationId) } : {}
    })
    return
  }

  if (type === 'PROACTIVE_MESSAGE' || type === 'MESSAGE') {
    if (conversationId) {
      await router.push(`/app/chat/${conversationId}`)
      return
    }
    if (characterId) {
      await openSingleChatByCharacterId(characterId)
      return
    }
  }

  if (conversationId) {
    await router.push(`/app/chat/${conversationId}`)
    return
  }
  if (characterId) {
    await openSingleChatByCharacterId(characterId)
  }
}

export function useNotificationNavigation() {
  return { navigateToNotification }
}
