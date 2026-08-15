import fs from 'fs'

export const MAX_EXPORT_TEXT_CHARS = 20 * 1024 * 1024

const ILLEGAL_NAME = /[\\/:*?"<>|\u0000-\u001f]/g
const WIN_RESERVED = /^(con|prn|aux|nul|com[1-9]|lpt[1-9])(\.|$)/i

export function sanitizeExportFileName(raw, fallback = '聊天记录--恋语.txt') {
  const fallbackStem = String(fallback).replace(/\.txt$/i, '') || '聊天记录--恋语'
  let name = String(raw || '').trim().replace(/\\/g, '/')
  if (!name) name = `${fallbackStem}.txt`
  const base = name.split('/').filter((part) => part && part !== '.' && part !== '..').pop()
    || `${fallbackStem}.txt`
  let stem = base.replace(/\.[^.]+$/, '')
  stem = stem.replace(ILLEGAL_NAME, '_').replace(/[. ]+$/g, '')
  if (!stem) stem = fallbackStem
  if (WIN_RESERVED.test(stem)) stem = `_${stem}`
  if (stem.length > 80) stem = stem.slice(0, 80)
  return `${stem}.txt`
}

export function validateExportText(content) {
  if (typeof content !== 'string') return { ok: false, reason: 'invalid_content' }
  if (!content.trim()) return { ok: false, reason: 'empty' }
  if (content.length > MAX_EXPORT_TEXT_CHARS) return { ok: false, reason: 'too_large' }
  return { ok: true }
}

export function writeUtf8TextFile(filePath, content) {
  const text = content.charCodeAt(0) === 0xfeff ? content : `\uFEFF${content}`
  fs.writeFileSync(filePath, text, 'utf8')
  return { ok: true, bytes: Buffer.byteLength(text, 'utf8') }
}
