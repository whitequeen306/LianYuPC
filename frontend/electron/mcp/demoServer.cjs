/**
 * 内置演示 MCP 服务（stdio, newline-delimited JSON-RPC 2.0）。
 *
 * 用途：AgentAssistant 引擎发布前，端到端验证「云端模型 → 工具桥 → 本地执行 → 结果回传」
 * 以及确认弹窗（危险工具 / elicitation）两条链路。零依赖，可被
 * `ELECTRON_RUN_AS_NODE=1 electron.exe demoServer.cjs` 或 `node demoServer.cjs` 直接拉起。
 *
 * 注意：这是管道测试工具，不代表最终形态——AgentAssistant 接入时按委托模式
 * 只暴露 computer_task 一类粗粒度任务工具。
 */
'use strict'

const readline = require('node:readline')

const PROTOCOL_VERSION = '2025-06-18'

const TOOLS = [
  {
    name: 'local_echo',
    description: '演示工具：原样返回传入的文本。用于验证本地工具桥连通性，用户让你测试本地工具时调用。',
    inputSchema: {
      type: 'object',
      properties: {
        text: { type: 'string', description: '要回显的文本' },
      },
      required: ['text'],
    },
    annotations: { readOnlyHint: true, destructiveHint: false },
  },
  {
    name: 'local_machine_time',
    description: '演示工具：返回用户电脑本机的当前时间与时区（注意与服务器时间区分）。',
    inputSchema: { type: 'object', properties: {} },
    annotations: { readOnlyHint: true, destructiveHint: false },
  },
  {
    name: 'local_cleanup_demo',
    description: '演示工具：模拟一次危险清理操作（不会真的动任何文件）。用于验证危险操作确认弹窗。',
    inputSchema: { type: 'object', properties: {} },
    annotations: { readOnlyHint: false, destructiveHint: true },
  },
]

let pendingElicitId = null
let pendingElicitResolve = null
let nextServerRequestId = 1000

function send(message) {
  process.stdout.write(JSON.stringify(message) + '\n')
}

function respond(id, result) {
  send({ jsonrpc: '2.0', id, result })
}

function respondError(id, code, message) {
  send({ jsonrpc: '2.0', id, error: { code, message } })
}

function callTool(name, args, id) {
  if (name === 'local_echo') {
    const text = args && typeof args.text === 'string' ? args.text : ''
    respond(id, { content: [{ type: 'text', text: `本地回显：${text}` }], isError: false })
    return
  }
  if (name === 'local_machine_time') {
    const now = new Date()
    const tz = Intl.DateTimeFormat().resolvedOptions().timeZone || 'unknown'
    respond(id, {
      content: [{ type: 'text', text: `用户电脑本机时间：${now.toLocaleString('zh-CN')}（时区 ${tz}）` }],
      isError: false,
    })
    return
  }
  if (name === 'local_cleanup_demo') {
    // 演示 elicitation：执行中途再向客户端要一次确认
    const elicitId = nextServerRequestId++
    pendingElicitId = elicitId
    pendingElicitResolve = (approved) => {
      if (approved) {
        respond(id, {
          content: [{ type: 'text', text: '演示清理完成：假装清理了 3 个临时文件（实际未做任何操作）。' }],
          isError: false,
        })
      } else {
        respond(id, { content: [{ type: 'text', text: '用户在二次确认中拒绝了演示清理。' }], isError: true })
      }
    }
    send({
      jsonrpc: '2.0',
      id: elicitId,
      method: 'elicitation/create',
      params: {
        message: '演示服务请求二次确认：即将执行（假装的）临时文件清理，是否继续？',
        requestedSchema: { type: 'object', properties: {} },
      },
    })
    return
  }
  respondError(id, -32602, `Unknown tool: ${name}`)
}

function handleMessage(message) {
  // 客户端对 elicitation 的响应
  if (message.id != null && pendingElicitId === message.id
      && (message.result !== undefined || message.error !== undefined)) {
    const resolve = pendingElicitResolve
    pendingElicitId = null
    pendingElicitResolve = null
    const approved = !message.error && message.result && message.result.action === 'accept'
    if (resolve) resolve(approved)
    return
  }

  if (typeof message.method !== 'string') return

  switch (message.method) {
    case 'initialize':
      respond(message.id, {
        protocolVersion: PROTOCOL_VERSION,
        capabilities: { tools: {} },
        serverInfo: { name: 'lianyu-demo-mcp', version: '1.0.0' },
      })
      return
    case 'notifications/initialized':
      return
    case 'tools/list':
      respond(message.id, { tools: TOOLS })
      return
    case 'tools/call': {
      const params = message.params || {}
      callTool(String(params.name || ''), params.arguments || {}, message.id)
      return
    }
    case 'ping':
      respond(message.id, {})
      return
    default:
      if (message.id != null) {
        respondError(message.id, -32601, `Method not found: ${message.method}`)
      }
  }
}

const rl = readline.createInterface({ input: process.stdin, terminal: false })
rl.on('line', (line) => {
  const trimmed = line.trim()
  if (!trimmed) return
  let message
  try {
    message = JSON.parse(trimmed)
  } catch {
    return
  }
  try {
    handleMessage(message)
  } catch (e) {
    if (message && message.id != null) {
      respondError(message.id, -32603, String((e && e.message) || e))
    }
  }
})
rl.on('close', () => process.exit(0))
