import { describe, it, expect } from 'vitest'

import {
  EVIDENCE_SCREEN,
  EVIDENCE_USER,
  decide,
  describeAction,
  escalateDanger,
  isAppAllowlisted,
  isAppDenied,
  isSensitiveProcess,
  isTerminalProcess,
  labelHitsHighRisk,
} from '../capability/policy.js'

describe('process / label heuristics', () => {
  it('detects terminal processes', () => {
    expect(isTerminalProcess('powershell.exe')).toBe(true)
    expect(isTerminalProcess('C:\\Windows\\System32\\cmd.exe')).toBe(true)
    expect(isTerminalProcess('chrome.exe')).toBe(false)
    expect(isTerminalProcess(undefined)).toBe(false)
  })

  it('detects sensitive processes', () => {
    expect(isSensitiveProcess('1Password.exe')).toBe(true)
    expect(isSensitiveProcess('某银行bank.exe')).toBe(true)
    expect(isSensitiveProcess('notepad.exe')).toBe(false)
  })

  it('detects high risk labels', () => {
    expect(labelHitsHighRisk('删除全部')).toBe(true)
    expect(labelHitsHighRisk('Pay now')).toBe(true)
    expect(labelHitsHighRisk('播放')).toBe(false)
  })
})

describe('escalateDanger', () => {
  it('typing into a terminal becomes R3', () => {
    expect(escalateDanger('desk_type', { text: 'dir' }, { danger: 'R2', foregroundProcess: 'powershell.exe' }))
      .toBe('R3')
    expect(escalateDanger('desk_type', { text: 'hi' }, { danger: 'R2', foregroundProcess: 'notepad.exe' }))
      .toBe('R2')
  })

  it('clicking a destructive label becomes R3', () => {
    expect(escalateDanger('desk_click', { label: '删除账号' }, { danger: 'R2' })).toBe('R3')
    expect(escalateDanger('desk_click', { label: '播放' }, { danger: 'R2' })).toBe('R2')
  })
})

describe('decide', () => {
  it('does not interrupt read-only actions', () => {
    const d = decide({ tool: 'desk_screen_read', danger: 'R0', args: { evidence: EVIDENCE_USER } })
    expect(d.allowed).toBe(true)
    expect(d.needsConfirm).toBe(false)
  })

  it('lets R2 through silently by default', () => {
    const d = decide({ tool: 'desk_click', danger: 'R2', args: { label: '播放', evidence: EVIDENCE_USER } })
    expect(d.allowed).toBe(true)
    expect(d.needsConfirm).toBe(false)
  })

  it('asks for everything with side effects in strict mode', () => {
    const d = decide({
      tool: 'desk_click', danger: 'R2', args: { label: '播放', evidence: EVIDENCE_USER },
      strictConfirm: true,
    })
    expect(d.needsConfirm).toBe(true)
  })

  it('always asks for R3', () => {
    const d = decide({ tool: 'desk_click', danger: 'R2', args: { label: '转账', evidence: EVIDENCE_USER } })
    expect(d.allowed).toBe(true)
    expect(d.needsConfirm).toBe(true)
    expect(d.danger).toBe('R3')
  })

  it('refuses R3 triggered by screen content', () => {
    const d = decide({ tool: 'desk_click', danger: 'R2', args: { label: '转账', evidence: EVIDENCE_SCREEN } })
    expect(d.allowed).toBe(false)
    expect(d.reason).toBe('screen_content_high_risk')
  })

  it('refuses everything on a sensitive foreground window', () => {
    const d = decide({
      tool: 'desk_screen_read', danger: 'R0', args: { evidence: EVIDENCE_USER },
      foregroundProcess: 'KeePassXC.exe',
    })
    expect(d.allowed).toBe(false)
    expect(d.reason).toBe('sensitive_window')
  })

  it('honours a role ceiling', () => {
    const policy = { ...{ version: 1 }, default: { maxDanger: 'R3' }, roles: { pet: { maxDanger: 'R1' } } }
    const d = decide({
      tool: 'desk_click', danger: 'R2', args: { label: '播放', evidence: EVIDENCE_USER }, role: 'pet', policy,
    })
    expect(d.allowed).toBe(false)
    expect(d.reason).toBe('policy_blocked')
  })

  it('always confirms clipboard reads (privacy)', () => {
    const d = decide({ tool: 'desk_clipboard_get', danger: 'R2', args: { evidence: EVIDENCE_USER } })
    expect(d.needsConfirm).toBe(true)
  })
})

describe('describeAction', () => {
  it('describes clicks by label', () => {
    expect(describeAction('desk_click', { label: '播放' })).toBe('点击「播放」')
  })

  it('truncates long typed text but reports the full length', () => {
    const text = 'x'.repeat(100)
    const out = describeAction('desk_type', { text })
    expect(out).toContain('共 100 字')
    expect(out.length).toBeLessThan(80)
  })

  it('maps system control actions to human sentences', () => {
    expect(describeAction('desk_system_control', { action: 'volume_set', value: 30 })).toBe('把音量调到 30%')
    expect(describeAction('desk_system_control', { action: 'media_next' })).toBe('下一首')
  })

  it('never returns an empty sentence', () => {
    expect(describeAction('desk_unknown_tool', {})).toBe('执行 desk_unknown_tool')
  })
})

describe('app allow/deny lists', () => {
  it('denies dangerous apps and allows common ones', () => {
    expect(isAppDenied('powershell.exe')).toBe(true)
    expect(isAppDenied('notepad.exe')).toBe(false)
    expect(isAppAllowlisted('cloudmusic.exe')).toBe(true)
    expect(isAppAllowlisted('unknown-tool.exe')).toBe(false)
  })
})
