function defaultNow() {
  if (typeof performance !== 'undefined' && typeof performance.now === 'function') {
    return performance.now()
  }
  return Date.now()
}

export function createStartupProfiler({ prefix, log, now = defaultNow }) {
  const startedAt = now()

  function mark(label) {
    const elapsed = Math.max(0, Math.round(now() - startedAt))
    log?.(`[${prefix}] ${label} +${elapsed}ms`)
  }

  return { mark }
}
