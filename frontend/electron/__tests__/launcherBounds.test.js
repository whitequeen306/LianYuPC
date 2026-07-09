import { describe, expect, it } from 'vitest'

import {
  clampLauncherBoundsToWorkArea,
  isLauncherWithinWorkArea,
} from '../launcherBounds.js'

const workArea = { x: 0, y: 0, width: 1920, height: 1040 }

describe('launcherBounds', () => {
  it('keeps a launcher already flush above the taskbar', () => {
    const bounds = { x: 860, y: 832, width: 192, height: 208 }

    expect(clampLauncherBoundsToWorkArea(bounds, workArea)).toEqual({ x: 860, y: 832 })
  })

  it('clamps a launcher below the work area to the taskbar-safe edge', () => {
    const bounds = { x: 860, y: 900, width: 192, height: 208 }

    expect(clampLauncherBoundsToWorkArea(bounds, workArea)).toEqual({ x: 860, y: 832 })
  })

  it('validates saved positions against the taskbar-safe edge', () => {
    expect(isLauncherWithinWorkArea({ x: 860, y: 832, width: 192, height: 208 }, workArea)).toBe(true)
    expect(isLauncherWithinWorkArea({ x: 860, y: 833, width: 192, height: 208 }, workArea)).toBe(false)
  })

  it('can clamp only the vertical axis during drag moves', () => {
    const bounds = { x: 1940, y: 900, width: 192, height: 208 }

    expect(clampLauncherBoundsToWorkArea(bounds, workArea, { axis: 'y' })).toEqual({ x: 1940, y: 832 })
  })
})
