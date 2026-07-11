# Updater Open Folder Fallback Design

## Goal

Replace the unstable in-app installer launch step with a stable fallback that downloads the installer to a known local directory and then guides the user to open that directory and manually run the installer.

## Problem Summary

The updater flow already succeeds through these stages on the user's machine:

- update check succeeds
- installer download succeeds
- installer file is staged under `C:\Users\hp\AppData\Roaming\lianyu-pc\updates\`

The failing step is only the transition from "downloaded" to "launch installer". Multiple approaches (`spawn(installer.exe)`, `powershell Start-Process`, and app exit timing adjustments) still produce machine-specific failures where the app reports success but the installer window never reliably appears for the user.

## Design Decision

Adopt a manual-install fallback as the default post-download action.

### New behavior

- Keep the existing update check and download pipeline unchanged.
- When the installer has been downloaded and verified, the updater enters a ready state that no longer tries to start the installer directly.
- The primary button changes from `打开安装向导` to `打开安装包目录`.
- Clicking the button opens the installer folder in Explorer and highlights the downloaded installer if possible.
- The app stays open; it does not force-quit itself.
- The dialog copy explicitly tells the user to double-click the downloaded `LianYu-Setup-x.x.x.exe` file to complete installation.

### Target folder

Use the already-proven stable download location:

- `app.getPath('userData')/updates`

On the user's machine this resolves to:

- `C:\Users\hp\AppData\Roaming\lianyu-pc\updates`

## Scope

### Included

- updater main-process IPC for "open installer folder"
- dialog copy and CTA label updates
- tests covering folder-open behavior and no forced quit

### Excluded

- further attempts to auto-launch the installer
- server-side update source changes
- installer package format changes

## UX Copy

- Ready headline remains version-oriented.
- Hint text changes to: installer downloaded, open folder, manually double-click installer.
- Busy/installing state is removed from the normal happy-path transition because the app is no longer attempting background launch.

## Error Handling

- If no downloaded installer is present, opening the folder action returns a structured error.
- If Explorer/shell folder open fails, the updater surfaces an error state with a retryable message.

## Testing

- Unit test main-process updater behavior for `openInstallerFolder` IPC.
- Verify the action opens the installer path/folder and does not call quit.
- Verify the ready-state dialog shows the new CTA and copy.
- Run targeted updater tests, full frontend/electron tests, and a local desktop package build.

## Self-review

- No placeholder requirements remain.
- Design intentionally chooses reliability over automation.
- Scope is minimal and uses existing download/storage behavior.
