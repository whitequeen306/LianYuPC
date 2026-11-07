import { describe, expect, test } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const currentDir = dirname(fileURLToPath(import.meta.url))
const settingsPagePath = resolve(currentDir, '../SettingsPage.vue')

describe('SettingsPage layout', () => {
  test('places the about entry before other settings sections', () => {
    const source = readFileSync(settingsPagePath, 'utf8')

    const headerIndex = source.indexOf('<header class="page-header">')
    const aboutIndex = source.indexOf('<!-- 关于 -->')
    const desktopIndex = source.indexOf('<!-- Desktop quick entry -->')
    const providerIndex = source.indexOf('<!-- Text Provider Section -->')
    const visionIndex = source.indexOf('<!-- Vision Provider Section -->')
    const diagnosticsIndex = source.indexOf('<!-- 诊断日志 -->')

    expect(headerIndex).toBeGreaterThan(-1)
    expect(aboutIndex).toBeGreaterThan(-1)
    expect(desktopIndex).toBeGreaterThan(-1)
    expect(providerIndex).toBeGreaterThan(-1)
    expect(visionIndex).toBeGreaterThan(-1)
    expect(diagnosticsIndex).toBeGreaterThan(-1)
    expect(aboutIndex).toBeGreaterThan(headerIndex)
    expect(aboutIndex).toBeLessThan(desktopIndex)
    expect(aboutIndex).toBeLessThan(providerIndex)
    expect(aboutIndex).toBeLessThan(diagnosticsIndex)
    expect(providerIndex).toBeLessThan(visionIndex)
  })

  test('places WeChat ClawBot under the QQ bridge row', () => {
    const source = readFileSync(settingsPagePath, 'utf8')
    const qqIndex = source.indexOf('goQqBridge')
    const wechatIndex = source.indexOf('goWechatBridge')
    expect(qqIndex).toBeGreaterThan(-1)
    expect(wechatIndex).toBeGreaterThan(qqIndex)
    expect(source).toContain('微信 ClawBot')
  })

  test('explains paused proactive messages without offering a switch', () => {
    const source = readFileSync(settingsPagePath, 'utf8')

    // 横幅只在后端熔断（proactiveEnabled === false）时出现，且不提供任何开关控件
    expect(source).toContain('class="quota-banner"')
    expect(source).toContain('quota.proactiveEnabled === false')
    expect(source).toContain('getModelQuota')
    expect(source).toContain("settings.quotaPausedTitle")
    expect(source).toContain("settings.quotaPausedDescNoWait")
    expect(source).toContain("settings.quotaPausedDesc")
    expect(source).not.toContain('quota-banner__switch')

    // 拉取失败必须静默：横幅是提示，不是错误弹窗
    const mountedIndex = source.indexOf('onMounted(async () => {')
    const callIndex = source.indexOf('getModelQuota()', mountedIndex)
    expect(mountedIndex).toBeGreaterThan(-1)
    expect(callIndex).toBeGreaterThan(mountedIndex)
    expect(source).toContain('.catch(() => { quota.value = null })')
  })
})
