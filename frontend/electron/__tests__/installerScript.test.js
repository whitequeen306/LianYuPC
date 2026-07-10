import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const installerScript = fs.readFileSync(path.join(root, 'build', 'installer.nsh'), 'utf8')

describe('installer NSIS hooks', () => {
  it('launches LianYu after silent install completes', () => {
    expect(installerScript).toContain('!macro customInstall')
    expect(installerScript).toContain('IfSilent 0 doneSilentLaunch')
    expect(installerScript).toContain('Exec \'"$INSTDIR\\LianYu.exe"\'')
    expect(installerScript).not.toContain('cmd.exe /d /s /c start')
  })
})
