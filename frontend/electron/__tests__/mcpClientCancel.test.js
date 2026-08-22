import { describe, it, expect, vi } from 'vitest'
import { createMcpClient } from '../mcp/mcpClient.js'
import { EventEmitter } from 'node:events'

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

function parsedWrites(proc) {
  return proc._writes.map((w) => JSON.parse(w.trim()))
}

describe('mcpClient cancel', () => {
  it('rejects the pending request and notifies the server', async () => {
    const proc = makeFakeProc()
    const client = createMcpClient({ proc })
    const p = client.request('tools/call', { name: 'computer_task' })
    expect(p.mcpRequestId).toBe(1)

    const cancelled = client.cancel(p.mcpRequestId, 'user_escape')
    expect(cancelled).toBe(true)
    await expect(p).rejects.toMatchObject({ message: 'user_escape', cancelled: true })

    const note = parsedWrites(proc).find((m) => m.method === 'notifications/cancelled')
    expect(note.params).toEqual({ requestId: 1, reason: 'user_escape' })
  })

  it('ignores a late response after cancel', async () => {
    const proc = makeFakeProc()
    const client = createMcpClient({ proc })
    const p = client.request('tools/call', {})
    client.cancel(p.mcpRequestId, 'user_escape')
    await expect(p).rejects.toMatchObject({ cancelled: true })
    proc._emitLine({ jsonrpc: '2.0', id: 1, result: { ok: true } })
    await expect(p).rejects.toMatchObject({ cancelled: true })
  })

  it('returns false when cancelling an unknown id', () => {
    const proc = makeFakeProc()
    const client = createMcpClient({ proc })
    expect(client.cancel(99)).toBe(false)
  })
})
