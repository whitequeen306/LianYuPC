/**
 * 角色头像展示 URL（广场 square-avatars 优先缩略图，失败回退原图）。
 */

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
