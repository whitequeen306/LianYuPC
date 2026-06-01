/** BCP 47 tag for Date/toLocale* formatters from app UI locale */
export function dateLocaleForUi(locale) {
  if (locale === 'zh') return 'zh-CN'
  if (locale === 'zh-TW') return 'zh-TW'
  return locale
}
