import { defineStore } from 'pinia'
import { ref } from 'vue'
import { Client } from '@stomp/stompjs'
import { ElNotification } from 'element-plus'
import { syncToken } from '@/utils/secureToken'
import {
  getUnreadCount,
  listNotifications,
  markNotificationsRead,
} from '@/api/notification'
import { buildWsUrl, isElectronRuntime } from '@/utils/runtime'
import { activeChatConversationId, requestActiveChatRefresh } from '@/composables/useActiveChatContext'
import { getElectronAPI } from '@/utils/electron'
import { navigateToNotification } from '@/composables/useNotificationNavigation'

/** 仅这些类型弹出站内通知；群聊发言与普通回复不弹窗 */
const IN_APP_POPUP_TYPES = new Set(['PROACTIVE_MESSAGE', 'MOMENT_NEW', 'MOMENT_COMMENT', 'DIARY_NEW'])

function shouldShowInAppPopup(type) {
  return IN_APP_POPUP_TYPES.has(type || '')
}

export const useNotificationsStore = defineStore('notifications', () => {
  const unreadCount = ref(0)
  const latest = ref([])
  const wsStatus = ref('disconnected')
  const browserNotifyPermission = ref(typeof Notification === 'undefined' ? 'unsupported' : Notification.permission)

  let stompClient = null
  let inited = false
  let lastSoundAt = 0
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
      } catch {}
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
    wsStatus.value = 'disconnected'
  }

  async function refreshUnreadCount() {
    try {
      const res = await getUnreadCount({ silent: true })
      unreadCount.value = Number(res?.unreadCount || 0)
    } catch {}
  }

  async function refreshLatest() {
    try {
      const list = await listNotifications({ limit: 20 }, { silent: true })
      latest.value = Array.isArray(list) ? list : []
    } catch {}
  }

  async function markAllRead() {
    try {
      await markNotificationsRead({ all: true })
      unreadCount.value = 0
      latest.value = latest.value.map(n => ({ ...n, read: true }))
    } catch {}
  }

  async function markConversationRead(conversationId) {
    if (!conversationId) return
    try {
      await markNotificationsRead({ conversationId }, { silent: true })
      latest.value = latest.value.map(n =>
        n.conversationId === conversationId ? { ...n, read: true } : n
      )
      await refreshUnreadCount()
    } catch {}
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
    } catch {}
  }

  function connectWebSocket() {
    const token = syncToken() || localStorage.getItem('lianyu-token')
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
          } catch {}
        })
        stompClient.subscribe('/user/queue/notification-unread', message => {
          try {
            const data = JSON.parse(message.body)
            unreadCount.value = Number(data?.unreadCount || unreadCount.value)
          } catch {}
        })
        resubscribeGroupChat()
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

  function onIncomingNotification(data) {
    if (!data) return
    const convId = data.conversationId != null ? Number(data.conversationId) : null
    const viewingSameChat =
      convId != null &&
      activeChatConversationId.value != null &&
      activeChatConversationId.value === convId

    if (viewingSameChat) {
      latest.value = [{ ...data, read: true }, ...latest.value].slice(0, 50)
      void markConversationRead(convId)
      requestActiveChatRefresh(convId)
      return
    }

    latest.value = [data, ...latest.value].slice(0, 50)
    if (!data.read) {
      unreadCount.value += 1
    }
    if (!shouldShowInAppPopup(data.type)) {
      return
    }
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

  function playSound() {
    const now = Date.now()
    if (now - lastSoundAt < 3000) return
    lastSoundAt = now
    try {
      const audioContext = new (window.AudioContext || window.webkitAudioContext)()
      const oscillator = audioContext.createOscillator()
      const gainNode = audioContext.createGain()
      oscillator.type = 'sine'
      oscillator.frequency.value = 880
      gainNode.gain.value = 0.03
      oscillator.connect(gainNode)
      gainNode.connect(audioContext.destination)
      oscillator.start()
      oscillator.stop(audioContext.currentTime + 0.08)
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
    unreadCount,
    latest,
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
