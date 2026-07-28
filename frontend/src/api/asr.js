import http from './httpCore'

export function transcribeAudio(blob, filename = 'voice.webm') {
  const formData = new FormData()
  formData.append('file', blob, filename)
  return http.post('/asr/transcribe', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    skipGlobalError: true,
  })
}
