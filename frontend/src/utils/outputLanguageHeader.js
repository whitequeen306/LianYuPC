import {
  DEFAULT_MODEL_LANGUAGE,
  HEADER_OUTPUT_LANGUAGE,
  STORAGE_MODEL_LANGUAGE,
  normalizeLanguage
} from '@/constants/language'

export function getOutputLanguageHeaderValue() {
  return normalizeLanguage(
    localStorage.getItem(STORAGE_MODEL_LANGUAGE),
    DEFAULT_MODEL_LANGUAGE
  )
}

export function applyOutputLanguageHeaders(headers = {}) {
  return {
    ...headers,
    [HEADER_OUTPUT_LANGUAGE]: getOutputLanguageHeaderValue(),
    'output-language': getOutputLanguageHeaderValue()
  }
}
