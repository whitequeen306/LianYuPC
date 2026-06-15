import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import {
  login as loginApi,
  register as registerApi,
  logout as logoutApi,
  changePassword as changePasswordApi,
  getProfile,
  updateProfile as updateProfileApi,
  uploadProfileAvatar as uploadProfileAvatarApi
} from '@/api/auth'
import { storeToken, readToken, clearTokenStorage, syncToken } from '@/utils/secureToken'
import { getElectronAPI } from '@/utils/electron'
import { LAST_USERNAME_KEY, PROFILE_CACHE_KEY } from '@/constants/authSession'

function readProfileCache() {
  try {
    const raw = localStorage.getItem(PROFILE_CACHE_KEY)
    return raw ? JSON.parse(raw) : null
  } catch {
    return null
  }
}

function writeProfileCache(profile) {
  if (!profile) {
    localStorage.removeItem(PROFILE_CACHE_KEY)
    return
  }
  localStorage.setItem(PROFILE_CACHE_KEY, JSON.stringify({
    userId: profile.userId ?? null,
    username: profile.username ?? '',
    nickname: profile.nickname ?? '',
    avatarUrl: profile.avatarUrl ?? '',
  }))
}

function rememberUsername(username) {
  const value = (username || '').trim()
  if (!value) return
  localStorage.setItem(LAST_USERNAME_KEY, value)
}

export function getLastUsername() {
  return localStorage.getItem(LAST_USERNAME_KEY) || ''
}

export const useUserStore = defineStore('user', () => {
  const token = ref(syncToken() || '')
  const tokenName = ref('lianyu-token')
  const userId = ref(null)
  const username = ref('')
  const nickname = ref('')
  const avatarUrl = ref('')

  const isLoggedIn = computed(() => !!token.value)
  const displayName = computed(() => nickname.value || username.value || '未登录')

  async function loadPersistedSession() {
    const electronAPI = getElectronAPI()
    if (electronAPI?.getAuthSession) {
      const session = await electronAPI.getAuthSession()
      if (session) return session
    }

    const cachedToken = await readToken()
    const cachedProfile = readProfileCache()
    if (!cachedToken && !cachedProfile) return null

    return {
      token: cachedToken || '',
      tokenName: 'lianyu-token',
      ...cachedProfile,
    }
  }

  async function persistSession() {
    const electronAPI = getElectronAPI()
    const payload = {
      token: token.value || '',
      tokenName: tokenName.value || '',
      userId: userId.value,
      username: username.value || '',
      nickname: nickname.value || '',
      avatarUrl: avatarUrl.value || '',
      savedAt: Date.now(),
    }

    if (payload.token) {
      await storeToken(payload.token)
      writeProfileCache(payload)
      rememberUsername(payload.username)
    }

    if (electronAPI?.setAuthSession) {
      await electronAPI.setAuthSession(payload.token ? payload : {
        username: payload.username,
        savedAt: payload.savedAt,
      })
    }
  }

  async function restoreSession() {
    const session = await loadPersistedSession()
    if (!session?.token) {
      if (session?.username) {
        rememberUsername(session.username)
      }
      return false
    }

    token.value = session.token
    tokenName.value = session.tokenName || ''
    if (session.userId || session.username) {
      applyProfile(session)
    }
    await storeToken(session.token)

    try {
      const profile = await getProfile()
      if (!profile) {
        throw new Error('profile empty')
      }
      applyProfile(profile)
      await persistSession()
      getElectronAPI()?.setLoginState?.(true)
      return true
    } catch {
      rememberUsername(session.username || username.value)
      await clearAuth({ keepUsername: true })
      return false
    }
  }

  function setAuth(t, tn, user) {
    token.value = t
    tokenName.value = tn
    if (user) {
      applyProfile(user)
    }
    void persistSession()
    getElectronAPI()?.setLoginState?.(true)
  }

  function applyProfile(user) {
    userId.value = user.userId
    username.value = user.username
    nickname.value = user.nickname
    avatarUrl.value = user.avatarUrl
    rememberUsername(user.username)
  }

  async function clearAuth(options = {}) {
    const { keepUsername = false } = options
    const savedUsername = keepUsername ? (username.value || getLastUsername()) : ''

    token.value = ''
    tokenName.value = ''
    userId.value = null
    username.value = ''
    nickname.value = ''
    avatarUrl.value = ''
    localStorage.removeItem(PROFILE_CACHE_KEY)
    clearTokenStorage()

    const electronAPI = getElectronAPI()
    if (electronAPI?.clearAuthSession) {
      if (keepUsername && savedUsername) {
        await electronAPI.setAuthSession({
          username: savedUsername,
          savedAt: Date.now(),
        })
      } else {
        await electronAPI.clearAuthSession()
        localStorage.removeItem(LAST_USERNAME_KEY)
      }
    } else if (!keepUsername) {
      localStorage.removeItem(LAST_USERNAME_KEY)
    }

    electronAPI?.setLoginState?.(false)
  }

  async function login(data) {
    const res = await loginApi(data)
    setAuth(res.token, res.tokenName, res)
    return res
  }

  async function register(data) {
    const res = await registerApi(data)
    setAuth(res.token, res.tokenName, res)
    return res
  }

  async function logout() {
    try { await logoutApi() } catch {}
    await clearAuth()
  }

  async function fetchProfile() {
    const res = await getProfile()
    if (res) {
      applyProfile(res)
      await persistSession()
    }
    return res
  }

  async function updateProfile(data) {
    const res = await updateProfileApi(data)
    if (res) {
      applyProfile(res)
      await persistSession()
    }
    return res
  }

  async function uploadAvatar(file) {
    const res = await uploadProfileAvatarApi(file)
    if (res) {
      applyProfile(res)
      await persistSession()
    }
    return res
  }

  async function changePassword(data) {
    await changePasswordApi(data)
  }

  return {
    token, tokenName, userId, username, nickname, avatarUrl,
    isLoggedIn, displayName,
    setAuth, applyProfile, clearAuth, restoreSession, persistSession,
    login, register, logout, fetchProfile, updateProfile, uploadAvatar, changePassword
  }
})
