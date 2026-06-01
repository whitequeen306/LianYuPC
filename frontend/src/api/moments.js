import http from './index'

export function fetchMomentsFeed(params = {}) {
  return http.get('/moments', { params })
}

export function fetchMomentsUnreadCount() {
  return http.get('/moments/unread-count')
}

export function markMomentsSeen() {
  return http.post('/moments/mark-seen')
}

export function fetchMomentComments(postId, params = {}) {
  return http.get(`/moments/${postId}/comments`, { params })
}

export function addMomentComment(postId, body) {
  return http.post(`/moments/${postId}/comments`, body)
}
