/**
 * 后端 boolean isMine 经 Jackson 序列化后字段名可能是 mine，统一归一化。
 * @param {object} comment
 * @param {number|string|null|undefined} viewerUserId
 */
export function isSquareCommentMine(comment, viewerUserId = null) {
  if (!comment) return false
  if (comment.isMine === true || comment.mine === true) return true
  if (viewerUserId != null && comment.userId != null) {
    return String(comment.userId) === String(viewerUserId)
  }
  return false
}

/**
 * @param {object[]} comments
 * @param {number|string|null|undefined} viewerUserId
 */
export function normalizeSquareComments(comments, viewerUserId = null) {
  if (!Array.isArray(comments)) return []
  return comments.map((comment) => ({
    ...comment,
    isMine: isSquareCommentMine(comment, viewerUserId),
  }))
}
