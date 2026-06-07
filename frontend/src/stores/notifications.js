import { defineStore } from 'pinia'
import { ref } from 'vue'
import { Client } from '@stomp/stompjs'
import { ElNotification } from 'element-plus'
import {
  getUnreadCount,
  listNotifications,
  markNotificationsRead,
  getPushPublicKey,
  getPushStatus,
  subscribePush,
  unsubscribePush
} from '@/api/notification'
import { buildWsUrl, canUseWebPush, isElectronRuntime, resolveServiceWorkerUrl } from '@/utils/runtime'
import { activeChatConversationId, requestActiveChatRefresh } from '@/composables/useActiveChatContext'
import { getElectronAPI } from '@/utils/electron'

const DESKTOP_PUSH_KEY = 'lianyu-desktop-push-enabled'
const PUSH_OPT_OUT_KEY = 'lianyu-push-opt-out'

export const useNotificationsStore = defineStore('notifications', () => {
  const unreadCount = ref(0)
  const latest = ref([])
  const wsStatus = ref('disconnected')
  const browserNotifyPermission = ref(typeof Notification === 'undefined' ? 'unsupported' : Notification.permission)
  const pushEnabled = ref(false)

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
    await syncPushState()
    await ensurePushRegistered()
    void Promise.all([refreshUnreadCount(), refreshLatest()])
  }

  async function ensurePushRegistered() {
    if (localStorage.getItem(PUSH_OPT_OUT_KEY) === '1') return
    const first = await enablePush({ silent: true })
    if (isElectronRuntime()) {
      return
    }
    if (!canUseWebPush()) {
      return
    }
    try {
      const status = await getPushStatus({ silent: true })
      if (status?.serverConfigured && !status?.subscribed) {
        await enablePush({ silent: true })
      }
    } catch {
      if (!first?.ok && first?.reason === 'no_public_key') {
        return
      }
    }
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

  function connectWebSocket() {
    const token = localStorage.getItem('lianyu-token')
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
    ElNotification({
      title: data.title || '新消息',
      message: data.contentPreview || '',
      type: 'info',
      duration: 4500
    })
    playSound()
    notifyIfPushEnabled(data)
    pulseLauncherIfNeeded(data)
  }

  function extractCharacterName(title) {
    if (!title) return '她'
    const cleaned = String(title).trim()
    const zhMatch = cleaned.match(/^(.+?)\s*(给你发来消息|在群聊中发言|发布了|评论了)/)
    if (zhMatch?.[1]) return zhMatch[1].trim()
    const enMatch = cleaned.match(/^(.+?)\s+(sent you|posted|commented)/i)
    if (enMatch?.[1]) return enMatch[1].trim()
    return cleaned.split(/\s+/)[0] || '她'
  }

  function pulseLauncherIfNeeded(data) {
    const electronAPI = getElectronAPI()
    if (!electronAPI?.notifyLauncherNewMessage) return
    const type = data?.type || ''
    if (type.startsWith('MOMENT_')) return
    void electronAPI.notifyLauncherNewMessage({
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

  function buildNotificationTarget(conversationId) {
    if (!conversationId) return '#/app'
    return `#/app/chat/${conversationId}`
  }

  function shouldShowNativeNotification() {
    if (typeof document === 'undefined') return false
    return document.visibilityState === 'hidden' || !document.hasFocus()
  }

  function notifyIfPushEnabled(data) {
    if (!pushEnabled.value) return
    if (typeof Notification === 'undefined' || Notification.permission !== 'granted') return
    if (!shouldShowNativeNotification()) return

    const notification = new Notification(data.title || '新消息', {
      body: data.contentPreview || '',
      tag: `conv-${data.conversationId || 'general'}`,
      renotify: false
    })
    notification.onclick = () => {
      window.focus()
      const hash = buildNotificationTarget(data.conversationId)
      const electronAPI = getElectronAPI()
      if (electronAPI?.openMainWindow) {
        electronAPI.openMainWindow(hash)
        return
      }
      window.location.hash = hash
    }
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

  async function enablePush(options = {}) {
    const { silent = false } = options
    const permission = await requestBrowserNotificationPermission()
    if (permission !== 'granted') {
      return { ok: false, reason: 'permission_denied' }
    }

    if (isElectronRuntime()) {
      localStorage.setItem(DESKTOP_PUSH_KEY, '1')
      localStorage.removeItem(PUSH_OPT_OUT_KEY)
      pushEnabled.value = true
      return { ok: true, mode: 'desktop' }
    }

    if (!canUseWebPush()) {
      return { ok: false, reason: 'unsupported' }
    }

    try {
      const reg = await navigator.serviceWorker.register(resolveServiceWorkerUrl())
      await navigator.serviceWorker.ready
      const keyRes = await getPushPublicKey()
      const publicKey = keyRes?.publicKey
      if (!publicKey) {
        return { ok: false, reason: 'no_public_key' }
      }
      let sub = await reg.pushManager.getSubscription()
      if (!sub) {
        sub = await reg.pushManager.subscribe({
          userVisibleOnly: true,
          applicationServerKey: urlBase64ToUint8Array(publicKey)
        })
      }
      await subscribePush({
        endpoint: sub.endpoint,
        p256dh: arrayBufferToBase64(sub.getKey('p256dh')),
        auth: arrayBufferToBase64(sub.getKey('auth')),
        userAgent: navigator.userAgent
      })
      localStorage.removeItem(PUSH_OPT_OUT_KEY)
      pushEnabled.value = true
      return { ok: true, mode: 'webpush' }
    } catch (e) {
      if (!silent) {
        console.warn('enablePush failed:', e)
      }
      return { ok: false, reason: 'subscribe_failed' }
    }
  }

  async function disablePush() {
    localStorage.setItem(PUSH_OPT_OUT_KEY, '1')

    if (isElectronRuntime()) {
      localStorage.removeItem(DESKTOP_PUSH_KEY)
      pushEnabled.value = false
      return { ok: true }
    }

    if (!('serviceWorker' in navigator)) {
      pushEnabled.value = false
      return { ok: true }
    }

    try {
      const regs = await navigator.serviceWorker.getRegistrations()
      for (const reg of regs) {
        const sub = await reg.pushManager.getSubscription()
        if (!sub) continue
        await unsubscribePush(sub.endpoint)
        await sub.unsubscribe()
      }
    } catch (e) {
      console.warn('disablePush failed:', e)
    }
    pushEnabled.value = false
    return { ok: true }
  }

  async function syncPushState() {
    if (isElectronRuntime()) {
      pushEnabled.value =
        localStorage.getItem(DESKTOP_PUSH_KEY) === '1'
        && Notification.permission === 'granted'
      return
    }

    if (!('serviceWorker' in navigator)) {
      pushEnabled.value = false
      return
    }

    try {
      const regs = await navigator.serviceWorker.getRegistrations()
      for (const reg of regs) {
        const sub = await reg.pushManager.getSubscription()
        if (sub) {
          pushEnabled.value = true
          return
        }
      }
      pushEnabled.value = false
    } catch {
      pushEnabled.value = false
    }
  }

  function buildBrokerUrl() {
    return buildWsUrl()
  }

  return {
    unreadCount,
    latest,
    wsStatus,
    browserNotifyPermission,
    pushEnabled,
    init,
    dispose,
    refreshUnreadCount,
    refreshLatest,
    markAllRead,
    markConversationRead,
    subscribeGroupChat,
    unsubscribeGroupChat,
    reconnectWebSocket,
    publishGroupMessage,
    requestBrowserNotificationPermission,
    enablePush,
    disablePush
  }
})

function urlBase64ToUint8Array(base64String) {
  const padding = '='.repeat((4 - (base64String.length % 4)) % 4)
  const base64 = (base64String + padding).replace(/\-/g, '+').replace(/_/g, '/')
  const rawData = atob(base64)
  const outputArray = new Uint8Array(rawData.length)
  for (let i = 0; i < rawData.length; ++i) {
    outputArray[i] = rawData.charCodeAt(i)
  }
  return outputArray
}

function arrayBufferToBase64(buffer) {
  if (!buffer) return ''
  const bytes = new Uint8Array(buffer)
  let binary = ''
  for (let i = 0; i < bytes.byteLength; i++) {
    binary += String.fromCharCode(bytes[i])
  }
  return btoa(binary)
}
