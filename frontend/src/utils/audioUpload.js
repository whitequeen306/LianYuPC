/**
 * Normalize MediaRecorder blob types for multipart upload.
 * Chrome/Electron often set type to "audio/webm;codecs=opus" which some servers reject.
 */
export function prepareAudioUpload(blob, fallbackName = 'voice.webm') {
  const rawType = (blob && blob.type) ? String(blob.type) : 'audio/webm'
  const baseType = rawType.split(';')[0].trim().toLowerCase() || 'audio/webm'
  const ext = extensionForAudioMime(baseType)
  const filename = fallbackName.includes('.')
    ? fallbackName.replace(/\.[^.]+$/, `.${ext}`)
    : `${fallbackName}.${ext}`
  const cleanBlob = blob && blob.type === baseType
    ? blob
    : new Blob([blob], { type: baseType })
  return { blob: cleanBlob, filename, contentType: baseType }
}

export function extensionForAudioMime(mime) {
  const m = String(mime || '').toLowerCase()
  if (m.includes('ogg')) return 'ogg'
  if (m === 'audio/opus') return 'opus'
  if (m.includes('mp4') || m.includes('m4a')) return 'm4a'
  if (m.includes('mpeg') || m.includes('mp3')) return 'mp3'
  if (m.includes('wav')) return 'wav'
  return 'webm'
}
