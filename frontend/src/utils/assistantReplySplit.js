/**
 * 与后端 {@code AssistantReplySplitter} 一致：按换行拆成多条气泡。
 */
import {
  isInsideParentheses,
  normalizeAssistantContent,
  rebalanceSplitPieces,
  stripLeadingOrphanCloses
} from '@/utils/innerThoughtFilter'

const SENTENCE_SPLIT_MIN_CHARS = 40
const CJK_SENTENCE_BOUNDARY = /(?<=[。！？!?])(?=[^。！？!?\s])/u
const EN_SENTENCE_BOUNDARY = /(?<=[.!?])\s+/

function splitWithPattern(text, pattern) {
  const parts = text.split(pattern).map(s => s.trim()).filter(Boolean)
  return parts.length ? parts : [text.trim()]
}

function splitBySentenceBoundary(text) {
  const pieces = []
  let start = 0
  let i = 0
  while (i < text.length) {
    const ch = text[i]
    let splitAt = -1
    if (/[。！？!?]/.test(ch) && i + 1 < text.length && !/\s/.test(text[i + 1])) {
      if (!isInsideParentheses(text, i + 1)) splitAt = i + 1
    } else if (/[.!?]/.test(ch)) {
      let j = i + 1
      while (j < text.length && /\s/.test(text[j])) j += 1
      if (j > i + 1 && !isInsideParentheses(text, i + 1)) splitAt = j
    }
    if (splitAt > start) {
      const part = text.slice(start, splitAt).trim()
      if (part) pieces.push(part)
      start = splitAt
      i = splitAt
      continue
    }
    i += 1
  }
  const tail = text.slice(start).trim()
  if (tail) pieces.push(tail)
  if (pieces.length > 1) return pieces
  const cjk = splitWithPattern(text, CJK_SENTENCE_BOUNDARY)
  if (cjk.length > 1) return cjk
  const en = splitWithPattern(text, EN_SENTENCE_BOUNDARY)
  if (en.length > 1) return en
  return [text.trim()]
}

function splitLinesOutsideParentheses(text) {
  const normalized = String(text).replace(/\r\n/g, '\n')
  const lines = []
  let current = ''
  for (let i = 0; i < normalized.length; i += 1) {
    const ch = normalized[i]
    if (ch === '\n') {
      if (!isInsideParentheses(normalized, i)) {
        const trimmed = current.trim()
        if (trimmed) lines.push(trimmed)
        current = ''
        continue
      }
    }
    current += ch
  }
  const trimmed = current.trim()
  if (trimmed) lines.push(trimmed)
  return lines.length ? lines : [normalized.trim()]
}

function collectReplyPieces(fullContent) {
  if (!fullContent || !String(fullContent).trim()) {
    return []
  }
  const normalized = normalizeAssistantContent(fullContent)
  let pieces = splitLinesOutsideParentheses(normalized)
  if (pieces.length === 0) {
    pieces = [normalized]
  }

  if (pieces.length === 1 && pieces[0].length >= SENTENCE_SPLIT_MIN_CHARS) {
    const sentencePieces = splitBySentenceBoundary(pieces[0])
    if (sentencePieces.length > 1) {
      pieces = sentencePieces
    }
  }
  return rebalanceSplitPieces(pieces.map(p => stripLeadingOrphanCloses(p)))
}

function capReplyPieces(pieces, limit) {
  const capped = Math.max(1, Number(limit) || 3)
  if (pieces.length <= capped) {
    return pieces
  }
  const head = pieces.slice(0, capped - 1)
  const tail = pieces.slice(capped - 1).join(' ').trim()
  return [...head, tail]
}

export function splitAssistantReply(fullContent, maxRepliesPerTurn = 3) {
  return processAssistantReply(fullContent, maxRepliesPerTurn)
}

/** 展示层：后端已按条落库时不再二次切分；仅用于流式兜底 */
export function splitAssistantReplyForDisplay(fullContent) {
  return capReplyPieces(collectReplyPieces(fullContent), 5)
}

export function processAssistantReply(fullContent, maxRepliesPerTurn = 3) {
  return capReplyPieces(collectReplyPieces(fullContent), maxRepliesPerTurn)
}

/** 与后端 {@code CharacterChatBehaviorResolver.StyleProfile} 默认条数一致 */
const SPEAKING_STYLE_MAX_REPLIES = {
  活泼: 3,
  元气: 3,
  温柔: 2,
  傲娇: 2,
  毒舌: 2,
  冷静: 1,
  成熟: 1,
  慵懒: 1
}

function clampMaxReplies(n) {
  return Math.min(5, Math.max(1, n))
}

export function resolveMaxRepliesPerTurn(character) {
  const settings = character?.settings
  if (settings && typeof settings === 'object') {
    const nested = settings.chatBehavior
    if (nested && typeof nested === 'object' && nested.maxRepliesPerTurn != null) {
      const n = Number(nested.maxRepliesPerTurn)
      if (Number.isFinite(n)) return clampMaxReplies(n)
    }
    if (settings.maxRepliesPerTurn != null) {
      const n = Number(settings.maxRepliesPerTurn)
      if (Number.isFinite(n)) return clampMaxReplies(n)
    }
    const style = String(settings.speakingStyle || '').trim()
    if (style && SPEAKING_STYLE_MAX_REPLIES[style] != null) {
      return SPEAKING_STYLE_MAX_REPLIES[style]
    }
  }
  return 2
}
