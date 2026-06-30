import { describe, it, expect, beforeEach, vi } from 'vitest'

// apiProxy.js 顶层 import { net } from 'electron'；纯函数测试不触达 net，仍 mock 以隔离主进程依赖
vi.mock('electron', () => ({ net: { request: vi.fn() } }))

import { isAllowedEgressUrl, sanitizeEgressHeaders, TokenBucket } from '../apiProxy.js'

describe('isAllowedEgressUrl — issue #6 出口 host 白名单', () => {
  const api = 'https://api.lianyu.example.com:8443'
  it('放行 API origin 内的 http(s) 路径', () => {
    expect(isAllowedEgressUrl('https://api.lianyu.example.com:8443/api/conversation', api)).toBe(true)
    expect(isAllowedEgressUrl('https://api.lianyu.example.com:8443/', api)).toBe(true)
  })
  it('拒绝内网/元数据/其它主机（SSRF）', () => {
    expect(isAllowedEgressUrl('http://169.254.169.254/latest/meta-data/', api)).toBe(false)
    expect(isAllowedEgressUrl('http://127.0.0.1:8080/admin', api)).toBe(false)
    expect(isAllowedEgressUrl('http://localhost/api', api)).toBe(false)
  })
  it('拒绝同 hostname 不同端口（越权到同机其它服务）', () => {
    expect(isAllowedEgressUrl('https://api.lianyu.example.com:9999/', api)).toBe(false)
  })
  it('拒绝非 http(s) scheme（file/data/javascript/ws）', () => {
    expect(isAllowedEgressUrl('file:///etc/passwd', api)).toBe(false)
    expect(isAllowedEgressUrl('data:text/html,xx', api)).toBe(false)
    expect(isAllowedEgressUrl('javascript:alert(1)', api)).toBe(false)
    expect(isAllowedEgressUrl('ws://api.lianyu.example.com:8443/ws', api)).toBe(false)
  })
  it('拒绝空/非字符串/不可解析', () => {
    expect(isAllowedEgressUrl('', api)).toBe(false)
    expect(isAllowedEgressUrl(null, api)).toBe(false)
    expect(isAllowedEgressUrl('not-a-url', api)).toBe(false)
  })
  it('dev origin（http 127.0.0.1:8080）同样精确匹配 host:port', () => {
    const dev = 'http://127.0.0.1:8080'
    expect(isAllowedEgressUrl('http://127.0.0.1:8080/api/x', dev)).toBe(true)
    expect(isAllowedEgressUrl('http://127.0.0.1:9090/api/x', dev)).toBe(false)
  })
})

describe('sanitizeEgressHeaders — issue #6 鉴权头由主进程注入', () => {
  it('剔除渲染层自带的 lianyu-token / authorization（大小写不敏感）并由主进程注入', () => {
    const out = sanitizeEgressHeaders({
      'lianyu-token': 'forged-by-xss',
      Authorization: 'Bearer forged',
      AUTHORIZATION: 'x',
      'Content-Type': 'application/json',
      'X-Trace-Id': 'abc',
    }, 'main-process-token')
    expect(out['lianyu-token']).toBe('main-process-token')
    expect(out.Authorization).toBeUndefined()
    expect(out['AUTHORIZATION']).toBeUndefined()
    expect(out['Content-Type']).toBe('application/json')
    expect(out['X-Trace-Id']).toBe('abc')
  })
  it('无 authToken 时不注入鉴权头', () => {
    const out = sanitizeEgressHeaders({ 'lianyu-token': 'forged' }, '')
    expect(out['lianyu-token']).toBeUndefined()
  })
  it('数组头拼接、null/undefined 跳过', () => {
    const out = sanitizeEgressHeaders({ Accept: ['a', 'b'], Skip: null, X: undefined }, 't')
    expect(out.Accept).toBe('a, b')
    expect(out.Skip).toBeUndefined()
    expect(out.X).toBeUndefined()
  })
})

describe('TokenBucket — issue #6 出口限流', () => {
  beforeEach(() => { vi.useRealTimers() })
  it('容量内突发通过，超容拒绝', () => {
    const b = new TokenBucket(3, 0)
    expect(b.tryAcquire()).toBe(true)
    expect(b.tryAcquire()).toBe(true)
    expect(b.tryAcquire()).toBe(true)
    expect(b.tryAcquire()).toBe(false)
  })
  it('按速率补给后恢复', () => {
    const now = 1000
    const spy = vi.spyOn(Date, 'now').mockReturnValue(now)
    const b = new TokenBucket(2, 100) // 100/s = 0.1/ms
    expect(b.tryAcquire()).toBe(true)
    expect(b.tryAcquire()).toBe(true)
    expect(b.tryAcquire()).toBe(false)
    spy.mockReturnValue(now + 50) // 50ms * 0.1 = 5 → 补到 cap 2
    expect(b.tryAcquire()).toBe(true)
    spy.mockRestore()
  })
})
