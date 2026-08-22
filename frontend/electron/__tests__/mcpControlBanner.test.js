import { describe, it, expect, vi } from 'vitest'
import {
  escapeHtml,
  safeAvatarUrl,
  sanitizeActor,
  buildBannerHtml,
  createMcpControlBanner,
  BANNER_HEIGHT,
} from '../mcp/mcpControlBanner.js'

describe('mcpControlBanner html', () => {
  it('escapes a hostile character name in the caption', () => {
    const html = buildBannerHtml({
      name: '<img src=x onerror=alert(1)>',
      caption: '<script>alert(1)</script>琉璃正在操控你的电脑，按 Esc 取消',
      avatarUrl: 'javascript:alert(1)',
      theme: 'dark',
    })
    expect(html).not.toContain('<script>')
    expect(html).not.toContain('javascript:')
    expect(html).toContain('&lt;script&gt;')
    expect(html).toContain('<kbd>Esc</kbd>')
    expect(html).toContain('--ly-banner-bg')
    expect(html).toContain('#f4a6b5')
    expect(html).toContain('#ffffff')
  })

  it('renders a safe http avatar on the pink banner', () => {
    const html = buildBannerHtml({
      name: '琉璃',
      caption: '琉璃正在操控你的电脑，按 Esc 取消',
      avatarUrl: 'https://example.com/a.png',
      theme: 'light',
    })
    expect(html).toContain('src="https://example.com/a.png"')
    expect(html).toContain('#f4a6b5')
    expect(html).toContain('#ffffff')
  })

  it('rejects non-image data urls', () => {
    expect(safeAvatarUrl('data:text/html,hi')).toBe('')
    expect(safeAvatarUrl('data:image/png;base64,abc')).toBe('data:image/png;base64,abc')
    expect(escapeHtml('<x>')).toBe('&lt;x&gt;')
  })

  it('fills a default caption when missing', () => {
    const actor = sanitizeActor({ name: '爱莉' })
    expect(actor.caption).toContain('爱莉')
    expect(actor.caption).toMatch(/Esc/)
  })
})

describe('createMcpControlBanner', () => {
  it('registers Escape and fires onCancel, then unregisters on hide', () => {
    vi.useFakeTimers()
    const registered = []
    const windows = []
    const BrowserWindow = vi.fn(function ctor(opts) {
      const win = {
        opts,
        _visible: false,
        isDestroyed: () => false,
        isVisible: () => win._visible,
        setIgnoreMouseEvents: vi.fn(),
        setAlwaysOnTop: vi.fn(),
        setVisibleOnAllWorkspaces: vi.fn(),
        setBounds: vi.fn(),
        hide: vi.fn(() => { win._visible = false }),
        showInactive: vi.fn(() => { win._visible = true }),
        show: vi.fn(() => { win._visible = true }),
        loadURL: vi.fn(async () => {}),
        destroy: vi.fn(),
        on: vi.fn(),
      }
      windows.push(win)
      return win
    })
    const globalShortcut = {
      register: vi.fn((key, cb) => {
        registered.push({ key, cb })
        return true
      }),
      unregister: vi.fn(),
    }
    const onCancel = vi.fn()
    const banner = createMcpControlBanner({
      BrowserWindow,
      screen: { getPrimaryDisplay: () => ({ bounds: { x: 0, y: 0, width: 1920, height: 1080 } }) },
      globalShortcut,
      onCancel,
    })

    banner.show({ name: '琉璃', caption: '琉璃正在操控你的电脑，按 Esc 取消' })
    expect(globalShortcut.register).toHaveBeenCalledWith('Escape', expect.any(Function))
    registered[0].cb()
    expect(onCancel).toHaveBeenCalledTimes(1)

    vi.advanceTimersByTime(200)
    expect(windows[0].loadURL).toHaveBeenCalled()
    expect(windows[0].opts.height).toBe(BANNER_HEIGHT)
    expect(windows[0].opts.y).toBe(0)

    banner.hide()
    expect(globalShortcut.unregister).toHaveBeenCalledWith('Escape')
    banner.dispose()
    vi.useRealTimers()
  })
})
