# Self-Hosted Updater Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the update runtime path from GitHub proxy to LianYu public MinIO files and replace inline About-page updater state with a global dialog.

**Architecture:** The backend exposes update artifacts as restricted public files under `updates/`. The Electron main process reads `/api/public/files/updates/latest.yml`, resolves relative asset URLs against that directory, streams the installer to temp, and broadcasts progress metrics. Vue mounts one global dialog in `App.vue`; About page keeps only a manual check button.

**Tech Stack:** Spring MVC, MinIO public file service, Electron `net.request`, Vue 3, Element Plus, Vitest, electron-builder.

---

### Task 1: Backend Public Updates Source

**Files:**
- Modify: `backend/lianyu-service/src/main/java/com/lianyu/service/storage/FileStorageService.java`
- Modify: `backend/lianyu-web/src/main/java/com/lianyu/web/controller/PublicFileController.java`
- Delete: `backend/lianyu-web/src/main/java/com/lianyu/web/controller/UpdaterController.java`
- Modify: `backend/lianyu-app/src/main/resources/application.yml`

- [ ] Add `updates/` to the safe object-key regex with only `latest.yml`, `.exe`, and `.exe.blockmap`.
- [ ] Return safe content types for update files.
- [ ] Use attachment disposition for `.exe` and `.blockmap`, inline for `latest.yml`.
- [ ] Delete old GitHub updater proxy controller and config.

### Task 2: Electron Updater Runtime

**Files:**
- Modify: `frontend/electron/updater/updater.js`
- Modify: `frontend/electron/updater/__tests__/updater.test.js`

- [ ] Change latest URL to `${apiOrigin}/api/public/files/updates/latest.yml`.
- [ ] Resolve relative asset URLs against the latest.yml URL.
- [ ] Add speed and ETA metrics during download.
- [ ] Validate complete download length before setting `ready`.
- [ ] Improve install state message before quitting.

### Task 3: Global Update Dialog

**Files:**
- Create: `frontend/src/components/AppUpdateDialog.vue`
- Modify: `frontend/src/components/AppUpdateButton.vue`
- Modify: `frontend/src/App.vue`
- Modify: `frontend/src/pages/AboutPage.vue`

- [ ] Move update dialog UI into `AppUpdateDialog.vue` mounted globally in `App.vue`.
- [ ] Keep `AppUpdateButton.vue` as compact manual check entry only.
- [ ] Show progress, speed, ETA, ready-to-install, installing, and retry states in the global dialog.
- [ ] Remove inline progress bar and GitHub fallback link from About-page area.

### Task 4: Release Asset Upload

**Files:**
- Create: `scripts/_upload_update_assets.py`
- Modify: `frontend/scripts/electron-release.mjs`
- Modify: `frontend/scripts/electron-pack.mjs`
- Modify: `frontend/package.json`
- Modify: `frontend/package-lock.json`

- [ ] Upload installer, blockmap, and latest.yml to MinIO `updates/` after release packaging.
- [ ] Upload installer/blockmap first and latest.yml last.
- [ ] Remove `electron-updater` dependency and esbuild external entry.

### Task 5: Verification and Cleanup

**Files:**
- Modify: `CLAUDE.md`
- Delete: `scripts/_deploy_updater_proxy.py`
- Delete: `scripts/_cloud_logs_diag.py`

- [ ] Update project release documentation for the self-hosted runtime update path.
- [ ] Run frontend tests.
- [ ] Run frontend build.
- [ ] Run backend compile/tests for changed modules.
- [ ] Grep for obsolete `/api/public/updater` and `electron-updater` runtime references.
