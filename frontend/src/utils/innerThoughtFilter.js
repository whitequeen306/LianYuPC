const FULL_WIDTH_PARENS = /（[^（）]*）/g
const HALF_WIDTH_PARENS = /\([^()]*\)/g

export function stripInnerThoughts(text, showInnerThoughts = true) {
  if (showInnerThoughts || text == null) {
    return text == null || text === '' ? '' : text
  }
  let result = text
  result = result.replace(FULL_WIDTH_PARENS, '')
  result = result.replace(HALF_WIDTH_PARENS, '')
  return result.replace(/\s{2,}/g, ' ').trim()
}

export function resolveShowInnerThoughts(settings) {
  if (!settings || settings.showInnerThoughts === undefined || settings.showInnerThoughts === null) {
    return true
  }
  return settings.showInnerThoughts !== false && settings.showInnerThoughts !== 'false'
}

export function displayAssistantContent(content, showInnerThoughts = true) {
  return stripInnerThoughts(content, showInnerThoughts)
}
