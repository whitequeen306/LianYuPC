import { i18n } from '@/i18n'
import { resolveMediaUrl } from '@/utils/media'
import { sameCharacterId } from '@/utils/characterAvatar'

function lookupLocalCharacter(message, { characters = [], currentCharacter = null } = {}) {
  const id = message?.characterId != null ? Number(message.characterId) : NaN
  const fromList = Number.isFinite(id)
    ? (characters || []).find((c) => c != null && sameCharacterId(c.id ?? c.characterId, id))
    : null
  const current = currentCharacter && typeof currentCharacter === 'object' ? currentCharacter : null
  const currentMatches = current && (!Number.isFinite(id) || sameCharacterId(current.id ?? current.characterId, id))
  return fromList || (currentMatches ? current : null)
}

/**
 * 进度气泡 / 控制条共用的角色身份。
 * 名字优先 STOMP；头像优先本地角色列表里已经解析过的公开 URL
 *（含广场缩略图）。STOMP 的 characterAvatarUrl 经常是库里的 object key，
 * 不能当 img src。
 */
export function resolveMcpActorIdentity(message, {
  characters = [],
  currentCharacter = null,
} = {}) {
  const t = i18n.global.t
  const id = message?.characterId != null ? Number(message.characterId) : NaN
  const local = lookupLocalCharacter(message, { characters, currentCharacter })
  const fallbackName = t('about.mcpControlFallbackName')
  const name = String(message?.characterName || local?.name || fallbackName || '角色')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 32) || fallbackName || '角色'
  const localOrig = String(local?.avatarUrl || '').trim()
  const localThumb = String(local?.avatarThumbUrl || '').trim()
  const stompUrl = String(message?.characterAvatarUrl || '').trim()
  return {
    characterId: Number.isFinite(id) ? id : (local?.id ?? null),
    name,
    avatarUrl: localOrig || stompUrl || '',
    avatarThumbUrl: localThumb,
  }
}

/**
 * 把云端工具调用载荷收成桌面控制条要用的角色信息。
 */
export function resolveMcpControlActor(message, {
  characters = [],
  currentCharacter = null,
  theme = 'dark',
} = {}) {
  const t = i18n.global.t
  const identity = resolveMcpActorIdentity(message, { characters, currentCharacter })
  const rawAvatar = identity.avatarThumbUrl || identity.avatarUrl
  return {
    name: identity.name,
    avatarUrl: resolveMediaUrl(rawAvatar),
    caption: t('about.mcpControlBanner', { name: identity.name }),
    theme: theme === 'light' ? 'light' : 'dark',
  }
}
