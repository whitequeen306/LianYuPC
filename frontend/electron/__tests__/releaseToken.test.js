import { describe, expect, it } from 'vitest'

import { extractGithubTokenFromCredentialFill, resolveGithubToken } from '../../scripts/electron-release-token.js'

describe('electron release token resolution', () => {
  it('prefers explicit GH_TOKEN environment variable', () => {
    const token = resolveGithubToken({
      env: { GH_TOKEN: 'ghp_env_token' },
      credentialFillOutput: 'protocol=https\nhost=github.com\npassword=gho_from_manager\n',
    })

    expect(token).toBe('ghp_env_token')
  })

  it('extracts token from git credential fill output', () => {
    const token = extractGithubTokenFromCredentialFill([
      'protocol=https',
      'host=github.com',
      'username=whitequeen306',
      'password=gho_from_manager',
      '',
    ].join('\n'))

    expect(token).toBe('gho_from_manager')
  })

  it('falls back to credential manager token when env is absent', () => {
    const token = resolveGithubToken({
      env: {},
      credentialFillOutput: 'protocol=https\nhost=github.com\npassword=gho_from_manager\n',
    })

    expect(token).toBe('gho_from_manager')
  })

  it('returns empty string when neither env nor credential manager yields a token', () => {
    const token = resolveGithubToken({
      env: {},
      credentialFillOutput: 'protocol=https\nhost=github.com\nusername=whitequeen306\n',
    })

    expect(token).toBe('')
  })
})
