import { defineStore } from 'pinia'
import { ref } from 'vue'
import { Client } from '@stomp/stompjs'
import { ElNotification } from 'element-plus'
import {
  getUnreadCount,
  listNotifications,
  markNotificationsRead,
  getPushPublicKey,
  subscribePush,
  unsubscribePush
} from '@/api/notification'

export const useNotificationsStore = defineStore('notifications', () => {
  const unreadCount = ref(0)
  const latest = ref([])
  const wsStatus = ref('disconnected')
  const browserNotifyPermission = ref(typeof Notification === 'undefined' ? 'unsupported' : Notification.permission)
  const pushEnabled = ref(false)

  let stompClient = null
  let inited = false
  let lastSoundAt = 0

  async function init() {
    if (inited) return
    inited = true
    await refreshUnreadCount()
    await refreshLatest()
    connectWebSocket()
    await syncPushState()
  }

  function dispose() {
    inited = false
    if (stompClient) {
      stompClient.deactivate()
      stompClient = null
    }
    wsStatus.value = 'disconnected'
  }

  async function refreshUnreadCount() {
    try {
      const res = await getUnreadCount()
      unreadCount.value = Number(res?.unreadCount || 0)
    } catch {}
  }

  async function refreshLatest() {
    try {
      const list = await listNotifications({ limit: 20 })
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
      await markNotificationsRead({ conversationId })
      latest.value = latest.value.map(n =>
        n.conversationId === conversationId ? { ...n, read: true } : n
      )
      await refreshUnreadCount()
    } catch {}
  }

  function connectWebSocket() {
    if (stompClient) return
    const token = localStorage.getItem('lianyu-token')
    if (!token) return
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
      },
      onDisconnect: () => {
        wsStatus.value = 'disconnected'
      },
      onWebSocketError: () => {
        wsStatus.value = 'disconnected'
      },
      onStompError: () => {
        wsStatus.value = 'disconnected'
      }
    })
    stompClient.activate()
  }

  function onIncomingNotification(data) {
    if (!data) return
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
    notifyBrowserIfHidden(data)
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

  function notifyBrowserIfHidden(data) {
    if (typeof document === 'undefined' || document.visibilityState !== 'hidden') return
    if (typeof Notification === 'undefined' || Notification.permission !== 'granted') return
    const notification = new Notification(data.title || '新消息', {
      body: data.contentPreview || '',
      tag: `conv-${data.conversationId || 'general'}`,
      renotify: false
    })
    notification.onclick = () => {
      window.focus()
      if (data.conversationId) {
        window.location.hash = `#/chat/${data.conversationId}`
      }
    }
  }

  async function requestBrowserNotificationPermission() {
    if (typeof Notification === 'undefined') {
      browserNotifyPermission.value = 'unsupported'
      return browserNotifyPermission.value
    }
    const result = await Notification.requestPermission()
    browserNotifyPermission.value = result
    return result
  }

  async function enablePush() {
    const permission = await requestBrowserNotificationPermission()
    if (permission !== 'granted') return false
    if (!('serviceWorker' in navigator) || !('PushManager' in window)) {
      return false
    }
    const reg = await navigator.serviceWorker.register('/sw.js')
    const keyRes = await getPushPublicKey()
    const publicKey = keyRes?.publicKey
    if (!publicKey) {
      return false
    }
    const sub = await reg.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: urlBase64ToUint8Array(publicKey)
    })
    const payload = {
      endpoint: sub.endpoint,
      p256dh: arrayBufferToBase64(sub.getKey('p256dh')),
      auth: arrayBufferToBase64(sub.getKey('auth')),
      userAgent: navigator.userAgent
    }
    await subscribePush(payload)
    pushEnabled.value = true
    return true
  }

  async function disablePush() {
    if (!('serviceWorker' in navigator)) return
    const reg = await navigator.serviceWorker.ready
    const sub = await reg.pushManager.getSubscription()
    if (sub) {
      await unsubscribePush(sub.endpoint)
      await sub.unsubscribe()
    }
    pushEnabled.value = false
  }

  async function syncPushState() {
    if (!('serviceWorker' in navigator)) {
      pushEnabled.value = false
      return
    }
    try {
      const reg = await navigator.serviceWorker.getRegistration('/sw.js')
      if (!reg) {
        pushEnabled.value = false
        return
      }
      const sub = await reg.pushManager.getSubscription()
      pushEnabled.value = !!sub
    } catch {
      pushEnabled.value = false
    }
  }

  function buildBrokerUrl() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    return `${protocol}//${window.location.host}/ws`
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
