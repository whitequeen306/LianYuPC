import { describe, it, expect, vi } from 'vitest'

import { createCapabilityHost } from '../capability/capabilityHost.js'
import { createInputDriver } from '../capability/input.js'
import { createWindowDriver } from '../capability/windows.js'
import { buildActorCommand, resolveKeySequence } from '../capability/winActor.js'

function fakeActor(overrides = {}) {
  const calls = []
  return {
    calls,
    running: true,
    async call(op, args = []) {
      calls.push({ op, args })
      const handler = overrides[op]
      if (handler) return handler(args)
      return { ok: true }
    },
  }
}

function defaultActor() {
  return fakeActor({
    foreground: () => ({ ok: true, title: '网易云音乐', process: 'cloudmusic.exe', hwnd: 1, x: 0, y: 0, w: 1920, h: 1080 }),
    windows: () => ({
      ok: true,
      windows: [
        { hwnd: 1, title: '网易云音乐', process: 'cloudmusic.exe', fg: true, x: 0, y: 0, w: 1920, h: 1080 },
        { hwnd: 2, title: '记事本', process: 'notepad.exe', fg: false, x: 0, y: 0, w: 800, h: 600 },
      ],
    }),
    elements: () => ({
      ok: true,
      elements: [
        { id: 5, label: '播放', role: 'Button', x: 100, y: 200, w: 40, h: 20 },
        { id: 6, label: '下一首', role: 'Button', x: 160, y: 200, w: 40, h: 20 },
      ],
    }),
  })
}

function buildHost(overrides = {}) {
  const actor = overrides.actor || defaultActor()
  const host = createCapabilityHost({
    actor,
    capture: overrides.capture,
    windows: createWindowDriver({ actor }),
    input: createInputDriver({ actor, clipboard: overrides.clipboard }),
    clipboard: overrides.clipboard,
    files: overrides.files,
    launchApp: overrides.launchApp,
    requestConfirm: overrides.requestConfirm,
    readSettings: overrides.readSettings || (() => ({ enabled: true, strictConfirm: false })),
    loadPolicy: () => undefined,
    log: () => {},
    onAudit: overrides.onAudit,
  })
  return { host, actor }
}

const SCREEN_SHOT = {
  ok: true,
  mime: 'image/jpeg',
  b64: 'AAAA',
  w: 1024,
  h: 576,
  scaleFactor: 1.5,
  screen: { width: 1920, height: 1080, id: 1 },
  black: false,
}

const captureStub = (over = {}) => ({ capture: vi.fn(async () => ({ ...SCREEN_SHOT, ...over })) })

describe('capability host · 基本闸门', () => {
  it('refuses everything when the user has the capability switch off', async () => {
    const { host, actor } = buildHost({ readSettings: () => ({ enabled: false }) })
    const res = await host.exec({ tool: 'desk_window_list', args: {}, stepId: 's1' })
    expect(res.ok).toBe(false)
    expect(res.errorCode).toBe('disabled')
    expect(actor.calls.some((c) => c.op === 'windows')).toBe(false)
  })

  it('refuses on a sensitive foreground window without touching input', async () => {
    const actor = fakeActor({ foreground: () => ({ ok: true, title: 'KeePass', process: 'KeePassXC.exe' }) })
    const { host } = buildHost({ actor })
    const res = await host.exec({ tool: 'desk_click', args: { label: '播放', x: 10, y: 20 }, stepId: 's2' })
    expect(res.ok).toBe(false)
    expect(res.errorCode).toBe('sensitive_window')
    expect(actor.calls.some((c) => c.op === 'click')).toBe(false)
  })

  it('rejects unknown tools', async () => {
    const { host } = buildHost()
    const res = await host.exec({ tool: 'computer_task', args: {} })
    expect(res.errorCode).toBe('unknown_tool')
  })
})

describe('capability host · 危险动作确认', () => {
  it('does not click when the user declines', async () => {
    const { host, actor } = buildHost({ requestConfirm: async () => false })
    const res = await host.exec({
      tool: 'desk_click', args: { label: '删除账号', x: 10, y: 20, evidence: 'user_request' }, stepId: 's3',
    })
    expect(res.ok).toBe(false)
    expect(res.errorCode).toBe('user_denied')
    expect(actor.calls.some((c) => c.op === 'click')).toBe(false)
  })

  it('clicks after the user approves, and records the confirmation in policy', async () => {
    const confirm = vi.fn(async () => true)
    const { host, actor } = buildHost({ requestConfirm: confirm })
    const res = await host.exec({
      tool: 'desk_click', args: { label: '删除账号', x: 10, y: 20, evidence: 'user_request' }, stepId: 's4',
    })
    expect(res.ok).toBe(true)
    expect(confirm).toHaveBeenCalledTimes(1)
    expect(confirm.mock.calls[0][0].description).toBe('点击「删除账号」')
    expect(res.policy.confirmed).toBe(true)
    expect(actor.calls.some((c) => c.op === 'click')).toBe(true)
  })

  it('never asks for read-only actions', async () => {
    const confirm = vi.fn(async () => true)
    const { host } = buildHost({ requestConfirm: confirm, capture: captureStub() })
    await host.exec({ tool: 'desk_screen_read', args: { evidence: 'user_request' }, stepId: 's5' })
    expect(confirm).not.toHaveBeenCalled()
  })

  it('refuses R3 driven by screen content even before asking', async () => {
    const confirm = vi.fn(async () => true)
    const { host } = buildHost({ requestConfirm: confirm })
    const res = await host.exec({
      tool: 'desk_click', args: { label: '转账', x: 1, y: 2, evidence: 'screen_content' }, stepId: 's6',
    })
    expect(res.ok).toBe(false)
    expect(res.errorCode).toBe('screen_content_high_risk')
    expect(confirm).not.toHaveBeenCalled()
  })
})

describe('capability host · 幂等', () => {
  it('replays the same result for a repeated stepId and only acts once', async () => {
    const { host, actor } = buildHost()
    const req = { tool: 'desk_click', args: { label: '播放', x: 5, y: 6, evidence: 'user_request' }, stepId: 'dup-1' }
    const first = await host.exec(req)
    const second = await host.exec(req)
    expect(first.ok).toBe(true)
    expect(second).toBe(first)
    expect(actor.calls.filter((c) => c.op === 'click')).toHaveLength(1)
  })
})

describe('capability host · 感知与元素', () => {
  it('screen_read returns a jpeg frame plus screen facts', async () => {
    const capture = captureStub()
    const { host } = buildHost({ capture })
    const res = await host.exec({ tool: 'desk_screen_read', args: { evidence: 'user_request' }, stepId: 's7' })
    expect(res.ok).toBe(true)
    expect(res.images).toHaveLength(1)
    expect(res.images[0]).toMatchObject({ mime: 'image/jpeg', w: 1024, h: 576, scaleFactor: 1.5 })
    expect(res.data.app).toBe('cloudmusic.exe')
    expect(res.data.windowTitle).toBe('网易云音乐')
    expect(res.text).toContain('物理像素')
  })

  it('reports a black frame as capture_black', async () => {
    const { host } = buildHost({ capture: captureStub({ black: true }) })
    const res = await host.exec({ tool: 'desk_screen_read', args: {}, stepId: 's8' })
    expect(res.ok).toBe(false)
    expect(res.errorCode).toBe('capture_black')
  })

  it('clicks the centre of a registered element', async () => {
    const { host, actor } = buildHost()
    host.registerElements('sess-1', [{ id: 7, label: '播放', role: 'Button', x: 100, y: 200, w: 40, h: 20 }])
    const res = await host.exec({
      tool: 'desk_click', args: { element_id: 7, evidence: 'user_request' }, stepId: 's9', sessionId: 'sess-1',
    })
    expect(res.ok).toBe(true)
    const click = actor.calls.find((c) => c.op === 'click')
    expect(click.args).toEqual([120, 210, 'left', 1])
    expect(res.text).toContain('播放')
  })

  it('tells the model to re-read when the element id is unknown', async () => {
    const { host } = buildHost()
    const res = await host.exec({
      tool: 'desk_click', args: { element_id: 99, evidence: 'user_request' }, stepId: 's10', sessionId: 'nope',
    })
    expect(res.errorCode).toBe('element_not_found')
    expect(res.text).toContain('desk_screen_read')
  })

  it('harvests elements without taking a screenshot (privacy mode)', async () => {
    const { host } = buildHost()
    const res = await host.exec({ tool: 'desk_read_elements', args: {}, stepId: 'e1', sessionId: 'sess-2' })
    expect(res.ok).toBe(true)
    expect(res.images).toHaveLength(0)
    expect(res.data.elements).toHaveLength(2)
    expect(res.text).toContain('可操作元素')
  })

  it('can click by element id right after harvesting', async () => {
    const { host, actor } = buildHost()
    await host.exec({ tool: 'desk_read_elements', args: {}, stepId: 'e2', sessionId: 'sess-3' })
    const res = await host.exec({
      tool: 'desk_click', args: { element_id: 5, evidence: 'user_request' }, stepId: 'e3', sessionId: 'sess-3',
    })
    expect(res.ok).toBe(true)
    const click = actor.calls.find((c) => c.op === 'click')
    expect(click.args.slice(0, 2)).toEqual([120, 210])
  })

  it('maps a missing accessibility tree to unsupported (fall back to screenshot)', async () => {
    const actor = defaultActor()
    actor.call = async (op, args = []) => {
      if (op === 'elements') return { ok: false, error: 'uia_unavailable' }
      if (op === 'foreground') return { ok: true, title: '游戏', process: 'game.exe' }
      return { ok: true }
    }
    const { host } = buildHost({ actor })
    const res = await host.exec({ tool: 'desk_read_elements', args: {}, stepId: 'e4' })
    expect(res.ok).toBe(false)
    expect(res.errorCode).toBe('unsupported')
  })
})

describe('capability host · 窗口与超时', () => {
  it('lists windows and focuses by title', async () => {
    const { host, actor } = buildHost()
    const list = await host.exec({ tool: 'desk_window_list', args: {}, stepId: 's11' })
    expect(list.ok).toBe(true)
    expect(list.data.windows).toHaveLength(2)
    expect(list.text).toContain('网易云音乐')

    const focus = await host.exec({ tool: 'desk_window_focus', args: { title: '记事本' }, stepId: 's12' })
    expect(focus.ok).toBe(true)
    expect(actor.calls.some((c) => c.op === 'focus')).toBe(true)
  })

  it('returns step_timeout instead of hanging when the actor never answers', async () => {
    const hanging = { running: true, call: () => new Promise(() => {}) }
    const host = createCapabilityHost({
      actor: hanging,
      windows: { foreground: async () => ({ ok: false }), list: async () => ({ ok: true, windows: [] }) },
      input: { click: () => new Promise(() => {}), key: async () => ({ ok: true }), type: async () => ({ ok: true }), scroll: async () => ({ ok: true }), system: async () => ({ ok: true }) },
      readSettings: () => ({ enabled: true }),
      loadPolicy: () => undefined,
    })
    const res = await host.exec({ tool: 'desk_click', args: { label: '播放', x: 1, y: 2 }, stepId: 's13', timeoutMs: 50 })
    expect(res.ok).toBe(false)
    expect(res.errorCode).toBe('step_timeout')
  })

  it('records an audit entry for every attempt', async () => {
    const audited = []
    const { host } = buildHost({ onAudit: (e) => audited.push(e) })
    await host.exec({ tool: 'desk_window_list', args: {}, stepId: 'a1' })
    await host.exec({ tool: 'desk_nope', args: {}, stepId: 'a2' })
    expect(audited).toHaveLength(2)
    expect(audited[0]).toMatchObject({ tool: 'desk_window_list', ok: true })
    expect(audited[1]).toMatchObject({ errorCode: 'unknown_tool' })
    expect(host.status().auditSize).toBe(2)
  })
})

describe('capability host · 文件能力', () => {
  const files = {
    reveal: vi.fn(async () => ({ ok: true, text: '已在资源管理器里定位 C:\\a\\x.pdf', data: {} })),
    open: vi.fn(async () => ({ ok: true, text: '已打开 C:\\a\\x.pdf', data: {} })),
  }

  it('delegates reveal without asking for confirmation', async () => {
    const confirm = vi.fn(async () => true)
    const { host } = buildHost({ files, requestConfirm: confirm })
    const res = await host.exec({ tool: 'desk_file_reveal', args: { path: 'C:\\a\\x.pdf' }, stepId: 'f1' })
    expect(res.ok).toBe(true)
    expect(files.reveal).toHaveBeenCalledWith({ path: 'C:\\a\\x.pdf' })
    expect(confirm).not.toHaveBeenCalled()
  })

  it('asks before opening a file (R3) and respects a refusal', async () => {
    const confirm = vi.fn(async () => false)
    const { host } = buildHost({ files, requestConfirm: confirm })
    const res = await host.exec({
      tool: 'desk_file_open', args: { path: 'C:\\a\\x.pdf', evidence: 'user_request' },
      danger: 'R3', stepId: 'f2',
    })
    expect(res.ok).toBe(false)
    expect(res.errorCode).toBe('user_denied')
    expect(files.open).not.toHaveBeenCalled()
  })

  it('opens after approval', async () => {
    const confirm = vi.fn(async () => true)
    const { host } = buildHost({ files, requestConfirm: confirm })
    const res = await host.exec({
      tool: 'desk_file_open', args: { path: 'C:\\a\\x.pdf', evidence: 'user_request' },
      danger: 'R3', stepId: 'f3',
    })
    expect(res.ok).toBe(true)
    expect(files.open).toHaveBeenCalled()
  })
})

describe('winActor 协议', () => {
  it('rejects unknown ops and base64-encodes args', () => {
    expect(() => buildActorCommand('1', 'rm_rf', [])).toThrow(/不支持的 op/)
    const line = buildActorCommand('9', 'type', ['你好\n'])
    expect(line.startsWith('9\u0001type\u0001')).toBe(true)
    const b64 = line.split('\u0001')[2]
    expect(Buffer.from(b64, 'base64').toString('utf8')).toBe('你好\n')
  })

  it('whitelists keys instead of guessing virtual-key codes', () => {
    expect(resolveKeySequence(['ctrl', 's'])).toEqual([0x11, 0x53])
    expect(resolveKeySequence(['enter'])).toEqual([0x0d])
    expect(() => resolveKeySequence(['hyper'])).toThrow(/不支持的按键/)
    expect(() => resolveKeySequence([])).toThrow(/不能为空/)
  })
})
