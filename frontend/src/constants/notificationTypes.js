/** 羁绊页角色卡片：仅单聊角色主动消息 */
export const CHAR_CARD_UNREAD_TYPES = new Set(['PROACTIVE_MESSAGE'])

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
