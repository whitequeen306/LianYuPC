import http from './httpCore'

export function fetchPublicUserProfile(userId) {
  return http.get(`/users/${userId}/profile`)
}

export function fetchUserCommunityPosts(userId, params = {}) {
  return http.get(`/users/${userId}/community-posts`, { params })
}

export function getMyUserSettings() {
  return http.get('/auth/me/settings')
}

export function updateMyUserSettings(body) {
  return http.put('/auth/me/settings', body)
}
