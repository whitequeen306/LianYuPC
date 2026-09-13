# Startup Chat Routing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Electron startup enter the correct authenticated route faster, keep single-chat replies alive when the user leaves the page, and show assistant-first character card previews.

**Architecture:** Keep the existing Vue/Electron structure and fix the three behaviors with small focused changes. Move lightweight auth route preparation earlier in the startup path, change stream-abort semantics from page-bound to send-bound, and centralize character-card preview selection so assistant replies win without changing unrelated list behavior.

**Tech Stack:** Vue 3, Vue Router 4, Pinia, Vitest, Electron

---

## File Structure

- `frontend/src/auth/bootstrap.js`
  Purpose: recover persisted auth state and decide whether startup should enter `/app` before the landing page renders.
- `frontend/src/main.js`
  Purpose: reorder startup so lightweight auth route preparation happens before post-mount background bootstrap.
- `frontend/src/router/index.js`
  Purpose: keep route guard behavior aligned with token-first startup routing.
- `frontend/src/auth/__tests__/bootstrap.test.js`
  Purpose: regression tests for authenticated and anonymous startup route decisions.
- `frontend/src/composables/useStreamAbort.js`
  Purpose: support stream abort policies that do not always abort on component unmount.
- `frontend/src/composables/__tests__/useStreamAbort.test.js`
  Purpose: verify abort-on-unmount vs persist-on-unmount behavior.
- `frontend/src/pages/ChatPage.vue`
  Purpose: keep active send requests alive across page leave while preventing writes into unmounted state.
- `frontend/src/pages/QuickChatPage.vue`
  Purpose: apply the same send-bound stream behavior to quick chat.
- `frontend/src/pages/CharactersPage.vue`
  Purpose: use assistant-first preview text for character cards.
- `frontend/src/pages/__tests__/charactersPreview.test.js`
  Purpose: verify preview selection logic prefers assistant replies.

### Task 1: Auth Startup Routing Fast Path

**Files:**
- Create: `frontend/src/auth/__tests__/bootstrap.test.js`
- Modify: `frontend/src/auth/bootstrap.js`
- Modify: `frontend/src/main.js`
- Modify: `frontend/src/router/index.js`

- [ ] **Step 1: Write the failing auth startup tests**

```js
import { beforeEach, describe, expect, it, vi } from 'vitest'

const replaceMock = vi.fn()
const readTokenMock = vi.fn()
const syncTokenMock = vi.fn()
const syncSetTokenCacheMock = vi.fn()
const bootstrapAuthTokenMock = vi.fn()

vi.mock('@/router/index.js', () => ({
  default: { replace: replaceMock },
}))

vi.mock('@/utils/secureToken', () => ({
  readToken: readTokenMock,
  syncToken: syncTokenMock,
  syncSetTokenCache: syncSetTokenCacheMock,
}))

vi.mock('@/utils/electron', () => ({
  getElectronAPI: () => ({
    bootstrapAuthToken: bootstrapAuthTokenMock,
  }),
}))

vi.mock('@/stores/user', () => ({
  useUserStore: () => ({ token: '', applyProfile: vi.fn() }),
}))

describe('prepareAuthRoute', () => {
  beforeEach(() => {
    vi.resetModules()
    replaceMock.mockReset()
    readTokenMock.mockReset()
    syncTokenMock.mockReset()
    syncSetTokenCacheMock.mockReset()
    bootstrapAuthTokenMock.mockReset()
    window.location.hash = '#/'
    localStorage.clear()
  })

  it('routes authenticated startup directly to /app', async () => {
    bootstrapAuthTokenMock.mockResolvedValue('tok-123')
    syncTokenMock.mockReturnValue('tok-123')
    const { prepareAuthRoute } = await import('@/auth/bootstrap')

    await prepareAuthRoute({})

    expect(syncSetTokenCacheMock).toHaveBeenCalledWith('tok-123')
    expect(replaceMock).toHaveBeenCalledWith('/app')
  })

  it('keeps anonymous startup on public route', async () => {
    bootstrapAuthTokenMock.mockResolvedValue('')
    syncTokenMock.mockReturnValue('')
    const { prepareAuthRoute } = await import('@/auth/bootstrap')

    await prepareAuthRoute({})

    expect(replaceMock).not.toHaveBeenCalled()
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx vitest run frontend/src/auth/__tests__/bootstrap.test.js`

Expected: FAIL because `frontend/src/auth/__tests__/bootstrap.test.js` does not exist yet and/or `prepareAuthRoute()` still assumes the old post-mount flow.

- [ ] **Step 3: Implement the minimal auth startup fast path**

```js
// frontend/src/auth/bootstrap.js
import { useUserStore } from '@/stores/user'
import router from '@/router/index.js'
import { readToken, syncToken, syncSetTokenCache } from '@/utils/secureToken'
import { PROFILE_CACHE_KEY } from '@/constants/authSession'
import { getElectronAPI } from '@/utils/electron'

const AUTO_ENTRY_PATHS = new Set(['/', '/login', '/register'])

function currentHashPath() {
  return (window.location.hash.replace(/^#/, '') || '/').split('?')[0]
}

function hydrateProfileFromCache(userStore) {
  try {
    const raw = localStorage.getItem(PROFILE_CACHE_KEY)
    if (raw) userStore.applyProfile(JSON.parse(raw))
  } catch {
    // ignore corrupt cache
  }
}

export async function prepareAuthRoute(pinia) {
  const electronAPI = getElectronAPI()
  if (electronAPI?.bootstrapAuthToken) {
    const token = await electronAPI.bootstrapAuthToken()
    if (token) syncSetTokenCache(token)
  } else {
    await readToken()
  }

  const hashPath = currentHashPath()
  if (!AUTO_ENTRY_PATHS.has(hashPath)) return false

  const cachedToken = syncToken()
  if (!cachedToken) return false

  const userStore = useUserStore(pinia)
  userStore.token = cachedToken
  syncSetTokenCache(cachedToken)
  hydrateProfileFromCache(userStore)
  await router.replace('/app')
  return true
}
```

```js
// frontend/src/main.js
;(async () => {
  void initElectronRuntimeConfig()
  const aux = isDesktopAuxSurface()
  if (isQuickChatSurface()) {
    window.__lianyuNavigateQuickChat = (target) => router.push(target)
  }

  if (!aux) {
    try {
      await prepareAuthRoute(pinia)
    } catch {
      // keep anonymous startup path if bootstrap fails
    }
  }

  app.mount('#app')

  try {
    await router.isReady()
  } catch {
    showBootSplashError('启动失败，请重新安装最新版本。')
    return
  } finally {
    dismissBootSplash()
  }

  if (aux) {
    void bootstrapLauncherSession(pinia)
  } else {
    void bootstrapAuth(pinia)
  }
})()
```

```js
// frontend/src/router/index.js
router.beforeEach(async (to, from, next) => {
  await readToken()
  const token = syncToken()
  const userStore = useUserStore()
  if (token && !userStore.token) {
    userStore.token = token
    syncSetTokenCache(token)
  }

  const hasStartupToken = !!token
  const hasValidSession = !!(token && userStore.isLoggedIn)

  if (to.meta.guest) {
    if (hasStartupToken) return next('/app')
    return next()
  }

  if (to.meta.public) {
    if (to.name === 'Landing' && hasStartupToken) return next('/app')
    return next()
  }

  if (to.meta.requiresAuth && !token) {
    return next({ path: '/', replace: true })
  }

  next()
})
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `npx vitest run frontend/src/auth/__tests__/bootstrap.test.js`

Expected: PASS with 2 tests passing.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/auth/__tests__/bootstrap.test.js frontend/src/auth/bootstrap.js frontend/src/main.js frontend/src/router/index.js
git commit -m "fix(startup): route authenticated sessions directly into app"
```

### Task 2: Send-Bound Stream Abort Policy

**Files:**
- Create: `frontend/src/composables/__tests__/useStreamAbort.test.js`
- Modify: `frontend/src/composables/useStreamAbort.js`

- [ ] **Step 1: Write the failing stream abort policy tests**

```js
import { describe, expect, it } from 'vitest'
import { effectScope } from 'vue'
import { useStreamAbort } from '@/composables/useStreamAbort'

describe('useStreamAbort', () => {
  it('aborts the previous stream when a new one starts', () => {
    const scope = effectScope()
    let api
    scope.run(() => {
      api = useStreamAbort({ abortOnUnmount: false })
    })

    const first = api.beginStream()
    const second = api.beginStream()

    expect(first.aborted).toBe(true)
    expect(second.aborted).toBe(false)
    scope.stop()
  })

  it('keeps the active stream alive on unmount when abortOnUnmount is false', () => {
    const scope = effectScope()
    let api
    let signal
    scope.run(() => {
      api = useStreamAbort({ abortOnUnmount: false })
      signal = api.beginStream()
    })

    scope.stop()

    expect(signal.aborted).toBe(false)
  })

  it('aborts the active stream on unmount when abortOnUnmount is true', () => {
    const scope = effectScope()
    let api
    let signal
    scope.run(() => {
      api = useStreamAbort({ abortOnUnmount: true })
      signal = api.beginStream()
    })

    scope.stop()

    expect(signal.aborted).toBe(true)
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx vitest run frontend/src/composables/__tests__/useStreamAbort.test.js`

Expected: FAIL because the file does not exist and `useStreamAbort()` always aborts on unmount.

- [ ] **Step 3: Implement configurable unmount abort behavior**

```js
// frontend/src/composables/useStreamAbort.js
import { onUnmounted } from 'vue'

export function isAbortError(err) {
  return err?.name === 'AbortError'
    || (typeof DOMException !== 'undefined' && err instanceof DOMException && err.name === 'AbortError')
}

export function isNetworkError(err) {
  if (!err) return false
  if (err.name === 'TypeError') return true
  const msg = String(err.message || '').toLowerCase()
  return msg.includes('network')
    || msg.includes('failed to fetch')
    || msg.includes('load failed')
}

export function useStreamAbort(options = {}) {
  const { abortOnUnmount = true } = options
  let controller = null

  function beginStream() {
    controller?.abort()
    controller = new AbortController()
    return controller.signal
  }

  function abortStream() {
    controller?.abort()
    controller = null
  }

  onUnmounted(() => {
    if (abortOnUnmount) abortStream()
  })

  return { beginStream, abortStream, isAbortError }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `npx vitest run frontend/src/composables/__tests__/useStreamAbort.test.js`

Expected: PASS with 3 tests passing.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/composables/__tests__/useStreamAbort.test.js frontend/src/composables/useStreamAbort.js
git commit -m "test(stream): support send-bound abort policy"
```

### Task 3: Keep Single-Chat Sends Alive Across Page Leave

**Files:**
- Modify: `frontend/src/pages/ChatPage.vue:312-313`
- Modify: `frontend/src/pages/ChatPage.vue:561-570`
- Modify: `frontend/src/pages/QuickChatPage.vue:120-121`
- Modify: `frontend/src/pages/QuickChatPage.vue:354-359`

- [ ] **Step 1: Add a failing regression note by reproducing current behavior**

Run: `npx vitest run frontend/src/composables/__tests__/useStreamAbort.test.js`

Expected: PASS from Task 2, but manual reproduction in the app still shows `ChatPage` and `QuickChatPage` using default unmount-abort behavior until code is updated.

- [ ] **Step 2: Update `ChatPage.vue` to opt out of unmount abort**

```js
// frontend/src/pages/ChatPage.vue
const { beginStream, abortStream, isAbortError } = useStreamAbort({ abortOnUnmount: false })
```

```js
onUnmounted(() => {
  isUnmounted = true
  burstTimers.forEach((id) => clearTimeout(id))
  burstTimers.length = 0
  setActiveChatConversationId(null)
  setActiveChatRefreshHandler(null)
  stopConversationPolling()
  bounceTween?.kill()
})
```

```js
async function handleSend() {
  // existing setup omitted
  const signal = beginStream()

  try {
    // existing stream logic omitted
  } catch (err) {
    if (isAbortError(err)) return
    ElMessage.error(humanizeError(err, '消息发送失败，请稍后再试'))
    messages.value = messages.value.filter(m => m._streamGroupId !== streamGroupId)
    inputText.value = draftText
    pendingImageUrl.value = draftImageUrl
    skipBounceOnce = true
  } finally {
    if (!signal.aborted) abortStream()
    if (isUnmounted) return
    waitingReply.value = false
    if (currentConvId.value === sendConvId) {
      await pollCurrentConversationMessages(true)
    }
    await nextTick()
    focusChatInput()
    scrollToBottom()
    loadEmotionState()
  }
}
```

- [ ] **Step 3: Update `QuickChatPage.vue` to use the same send-bound policy**

```js
// frontend/src/pages/QuickChatPage.vue
const { beginStream, abortStream, isAbortError } = useStreamAbort({ abortOnUnmount: false })
```

```js
onUnmounted(() => {
  isUnmounted = true
  stopPolling()
  clearTimeout(errorTimer)
})
```

```js
async function handleSend() {
  // existing setup omitted
  const signal = beginStream()

  try {
    // existing stream logic omitted
  } catch (err) {
    if (isAbortError(err)) return
    inputText.value = draftText
    showError(humanizeError(err, '消息发送失败，请稍后再试'))
  } finally {
    if (!signal.aborted) abortStream()
    if (isUnmounted) return
    waitingReply.value = false
    await nextTick()
    scrollToBottom({ force: true })
  }
}
```

- [ ] **Step 4: Run targeted tests and a manual smoke check**

Run: `npx vitest run frontend/src/composables/__tests__/useStreamAbort.test.js`

Expected: PASS.

Manual check:
- open a single chat,
- send a message,
- immediately navigate away,
- wait for the role to finish,
- return to the same chat and verify the assistant reply was saved.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/ChatPage.vue frontend/src/pages/QuickChatPage.vue
git commit -m "fix(chat): keep single-chat replies alive across navigation"
```

### Task 4: Assistant-First Character Card Preview

**Files:**
- Create: `frontend/src/pages/__tests__/charactersPreview.test.js`
- Modify: `frontend/src/pages/CharactersPage.vue`

- [ ] **Step 1: Write the failing preview selection tests**

```js
import { describe, expect, it } from 'vitest'
import { selectCharacterPreview } from '@/pages/CharactersPage.vue'

describe('selectCharacterPreview', () => {
  it('prefers the latest assistant message preview', () => {
    expect(selectCharacterPreview({
      lastMessage: '用户：你在吗',
      lastCharacterMessage: '角色：我在，一直都在。',
    })).toBe('角色：我在，一直都在。')
  })

  it('falls back to the generic last message when no assistant reply exists', () => {
    expect(selectCharacterPreview({
      lastMessage: '用户：你好',
      lastCharacterMessage: '',
    })).toBe('用户：你好')
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx vitest run frontend/src/pages/__tests__/charactersPreview.test.js`

Expected: FAIL because `selectCharacterPreview` does not exist yet.

- [ ] **Step 3: Extract and use assistant-first preview selection**

```js
// frontend/src/pages/CharactersPage.vue
export function selectCharacterPreview(conversationEntry, fallbackText) {
  const assistantPreview = conversationEntry?.lastCharacterMessage?.trim()
  if (assistantPreview) return assistantPreview

  const latestPreview = conversationEntry?.lastMessage?.trim()
  if (latestPreview) return latestPreview

  return fallbackText
}

function lastMessageForCharacter(characterId) {
  return selectCharacterPreview(
    singleConvByCharacterId.value[characterId],
    t('characters.noMessagesYet'),
  )
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `npx vitest run frontend/src/pages/__tests__/charactersPreview.test.js`

Expected: PASS with 2 tests passing.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/__tests__/charactersPreview.test.js frontend/src/pages/CharactersPage.vue
git commit -m "fix(characters): prefer assistant replies in card previews"
```

### Task 5: Full Verification Pass

**Files:**
- Test: `frontend/src/auth/__tests__/bootstrap.test.js`
- Test: `frontend/src/composables/__tests__/useStreamAbort.test.js`
- Test: `frontend/src/pages/__tests__/charactersPreview.test.js`
- Verify: `frontend/src/main.js`
- Verify: `frontend/src/pages/ChatPage.vue`
- Verify: `frontend/src/pages/QuickChatPage.vue`
- Verify: `frontend/src/pages/CharactersPage.vue`

- [ ] **Step 1: Run the full targeted test set**

Run: `npx vitest run frontend/src/auth/__tests__/bootstrap.test.js frontend/src/composables/__tests__/useStreamAbort.test.js frontend/src/pages/__tests__/charactersPreview.test.js`

Expected: PASS with all tests green.

- [ ] **Step 2: Run an Electron/frontend smoke build**

Run: `npm run electron:build`

Expected: PASS with Vite build and Electron packaging completing successfully.

- [ ] **Step 3: Manual startup verification**

Check:
- logged-in launch opens directly into `/app`,
- logged-out launch opens `LandingPage`,
- no landing-page flash when a local session exists,
- window becomes visible without waiting on profile/notification/character fetches.

- [ ] **Step 4: Manual chat continuity verification**

Check:
- send in main single chat then leave page immediately; reply still appears after returning,
- send in quick chat then close/leave; reply still appears after reopening,
- no duplicate assistant reply is shown after refresh.

- [ ] **Step 5: Manual character-card verification**

Check:
- character card preview shows the assistant's newest line,
- if no assistant line exists yet, card still shows the last available conversation preview.

- [ ] **Step 6: Commit final polish if verification required small follow-up edits**

```bash
git add frontend/src/auth/__tests__/bootstrap.test.js frontend/src/composables/__tests__/useStreamAbort.test.js frontend/src/pages/__tests__/charactersPreview.test.js frontend/src/auth/bootstrap.js frontend/src/main.js frontend/src/router/index.js frontend/src/composables/useStreamAbort.js frontend/src/pages/ChatPage.vue frontend/src/pages/QuickChatPage.vue frontend/src/pages/CharactersPage.vue
git commit -m "test(frontend): verify startup routing and chat continuity"
```
