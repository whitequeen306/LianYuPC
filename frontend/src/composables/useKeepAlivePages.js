// keep-alive :include 名单：仅缓存重列表页。
// 不缓存：Chat/CharacterChatDetail（按 id 挂载）、GroupChat（会话状态）、
// Settings/QqBridge/About（轻量/可弃表单）、Landing/Login/Register/Launcher/QuickChat。
export const KEEP_ALIVE_PAGES = [
  'HomePage',
  'CharactersPage',
  'CharacterSquarePage',
  'MomentsPage',
  'MemoryPage',
  'DiaryPage',
  'ProfilePage',
]

const CACHE_SET = new Set(KEEP_ALIVE_PAGES)

export function isCacheable(name) {
  return !!name && CACHE_SET.has(name)
}
