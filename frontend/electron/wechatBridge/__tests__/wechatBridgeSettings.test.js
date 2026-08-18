import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import os from 'os'
import path from 'path'
import fs from 'fs'

const { state } = vi.hoisted(() => ({ state: { userData: '' } }))
vi.mock('electron', () => ({ app: { getPath: () => state.userData } }))

import {
  normalizeWechatBridgeSettings,
  readWechatBridgeSettings,
  writeWechatBridgeSettings,
} from '../wechatBridgeSettings.js'

let tmp
beforeEach(() => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'lianyu-wechat-settings-'))
  state.userData = tmp
})
afterEach(() => {
  fs.rmSync(tmp, { recursive: true, force: true })
})

describe('normalizeWechatBridgeSettings', () => {
  it('empty input returns defaults', () => {
    const s = normalizeWechatBridgeSettings({})
    expect(s.enabled).toBe(false)
    expect(s.binding.provider).toBe('')
    expect(s.binding.characterId).toBe('')
    expect(s.hosting.consented).toBe(false)
    expect(s.reply.timeoutMs).toBe(120000)
  })

  it('trims ids and treats consented as strict true', () => {
    const s = normalizeWechatBridgeSettings({
      enabled: true,
      binding: { characterId: '  7  ', provider: ' deepseek ', conversationId: ' 42 ' },
      hosting: { consented: 'true' },
    })
    expect(s.enabled).toBe(true)
    expect(s.binding.characterId).toBe('7')
    expect(s.binding.provider).toBe('deepseek')
    expect(s.binding.conversationId).toBe('42')
    expect(s.hosting.consented).toBe(false)
    expect(normalizeWechatBridgeSettings({ hosting: { consented: true } }).hosting.consented).toBe(true)
  })

  it('rejects too-small timeout', () => {
    expect(normalizeWechatBridgeSettings({ reply: { timeoutMs: 10 } }).reply.timeoutMs).toBe(120000)
  })
})

describe('read/write wechat-bridge-settings.json', () => {
  it('round-trips binding without writing secrets', () => {
    const next = writeWechatBridgeSettings({
      binding: { characterId: '9', provider: 'openai', model: 'gpt-4.1' },
      hosting: { consented: true },
    })
    expect(next.binding.characterId).toBe('9')
    const disk = JSON.parse(fs.readFileSync(path.join(tmp, 'wechat-bridge-settings.json'), 'utf8'))
    expect(disk.hosting.consented).toBe(true)
    expect(JSON.stringify(disk)).not.toMatch(/sk-|token|secret/i)
    expect(readWechatBridgeSettings().binding.provider).toBe('openai')
  })
})
