import { describe, it, expect, vi } from 'vitest'

import { BUILTIN_ALIASES, createAppIndex } from '../capability/appIndex.js'
import { createLauncher } from '../capability/launcher.js'

const env = { ProgramData: 'C:/ProgramData', APPDATA: 'C:/Users/hp/AppData/Roaming' }

/** 目录树：[路径] → 子项（目录名以 / 结尾） */
function fakeFs(tree) {
  return {
    readdirSync: (dir) => {
      const entries = tree[dir]
      if (!entries) {
        const err = new Error(`ENOENT: ${dir}`)
        err.code = 'ENOENT'
        throw err
      }
      return entries.map((name) => ({
        name,
        isDirectory: () => name.endsWith('/'),
      }))
    },
  }
}

const pathStub = {
  join: (...parts) => parts.filter(Boolean).join('/').replace(/\/+/g, '/').replace(/\/$/, ''),
}

const startMenu = `${env.ProgramData}/Microsoft/Windows/Start Menu/Programs`

describe('app index', () => {
  it('resolves builtin aliases without touching the filesystem', () => {
    const index = createAppIndex({
      fsImpl: fakeFs({}),
      pathImpl: pathStub,
      env,
      execFileSyncImpl: () => { throw new Error('should not run') },
    })
    expect(index.resolve('记事本')).toBe('notepad.exe')
    expect(index.resolve('网易云音乐')).toBe(BUILTIN_ALIASES['网易云音乐'])
    expect(index.stats().startMenu).toBe(-1)
  })

  it('scans the start menu and matches by name', () => {
    const index = createAppIndex({
      fsImpl: fakeFs({
        [startMenu]: ['网易云音乐.lnk', 'Visual Studio Code.lnk', 'Tools/'],
        [`${startMenu}/Tools`]: ['Everything.lnk'],
      }),
      pathImpl: pathStub,
      env,
      execFileSyncImpl: () => '',
    })
    expect(index.resolve('visual studio code')).toBe(`${startMenu}/Visual Studio Code.lnk`)
    expect(index.resolve('everything')).toBe(`${startMenu}/Tools/Everything.lnk`)
    expect(index.stats().startMenu).toBe(3)
  })

  it('prefers the shortest fuzzy hit', () => {
    const index = createAppIndex({
      fsImpl: fakeFs({
        [startMenu]: ['网易云音乐.lnk', '网易云音乐 帮助.lnk'],
      }),
      pathImpl: pathStub,
      env,
      execFileSyncImpl: () => '',
    })
    // 「云音乐」不在内置别名里，走开始菜单的包含匹配
    expect(index.resolve('云音乐')).toBe(`${startMenu}/网易云音乐.lnk`)
  })

  it('falls back to registry App Paths and finally null', () => {
    const regOutput = [
      'HKEY_LOCAL_MACHINE\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\code.exe',
      '    (Default)    REG_SZ    C:\\Program Files\\VS Code\\Code.exe',
    ].join('\r\n')
    const index = createAppIndex({
      fsImpl: fakeFs({ [startMenu]: [] }),
      pathImpl: pathStub,
      env,
      execFileSyncImpl: () => regOutput,
    })
    expect(index.resolve('code.exe')).toBe('C:\\Program Files\\VS Code\\Code.exe')
    expect(index.resolve('不存在的应用')).toBeNull()
  })

  it('survives a missing start menu directory and a failing reg.exe', () => {
    const index = createAppIndex({
      fsImpl: fakeFs({}),
      pathImpl: pathStub,
      env,
      execFileSyncImpl: () => { throw new Error('reg blocked') },
      log: () => {},
    })
    expect(index.resolve('nope')).toBeNull()
  })
})

describe('launcher', () => {
  const makeLauncher = (resolveTo, shellOverrides = {}) => {
    const shell = {
      openPath: vi.fn(async () => ''),
      openExternal: vi.fn(async () => {}),
      ...shellOverrides,
    }
    const launcher = createLauncher({
      index: { resolve: () => resolveTo },
      shell,
      // 注入解析器：裸 exe 名按 System32 展开，避免测试真的去跑 where.exe
      resolveExe: (t) => (/^[a-zA-Z]:/.test(t) || /[\\/]/.test(t) ? t : `C:/Windows/System32/${t}`),
      log: () => {},
    })
    return { launcher, shell }
  }

  it('refuses unknown apps instead of guessing', async () => {
    const { launcher, shell } = makeLauncher(null)
    const res = await launcher.launch({ app: '不存在的应用' })
    expect(res).toEqual({ ok: false, error: 'app_not_installed' })
    expect(shell.openPath).not.toHaveBeenCalled()
  })

  it('falls back to PATH resolution when the index misses a *.exe name', async () => {
    // 真实踩到：模型说「notepad.exe」时 appIndex 里没有这个键，曾被误报 app_not_installed
    const { launcher, shell } = makeLauncher(null)
    const res = await launcher.launch({ app: 'notepad.exe' })
    expect(res.ok).toBe(true)
    expect(shell.openPath).toHaveBeenCalledWith('C:/Windows/System32/notepad.exe')
  })

  it('falls back to the Start Menu shortcut when the builtin alias is not on PATH', async () => {
    // 真实线上故障（user 402）：内置别名把「网易云音乐」映射成 cloudmusic.exe，
    // 但它装在 D:\网易云\CloudMusic\ 不在 PATH 上 → where.exe 失败 → app_not_installed。
    // 正确行为：回退到开始菜单里的 .lnk。
    const shell = { openPath: vi.fn(async () => ''), openExternal: vi.fn(async () => {}) }
    const LINK = 'C:/ProgramData/Microsoft/Windows/Start Menu/Programs/网易云音乐.lnk'
    const launcher = createLauncher({
      index: {
        resolve: (_name, opts) => (opts?.skipBuiltin ? LINK : 'cloudmusic.exe'),
      },
      shell,
      resolveExe: (t) => (t === 'cloudmusic.exe' ? null : t),
      log: () => {},
    })

    const res = await launcher.launch({ app: '网易云音乐' })
    expect(res.ok).toBe(true)
    expect(shell.openPath).toHaveBeenCalledWith(LINK)
  })

  it('opens a resolved app through shell.openPath (never a command line)', async () => {
    const { launcher, shell } = makeLauncher('notepad.exe')
    const res = await launcher.launch({ app: '记事本' })
    expect(res.ok).toBe(true)
    expect(shell.openPath).toHaveBeenCalledWith('C:/Windows/System32/notepad.exe')
  })

  it('reports openPath failures as app_not_installed', async () => {
    const { launcher } = makeLauncher('bad.exe', { openPath: async () => '没有应用程序与此操作的指定文件有关联' })
    const res = await launcher.launch({ app: 'bad' })
    expect(res.ok).toBe(false)
    expect(res.error).toBe('app_not_installed')
  })

  it('handles protocol targets and extra URLs', async () => {
    const { launcher, shell } = makeLauncher('ms-settings:')
    await launcher.launch({ app: '设置' })
    expect(shell.openExternal).toHaveBeenCalledWith('ms-settings:')

    const second = makeLauncher('notepad.exe')
    await second.launcher.launch({ app: '记事本', open: 'https://example.com' })
    expect(second.shell.openExternal).toHaveBeenCalledWith('https://example.com')
  })

  it('rejects an empty app name', async () => {
    const { launcher } = makeLauncher('notepad.exe')
    expect((await launcher.launch({ app: '   ' })).ok).toBe(false)
  })
})
