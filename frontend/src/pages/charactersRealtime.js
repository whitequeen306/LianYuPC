import { CHAR_CARD_UNREAD_TYPES } from '@/constants/notificationTypes'

export function resolveCharacterCardRefreshTarget(notification, conversationModes) {
  const convId = notification?.conversationId != null ? Number(notification.conversationId) : null
  if (convId == null) return null
  if (!CHAR_CARD_UNREAD_TYPES.has(notification?.type || '')) return null
  if ((conversationModes?.[convId] || '') === 'GROUP') return null
  return convId
}
