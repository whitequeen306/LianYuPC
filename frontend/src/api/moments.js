import http from './index'

function requestConfig(options = {}) {
  return options.silent === true ? { skipGlobalError: true } : {}
}

export function fetchMomentsFeed(params = {}, options = {}) {
  return http.get('/moments', { params, ...requestConfig(options) })
}

export function fetchMomentsUnreadCount(options = {}) {
  return http.get('/moments/unread-count', requestConfig(options))
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

export function createMomentPost(body) {
  return http.post('/moments', body)
}
