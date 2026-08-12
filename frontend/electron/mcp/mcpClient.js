/**
 * 极简 MCP stdio 客户端：newline-delimited JSON-RPC 2.0。
 *
 * 支持：
 * - client→server 请求（initialize / tools/list / tools/call …）带超时
 * - client→server 通知（notifications/initialized）
 * - server→client 请求（elicitation/create → 确认弹窗）经 onServerRequest 回调
 * - server→client 通知（notifications/progress 等）经 onNotification 回调
 *
 * stdout 上的非 JSON 行一律忽略（打日志），防御引擎往 stdout 混打日志的场景。
 */

export const MCP_PROTOCOL_VERSION = '2025-06-18'

export function createMcpClient({ proc, onNotification, onServerRequest, onClose, log = () => {} }) {
  let nextId = 1
  let closed = false
  const pending = new Map() // id -> { resolve, reject, timer }
  let stdoutBuffer = ''

  function send(message) {
    if (closed || !proc.stdin || proc.stdin.destroyed) return false
    try {
      proc.stdin.write(JSON.stringify(message) + '\n')
      return true
    } catch (e) {
      log(`mcp stdin write failed: ${e?.message || e}`)
      return false
    }
  }

  function request(method, params, timeoutMs = 30000) {
    return new Promise((resolve, reject) => {
      if (closed) {
        reject(new Error('MCP client closed'))
        return
      }
      const id = nextId++
      const timer = setTimeout(() => {
        pending.delete(id)
        reject(new Error(`MCP request timeout: ${method}`))
      }, timeoutMs)
      pending.set(id, { resolve, reject, timer })
      if (!send({ jsonrpc: '2.0', id, method, params: params ?? {} })) {
        clearTimeout(timer)
        pending.delete(id)
        reject(new Error('MCP server not writable'))
      }
    })
  }

  function notify(method, params) {
    send({ jsonrpc: '2.0', method, params: params ?? {} })
  }

  function respond(id, result) {
    send({ jsonrpc: '2.0', id, result: result ?? {} })
  }

  function respondError(id, code, message) {
    send({ jsonrpc: '2.0', id, error: { code, message } })
  }

  function handleMessage(message) {
    // 响应（对应我们发出的请求）
    if (message.id != null && (message.result !== undefined || message.error !== undefined)) {
      const entry = pending.get(message.id)
      if (!entry) return
      pending.delete(message.id)
      clearTimeout(entry.timer)
      if (message.error) {
        entry.reject(new Error(message.error.message || `MCP error ${message.error.code}`))
      } else {
        entry.resolve(message.result)
      }
      return
    }
    // server→client 请求（需要回包，如 elicitation/create）
    if (message.id != null && typeof message.method === 'string') {
      Promise.resolve()
        .then(() => onServerRequest?.(message.method, message.params || {}))
        .then((result) => {
          if (result === undefined) {
            respondError(message.id, -32601, `Method not supported: ${message.method}`)
          } else {
            respond(message.id, result)
          }
        })
        .catch((e) => respondError(message.id, -32603, e?.message || 'internal error'))
      return
    }
    // 通知
    if (typeof message.method === 'string') {
      try {
        onNotification?.(message.method, message.params || {})
      } catch { /* 通知处理失败不影响协议 */ }
    }
  }

  proc.stdout.setEncoding('utf8')
  proc.stdout.on('data', (chunk) => {
    stdoutBuffer += chunk
    let idx
    while ((idx = stdoutBuffer.indexOf('\n')) >= 0) {
      const line = stdoutBuffer.slice(0, idx).trim()
      stdoutBuffer = stdoutBuffer.slice(idx + 1)
      if (!line) continue
      let message
      try {
        message = JSON.parse(line)
      } catch {
        log(`mcp stdout non-json line ignored: ${line.slice(0, 200)}`)
        continue
      }
      try {
        handleMessage(message)
      } catch (e) {
        log(`mcp message handling failed: ${e?.message || e}`)
      }
    }
  })

  proc.stderr?.setEncoding('utf8')
  proc.stderr?.on('data', (chunk) => {
    const text = String(chunk).trim()
    if (text) log(`mcp stderr: ${text.slice(0, 500)}`)
  })

  function close(reason) {
    if (closed) return
    closed = true
    for (const [, entry] of pending) {
      clearTimeout(entry.timer)
      entry.reject(new Error(reason || 'MCP client closed'))
    }
    pending.clear()
    onClose?.(reason)
  }

  proc.on('exit', (code) => close(`MCP server exited (code=${code})`))
  proc.on('error', (e) => close(`MCP server error: ${e?.message || e}`))

  async function initialize(clientInfo) {
    const result = await request('initialize', {
      protocolVersion: MCP_PROTOCOL_VERSION,
      capabilities: { elicitation: {} },
      clientInfo,
    }, 15000)
    notify('notifications/initialized')
    return result
  }

  return { request, notify, initialize, close, get isClosed() { return closed } }
}
