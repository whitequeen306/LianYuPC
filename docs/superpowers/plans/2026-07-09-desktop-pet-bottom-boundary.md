# Desktop Pet Bottom Boundary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the desktop pet settle flush above the taskbar without bouncing upward after downward drags.

**Architecture:** Extract launcher position boundary math into a pure Electron helper and call it from `main.js`. Keep front-end drag gesture code unchanged; main process clamps candidate drag positions vertically before moving the window so the launcher never enters the taskbar area and cannot snap back on release. Horizontal movement is left free during drag to preserve multi-display movement.

**Tech Stack:** Electron main process, Vue launcher renderer, Vitest.

---

## File Structure

- Create: `frontend/electron/launcherBounds.js` for pure launcher bounds validation and clamping.
- Create: `frontend/electron/__tests__/launcherBounds.test.js` for focused Vitest coverage.
- Modify: `frontend/electron/main.js` to import and use the helper.

### Task 1: Add Boundary Helper Tests

- [x] Write failing tests for bottom-edge clamping, vertical-only drag clamping, and saved-position validation.
- [x] Verify the tests fail before the helper exists.

### Task 2: Implement Helper

- [x] Add `clampLauncherBoundsToWorkArea(bounds, workArea, options)`.
- [x] Add `isLauncherWithinWorkArea(bounds, workArea)`.
- [x] Verify helper tests pass.

### Task 3: Wire Helper Into Main Process

- [x] Import helper functions in `frontend/electron/main.js`.
- [x] Replace saved-position validation with `isLauncherWithinWorkArea`.
- [x] Replace final clamp math with `clampLauncherBoundsToWorkArea`.
- [x] Clamp `desktop:launcher-drag-move`, `desktop:move-launcher-by-delta`, and `desktop:set-launcher-screen-position` candidate positions vertically before `setPosition`.

### Task 4: Final Verification

- [x] Run `npm test -- --run electron/__tests__/launcherBounds.test.js`.
- [x] Run broader frontend verification with `npm test -- --run`.
- [x] Inspect diff for scoped changes.
