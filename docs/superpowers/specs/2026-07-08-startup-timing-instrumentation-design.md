# Startup Timing Instrumentation Design

## Goal

Add development-only startup timing instrumentation so we can measure where desktop launch time is spent before making a second round of performance optimizations.

The instrumentation must:

1. Measure both Electron main-process startup and renderer startup.
2. Reuse existing logging channels instead of adding user-facing UI.
3. Produce clear, phase-by-phase elapsed timing that can be compared across runs.
4. Avoid changing runtime behavior beyond lightweight diagnostic logging.

## Current Context

### Main process startup today

- `frontend/electron/main.js` performs a sequence of startup steps inside `app.whenReady()`.
- The current chain includes:
  - `ensureToastAppRegistration()`
  - `configureSecurity()`
  - `configureAntiDebug()`
  - `patchDesktopRequestOrigin()`
  - `applyLaunchAtLogin(readDesktopSettings().launchAtLogin)`
  - `registerIpcHandlers()`
  - `createMainWindow()`
  - post-window startup scheduling for `initUpdater(mainWindow)`, `ensureTray()`, and `scheduleAuxWindowPrewarm()`
- `createMainWindow()` reveals the window on `did-finish-load` or `ready-to-show`, whichever comes first.
- Main-process logging already exists through `frontend/electron/logger.js` and the local `log(message)` wrapper in `main.js`.

### Renderer startup today

- `frontend/src/main.js` performs startup work in this order:
  - `initElectronRuntimeConfig()`
  - optional `prepareAuthRoute(pinia)` before `app.mount()` for non-aux routes
  - `app.mount('#app')`
  - `await router.isReady()`
  - `dismissBootSplash()`
  - async `bootstrapAuth(pinia)` or launcher bootstrap after mount
- Renderer-side logging already exists through `frontend/src/utils/logger` and global error capture hooks.

### Why instrumentation is needed

- We already made one safe optimization by deferring updater/tray/prewarm work until after the main window finishes loading.
- The user still perceives startup as slow, so the next optimization pass must be based on measured evidence.
- Right now we do not have a unified timing trail that tells us whether startup delay is primarily caused by:
  - main-process pre-window work,
  - window load/reveal timing,
  - renderer route/auth bootstrap,
  - or post-mount session restoration.

## Chosen Approach

Add lightweight structured timing instrumentation in both startup paths, using a tiny helper on each side to emit logs with a shared format.

This is intentionally diagnostic-only and does not change any user-facing behavior.

## Design

### 1. Logging format

Use a consistent prefix and elapsed-time format so startup traces can be scanned quickly in logs.

Examples:

- `[startup-main] whenReady +0ms`
- `[startup-main] configureSecurity:done +18ms`
- `[startup-main] createMainWindow:done +42ms`
- `[startup-main] mainWindow:did-finish-load +385ms`
- `[startup-renderer] entry +0ms`
- `[startup-renderer] prepareAuthRoute:start +3ms`
- `[startup-renderer] prepareAuthRoute:done +47ms`
- `[startup-renderer] router:isReady +195ms`
- `[startup-renderer] bootstrapAuth:done +612ms`

The important requirement is stable phase names and monotonic elapsed timing from a single per-process start point.

### 2. Main-process instrumentation

#### Scope

Instrument the major phases in `frontend/electron/main.js`:

- `app.whenReady()` entry
- `ensureToastAppRegistration()`
- `configureSecurity()`
- `configureAntiDebug()`
- `patchDesktopRequestOrigin()`
- `applyLaunchAtLogin(...)`
- `registerIpcHandlers()`
- `createMainWindow()`
- `readAuthSession()` / launcher login flag restoration
- post-window startup scheduling
- `mainWindow did-finish-load`
- `mainWindow ready-to-show`
- post-window startup execution (`initUpdater`, `ensureTray`, `scheduleAuxWindowPrewarm`)

#### Implementation shape

- Add a small helper in Electron code, for example a `startupProfiler` module, that:
  - captures a start timestamp once,
  - exposes `mark(label)` for logging elapsed time,
  - optionally exposes `measure(label, fn)` for synchronous phases if that keeps call sites cleaner.
- Keep the helper tiny and side-effect free except for logging.
- Use existing `logger.info()` or the local `log()` wrapper so logs land in the normal Electron log file.

#### Constraints

- No user-visible UI.
- No extra IPC just for instrumentation.
- No persistent metrics storage yet; plain logs are enough for this phase.

### 3. Renderer instrumentation

#### Scope

Instrument the major phases in `frontend/src/main.js`:

- entry to startup IIFE
- `initElectronRuntimeConfig()` dispatch
- `prepareAuthRoute(pinia)` start/done (only when applicable)
- `app.mount('#app')`
- `router.isReady()` done
- `dismissBootSplash()`
- `bootstrapAuth(pinia)` start/done (or launcher bootstrap path)
- optional quick-chat profile bootstrap path if it is reached

#### Implementation shape

- Add a small renderer-side helper using `performance.now()` when available, falling back to `Date.now()` only if needed.
- Emit logs through the existing renderer logger so timings end up in the same diagnostic channel already used for renderer errors.
- Use the same naming style as the main process, with a distinct prefix: `[startup-renderer]`.

#### Constraints

- Do not block startup on the logger itself.
- Do not log high-volume per-component timings; keep it limited to the top-level startup path.

### 4. First measurement targets

After instrumentation is added, we want to answer these questions from one startup trace:

1. How long from `app.whenReady()` to `createMainWindow()` completion?
2. How long from `createMainWindow()` to `did-finish-load`?
3. How long from renderer entry to `router.isReady()`?
4. How long does `prepareAuthRoute()` take when a persisted session exists?
5. How long does `bootstrapAuth()` take relative to visible UI readiness?
6. Are deferred tasks (`initUpdater`, `ensureTray`, `scheduleAuxWindowPrewarm`) still overlapping the first visible frame too aggressively?

These answers will drive the next optimization pass.

## Files Likely To Change

- `frontend/electron/main.js`
- new Electron helper, likely under `frontend/electron/`
- `frontend/src/main.js`
- new renderer helper, likely under `frontend/src/utils/` or `frontend/src/startup/`
- new tests for any extracted helpers

## Error Handling

- Timing instrumentation must never throw or block startup.
- If a mark cannot be emitted, startup continues normally.
- If `performance.now()` is unavailable in a given context, fall back to a simpler timestamp source.

## Testing Strategy

### Unit tests

- Add focused tests around any extracted startup timing helper:
  - elapsed timing is monotonic,
  - labels are passed through correctly,
  - scheduling behavior remains unchanged when instrumentation is present.

### Manual verification

- Launch the app from a context where Electron logs are accessible.
- Verify that a single startup produces a readable sequence of `[startup-main]` and `[startup-renderer]` markers.
- Confirm no user-facing UI changes were introduced.

## Non-Goals

- No optimization decisions beyond the already-shipped post-window deferral.
- No user-facing performance dashboard.
- No analytics upload or telemetry pipeline.
- No permanent configuration UI for startup profiling.

## Recommendation

Implement the instrumentation as small extracted helpers plus minimal call-site marks in `main.js` and `frontend/src/main.js`, then collect a real startup trace before making the next optimization pass.
