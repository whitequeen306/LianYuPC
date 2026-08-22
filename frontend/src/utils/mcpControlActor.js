import { i18n } from '@/i18n'
import { resolveMediaUrl } from '@/utils/media'

/**
 * 把云端工具调用载荷收成桌面控制条要用的角色信息。
 * 优先 STOMP 下发的 character*，缺了再从本地角色列表 / 当前聊天角色补。
 */
export function resolveMcpControlActor(message, {
  characters = [],
  currentCharacter = null,
  theme = 'dark',
} = {}) {
  const t = i18n.global.t
  const id = message?.characterId != null ? Number(message.characterId) : NaN
  const fromList = Number.isFinite(id)
    ? (characters || []).find((c) => Number(c.id) === id)
    : null
  const current = currentCharacter && typeof currentCharacter === 'object' ? currentCharacter : null
  const currentMatches = current && (!Number.isFinite(id) || Number(current.id) === id)
  const fromCurrent = currentMatches ? current : null
  const fallbackName = t('about.mcpControlFallbackName')
  const name = String(message?.characterName || fromList?.name || fromCurrent?.name || fallbackName || '角色')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 32) || fallbackName || '角色'
  const rawAvatar = message?.characterAvatarUrl
    || fromList?.avatarThumbUrl || fromList?.avatarUrl
    || fromCurrent?.avatarThumbUrl || fromCurrent?.avatarUrl
    || ''
  return {
    name,
    avatarUrl: resolveMediaUrl(rawAvatar),
    caption: t('about.mcpControlBanner', { name }),
    theme: theme === 'light' ? 'light' : 'dark',
  }
}
