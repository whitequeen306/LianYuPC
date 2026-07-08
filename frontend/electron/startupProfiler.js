export function createStartupProfiler({ prefix, log, now = () => Date.now() }) {
  const startedAt = now()

  function mark(label) {
    const elapsed = Math.max(0, now() - startedAt)
    log?.(`[${prefix}] ${label} +${elapsed}ms`)
  }

  return { mark }
}
