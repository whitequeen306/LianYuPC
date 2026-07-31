/** 羁绊页角色卡片：单聊普通回复 + 主动消息 */
export const CHAR_CARD_UNREAD_TYPES = new Set(['MESSAGE', 'PROACTIVE_MESSAGE'])

/** 顶栏铃铛：动态与日记相关 */
export const BELL_UNREAD_TYPES = new Set(['MOMENT_NEW', 'MOMENT_COMMENT', 'DIARY_NEW'])

/** 群聊列表卡片 */
export const GROUP_CARD_UNREAD_TYPES = new Set(['GROUP_MESSAGE'])

export function countUnreadByTypes(list, types) {
  let count = 0
  for (const n of list || []) {
    if (n?.read) continue
    if (types.has(n.type || '')) count += 1
  }
  return count
}

/** Drop character-linked notifications after a character is deleted. */
export function filterOutCharacterNotifications(list, characterId) {
  if (characterId == null) return Array.isArray(list) ? list : []
  const id = Number(characterId)
  if (!Number.isFinite(id)) return Array.isArray(list) ? list : []
  return (list || []).filter((n) => {
    if (n?.characterId == null) return true
    return Number(n.characterId) !== id
  })
}
