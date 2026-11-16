import { describe, expect, test } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import zh from '../../i18n/locales/zh.js'
import zhTW from '../../i18n/locales/zh-TW.js'
import en from '../../i18n/locales/en.js'
import ja from '../../i18n/locales/ja.js'

const currentDir = dirname(fileURLToPath(import.meta.url))
const pagePath = resolve(currentDir, '../QqChatPage.vue')
const source = readFileSync(pagePath, 'utf8')

describe('QqChatPage', () => {
  test('leads with a status bar that names the blocker', () => {
    expect(source).toContain('qq-status')
    expect(source).toContain("t('qqChat.statusNoModel')")
    expect(source).toContain("t('qqChat.statusIdle')")
    expect(source).toContain("t('qqChat.statusOk', { names })")
  })

  test('connects by QR, not by a 6-digit code', () => {
    expect(source).toContain('startQqBotTask')
    expect(source).toContain("QRCode.toDataURL")
    expect(source).toContain('qq-qr__image')
    // 扫码这一步要排在「选角色」之后，用户先知道自己在给谁扫码
    expect(source.indexOf("t('qqChat.character')")).toBeLessThan(source.indexOf('qq-qr__image'))
    // 6 位码入口已经从页面退役
    expect(source).not.toContain('issueQqBindCode')
    expect(source).not.toContain('qq-code__value')
  })

  test('binds the freshly scanned bot to the chosen character automatically', () => {
    expect(source).toContain("pollQqBotTask(")
    expect(source).toContain("result.status === 'completed'")
    expect(source).toContain('bindQqBotCharacter(result.appId, characterId.value)')
  })

  test('sticker frequency is adjustable in place, on the account card', () => {
    expect(source).toContain("t('qqChat.stickerFreq')")
    expect(source).toContain('@update:model-value="value => onChangeFrequency(item, value)"')
    expect(source).toContain('settings: { stickerFrequency: value }')
  })

  test('disconnecting removes the credentials, with a confirm step', () => {
    expect(source).toContain("t('qqChat.disconnectConfirm'")
    expect(source).toContain('removeQqBotAccount(item.appId)')
  })

  test('answers the four questions users actually ask', () => {
    for (const key of ['faqNoReplyQ', 'faqProactiveQ', 'faqGroupQ', 'faqAuditQ']) {
      expect(source).toContain(`t('qqChat.${key}')`)
    }
  })
})

describe('qqChat i18n', () => {
  const locales = { zh, 'zh-TW': zhTW, en, ja }
  const expected = Object.keys(zh.qqChat).sort()

  test('every locale carries the same keys', () => {
    for (const [name, locale] of Object.entries(locales)) {
      expect(Object.keys(locale.qqChat).sort(), `${name} qqChat keys`).toEqual(expected)
    }
  })

  test('no new key is left as an empty string', () => {
    for (const [name, locale] of Object.entries(locales)) {
      for (const [key, value] of Object.entries(locale.qqChat)) {
        expect(String(value).trim(), `${name}.qqChat.${key}`).not.toBe('')
      }
    }
  })

  test('copy avoids characters vue-i18n reads as syntax', () => {
    // @ 会被当成 linked key；花括号只在已知插值处允许，否则会编译失败、页面白屏
    const allowedPlaceholders = /\{(names|name|time)\}/g
    for (const [name, locale] of Object.entries(locales)) {
      for (const [key, value] of Object.entries(locale.qqChat)) {
        const text = String(value)
        expect(text.includes('@'), `${name}.qqChat.${key} 含 @`).toBe(false)
        expect(
          /[{}]/.test(text.replace(allowedPlaceholders, '')),
          `${name}.qqChat.${key} 含未声明插值`
        ).toBe(false)
      }
    }
  })

  test('every key the page uses exists in all locales', () => {
    const used = new Set()
    for (const match of source.matchAll(/t\('qqChat\.([A-Za-z0-9_]+)'/g)) used.add(match[1])
    expect(used.size).toBeGreaterThan(20)
    for (const [name, locale] of Object.entries(locales)) {
      for (const key of used) {
        expect(locale.qqChat[key], `${name} 缺 qqChat.${key}`).toBeTruthy()
      }
    }
  })
})
