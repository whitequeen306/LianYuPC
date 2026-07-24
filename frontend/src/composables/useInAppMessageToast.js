import { ref } from 'vue'

/** @typedef {{
 *   id: string,
 *   characterName: string,
 *   preview: string,
 *   timeLabel: string,
 *   conversationId: number|null,
 *   characterId: number|null,
 *   avatarUrl: string|null,
 *   avatarThumbUrl: string|null,
 *   raw: object,
 * }} ChatMessageToast */

const toasts = ref(/** @type {ChatMessageToast[]} */ ([]))
const MAX_TOASTS = 3
const AUTO_DISMISS_MS = 4800

/** @type {Map<string, ReturnType<typeof setTimeout>>} */
const timers = new Map()

function formatToastTime(value) {
  const d = value ? new Date(value) : new Date()
  if (Number.isNaN(d.getTime())) {
    const now = new Date()
    return `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`
  }
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

function sanitizePreview(text) {
  return String(text || '')
    .replace(/\s+/g, ' ')
    .trim()
}

/**
 * Push a WeChat/QQ-style top banner for an out-of-focus character message.
 * @param {{ characterName?: string, preview?: string, createdAt?: string, conversationId?: number|null, characterId?: number|null, avatarUrl?: string|null, avatarThumbUrl?: string|null, raw?: object }} payload
 */
export function pushChatMessageToast(payload = {}) {
  const id = `toast-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
  const avatarRaw = payload.avatarUrl != null ? String(payload.avatarUrl).trim() : ''
  const thumbRaw = payload.avatarThumbUrl != null ? String(payload.avatarThumbUrl).trim() : ''
  const item = {
    id,
    characterName: String(payload.characterName || '角色').trim() || '角色',
    preview: sanitizePreview(payload.preview) || '发来一条消息',
    timeLabel: formatToastTime(payload.createdAt),
    conversationId: payload.conversationId != null ? Number(payload.conversationId) : null,
    characterId: payload.characterId != null ? Number(payload.characterId) : null,
    avatarUrl: avatarRaw || null,
    // actorAvatarUrl from backend is usually thumb-first; keep both slots for tier fallback.
    avatarThumbUrl: thumbRaw || avatarRaw || null,
    raw: payload.raw || payload,
  }
  toasts.value = [item, ...toasts.value].slice(0, MAX_TOASTS)
  const timer = setTimeout(() => dismissChatMessageToast(id), AUTO_DISMISS_MS)
  timers.set(id, timer)
  return id
}

export function dismissChatMessageToast(id) {
  const timer = timers.get(id)
  if (timer) {
    clearTimeout(timer)
    timers.delete(id)
  }
  toasts.value = toasts.value.filter((t) => t.id !== id)
}

export function clearChatMessageToasts() {
  for (const timer of timers.values()) clearTimeout(timer)
  timers.clear()
  toasts.value = []
}

export function useInAppMessageToast() {
  return {
    toasts,
    pushChatMessageToast,
    dismissChatMessageToast,
    clearChatMessageToasts,
  }
}
