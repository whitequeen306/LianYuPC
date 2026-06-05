/** 业务错误码 → 用户可读文案（与后端 ErrorCode 对齐） */
const CODE_MESSAGES = {
  1001: '用户名已存在，请直接登录或换一个用户名',
  1002: '密码错误，请重试',
  1006: '该账号还未注册，请先注册试试',
  1003: '用户不存在',
  1004: '账号已被禁用',
  1005: '尝试次数过多，请稍后再试',
  2003: '你已经添加过该角色了哦',
  4003: '请求太频繁，请稍后再试',
}

/** 将 fetch/axios 等底层英文错误转为用户可读中文 */
const TECHNICAL_RULES = [
  { test: /failed to fetch|fetch failed|network error|networkrequestfailed/i, message: '无法连接服务器，请确认网络正常且后端已启动' },
  { test: /ECONNABORTED|timeout|timed out/i, message: '请求超时，请稍后再试' },
  { test: /^Request failed with status code \d+$/i, message: '' },
  { test: /abort(ed)?/i, message: '请求已取消' },
  { test: /unexpected token|<!doctype/i, message: '服务响应异常，请稍后再试' },
  { test: /^TypeError:|^ReferenceError:|^SyntaxError:/i, message: '操作失败，请刷新页面后重试' },
  { test: /ECONNREFUSED|connection refused/i, message: '无法连接服务器，请确认后端已启动' },
]

const GENERIC_AXIOS_STATUS = /^Request failed with status code \d+$/i

/**
 * @param {unknown} error - Error、axios error 或字符串
 * @param {string} [fallback='操作失败，请稍后再试']
 */
export function humanizeError(error, fallback = '操作失败，请稍后再试') {
  const api = extractApiError(error)
  if (api?.message) {
    return api.message
  }
  if (api?.code && CODE_MESSAGES[api.code]) {
    return CODE_MESSAGES[api.code]
  }

  const raw = extractRawMessage(error)
  if (!raw) return fallback

  for (const rule of TECHNICAL_RULES) {
    if (rule.test.test(raw)) {
      return rule.message || fallback
    }
  }

  if (/^401\b|unauthorized/i.test(raw) && /未登录|登录已过期|token/i.test(raw)) {
    return '登录已过期，请重新登录'
  }

  // 纯英文短句且含技术词，不直接展示
  if (/^[A-Za-z0-9\s:._\-/]+$/.test(raw) && /error|exception|failed|null|undefined|socket|stream/i.test(raw)) {
    return fallback
  }

  return raw
}

/** 从 axios 错误中解析后端 Result 结构 */
export function extractApiError(error) {
  if (!error || typeof error !== 'object') return null
  const data = error.response?.data
  if (!data || typeof data !== 'object' || typeof data.code !== 'number' || data.code === 200) {
    return null
  }
  const message = typeof data.message === 'string' ? data.message.trim() : ''
  if (message) {
    return { code: data.code, message }
  }
  if (CODE_MESSAGES[data.code]) {
    return { code: data.code, message: CODE_MESSAGES[data.code] }
  }
  return { code: data.code, message: '' }
}

function extractRawMessage(error) {
  if (!error) return ''
  if (typeof error === 'string') return error.trim()

  const api = extractApiError(error)
  if (api?.message) return api.message

  const msg = typeof error.message === 'string' ? error.message.trim() : ''
  if (msg && !GENERIC_AXIOS_STATUS.test(msg)) return msg

  return ''
}
