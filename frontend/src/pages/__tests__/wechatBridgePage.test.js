import { describe, expect, test } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const currentDir = dirname(fileURLToPath(import.meta.url))
const pagePath = resolve(currentDir, '../WechatBridgePage.vue')

describe('WechatBridgePage', () => {
  test('web build shows desktop-only hint; Electron has install/start', () => {
    const source = readFileSync(pagePath, 'utf8')
    expect(source).toContain('v-if="!isElectron"')
    expect(source).toContain("t('wechatBridge.notElectron')")
    expect(source).toContain("t('wechatBridge.host.start')")
    expect(source).toContain('isElectronApp')
  })
})
