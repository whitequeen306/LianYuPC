import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import os from 'os'
import path from 'path'
import fs from 'fs'

// 与 napcatDownloader 测试同构：用 hoisted holder 让 electron mock 工厂与测试体
// 共享同一 userData 路径（vi.mock 工厂被提升到顶部，无法直接闭包普通 const）。
const { state } = vi.hoisted(() => ({ state: { userData: '' } }))
vi.mock('electron', () => ({ app: { getPath: () => state.userData } }))

import {
  normalizeQqBridgeSettings,
  readQqBridgeSettings,
  writeQqBridgeSettings,
  isAllowedByBinding,
} from '../qqBridgeSettings.js'

let tmp
beforeEach(() => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'lianyu-qq-settings-'))
  state.userData = tmp
})
afterEach(() => {
  fs.rmSync(tmp, { recursive: true, force: true })
})

describe('normalizeQqBridgeSettings', () => {
  it('空输入返回完整默认值', () => {
    const s = normalizeQqBridgeSettings({})
    expect(s.enabled).toBe(false)
    expect(s.napcat.wsUrl).toBe('ws://127.0.0.1:3001')
    expect(s.napcat.connectTimeoutMs).toBe(10000)
    expect(s.binding.provider).toBe('platform')
    expect(s.binding.allowUsers).toEqual([])
    expect(s.reply.fallbackText).toMatch(/稍后再试/)
    expect(s.hosting.mode).toBe('manual')
    expect(s.hosting.consented).toBe(false)
    expect(s.hosting.wsPort).toBe(3001)
    expect(s.hosting.webuiPort).toBe(6099)
    expect(s.hosting.napcatVersion).toBe('')
  })

  it('null/undefined 视作空对象', () => {
    expect(normalizeQqBridgeSettings(null).hosting.mode).toBe('manual')
    expect(normalizeQqBridgeSettings(undefined).enabled).toBe(false)
  })

  it('hosting.mode 仅认 "auto"，其余一律降为 manual（大小写敏感）', () => {
    expect(normalizeQqBridgeSettings({ hosting: { mode: 'auto' } }).hosting.mode).toBe('auto')
    expect(normalizeQqBridgeSettings({ hosting: { mode: 'AUTO' } }).hosting.mode).toBe('manual')
    expect(normalizeQqBridgeSettings({ hosting: { mode: 'automatic' } }).hosting.mode).toBe('manual')
    expect(normalizeQqBridgeSettings({ hosting: { mode: 'xxx' } }).hosting.mode).toBe('manual')
  })

  it('QQ号/版本/会话ID 字符串化并 trim', () => {
    const s = normalizeQqBridgeSettings({
      binding: { conversationId: '  conv-1  ' },
      hosting: { qqUserId: 12345, napcatVersion: '  v4.20.0  ' },
    })
    expect(s.binding.conversationId).toBe('conv-1')
    expect(s.hosting.qqUserId).toBe('12345')
    expect(s.hosting.napcatVersion).toBe('v4.20.0')
  })

  it('端口数值化，非法（NaN）则回默认', () => {
    const s = normalizeQqBridgeSettings({ hosting: { wsPort: '4001', webuiPort: 'abc' } })
    expect(s.hosting.wsPort).toBe(4001)
    expect(s.hosting.webuiPort).toBe(6099)
  })

  it('令牌非字符串则置空（防数字/对象污染配置）', () => {
    const s = normalizeQqBridgeSettings({ hosting: { wsToken: 123, webuiToken: { x: 1 } } })
    expect(s.hosting.wsToken).toBe('')
    expect(s.hosting.webuiToken).toBe('')
  })

  it('consented 仅严格 true 才为 true', () => {
    expect(normalizeQqBridgeSettings({ hosting: { consented: true } }).hosting.consented).toBe(true)
    expect(normalizeQqBridgeSettings({ hosting: { consented: 'true' } }).hosting.consented).toBe(false)
    expect(normalizeQqBridgeSettings({ hosting: { consented: 1 } }).hosting.consented).toBe(false)
  })

  it('allowUsers/allowGroups 归一为非空字符串数组（数字转换、空白剔除、非数组→空）', () => {
    const s = normalizeQqBridgeSettings({
      binding: { allowUsers: [123, '  456  ', '   ', 789], allowGroups: 'not-an-array' },
    })
    expect(s.binding.allowUsers).toEqual(['123', '456', '789'])
    expect(s.binding.allowGroups).toEqual([])
  })

  it('binding.allowMode 仅认 "open"，其余一律降为 allowlist（默认拒绝，issue #11）', () => {
    expect(normalizeQqBridgeSettings({ binding: { allowMode: 'open' } }).binding.allowMode).toBe('open')
    expect(normalizeQqBridgeSettings({ binding: { allowMode: 'OPEN' } }).binding.allowMode).toBe('allowlist')
    expect(normalizeQqBridgeSettings({ binding: { allowMode: 'all' } }).binding.allowMode).toBe('allowlist')
    expect(normalizeQqBridgeSettings({}).binding.allowMode).toBe('allowlist')
  })

  it('enabled 仅严格 true 才为 true', () => {
    expect(normalizeQqBridgeSettings({ enabled: true }).enabled).toBe(true)
    expect(normalizeQqBridgeSettings({ enabled: 'true' }).enabled).toBe(false)
    expect(normalizeQqBridgeSettings({ enabled: 1 }).enabled).toBe(false)
  })

  it('未出现的子键由默认值补齐（合并而非整体替换）', () => {
    const s = normalizeQqBridgeSettings({ napcat: { wsUrl: 'ws://10.0.0.1:5000' } })
    // 只改 wsUrl，其余 napcat 字段保留默认
    expect(s.napcat.wsUrl).toBe('ws://10.0.0.1:5000')
    expect(s.napcat.connectTimeoutMs).toBe(10000)
    // reconnectMaxMs 默认已下调到 6000（≥30000 会被迁移逻辑转回 6000，见 normalizeQqBridgeSettings）
    expect(s.napcat.reconnectMaxMs).toBe(6000)
  })

  it('reply.segmentDelayMs 允许 0（不延迟），不再被 || 误回落 500', () => {
    // 旧实现 Number(0) || 500 = 500，用户设 0 保存后变回 500 → "回复设置无法修改"
    expect(normalizeQqBridgeSettings({ reply: { segmentDelayMs: 0 } }).reply.segmentDelayMs).toBe(0)
    expect(normalizeQqBridgeSettings({ reply: { segmentDelayMs: 800 } }).reply.segmentDelayMs).toBe(800)
    // 缺失/非有限数/负数回落默认 500
    expect(normalizeQqBridgeSettings({ reply: { segmentDelayMs: undefined } }).reply.segmentDelayMs).toBe(500)
    expect(normalizeQqBridgeSettings({ reply: { segmentDelayMs: 'abc' } }).reply.segmentDelayMs).toBe(500)
    expect(normalizeQqBridgeSettings({ reply: { segmentDelayMs: -10 } }).reply.segmentDelayMs).toBe(500)
  })

  it('reply.appendCharacterSuffix 默认 true，仅显式 false 才关', () => {
    expect(normalizeQqBridgeSettings({}).reply.appendCharacterSuffix).toBe(true)
    expect(normalizeQqBridgeSettings({ reply: { appendCharacterSuffix: false } }).reply.appendCharacterSuffix).toBe(false)
    // 非布尔（字符串/数字等）一律视为开，避免脏数据误关尾缀
    expect(normalizeQqBridgeSettings({ reply: { appendCharacterSuffix: 'false' } }).reply.appendCharacterSuffix).toBe(true)
    expect(normalizeQqBridgeSettings({ reply: { appendCharacterSuffix: 0 } }).reply.appendCharacterSuffix).toBe(true)
  })
})

describe('writeQqBridgeSettings', () => {
  it('hosting 深合并：部分 hosting 写入不覆盖既有令牌/端口', () => {
    writeQqBridgeSettings({ hosting: { wsToken: 'secret-ws', webuiToken: 'secret-webui', mode: 'auto' } })
    // 仅写 webuiPort，不应清掉已持久化的令牌与 mode
    const next = writeQqBridgeSettings({ hosting: { webuiPort: 7000 } })
    expect(next.hosting.wsToken).toBe('secret-ws')
    expect(next.hosting.webuiToken).toBe('secret-webui')
    expect(next.hosting.webuiPort).toBe(7000)
    expect(next.hosting.mode).toBe('auto')
    // 落盘后读回一致
    expect(readQqBridgeSettings().hosting.wsToken).toBe('secret-ws')
  })

  it('未提供 hosting 时不影响既有 hosting（仅改顶层 enabled）', () => {
    writeQqBridgeSettings({ hosting: { wsToken: 'keep' } })
    writeQqBridgeSettings({ enabled: true })
    const s = readQqBridgeSettings()
    expect(s.hosting.wsToken).toBe('keep')
    expect(s.enabled).toBe(true)
  })

  it('写入内容总是经 normalize 规整（脏数据落盘前被清洗）', () => {
    writeQqBridgeSettings({ hosting: { mode: 'weird', wsPort: 'not-a-number', consented: 'yes' } })
    const s = readQqBridgeSettings()
    expect(s.hosting.mode).toBe('manual')
    expect(s.hosting.wsPort).toBe(3001)
    expect(s.hosting.consented).toBe(false)
  })

  it('reply.segmentDelayMs=0 经 write→read 往返不丢失（issue：回复设置无法修改）', () => {
    writeQqBridgeSettings({ reply: { segmentDelayMs: 0, fallbackText: '' } })
    const s = readQqBridgeSettings()
    expect(s.reply.segmentDelayMs).toBe(0)
    expect(s.reply.fallbackText).toBe('')
  })
})

describe('isAllowedByBinding（issue #11 默认拒绝）', () => {
  const privateSender = { userId: '111', groupId: '', messageType: 'private' }
  const groupSender = { userId: '222', groupId: 'g-1', messageType: 'group' }

  it('allowMode=open：不限制，任何发送方放行', () => {
    const s = normalizeQqBridgeSettings({ binding: { allowMode: 'open' } })
    expect(isAllowedByBinding(s, privateSender)).toBe(true)
    expect(isAllowedByBinding(s, groupSender)).toBe(true)
  })

  it('allowlist 私聊：命中 allowUsers 放行', () => {
    const s = normalizeQqBridgeSettings({ binding: { allowUsers: ['111'] } })
    expect(isAllowedByBinding(s, privateSender)).toBe(true)
  })

  it('allowlist 私聊：未命中 allowUsers 拒绝', () => {
    const s = normalizeQqBridgeSettings({ binding: { allowUsers: ['999'] } })
    expect(isAllowedByBinding(s, privateSender)).toBe(false)
  })

  it('allowlist 私聊：空 allowUsers = 全拒（不再静默全放行）', () => {
    const s = normalizeQqBridgeSettings({})
    expect(isAllowedByBinding(s, privateSender)).toBe(false)
  })

  it('allowlist 群聊：命中 allowGroups 放行', () => {
    const s = normalizeQqBridgeSettings({ binding: { allowGroups: ['g-1'] } })
    expect(isAllowedByBinding(s, groupSender)).toBe(true)
  })

  it('allowlist 群聊：未命中 allowGroups 拒绝（即便发送方在 allowUsers）', () => {
    const s = normalizeQqBridgeSettings({ binding: { allowUsers: ['222'], allowGroups: ['g-9'] } })
    expect(isAllowedByBinding(s, groupSender)).toBe(false)
  })

  it('allowlist 群聊：空 allowGroups = 全拒', () => {
    const s = normalizeQqBridgeSettings({})
    expect(isAllowedByBinding(s, groupSender)).toBe(false)
  })

  it('缺失 binding 归为 allowlist 默认拒绝', () => {
    expect(isAllowedByBinding({}, privateSender)).toBe(false)
    expect(isAllowedByBinding(null, privateSender)).toBe(false)
  })
})
