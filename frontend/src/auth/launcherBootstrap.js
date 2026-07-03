import { useUserStore } from '@/stores/user'
import { readToken, resetTokenReadCache, syncSetTokenCache, syncToken } from '@/utils/secureToken'
import { getElectronAPI, normalizeAuthSession } from '@/utils/electron'

/**
 * 仅把主进程 session 写入 Pinia，不 persist / 不拉 profile / 不广播。
 */
export function applyLauncherAuthSession(pinia, session) {
  if (!session?.token) return false
  const userStore = useUserStore(pinia)
  userStore.token = session.token
  syncSetTokenCache(session.token)
  if (session.userId || session.username) {
    userStore.applyProfile(session)
  }
  getElectronAPI()?.setLoginState?.(true)
  return true
}

/**
 * 桌宠 / 快捷聊：从主进程或 partition 恢复登录态（轻量，避免 restoreSession 循环广播）。
 * #14：主进程 getAuthSession 不回传明文 token（仅 hasToken + profile），
 *      明文 token 由 auth:bootstrap-token 一次性注入 secureToken 内存（对齐 prepareAuthRoute）。
 */
export async function refreshLauncherSession(pinia, { fetchProfile = false } = {}) {
  const userStore = useUserStore(pinia)
  const electronAPI = getElectronAPI()

  if (electronAPI?.getAuthSession) {
    const session = normalizeAuthSession(await electronAPI.getAuthSession())
    if (session?.hasToken) {
      let token = syncToken()
      if (!token && electronAPI?.bootstrapAuthToken) {
        token = await electronAPI.bootstrapAuthToken()
        if (token) syncSetTokenCache(token)
      }
      if (token) {
        applyLauncherAuthSession(pinia, { ...session, token })
        if (fetchProfile && !userStore.userId) {
          void userStore.fetchProfile({ skipGlobalError: true }).catch(() => {})
        }
        return true
      }
    }
  }

  if (userStore.token) return true

  resetTokenReadCache()
  const token = await readToken({ force: true })
  if (!token) {
    userStore.token = ''
    return false
  }

  userStore.token = token
  syncSetTokenCache(token)
  electronAPI?.setLoginState?.(true)
  if (fetchProfile) {
    void userStore.fetchProfile({ skipGlobalError: true }).catch(() => {})
  }
  return true
}

/** @deprecated alias */
export async function bootstrapLauncherSession(pinia) {
  return refreshLauncherSession(pinia)
}
