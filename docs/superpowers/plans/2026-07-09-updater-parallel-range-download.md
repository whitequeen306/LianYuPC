# Updater Parallel Range Download Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Electron updater downloads use bounded parallel HTTP Range requests to approach 500 KB/s on slow single-connection routes, while preserving the existing single-request fallback and install/restart flow.

**Architecture:** Keep the change inside `frontend/electron/updater/updater.js`. Add small helpers for range probing, part planning, part download, merge, and fallback; keep IPC state shape compatible with the current renderer.

**Tech Stack:** Electron `net`, Node `fs`, Vitest tests under `frontend/electron/updater/__tests__/updater.test.js`.

---

### Task 1: Tests First

**Files:**
- Modify: `frontend/electron/updater/__tests__/updater.test.js`

- [ ] Add a failing test that a Range-capable server is downloaded via parallel `Range` requests and merged into the final installer path.
- [ ] Add a failing test that a non-Range server falls back to a normal single `GET`.
- [ ] Add a regression test that `updater:install` still calls the injected forced quit function after spawning the installer.
- [ ] Run `npm run test -- updater` from `frontend/` and confirm the new tests fail for missing parallel Range behavior.

### Task 2: Minimal Implementation

**Files:**
- Modify: `frontend/electron/updater/updater.js`

- [ ] Add constants for `UPDATE_DOWNLOAD_CONCURRENCY = 6` and retry count.
- [ ] Probe with `Range: bytes=0-0`; use `206` plus `Content-Range` total size as the signal for parallel mode.
- [ ] Split the installer size into six parts and download each with `Range: bytes=start-end` into `.part.N` files.
- [ ] Aggregate progress across parts and keep sending `percent`, `transferred`, `total`, `speedBytesPerSec`, `etaSeconds`, and `version`.
- [ ] Merge parts into the final installer only after every part completes and the total byte count matches.
- [ ] On unsupported Range or probe failure, use the existing single-request download path.
- [ ] Run the updater test file until green.

### Task 3: Verification

**Files:**
- Read/verify: `frontend/electron/updater/updater.js`
- Test: `frontend/electron/updater/__tests__/updater.test.js`

- [ ] Run targeted Vitest updater tests.
- [ ] Run frontend lint/test command if available and scoped enough.
- [ ] Inspect `git diff` to ensure only updater/test/plan files changed.
- [ ] Confirm install flow still uses existing spawn plus forced quit path and was not made dependent on download concurrency.
