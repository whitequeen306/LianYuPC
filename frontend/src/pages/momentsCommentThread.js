function resolveCommentAuthorName(comment, fallbackYou = 'You') {
  return comment?.characterName || comment?.userDisplayName || fallbackYou
}

export function buildMomentCommentThread(comments = []) {
  const normalized = comments.map(comment => ({
    ...comment,
    replies: []
  }))
  const byId = new Map(normalized.map(comment => [comment.id, comment]))
  const roots = []

  for (const comment of normalized) {
    if (!comment.parentId) {
      roots.push(comment)
      continue
    }
    const parent = byId.get(comment.parentId)
    if (!parent) {
      roots.push(comment)
      continue
    }
    parent.replies.push({
      ...comment,
      replyToName: resolveCommentAuthorName(parent)
    })
  }

  return roots
}

export function formatMomentCommentAuthorLabel(comment, fallbackYou = 'You', formatReplyLabel = null) {
  const authorName = resolveCommentAuthorName(comment, fallbackYou)
  if (!comment?.replyToName) {
    return authorName
  }
  if (typeof formatReplyLabel === 'function') {
    return formatReplyLabel(authorName, comment.replyToName)
  }
  return `${authorName} -> ${comment.replyToName}`
}
