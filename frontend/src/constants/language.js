/** @typedef {'zh'|'ja'|'en'} AppLanguage */

export const SUPPORTED_LANGUAGES = /** @type {const} */ (['zh', 'ja', 'en'])

export const DEFAULT_UI_LANGUAGE = 'zh'
export const DEFAULT_MODEL_LANGUAGE = 'zh'

export const STORAGE_UI_LANGUAGE = 'lianyu-ui-language'
export const STORAGE_MODEL_LANGUAGE = 'lianyu-model-output-language'
export const HEADER_OUTPUT_LANGUAGE = 'X-LianYu-Output-Language'

export function isSupportedLanguage(value) {
  return SUPPORTED_LANGUAGES.includes(value)
}

export function normalizeLanguage(value, fallback = DEFAULT_UI_LANGUAGE) {
  return isSupportedLanguage(value) ? value : fallback
}
