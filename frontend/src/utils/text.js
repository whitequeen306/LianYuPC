/** Trim and ellipsize plain text for feed previews. */
export function truncateText(text, maxLen) {
  if (!text) return ''
  const trimmed = text.trim()
  if (trimmed.length <= maxLen) return trimmed
  return `${trimmed.slice(0, maxLen)}…`
}
