# Startup Timing Instrumentation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add development-only startup timing logs for Electron main-process and renderer startup so the next optimization round is guided by measured evidence.

**Architecture:** Add one tiny timing helper on the Electron side and one tiny timing helper on the renderer side, then instrument only the top-level startup path. Reuse existing logging infrastructure and keep the instrumentation side-effect free except for lightweight diagnostic logging.

**Tech Stack:** Electron, Vue 3, Vitest, existing `electron/logger.js` and renderer logger utilities

---

## File Structure

- `frontend/electron/startupProfiler.js`
  Purpose: small main-process helper that tracks elapsed startup time and emits structured timing marks.
- `frontend/electron/__tests__/startupProfiler.test.js`
  Purpose: verify elapsed logging labels and monotonic timing on the main-process helper.
- `frontend/electron/main.js`
  Purpose: instrument the major Electron startup milestones using the shared profiler.
- `frontend/src/utils/startupProfiler.js`
  Purpose: small renderer helper for elapsed startup timing using `performance.now()` when available.
- `frontend/src/utils/__tests__/startupProfiler.test.js`
  Purpose: verify renderer profiler timing output and fallback behavior.
- `frontend/src/main.js`
  Purpose: instrument renderer startup milestones (`prepareAuthRoute`, `mount`, `router.isReady`, `bootstrapAuth`).

### Task 1: Main-Process Startup Profiler Helper

**Files:**
- Create: `frontend/electron/__tests__/startupProfiler.test.js`
- Create: `frontend/electron/startupProfiler.js`

- [ ] **Step 1: Write the failing main-process profiler tests**

```js
import { describe, expect, it, vi } from 'vitest'
import { createStartupProfiler } from '../startupProfiler.js'

describe('createStartupProfiler', () => {
  it('logs elapsed milliseconds from the initial start time', () => {
    const log = vi.fn()
    const now = vi.spyOn(Date, 'now')
      .mockReturnValueOnce(1000)
      .mockReturnValueOnce(1018)

    const profiler = createStartupProfiler({ prefix: 'startup-main', log })
    profiler.mark('configureSecurity:done')

    expect(log).toHaveBeenCalledWith('[startup-main] configureSecurity:done +18ms')
    now.mockRestore()
  })

  it('keeps elapsed time monotonic across multiple marks', () => {
    const log = vi.fn()
    const now = vi.spyOn(Date, 'now')
      .mockReturnValueOnce(2000)
      .mockReturnValueOnce(2010)
      .mockReturnValueOnce(2045)

    const profiler = createStartupProfiler({ prefix: 'startup-main', log })
    profiler.mark('createMainWindow:start')
    profiler.mark('createMainWindow:done')

    expect(log.mock.calls).toEqual([
      ['[startup-main] createMainWindow:start +10ms'],
      ['[startup-main] createMainWindow:done +45ms'],
    ])
    now.mockRestore()
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx vitest run electron/__tests__/startupProfiler.test.js`

Expected: FAIL because `frontend/electron/startupProfiler.js` does not exist yet.

- [ ] **Step 3: Write the minimal main-process profiler implementation**

```js
// frontend/electron/startupProfiler.js
export function createStartupProfiler({ prefix, log, now = () => Date.now() }) {
  const startedAt = now()

  function mark(label) {
    const elapsed = Math.max(0, now() - startedAt)
    log?.(`[${prefix}] ${label} +${elapsed}ms`)
  }

  return { mark }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx vitest run electron/__tests__/startupProfiler.test.js`

Expected: PASS with 2 tests passing.

- [ ] **Step 5: Commit**

```bash
git add frontend/electron/__tests__/startupProfiler.test.js frontend/electron/startupProfiler.js
git commit -m "test(startup): add electron startup profiler helper"
```

### Task 2: Instrument Electron Startup Milestones

**Files:**
- Modify: `frontend/electron/main.js`

- [ ] **Step 1: Use the failing helper test as the red baseline**

Run: `npx vitest run electron/__tests__/startupProfiler.test.js`

Expected: PASS from Task 1, while `main.js` still has no phase marks yet.

- [ ] **Step 2: Add main-process startup timing marks**

```js
// frontend/electron/main.js
import { createStartupProfiler } from './startupProfiler.js'

const startupMainProfiler = createStartupProfiler({
  prefix: 'startup-main',
  log,
})
```

```js
app.whenReady().then(() => {
  startupMainProfiler.mark('whenReady')
  logger.initGlobalErrorHandlers()

  ensureToastAppRegistration()
  startupMainProfiler.mark('ensureToastAppRegistration:done')

  if (!runtimeSecretsConfigured()) {
    dialog.showErrorBox(
      'LianYu',
      '客户端配置读取失败，请卸载后重新安装最新版本。若仍失败请联系支持。',
    )
  }

  configureSecurity()
  startupMainProfiler.mark('configureSecurity:done')

  configureAntiDebug()
  startupMainProfiler.mark('configureAntiDebug:done')

  patchDesktopRequestOrigin()
  startupMainProfiler.mark('patchDesktopRequestOrigin:done')

  applyLaunchAtLogin(readDesktopSettings().launchAtLogin)
  startupMainProfiler.mark('applyLaunchAtLogin:done')

  registerIpcHandlers()
  startupMainProfiler.mark('registerIpcHandlers:done')

  createMainWindow()
  startupMainProfiler.mark('createMainWindow:done')

  schedulePostWindowStartup({
    mainWindow,
    initUpdater: (win) => {
      startupMainProfiler.mark('postWindow:initUpdater:start')
      initUpdater(win)
      startupMainProfiler.mark('postWindow:initUpdater:done')
    },
    ensureTray: () => {
      startupMainProfiler.mark('postWindow:ensureTray:start')
      ensureTray()
      startupMainProfiler.mark('postWindow:ensureTray:done')
    },
    scheduleAuxWindowPrewarm: () => {
      startupMainProfiler.mark('postWindow:scheduleAuxWindowPrewarm:start')
      scheduleAuxWindowPrewarm()
      startupMainProfiler.mark('postWindow:scheduleAuxWindowPrewarm:done')
    },
  })

  if (readAuthSession()) {
    launcherLoggedIn = true
  }
  startupMainProfiler.mark('readAuthSession:done')
})
```

```js
// inside createMainWindow()
win.webContents.once('did-finish-load', () => {
  startupMainProfiler.mark('mainWindow:did-finish-load')
  clearTimeout(revealFallbackTimer)
  revealMainWindow()
})

win.once('ready-to-show', () => {
  startupMainProfiler.mark('mainWindow:ready-to-show')
  clearTimeout(revealFallbackTimer)
  revealMainWindow()
})
```

- [ ] **Step 3: Run helper and startup orchestrator tests**

Run: `npx vitest run electron/__tests__/startupProfiler.test.js electron/__tests__/startupOrchestrator.test.js`

Expected: PASS with both test files green.

- [ ] **Step 4: Commit**

```bash
git add frontend/electron/main.js
git commit -m "chore(startup): instrument electron startup milestones"
```

### Task 3: Renderer Startup Profiler Helper

**Files:**
- Create: `frontend/src/utils/__tests__/startupProfiler.test.js`
- Create: `frontend/src/utils/startupProfiler.js`

- [ ] **Step 1: Write the failing renderer profiler tests**

```js
import { describe, expect, it, vi } from 'vitest'
import { createStartupProfiler } from '@/utils/startupProfiler'

describe('renderer createStartupProfiler', () => {
  it('uses the provided now function to compute elapsed time', () => {
    const log = vi.fn()
    const now = vi.fn()
      .mockReturnValueOnce(10)
      .mockReturnValueOnce(37)

    const profiler = createStartupProfiler({ prefix: 'startup-renderer', log, now })
    profiler.mark('prepareAuthRoute:done')

    expect(log).toHaveBeenCalledWith('[startup-renderer] prepareAuthRoute:done +27ms')
  })

  it('falls back to a non-negative elapsed time when called repeatedly', () => {
    const log = vi.fn()
    const now = vi.fn()
      .mockReturnValueOnce(100)
      .mockReturnValueOnce(100)
      .mockReturnValueOnce(115)

    const profiler = createStartupProfiler({ prefix: 'startup-renderer', log, now })
    profiler.mark('entry')
    profiler.mark('mount')

    expect(log.mock.calls).toEqual([
      ['[startup-renderer] entry +0ms'],
      ['[startup-renderer] mount +15ms'],
    ])
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx vitest run src/utils/__tests__/startupProfiler.test.js`

Expected: FAIL because `frontend/src/utils/startupProfiler.js` does not exist yet.

- [ ] **Step 3: Write the minimal renderer profiler implementation**

```js
// frontend/src/utils/startupProfiler.js
function defaultNow() {
  if (typeof performance !== 'undefined' && typeof performance.now === 'function') {
    return performance.now()
  }
  return Date.now()
}

export function createStartupProfiler({ prefix, log, now = defaultNow }) {
  const startedAt = now()

  function mark(label) {
    const elapsed = Math.max(0, Math.round(now() - startedAt))
    log?.(`[${prefix}] ${label} +${elapsed}ms`)
  }

  return { mark }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx vitest run src/utils/__tests__/startupProfiler.test.js`

Expected: PASS with 2 tests passing.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/utils/__tests__/startupProfiler.test.js frontend/src/utils/startupProfiler.js
git commit -m "test(startup): add renderer startup profiler helper"
```

### Task 4: Instrument Renderer Startup Milestones

**Files:**
- Modify: `frontend/src/main.js`

- [ ] **Step 1: Use the passing renderer helper test as the baseline**

Run: `npx vitest run src/utils/__tests__/startupProfiler.test.js`

Expected: PASS from Task 3 while `main.js` still has no renderer startup marks yet.

- [ ] **Step 2: Add renderer startup timing marks**

```js
// frontend/src/main.js
import { createStartupProfiler } from '@/utils/startupProfiler'

const startupRendererProfiler = createStartupProfiler({
  prefix: 'startup-renderer',
  log: (message) => rendererLogger.info('startup', message),
})

startupRendererProfiler.mark('entry')
```

```js
;(async () => {
  startupRendererProfiler.mark('iife:start')
  void initElectronRuntimeConfig()
  startupRendererProfiler.mark('initElectronRuntimeConfig:queued')

  const aux = isDesktopAuxSurface()
  if (!aux) {
    startupRendererProfiler.mark('prepareAuthRoute:start')
    try {
      await prepareAuthRoute(pinia)
    } finally {
      startupRendererProfiler.mark('prepareAuthRoute:done')
    }
  }

  app.mount('#app')
  startupRendererProfiler.mark('app:mounted')

  try {
    await router.isReady()
    startupRendererProfiler.mark('router:isReady')
  } catch {
    showBootSplashError('启动失败，请重新安装最新版本。')
    return
  } finally {
    dismissBootSplash()
    startupRendererProfiler.mark('bootSplash:dismissed')
  }

  if (aux) {
    startupRendererProfiler.mark('bootstrapLauncherSession:start')
    void bootstrapLauncherSession(pinia).finally(() => {
      startupRendererProfiler.mark('bootstrapLauncherSession:done')
    })
  } else {
    startupRendererProfiler.mark('bootstrapAuth:start')
    void readToken().then(() => bootstrapAuth(pinia)).finally(() => {
      startupRendererProfiler.mark('bootstrapAuth:done')
    })
  }
})()
```

- [ ] **Step 3: Run the helper tests and existing startup/auth regressions**

Run: `npx vitest run electron/__tests__/startupProfiler.test.js electron/__tests__/startupOrchestrator.test.js src/utils/__tests__/startupProfiler.test.js src/auth/__tests__/bootstrap.test.js`

Expected: PASS with all listed test files green.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/main.js
git commit -m "chore(startup): instrument renderer startup milestones"
```

### Task 5: Full Verification And Diagnostic Readiness

**Files:**
- Test: `frontend/electron/__tests__/startupProfiler.test.js`
- Test: `frontend/electron/__tests__/startupOrchestrator.test.js`
- Test: `frontend/src/utils/__tests__/startupProfiler.test.js`
- Test: `frontend/src/auth/__tests__/bootstrap.test.js`
- Test: `frontend/src/composables/__tests__/useStreamAbort.test.js`
- Test: `frontend/src/pages/__tests__/charactersPreview.test.js`
- Verify: `frontend/electron/main.js`
- Verify: `frontend/src/main.js`

- [ ] **Step 1: Run the full targeted verification suite**

Run: `npx vitest run electron/__tests__/startupProfiler.test.js electron/__tests__/startupOrchestrator.test.js src/utils/__tests__/startupProfiler.test.js src/auth/__tests__/bootstrap.test.js src/composables/__tests__/useStreamAbort.test.js src/pages/__tests__/charactersPreview.test.js`

Expected: PASS with all startup/profile/auth/chat regression tests green.

- [ ] **Step 2: Run an Electron build verification**

Run: `npm run electron:build`

Expected: PASS, including the existing launcher smoke step.

- [ ] **Step 3: Manual diagnostic verification**

Check after launching the app once:
- Electron log contains `[startup-main]` entries in startup order.
- Renderer log contains `[startup-renderer]` entries in startup order.
- No user-facing UI changes appear.
- The emitted timings are sufficient to identify the slowest startup phase.

- [ ] **Step 4: Commit final follow-up if verification required small adjustments**

```bash
git add frontend/electron/startupProfiler.js frontend/electron/__tests__/startupProfiler.test.js frontend/electron/main.js frontend/src/utils/startupProfiler.js frontend/src/utils/__tests__/startupProfiler.test.js frontend/src/main.js
git commit -m "chore(startup): add startup timing instrumentation"
```
