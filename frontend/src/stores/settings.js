import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import { setUiLocale } from '@/i18n'
import {
  DEFAULT_MODEL_LANGUAGE,
  DEFAULT_UI_LANGUAGE,
  STORAGE_MODEL_LANGUAGE,
  STORAGE_UI_LANGUAGE,
  normalizeLanguage
} from '@/constants/language'
import {
  DEFAULT_ACCENT,
  DEFAULT_BACKGROUND,
  applyTheme,
  normalizeHex
} from '@/utils/themeColor'

const STORAGE_SIDEBAR = 'lianyu-sidebar'
const STORAGE_THEME = 'lianyu-theme'
const STORAGE_ACCENT = 'lianyu-accent-color'
const STORAGE_BG = 'lianyu-bg-color'
const STORAGE_CHAT_BG = 'lianyu-character-chat-bg'

function loadChatBackgrounds() {
  try {
    const raw = localStorage.getItem(STORAGE_CHAT_BG)
    return raw ? JSON.parse(raw) : {}
  } catch {
    return {}
  }
}

export const useSettingsStore = defineStore('settings', () => {
  const sidebarCollapsed = ref(localStorage.getItem(STORAGE_SIDEBAR) === 'collapsed')
  const theme = ref(localStorage.getItem(STORAGE_THEME) || 'dark')

  const savedAccent = normalizeHex(localStorage.getItem(STORAGE_ACCENT))
  const savedBg = normalizeHex(localStorage.getItem(STORAGE_BG))
  const accentColor = ref(savedAccent || DEFAULT_ACCENT)
  const backgroundColor = ref(savedBg || DEFAULT_BACKGROUND)

  const uiLanguage = ref(
    normalizeLanguage(localStorage.getItem(STORAGE_UI_LANGUAGE), DEFAULT_UI_LANGUAGE)
  )
  const modelOutputLanguage = ref(
    normalizeLanguage(localStorage.getItem(STORAGE_MODEL_LANGUAGE), DEFAULT_MODEL_LANGUAGE)
  )
  const chatBackgroundByCharacter = ref(loadChatBackgrounds())

  watch(sidebarCollapsed, val => {
    localStorage.setItem(STORAGE_SIDEBAR, val ? 'collapsed' : 'expanded')
  })

  watch(theme, val => {
    localStorage.setItem(STORAGE_THEME, val)
    document.documentElement.classList.toggle('dark', val === 'dark')
  })

  function persistAndApply() {
    const bg = normalizeHex(backgroundColor.value)
    const accent = normalizeHex(accentColor.value)
    if (!bg || !accent) return
    localStorage.setItem(STORAGE_BG, bg)
    localStorage.setItem(STORAGE_ACCENT, accent)
    applyTheme(bg, accent)
  }

  watch([backgroundColor, accentColor], persistAndApply)

  function toggleSidebar() {
    sidebarCollapsed.value = !sidebarCollapsed.value
  }

  function setAccentColor(color) {
    const normalized = normalizeHex(color)
    if (normalized) accentColor.value = normalized
  }

  function setBackgroundColor(color) {
    const normalized = normalizeHex(color)
    if (normalized) backgroundColor.value = normalized
  }

  function applyThemePreset(bg, accent) {
    const normalizedBg = normalizeHex(bg)
    const normalizedAccent = normalizeHex(accent)
    if (normalizedBg) backgroundColor.value = normalizedBg
    if (normalizedAccent) accentColor.value = normalizedAccent
  }

  function resetTheme() {
    backgroundColor.value = DEFAULT_BACKGROUND
    accentColor.value = DEFAULT_ACCENT
    localStorage.removeItem(STORAGE_BG)
    localStorage.removeItem(STORAGE_ACCENT)
    applyTheme(DEFAULT_BACKGROUND, DEFAULT_ACCENT)
  }

  function initTheme() {
    document.documentElement.classList.toggle('dark', theme.value === 'dark')
    applyTheme(backgroundColor.value, accentColor.value)
  }

  function initLanguage() {
    const ui = normalizeLanguage(localStorage.getItem(STORAGE_UI_LANGUAGE), DEFAULT_UI_LANGUAGE)
    const model = normalizeLanguage(
      localStorage.getItem(STORAGE_MODEL_LANGUAGE),
      DEFAULT_MODEL_LANGUAGE
    )
    uiLanguage.value = ui
    modelOutputLanguage.value = model
    setUiLocale(ui)
  }

  function setUiLanguage(lang) {
    const normalized = normalizeLanguage(lang)
    uiLanguage.value = normalized
    localStorage.setItem(STORAGE_UI_LANGUAGE, normalized)
    setUiLocale(normalized)
  }

  function setModelOutputLanguage(lang) {
    const normalized = normalizeLanguage(lang)
    modelOutputLanguage.value = normalized
    localStorage.setItem(STORAGE_MODEL_LANGUAGE, normalized)
  }

  function persistChatBackgrounds() {
    localStorage.setItem(STORAGE_CHAT_BG, JSON.stringify(chatBackgroundByCharacter.value))
  }

  function getChatBackground(characterId) {
    if (characterId == null || characterId === '') return ''
    return chatBackgroundByCharacter.value[String(characterId)] || ''
  }

  function setChatBackground(characterId, key) {
    if (characterId == null || characterId === '') return
    const map = { ...chatBackgroundByCharacter.value }
    if (key) {
      map[String(characterId)] = key
    } else {
      delete map[String(characterId)]
    }
    chatBackgroundByCharacter.value = map
    persistChatBackgrounds()
  }

  return {
    sidebarCollapsed,
    theme,
    accentColor,
    backgroundColor,
    uiLanguage,
    modelOutputLanguage,
    toggleSidebar,
    setAccentColor,
    setBackgroundColor,
    applyThemePreset,
    resetTheme,
    initTheme,
    initLanguage,
    setUiLanguage,
    setModelOutputLanguage,
    getChatBackground,
    setChatBackground,
    /** @deprecated */
    resetAccentColor: resetTheme
  }
})
