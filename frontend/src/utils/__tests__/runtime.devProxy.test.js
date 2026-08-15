import { afterEach, describe, expect, it, vi } from 'vitest'
import { apiBasePath, buildWsUrl, isElectronRuntime, shouldUseViteDevProxy } from '../runtime.js'

describe('electron:dev Vite proxy', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('uses same-origin /api and /ws so Chromium does not fetch 127.0.0.1 from localhost', () => {
    vi.stubGlobal('window', {
      electronAPI: { isElectron: true },
      location: {
        protocol: 'http:',
        host: 'localhost:5180',
        origin: 'http://localhost:5180',
      },
      navigator: { userAgent: 'Electron' },
    })
    expect(isElectronRuntime()).toBe(true)
    expect(shouldUseViteDevProxy()).toBe(true)
    expect(apiBasePath()).toBe('/api')
    expect(buildWsUrl()).toBe('ws://localhost:5180/ws')
  })
})
