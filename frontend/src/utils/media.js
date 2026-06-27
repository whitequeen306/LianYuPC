import { apiOrigin, isElectronRuntime } from '@/utils/runtime.js'

const OBJECT_KEY_PREFIXES = ['square-avatars/', 'chat-images/', 'avatars/']

export { isElectronRuntime }

function getApiOrigin() {
  return apiOrigin()
}

/** public/ 下的静态资源（logo、landing 角色图等） */
export function resolveStaticAsset(relativePath) {
  const normalized = String(relativePath).replace(/^\/+/, '')
  const combined = `${import.meta.env.BASE_URL}${normalized}`
  if (typeof window !== 'undefined' && isElectronRuntime()) {
    try {
      return new URL(combined, window.location.href).href
    } catch {
      return combined
    }
  }
  return combined
}

/**
 * 头像/媒体 URL：兼容 blob 预览、API 代理路径与历史 MinIO 直链。
 * Electron file:// 下 /api/... 必须补全为 http://localhost:8080/api/...
 */
export function resolveMediaUrl(url) {
  if (!url) return ''
  if (url.startsWith('blob:') || url.startsWith('data:') || url.startsWith('file:')) {
    return url
  }

  let resolved = url

  if (url.startsWith('/api/')) {
    resolved = url
  } else {
    const apiIdx = url.indexOf('/api/public/files/')
    if (apiIdx >= 0) {
      resolved = url.substring(apiIdx)
    } else {
      let mapped = false
      for (const prefix of OBJECT_KEY_PREFIXES) {
        const prefixIdx = url.indexOf(prefix)
        if (prefixIdx < 0) continue
        let key = url.substring(prefixIdx)
        const q = key.indexOf('?')
        if (q >= 0) key = key.substring(0, q)
        resolved = `/api/public/files/${key}`
        mapped = true
        break
      }
      if (!mapped && !url.startsWith('http://') && !url.startsWith('https://')) {
        for (const prefix of OBJECT_KEY_PREFIXES) {
          if (url.startsWith(prefix)) {
            return `${getApiOrigin()}/api/public/files/${url}`
          }
        }
        return resolveStaticAsset(url)
      }
    }
  }

  if (resolved.startsWith('/api/')) {
    return `${getApiOrigin()}${resolved}`
  }

  return resolved
}
