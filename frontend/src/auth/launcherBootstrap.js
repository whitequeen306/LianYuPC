import { useUserStore } from '@/stores/user'
import { getElectronAPI } from '@/utils/electron'
import { syncSetTokenCache } from '@/utils/secureToken'

/** 桌宠 / 角色选择器窗口：恢复登录态（不写回主进程，避免竞态覆盖 token） */
export async function bootstrapLauncherSession(pinia) {
  // #14：辅助窗口不走 prepareAuthRoute，这里同样向主进程一次性取回 token 注入内存，
  //       否则 restoreSession 内 readToken() 取不到内存值会误判未登录。
  const electronAPI = getElectronAPI()
  if (electronAPI?.bootstrapAuthToken) {
    const token = await electronAPI.bootstrapAuthToken()
    if (token) syncSetTokenCache(token)
  }
  const userStore = useUserStore(pinia)
  if (!userStore.isLoggedIn) {
    const restored = await userStore.restoreSession()
    if (!restored) return false
  }
  getElectronAPI()?.setLoginState?.(true)
  getElectronAPI()?.requestChromeSync?.()
  return userStore.isLoggedIn
}
