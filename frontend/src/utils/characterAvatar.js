/**
 * 角色头像展示 URL（广场 square-avatars 优先缩略图，失败回退原图）。
 */

export function sameCharacterId(a, b) {
  if (a == null || b == null) return false
  const na = Number(a)
  const nb = Number(b)
  if (Number.isFinite(na) && Number.isFinite(nb)) return na === nb
  return String(a) === String(b)
}

export function pickCharacterAvatarRaw(character, tier = 'thumb') {
  if (!character) return ''
  if (tier === 'broken') return ''
  if (tier === 'orig') {
    return character.avatarUrl || character.avatarThumbUrl || ''
  }
  return character.avatarThumbUrl || character.avatarUrl || ''
}

export function nextCharacterAvatarTier(character, currentTier) {
  if (!character?.id) return 'broken'
  const thumb = character.avatarThumbUrl || ''
  const orig = character.avatarUrl || ''
  if (currentTier !== 'orig' && orig && orig !== thumb) {
    return 'orig'
  }
  return 'broken'
}

/**
 * 统一入口：合并角色对象 / 本地列表 / DTO 字段别名后再 pick。
 *
 * @param {object} [input]
 * @param {object} [input.character] 已有角色对象（优先）
 * @param {number|string} [input.characterId]
 * @param {Array} [input.characters] 本地角色列表（按 id 查找）
 * @param {string} [input.avatarUrl]
 * @param {string} [input.avatarThumbUrl]
 * @param {string} [input.characterAvatarUrl] DTO 别名
 * @param {string} [input.characterAvatarThumbUrl]
 * @param {'thumb'|'orig'|'broken'} [input.tier='thumb']
 * @returns {string} 未 resolveMediaUrl 的相对/绝对路径
 */
export function resolveCharacterAvatarSrc(input = {}) {
  const {
    character,
    characterId,
    characters = [],
    avatarUrl = '',
    avatarThumbUrl = '',
    characterAvatarUrl = '',
    characterAvatarThumbUrl = '',
    tier = 'thumb',
  } = input

  const fromStore = character
    || (characterId != null && Array.isArray(characters)
      ? characters.find((c) => c != null && sameCharacterId(c.id ?? c.characterId, characterId))
      : null)

  const merged = {
    id: fromStore?.id ?? characterId,
    avatarUrl: fromStore?.avatarUrl || avatarUrl || characterAvatarUrl || '',
    avatarThumbUrl: fromStore?.avatarThumbUrl || avatarThumbUrl || characterAvatarThumbUrl || '',
  }

  return pickCharacterAvatarRaw(merged, tier)
}
