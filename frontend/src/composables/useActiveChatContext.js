import { ref } from 'vue'
import router from '@/router'

/** 当前正在查看的单聊会话 ID（ChatPage 设置，用于抑制同会话的站内通知） */
export const activeChatConversationId = ref(null)

let refreshHandler = null

export function setActiveChatConversationId(id) {
  activeChatConversationId.value = id != null ? Number(id) : null
}

/** ChatPage 注册：收到同会话 proactive 通知时立即拉取消息 */
export function setActiveChatRefreshHandler(handler) {
  refreshHandler = typeof handler === 'function' ? handler : null
}

export function requestActiveChatRefresh(conversationId) {
  const activeId = activeChatConversationId.value
  if (activeId == null || conversationId == null) return
  if (activeId !== Number(conversationId)) return
  refreshHandler?.()
}

/**
 * True when the user is already looking at this conversation
 * (ChatPage state and/or current route — covers square cold-open race).
 */
export function isViewingConversation(conversationId) {
  if (conversationId == null) return false
  const id = Number(conversationId)
  if (!Number.isFinite(id) || id <= 0) return false
  if (activeChatConversationId.value === id) return true

  try {
    const path = String(router.currentRoute.value?.path || '')
    const pathMatch = path.match(/\/(?:app\/)?chat\/(\d+)/)
    if (pathMatch && Number(pathMatch[1]) === id) return true
  } catch {
    // ignore
  }

  const hash = typeof window !== 'undefined' ? String(window.location.hash || '') : ''
  const hashMatch = hash.match(/#\/(?:app\/)?chat\/(\d+)/)
    || hash.match(/#\/quick\/chat\/(\d+)/)
  if (hashMatch && Number(hashMatch[1]) === id) return true

  return false
}

/** True when the user is already on the community feed page. */
export function isViewingCommunityPage() {
  try {
    const path = String(router.currentRoute.value?.path || '')
    if (path.includes('/community')) return true
  } catch {
    // ignore
  }
  const hash = typeof window !== 'undefined' ? String(window.location.hash || '') : ''
  return /#\/(?:app\/)?community/.test(hash)
}
