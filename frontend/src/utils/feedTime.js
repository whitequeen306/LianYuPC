/**
 * API returns LocalDateTime without offset (Asia/Shanghai business time).
 */
export function parseFeedDateTime(iso) {
  if (!iso) return null
  const raw = String(iso).trim()
  if (!raw) return null
  if (/[zZ]$/.test(raw) || /[+-]\d{2}:\d{2}$/.test(raw)) {
    const d = new Date(raw)
    return Number.isNaN(d.getTime()) ? null : d
  }
  const d = new Date(`${raw}+08:00`)
  return Number.isNaN(d.getTime()) ? null : d
}

/**
 * Relative / friendly timestamps for social feed surfaces.
 */
export function formatFeedTime(iso, t) {
  const d = parseFeedDateTime(iso)
  if (!d) return iso || ''

  const now = new Date()
  const sameDay = d.toDateString() === now.toDateString()
  if (sameDay) {
    return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
  }

  const yesterday = new Date(now)
  yesterday.setDate(yesterday.getDate() - 1)
  if (d.toDateString() === yesterday.toDateString()) {
    const time = d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
    return t ? t('feed.yesterday', { time }) : `Yesterday ${time}`
  }

  return d.toLocaleString([], {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit'
  })
}

export function formatFeedDateLabel(iso, t) {
  const d = parseFeedDateTime(iso)
  if (!d) return iso || ''

  const now = new Date()
  if (d.toDateString() === now.toDateString()) {
    return t ? t('feed.today') : 'Today'
  }

  const yesterday = new Date(now)
  yesterday.setDate(yesterday.getDate() - 1)
  if (d.toDateString() === yesterday.toDateString()) {
    return t ? t('feed.yesterdayLabel') : 'Yesterday'
  }

  return d.toLocaleDateString([], { month: 'long', day: 'numeric', weekday: 'short' })
}

export function feedDateKey(iso) {
  const d = parseFeedDateTime(iso)
  if (!d) return iso || ''
  return d.toDateString()
}
