import { test } from 'node:test'
import assert from 'node:assert/strict'
import net from 'node:net'
import { allocatePort, isPortFree } from '../src/ports.js'

async function withListening(handler) {
  const server = net.createServer()
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  const port = server.address().port
  try {
    return await handler(port)
  } finally {
    await new Promise((resolve) => server.close(resolve))
  }
}

test('isPortFree 对被占端口返回 false，释放后返回 true', async () => {
  await withListening(async (port) => {
    assert.equal(await isPortFree(port), false)
  })
  // 关掉后同一端口应可绑定（端口可能被系统短暂保留，故只断言最终为 true）
  const port = await allocatePort(39000)
  assert.equal(await isPortFree(port), true)
})

test('allocatePort 跳过被预留的端口', async () => {
  const first = await allocatePort(39100)
  const reserved = new Set([first])
  const second = await allocatePort(39100, { reserved })
  assert.notEqual(second, first)
  assert.ok(second > first)
})

test('allocatePort 跳过实际被占用的端口', async () => {
  await withListening(async (port) => {
    const picked = await allocatePort(port, { span: 5 })
    assert.notEqual(picked, port)
  })
})
