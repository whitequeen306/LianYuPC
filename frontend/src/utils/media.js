/**
 * 头像/媒体 URL：兼容 blob 预览、API 代理路径与历史 MinIO 直链
 */
export function resolveMediaUrl(url) {
  if (!url) return ''
  if (url.startsWith('blob:') || url.startsWith('data:')) {
    return url
  }
  if (url.startsWith('/api/')) {
    return url
  }
  const idx = url.indexOf('/api/public/files/')
  if (idx >= 0) {
    return url.substring(idx)
  }
  const avatarsIdx = url.indexOf('avatars/')
  if (avatarsIdx >= 0) {
    let key = url.substring(avatarsIdx)
    const q = key.indexOf('?')
    if (q >= 0) key = key.substring(0, q)
    return `/api/public/files/${key}`
  }
  return url
}
