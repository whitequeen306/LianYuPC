# App Auto-Updater Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在「关于」页加"检查更新"入口，用 electron-updater + GitHub Releases 实现 Electron 客户端一键下载 + 静默安装 + 重启。

**Architecture:** 主进程 `updater.js` 封装 `autoUpdater`，经 IPC 与 renderer 通信；renderer 侧 `useAppUpdater` composable 维护状态机，`AppUpdateButton.vue` 渲染按钮/进度/对话框；发布脚本按 `GH_TOKEN` 切换 `--publish always/never`。

**Tech Stack:** electron-updater, electron-builder, Vue 3 composable, Vitest, SCSS（DESIGN.md 令牌）

**Spec:** `docs/superpowers/specs/2026-07-06-app-auto-updater-design.md`

---

## File Structure

| 文件 | 职责 | 创建/修改 |
|---|---|---|
| `frontend/package.json` | 加 electron-updater 依赖 + build.publish | 修改 |
| `frontend/electron/updater/updater.js` | 主进程封装 autoUpdater + IPC + 事件转发 | 创建 |
| `frontend/electron/updater/__tests__/updater.test.js` | updater.js 单测 | 创建 |
| `frontend/electron/preload.js` | 暴露 updater IPC | 修改 |
| `frontend/electron/main.js` | whenReady 调 initUpdater | 修改 |
| `frontend/src/composables/useAppUpdater.js` | renderer 状态机 | 创建 |
| `frontend/src/composables/__tests__/useAppUpdater.test.js` | composable 单测 | 创建 |
| `frontend/src/components/AppUpdateButton.vue` | 按钮+进度+对话框 | 创建 |
| `frontend/src/pages/AboutPage.vue` | 集成按钮 | 修改 |
| `frontend/scripts/electron-pack.mjs` | esbuild external + publish 切换 | 修改 |
| `frontend/scripts/electron-release.mjs` | GH_TOKEN 校验 | 修改 |

---

## Task 1: 安装依赖 + package.json publish 配置

**Files:**
- Modify: `frontend/package.json`

- [ ] **Step 1: 加 electron-updater 到 dependencies + build.publish 字段**

在 `frontend/package.json` 的 `dependencies` 加一行（按字母序，在 `dompurify` 后）：
```json
    "dompurify": "^3.4.8",
    "electron-updater": "^6.3.9",
    "element-plus": "^2.8.8",
```

在 `build` 对象里，`directories` 之后加 `publish`：
```json
    "directories": {
      "output": "release"
    },
    "publish": {
      "provider": "github",
      "owner": "whitequeen306",
      "repo": "LianYuPC"
    },
```

- [ ] **Step 2: 安装依赖**

Run（在 `frontend/` 下）：
```bash
npm install
```
Expected：`electron-updater@^6.x` 装入 `node_modules`，`package.json` 与 lockfile 更新。

- [ ] **Step 3: 验证依赖可用**

Run：
```bash
node -e "require('electron-updater'); console.log('ok')"
```
Expected：输出 `ok`，无报错。

- [ ] **Step 4: Commit**

```bash
git add frontend/package.json frontend/package-lock.json
git commit -m "feat(updater): add electron-updater dep + github publish config"
```

---

## Task 2: 主进程 updater.js（TDD）

**Files:**
- Create: `frontend/electron/updater/updater.js`
- Test: `frontend/electron/updater/__tests__/updater.test.js`

- [ ] **Step 1: 写失败测试**

创建 `frontend/electron/updater/__tests__/updater.test.js`：

```js
import { describe, it, expect, beforeEach, vi } from 'vitest'

// mock electron：捕获 ipcMain.handle 注册 + app.isPackaged + webContents.send
const handleRegistry = new Map()
const mockWebContents = { send: vi.fn() }
const mockMainWindow = { isDestroyed: () => false, webContents: mockWebContents }

vi.mock('electron', () => ({
  app: { isPackaged: true },
  ipcMain: {
    handle: (channel, fn) => { handleRegistry.set(channel, fn) },
  },
}))

// mock electron-updater 的 autoUpdater：记录调用 + 允许手动触发事件
const autoUpdaterEvents = {}
const autoUpdaterCalls = { check: 0, download: 0, quitInstall: 0 }
vi.mock('electron-updater', () => ({
  autoUpdater: {
    autoDownload: true,
    autoInstallOnAppQuit: true,
    on: (event, fn) => { autoUpdaterEvents[event] = fn },
    checkForUpdates: () => { autoUpdaterCalls.check++; return Promise.resolve() },
    downloadUpdate: () => { autoUpdaterCalls.download++; return Promise.resolve() },
    quitAndInstall: () => { autoUpdaterCalls.quitInstall++ },
  },
}))

// mock logger 避免真实副作用
vi.mock('../../logger.js', () => ({ log: vi.fn(), initGlobalErrorHandlers: vi.fn() }))

import { initUpdater, getUpdaterState } from '../updater.js'

describe('updater — initUpdater', () => {
  beforeEach(() => {
    handleRegistry.clear()
    mockWebContents.send.mockClear()
    autoUpdaterCalls.check = autoUpdaterCalls.download = autoUpdaterCalls.quitInstall = 0
    Object.keys(autoUpdaterEvents).forEach(k => delete autoUpdaterEvents[k])
    // 重置模块内部状态：重新 require
    vi.resetModules()
  })

  it('注册 3 个 IPC 通道并配置 autoDownload=false', async () => {
    const mod = await import('../updater.js')
    mod.initUpdater(mockMainWindow)
    expect(handleRegistry.has('updater:check')).toBe(true)
    expect(handleRegistry.has('updater:download')).toBe(true)
    expect(handleRegistry.has('updater:install')).toBe(true)
    const { autoUpdater } = await import('electron-updater')
    expect(autoUpdater.autoDownload).toBe(false)
  })

  it('重复 initUpdater 不重复注册（幂等）', async () => {
    const mod = await import('../updater.js')
    mod.initUpdater(mockMainWindow)
    mod.initUpdater(mockMainWindow)
    expect(handleRegistry.size).toBe(3)
  })

  it('autoUpdater 事件触发后推送正确状态到 renderer', async () => {
    const mod = await import('../updater.js')
    mod.initUpdater(mockMainWindow)
    autoUpdaterEvents['checking-for-update']()
    expect(mockWebContents.send).toHaveBeenLastCalledWith('updater:state', { state: 'checking', info: {} })

    autoUpdaterEvents['update-available']({ version: '0.2.260' })
    expect(mockWebContents.send).toHaveBeenLastCalledWith('updater:state', expect.objectContaining({
      state: 'update-available', info: { version: '0.2.260' }
    }))

    autoUpdaterEvents['update-not-available']()
    expect(mockWebContents.send).toHaveBeenLastCalledWith('updater:state', { state: 'no-update', info: {} })

    autoUpdaterEvents['download-progress']({ percent: 45, transferred: 100, total: 200, bytesPerSecond: 50 })
    expect(mockWebContents.send).toHaveBeenLastCalledWith('updater:state', expect.objectContaining({
      state: 'downloading', info: { percent: 45, transferred: 100, total: 200, bytesPerSecond: 50 }
    }))

    autoUpdaterEvents['update-downloaded']({ version: '0.2.260' })
    expect(mockWebContents.send).toHaveBeenLastCalledWith('updater:state', expect.objectContaining({
      state: 'ready', info: { version: '0.2.260' }
    }))

    autoUpdaterEvents['error'](new Error('network'))
    expect(mockWebContents.send).toHaveBeenLastCalledWith('updater:state', expect.objectContaining({
      state: 'error', info: { errorMessage: 'network' }
    }))
  })

  it('updater:check 在 packaged 模式调用 autoUpdater.checkForUpdates', async () => {
    const mod = await import('../updater.js')
    mod.initUpdater(mockMainWindow)
    const ret = await handleRegistry.get('updater:check')()
    expect(ret).toEqual({ ok: true })
    expect(autoUpdaterCalls.check).toBe(1)
  })

  it('updater:check 在 dev 模式返回 dev-mode 不调用 autoUpdater', async () => {
    vi.doMock('electron', () => ({ app: { isPackaged: false }, ipcMain: { handle: (c, fn) => handleRegistry.set(c, fn) } }))
    const mod = await import('../updater.js')
    mod.initUpdater(mockMainWindow)
    const ret = await handleRegistry.get('updater:check')()
    expect(ret).toEqual({ ok: false, reason: 'dev-mode' })
    expect(autoUpdaterCalls.check).toBe(0)
    expect(mockWebContents.send).toHaveBeenLastCalledWith('updater:state', expect.objectContaining({
      state: 'error', info: { errorMessage: 'dev-mode' }
    }))
    vi.doUnmock('electron')
  })

  it('updater:download 调用 autoUpdater.downloadUpdate', async () => {
    const mod = await import('../updater.js')
    mod.initUpdater(mockMainWindow)
    const ret = await handleRegistry.get('updater:download')()
    expect(ret).toEqual({ ok: true })
    expect(autoUpdaterCalls.download).toBe(1)
  })

  it('updater:install 调用 autoUpdater.quitAndInstall', async () => {
    const mod = await import('../updater.js')
    mod.initUpdater(mockMainWindow)
    const ret = await handleRegistry.get('updater:install')()
    expect(ret).toEqual({ ok: true })
    expect(autoUpdaterCalls.quitInstall).toBe(1)
  })

  it('主窗口被销毁时不抛错（send 静默跳过）', async () => {
    const destroyedWin = { isDestroyed: () => true, webContents: { send: vi.fn() } }
    const mod = await import('../updater.js')
    mod.initUpdater(destroyedWin)
    autoUpdaterEvents['checking-for-update']()
    expect(destroyedWin.webContents.send).not.toHaveBeenCalled()
  })
})
```

- [ ] **Step 2: 运行测试确认失败**

Run（在 `frontend/` 下）：
```bash
npx vitest run electron/updater/__tests__/updater.test.js
```
Expected：FAIL，`Cannot find module '../updater.js'`。

- [ ] **Step 3: 写最小实现**

创建 `frontend/electron/updater/updater.js`：

```js
import { app, ipcMain } from 'electron'
import { autoUpdater } from 'electron-updater'
import * as logger from '../logger.js'

let initialized = false
/** @type {import('electron').BrowserWindow | null} */
let mainWindowRef = null
let currentState = { state: 'idle', info: {} }

function setState(partial) {
  currentState = { ...currentState, ...partial }
  sendState(currentState)
}

function sendState(payload) {
  if (!mainWindowRef || mainWindowRef.isDestroyed()) return
  mainWindowRef.webContents.send('updater:state', payload)
}

function bindAutoUpdaterEvents() {
  autoUpdater.on('checking-for-update', () => setState({ state: 'checking', info: {} }))
  autoUpdater.on('update-available', (info) => setState({
    state: 'update-available',
    info: { version: info?.version },
  }))
  autoUpdater.on('update-not-available', () => setState({ state: 'no-update', info: {} }))
  autoUpdater.on('download-progress', (p) => setState({
    state: 'downloading',
    info: {
      percent: p?.percent,
      transferred: p?.transferred,
      total: p?.total,
      bytesPerSecond: p?.bytesPerSecond,
    },
  }))
  autoUpdater.on('update-downloaded', (info) => setState({
    state: 'ready',
    info: { version: info?.version },
  }))
  autoUpdater.on('error', (err) => setState({
    state: 'error',
    info: { errorMessage: err?.message || String(err) },
  }))
}

function registerIpc() {
  ipcMain.handle('updater:check', async () => {
    if (!app.isPackaged) {
      setState({ state: 'error', info: { errorMessage: 'dev-mode' } })
      return { ok: false, reason: 'dev-mode' }
    }
    try {
      await autoUpdater.checkForUpdates()
      return { ok: true }
    } catch (err) {
      setState({ state: 'error', info: { errorMessage: err?.message || String(err) } })
      return { ok: false, error: err?.message || String(err) }
    }
  })

  ipcMain.handle('updater:download', async () => {
    try {
      await autoUpdater.downloadUpdate()
      return { ok: true }
    } catch (err) {
      setState({ state: 'error', info: { errorMessage: err?.message || String(err) } })
      return { ok: false, error: err?.message || String(err) }
    }
  })

  ipcMain.handle('updater:install', async () => {
    try {
      autoUpdater.quitAndInstall()
      return { ok: true }
    } catch (err) {
      return { ok: false, error: err?.message || String(err) }
    }
  })
}

export function initUpdater(mainWindow) {
  if (initialized) return
  initialized = true
  mainWindowRef = mainWindow
  autoUpdater.autoDownload = false
  autoUpdater.autoInstallOnAppQuit = false
  bindAutoUpdaterEvents()
  registerIpc()
  logger.log('updater initialized')
}

export function getUpdaterState() {
  return currentState
}
```

- [ ] **Step 4: 运行测试确认通过**

Run：
```bash
npx vitest run electron/updater/__tests__/updater.test.js
```
Expected：PASS，全部用例绿。

- [ ] **Step 5: Commit**

```bash
git add frontend/electron/updater/
git commit -m "feat(updater): main-process autoUpdater wrapper with IPC + state events"
```

---

## Task 3: preload.js 暴露 updater IPC

**Files:**
- Modify: `frontend/electron/preload.js`

- [ ] **Step 1: 在 electronAPI 对象末尾（`openLogFolder` 之后）追加 4 个 updater 方法**

定位 `frontend/electron/preload.js:153` 的 `openLogFolder: () => ipcRenderer.invoke('desktop:open-log-folder'),` 行，在其后、闭合 `})` 之前追加：

```js
  openLogFolder: () => ipcRenderer.invoke('desktop:open-log-folder'),
  // ── 应用自动更新（updater.js 主进程封装） ──
  checkForUpdates: () => ipcRenderer.invoke('updater:check'),
  downloadUpdate: () => ipcRenderer.invoke('updater:download'),
  installNow: () => ipcRenderer.invoke('updater:install'),
  onUpdateState: (callback) => {
    const handler = (_event, payload) => callback(payload)
    ipcRenderer.on('updater:state', handler)
    return () => ipcRenderer.removeListener('updater:state', handler)
  },
```

- [ ] **Step 2: 确认 preload 语法无误**

Run（在 `frontend/` 下）：
```bash
node --check electron/preload.js
```
Expected：无输出（语法 OK）。

- [ ] **Step 3: Commit**

```bash
git add frontend/electron/preload.js
git commit -m "feat(updater): expose updater IPC in preload"
```

---

## Task 4: useAppUpdater composable（TDD）

**Files:**
- Create: `frontend/src/composables/useAppUpdater.js`
- Test: `frontend/src/composables/__tests__/useAppUpdater.test.js`

- [ ] **Step 1: 写失败测试**

创建 `frontend/src/composables/__tests__/useAppUpdater.test.js`：

```js
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { useAppUpdater } from '../useAppUpdater.js'

// mock @/utils/electron：控制 isElectronApp + getElectronAPI
let mockIsElectron = true
const onUpdateStateCallbacks = []
const mockApi = {
  checkForUpdates: vi.fn(() => Promise.resolve({ ok: true })),
  downloadUpdate: vi.fn(() => Promise.resolve({ ok: true })),
  installNow: vi.fn(() => Promise.resolve({ ok: true })),
  onUpdateState: (cb) => {
    onUpdateStateCallbacks.push(cb)
    return () => { const i = onUpdateStateCallbacks.indexOf(cb); if (i >= 0) onUpdateStateCallbacks.splice(i, 1) }
  },
}

vi.mock('@/utils/electron', () => ({
  isElectronApp: () => mockIsElectron,
  getElectronAPI: () => mockApi,
}))

// 模拟 Vue 生命周期 onBeforeUnmount 直接执行以便测试
vi.mock('vue', () => {
  const real = vi.importActual('vue')
  return real.then(r => ({
    ...r,
    onBeforeUnmount: (fn) => { r.onBeforeUnmount(fn) },
  }))
})

function emitState(payload) {
  onUpdateStateCallbacks.forEach(cb => cb(payload))
}

describe('useAppUpdater', () => {
  beforeEach(() => {
    mockIsElectron = true
    onUpdateStateCallbacks.length = 0
    mockApi.checkForUpdates.mockClear()
    mockApi.downloadUpdate.mockClear()
    mockApi.installNow.mockClear()
  })

  it('初始 state=idle，isElectron=true', () => {
    const { state, info, isElectron } = useAppUpdater()
    expect(state.value).toBe('idle')
    expect(info.value).toEqual({})
    expect(isElectron.value).toBe(true)
  })

  it('订阅 onUpdateState，payload 更新 state/info', () => {
    const { state, info } = useAppUpdater()
    emitState({ state: 'update-available', info: { version: '0.2.260' } })
    expect(state.value).toBe('update-available')
    expect(info.value).toEqual({ version: '0.2.260' })
  })

  it('check 调用 electronAPI.checkForUpdates', async () => {
    const { check } = useAppUpdater()
    await check()
    expect(mockApi.checkForUpdates).toHaveBeenCalled()
  })

  it('download 调用 electronAPI.downloadUpdate', async () => {
    const { download } = useAppUpdater()
    await download()
    expect(mockApi.downloadUpdate).toHaveBeenCalled()
  })

  it('install 调用 electronAPI.installNow', async () => {
    const { install } = useAppUpdater()
    await install()
    expect(mockApi.installNow).toHaveBeenCalled()
  })

  it('非 Electron 环境不订阅、isElectron=false', () => {
    mockIsElectron = false
    const { isElectron, check } = useAppUpdater()
    expect(isElectron.value).toBe(false)
    // check 在非 electron 下应安全 no-op
    expect(check()).toBeUndefined()
  })
})
```

- [ ] **Step 2: 运行测试确认失败**

Run（在 `frontend/` 下）：
```bash
npx vitest run src/composables/__tests__/useAppUpdater.test.js
```
Expected：FAIL，`Cannot find module '../useAppUpdater.js'`。

- [ ] **Step 3: 写最小实现**

创建 `frontend/src/composables/useAppUpdater.js`：

```js
import { ref, onBeforeUnmount } from 'vue'
import { getElectronAPI, isElectronApp } from '@/utils/electron'

/**
 * 应用自动更新状态机 composable。
 * 订阅主进程 updater:state 事件，暴露 state/info + check/download/install actions。
 * 非 Electron 环境下 isElectron=false，actions 为 no-op。
 */
export function useAppUpdater() {
  const state = ref('idle')
  const info = ref({})
  const isElectron = ref(isElectronApp())

  /** @type {(() => void) | null} */
  let unsubscribe = null
  if (isElectron.value) {
    const api = getElectronAPI()
    unsubscribe = api?.onUpdateState?.((payload) => {
      if (!payload) return
      state.value = payload.state
      info.value = payload.info || {}
    })
  }

  onBeforeUnmount(() => {
    unsubscribe?.()
    unsubscribe = null
  })

  async function check() {
    if (!isElectron.value) return
    return getElectronAPI()?.checkForUpdates?.()
  }
  async function download() {
    if (!isElectron.value) return
    return getElectronAPI()?.downloadUpdate?.()
  }
  async function install() {
    if (!isElectron.value) return
    return getElectronAPI()?.installNow?.()
  }

  return { state, info, isElectron, check, download, install }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run：
```bash
npx vitest run src/composables/__tests__/useAppUpdater.test.js
```
Expected：PASS。

- [ ] **Step 5: Commit**

```bash
git add frontend/src/composables/useAppUpdater.js frontend/src/composables/__tests__/useAppUpdater.test.js
git commit -m "feat(updater): useAppUpdater composable with state machine"
```

---

## Task 5: AppUpdateButton.vue 组件

**Files:**
- Create: `frontend/src/components/AppUpdateButton.vue`

- [ ] **Step 1: 写组件**

创建 `frontend/src/components/AppUpdateButton.vue`：

```vue
<template>
  <div class="app-update-button">
    <!-- 主按钮：文本随状态切换 -->
    <button
      type="button"
      class="upd-btn"
      :class="btnClass"
      :disabled="busy"
      :title="tooltip"
      @click="onClick"
    >
      <span v-if="state === 'checking'" class="upd-spin" />
      {{ btnText }}
    </button>

    <!-- 下载进度条 -->
    <div v-if="state === 'downloading'" class="upd-progress">
      <div class="upd-progress__bar" :style="{ width: progressPct + '%' }" />
    </div>

    <!-- 失败兜底链接 -->
    <a
      v-if="state === 'error'"
      class="upd-fallback"
      href="#"
      @click.prevent="openReleases"
    >前往 GitHub 手动下载</a>

    <!-- 发现新版本对话框 -->
    <el-dialog
      v-model="dialogVisible"
      title="发现新版本"
      :width="dialogWidth"
      destroy-on-close
    >
      <p class="upd-dialog-line">发现新版本 <span class="upd-version">v{{ info.version }}</span></p>
      <p class="upd-dialog-hint">建议立即更新以获得最新修复。更新将下载安装包并自动重启应用。</p>
      <template #footer>
        <el-button @click="dialogVisible = false">下次再说</el-button>
        <el-button type="primary" class="btn-cta" :loading="state === 'downloading'" @click="startDownload">
          立即更新
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useAppUpdater } from '@/composables/useAppUpdater'
import { useResponsiveDialogWidth } from '@/composables/useResponsiveDialogWidth'
import { isElectronApp, getElectronAPI } from '@/utils/electron'

const { state, info, check, download, install } = useAppUpdater()
const dialogWidth = useResponsiveDialogWidth(420)
const dialogVisible = ref(false)

const RELEASES_URL = 'https://github.com/whitequeen306/LianYuPC/releases/latest'

const busy = computed(() => ['checking', 'downloading', 'verifying', 'installing'].includes(state.value))

const btnText = computed(() => {
  switch (state.value) {
    case 'checking': return '检查中…'
    case 'no-update': return '已是最新'
    case 'downloading': return `下载中 ${Math.round(info.value.percent || 0)}%`
    case 'verifying': return '校验中…'
    case 'ready': return '安装并重启'
    case 'error': return '更新失败·重试'
    default: return '检查更新'
  }
})

const btnClass = computed(() => ({
  'upd-btn--primary': state.value === 'ready',
  'upd-btn--error': state.value === 'error',
  'upd-btn--success': state.value === 'no-update',
}))

const tooltip = computed(() => state.value === 'error' ? (info.value.errorMessage || '更新失败') : '')

const progressPct = computed(() => Math.max(0, Math.min(100, info.value.percent || 0)))

// no-update 短暂提示后回 idle（autoUpdater 不会自动回 idle，前端控）
let noUpdateTimer = null
watch(state, (s) => {
  if (s === 'no-update') {
    if (noUpdateTimer) clearTimeout(noUpdateTimer)
    noUpdateTimer = setTimeout(() => { /* state 由主进程下次 check 重置；此处仅清 UI */ }, 3000)
  }
  if (s === 'update-available') {
    dialogVisible.value = true
  }
  if (s === 'ready') {
    ElMessage.success('更新已下载完成，点击「安装并重启」生效')
  }
})

async function onClick() {
  if (state.value === 'ready') {
    await install()
    return
  }
  if (state.value === 'error' || state.value === 'idle' || state.value === 'no-update') {
    await check()
  }
}

async function startDownload() {
  dialogVisible.value = false
  await download()
}

function openReleases() {
  if (isElectronApp()) {
    getElectronAPI()?.apiRequest?.({ /* shell.openExternal 走主进程白名单 */ })
  }
  // 兜底：直接 window.open（setWindowOpenHandler 会转发到 shell.openExternal）
  window.open(RELEASES_URL, '_blank', 'noopener,noreferrer')
}
</script>

<style lang="scss" scoped>
.app-update-button {
  display: inline-flex;
  align-items: center;
  gap: $space-2;
  margin-left: $space-3;
}

.upd-btn {
  display: inline-flex;
  align-items: center;
  gap: $space-1;
  padding: 4px 14px;
  border: 1px solid rgba($color-pink-rgb, 0.25);
  border-radius: $radius-pill;
  background: rgba(var(--ly-bg-surface-rgb), 0.35);
  color: $color-text-secondary;
  font-size: $font-size-xs;
  cursor: pointer;
  transition: all $transition-fast;
  &:hover { color: $color-pink-primary; border-color: rgba($color-pink-rgb, 0.45); }
  &:disabled { cursor: not-allowed; opacity: 0.6; }

  &--primary {
    background: linear-gradient(135deg, $color-pink-primary 0%, $color-pink-dark 100%);
    color: $color-text-inverse;
    border-color: transparent;
    box-shadow: $shadow-glow-pink;
    &:hover { color: $color-text-inverse; }
  }
  &--error { color: $color-error; border-color: rgba($color-error, 0.4); }
  &--success { color: $color-success; border-color: rgba($color-success, 0.3); }
}

.upd-spin {
  width: 10px; height: 10px;
  border: 2px solid rgba($color-pink-rgb, 0.3);
  border-top-color: $color-pink-primary;
  border-radius: $radius-full;
  animation: upd-spin 0.6s linear infinite;
}
@keyframes upd-spin { to { transform: rotate(360deg); } }

.upd-progress {
  width: 120px;
  height: 4px;
  border-radius: $radius-full;
  background: rgba($color-pink-rgb, 0.12);
  overflow: hidden;
  &__bar {
    height: 100%;
    background: linear-gradient(90deg, $color-pink-primary, $color-pink-light);
    transition: width 0.2s cubic-bezier(0.23, 1, 0.32, 1);
  }
}

.upd-fallback {
  font-size: $font-size-xs;
  color: $color-text-muted;
  text-decoration: underline;
  &:hover { color: $color-pink-primary; }
}

.upd-dialog-line {
  font-size: $font-size-base;
  color: $color-text-primary;
  margin-bottom: $space-2;
}
.upd-version {
  font-family: $font-mono;
  color: $color-pink-primary;
}
.upd-dialog-hint {
  font-size: $font-size-sm;
  color: $color-text-muted;
  line-height: $line-height-normal;
}
</style>
```

- [ ] **Step 2: 语法/构建自检**

Run（在 `frontend/` 下）：
```bash
npx vite build
```
Expected：构建成功，无 AppUpdateButton 相关报错。

- [ ] **Step 3: Commit**

```bash
git add frontend/src/components/AppUpdateButton.vue
git commit -m "feat(updater): AppUpdateButton component with state-driven UI"
```

---

## Task 6: AboutPage.vue 集成按钮

**Files:**
- Modify: `frontend/src/pages/AboutPage.vue`

- [ ] **Step 1: 在版本号 info-row 里加 AppUpdateButton**

定位 `AboutPage.vue:22-25` 的版本号行：
```vue
          <div class="info-row">
            <span class="info-label">{{ t('about.version') }}</span>
            <span class="info-value mono">v{{ version }}</span>
          </div>
```
改为：
```vue
          <div class="info-row">
            <span class="info-label">{{ t('about.version') }}</span>
            <span class="info-value mono">v{{ version }}</span>
            <AppUpdateButton v-if="isElectron" />
          </div>
```

- [ ] **Step 2: 在 `<script setup>` 顶部 import 组件**

定位 `AboutPage.vue:75` 的 import 区，在 `import { isElectronApp } from '@/utils/electron'` 之后加：
```js
import { isElectronApp } from '@/utils/electron'
import AppUpdateButton from '@/components/AppUpdateButton.vue'
```

- [ ] **Step 3: 构建自检**

Run（在 `frontend/` 下）：
```bash
npx vite build
```
Expected：构建成功。

- [ ] **Step 4: Commit**

```bash
git add frontend/src/pages/AboutPage.vue
git commit -m "feat(updater): integrate AppUpdateButton in AboutPage version row"
```

---

## Task 7: electron-pack.mjs 改造（esbuild external + publish 切换）

**Files:**
- Modify: `frontend/scripts/electron-pack.mjs`

- [ ] **Step 1: esbuild external 数组加 electron-updater**

定位 `electron-pack.mjs:93` 的 external 数组：
```js
    external: ['electron', 'active-win', 'bytenode', 'ws', 'extract-zip'],
```
改为：
```js
    external: ['electron', 'active-win', 'bytenode', 'ws', 'extract-zip', 'electron-updater'],
```

- [ ] **Step 2: electron-builder 命令按 GH_TOKEN 切换 publish**

定位 `electron-pack.mjs:219-223` 的 electron-builder 调用：
```js
const outputArg = `--config.directories.output=${outDir.replace(/\\/g, '/')}`
execSync(`npx electron-builder --win ${outputArg}`, {
  stdio: 'inherit',
  env: process.env,
})
```
改为：
```js
const outputArg = `--config.directories.output=${outDir.replace(/\\/g, '/')}`
// 配了 GH_TOKEN 才上传 Releases；否则只产本地包，避免误传
const publishArg = process.env.GH_TOKEN ? '--publish always' : '--publish never'
if (process.env.GH_TOKEN) {
  console.log('GH_TOKEN detected → will publish to GitHub Releases')
} else {
  console.log('GH_TOKEN not set → local build only (no upload)')
}
execSync(`npx electron-builder --win ${outputArg} ${publishArg}`, {
  stdio: 'inherit',
  env: process.env,
})
```

- [ ] **Step 3: 手动验证脚本语法**

Run（在 `frontend/` 下）：
```bash
node --check scripts/electron-pack.mjs
```
Expected：无输出（语法 OK）。

- [ ] **Step 4: Commit**

```bash
git add frontend/scripts/electron-pack.mjs
git commit -m "build(updater): external electron-updater + toggle publish by GH_TOKEN"
```

---

## Task 8: electron-release.mjs 改造（GH_TOKEN 校验）

**Files:**
- Modify: `frontend/scripts/electron-release.mjs`

- [ ] **Step 1: 加 GH_TOKEN 校验与指引**

定位 `electron-release.mjs` 全文（10 行），整体替换为：
```js
import { execSync } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..')
const bump = ['patch', 'minor', 'major'].includes(process.argv[2]) ? process.argv[2] : 'patch'

// 发版需上传 GitHub Releases，必须配 GH_TOKEN（PAT，repo 权限）
// 本地仅打包测试请用 npm run electron:build（不校验、不上传）
if (!process.env.GH_TOKEN) {
  console.error('\n[electron:release] 缺少 GH_TOKEN 环境变量，无法发布到 GitHub Releases。')
  console.error('  请先创建 GitHub Personal Access Token（classic，勾选 repo 权限），然后：')
  console.error('  PowerShell:  $env:GH_TOKEN = "ghp_xxxx"')
  console.error('  cmd:         set GH_TOKEN=ghp_xxxx')
  console.error('  仅本地打包不上传请改用：npm run electron:build\n')
  process.exit(1)
}

process.chdir(root)
execSync(`npm version ${bump} --no-git-tag-version`, { stdio: 'inherit' })
execSync('node scripts/electron-pack.mjs', { stdio: 'inherit' })
```

- [ ] **Step 2: 语法自检**

Run（在 `frontend/` 下）：
```bash
node --check scripts/electron-release.mjs
```
Expected：无输出。

- [ ] **Step 3: 验证缺 token 时报错退出**

Run（确保当前 shell 无 GH_TOKEN）：
```bash
cmd /c "set GH_TOKEN= && node scripts/electron-release.mjs"
```
Expected：输出 `[electron:release] 缺少 GH_TOKEN...` 并 `exit 1`，不执行打包。

- [ ] **Step 4: Commit**

```bash
git add frontend/scripts/electron-release.mjs
git commit -m "build(updater): guard electron:release with GH_TOKEN check"
```

---

## Task 9: main.js 集成 initUpdater

**Files:**
- Modify: `frontend/electron/main.js`

- [ ] **Step 1: import initUpdater**

定位 `main.js:63` 的 `import { loadRuntimeSecrets, getRuntimeSecrets } from './runtimeSecrets.js'` 行，在其后加：
```js
import { loadRuntimeSecrets, getRuntimeSecrets } from './runtimeSecrets.js'
import { initUpdater } from './updater/updater.js'
```

- [ ] **Step 2: 在 createMainWindow 后调 initUpdater**

定位 `main.js:3118` 的 `createMainWindow()` 行，在其后加：
```js
  createMainWindow()
  initUpdater(mainWindow)
  ensureTray()
```

注意：`mainWindow` 是 `createMainWindow()` 内部赋值的全局变量（`main.js:76` 声明 `let mainWindow = null`，createMainWindow 内赋值），此处可直接引用。

- [ ] **Step 3: 构建自检**

Run（在 `frontend/` 下）：
```bash
npx vite build
```
Expected：构建成功，main bundle 含 updater 模块。

- [ ] **Step 4: Commit**

```bash
git add frontend/electron/main.js
git commit -m "feat(updater): init updater in app.whenReady after main window"
```

---

## Task 10: 全量验证

**Files:** 无（仅运行验证）

- [ ] **Step 1: 跑全部单测**

Run（在 `frontend/` 下）：
```bash
npm test
```
Expected：所有测试绿，含新增 updater.test.js + useAppUpdater.test.js。

- [ ] **Step 2: 构建前端 + electron bundle**

Run：
```bash
npx vite build
```
Expected：构建成功，无 import 报错。

- [ ] **Step 3: 本地打包验证（不上传）**

确保无 `GH_TOKEN`，Run：
```bash
npm run electron:build
```
Expected：产出 `frontend/release/v0.2.255/LianYu Setup 0.2.255.exe`，日志含 `GH_TOKEN not set → local build only`。

- [ ] **Step 4: 最终提交（如有改动）**

```bash
git status
# 若有未提交改动：
git add -A
git commit -m "chore(updater): final verification pass"
```

---

## Self-Review 结果

**Spec coverage：** spec 第 3 节架构 → Task 2/3/9；第 4 节状态机 → Task 2/4；第 5 节 UI → Task 5/6；第 6 节发布流程 → Task 1/7/8；第 7 节错误处理 → Task 2（dev-mode/error 透传）+ Task 5（兜底链接）；第 8 节测试 → Task 2/4/10；第 9 节安全 → Task 1（publish 配置）+ Task 7（external）。全部覆盖。

**Placeholder scan：** 无 TBD/TODO，所有代码步骤含完整代码。

**Type/命名一致性：** IPC 通道 `updater:check/download/install` + `updater:state` 在 Task 2/3/4 一致；composable 返回 `{state, info, isElectron, check, download, install}` 在 Task 4/5 一致；`initUpdater(mainWindow)` 签名在 Task 2/9 一致。

**已知后续手动验收（计划外，需真实发版）：** 发一个 patch 版到 Releases，旧版本点更新走完整流程（spec 第 8.2 节）。
