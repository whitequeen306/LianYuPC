const CSS_PRELOAD_RE = /Unable to preload CSS/i
const STORAGE_KEY = 'lianyu:css-preload-reload'
const memoryReloadGuards = new Set()

export function shouldReloadForCssPreloadError(error, locationObject = window.location, storage = window.sessionStorage) {
  const message = error?.message || String(error || '')
  if (!CSS_PRELOAD_RE.test(message)) return false

  const href = locationObject?.href || ''
  const key = `${STORAGE_KEY}:${href}`
  if (memoryReloadGuards.has(key)) return false

  try {
    if (storage?.getItem?.(key) === '1') return false
    storage?.setItem?.(key, '1')
  } catch {
    // Storage can be unavailable on startup error paths; keep the fallback non-throwing.
  }

  memoryReloadGuards.add(key)
  return true
}

export function recoverFromStartupRouteError(error, locationObject = window.location, storage = window.sessionStorage) {
  if (!shouldReloadForCssPreloadError(error, locationObject, storage)) return false
  locationObject.reload()
  return true
}
