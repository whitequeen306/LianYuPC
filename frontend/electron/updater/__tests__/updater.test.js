import { describe, it, expect, beforeEach, vi } from 'vitest'
import { EventEmitter } from 'events'
import { createHash } from 'crypto'

const mocks = vi.hoisted(() => ({
  isPackaged: true,
  handleRegistry: new Map(),
  webSend: vi.fn(),
  apiResponses: {},
  netRequestImpl: null,
  appVersion: '0.2.258',
  appQuit: vi.fn(),
  quitAndInstall: vi.fn(),
  writes: new Map(),
  removedFiles: [],
}))

vi.mock('electron', () => ({
  app: {
    get isPackaged() { return mocks.isPackaged },
    getVersion: () => mocks.appVersion,
    getPath: () => '/tmp/lianyu-test',
    quit: mocks.appQuit,
  },
  ipcMain: { handle: (ch, fn) => mocks.handleRegistry.set(ch, fn) },
  net: {
    request: (opts) => {
      if (mocks.netRequestImpl) return mocks.netRequestImpl(opts)
      throw new Error('net.request not configured')
    },
  },
}))

vi.mock('child_process', () => ({
  spawn: vi.fn(() => ({ unref: vi.fn() })),
}))

vi.mock('fs', () => ({
  default: {
    mkdirSync: vi.fn(),
    createWriteStream: vi.fn((filePath) => {
      const stream = new EventEmitter()
      mocks.writes.set(filePath, [])
      stream.write = vi.fn((chunk) => {
        mocks.writes.get(filePath).push(Buffer.from(chunk))
        return true
      })
      stream.end = vi.fn((cb) => {
        if (cb) setTimeout(cb, 0)
        return stream
      })
      stream.destroy = vi.fn()
      return stream
    }),
    createReadStream: vi.fn((filePath) => {
      const stream = new EventEmitter()
      const originalOn = stream.on.bind(stream)
      stream.on = (ev, fn) => {
        const result = originalOn(ev, fn)
        if (ev === 'data') {
          for (const chunk of mocks.writes.get(filePath) || []) setTimeout(() => { if (!stream._piped) fn(chunk) }, 0)
        }
        if (ev === 'end') setTimeout(() => { if (!stream._piped) fn() }, 1)
        return result
      }
      stream.pipe = (dest, options = {}) => {
        stream._piped = true
        for (const chunk of mocks.writes.get(filePath) || []) dest.write(chunk)
        if (options.end !== false) dest.end()
        setTimeout(() => stream.emit('end'), 0)
        return dest
      }
      return stream
    }),
    existsSync: vi.fn(() => true),
    unlinkSync: vi.fn((filePath) => { mocks.removedFiles.push(filePath) }),
  },
}))

vi.mock('path', () => ({
  default: {
    join: (...args) => args.join('/'),
    basename: (value) => String(value).split('/').pop(),
  },
}))

vi.mock('../../logger.js', () => ({
  log: vi.fn(), info: vi.fn(), warn: vi.fn(), error: vi.fn(),
}))

vi.mock('../../runtimeSecrets.js', () => ({
  getRuntimeSecrets: () => ({
    apiOrigin: 'https://api.lianyu.test',
    updateOrigin: 'https://download.lianyu.test',
  }),
}))

vi.mock('../../apiProxy.js', () => ({
  performApiRequest: vi.fn(async ({ url }) => {
    if (mocks.apiResponses[url]) return mocks.apiResponses[url]
    return { status: 404, data: '' }
  }),
  isAllowedEgressUrl: () => true,
}))

const mockMainWindow = { isDestroyed: () => false, webContents: { send: mocks.webSend } }

async function loadUpdater() {
  return import('../updater.js')
}

describe('updater (manual mode)', () => {
  beforeEach(() => {
    mocks.handleRegistry.clear()
    mocks.webSend.mockClear()
    mocks.apiResponses = {}
    mocks.netRequestImpl = null
    mocks.isPackaged = true
    mocks.appVersion = '0.2.258'
    mocks.appQuit.mockClear()
    mocks.quitAndInstall.mockClear()
    mocks.writes = new Map()
    mocks.removedFiles = []
    vi.resetModules()
  })

  function sha512Base64(data) {
    return createHash('sha512').update(data).digest('base64')
  }

  function configureLatest(version = '0.2.260', sha512 = sha512Base64(Buffer.from('abcdefghijkl'))) {
    mocks.apiResponses['https://api.lianyu.test/api/public/files/updates/latest.yml'] = {
      status: 200,
      data: `version: ${version}\nfiles:\n  - url: LianYu-Setup-${version}.exe\npath: LianYu-Setup-${version}.exe\nsha512: ${sha512}\n`,
    }
  }

  function makeNetResponse(statusCode, headers, chunks = []) {
    return {
      statusCode,
      headers,
      on: (ev, fn) => {
        if (ev === 'data') chunks.forEach((chunk, index) => setTimeout(() => fn(Buffer.from(chunk)), index + 1))
        if (ev === 'end') setTimeout(() => fn(), chunks.length + 2)
      },
    }
  }

  it('initUpdater 注册 3 个 IPC 通道', async () => {
    const { initUpdater } = await loadUpdater()
    initUpdater(mockMainWindow)
    expect(mocks.handleRegistry.has('updater:check')).toBe(true)
    expect(mocks.handleRegistry.has('updater:download')).toBe(true)
    expect(mocks.handleRegistry.has('updater:install')).toBe(true)
  })

  it('check: 有新版本时推送 update-available', async () => {
    mocks.apiResponses['https://api.lianyu.test/api/public/files/updates/latest.yml'] = {
      status: 200,
      data: 'version: 0.2.260\nfiles:\n  - url: LianYu-Setup-0.2.260.exe\npath: LianYu-Setup-0.2.260.exe\nsha512: abc\n',
    }
    const { initUpdater } = await loadUpdater()
    initUpdater(mockMainWindow)
    const ret = await mocks.handleRegistry.get('updater:check')()
    expect(ret.ok).toBe(true)
    expect(ret.hasUpdate).toBe(true)
    expect(ret.version).toBe('0.2.260')
    expect(mocks.webSend).toHaveBeenLastCalledWith('updater:state', expect.objectContaining({
      state: 'update-available', info: { version: '0.2.260' },
    }))
  })

  it('check: 已是最新时推送 no-update', async () => {
    mocks.appVersion = '0.2.260'
    mocks.apiResponses['https://api.lianyu.test/api/public/files/updates/latest.yml'] = {
      status: 200,
      data: 'version: 0.2.260\nfiles:\n  - url: test.exe\nsha512: abc\n',
    }
    const { initUpdater } = await loadUpdater()
    initUpdater(mockMainWindow)
    const ret = await mocks.handleRegistry.get('updater:check')()
    expect(ret.ok).toBe(true)
    expect(ret.hasUpdate).toBe(false)
    expect(mocks.webSend).toHaveBeenLastCalledWith('updater:state', expect.objectContaining({
      state: 'no-update',
    }))
  })

  it('check: latest.yml 请求失败时推送 error', async () => {
    mocks.apiResponses['https://api.lianyu.test/api/public/files/updates/latest.yml'] = {
      status: 500, data: '',
    }
    const { initUpdater } = await loadUpdater()
    initUpdater(mockMainWindow)
    const ret = await mocks.handleRegistry.get('updater:check')()
    expect(ret.ok).toBe(false)
    expect(mocks.webSend).toHaveBeenLastCalledWith('updater:state', expect.objectContaining({
      state: 'error',
    }))
  })

  it('download: Range 可用时并发下载 part 后合并安装包', async () => {
    configureLatest('0.2.260')
    const { initUpdater } = await loadUpdater()
    initUpdater(mockMainWindow)

    const requestedRanges = []
    const payload = Buffer.from('abcdefghijkl')
    mocks.netRequestImpl = (opts) => {
      const handlers = { response: null, error: null }
      const requestHeaders = {}
      const req = {
        setHeader: (name, value) => { requestHeaders[name.toLowerCase()] = value },
        on: (ev, fn) => { handlers[ev] = fn; return req },
        end: () => {
          const range = requestHeaders.range || ''
          requestedRanges.push(range)
          setTimeout(() => {
            if (range === 'bytes=0-0') {
              handlers.response(makeNetResponse(206, { 'content-range': 'bytes 0-0/12', 'content-length': '1' }, [payload.subarray(0, 1)]))
              return
            }
            const match = range.match(/^bytes=(\d+)-(\d+)$/)
            const start = Number(match[1])
            const end = Number(match[2])
            handlers.response(makeNetResponse(206, {
              'content-range': `bytes ${start}-${end}/12`,
              'content-length': String(end - start + 1),
            }, [payload.subarray(start, end + 1)]))
          }, 0)
        },
      }
      return req
    }

    const ret = await mocks.handleRegistry.get('updater:download')()

    expect(ret.ok).toBe(true)
    expect(requestedRanges[0]).toBe('bytes=0-0')
    expect(requestedRanges.filter(Boolean).length).toBeGreaterThan(1)
    expect(requestedRanges).toContain('bytes=0-1')
    expect(requestedRanges).toContain('bytes=10-11')
    const finalPath = '/tmp/lianyu-test/lianyu-updater/LianYu-Setup-0.2.260.exe'
    expect(Buffer.concat(mocks.writes.get(finalPath)).toString()).toBe('abcdefghijkl')
    expect(mocks.removedFiles).toContain(`${finalPath}.part.0`)
    expect(mocks.webSend).toHaveBeenCalledWith('updater:state', expect.objectContaining({
      state: 'ready',
      info: { version: '0.2.260' },
    }))
  })

  it('download: Range 不可用时回退单连接下载', async () => {
    configureLatest('0.2.260')
    const { initUpdater } = await loadUpdater()
    initUpdater(mockMainWindow)

    const requestedRanges = []
    mocks.netRequestImpl = (opts) => {
      const handlers = { response: null, error: null }
      const requestHeaders = {}
      const req = {
        setHeader: (name, value) => { requestHeaders[name.toLowerCase()] = value },
        on: (ev, fn) => { handlers[ev] = fn; return req },
        end: () => {
          const range = requestHeaders.range || ''
          requestedRanges.push(range)
          setTimeout(() => {
            if (range === 'bytes=0-0') {
              handlers.response(makeNetResponse(200, { 'content-length': '12' }, [Buffer.from('abcdefghijkl')]))
              return
            }
            handlers.response(makeNetResponse(200, { 'content-length': '12' }, [Buffer.from('abcdefghijkl')]))
          }, 0)
        },
      }
      return req
    }

    const ret = await mocks.handleRegistry.get('updater:download')()

    expect(ret.ok).toBe(true)
    expect(requestedRanges).toEqual(['bytes=0-0', ''])
    const finalPath = '/tmp/lianyu-test/lianyu-updater/LianYu-Setup-0.2.260.exe'
    expect(Buffer.concat(mocks.writes.get(finalPath)).toString()).toBe('abcdefghijkl')
  })

  it('download: Range 探测返回 200 时不吞完整安装包再回退', async () => {
    configureLatest('0.2.260')
    const { initUpdater } = await loadUpdater()
    initUpdater(mockMainWindow)

    let probeChunksRead = 0
    mocks.netRequestImpl = () => {
      const handlers = { response: null, error: null }
      const requestHeaders = {}
      const req = {
        setHeader: (name, value) => { requestHeaders[name.toLowerCase()] = value },
        on: (ev, fn) => { handlers[ev] = fn; return req },
        end: () => {
          const range = requestHeaders.range || ''
          setTimeout(() => {
            if (range === 'bytes=0-0') {
              const res = new EventEmitter()
              res.statusCode = 200
              res.headers = { 'content-length': '12' }
              let destroyed = false
              res.destroy = vi.fn(() => { destroyed = true; res.emit('close') })
              res.on('data', () => { probeChunksRead += 1 })
              handlers.response(res)
              setTimeout(() => { if (!destroyed) res.emit('data', Buffer.from('abc')) }, 1)
              setTimeout(() => { if (!destroyed) res.emit('data', Buffer.from('def')) }, 2)
              setTimeout(() => { if (!destroyed) res.emit('end') }, 3)
              return
            }
            handlers.response(makeNetResponse(200, { 'content-length': '12' }, [Buffer.from('abcdefghijkl')]))
          }, 0)
        },
      }
      return req
    }

    const ret = await mocks.handleRegistry.get('updater:download')()

    expect(ret.ok).toBe(true)
    expect(probeChunksRead).toBe(0)
  })

  it('download: 合并后的安装包 sha512 不匹配时拒绝安装', async () => {
    configureLatest('0.2.260', sha512Base64(Buffer.from('expected')))
    const { initUpdater } = await loadUpdater()
    initUpdater(mockMainWindow)

    mocks.netRequestImpl = () => {
      const handlers = { response: null, error: null }
      const requestHeaders = {}
      const req = {
        setHeader: (name, value) => { requestHeaders[name.toLowerCase()] = value },
        on: (ev, fn) => { handlers[ev] = fn; return req },
        end: () => {
          const range = requestHeaders.range || ''
          setTimeout(() => {
            if (range === 'bytes=0-0') {
              handlers.response(makeNetResponse(200, { 'content-length': '12' }, [Buffer.from('abcdefghijkl')]))
              return
            }
            handlers.response(makeNetResponse(200, { 'content-length': '12' }, [Buffer.from('abcdefghijkl')]))
          }, 0)
        },
      }
      return req
    }

    const ret = await mocks.handleRegistry.get('updater:download')()

    expect(ret.ok).toBe(false)
    expect(ret.error).toContain('sha512 mismatch')
    await expect(mocks.handleRegistry.get('updater:install')()).resolves.toEqual({ ok: false, error: 'no downloaded installer' })
  })

  it('download: 分片 Content-Range 与请求范围不匹配时拒绝', async () => {
    configureLatest('0.2.260')
    const { initUpdater } = await loadUpdater()
    initUpdater(mockMainWindow)

    const payload = Buffer.from('abcdefghijkl')
    mocks.netRequestImpl = () => {
      const handlers = { response: null, error: null }
      const requestHeaders = {}
      const req = {
        setHeader: (name, value) => { requestHeaders[name.toLowerCase()] = value },
        on: (ev, fn) => { handlers[ev] = fn; return req },
        end: () => {
          const range = requestHeaders.range || ''
          setTimeout(() => {
            if (range === 'bytes=0-0') {
              handlers.response(makeNetResponse(206, { 'content-range': 'bytes 0-0/12', 'content-length': '1' }, [payload.subarray(0, 1)]))
              return
            }
            const match = range.match(/^bytes=(\d+)-(\d+)$/)
            const start = Number(match[1])
            const end = Number(match[2])
            const contentRange = start === 0 ? 'bytes 1-2/12' : `bytes ${start}-${end}/12`
            handlers.response(makeNetResponse(206, {
              'content-range': contentRange,
              'content-length': String(end - start + 1),
            }, [payload.subarray(start, end + 1)]))
          }, 0)
        },
      }
      return req
    }

    const ret = await mocks.handleRegistry.get('updater:download')()

    expect(ret.ok).toBe(false)
    expect(ret.error).toContain('Content-Range mismatch')
  })

  it('install: 调用 spawn 启动安装包并退出', async () => {
    const { initUpdater } = await loadUpdater()
    initUpdater(mockMainWindow, { quitAndInstall: mocks.quitAndInstall })
    // 先模拟下载完成（downloadedInstallerPath 由 download 设置）
    // 直接调 install，需要先调 download 设置路径
    mocks.apiResponses['https://api.lianyu.test/api/public/files/updates/latest.yml'] = {
      status: 200,
      data: `version: 0.2.260\nfiles:\n  - url: LianYu-Setup-0.2.260.exe\nsha512: ${sha512Base64(Buffer.alloc(100))}\n`,
    }
    // mock net.request for download
    let requestedDownloadUrl = ''
    mocks.netRequestImpl = (opts) => {
      requestedDownloadUrl = opts.url
      const handlers = { response: null, error: null }
      const req = {
        on: (ev, fn) => { handlers[ev] = fn; return req },
        end: () => {
          setTimeout(() => {
            handlers.response({
              statusCode: 200,
              headers: { 'content-length': '100' },
              on: (ev, fn) => {
                if (ev === 'data') setTimeout(() => fn(Buffer.alloc(100)), 1)
                if (ev === 'end') setTimeout(() => fn(), 2)
              },
            })
          }, 1)
        },
      }
      return req
    }
    await mocks.handleRegistry.get('updater:download')()
    expect(requestedDownloadUrl).toBe('https://download.lianyu.test/LianYu-Setup-0.2.260.exe')
    expect(mocks.webSend).toHaveBeenCalledWith('updater:state', expect.objectContaining({
      state: 'downloading',
      info: expect.objectContaining({ speedBytesPerSec: expect.any(Number), etaSeconds: expect.any(Number) }),
    }))
    const ret = await mocks.handleRegistry.get('updater:install')()
    expect(ret.ok).toBe(true)
    expect(mocks.webSend).toHaveBeenLastCalledWith('updater:state', expect.objectContaining({
      state: 'installing',
    }))
    await new Promise((resolve) => setTimeout(resolve, 550))
    expect(mocks.quitAndInstall).toHaveBeenCalledTimes(1)
    expect(mocks.appQuit).not.toHaveBeenCalled()
  })

  it('download: 绝对 asset url 保持原样，不强制改写到 updateOrigin', async () => {
    const { initUpdater } = await loadUpdater()
    initUpdater(mockMainWindow)
    mocks.apiResponses['https://api.lianyu.test/api/public/files/updates/latest.yml'] = {
      status: 200,
      data: `version: 0.2.260\nfiles:\n  - url: https://mirror.lianyu.test/LianYu-Setup-0.2.260.exe\nsha512: ${sha512Base64(Buffer.alloc(100))}\n`,
    }
    let requestedDownloadUrl = ''
    mocks.netRequestImpl = (opts) => {
      requestedDownloadUrl = opts.url
      const handlers = { response: null, error: null }
      const req = {
        on: (ev, fn) => { handlers[ev] = fn; return req },
        end: () => {
          setTimeout(() => {
            handlers.response({
              statusCode: 200,
              headers: { 'content-length': '100' },
              on: (ev, fn) => {
                if (ev === 'data') setTimeout(() => fn(Buffer.alloc(100)), 1)
                if (ev === 'end') setTimeout(() => fn(), 2)
              },
            })
          }, 1)
        },
      }
      return req
    }
    const ret = await mocks.handleRegistry.get('updater:download')()
    expect(ret.ok).toBe(true)
    expect(requestedDownloadUrl).toBe('https://mirror.lianyu.test/LianYu-Setup-0.2.260.exe')
  })

  it('check: 无 apiOrigin 时推送 error', async () => {
    vi.doMock('../../runtimeSecrets.js', () => ({
      getRuntimeSecrets: () => null,
    }))
    const { initUpdater } = await loadUpdater()
    initUpdater(mockMainWindow)
    const ret = await mocks.handleRegistry.get('updater:check')()
    expect(ret.ok).toBe(false)
    vi.doUnmock('../../runtimeSecrets.js')
  })

  it('重复 initUpdater 幂等', async () => {
    const { initUpdater } = await loadUpdater()
    initUpdater(mockMainWindow)
    initUpdater(mockMainWindow)
    expect(mocks.handleRegistry.size).toBe(3)
  })

  it('主窗口销毁时 send 静默跳过', async () => {
    const destroyedWin = { isDestroyed: () => true, webContents: { send: vi.fn() } }
    const { initUpdater } = await loadUpdater()
    initUpdater(destroyedWin)
    mocks.apiResponses['https://api.lianyu.test/api/public/files/updates/latest.yml'] = {
      status: 200,
      data: 'version: 0.2.260\nfiles:\n  - url: test.exe\nsha512: abc\n',
    }
    await mocks.handleRegistry.get('updater:check')()
    expect(destroyedWin.webContents.send).not.toHaveBeenCalled()
  })
})
