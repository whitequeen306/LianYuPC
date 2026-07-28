import http from './httpCore'
import { prepareAudioUpload } from '@/utils/audioUpload'

export function voiceCallTurn(conversationId, blob, filename = 'voice.webm', options = {}) {
  const prepared = prepareAudioUpload(blob, filename)
  const formData = new FormData()
  formData.append('file', prepared.blob, prepared.filename)
  return http.post(`/conversation/${conversationId}/voice-call/turn`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    skipGlobalError: true,
    timeout: 120000,
    signal: options.signal,
  })
}

/**
 * @param {number} conversationId
 * @param {{ durationSeconds: number, turns: Array<{ userText?: string, replyText?: string }> }} payload
 */
export function voiceCallEnd(conversationId, payload) {
  return http.post(`/conversation/${conversationId}/voice-call/end`, {
    durationSeconds: Math.max(0, Number(payload?.durationSeconds) || 0),
    turns: Array.isArray(payload?.turns) ? payload.turns.slice(0, 40) : [],
  }, {
    skipGlobalError: true,
    timeout: 60000,
  })
}
