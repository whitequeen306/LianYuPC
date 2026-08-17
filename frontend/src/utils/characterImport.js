export const IMPORT_MAX_CHARS = 12_000
export const IMPORT_MAX_BYTES = 2 * 1024 * 1024
export const IMPORT_MAX_RAW_CHARS = 100_000
export const ADDRESSING_MAX_CHARS = 32

const IMPORT_EXTS = ['.txt', '.md', '.json', '.html', '.htm', '.csv', '.log']

export function isAllowedImportFile(file) {
  if (!file) return false
  const name = String(file.name || '').toLowerCase()
  if (IMPORT_EXTS.some((ext) => name.endsWith(ext))) return true
  const type = String(file.type || '').toLowerCase()
  return type.startsWith('text/') || type === 'application/json'
}

export function sanitizeAddressing(raw) {
  return String(raw || '')
    .replace(/[\u0000-\u001F]/g, '')
    .replace(/[「」"'“”]/g, '')
    .trim()
    .slice(0, ADDRESSING_MAX_CHARS)
}

export function mergeAddressingIntoPrompt(promptTemplate, addressing) {
  const base = promptTemplate == null ? '' : String(promptTemplate)
  const trimmed = sanitizeAddressing(addressing)
  if (!trimmed) return base
  const marker = `最常用的称呼是「${trimmed}」`
  if (base.includes(marker)) return base
  return `${base}\n\n对用户最常用的称呼是「${trimmed}」。这是口吻参考，不是每句都必须这样叫。`
}

export function buildImportCreatePayload({ draft, city, userAddressing }) {
  const addressing = sanitizeAddressing(userAddressing)
  const promptTemplate = mergeAddressingIntoPrompt(draft?.promptTemplate || '', addressing)
  const settings = {
    city_mode: 'real',
    city: String(city || '').trim(),
    userAddressing: addressing,
  }
  if (draft?.age && draft.age !== '未知') settings.age = draft.age
  if (draft?.gender && draft.gender !== '未知') settings.gender = draft.gender
  if (draft?.speakingStyle) settings.speakingStyle = draft.speakingStyle
  if (draft?.personalityArchetype) settings.personalityArchetype = draft.personalityArchetype
  if (draft?.sourceType) settings.sourceType = draft.sourceType
  if (draft?.summary) settings.summary = draft.summary

  return {
    name: draft?.name || '未命名角色',
    promptTemplate,
    settings,
  }
}

export async function readImportFileAsText(file) {
  if (!file) return ''
  if (file.size > IMPORT_MAX_BYTES) {
    const err = new Error('FILE_TOO_LARGE')
    err.code = 'FILE_TOO_LARGE'
    throw err
  }
  const buf = await file.arrayBuffer()
  const utf8 = new TextDecoder('utf-8').decode(buf)
  const replacementCount = (utf8.match(/\uFFFD/g) || []).length
  if (replacementCount >= 8) {
    try {
      return new TextDecoder('gb18030').decode(buf)
    } catch {
      return utf8
    }
  }
  return utf8
}
