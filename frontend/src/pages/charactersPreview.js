export function selectCharacterPreview(conversationEntry, fallbackText) {
  const assistantPreview = conversationEntry?.lastCharacterMessage?.trim()
  if (assistantPreview) return assistantPreview

  const latestPreview = conversationEntry?.lastMessage?.trim()
  if (latestPreview) return latestPreview

  return fallbackText
}

function resolveConversationTimestamp(conversationEntry) {
  const raw = conversationEntry?.updatedAt
    || conversationEntry?.lastMessageAt
    || conversationEntry?.createdAt
    || null
  if (!raw) return 0
  const ms = new Date(raw).getTime()
  return Number.isFinite(ms) ? ms : 0
}

export function buildLatestSingleConversationMap(convList) {
  const map = {}
  for (const conversation of convList || []) {
    if (conversation?.mode !== 'SINGLE' || !conversation.characterId) continue

    const nextEntry = {
      id: conversation.id,
      characterId: conversation.characterId,
      lastMessage: conversation.lastMessage || '',
      lastCharacterMessage: conversation.lastCharacterMessage || '',
      updatedAt: conversation.updatedAt || conversation.lastMessageAt || conversation.createdAt || null,
    }
    const prevEntry = map[conversation.characterId]
    if (!prevEntry) {
      map[conversation.characterId] = nextEntry
      continue
    }

    const nextMs = resolveConversationTimestamp(nextEntry)
    const prevMs = resolveConversationTimestamp(prevEntry)
    if (nextMs > prevMs) {
      map[conversation.characterId] = nextEntry
    }
  }
  return map
}
