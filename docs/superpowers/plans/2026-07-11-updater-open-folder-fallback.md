# Updater Open Folder Fallback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the broken in-app installer launch with a stable "open installer folder" fallback after download completes.

**Architecture:** Preserve the existing check/download/verify pipeline, but change the post-download action from launching the installer to opening the local installer directory via Electron shell integration. Update the renderer dialog to clearly instruct manual installation.

**Tech Stack:** Electron main process, Vue 3, Vitest, Element Plus

---

### Task 1: Add failing tests for the new folder-open updater flow

**Files:**
- Modify: `frontend/electron/updater/__tests__/updater.test.js`
- Modify: `frontend/src/composables/__tests__/useAppUpdater.test.js`

- [ ] **Step 1: Write the failing test**

```javascript
it('ready action opens installer folder and does not quit the app', async () => {
  const ret = await mocks.handleRegistry.get('updater:openInstallerFolder')()
  expect(ret.ok).toBe(true)
  expect(mocks.shellShowItemInFolder).toHaveBeenCalled()
  expect(mocks.quitAndInstall).not.toHaveBeenCalled()
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm run test -- electron/updater/__tests__/updater.test.js --run`
Expected: FAIL because the IPC and shell behavior do not exist yet.

- [ ] **Step 3: Write minimal implementation**

```javascript
ipcMain.handle('updater:openInstallerFolder', async () => {
  return { ok: false, error: 'not implemented' }
})
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm run test -- electron/updater/__tests__/updater.test.js --run`
Expected: PASS

### Task 2: Implement the fallback in updater main process and dialog UI

**Files:**
- Modify: `frontend/electron/updater/updater.js`
- Modify: `frontend/electron/preload.js`
- Modify: `frontend/src/composables/useAppUpdater.js`
- Modify: `frontend/src/components/AppUpdateDialog.vue`

- [ ] **Step 1: Write the failing test**

```javascript
test('ready state exposes open-folder action instead of install-now flow', () => {
  expect(source).toContain('打开安装包目录')
  expect(source).toContain('请双击安装包完成更新')
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm run test -- --run`
Expected: FAIL because current dialog still references opening the installer wizard.

- [ ] **Step 3: Write minimal implementation**

```javascript
openInstallerFolder: () => ipcRenderer.invoke('updater:openInstallerFolder')
```

```vue
<el-button v-if="state === 'ready'" type="primary" class="btn-cta" @click="openFolder">
  打开安装包目录
</el-button>
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm run test -- --run`
Expected: PASS

### Task 3: Verify behavior and build a desktop test package

**Files:**
- Modify: `frontend/package.json`
- Modify: `frontend/package-lock.json`

- [ ] **Step 1: Bump version for the test package**

```json
{
  "version": "0.2.283"
}
```

- [ ] **Step 2: Run verification**

Run: `npm run test -- electron/updater/__tests__/updater.test.js --run`
Expected: PASS

Run: `npm run test -- --run`
Expected: PASS

Run: `osv-scanner scan --lockfile "package-lock.json"`
Expected: No issues found

- [ ] **Step 3: Build the package**

Run: `npm run electron:build`
Expected: `frontend/release/v0.2.283/LianYu Setup 0.2.283.exe`

## Self-review

- Plan covers main-process IPC, preload, renderer CTA/copy, tests, and packaging.
- Flow intentionally avoids auto-launch complexity.
- No backend changes are required.
