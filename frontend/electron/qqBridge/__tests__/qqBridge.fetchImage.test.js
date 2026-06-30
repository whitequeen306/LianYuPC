import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'

// qqBridge.js 顶部链了 electron / apiProxy / napCatClient；这里只测 fetchImageBytes
// 的 SSRF 重定向防护，把这些重依赖 mock 掉，避免拉起真实 electron/net/ws。
vi.mock('electron', () => ({ app: { getPath: () => '' } }))
vi.mock('../../apiProxy.js', () => ({ performApiRequest: vi.fn() }))
vi.mock('../napCatClient.js', () => ({ createNapCatClient: vi.fn() }))

import { fetchImageBytes, isPrivateImageHost, isLoopbackImageHost } from '../qqBridge.js'

// 构造类 fetch Response：status / ok / headers.get('location') / arrayBuffer()
function makeResponse({ status, location, body }) {
  return {
    status,
    get ok() { return status >= 200 && status < 300 },
    headers: {
      get(name) { return String(name).toLowerCase() === 'location' ? location : null },
    },
    async arrayBuffer() {
      if (!body) return new ArrayBuffer(0)
      return body.buffer.slice(body.byteOffset, body.byteOffset + body.byteLength)
    },
  }
}

let routes
let calls
beforeEach(() => {
  routes = {}
  calls = []
  vi.stubGlobal('fetch', async (url, options) => {
    calls.push({ url: String(url), options })
    const r = routes[String(url)]
    if (!r) throw new Error('test fetch: no route for ' + url)
    return makeResponse(r)
  })
})
afterEach(() => { vi.unstubAllGlobals() })

describe('isPrivateImageHost / isLoopbackImageHost', () => {
  it('loopback 识别 127.0.0.1/localhost/::1/0.0.0.0', () => {
    expect(isLoopbackImageHost('127.0.0.1')).toBe(true)
    expect(isLoopbackImageHost('localhost')).toBe(true)
    expect(isLoopbackImageHost('::1')).toBe(true)
    expect(isLoopbackImageHost('0.0.0.0')).toBe(true)
    expect(isLoopbackImageHost('169.254.169.254')).toBe(false)
    expect(isLoopbackImageHost('example.com')).toBe(false)
  })

  it('私网段拦截但放行 loopback 与公网', () => {
    expect(isPrivateImageHost('10.0.0.1')).toBe(true)
    expect(isPrivateImageHost('172.16.0.1')).toBe(true)
    expect(isPrivateImageHost('192.168.1.1')).toBe(true)
    expect(isPrivateImageHost('169.254.169.254')).toBe(true) // 云元数据
    expect(isPrivateImageHost('127.0.0.1')).toBe(false) // NapCat 本地放行
    expect(isPrivateImageHost('localhost')).toBe(false)
    expect(isPrivateImageHost('example.com')).toBe(false)
  })
})

describe('fetchImageBytes', () => {
  it('base64 源不经 fetch 直接解码', async () => {
    const b64 = Buffer.from('hello').toString('base64')
    const buf = await fetchImageBytes({ url: 'base64://' + b64 })
    expect(Buffer.from(buf).toString()).toBe('hello')
    expect(calls.length).toBe(0)
  })

  it('公网 URL 直达 200：用 redirect:manual 取回字节', async () => {
    routes['https://cdn.example.com/a.jpg'] = { status: 200, body: Buffer.from('IMG') }
    const buf = await fetchImageBytes({ url: 'https://cdn.example.com/a.jpg' })
    expect(Buffer.from(buf).toString()).toBe('IMG')
    expect(calls[0].options).toEqual({ redirect: 'manual' })
  })

  it('公网 URL 302 到公网：合法重定向被跟随', async () => {
    routes['https://cdn.example.com/a.jpg'] = { status: 302, location: 'https://cdn2.example.com/a.jpg' }
    routes['https://cdn2.example.com/a.jpg'] = { status: 200, body: Buffer.from('OK') }
    const buf = await fetchImageBytes({ url: 'https://cdn.example.com/a.jpg' })
    expect(Buffer.from(buf).toString()).toBe('OK')
    expect(calls.map((c) => c.url)).toEqual([
      'https://cdn.example.com/a.jpg',
      'https://cdn2.example.com/a.jpg',
    ])
  })

  it('公网 URL 302 到 127.0.0.1：SSRF 重定向绕过被拒（不实际请求 loopback）', async () => {
    routes['https://cdn.example.com/a.jpg'] = { status: 302, location: 'http://127.0.0.1:6099/secret' }
    await expect(fetchImageBytes({ url: 'https://cdn.example.com/a.jpg' })).rejects.toThrow(/loopback redirect/)
    expect(calls.length).toBe(1) // 首跳后即拒，未对 loopback 发起请求
  })

  it('公网 URL 302 到 169.254.169.254（云元数据）：被拒', async () => {
    routes['https://cdn.example.com/a.jpg'] = { status: 302, location: 'http://169.254.169.254/latest/meta-data/' }
    await expect(fetchImageBytes({ url: 'https://cdn.example.com/a.jpg' })).rejects.toThrow(/private redirect/)
    expect(calls.length).toBe(1)
  })

  it('初始即 loopback(NapCat 本地) 时 loopback→loopback 重定向放行', async () => {
    routes['http://127.0.0.1:6099/a.jpg'] = { status: 302, location: 'http://127.0.0.1:6099/b.jpg' }
    routes['http://127.0.0.1:6099/b.jpg'] = { status: 200, body: Buffer.from('LOCAL') }
    const buf = await fetchImageBytes({ url: 'http://127.0.0.1:6099/a.jpg' })
    expect(Buffer.from(buf).toString()).toBe('LOCAL')
  })

  it('初始私网 host(10.x) 直接拒绝，不发起请求', async () => {
    await expect(fetchImageBytes({ url: 'http://10.0.0.1/x' })).rejects.toThrow(/blocked private/)
    expect(calls.length).toBe(0)
  })

  it('超过最大重定向跳数：拒绝', async () => {
    for (let i = 0; i < 7; i++) {
      routes['https://cdn.example.com/' + i] = { status: 302, location: 'https://cdn.example.com/' + (i + 1) }
    }
    await expect(fetchImageBytes({ url: 'https://cdn.example.com/0' })).rejects.toThrow(/redirect limit/)
  })

  it('非 http(s)/base64 源（如 file://）：拒绝', async () => {
    await expect(fetchImageBytes({ url: 'file:///etc/passwd' })).rejects.toThrow(/unsupported image source/)
    expect(calls.length).toBe(0)
  })

  it('4xx：拒绝', async () => {
    routes['https://cdn.example.com/a.jpg'] = { status: 404 }
    await expect(fetchImageBytes({ url: 'https://cdn.example.com/a.jpg' })).rejects.toThrow(/http 404/)
  })
})
