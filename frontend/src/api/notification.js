import http from './index'

export function getUnreadCount() {
  return http.get('/notifications/unread-count')
}

export function listNotifications(params = {}) {
  return http.get('/notifications', { params })
}

export function markNotificationsRead(payload = { all: true }) {
  return http.post('/notifications/mark-read', payload)
}

export function getPushPublicKey() {
  return http.get('/notifications/push/public-key')
}

export function subscribePush(payload) {
  return http.post('/notifications/push/subscribe', payload)
}

export function unsubscribePush(endpoint) {
  return http.post('/notifications/push/unsubscribe', { endpoint })
}
