import http from './httpCore'

export function fetchCommunityFeed(params = {}) {
  return http.get('/community/feed', { params })
}

export function createCommunityPost(body) {
  return http.post('/community/posts', body)
}

export function deleteCommunityPost(postId) {
  return http.delete(`/community/posts/${postId}`)
}

export function toggleCommunityLike(postId) {
  return http.post(`/community/posts/${postId}/like`)
}

export function fetchCommunityComments(postId, params = {}) {
  return http.get(`/community/posts/${postId}/comments`, { params })
}

export function addCommunityComment(postId, body) {
  return http.post(`/community/posts/${postId}/comments`, body)
}

export function uploadCommunityImage(file) {
  const formData = new FormData()
  formData.append('file', file)
  return http.post('/community/images', formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}
