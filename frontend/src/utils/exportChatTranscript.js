import { isVoiceCallSummaryMessage, isVoiceCallTurnMessage } from '@/constants/voiceCallMarkers'
import { stripInnerThoughts } from '@/utils/innerThoughtFilter'

export const CHAT_EXPORT_PAGE_SIZE = 200
export const CHAT_EXPORT_MAX_PAGES = 250

const IMAGE_ONLY_PLACEHOLDERS = [
  '（用户发送了一张图片）',
  '（用户发了一张图片）',
  '用户发送了一张图片',
]

export function defaultChatExportFileName(characterName) {
  const name = String(characterName || '').trim()
  const stem = name ? `与${name}的聊天记录--恋语` : '聊天记录--恋语'
  return `${stem}.txt`
}

export function displayUserExportCaption(content) {
  if (!content) return ''
  let s = String(content)
  s = s.replace(/\n?（用户发了一张图片（[^）]*））/g, '')
  s = s.replace(/（用户发了一张图片）/g, '')
  s = s.replace(/（用户发送了一张图片）/g, '')
  s = s.replace(/用户发送了一张图片/g, '')
  return s.trim()
}

function flattenContent(text) {
  return String(text || '')
    .replace(/\r\n/g, '\n')
    .replace(/\r/g, '\n')
    .replace(/\n+/g, ' ')
    .replace(/[ \t]+/g, ' ')
    .trim()
}

function resolveMessageBody(msg, { includeInnerThoughts }) {
  const role = String(msg?.role || '').toLowerCase()
  let content = String(msg?.content || '')
  if (role === 'user' && msg?.imageUrl) {
    content = displayUserExportCaption(content)
  } else if (role !== 'user') {
    content = stripInnerThoughts(content, includeInnerThoughts)
  }
  if (IMAGE_ONLY_PLACEHOLDERS.includes(content)) content = ''
  content = flattenContent(content)
  if (content) return content
  if (msg?.imageUrl) return '（图片）'
  if (msg?.audioUrl && !isVoiceCallTurnMessage(msg) && !isVoiceCallSummaryMessage(msg)) {
    return '（语音）'
  }
  return ''
}

function roleLabel(msg, { userName, characterName }) {
  const role = String(msg?.role || '').toLowerCase()
  if (role === 'user') return userName || '用户'
  return characterName || '角色'
}

/** `{角色}：内容` 一行一条，按时间从早到晚。 */
export function formatChatTranscript(messages, options = {}) {
  const userName = String(options.userName || '').trim() || '用户'
  const characterName = String(options.characterName || '').trim() || '角色'
  const includeInnerThoughts = options.includeInnerThoughts === true
  const lines = []
  for (const msg of messages || []) {
    const role = String(msg?.role || '').toLowerCase()
    if (role !== 'user' && role !== 'assistant') continue
    if (isVoiceCallTurnMessage(msg) || isVoiceCallSummaryMessage(msg)) continue
    const body = resolveMessageBody(msg, { includeInnerThoughts })
    if (!body) continue
    const name = roleLabel(msg, { userName, characterName })
    lines.push(`{${name}}：${body}`)
  }
  return lines.join('\n')
}

function messageSeq(msg) {
  const n = Number(msg?.seq)
  return Number.isFinite(n) ? n : null
}

export async function fetchAllConversationMessages(conversationId, getMessages) {
  const id = Number(conversationId)
  if (!Number.isFinite(id) || id <= 0) return []
  if (typeof getMessages !== 'function') {
    throw new Error('getMessages is required')
  }

  const chunks = []
  let beforeSeq
  let lastBefore = null
  for (let page = 0; page < CHAT_EXPORT_MAX_PAGES; page += 1) {
    const params = { limit: CHAT_EXPORT_PAGE_SIZE }
    if (beforeSeq != null) params.beforeSeq = beforeSeq
    const result = await getMessages(id, params)
    const records = Array.isArray(result?.records) ? result.records : []
    chunks.push(records)
    if (!result?.hasMore) break
    const next = result.nextBeforeSeq
    if (next == null || next === lastBefore) break
    lastBefore = next
    beforeSeq = next
  }

  const seen = new Set()
  const merged = []
  for (const msg of chunks.reverse().flat()) {
    const key = msg?.id != null ? `id:${msg.id}` : `seq:${msg?.seq}`
    if (seen.has(key)) continue
    seen.add(key)
    merged.push(msg)
  }
  merged.sort((a, b) => {
    const as = messageSeq(a)
    const bs = messageSeq(b)
    if (as != null && bs != null && as !== bs) return as - bs
    return 0
  })
  return merged
}

export function downloadTextFile(filename, content) {
  const blob = new Blob([content], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.rel = 'noopener'
  a.click()
  URL.revokeObjectURL(url)
}

export async function saveChatTranscriptFile({ suggestedName, content, electronAPI }) {
  if (electronAPI && typeof electronAPI.exportTextFile === 'function') {
    return electronAPI.exportTextFile({ suggestedName, content })
  }
  if (typeof window !== 'undefined' && typeof window.showSaveFilePicker === 'function') {
    try {
      const handle = await window.showSaveFilePicker({
        suggestedName,
        startIn: 'desktop',
        types: [{ description: 'Text', accept: { 'text/plain': ['.txt'] } }],
      })
      const writable = await handle.createWritable()
      await writable.write(content)
      await writable.close()
      return { ok: true }
    } catch (e) {
      if (e?.name === 'AbortError') return { ok: false, reason: 'cancelled' }
    }
  }
  downloadTextFile(suggestedName, content)
  return { ok: true, downloaded: true }
}
