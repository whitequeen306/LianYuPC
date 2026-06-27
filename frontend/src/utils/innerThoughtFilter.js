const OPEN_PARENS = new Set(['（', '('])
const CLOSE_PARENS = new Set(['）', ')'])

export function normalizeAssistantContent(text) {
  if (text == null || text === '') return ''
  let t = String(text).replace(/\r\n/g, '\n').trim()
  t = stripLeadingOrphanCloses(t)
  t = flattenNewlinesInsideParentheses(t)
  const depth = countUnclosedParenDepth(t)
  if (depth > 0) {
    t += '）'.repeat(depth)
  }
  return t.replace(/\s{2,}/g, ' ').trim()
}

function flattenNewlinesInsideParentheses(text) {
  let out = ''
  for (let i = 0; i < text.length; i += 1) {
    const ch = text[i]
    if (ch === '\n' && isInsideParentheses(text, i)) {
      if (out.length > 0 && out[out.length - 1] !== ' ') out += ' '
      continue
    }
    out += ch
  }
  return out
}

export function countUnclosedParenDepth(text) {
  if (!text) return 0
  let depth = 0
  for (let i = 0; i < text.length; i += 1) {
    const ch = text[i]
    if (OPEN_PARENS.has(ch)) depth += 1
    else if (CLOSE_PARENS.has(ch) && depth > 0) depth -= 1
  }
  return depth
}

/** 去掉气泡开头孤立的闭括号（模型/切分错位产物） */
export function stripLeadingOrphanCloses(text) {
  if (!text) return ''
  let i = 0
  while (i < text.length) {
    const ch = text[i]
    if (CLOSE_PARENS.has(ch)) {
      i += 1
      continue
    }
    if (ch === ' ' || ch === '\n' || ch === '\r' || ch === '\t') {
      i += 1
      continue
    }
    break
  }
  return text.slice(i)
}

/** 合并切分后括号未闭合的片段，避免（与）落在不同气泡 */
export function rebalanceSplitPieces(pieces) {
  if (!pieces?.length) return []
  const out = []
  let pending = ''
  for (const raw of pieces) {
    const piece = String(raw ?? '').trim()
    if (!piece) continue
    const combined = pending ? `${pending}\n${piece}` : piece
    if (countUnclosedParenDepth(combined) > 0) {
      pending = combined
      continue
    }
    const cleaned = stripLeadingOrphanCloses(combined).trim()
    if (cleaned) out.push(cleaned)
    pending = ''
  }
  const tail = stripLeadingOrphanCloses(pending).trim()
  if (tail) out.push(tail)
  return out
}

/** @returns {{ start: number, end: number, unclosed?: boolean }[]} */
export function findParenthesisRanges(text) {
  if (!text) return []
  const ranges = []
  const stack = []
  for (let i = 0; i < text.length; i += 1) {
    const ch = text[i]
    if (OPEN_PARENS.has(ch)) {
      stack.push(i)
    } else if (CLOSE_PARENS.has(ch) && stack.length) {
      const start = stack.pop()
      ranges.push({ start, end: i })
    }
  }
  while (stack.length) {
    const start = stack.pop()
    ranges.push({ start, end: text.length - 1, unclosed: true })
  }
  return ranges.sort((a, b) => a.start - b.start)
}

export function isInsideParentheses(text, index) {
  if (!text || index <= 0) return false
  let depth = 0
  for (let i = 0; i < index; i += 1) {
    const ch = text[i]
    if (OPEN_PARENS.has(ch)) depth += 1
    else if (CLOSE_PARENS.has(ch) && depth > 0) depth -= 1
  }
  return depth > 0
}

export function parseInnerThoughtSegments(text) {
  if (text == null || text === '') return []
  const ranges = findParenthesisRanges(text)
  if (!ranges.length) return [{ type: 'speech', text }]

  const segments = []
  let lastIndex = 0
  for (const range of ranges) {
    const { start, end } = range
    if (start > lastIndex) {
      const speech = text.slice(lastIndex, start)
      if (speech) segments.push({ type: 'speech', text: speech })
    }
    const innerText = range.unclosed ? text.slice(start) : text.slice(start, end + 1)
    if (innerText) segments.push({ type: 'inner', text: innerText })
    lastIndex = range.unclosed ? text.length : end + 1
  }
  if (lastIndex < text.length) {
    const speech = text.slice(lastIndex)
    if (speech) segments.push({ type: 'speech', text: speech })
  }
  return segments.length ? segments : [{ type: 'speech', text }]
}

export function hasInnerThoughtMarkers(text) {
  return findParenthesisRanges(text).length > 0
}

export function stripInnerThoughts(text, showInnerThoughts = true) {
  if (showInnerThoughts || text == null) {
    return text == null || text === '' ? '' : text
  }
  const ranges = findParenthesisRanges(text).sort((a, b) => b.start - a.start)
  let result = text
  for (const { start, end } of ranges) {
    result = result.slice(0, start) + result.slice(end + 1)
  }
  return result.replace(/\s{2,}/g, ' ').trim()
}

export function resolveShowInnerThoughts(settings) {
  if (!settings || settings.showInnerThoughts === undefined || settings.showInnerThoughts === null) {
    return true
  }
  return settings.showInnerThoughts !== false && settings.showInnerThoughts !== 'false'
}

export function displayAssistantContent(content, showInnerThoughts = true) {
  return stripInnerThoughts(content, showInnerThoughts)
}
