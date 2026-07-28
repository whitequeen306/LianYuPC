import http from './httpCore'

export function voiceCallTurn(conversationId, blob, filename = 'voice.webm') {
  const formData = new FormData()
  formData.append('file', blob, filename)
  return http.post(`/conversation/${conversationId}/voice-call/turn`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    skipGlobalError: true,
    timeout: 120000,
  })
}
