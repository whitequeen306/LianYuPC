import { createI18n } from 'vue-i18n'
import {
  DEFAULT_UI_LANGUAGE,
  normalizeLanguage,
  STORAGE_UI_LANGUAGE
} from '@/constants/language'
import zh from './locales/zh'
import ja from './locales/ja'
import en from './locales/en'

const saved = normalizeLanguage(localStorage.getItem(STORAGE_UI_LANGUAGE), DEFAULT_UI_LANGUAGE)

export const i18n = createI18n({
  legacy: false,
  locale: saved,
  fallbackLocale: 'zh',
  messages: { zh, ja, en }
})

export function setUiLocale(locale) {
  const normalized = normalizeLanguage(locale)
  i18n.global.locale.value = normalized
  document.documentElement.lang = normalized === 'zh' ? 'zh-CN' : normalized
  return normalized
}
