import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const installerScript = fs.readFileSync(path.join(root, 'build', 'installer.nsh'), 'utf8')

describe('installer NSIS hooks', () => {
  it('forces updater-triggered silent installs back to the visible installer wizard', () => {
    const customInit = installerScript.match(/!macro customInit[\s\S]*?!macroend/)?.[0] || ''
    const ifSilentIndex = customInit.indexOf('IfSilent 0 doneForceVisibleInstall')
    const setSilentIndex = customInit.indexOf('SetSilent normal')
    const labelIndex = customInit.indexOf('doneForceVisibleInstall:')
    const taskkillIndex = customInit.indexOf('taskkill /F /IM "LianYu.exe" /T')
    expect(ifSilentIndex).toBeGreaterThan(-1)
    expect(setSilentIndex).toBeGreaterThan(ifSilentIndex)
    expect(labelIndex).toBeGreaterThan(setSilentIndex)
    expect(taskkillIndex).toBeGreaterThan(labelIndex)
    expect(installerScript).not.toContain('doneSilentLaunch')
  })
})
