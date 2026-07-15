import { useUserStore } from '@/stores/user'
import router from '@/router/index.js'
import { readToken, syncToken, syncSetTokenCache } from '@/utils/secureToken'
import { PROFILE_CACHE_KEY } from '@/constants/authSession'
import { getElectronAPI } from '@/utils/electron'

const AUTO_ENTRY_PATHS = new Set(['/', '/login', '/register'])
const LAST_ROUTE_KEY = 'lianyu-last-route'

function readLastAppRoute() {
  try {
    const raw = localStorage.getItem(LAST_ROUTE_KEY)
    if (!raw || typeof raw !== 'string') return '/app'
    if (!raw.startsWith('/app')) return '/app'
    return raw
  } catch {
    return '/app'
  }
}

function hydrateProfileFromCache(userStore) {
  try {
    const raw = localStorage.getItem(PROFILE_CACHE_KEY)
    if (raw) userStore.applyProfile(JSON.parse(raw))
  } catch {
    // ignore corrupt cache
  }
}

/** mount 前：已解密 token 则进主界面，避免仅凭 profile 残留误判已登录 */
export async function prepareAuthRoute(pinia) {
  // #14：重载后内存 token 丢失，向主进程 auth:bootstrap-token 一次性取回明文注入内存，
  //       供后续路由守卫 / STOMP / 直连请求使用（主进程 safeStorage 为唯一持久存储）。
  //       非 Electron（DEV/浏览器）无此 IPC，readToken 兜底返回 null→需重新登录。
  const electronAPI = getElectronAPI()
  if (electronAPI?.bootstrapAuthToken) {
    const token = await electronAPI.bootstrapAuthToken()
    if (token) syncSetTokenCache(token)
  } else {
    await readToken()
  }
  const hashPath = (window.location.hash.replace(/^#/, '') || '/').split('?')[0]
  if (!AUTO_ENTRY_PATHS.has(hashPath)) return false

  const cachedToken = syncToken()
  if (!cachedToken) return false

  const userStore = useUserStore(pinia)
  userStore.token = cachedToken
  syncSetTokenCache(cachedToken)
  hydrateProfileFromCache(userStore)
  await router.replace(readLastAppRoute())
  return true
}

/** mount 后恢复完整会话（profile + 主进程同步） */
export async function bootstrapAuth(pinia) {
  const userStore = useUserStore(pinia)
  const restored = await userStore.restoreSession()
  if (restored) {
    // #15：恢复成功后主动滑动续签一次（后端 renewTimeout），延长长会话寿命；
    //      失败不阻塞启动（临界过期由后续 401 单飞刷新兜底）。
    try {
      const { refreshAuthToken } = await import('@/api/auth')
      await refreshAuthToken()
    } catch { /* 滑动续签失败不阻断启动 */ }
    const hashPath = (window.location.hash.replace(/^#/, '') || '/').split('?')[0]
    if (AUTO_ENTRY_PATHS.has(hashPath)) {
      await router.replace(readLastAppRoute())
    }
  } else if (router.currentRoute.value?.meta?.requiresAuth) {
    await router.replace('/')
  }
  return restored
}
