/**
 * 输入驱动（鼠标/键盘/滚轮）。
 *
 * 职责边界：**只做参数校验与协议转换**，不判断该不该做 —— 那属于 `policy.js`
 * （在 `capabilityHost` 里先跑策略再调这里）。这样策略可以纯函数单测，
 * 而这一层可以用假 actor 单测，两者都不需要真的动鼠标。
 *
 * 所有坐标都是**物理像素**（actor 启动时已设 PER_MONITOR_AWARE_V2），
 * 模型给的是归一化 0–1000，换算在 `capabilityHost` 用 `scaleFactor`/屏幕尺寸完成。
 */

import { resolveKeySequence } from './winActor.js'

const MAX_TEXT_CHARS = 2000

function assertInt(value, name, { min = -100000, max = 100000 } = {}) {
  const n = Number(value)
  if (!Number.isInteger(n)) throw new Error(`${name} 必须是整数`)
  if (n < min || n > max) throw new Error(`${name} 超出范围`)
  return n
}

export function createInputDriver({ actor, clipboard, log = () => {} } = {}) {
  if (!actor) throw new Error('input driver: actor is required')

  async function pasteViaClipboard(text) {
    if (!clipboard) return { ok: false, error: 'unsupported', detail: 'no clipboard' }
    let previous = ''
    try {
      previous = clipboard.readText()
    } catch { /* 读不到就算了 */ }
    try {
      clipboard.writeText(text)
      const keys = resolveKeySequence(['ctrl', 'v'])
      const res = await actor.call('key', [keys.join(',')])
      return res
    } finally {
      // 恢复用户剪贴板：失败要说出来（用户可能刚复制了东西）
      setTimeout(() => {
        try {
          clipboard.writeText(previous)
        } catch (e) {
          log(`clipboard restore failed: ${e?.message || e}`)
        }
      }, 250)
    }
  }

  return {
    async click({ x, y, button = 'left', clicks = 1 } = {}) {
      const px = assertInt(x, 'x', { min: -20000, max: 20000 })
      const py = assertInt(y, 'y', { min: -20000, max: 20000 })
      const btn = ['left', 'right', 'middle'].includes(button) ? button : 'left'
      const n = assertInt(clicks, 'clicks', { min: 1, max: 3 })
      return actor.call('click', [px, py, btn, n])
    },

    async move({ x, y } = {}) {
      return actor.call('move', [assertInt(x, 'x'), assertInt(y, 'y')])
    },

    /** 组合键：先按白名单解析成 VK，未知键直接报错（不猜键码）。 */
    async key({ keys, repeat = 1 } = {}) {
      const vks = resolveKeySequence(keys)
      const times = assertInt(repeat, 'repeat', { min: 1, max: 20 })
      let last = { ok: true }
      for (let i = 0; i < times; i += 1) {
        last = await actor.call('key', [vks.join(',')])
        if (!last.ok) return last
      }
      return last
    },

    /**
     * 键入文本。
     *
     * ≤30 字且无换行走 `SendInput(KEYEVENTF_UNICODE)`（最快、不污染剪贴板）；
     * 更长的走「写剪贴板 → Ctrl+V → 恢复」，避免逐字符注入几千次。
     */
    async type({ text, submit = false } = {}) {
      const value = String(text ?? '')
      if (!value) throw new Error('text 不能为空')
      if (value.length > MAX_TEXT_CHARS) throw new Error('text 过长')
      const usePaste = value.length > 30 || value.includes('\n')
      const result = usePaste
        ? await pasteViaClipboard(value)
        : await actor.call('type', [value, submit ? '1' : '0'])
      if (!result.ok) return result
      return { ...result, method: usePaste ? 'paste' : 'unicode', chars: value.length }
    },

    async scroll({ dy, x, y } = {}) {
      const delta = assertInt(dy, 'dy', { min: -6000, max: 6000 })
      if (x != null && y != null) {
        await actor.call('move', [assertInt(x, 'x'), assertInt(y, 'y')])
      }
      return actor.call('scroll', [delta])
    },

    /** 系统能力：音量/静音/媒体/勿扰（都不碰界面，比点按钮快且准）。 */
    async system({ action, value } = {}) {
      const allowed = [
        'volume_set', 'volume_mute', 'media_play', 'media_pause',
        'media_next', 'media_prev', 'dnd_on', 'dnd_off',
      ]
      if (!allowed.includes(action)) throw new Error(`不支持的 action：${action}`)
      if (action === 'volume_set') {
        const percent = assertInt(value, 'value', { min: 0, max: 100 })
        return actor.call('volume', [action, percent])
      }
      return actor.call('volume', [action])
    },
  }
}
