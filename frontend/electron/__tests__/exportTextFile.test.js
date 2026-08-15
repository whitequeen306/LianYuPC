import { describe, expect, it } from 'vitest'
import { sanitizeExportFileName, validateExportText } from '../exportTextFile.js'

describe('sanitizeExportFileName', () => {
  it('keeps a normal Chinese default name', () => {
    expect(sanitizeExportFileName('与甘雨的聊天记录--恋语.txt')).toBe('与甘雨的聊天记录--恋语.txt')
  })

  it('strips path pieces and illegal characters', () => {
    expect(sanitizeExportFileName('与*:甘雨.txt')).toBe('与__甘雨.txt')
    expect(sanitizeExportFileName('folder/与甘雨的聊天记录--恋语.txt')).toBe('与甘雨的聊天记录--恋语.txt')
  })

  it('forces txt and rejects reserved device names', () => {
    expect(sanitizeExportFileName('con')).toBe('_con.txt')
    expect(sanitizeExportFileName('../secret.log')).toBe('secret.txt')
  })
})

describe('validateExportText', () => {
  it('rejects empty or non-string payloads', () => {
    expect(validateExportText('')).toEqual({ ok: false, reason: 'empty' })
    expect(validateExportText(null)).toEqual({ ok: false, reason: 'invalid_content' })
  })

  it('accepts normal transcripts', () => {
    expect(validateExportText('{用户}：你好')).toEqual({ ok: true })
  })
})
