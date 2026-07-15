import { beforeEach, describe, expect, it, vi } from 'vitest'

const mockGetPath = vi.fn()
const mockSetLoginItemSettings = vi.fn()
const state = new Map()

vi.mock('electron', () => ({
  app: {
    getPath: mockGetPath,
    setLoginItemSettings: mockSetLoginItemSettings,
  },
}))

vi.mock('fs', () => ({
  default: {
    readFileSync: vi.fn((filePath) => {
      if (!state.has(filePath)) throw new Error('missing')
      return state.get(filePath)
    }),
    writeFileSync: vi.fn((filePath, content) => {
      state.set(filePath, content)
    }),
    mkdirSync: vi.fn(),
  },
}))

describe('desktopSettings writeDesktopSettings', () => {
  beforeEach(() => {
    vi.resetModules()
    state.clear()
    mockSetLoginItemSettings.mockReset()
    mockGetPath.mockReturnValue('C:/tmp/lianyu-user-data')
  })

  it('accepts elysia as a valid launcher pet id', async () => {
    const { writeDesktopSettings, readDesktopSettings } = await import('../desktopSettings.js')

    const next = writeDesktopSettings({ launcherPetId: 'elysia' })

    expect(next.launcherPetId).toBe('elysia')
    expect(readDesktopSettings().launcherPetId).toBe('elysia')
  })
})
