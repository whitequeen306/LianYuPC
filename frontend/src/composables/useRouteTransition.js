// 路由过渡期间的气氛动画暂停 class + 时长；以及空闲预热相邻路由 chunk。
// 不含 Vue 生命周期——App.vue / main.js 负责注入执行环境，便于在 node env 单测。
export const ROUTE_TRANSITION_CLASS = 'ly-route-transitioning'
export const ROUTE_TRANSITION_DURATION_MS = 280

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
