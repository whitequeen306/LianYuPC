function resolveCommentAuthorName(comment, fallbackYou = 'You') {
  return comment?.characterName || comment?.userDisplayName || fallbackYou
}

/**
 * Build a 2-level thread: roots + flat replies under each root.
 * Replies-to-replies are attached to the root (via rootId / parent walk) so none are hidden.
 */
export function buildMomentCommentThread(comments = []) {
  const normalized = comments.map(comment => ({
    ...comment,
    replies: []
  }))
  const byId = new Map(normalized.map(comment => [comment.id, comment]))
  const roots = []

  function findRoot(comment) {
    if (!comment) return null
    if (comment.rootId && byId.has(comment.rootId)) {
      const byRoot = byId.get(comment.rootId)
      if (byRoot && !byRoot.parentId) return byRoot
    }
    let cur = comment
    const seen = new Set()
    while (cur?.parentId && !seen.has(cur.id)) {
      seen.add(cur.id)
      const parent = byId.get(cur.parentId)
      if (!parent) break
      if (!parent.parentId) return parent
      cur = parent
    }
    return null
  }

  for (const comment of normalized) {
    if (!comment.parentId) {
      roots.push(comment)
      continue
    }
    const parent = byId.get(comment.parentId)
    const root = findRoot(comment)
    if (root) {
      root.replies.push({
        ...comment,
        replyToName: resolveCommentAuthorName(parent || root)
      })
      continue
    }
    // Orphan reply — keep visible
    roots.push(comment)
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
