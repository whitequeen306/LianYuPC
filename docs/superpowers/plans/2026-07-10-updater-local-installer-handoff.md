# Updater Local Installer Handoff Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stage downloaded update installers in a stable local app-owned directory and launch them directly, so clicking install reliably opens the installer wizard.

**Architecture:** Keep the existing manual updater flow and only replace the handoff step that launches the installer. Downloaded installers will be stored under Electron `userData/updates`, validated there, and launched with a direct detached process instead of `cmd.exe /c start` from `%Temp%`.

**Tech Stack:** Electron main process, Node.js `fs`/`path`/`child_process`, Vitest.

---

### Task 1: Lock the New Handoff Contract With Tests

**Files:**
- Modify: `frontend/electron/updater/__tests__/updater.test.js`
- Test: `frontend/electron/updater/__tests__/updater.test.js`

- [ ] **Step 1: Add a failing test for staging into userData updates**

Add assertions that the downloaded installer path is `/tmp/lianyu-test/updates/LianYu-Setup-0.2.260.exe` rather than the temp subdirectory path.

- [ ] **Step 2: Add a failing test for direct executable launch**

Add assertions that install uses `spawn('/tmp/lianyu-test/updates/LianYu-Setup-0.2.260.exe', [], { detached: true, shell: false, stdio: 'ignore', windowsHide: false })` rather than `cmd.exe /c start`.

- [ ] **Step 3: Run the focused test file and verify it fails for the expected reason**

Run: `npm run test -- electron/updater/__tests__/updater.test.js --run`
Expected: FAIL because updater still downloads into `temp/lianyu-updater` and still launches through `cmd.exe`.

### Task 2: Implement Stable Local Installer Staging

**Files:**
- Modify: `frontend/electron/updater/updater.js`
- Test: `frontend/electron/updater/__tests__/updater.test.js`

- [ ] **Step 1: Change installer staging directory**

Update the download path from `app.getPath('temp')/lianyu-updater` to `app.getPath('userData')/updates` while keeping the validated versioned filename.

- [ ] **Step 2: Change installer launch to direct spawn**

Replace `cmd.exe /c start` with a direct `spawn(installerPath, [], { detached: true, stdio: 'ignore', windowsHide: false, shell: false })` handoff.

- [ ] **Step 3: Keep the existing delayed app shutdown**

Preserve the current `setTimeout(..., 500)` quit behavior so the installer process is launched first and the app exits after handoff.

- [ ] **Step 4: Run the focused test file and verify it passes**

Run: `npm run test -- electron/updater/__tests__/updater.test.js --run`
Expected: PASS.

### Task 3: Full Verification and Packaging

**Files:**
- Modify: `frontend/electron/updater/updater.js`
- Modify: `frontend/electron/updater/__tests__/updater.test.js`

- [ ] **Step 1: Run the full frontend/electron unit suite**

Run: `npm run test -- --run`
Expected: PASS with zero failed tests.

- [ ] **Step 2: Build the Electron installer**

Run: `npm run electron:build`
Expected: PASS and produce a new installer under `frontend/release/v<version>/`.

- [ ] **Step 3: Request code review**

Review the two updater files for correctness, readability, architecture fit, security, and performance before claiming completion.
