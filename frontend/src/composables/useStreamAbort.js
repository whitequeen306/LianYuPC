import { onScopeDispose } from 'vue'

export function isAbortError(err) {
  return err?.name === 'AbortError'
    || (typeof DOMException !== 'undefined' && err instanceof DOMException && err.name === 'AbortError')
}

export function isNetworkError(err) {
  if (!err) return false
  if (err.name === 'TypeError') return true
  const msg = String(err.message || '').toLowerCase()
  return msg.includes('network')
    || msg.includes('failed to fetch')
    || msg.includes('load failed')
}

export function useStreamAbort(options = {}) {
  const { abortOnUnmount = true } = options
  let controller = null

  function beginStream() {
    controller?.abort()
    controller = new AbortController()
    return controller.signal
  }

  function abortStream() {
    controller?.abort()
    controller = null
  }

  onScopeDispose(() => {
    if (abortOnUnmount) abortStream()
  })

  return { beginStream, abortStream, isAbortError }
}
