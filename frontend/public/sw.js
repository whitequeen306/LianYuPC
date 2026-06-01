self.addEventListener('push', event => {
  if (!event.data) return
  let payload = {}
  try {
    payload = event.data.json()
  } catch {
    payload = { title: '新消息', body: event.data.text() }
  }
  const title = payload.title || '新消息'
  const options = {
    body: payload.body || '',
    tag: payload.conversationId ? `conv-${payload.conversationId}` : 'general',
    data: {
      url: payload.url || '#/'
    }
  }
  event.waitUntil(self.registration.showNotification(title, options))
})

self.addEventListener('notificationclick', event => {
  event.notification.close()
  const url = event.notification.data?.url || '#/'
  event.waitUntil(clients.openWindow(url))
})
