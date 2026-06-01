/** @returns {boolean} */
export function hasCustomGroupTitle(title) {
  return Boolean(title && String(title).trim())
}

/**
 * @param {string | null | undefined} title
 * @param {string} untitledLabel i18n label for empty titles
 */
export function resolveGroupDisplayTitle(title, untitledLabel) {
  const trimmed = title?.trim()
  return trimmed || untitledLabel
}
