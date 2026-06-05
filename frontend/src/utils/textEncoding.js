/**
 * UTF-8 字节被误按 Windows-1252 / Latin-1 显示时的乱码修复。
 * 支持单重/双重编码，以及「Ã¦Â¸Â©Ã¦Â¸Â柔」这类混合串。
 */

/** Windows-1252 中 0x80–0x9F 等非 Latin-1 字节对应的 Unicode 码点 → 字节值 */
const CP1252_CHAR_TO_BYTE = new Map([
  [0x20ac, 0x80], [0x201a, 0x82], [0x0192, 0x83], [0x201e, 0x84], [0x2026, 0x85],
  [0x2020, 0x86], [0x2021, 0x87], [0x02c6, 0x88], [0x2030, 0x89], [0x0160, 0x8a],
  [0x2039, 0x8b], [0x0152, 0x8c], [0x017d, 0x8e], [0x2018, 0x91], [0x2019, 0x92],
  [0x201c, 0x93], [0x201d, 0x94], [0x2022, 0x95], [0x2013, 0x96], [0x2014, 0x97],
  [0x02dc, 0x98], [0x2122, 0x99], [0x0161, 0x9a], [0x203a, 0x9b], [0x0153, 0x9c],
  [0x017e, 0x9e], [0x0178, 0x9f],
])

function encodeWindows1252(value) {
  const bytes = new Uint8Array(value.length)
  for (let i = 0; i < value.length; i++) {
    const code = value.charCodeAt(i)
    if (code <= 0xff) {
      bytes[i] = code
      continue
    }
    const mapped = CP1252_CHAR_TO_BYTE.get(code)
    if (mapped === undefined) return null
    bytes[i] = mapped
  }
  return bytes
}

function looksLikeMojibake(value) {
  return /[\u00c0-\u00ff\u0152\u0153\u0160\u0161\u0178\u017d\u017e\u2013-\u201e\u2026]/.test(value)
}

function tryDecodeOnce(value, { fatal = true } = {}) {
  try {
    const bytes = encodeWindows1252(value)
    if (!bytes) return null
    const decoded = new TextDecoder('utf-8', { fatal }).decode(bytes)
    if (!decoded) return null
    if (fatal && decoded.includes('\uFFFD')) return null
    return decoded.replace(/\uFFFD/g, '')
  } catch {
    return null
  }
}

function fixMojibakeChunk(text, hasTrailingCjk = false) {
  let current = text
  for (let i = 0; i < 4; i++) {
    if (!looksLikeMojibake(current)) break
    const decoded = tryDecodeOnce(current, { fatal: false })
    if (!decoded || decoded === current) break
    current = decoded
  }
  if (!looksLikeMojibake(current)) return current

  if (!hasTrailingCjk) return current

  let result = ''
  let rest = text
  while (rest && looksLikeMojibake(rest)) {
    let progressed = false
    for (let len = rest.length; len >= 3; len--) {
      const prefix = rest.slice(0, len)
      const fixed = fixMojibakeChunk(prefix, false)
      if (fixed !== prefix && !looksLikeMojibake(fixed)) {
        result += fixed
        rest = rest.slice(len)
        progressed = true
        break
      }
    }
    if (!progressed) break
  }
  return result || current
}

export function fixUtf8Mojibake(value) {
  if (!value || typeof value !== 'string') return value

  if (/[\u4e00-\u9fff]/.test(value) && looksLikeMojibake(value)) {
    const parts = value.split(/([\u4e00-\u9fff]+)/)
    let out = ''
    for (let i = 0; i < parts.length; i++) {
      const part = parts[i]
      if (!part) continue
      if (/^[\u4e00-\u9fff]+$/.test(part)) {
        out += part
        continue
      }
      const nextPart = parts[i + 1] || ''
      const hasTrailingCjk = /^[\u4e00-\u9fff]+$/.test(nextPart)
      out += fixMojibakeChunk(part, hasTrailingCjk)
    }
    return out
  }

  return fixMojibakeChunk(value, false)
}

export function sanitizeCharacterSettings(settings) {
  if (!settings || typeof settings !== 'object') return settings
  const out = {}
  for (const [key, value] of Object.entries(settings)) {
    out[key] = typeof value === 'string' ? fixUtf8Mojibake(value) : value
  }
  return out
}

export function sanitizeCharacter(character) {
  if (!character || typeof character !== 'object') return character
  return {
    ...character,
    name: typeof character.name === 'string' ? fixUtf8Mojibake(character.name) : character.name,
    settings: sanitizeCharacterSettings(character.settings),
  }
}
