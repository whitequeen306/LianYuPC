import { describe, it, expect, vi } from 'vitest'
import { EventEmitter } from 'node:events'
import { createMcpClient } from '../mcp/mcpClient.js'

/** 假 stdio 进程：stdin 收集写入的 JSON 行，stdout/stderr 为可 emit 的 EventEmitter。 */
function makeFakeProc() {
  const proc = new EventEmitter()
  const writes = []
  proc.stdin = { destroyed: false, write: (data) => { writes.push(data); return true } }
  proc.stdout = new EventEmitter()
  proc.stdout.setEncoding = () => {}
  proc.stderr = new EventEmitter()
  proc.stderr.setEncoding = () => {}
  proc._writes = writes
  proc._emitLine = (obj) => proc.stdout.emit('data', JSON.stringify(obj) + '\n')
  return proc
}

function lastRequest(proc) {
  const parsed = proc._writes.map((w) => JSON.parse(w.trim()))
  return parsed[parsed.length - 1]
}

describe('mcpClient', () => {
  it('resolves a request when a matching response arrives', async () => {
    const proc = makeFakeProc()
    const client = createMcpClient({ proc })

    const p = client.request('tools/list', {})
    const sent = lastRequest(proc)
    expect(sent.method).toBe('tools/list')
    expect(sent.jsonrpc).toBe('2.0')

    proc._emitLine({ jsonrpc: '2.0', id: sent.id, result: { tools: [{ name: 'a' }] } })
    await expect(p).resolves.toEqual({ tools: [{ name: 'a' }] })
  })

  it('rejects a request when an error response arrives', async () => {
    const proc = makeFakeProc()
    const client = createMcpClient({ proc })
    const p = client.request('tools/call', {})
    const sent = lastRequest(proc)
    proc._emitLine({ jsonrpc: '2.0', id: sent.id, error: { code: -32000, message: 'nope' } })
    await expect(p).rejects.toThrow('nope')
  })

  it('routes notifications to onNotification', () => {
    const proc = makeFakeProc()
    const onNotification = vi.fn()
    createMcpClient({ proc, onNotification })
    proc._emitLine({ jsonrpc: '2.0', method: 'notifications/progress', params: { progress: 0.5 } })
    expect(onNotification).toHaveBeenCalledWith('notifications/progress', { progress: 0.5 })
  })

  it('answers server→client requests via onServerRequest and writes a response', async () => {
    const proc = makeFakeProc()
    const onServerRequest = vi.fn(async () => ({ action: 'accept' }))
    createMcpClient({ proc, onServerRequest })

    proc._emitLine({ jsonrpc: '2.0', id: 42, method: 'elicitation/create', params: { message: 'ok?' } })
    await vi.waitFor(() => {
      const reply = lastRequest(proc)
      expect(reply.id).toBe(42)
      expect(reply.result).toEqual({ action: 'accept' })
    })
    expect(onServerRequest).toHaveBeenCalledWith('elicitation/create', { message: 'ok?' })
  })

  it('replies method-not-found when onServerRequest returns undefined', async () => {
    const proc = makeFakeProc()
    createMcpClient({ proc, onServerRequest: async () => undefined })
    proc._emitLine({ jsonrpc: '2.0', id: 7, method: 'unknown/method', params: {} })
    await vi.waitFor(() => {
      const reply = lastRequest(proc)
      expect(reply.id).toBe(7)
      expect(reply.error.code).toBe(-32601)
    })
  })

  it('ignores non-JSON stdout lines without crashing', async () => {
    const proc = makeFakeProc()
    const log = vi.fn()
    const client = createMcpClient({ proc, log })
    const p = client.request('ping', {})
    const sent = lastRequest(proc)
    proc.stdout.emit('data', 'starting engine...\n')       // noise
    proc._emitLine({ jsonrpc: '2.0', id: sent.id, result: {} })
    await expect(p).resolves.toEqual({})
    expect(log).toHaveBeenCalled()
  })

  it('handles partial/multiple messages across chunk boundaries', async () => {
    const proc = makeFakeProc()
    const client = createMcpClient({ proc })
    const p = client.request('ping', {})
    const sent = lastRequest(proc)
    const full = JSON.stringify({ jsonrpc: '2.0', id: sent.id, result: { ok: 1 } }) + '\n'
    proc.stdout.emit('data', full.slice(0, 10))
    proc.stdout.emit('data', full.slice(10))
    await expect(p).resolves.toEqual({ ok: 1 })
  })

  it('rejects all pending on process exit', async () => {
    const proc = makeFakeProc()
    const client = createMcpClient({ proc })
    const p = client.request('tools/list', {})
    proc.emit('exit', 1)
    await expect(p).rejects.toThrow(/exited/)
    expect(client.isClosed).toBe(true)
  })

  it('initialize sends protocol version then an initialized notification', async () => {
    const proc = makeFakeProc()
    const client = createMcpClient({ proc })
    const p = client.initialize({ name: 'LianYu', version: '1.0' })
    const initReq = lastRequest(proc)
    expect(initReq.method).toBe('initialize')
    expect(initReq.params.protocolVersion).toBeTruthy()
    proc._emitLine({ jsonrpc: '2.0', id: initReq.id, result: { serverInfo: { name: 's' } } })
    await p
    const note = lastRequest(proc)
    expect(note.method).toBe('notifications/initialized')
  })
})
