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
    const providerIndex = source.indexOf('<!-- Provider Section -->')
    const diagnosticsIndex = source.indexOf('<!-- 诊断日志 -->')

    expect(headerIndex).toBeGreaterThan(-1)
    expect(aboutIndex).toBeGreaterThan(-1)
    expect(desktopIndex).toBeGreaterThan(-1)
    expect(providerIndex).toBeGreaterThan(-1)
    expect(diagnosticsIndex).toBeGreaterThan(-1)
    expect(aboutIndex).toBeGreaterThan(headerIndex)
    expect(aboutIndex).toBeLessThan(desktopIndex)
    expect(aboutIndex).toBeLessThan(providerIndex)
    expect(aboutIndex).toBeLessThan(diagnosticsIndex)
  })
})
