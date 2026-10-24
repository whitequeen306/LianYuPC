import { describe, it, expect, vi } from 'vitest'
import {
  escapeHtml,
  safeAvatarUrl,
  sanitizeActor,
  buildBannerHtml,
  createControlBanner,
  BANNER_HEIGHT,
} from '../capability/controlBanner.js'

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

describe('createControlBanner', () => {
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
    const banner = createControlBanner({
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

describe('controlBanner live caption', () => {
  const makeHarness = () => {
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
        webContents: { executeJavaScript: vi.fn(async () => true) },
      }
      windows.push(win)
      return win
    })
    const banner = createControlBanner({
      BrowserWindow,
      screen: { getPrimaryDisplay: () => ({ bounds: { x: 0, y: 0, width: 1920, height: 1080 } }) },
      globalShortcut: { register: () => true, unregister: vi.fn() },
    })
    return { banner, windows }
  }

  it('update() 不重建窗口，只改文案并补 Esc 提示', async () => {
    vi.useFakeTimers()
    const { banner, windows } = makeHarness()
    banner.show({ name: '甘雨', caption: '甘雨正在操控你的电脑' })
    vi.advanceTimersByTime(200)
    expect(windows).toHaveLength(1)

    expect(banner.update('正在打开网易云音乐')).toBe(true)
    const js = windows[0].webContents.executeJavaScript.mock.calls[0][0]
    expect(js).toContain('正在打开网易云音乐')
    expect(js).toContain('Esc 取消')
    // 同一个窗口，没有再次 loadURL
    expect(windows[0].loadURL).toHaveBeenCalledTimes(1)

    banner.dispose()
    vi.useRealTimers()
  })

  it('同一角色再次 show() 只换文案，不重载页面（避免闪一帧）', async () => {
    vi.useFakeTimers()
    const { banner, windows } = makeHarness()
    const actor = { name: '甘雨', caption: '甘雨正在操控你的电脑' }
    banner.show(actor)
    vi.advanceTimersByTime(200)
    // 让 loadURL().then() 里的 showInactive 真正执行，窗口才处于可见态
    await Promise.resolve()
    await Promise.resolve()
    const firstLoads = windows[0].loadURL.mock.calls.length

    banner.show({ ...actor, caption: '甘雨正在打开网易云音乐' })
    vi.advanceTimersByTime(200)
    expect(windows).toHaveLength(1)
    expect(windows[0].loadURL).toHaveBeenCalledTimes(firstLoads)
    expect(windows[0].webContents.executeJavaScript).toHaveBeenCalled()

    banner.dispose()
    vi.useRealTimers()
  })

  it('窗口还没起来时 update() 是安全 no-op', () => {
    const { banner } = makeHarness()
    expect(banner.update('正在点一下')).toBe(false)
    expect(() => banner.hide()).not.toThrow()
  })
})
