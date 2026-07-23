import { stripInnerThoughts } from '@/utils/innerThoughtFilter'

export const COMMUNITY_SHARE_DRAFT_KEY = 'communityShareDraft'
export const COMMUNITY_SHARE_MAX_MESSAGES = 20
export const COMMUNITY_SHARE_MAX_CONTENT = 1000
const IMAGE_ONLY_PLACEHOLDER = '（用户发送了一张图片）'

export function saveCommunityShareDraft(draft) {
  sessionStorage.setItem(COMMUNITY_SHARE_DRAFT_KEY, JSON.stringify(draft))
}

export function consumeCommunityShareDraft() {
  const raw = sessionStorage.getItem(COMMUNITY_SHARE_DRAFT_KEY)
  if (!raw) return null
  sessionStorage.removeItem(COMMUNITY_SHARE_DRAFT_KEY)
  try {
    return JSON.parse(raw)
  } catch {
    return null
  }
}

export function isShareSelectableMessage(item) {
  return item?.type === 'message'
    && item.id != null
    && Number.isFinite(Number(item.id))
    && !item._streamGroupId
}

function formatMessageLine(msg, { characterName, userLabel }) {
  const label = msg.role === 'user' ? userLabel : (characterName || '角色')
  if (msg.imageUrl) {
    return `${label}：[图片]`
  }
  if (msg.audioUrl) {
    const voiceText = stripInnerThoughts(msg.content || '', false).trim()
    return voiceText ? `${label}：${voiceText}` : `${label}：[语音]`
  }
  let text = msg.role === 'assistant'
    ? stripInnerThoughts(msg.content || '', false).trim()
    : String(msg.content || '').trim()
  if (msg.imageUrl && text === IMAGE_ONLY_PLACEHOLDER) {
    text = '[图片]'
  }
  if (!text) return null
  return `${label}：${text}`
}

export function formatChatMessagesForCommunityShare(messages, options = {}) {
  const {
    characterName = '角色',
    userLabel = '我',
    maxMessages = COMMUNITY_SHARE_MAX_MESSAGES,
    maxContent = COMMUNITY_SHARE_MAX_CONTENT
  } = options

  const sorted = [...messages]
    .filter((msg) => msg?.id != null && Number.isFinite(Number(msg.id)))
    .sort((a, b) => Number(a.id) - Number(b.id))
    .slice(-maxMessages)

  const lines = sorted
    .map((msg) => formatMessageLine(msg, { characterName, userLabel }))
    .filter(Boolean)

  const content = lines.join('\n')
  if (content.length <= maxContent) {
    return content
  }
  return content.slice(0, maxContent)
}
