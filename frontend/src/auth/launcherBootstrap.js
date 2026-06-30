import { useUserStore } from '@/stores/user'
import { readToken, syncSetTokenCache } from '@/utils/secureToken'
import { getElectronAPI, normalizeAuthSession } from '@/utils/electron'

/** 桌宠窗口：仅恢复本地 token，不阻塞 mount；完整 profile 同步放后台 */
export async function bootstrapLauncherSession(pinia) {
  const userStore = useUserStore(pinia)
  const electronAPI = getElectronAPI()

  if (electronAPI?.getAuthSession) {
    const session = normalizeAuthSession(await electronAPI.getAuthSession())
    if (session?.token) {
      userStore.token = session.token
      syncSetTokenCache(session.token)
      if (session.userId || session.username) {
        userStore.applyProfile(session)
      }
      electronAPI.setLoginState?.(true)
      void userStore.restoreSession()
      return true
    }
  }

  const token = await readToken()
  if (!token) return false

  userStore.token = token
  syncSetTokenCache(token)
  electronAPI?.setLoginState?.(true)
  void userStore.restoreSession()
  return true
}
