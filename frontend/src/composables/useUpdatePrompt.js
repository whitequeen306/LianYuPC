/**
 * 「发现新版本，请更新」启动提示的判定逻辑（纯函数 + 进程内状态，便于单测）。
 *
 * 为什么单独抽出来：弹不弹、弹几次、点「稍后再装」之后还弹不弹，
 * 这三条规则最容易在改动里悄悄变味（比如变成每次路由切换都弹一次）。
 * 放在 .vue 里就只能靠手点验证；放在这里可以被测试钉住。
 *
 * 状态生命周期 = **一次进程**：
 * - `snoozed`：用户点了「稍后再装」，这一轮就不再打扰；下次启动还会问。
 * - `probed`：这一轮是否已经探测过，避免重复请求 latest.yml。
 */

export const UPDATE_PAGE_PATH = '/app/about'

/** 是否已经探测过（一次进程只探测一次） */
let probed = false
/** 用户是否选择了「稍后再装」（一次进程只问一次） */
let snoozed = false

export function isProbed() {
  return probed
}

export function markProbed() {
  probed = true
}

export function isSnoozed() {
  return snoozed
}

export function snoozeUpdatePrompt() {
  snoozed = true
}

/** 仅供测试：把模块状态复位 */
export function resetUpdatePromptState() {
  probed = false
  snoozed = false
}

/**
 * 探测结果是否该弹「请更新至最新版本」。
 *
 * 只有「探测成功 + 确实有新版本 + 用户没说过稍后」三者同时成立才弹。
 * 探测失败（断网、更新源不可用）一律当作「没事」—— 刚打开软件就糊一个
 * 错误弹窗比不提示更糟。
 */
export function shouldPromptForUpdate(result) {
  if (!result || result.ok !== true) return false
  if (result.hasUpdate !== true) return false
  if (!result.version) return false
  return !snoozed
}

/** 多语言不做的理由：同目录 AppUpdateDialog 的文案也是中文硬编码，保持一致 */
export function buildPromptMessage(version, currentVersion) {
  const latest = version ? `v${version}` : '新版本'
  if (currentVersion) {
    return `你当前是 v${currentVersion}，最新版本是 ${latest}。建议更新到最新版本后再使用。`
  }
  return `发现${latest}，建议更新到最新版本后再使用。`
}

export function buildPromptTitle(version) {
  return version ? `发现新版本 v${version}` : '发现新版本'
}
