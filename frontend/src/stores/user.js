import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import {
  login as loginApi,
  register as registerApi,
  logout as logoutApi,
  getProfile,
  updateProfile as updateProfileApi,
  uploadProfileAvatar as uploadProfileAvatarApi
} from '@/api/auth'

export const useUserStore = defineStore('user', () => {
  const token = ref(localStorage.getItem('lianyu-token') || '')
  const tokenName = ref(localStorage.getItem('lianyu-token-name') || '')
  const userId = ref(null)
  const username = ref('')
  const nickname = ref('')
  const avatarUrl = ref('')

  const isLoggedIn = computed(() => !!token.value)
  const displayName = computed(() => nickname.value || username.value || '未登录')

  function setAuth(t, tn, user) {
    token.value = t
    tokenName.value = tn
    localStorage.setItem('lianyu-token', t)
    localStorage.setItem('lianyu-token-name', tn)
    if (user) {
      applyProfile(user)
    }
  }

  function applyProfile(user) {
    userId.value = user.userId
    username.value = user.username
    nickname.value = user.nickname
    avatarUrl.value = user.avatarUrl
  }

  function clearAuth() {
    token.value = ''
    tokenName.value = ''
    userId.value = null
    username.value = ''
    nickname.value = ''
    avatarUrl.value = ''
    localStorage.removeItem('lianyu-token')
    localStorage.removeItem('lianyu-token-name')
  }

  async function login(data) {
    const res = await loginApi(data)
    setAuth(res.token, res.tokenName, res)
    return res
  }

  async function register(data) {
    await registerApi(data)
  }

  async function logout() {
    try { await logoutApi() } catch {}
    clearAuth()
  }

  async function fetchProfile() {
    const res = await getProfile()
    if (res) {
      applyProfile(res)
    }
    return res
  }

  async function updateProfile(data) {
    const res = await updateProfileApi(data)
    if (res) {
      applyProfile(res)
    }
    return res
  }

  async function uploadAvatar(file) {
    const res = await uploadProfileAvatarApi(file)
    if (res) {
      applyProfile(res)
    }
    return res
  }

  return {
    token, tokenName, userId, username, nickname, avatarUrl,
    isLoggedIn, displayName,
    setAuth, applyProfile, clearAuth, login, register, logout, fetchProfile, updateProfile, uploadAvatar
  }
})
