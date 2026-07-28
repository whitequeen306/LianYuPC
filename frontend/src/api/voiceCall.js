import http from './httpCore'
import { prepareAudioUpload } from '@/utils/audioUpload'

export function voiceCallTurn(conversationId, blob, filename = 'voice.webm') {
  const prepared = prepareAudioUpload(blob, filename)
  const formData = new FormData()
  formData.append('file', prepared.blob, prepared.filename)
  return http.post(`/conversation/${conversationId}/voice-call/turn`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    skipGlobalError: true,
    timeout: 120000,
  })
}
