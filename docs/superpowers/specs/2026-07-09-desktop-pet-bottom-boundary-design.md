# Desktop Pet Bottom Boundary Design

## Goal

Fix desktop pet dragging so the pet can settle flush above the Windows taskbar without jumping upward on later downward drags.

## Confirmed Behavior

- The desktop pet should not cover the taskbar.
- The pet should be draggable down to the bottom of the active display work area.
- Releasing the drag at the bottom must not bounce the pet upward beyond the taskbar-safe edge.
- A saved bottom-edge position should remain valid on restart.

## Root Cause

The Electron main process allowed drag moves to place the launcher outside `display.workArea`, then clamped the window only after drag end or later reset events. Because `workArea` excludes the taskbar, a downward drag could temporarily move below the taskbar-safe edge and then snap back upward when clamped. Repeating the downward drag reproduced the same snap.

## Design

Move launcher boundary calculation into a small pure helper used by the main process. The helper computes the top-left window position from current window bounds and the display work area.

Main process movement now clamps candidate positions vertically before calling `setPosition`, which prevents the launcher from entering the taskbar area while preserving horizontal movement across displays. Drag end still performs a final full clamp, but it should already be a no-op when the user drags to the bottom edge. Saved positions are validated with the same helper before restore.

## Testing

Add Vitest coverage for the boundary helper:

- a launcher at the work-area bottom remains there,
- a launcher below the work-area bottom clamps to the taskbar-safe edge,
- drag-time clamping can affect only the vertical axis,
- saved-position validation uses the same bounds math as clamping.

## Scope

This change is limited to Electron launcher positioning. It does not change pet assets, taskbar overlap policy, picker behavior, or frontend drag gesture handling.
