import { describe, it, expect, beforeEach } from 'vitest'
import {
  UPDATE_PAGE_PATH,
  buildPromptMessage,
  buildPromptTitle,
  isProbed,
  isSnoozed,
  markProbed,
  resetUpdatePromptState,
  shouldPromptForUpdate,
  snoozeUpdatePrompt,
} from '../useUpdatePrompt.js'

const HAS_UPDATE = { ok: true, hasUpdate: true, version: '0.2.368', currentVersion: '0.2.367' }

describe('shouldPromptForUpdate', () => {
  beforeEach(() => resetUpdatePromptState())

  it('有新版本时弹', () => {
    expect(shouldPromptForUpdate(HAS_UPDATE)).toBe(true)
  })

  it('已是最新时不弹', () => {
    expect(shouldPromptForUpdate({ ok: true, hasUpdate: false })).toBe(false)
  })

  it('探测失败时不弹（断网不该弹错误）', () => {
    expect(shouldPromptForUpdate({ ok: false, error: 'timeout' })).toBe(false)
    expect(shouldPromptForUpdate(null)).toBe(false)
    expect(shouldPromptForUpdate(undefined)).toBe(false)
  })

  it('缺少版本号时不弹（避免出现「发现新版本 v」）', () => {
    expect(shouldPromptForUpdate({ ok: true, hasUpdate: true })).toBe(false)
    expect(shouldPromptForUpdate({ ok: true, hasUpdate: true, version: '' })).toBe(false)
  })

  it('点过「稍后再装」后这一轮不再弹', () => {
    expect(shouldPromptForUpdate(HAS_UPDATE)).toBe(true)
    snoozeUpdatePrompt()
    expect(shouldPromptForUpdate(HAS_UPDATE)).toBe(false)
    expect(isSnoozed()).toBe(true)
  })

  it('复位后又能弹（模拟下次启动）', () => {
    snoozeUpdatePrompt()
    resetUpdatePromptState()
    expect(shouldPromptForUpdate(HAS_UPDATE)).toBe(true)
  })
})

describe('probe 只跑一次', () => {
  beforeEach(() => resetUpdatePromptState())

  it('markProbed 之后 isProbed 为真', () => {
    expect(isProbed()).toBe(false)
    markProbed()
    expect(isProbed()).toBe(true)
  })
})

describe('文案', () => {
  it('带上当前版本与最新版本', () => {
    expect(buildPromptTitle('0.2.368')).toBe('发现新版本 v0.2.368')
    expect(buildPromptMessage('0.2.368', '0.2.367'))
      .toContain('v0.2.367')
    expect(buildPromptMessage('0.2.368', '0.2.367'))
      .toContain('v0.2.368')
  })

  it('版本缺失时不留空档', () => {
    expect(buildPromptTitle('')).toBe('发现新版本')
    expect(buildPromptMessage('', '')).not.toContain('vv')
  })
})

describe('更新页路径', () => {
  it('指向关于页（AppUpdateButton 所在处）', () => {
    expect(UPDATE_PAGE_PATH).toBe('/app/about')
  })
})
