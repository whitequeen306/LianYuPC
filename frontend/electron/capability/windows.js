/**
 * 窗口驱动：列窗口 / 查前台 / 切前台。
 *
 * 只暴露「事实」与「切前台」两类操作，不做任何判断（策略在上层）。
 * 返回的 `windowTitle` 会作为**不可信文本**流进模型上下文，因此这里只截断长度，
 * 转义与 `<untrusted_screen>` 包裹由 Python 侧统一负责。
 */

const MAX_TITLE_CHARS = 200

function normalizeWindow(raw) {
  if (!raw || typeof raw !== 'object') return null
  return {
    hwnd: Number(raw.hwnd) || 0,
    title: String(raw.title || '').slice(0, MAX_TITLE_CHARS),
    process: String(raw.process || '').slice(0, 120),
    x: Number(raw.x) || 0,
    y: Number(raw.y) || 0,
    w: Number(raw.w) || 0,
    h: Number(raw.h) || 0,
    fg: raw.fg === true,
  }
}

/**
 * 元素归一化：只保留「模型选编号」需要的字段。
 *
 * 为什么坐标也要给模型看：它需要判断「这个编号在哪个区域」来决定先看哪一块；
 * 但**点击永远用编号**（矩形映射留在 Electron，随时可重收割，坐标不会过期）。
 */
function normalizeElement(raw) {
  if (!raw || typeof raw !== 'object') return null
  const id = Number(raw.id)
  if (!Number.isFinite(id)) return null
  const w = Number(raw.w) || 0
  const h = Number(raw.h) || 0
  if (w < 6 || h < 6) return null
  return {
    id,
    label: String(raw.label || '').slice(0, 80),
    role: String(raw.role || '').slice(0, 40),
    x: Number(raw.x) || 0,
    y: Number(raw.y) || 0,
    w,
    h,
  }
}

export function createWindowDriver({ actor } = {}) {
  if (!actor) throw new Error('window driver: actor is required')

  return {
    async foreground() {
      const res = await actor.call('foreground')
      if (!res.ok) return res
      return { ...res, window: normalizeWindow(res) }
    },

    async list() {
      const res = await actor.call('windows')
      if (!res.ok) return res
      const windows = Array.isArray(res.windows)
        ? res.windows.map(normalizeWindow).filter(Boolean)
        : []
      return { ...res, windows }
    },

    /**
     * 收割当前窗口的可操作元素（UIA 扁平遍历，深度/数量受限）。
     *
     * 返回空列表是**正常结果**（Chromium 自绘、游戏、远程桌面都可能没有可访问性树），
     * 由上层决定「降级到截图」而不是当作错误。
     */
    async readElements() {
      const res = await actor.call('elements', [], 12000)
      if (!res.ok) return res
      const elements = Array.isArray(res.elements)
        ? res.elements.map(normalizeElement).filter(Boolean)
        : []
      return { ...res, elements }
    },

    /**
     * 切前台。`title`/`process` 支持包含匹配（大小写不敏感，由 actor 侧实现）。
     * 同一标题有多个窗口时优先精确匹配，否则取面积最大的那个。
     */
    async focus({ title = '', process = '', maximized = false } = {}) {
      const needle = String(title || '').slice(0, MAX_TITLE_CHARS)
      const proc = String(process || '').slice(0, 120)
      if (!needle && !proc) throw new Error('title 与 process 至少要给一个')
      const res = await actor.call('focus', [needle, proc, maximized ? '1' : '0'])
      if (!res.ok) return res
      return { ...res, window: normalizeWindow(res), matched: needle ? 'fuzzy' : 'process' }
    },
  }
}
