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

  // 表情包先搁置：QQ 页不再出现频率开关，避免给用户一个「调了也看不到变化」的旋钮
  test('sticker frequency control is gone from the QQ page', () => {
    expect(source).not.toContain('onChangeFrequency')
    expect(source).not.toContain('stickerFrequency')
  })

  test('disconnecting removes the credentials, with a confirm step', () => {
    expect(source).toContain("t('qqChat.disconnectConfirm'")
    expect(source).toContain('removeQqBotAccount(item.appId)')
  })

  test('back button never becomes a silent no-op', () => {
    // 桌面端整页载入时历史里没有上一页，裸 router.back() 点了没反应（线上报过两次）。
    // 现在统一走 navigateBack：先试 back，没走就 push 兜底。
    expect(source).toContain("import { navigateBack } from '@/utils/navBack'")
    expect(source).toContain('navigateBack(router, window.history.state)')
    expect(source).not.toMatch(/function goBack\(\)\s*\{\s*router\.back\(\)\s*\}/)
    expect(source).not.toMatch(/function goBack\(\)\s*\{\s*const next = resolveBack/)
  })

  test('answers the four questions users actually ask', () => {
    for (const key of ['faqNoReplyQ', 'faqProactiveQ', 'faqGroupQ', 'faqAuditQ']) {
      expect(source).toContain(`t('qqChat.${key}')`)
    }
  })

  test('surfaces why a scanned bot is still offline instead of showing green', () => {
    // 线上真事：扫码成功（后端已落库）但网关那条 WS 没连上（腾讯限流 / token 被重置），
    // 页面只显示绿色的「已连上」，用户对着手机干等四五分钟。
    expect(source).toContain("t('qqChat.statusFailed'")
    expect(source).toContain('item.lastError')
    expect(source).toContain('statusFailedAction')
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
    // error 是「连不上的原因」原文，来自后端 lastError（如 gateway_url_failed_401:…）。
    const allowedPlaceholders = /\{(names|name|time|error)\}/g
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
