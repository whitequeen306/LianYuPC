import { beforeEach, describe, expect, it, vi } from 'vitest'
import { schedulePostWindowStartup } from '../startupOrchestrator.js'

describe('schedulePostWindowStartup', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  it('defers startup follow-up work until the main window finishes loading', () => {
    const finishLoad = vi.fn()
    const mockWindow = {
      isDestroyed: () => false,
      webContents: {
        isLoading: () => true,
        once: vi.fn((event, handler) => {
          expect(event).toBe('did-finish-load')
          finishLoad.mockImplementation(handler)
        }),
      },
    }
    const patchDesktopRequestOrigin = vi.fn()
    const applyLaunchAtLogin = vi.fn()
    const initUpdater = vi.fn()
    const ensureTray = vi.fn()
    const scheduleAuxWindowPrewarm = vi.fn()

    schedulePostWindowStartup({
      mainWindow: mockWindow,
      patchDesktopRequestOrigin,
      applyLaunchAtLogin,
      initUpdater,
      ensureTray,
      scheduleAuxWindowPrewarm,
    })

    expect(patchDesktopRequestOrigin).not.toHaveBeenCalled()
    expect(applyLaunchAtLogin).not.toHaveBeenCalled()
    expect(initUpdater).not.toHaveBeenCalled()
    expect(ensureTray).not.toHaveBeenCalled()
    expect(scheduleAuxWindowPrewarm).not.toHaveBeenCalled()

    finishLoad()
    vi.runAllTimers()

    expect(patchDesktopRequestOrigin).toHaveBeenCalledTimes(1)
    expect(applyLaunchAtLogin).toHaveBeenCalledTimes(1)
    expect(initUpdater).toHaveBeenCalledWith(mockWindow)
    expect(ensureTray).toHaveBeenCalledTimes(1)
    expect(scheduleAuxWindowPrewarm).toHaveBeenCalledTimes(1)
  })

  it('still defers follow-up work by one tick when the main window is already loaded', () => {
    const mockWindow = {
      isDestroyed: () => false,
      webContents: {
        isLoading: () => false,
        once: vi.fn(),
      },
    }
    const patchDesktopRequestOrigin = vi.fn()
    const applyLaunchAtLogin = vi.fn()
    const initUpdater = vi.fn()
    const ensureTray = vi.fn()
    const scheduleAuxWindowPrewarm = vi.fn()

    schedulePostWindowStartup({
      mainWindow: mockWindow,
      patchDesktopRequestOrigin,
      applyLaunchAtLogin,
      initUpdater,
      ensureTray,
      scheduleAuxWindowPrewarm,
    })

    expect(initUpdater).not.toHaveBeenCalled()

    vi.runAllTimers()

    expect(patchDesktopRequestOrigin).toHaveBeenCalledTimes(1)
    expect(applyLaunchAtLogin).toHaveBeenCalledTimes(1)
    expect(initUpdater).toHaveBeenCalledWith(mockWindow)
    expect(ensureTray).toHaveBeenCalledTimes(1)
    expect(scheduleAuxWindowPrewarm).toHaveBeenCalledTimes(1)
  })
})
