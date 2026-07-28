import http from './httpCore'
import { prepareAudioUpload } from '@/utils/audioUpload'

export function transcribeAudio(blob, filename = 'voice.webm') {
  const prepared = prepareAudioUpload(blob, filename)
  const formData = new FormData()
  formData.append('file', prepared.blob, prepared.filename)
  return http.post('/asr/transcribe', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    skipGlobalError: true,
  })
}
