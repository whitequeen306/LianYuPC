import http from './index'
import { applyOutputLanguageHeaders } from '@/utils/outputLanguageHeader'
import { apiBasePath } from '@/utils/runtime'
import { syncToken } from '@/utils/secureToken'

export function listConversations(options = {}) {
  return http.get('/conversation', {
    skipGlobalError: options.silent === true
  })
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

// 清空会话消息但保留会话行（ID 不变）——供「清空聊天记录」调用，避免外部绑定（QQ 桥接）因 ID 变化而 404
export function clearConversationMessages(id) {
  return http.delete(`/conversation/${id}/messages`)
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

export function getMessages(id, params = {}, options = {}) {
  return http.get(`/conversation/${id}/messages`, {
    params,
    skipGlobalError: options.silent === true
  })
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
export async function sendMessageStream(id, data, signal) {
  const token = syncToken()
  const bodyText = JSON.stringify(data)
  const headers = applyOutputLanguageHeaders({
    'Content-Type': 'application/json',
    'lianyu-token': token || ''
  })
  return fetch(`${apiBasePath()}/conversation/${id}/messages/stream`, {
    method: 'POST',
    headers,
    body: bodyText,
    signal
  })
}
