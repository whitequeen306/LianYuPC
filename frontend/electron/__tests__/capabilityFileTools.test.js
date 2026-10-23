import { describe, it, expect, vi } from 'vitest'

import { createFileTools, sanitizeFilePath } from '../capability/fileTools.js'

const pathStub = {
  isAbsolute: (p) => /^[A-Za-z]:[\\/]/.test(p) || p.startsWith('/'),
  extname: (p) => {
    const idx = p.lastIndexOf('.')
    return idx > 0 ? p.slice(idx).toLowerCase() : ''
  },
}

function makeTools({ existing = [], openFailure = '' } = {}) {
  const shell = {
    showItemInFolder: vi.fn(),
    openPath: vi.fn(async () => openFailure),
  }
  const fsImpl = {
    existsSync: (p) => existing.includes(p),
  }
  const tools = createFileTools({ shell, fsImpl, pathImpl: pathStub, log: () => {} })
  return { tools, shell }
}

describe('sanitizeFilePath', () => {
  it('accepts absolute windows paths and strips wrapping quotes', () => {
    expect(sanitizeFilePath('"C:\\Users\\hp\\a.pdf"', { pathImpl: pathStub })).toBe('C:\\Users\\hp\\a.pdf')
  })

  it('rejects relative paths, empty, control chars and overlong input', () => {
    expect(() => sanitizeFilePath('a.pdf', { pathImpl: pathStub })).toThrow(/绝对路径/)
    expect(() => sanitizeFilePath('  ', { pathImpl: pathStub })).toThrow(/不能为空/)
    expect(() => sanitizeFilePath('C:\\a\u0000b', { pathImpl: pathStub })).toThrow(/控制字符/)
    expect(() => sanitizeFilePath(`C:\\${'x'.repeat(1100)}`, { pathImpl: pathStub })).toThrow(/过长/)
  })
})

describe('file tools', () => {
  it('reveals an existing file without opening it', async () => {
    const { tools, shell } = makeTools({ existing: ['C:\\a\\报告.pdf'] })
    const res = await tools.reveal({ path: 'C:\\a\\报告.pdf' })
    expect(res.ok).toBe(true)
    expect(shell.showItemInFolder).toHaveBeenCalledWith('C:\\a\\报告.pdf')
    expect(shell.openPath).not.toHaveBeenCalled()
  })

  it('reports a missing path instead of guessing', async () => {
    const { tools } = makeTools({ existing: [] })
    expect((await tools.reveal({ path: 'C:\\a\\nope.pdf' })).error).toBe('file_not_found')
    expect((await tools.open({ path: 'C:\\a\\nope.pdf' })).error).toBe('file_not_found')
  })

  it('opens a normal document through the shell', async () => {
    const { tools, shell } = makeTools({ existing: ['C:\\a\\报告.pdf'] })
    const res = await tools.open({ path: 'C:\\a\\报告.pdf' })
    expect(res.ok).toBe(true)
    expect(shell.openPath).toHaveBeenCalledWith('C:\\a\\报告.pdf')
  })

  it('refuses to execute programs (open == double click)', async () => {
    const { tools, shell } = makeTools({ existing: ['C:\\a\\setup.exe', 'C:\\a\\run.bat', 'C:\\a\\x.ps1'] })
    for (const p of ['C:\\a\\setup.exe', 'C:\\a\\run.bat', 'C:\\a\\x.ps1']) {
      const res = await tools.open({ path: p })
      expect(res.ok).toBe(false)
      expect(res.error).toBe('policy_blocked')
    }
    expect(shell.openPath).not.toHaveBeenCalled()
  })

  it('surfaces shell failures', async () => {
    const { tools } = makeTools({ existing: ['C:\\a\\x.pdf'], openFailure: '没有关联程序' })
    const res = await tools.open({ path: 'C:\\a\\x.pdf' })
    expect(res.ok).toBe(false)
    expect(res.error).toBe('file_open_failed')
  })
})
