/**
 * httpCore unwraps Result.data on success, but some callers still read .data.*.
 * Accept both shapes so voice features keep working if response shape drifts.
 */
export function pickAsrText(res) {
  if (res == null) return ''
  if (typeof res === 'string') return res.trim()
  const text = res.text ?? res.data?.text
  return String(text || '').trim()
}

export function pickVoiceCallPayload(res) {
  if (!res || typeof res !== 'object') {
    return { userText: '', replyText: '', audioBase64: '', audioMimeType: '' }
  }
  const raw = res.replyText != null || res.userText != null ? res : (res.data || {})
  return {
    userText: String(raw.userText || '').trim(),
    replyText: String(raw.replyText || '').trim(),
    audioBase64: raw.audioBase64 || '',
    audioMimeType: raw.audioMimeType || 'audio/wav',
    userMessageId: raw.userMessageId,
    replyMessageId: raw.replyMessageId,
  }
}

/**
 * Reveal text character-by-character. Caller updates UI via onUpdate(partial).
 */
export async function typewriteText(fullText, { onUpdate, signal, charDelayMs = 26 } = {}) {
  const text = String(fullText || '')
  if (!text) {
    onUpdate?.('')
    return ''
  }
  let shown = ''
  for (const ch of text) {
    if (signal?.aborted) break
    shown += ch
    onUpdate?.(shown)
    await new Promise((resolve) => setTimeout(resolve, Math.max(8, charDelayMs)))
  }
  onUpdate?.(shown)
  return shown
}
