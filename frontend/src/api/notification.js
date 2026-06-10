import http from './index'

export function getUnreadCount(options = {}) {
  return http.get('/notifications/unread-count', {
    skipGlobalError: options.silent === true
  })
}

export function listNotifications(params = {}, options = {}) {
  return http.get('/notifications', {
    params,
    skipGlobalError: options.silent === true,
    ...(options.timeout != null ? { timeout: options.timeout } : {})
  })
}

export function markNotificationsRead(payload = { all: true }, options = {}) {
  return http.post('/notifications/mark-read', payload, {
    skipGlobalError: options.silent === true
  })
}
