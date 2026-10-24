/**
 * 电脑操控指示条：屏幕最顶端 always-on-top 窗口，展示角色头像 + 文案，Esc 取消。
 *
 * 视觉令牌来自 DESIGN.md：primary（#f4a6b5）+ on-primary-light（#ffffff）。
 * 窗口 click-through + showInactive，避免抢走正在被操控的前台焦点。
 */

export const BANNER_HEIGHT = 44
export const BANNER_SHOW_DELAY_MS = 120
/** 一步做完后不立刻消失：多步任务中间会有几百毫秒空档，闪一下反而更吓人。 */
export const BANNER_IDLE_HIDE_MS = 4000

/** DESIGN.md primary + on-primary-light；独立窗口无法继承应用 CSS 变量，这里内联同一套令牌。 */
const BANNER_TOKENS = {
  bg: '#f4a6b5',
  text: '#ffffff',
  muted: 'rgba(255, 255, 255, 0.86)',
  avatarBg: 'rgba(255, 255, 255, 0.28)',
  avatarText: '#ffffff',
  border: 'rgba(255, 255, 255, 0.32)',
  kbdBg: 'rgba(255, 255, 255, 0.22)',
  kbdText: '#ffffff',
}

export function escapeHtml(value) {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

export function safeAvatarUrl(url) {
  if (typeof url !== 'string') return ''
  const trimmed = url.trim()
  if (!trimmed) return ''
  if (trimmed.startsWith('https://') || trimmed.startsWith('http://')) return trimmed
  if (trimmed.startsWith('data:image/')) return trimmed
  return ''
}

export function sanitizeActor(raw) {
  const src = raw && typeof raw === 'object' ? raw : {}
  const name = String(src.name || '角色').replace(/\s+/g, ' ').trim().slice(0, 32) || '角色'
  const caption = String(src.caption || `${name}正在操控你的电脑，按 Esc 取消`).replace(/\s+/g, ' ').trim().slice(0, 96)
  return {
    name,
    caption,
    avatarUrl: safeAvatarUrl(src.avatarUrl),
    theme: src.theme === 'light' ? 'light' : 'dark',
  }
}

export function buildBannerHtml(actorInput) {
  const actor = sanitizeActor(actorInput)
  const tokens = BANNER_TOKENS
  const initial = escapeHtml(actor.name.slice(0, 1) || '·')
  const caption = escapeHtml(actor.caption).replace(/\bEsc\b/g, '<kbd>Esc</kbd>')
  const avatar = actor.avatarUrl
    ? `<img class="avatar-img" src="${escapeHtml(actor.avatarUrl)}" alt="">`
    : `<span class="avatar-fallback">${initial}</span>`

  return `<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src http: https: data:; style-src 'unsafe-inline';">
<style>
  :root {
    --ly-accent: #f4a6b5;
    --ly-banner-bg: ${tokens.bg};
    --ly-banner-text: ${tokens.text};
    --ly-banner-muted: ${tokens.muted};
    --ly-banner-avatar-bg: ${tokens.avatarBg};
    --ly-banner-avatar-text: ${tokens.avatarText};
    --ly-banner-border: ${tokens.border};
    --ly-banner-kbd-bg: ${tokens.kbdBg};
    --ly-banner-kbd-text: ${tokens.kbdText};
    --ly-radius-full: 9999px;
    --ly-ease: cubic-bezier(0.23, 1, 0.32, 1);
  }
  html, body {
    margin: 0;
    height: 100%;
    overflow: hidden;
    background: transparent;
    font-family: 'PingFang SC', 'Microsoft YaHei', 'Hiragino Sans GB', system-ui, sans-serif;
    user-select: none;
    pointer-events: none;
  }
  .bar {
    display: flex;
    align-items: center;
    justify-content: center;
    height: 100%;
    background: var(--ly-banner-bg);
    border-bottom: 1px solid var(--ly-banner-border);
    backdrop-filter: blur(14px);
    -webkit-backdrop-filter: blur(14px);
    animation: ly-drop 0.24s var(--ly-ease);
  }
  .inner {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    max-width: min(720px, calc(100vw - 2rem));
    padding: 0 1rem;
    color: var(--ly-banner-text);
    font-size: 0.875rem;
    line-height: 1.5;
    font-weight: 500;
  }
  .avatar {
    flex-shrink: 0;
    width: 28px;
    height: 28px;
    border-radius: var(--ly-radius-full);
    overflow: hidden;
    background: var(--ly-banner-avatar-bg);
    color: var(--ly-banner-avatar-text);
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 0.75rem;
    font-weight: 600;
  }
  .avatar-img { width: 100%; height: 100%; object-fit: cover; display: block; }
  kbd {
    display: inline-block;
    margin: 0 0.15em;
    padding: 0 0.4em;
    border-radius: 8px;
    border: 1px solid var(--ly-banner-border);
    background: var(--ly-banner-kbd-bg);
    color: var(--ly-banner-kbd-text);
    font-family: inherit;
    font-size: 0.75rem;
    font-weight: 600;
    line-height: 1.5;
  }
  @keyframes ly-drop {
    from { transform: translateY(-100%); opacity: 0; }
    to { transform: translateY(0); opacity: 1; }
  }
</style>
</head>
<body>
  <div class="bar" role="status" aria-live="polite">
    <div class="inner">
      <div class="avatar" aria-hidden="true">${avatar}</div>
      <span id="ly-caption">${caption}</span>
    </div>
  </div>
</body>
</html>`
}

export function createControlBanner({
  BrowserWindow,
  screen,
  globalShortcut,
  log = () => {},
  onCancel,
} = {}) {
  let win = null
  let showTimer = null
  let escapeArmed = false
  let displayListener = null
  /** 当前展示的身份（名字+头像）：同一角色连续动作时只改文案，不重载页面（避免闪一帧） */
  let currentIdentity = ''
  /** 记住名字，update() 时要把「XXX正在操控你的电脑」保住，只换后面那半句 */
  let currentName = '角色'

  function primaryBounds() {
    const display = screen?.getPrimaryDisplay?.() || screen?.getDisplayNearestPoint?.({ x: 0, y: 0 })
    const b = display?.bounds || { x: 0, y: 0, width: 1280, height: 44 }
    return { x: b.x, y: b.y, width: b.width, height: BANNER_HEIGHT }
  }

  function armEscape() {
    if (escapeArmed || !globalShortcut?.register) return
    try {
      const ok = globalShortcut.register('Escape', () => {
        log('esc pressed, cancelling control')
        try { onCancel?.() } catch (e) { log(`onCancel failed: ${e?.message || e}`) }
      })
      escapeArmed = !!ok
      if (!ok) log('failed to register Escape shortcut')
    } catch (e) {
      log(`Escape register failed: ${e?.message || e}`)
    }
  }

  function disarmEscape() {
    if (!escapeArmed) return
    escapeArmed = false
    try { globalShortcut.unregister?.('Escape') } catch { /* ignore */ }
  }

  function reposition() {
    if (!win || win.isDestroyed()) return
    try { win.setBounds(primaryBounds()) } catch { /* ignore */ }
  }

  function ensureWindow() {
    if (win && !win.isDestroyed()) return win
    const bounds = primaryBounds()
    win = new BrowserWindow({
      ...bounds,
      frame: false,
      transparent: true,
      backgroundColor: '#00000000',
      resizable: false,
      movable: false,
      minimizable: false,
      maximizable: false,
      fullscreenable: false,
      skipTaskbar: true,
      alwaysOnTop: true,
      focusable: false,
      hasShadow: false,
      thickFrame: false,
      show: false,
      webPreferences: {
        contextIsolation: true,
        sandbox: true,
        nodeIntegration: false,
      },
    })
    win.lianyuKind = 'mcpControlBanner'
    try { win.setIgnoreMouseEvents(true, { forward: true }) } catch { /* ignore */ }
    try { win.setAlwaysOnTop(true, 'screen-saver') } catch { /* ignore */ }
    try { win.setVisibleOnAllWorkspaces(true, { visibleOnFullScreen: true }) } catch { /* ignore */ }
    win.on('closed', () => { win = null })
    if (screen?.on && !displayListener) {
      displayListener = () => reposition()
      try { screen.on('display-metrics-changed', displayListener) } catch { displayListener = null }
    }
    return win
  }

  function hideWindow() {
    if (!win || win.isDestroyed()) return
    try { win.hide() } catch { /* ignore */ }
  }

  /**
   * 只换文案不重建窗口（多步任务里每步都会调一次）。
   * 传入的是「当前这一步在干什么」，最终展示为：
   *   `{角色名}正在操控你的电脑 · 正在打开网易云音乐 · Esc 取消`
   */
  function update(caption) {
    if (!win || win.isDestroyed()) return false
    const step = String(caption || '').replace(/\s+/g, ' ').trim().slice(0, 64)
    if (!step) return false
    // 已经是完整句子（调用方自己拼好了名字）就不再重复前缀
    const text = step.includes('正在操控')
      ? step
      : `${currentName}正在操控你的电脑 · ${step}`
    const full = /esc/i.test(text) ? text : `${text} · Esc 取消`
    const js = `(() => {
      const el = document.getElementById('ly-caption')
      if (!el) return false
      el.textContent = ${JSON.stringify(full)}
      return true
    })()`
    try {
      const pending = win.webContents.executeJavaScript(js)
      if (pending && typeof pending.catch === 'function') {
        pending.catch((e) => log(`banner update failed: ${e?.message || e}`))
      }
      return true
    } catch (e) {
      log(`banner update threw: ${e?.message || e}`)
      return false
    }
  }

  function show(actor) {
    armEscape()
    if (showTimer) {
      clearTimeout(showTimer)
      showTimer = null
    }
    const payload = sanitizeActor(actor)
    currentName = payload.name
    const identity = `${payload.name}|${payload.avatarUrl}`
    // 同一角色、窗口已在：只更新文案，避免整块重载造成闪烁
    if (identity === currentIdentity && win && !win.isDestroyed() && win.isVisible?.()) {
      update(payload.caption)
      return
    }
    showTimer = setTimeout(() => {
      showTimer = null
      const w = ensureWindow()
      reposition()
      const html = buildBannerHtml(payload)
      currentIdentity = identity
      w.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(html))
        .then(() => {
          if (w.isDestroyed()) return
          revealWindow(w)
        })
        .catch((e) => log(`banner load failed: ${e?.message || e}`))
    }, BANNER_SHOW_DELAY_MS)
  }

  /**
   * 让窗口真正出现在屏幕上。
   *
   * 真实踩到（Win11 + 2026-10-24 真机验证）：`focusable:false` 的窗口调
   * `showInactive()` 可能**什么都不做**——webContents 已加载、target 也存在，
   * 但屏幕上根本没有那条横幅。所以这里补一次可见性兜底：
   *   showInactive → 下一帧检查 isVisible() → 不可见就用 show() 硬唤。
   * 窗口本身 focusable=false，show() 也不会抢走正在被操控窗口的焦点。
   */
  function revealWindow(w) {
    try {
      // 重新设一次置顶与尺寸：某些 DPI/多屏场景下 show 之前设的 bounds 会被丢
      try { w.setAlwaysOnTop(true, 'screen-saver') } catch { /* ignore */ }
      reposition()
      if (typeof w.showInactive === 'function') w.showInactive()
      else w.show()
    } catch (e) {
      log(`banner showInactive threw: ${e?.message || e}`)
    }
    setTimeout(() => {
      if (!w || w.isDestroyed()) return
      let visible = false
      try { visible = w.isVisible?.() === true } catch { visible = false }
      if (visible) {
        log('banner visible')
        return
      }
      log('banner still hidden after showInactive, falling back to show()')
      try { w.show() } catch (e) { log(`banner show() failed: ${e?.message || e}`) }
    }, 150)
  }

  function hide() {
    if (showTimer) {
      clearTimeout(showTimer)
      showTimer = null
    }
    disarmEscape()
    currentIdentity = ''
    hideWindow()
  }

  function dispose() {
    hide()
    if (displayListener && screen?.off) {
      try { screen.off('display-metrics-changed', displayListener) } catch { /* ignore */ }
      displayListener = null
    }
    if (win && !win.isDestroyed()) {
      try { win.destroy() } catch { /* ignore */ }
    }
    win = null
  }

  return {
    show,
    update,
    hide,
    dispose,
    get isVisible() { return !!(win && !win.isDestroyed() && win.isVisible?.()) },
  }
}
