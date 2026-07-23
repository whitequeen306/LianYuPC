import { defineStore } from 'pinia'
import { ref } from 'vue'
import { Client } from '@stomp/stompjs'
import { ElNotification } from 'element-plus'
import { syncToken } from '@/utils/secureToken'
import {
  listNotifications,
  markNotificationsRead,
} from '@/api/notification'
import { buildWsUrl, isElectronRuntime } from '@/utils/runtime'
import { isViewingCommunityPage, isViewingConversation, requestActiveChatRefresh } from '@/composables/useActiveChatContext'
import { pushChatMessageToast } from '@/composables/useInAppMessageToast'
import { getElectronAPI } from '@/utils/electron'
import { navigateToNotification } from '@/composables/useNotificationNavigation'
import { BELL_UNREAD_TYPES, countUnreadByTypes } from '@/constants/notificationTypes'
import { catchUpCommunityPush } from '@/api/community'

/** 动态 / 日记仍用 Element 站内通知 */
const FEED_POPUP_TYPES = new Set(['MOMENT_NEW', 'MOMENT_COMMENT', 'DIARY_NEW'])

/** 角色消息 + 社区动态：微信/QQ 式顶栏条 */
const CHAT_TOAST_TYPES = new Set(['PROACTIVE_MESSAGE', 'MESSAGE', 'COMMUNITY_POST_NEW'])

const COMMUNITY_NOTIFY_TYPES = new Set([
  'COMMUNITY_POST_NEW',
  'COMMUNITY_LIKE',
  'COMMUNITY_COMMENT',
])

function shouldShowFeedPopup(type) {
  return FEED_POPUP_TYPES.has(type || '')
}

function shouldShowChatToast(type) {
  return CHAT_TOAST_TYPES.has(type || '')
}

function isAppSurfaceVisible() {
  if (typeof document === 'undefined') return false
  if (document.visibilityState && document.visibilityState !== 'visible') return false
  if (document.hidden) return false
  return true
}

export const useNotificationsStore = defineStore('notifications', () => {
  /** 顶栏铃铛：动态 / 日记未读，不含单聊主动消息 */
  const bellUnreadCount = ref(0)
  const latest = ref([])
  const lastSyncError = ref(null)
  const wsStatus = ref('disconnected')
  const browserNotifyPermission = ref(typeof Notification === 'undefined' ? 'unsupported' : Notification.permission)

  let stompClient = null
  let inited = false
  let lastSoundAt = 0
  /** 提示音 AudioContext 单例：复用而非每次新建（浏览器对 AudioContext 实例数有限制，常开不关会泄漏
   *  导致后续播放静默失败）。见 issue #16；dispose() 时 close 释放，下次播放按需重建 */
  let audioContext = null
  let pendingGroupId = null
  /** @type {((body: object) => void) | null} */
  let groupMessageHandler = null
  let groupTopicSubscription = null

  function resubscribeGroupChat() {
    if (!stompClient?.connected || pendingGroupId == null || !groupMessageHandler) return
    if (groupTopicSubscription) {
      groupTopicSubscription.unsubscribe()
      groupTopicSubscription = null
    }
    groupTopicSubscription = stompClient.subscribe(`/topic/group/${pendingGroupId}`, (message) => {
      try {
        groupMessageHandler(JSON.parse(message.body))
      } catch (e) {
        console.warn('[notifications] groupMessage parse', e)
      }
    })
  }

  function subscribeGroupChat(groupId, handler) {
    pendingGroupId = groupId
    groupMessageHandler = handler
    if (!stompClient) {
      connectWebSocket()
    }
    resubscribeGroupChat()
  }

  function unsubscribeGroupChat() {
    pendingGroupId = null
    groupMessageHandler = null
    if (groupTopicSubscription) {
      groupTopicSubscription.unsubscribe()
      groupTopicSubscription = null
    }
  }

  function reconnectWebSocket() {
    if (stompClient) {
      stompClient.deactivate()
      stompClient = null
    }
    wsStatus.value = 'disconnected'
    connectWebSocket()
  }

  function publishGroupMessage(groupId, payload) {
    if (!stompClient?.connected || groupId == null) return false
    stompClient.publish({
      destination: `/app/group/${groupId}/send`,
      body: JSON.stringify(payload)
    })
    return true
  }

  async function init() {
    if (inited) return
    inited = true
    connectWebSocket()
    if (isElectronRuntime()) {
      void requestBrowserNotificationPermission()
    }
    void Promise.all([refreshUnreadCount(), refreshLatest()])
  }

  function dispose() {
    inited = false
    unsubscribeGroupChat()
    if (stompClient) {
      stompClient.deactivate()
      stompClient = null
    }
    closeAudioContext()
    wsStatus.value = 'disconnected'
  }

  async function refreshBellUnreadCount() {
    try {
      const list = await listNotifications({ unreadOnly: true, limit: 200 }, { silent: true })
      bellUnreadCount.value = countUnreadByTypes(list, BELL_UNREAD_TYPES)
      lastSyncError.value = null
    } catch (e) {
      lastSyncError.value = e
      console.warn('[notifications] refreshBellUnreadCount', e)
    }
  }

  async function refreshUnreadCount() {
    await refreshBellUnreadCount()
  }

  async function refreshLatest() {
    try {
      const list = await listNotifications({ limit: 20 }, { silent: true })
      latest.value = Array.isArray(list) ? list : []
      lastSyncError.value = null
    } catch (e) {
      lastSyncError.value = e
      console.warn('[notifications] refreshLatest', e)
    }
  }

  async function markAllRead() {
    try {
      await markNotificationsRead({ all: true })
      bellUnreadCount.value = 0
      latest.value = latest.value.map(n => ({ ...n, read: true }))
      lastSyncError.value = null
    } catch (e) {
      lastSyncError.value = e
      console.warn('[notifications] markAllRead', e)
    }
  }

  async function markConversationRead(conversationId) {
    if (!conversationId) return
    try {
      await markNotificationsRead({ conversationId }, { silent: true })
      latest.value = latest.value.map(n =>
        n.conversationId === conversationId ? { ...n, read: true } : n
      )
      await refreshUnreadCount()
      lastSyncError.value = null
    } catch (e) {
      lastSyncError.value = e
      console.warn('[notifications] markConversationRead', e)
    }
  }

  async function markNotificationsByIds(ids) {
    const idList = (ids || []).filter(id => id != null)
    if (!idList.length) return
    try {
      await markNotificationsRead({ ids: idList }, { silent: true })
      const idSet = new Set(idList)
      latest.value = latest.value.map(n =>
        idSet.has(n.id) ? { ...n, read: true } : n
      )
      await refreshUnreadCount()
      lastSyncError.value = null
    } catch (e) {
      lastSyncError.value = e
      console.warn('[notifications] markNotificationsByIds', e)
    }
  }

  function connectWebSocket() {
    const token = syncToken()
    if (!token) {
      wsStatus.value = 'disconnected'
      return
    }
    if (stompClient?.connected) {
      wsStatus.value = 'connected'
      resubscribeGroupChat()
      return
    }
    if (stompClient) {
      wsStatus.value = 'connecting'
      return
    }
    wsStatus.value = 'connecting'
    stompClient = new Client({
      brokerURL: buildBrokerUrl(),
      connectHeaders: { token },
      reconnectDelay: 5000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      onConnect: () => {
        wsStatus.value = 'connected'
        stompClient.subscribe('/user/queue/notifications', message => {
          try {
            const data = JSON.parse(message.body)
            onIncomingNotification(data)
          } catch (e) {
            console.warn('[notifications] parse /user/queue/notifications',
              message.body?.slice?.(0, 120), e)
          }
        })
        stompClient.subscribe('/user/queue/notification-unread', message => {
          try {
            JSON.parse(message.body)
            void refreshBellUnreadCount()
          } catch (e) {
            console.warn('[notifications] parse /user/queue/notification-unread',
              message.body?.slice?.(0, 120), e)
          }
        })
        resubscribeGroupChat()
        void runCommunityPushCatchup()
      },
      onDisconnect: () => {
        wsStatus.value = 'disconnected'
      },
      onWebSocketError: () => {
        wsStatus.value = 'disconnected'
      },
      onStompError: (frame) => {
        console.warn('STOMP error:', frame.headers?.message)
        wsStatus.value = 'disconnected'
      }
    })
    stompClient.activate()
  }

  async function runCommunityPushCatchup() {
    try {
      await catchUpCommunityPush()
    } catch (e) {
      console.warn('[notifications] community push catch-up', e)
    }
  }

  function onIncomingNotification(data) {
    if (!data) return
    const convId = data.conversationId != null ? Number(data.conversationId) : null
    const type = data.type || ''

    // 社区通知：postId 复用 conversationId 字段，不能走单聊会话匹配
    if (COMMUNITY_NOTIFY_TYPES.has(type)) {
      if (type === 'COMMUNITY_POST_NEW' && isViewingCommunityPage()) {
        latest.value = [{ ...data, read: true }, ...latest.value].slice(0, 50)
        if (data.id != null) void markNotificationsByIds([data.id])
        return
      }
    } else if (convId != null && isViewingConversation(convId)) {
      // 正在看该会话（含广场刚加入后的破冰消息）→ 不弹窗，只静默刷新
      latest.value = [{ ...data, read: true }, ...latest.value].slice(0, 50)
      void markConversationRead(convId)
      requestActiveChatRefresh(convId)
      return
    }

    latest.value = [data, ...latest.value].slice(0, 50)
    if (!data.read && BELL_UNREAD_TYPES.has(type)) {
      bellUnreadCount.value += 1
    }

    if (shouldShowChatToast(type) && isAppSurfaceVisible()) {
      pushChatMessageToast({
        characterName: extractCharacterName(data.title),
        preview: data.contentPreview || '',
        createdAt: data.createdAt,
        conversationId: convId,
        characterId: data.characterId != null ? Number(data.characterId) : null,
        raw: data,
      })
      playSound()
    } else if (shouldShowFeedPopup(type)) {
      ElNotification({
        title: data.title || '新消息',
        message: data.contentPreview || '',
        type: 'info',
        duration: 4500,
        onClick: () => {
          void navigateToNotification(data)
        }
      })
      playSound()
    }

    // Desktop/OS toast only when main window is backgrounded (gated in main process).
    notifyProactiveDesktopIfNeeded(data)
  }

  function extractCharacterName(title) {
    if (!title) return '她'
    const cleaned = String(title).trim()
    const zhMatch = cleaned.match(/^(.+?)\s*(给你发来消息|在群聊中发言|发布了|评论了|写了一篇新日记|写了新日记)/)
    if (zhMatch?.[1]) return zhMatch[1].trim()
    const enMatch = cleaned.match(/^(.+?)\s+(sent you|posted|commented)/i)
    if (enMatch?.[1]) return enMatch[1].trim()
    return cleaned.split(/\s+/)[0] || '她'
  }

  function notifyProactiveDesktopIfNeeded(data) {
    if ((data?.type || '') !== 'PROACTIVE_MESSAGE') return
    const electronAPI = getElectronAPI()
    if (!electronAPI?.notifyProactiveMessage) return
    void electronAPI.notifyProactiveMessage({
      characterName: extractCharacterName(data?.title),
      preview: data?.contentPreview || '',
      conversationId: data?.conversationId ?? null,
      characterId: data?.characterId ?? null,
    })
  }

  /** 懒建提示音 AudioContext 单例：浏览器对 AudioContext 实例数有限制（约 6 个），每次新建且不 close 会
   *  泄漏，导致后续播放静默失败（issue #16）。单例化后全生命周期复用一个，dispose 时显式 close。 */
  function getAudioContext() {
    if (audioContext) return audioContext
    const Ctor = window.AudioContext || window.webkitAudioContext
    if (!Ctor) return null
    try {
      audioContext = new Ctor()
    } catch {
      audioContext = null
    }
    return audioContext
  }

  function closeAudioContext() {
    if (!audioContext) return
    try {
      audioContext.close()
    } catch {}
    audioContext = null
  }

  function playSound() {
    const now = Date.now()
    if (now - lastSoundAt < 3000) return
    lastSoundAt = now
    try {
      const ctx = getAudioContext()
      if (!ctx) return
      // 浏览器可能在用户手势前挂起 AudioContext；尝试恢复（失败则本次静默跳过）
      if (ctx.state === 'suspended') {
        void ctx.resume().catch(() => {})
      }
      const oscillator = ctx.createOscillator()
      const gainNode = ctx.createGain()
      oscillator.type = 'sine'
      oscillator.frequency.value = 880
      gainNode.gain.value = 0.03
      oscillator.connect(gainNode)
      gainNode.connect(ctx.destination)
      oscillator.start()
      oscillator.stop(ctx.currentTime + 0.08)
    } catch {}
  }

  async function requestBrowserNotificationPermission() {
    if (typeof Notification === 'undefined') {
      browserNotifyPermission.value = 'unsupported'
      return browserNotifyPermission.value
    }
    if (Notification.permission === 'granted') {
      browserNotifyPermission.value = 'granted'
      return 'granted'
    }
    const result = await Notification.requestPermission()
    browserNotifyPermission.value = result
    return result
  }

  function buildBrokerUrl() {
    return buildWsUrl()
  }

  return {
    bellUnreadCount,
    latest,
    lastSyncError,
    wsStatus,
    browserNotifyPermission,
    init,
    dispose,
    refreshUnreadCount,
    refreshLatest,
    markAllRead,
    markConversationRead,
    markNotificationsByIds,
    subscribeGroupChat,
    unsubscribeGroupChat,
    reconnectWebSocket,
    publishGroupMessage,
    requestBrowserNotificationPermission,
  }
})
