# 前端跳转与交互卡顿优化 实现计划（方案 A）

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:test-driven-development to implement task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** 在不破坏任何原有功能、不动构建配置/依赖的前提下，消除 Electron 客户端路由跳转与页内交互卡顿。

**Architecture:** 全部改动落在运行时：路由过渡瘦身（纯 opacity + 缩短 + EaseOutQuint）、7 个列表页 keep-alive + SWR 回页后台重取、过渡期间暂停气氛动画、空闲预热相邻路由 chunk。可测逻辑抽成纯 `.js` composable（项目 vitest 为 node env，不挂载 SFC）。

**Tech Stack:** Vue 3.5 · vue-router 4.4 · Pinia 2.2 · Vitest 2.x（node env）· SCSS

**Commit policy:** 按项目规则（CLAUDE.md）不主动 commit；任务验证靠 `npm test` + `vite build`。

---

## File Structure

- Create: `frontend/src/composables/useKeepAlivePages.js` — 缓存名单 + isCacheable 判定（纯 JS，可测）
- Create: `frontend/src/composables/useRouteTransition.js` — 过渡常量 + class 切换调度 + 预热 chunk 名单（纯 JS，可测）
- Modify: `frontend/src/styles/variables.scss` — 新增页过渡 token（不改既有 token）
- Modify: `frontend/src/styles/global.scss` — 页过渡 keyframes 瘦身 + 气氛暂停规则 + reduced-motion
- Modify: `frontend/src/styles/app-shell.scss` — 气氛浮动暂停规则
- Modify: `frontend/src/App.vue` — 包 keep-alive + 过渡 class 切换
- Modify: `frontend/src/main.js` — 空闲预热块
- Modify: 7 个列表页 — 各加 `defineOptions({name})` + `onActivated` SWR；MomentsPage 额外把 polling 迁到 onActivated/onDeactivated

---

### Task 1: 新增页过渡 SCSS token

**Files:**
- Modify: `frontend/src/styles/variables.scss` (在 `// --- Transitions ---` 区块末尾追加)

- [ ] **Step 1: 追加 token**

在 `$transition-spring` 行之后追加：

```scss
// 页过渡专用（DESIGN.md 规则 5：EaseOutQuint；纯 opacity，避免 backdrop-filter 重栅格化）
$transition-page-leave: 100ms cubic-bezier(0.23, 1, 0.32, 1);
$transition-page-enter: 180ms cubic-bezier(0.23, 1, 0.32, 1);
```

- [ ] **Step 2: 验证构建**

Run: `npm run build -- --mode development 2>&1 | Select-String "error|Error" `
workdir: `frontend`
Expected: 无 SCSS 编译错误（若 vite build 因 electron 配置触发，至少 SCSS 段无错）

---

### Task 2: useKeepAlivePages composable（TDD）

**Files:**
- Create: `frontend/src/composables/useKeepAlivePages.js`
- Test: `frontend/src/composables/__tests__/useKeepAlivePages.test.js`

- [ ] **Step 1: 写失败测试**

```js
// frontend/src/composables/__tests__/useKeepAlivePages.test.js
import { describe, expect, it } from 'vitest'
import { KEEP_ALIVE_PAGES, isCacheable } from '@/composables/useKeepAlivePages'

describe('useKeepAlivePages', () => {
  it('contains exactly the 7 heavy list pages', () => {
    expect(KEEP_ALIVE_PAGES).toEqual([
      'HomePage',
      'CharactersPage',
      'CharacterSquarePage',
      'MomentsPage',
      'MemoryPage',
      'DiaryPage',
      'ProfilePage',
    ])
  })

  it('isCacheable returns true for cached page names', () => {
    expect(isCacheable('HomePage')).toBe(true)
    expect(isCacheable('MomentsPage')).toBe(true)
    expect(isCacheable('ProfilePage')).toBe(true)
  })

  it('isCacheable returns false for non-cached / stateful pages', () => {
    expect(isCacheable('Chat')).toBe(false)
    expect(isCacheable('CharacterChatDetail')).toBe(false)
    expect(isCacheable('GroupChat')).toBe(false)
    expect(isCacheable('Settings')).toBe(false)
    expect(isCacheable('Landing')).toBe(false)
    expect(isCacheable(undefined)).toBe(false)
  })
})
```

- [ ] **Step 2: 跑测试确认失败**

Run: `npx vitest run src/composables/__tests__/useKeepAlivePages.test.js`
workdir: `frontend`
Expected: FAIL（模块不存在）

- [ ] **Step 3: 实现 composable**

```js
// frontend/src/composables/useKeepAlivePages.js
// keep-alive :include 名单：仅缓存重列表页。
// 不缓存：Chat/CharacterChatDetail（按 id 挂载）、GroupChat（会话状态）、
// Settings/QqBridge/About（轻量/可弃表单）、Landing/Login/Register/Launcher/QuickChat。
export const KEEP_ALIVE_PAGES = [
  'HomePage',
  'CharactersPage',
  'CharacterSquarePage',
  'MomentsPage',
  'MemoryPage',
  'DiaryPage',
  'ProfilePage',
]

const CACHE_SET = new Set(KEEP_ALIVE_PAGES)

export function isCacheable(name) {
  return !!name && CACHE_SET.has(name)
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `npx vitest run src/composables/__tests__/useKeepAlivePages.test.js`
workdir: `frontend`
Expected: PASS（3 tests）

---

### Task 3: 7 个缓存页加 defineOptions({name})

**Files:**
- Modify: `frontend/src/pages/HomePage.vue` / `CharactersPage.vue` / `CharacterSquarePage.vue` / `MomentsPage.vue` / `MemoryPage.vue` / `DiaryPage.vue` / `ProfilePage.vue`

- [ ] **Step 1: 每个页面 `<script setup>` 顶部 import 后加 defineOptions**

各页在 `import` 区块之后、其它逻辑之前加（名字与 Task 2 名单逐一对应）：

```js
// HomePage.vue
defineOptions({ name: 'HomePage' })
// CharactersPage.vue
defineOptions({ name: 'CharactersPage' })
// CharacterSquarePage.vue
defineOptions({ name: 'CharacterSquarePage' })
// MomentsPage.vue
defineOptions({ name: 'MomentsPage' })
// MemoryPage.vue
defineOptions({ name: 'MemoryPage' })
// DiaryPage.vue
defineOptions({ name: 'DiaryPage' })
// ProfilePage.vue
defineOptions({ name: 'ProfilePage' })
```

- [ ] **Step 2: 验证构建**

Run: `npm run build -- --mode development 2>&1 | Select-String "error|Error"`
workdir: `frontend`
Expected: 无编译错误

---

### Task 4: 路由过渡 CSS 瘦身（global.scss）

**Files:**
- Modify: `frontend/src/styles/global.scss:345-386`

- [ ] **Step 1: 替换 Page Transition 段**

把 `global.scss` 中 `// --- Page Transition ---` 到 `@media (prefers-reduced-motion: reduce)` 之前的 `stagger-item` 段不变；只替换 `.page-enter-active` / `.page-leave-active` / 两个 keyframes：

```scss
// --- Page Transition ---
// out-in 模式：纯 opacity 淡入淡出（删除 translateY，避免 backdrop-filter 逐帧重栅格化）。
// 时长缩短 + EaseOutQuint（DESIGN.md 规则 5）。reduced-motion 下即时切换。
.page-enter-active {
  animation: pageEnter $transition-page-enter;
}
.page-leave-active {
  animation: pageLeave $transition-page-leave;
}

@keyframes pageEnter {
  from { opacity: 0; }
  to { opacity: 1; }
}
@keyframes pageLeave {
  from { opacity: 1; }
  to { opacity: 0; }
}
```

在已有 `@media (prefers-reduced-motion: reduce)` 块内追加页过渡禁用：

```scss
@media (prefers-reduced-motion: reduce) {
  .stagger-item {
    opacity: 1;
    animation: none;
    transform: none;
  }
  .page-enter-active,
  .page-leave-active {
    animation: none;
  }
}
```

- [ ] **Step 2: 验证构建**

Run: `npm run build -- --mode development 2>&1 | Select-String "error|Error"`
workdir: `frontend`
Expected: 无 SCSS 错误

---

### Task 5: useRouteTransition composable（TDD）

**Files:**
- Create: `frontend/src/composables/useRouteTransition.js`
- Test: `frontend/src/composables/__tests__/useRouteTransition.test.js`

- [ ] **Step 1: 写失败测试**

```js
// frontend/src/composables/__tests__/useRouteTransition.test.js
import { describe, expect, it, vi } from 'vitest'
import {
  ROUTE_TRANSITION_CLASS,
  ROUTE_TRANSITION_DURATION_MS,
  PREFETCH_ROUTE_FACTORIES,
  prefetchRoutesOnIdle,
} from '@/composables/useRouteTransition'

describe('useRouteTransition', () => {
  it('exposes the transition class name and duration', () => {
    expect(ROUTE_TRANSITION_CLASS).toBe('ly-route-transitioning')
    expect(ROUTE_TRANSITION_DURATION_MS).toBe(220)
  })

  it('provides 6 prefetch factory functions', () => {
    expect(PREFETCH_ROUTE_FACTORIES).toHaveLength(6)
    for (const f of PREFETCH_ROUTE_FACTORIES) {
      expect(typeof f).toBe('function')
    }
  })

  it('prefetchRoutesOnIdle calls all factories on idle callback', () => {
    const calls = []
    const factories = [() => calls.push('a'), () => calls.push('b')]
    const ric = vi.fn((cb) => cb())
    const allSettled = vi.fn((arr) => Promise.all(arr))
    prefetchRoutesOnIdle({ factories, requestIdleCallback: ric, Promise: { allSettled } })
    expect(calls).toEqual(['a', 'b'])
    expect(ric).toHaveBeenCalled()
  })

  it('prefetchRoutesOnIdle no-ops when requestIdleCallback unavailable', () => {
    const factories = [() => { throw new Error('should not run') }]
    expect(() => prefetchRoutesOnIdle({ factories, requestIdleCallback: undefined })).not.toThrow()
  })
})
```

- [ ] **Step 2: 跑测试确认失败**

Run: `npx vitest run src/composables/__tests__/useRouteTransition.test.js`
workdir: `frontend`
Expected: FAIL（模块不存在）

- [ ] **Step 3: 实现 composable**

```js
// frontend/src/composables/useRouteTransition.js
// 路由过渡期间的气氛动画暂停 class + 时长；以及空闲预热相邻路由 chunk。
// 不含 Vue 生命周期——App.vue / main.js 负责注入执行环境，便于在 node env 单测。
export const ROUTE_TRANSITION_CLASS = 'ly-route-transitioning'
export const ROUTE_TRANSITION_DURATION_MS = 220

// 6 个主应用页 chunk 的动态 import 工厂；首次进页前在空闲时段解析+编译进内存。
export const PREFETCH_ROUTE_FACTORIES = [
  () => import('@/pages/HomePage.vue'),
  () => import('@/pages/CharactersPage.vue'),
  () => import('@/pages/CharacterSquarePage.vue'),
  () => import('@/pages/MomentsPage.vue'),
  () => import('@/pages/MemoryPage.vue'),
  () => import('@/pages/SettingsPage.vue'),
]

export function prefetchRoutesOnIdle({
  factories = PREFETCH_ROUTE_FACTORIES,
  requestIdleCallback = typeof window !== 'undefined' ? window.requestIdleCallback : undefined,
  Promise = globalThis.Promise,
} = {}) {
  if (typeof requestIdleCallback !== 'function') return
  requestIdleCallback(() => {
    if (typeof Promise?.allSettled === 'function') {
      Promise.allSettled(factories.map((f) => f())).catch(() => {})
    } else {
      for (const f of factories) {
        try { f() } catch {}
      }
    }
  })
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `npx vitest run src/composables/__tests__/useRouteTransition.test.js`
workdir: `frontend`
Expected: PASS（4 tests）

---

### Task 6: App.vue 包 keep-alive + 过渡 class 切换

**Files:**
- Modify: `frontend/src/App.vue`

- [ ] **Step 1: 改 template 与 script**

template（`App.vue:8-12`）改为：

```vue
<router-view v-slot="{ Component, route }">
  <transition :name="pageTransitionName" :mode="pageTransitionMode">
    <keep-alive :include="keepAlivePages">
      <component :is="Component" :key="viewKey(route)" />
    </keep-alive>
  </transition>
</router-view>
```

script setup 中新增 import 与 keep-alive 名单 + 过渡 class 调度：

```js
import { computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import { KEEP_ALIVE_PAGES } from '@/composables/useKeepAlivePages'
import {
  ROUTE_TRANSITION_CLASS,
  ROUTE_TRANSITION_DURATION_MS,
} from '@/composables/useRouteTransition'

const keepAlivePages = KEEP_ALIVE_PAGES
let transitionTimer = null
function markRouteTransitioning() {
  const root = document.documentElement
  root.classList.add(ROUTE_TRANSITION_CLASS)
  if (transitionTimer) clearTimeout(transitionTimer)
  transitionTimer = setTimeout(() => {
    root.classList.remove(ROUTE_TRANSITION_CLASS)
    transitionTimer = null
  }, ROUTE_TRANSITION_DURATION_MS)
}
```

在已有 `watch(() => [route.name, route.path, settingsStore.theme], ...)` 附近新增：

```js
watch(
  () => route.path,
  (to, from) => {
    if (to !== from) markRouteTransitioning()
  }
)
```

`onBeforeUnmount` 中清理计时器（在已有 `qqAlertUnsub?.()` 行旁）：

```js
onBeforeUnmount(() => {
  qqAlertUnsub?.()
  if (transitionTimer) { clearTimeout(transitionTimer); transitionTimer = null }
})
```

- [ ] **Step 2: 验证构建**

Run: `npm run build -- --mode development 2>&1 | Select-String "error|Error"`
workdir: `frontend`
Expected: 无编译错误

---

### Task 7: 气氛动画暂停规则（global.scss + app-shell.scss）

**Files:**
- Modify: `frontend/src/styles/global.scss`（在 Page Transition 段之后追加）
- Modify: `frontend/src/styles/app-shell.scss`（文件末尾追加）

- [ ] **Step 1: global.scss 追加 #app::before 暂停规则**

在 Page Transition 段之后追加：

```scss
// 路由过渡期间暂停 #app::before 的 ambientShift，把 GPU 帧预算让给页面淡入。
.ly-route-transitioning #app::before {
  animation-play-state: paused !important;
}
```

- [ ] **Step 2: app-shell.scss 追加 atmosphere-float 暂停规则**

文件末尾追加：

```scss
// 路由过渡期间暂停气氛层浮动 orb，结束后自动恢复。
.ly-route-transitioning .atmosphere-orb {
  animation-play-state: paused !important;
}
```

- [ ] **Step 3: 验证 class 真实存在**

确认 `app-shell.scss` 中 atmosphere 浮动元素类名为 `.atmosphere-orb`（grep `atmosphere-float` 定位其选择器；若类名不同则按实际改选择器）。

Run: 在 `frontend/src/styles/app-shell.scss` 中 grep `animation: atmosphere-float`
Expected: 找到对应选择器，确认 Step 2 选择器与之匹配

- [ ] **Step 4: 验证构建**

Run: `npm run build -- --mode development 2>&1 | Select-String "error|Error"`
workdir: `frontend`
Expected: 无 SCSS 错误

---

### Task 8: main.js 空闲预热

**Files:**
- Modify: `frontend/src/main.js`

- [ ] **Step 1: 在 `app.mount` / `router.isReady` 之后接入预热**

在已有 `if (aux && isQuickChatSurface()) { ... }` 块之后、文件末尾的 IIFE 收尾之前追加：

```js
if (!aux) {
  import('@/composables/useRouteTransition').then(({ prefetchRoutesOnIdle }) => {
    prefetchRoutesOnIdle()
  }).catch(() => {})
}
```

- [ ] **Step 2: 验证构建**

Run: `npm run build -- --mode development 2>&1 | Select-String "error|Error"`
workdir: `frontend`
Expected: 无编译错误

---

### Task 9: 7 个缓存页 onActivated SWR

**Files:**
- Modify: 7 个列表页（同 Task 3）

各页在已有 `onMounted` 之后新增 `onActivated`，调其已有 fetch 函数做后台重取（先显缓存内容，后台回来替换）。**不重复一次性 setup**（监听器/轮询启动等）。

- [ ] **Step 1: HomePage.vue** — `onMounted:257` 之后加：

```js
import { computed, ref, onMounted, onActivated } from 'vue'

onActivated(() => {
  charactersStore.fetchList().catch(() => [])
  conversationsStore.fetchList().catch(() => [])
  listCharacterStates({ silent: true }).then((states) => {
    emotionStates.value = Array.isArray(states) ? states : []
  }).catch(() => {})
  loadFeedPreview()
})
```
（`onMounted` 主体不动；SWR 仅复用其数据加载部分）

- [ ] **Step 2: CharactersPage.vue** — `onMounted:369` 之后加：

```js
import { ref, reactive, onMounted, onActivated, watch, computed } from 'vue'

onActivated(() => {
  fetchCharacters()
})
```
（不重复 `notificationsStore.init()`——它可能注册监听，重复调用有重复注册风险；init 由 onMounted 一次即可）

- [ ] **Step 3: CharacterSquarePage.vue** — `onMounted:387` 之后加：

```js
import { computed, onMounted, onActivated, onUnmounted, ref, watch } from 'vue'

onActivated(() => {
  loadCatalog()
})
```

- [ ] **Step 4: MemoryPage.vue** — `onMounted:140` 之后加：

```js
import { ref, computed, onMounted, onActivated } from 'vue'

onActivated(() => {
  fetchMemories()
})
```

- [ ] **Step 5: DiaryPage.vue** — 当前 `onMounted:98` 是内联 fetch，先抽函数再复用。`onMounted` 之前定义：

```js
import { computed, ref, onMounted, onActivated, watch } from 'vue'

async function loadDiaries() {
  try {
    const data = await listAllDiaries({ page: 1, size: 50 })
    diaries.value = Array.isArray(data) ? data : []
  } catch {
    /* errors handled by global interceptor */
  } finally {
    loading.value = false
  }
}
```
`onMounted` 改为：

```js
onMounted(async () => {
  applyRouteCharacterFilter()
  await loadDiaries()
})
```
追加：

```js
onActivated(() => {
  loadDiaries()
})
```

- [ ] **Step 6: ProfilePage.vue** — `onMounted:190` 之后加：

```js
import { computed, nextTick, onMounted, onActivated, onUnmounted, reactive, ref, watch } from 'vue'

onActivated(() => {
  userStore.fetchProfile().catch(() => {})
})
```
（不重复 hash 滚动——那是首次挂载行为；SWR 仅刷新 profile 数据）

- [ ] **Step 7: MomentsPage.vue — 含 polling 生命周期迁移**

`onMounted:492` 当前做：fetch + reloadFeed + loadSidebarData + markMomentsSeen + `startCommentPolling()`；`onUnmounted:520` 做 `stopCommentPolling()`。

keep-alive 后 `onUnmounted` 不在切页时触发（仅缓存被 prune 时触发）→ 轮询会在后台泄漏。修复：把轮询启停迁到 `onActivated`/`onDeactivated`，`onUnmounted` 保留作为最终兜底。

改 import：

```js
import { computed, onMounted, onActivated, onDeactivated, onUnmounted, reactive, ref, watch } from 'vue'
```

`onMounted` 去掉 `startCommentPolling()`：

```js
onMounted(async () => {
  try {
    await charactersStore.fetchList()
    await userStore.fetchProfile()
  } catch {
    charactersStore.invalidate()
  }
  applyRouteCharacterFilter()
  await Promise.all([reloadFeed(), loadSidebarData()])
  try { await markMomentsSeen() } catch {}
})
```

新增：

```js
onActivated(() => {
  reloadFeed()
  loadSidebarData()
  startCommentPolling()
})

onDeactivated(() => {
  stopCommentPolling()
})
```

`onUnmounted` 保留（最终兜底）：

```js
onUnmounted(() => {
  stopCommentPolling()
})
```

- [ ] **Step 8: 验证构建**

Run: `npm run build -- --mode development 2>&1 | Select-String "error|Error"`
workdir: `frontend`
Expected: 无编译错误

---

### Task 10: 全量验证

- [ ] **Step 1: 跑全部单元测试**

Run: `npm test`
workdir: `frontend`
Expected: 全绿（含新增 2 个 composable 测试套件，既有测试无回归）

- [ ] **Step 2: 跑生产构建**

Run: `npm run build`
workdir: `frontend`
Expected: 构建成功，无 error

- [ ] **Step 3: 冒烟检查（手动 / webapp-testing）**

启动 `npm run dev`，在浏览器逐项验证：
- 点 Dock 在 Home/Characters/Moments/Memory/Profile 间反复切换：无白屏、过渡顺滑、回页瞬时显缓存内容
- Characters 列表回页不重新转圈（瞬时显示，后台刷新）
- Moments 切走再切回：评论轮询不重复（DevTools 只一个 setInterval）、切走后轮询停
- Chat 页反复进出：每次正常按 id 挂载（未被缓存）
- 视觉无回退：过渡仍是淡入淡出，仅更快更顺

---

## Self-Review（计划自检）

- **Spec 覆盖**：spec §2.1→Task 1,4；§2.2→Task 2,3,6,9；§2.3→Task 5,6,7；§2.4→Task 5,8；§3 验证→Task 10。全覆盖。
- **占位符扫描**：无 TBD/TODO，每步含完整代码。
- **类型/命名一致性**：`KEEP_ALIVE_PAGES` 名单（Task 2）与 Task 3 `defineOptions` 名字、Task 6 `:include` 一致；`ROUTE_TRANSITION_CLASS`（Task 5）与 Task 6/7 CSS 选择器一致。
- **不破坏功能**：Chat/详情/群聊不缓存；MomentsPage polling 迁移防泄漏；ProfilePage 不重复 hash 滚动；CharactersPage 不重复 init 监听。
