import http from './index'

export function listConversations() {
  return http.get('/conversation')
}

export function getConversation(id) {
  return http.get(`/conversation/${id}`)
}

export function createConversation(data) {
  return http.post('/conversation', data)
}

export function deleteConversation(id) {
  return http.delete(`/conversation/${id}`)
}

export function sendMessage(id, data) {
  return http.post(`/conversation/${id}/messages`, data)
}

export function uploadChatImage(file) {
  const formData = new FormData()
  formData.append('file', file)
  return http.post('/conversation/chat-image', formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

export function getMessages(id, params = {}) {
  return http.get(`/conversation/${id}/messages`, { params })
}

export function createGroupConversation(data) {
  return http.post('/conversation/group', data)
}

export function getGroupMembers(id) {
  return http.get(`/conversation/group/${id}/members`)
}

export function updateGroupTitle(id, title) {
  return http.patch(`/conversation/group/${id}/title`, { title })
}

// Non-Axios SSE — fetch API handles streams better
export function sendMessageStream(id, data) {
  const token = localStorage.getItem('lianyu-token')
  return fetch(`/api/conversation/${id}/messages/stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'lianyu-token': token || ''
    },
    body: JSON.stringify(data)
  })
}
