export const COMMUNITY_SHARE_DRAFT_KEY = 'communityShareDraft'
export const COMMUNITY_SHARE_DRAFT_KIND_CHAT = 'chat'
export const COMMUNITY_SHARE_MAX_MESSAGES = 20
export const COMMUNITY_SHARE_MAX_CONTENT = 1000

export function saveCommunityShareDraft(draft) {
  sessionStorage.setItem(COMMUNITY_SHARE_DRAFT_KEY, JSON.stringify(draft))
}

export function consumeCommunityShareDraft() {
  const raw = sessionStorage.getItem(COMMUNITY_SHARE_DRAFT_KEY)
  if (!raw) return null
  sessionStorage.removeItem(COMMUNITY_SHARE_DRAFT_KEY)
  try {
    return JSON.parse(raw)
  } catch {
    return null
  }
}

export function isShareSelectableMessage(item) {
  return item?.type === 'message'
    && item.id != null
    && Number.isFinite(Number(item.id))
    && !item._streamGroupId
}

export function buildChatImageShareDraft({ imageUrl, linkedCharacterId }) {
  return {
    kind: COMMUNITY_SHARE_DRAFT_KIND_CHAT,
    imageUrl: imageUrl || '',
    linkedCharacterId: linkedCharacterId ?? null
  }
}
