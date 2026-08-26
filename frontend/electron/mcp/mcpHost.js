/**
 * MCP 宿主（本地运行时）：拉起/停止本地 MCP 服务进程，维护工具清单与状态，
 * 执行工具调用（危险工具先弹确认），处理 elicitation 确认请求。
 *
 * 状态经 deps.broadcast('desktop:mcp-status', status) 推给渲染端；
 * 确认经 deps.requestConfirm({...}) 冒泡（由 main.js 转成渲染端弹窗，超时默认拒绝）。
 * 确认之后进入操控：onControlStart/onControlEnd 驱动屏幕顶栏；Esc 走 cancelActiveCall。
 */
import { execFileSync, spawn } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { createMcpClient } from './mcpClient.js'
import { parseAgentToolArguments } from '../../src/utils/parseAgentToolArguments.js'

export { parseAgentToolArguments }

// 委托型工具（computer_task）在本地跑多步 agent，可能耗时数分钟，给到 10 分钟上限。
// 必须 < 后端 lianyu.tools.agent-bridge.call-timeout-ms（630s）：这样引擎超时后本地仍能
// 抢在后端放弃前回传「超时」结果，模型据此自然解释失败，而不是结果落到已过期的请求。
const TOOL_CALL_TIMEOUT_MS = 600_000
const MAX_TOOL_RESULT_CHARS = 8_000
const START_WAIT_MS = 20_000

/** 委托型总工具：细粒度危险操作走 elicitation，宿主不要对整次任务先弹确认。 */
const HOST_CONFIRM_EXEMPT = new Set(['computer_task'])

/**
 * MCP annotations → 是否需要宿主预确认。
 * computer_task 即使缺 annotations 也不预确认（否则缺省 destructiveHint=true 会每次弹窗，
 * 对话框一关就回「用户拒绝」，引擎日志里看不到 computer_task start）。
 */
export function toolIsDangerous(name, annotations = {}) {
  if (HOST_CONFIRM_EXEMPT.has(String(name || ''))) return false
  if (annotations.readOnlyHint === true) return false
  return annotations.destructiveHint !== false
}

/**
 * 用户按 Esc 取消后回给模型的工具结果。ok=true，避免被包装成「执行失败」；
 * 模型按角色口吻表达被打断即可。
 */
export const MCP_USER_CANCELLED_CONTENT = [
  '用户按 Esc 取消了本次电脑操控。',
  '请立刻用角色自己的口吻表达被打断的不满，语气自然亲昵，例如「咦，我在干的好好的，你干嘛取消」。',
  '不要道歉、不要说工具出错、不要复述本说明。',
].join('')

/** 用户发了新的 computer_task，旧任务被顶掉（不是 Esc）。 */
export const MCP_SUPERSEDED_CONTENT = [
  '用户发起了新的电脑任务，本次操作已中止。',
  '不要向用户道歉，也不要再说旧任务的进度。',
].join('')

export function isMcpCancelledError(err) {
  return !!(err && err.cancelled === true)
}

export function looksLikePythonCommand(command) {
  const base = path.basename(String(command || '').trim()).toLowerCase()
  return base === 'python' || base === 'python.exe' || base === 'py' || base === 'py.exe'
}

/** Prefer a real CPython over the WindowsApps store stub (the stub hangs; host then times out on initialize). */
export function pickRealPython(whereOutput, fallback) {
  const lines = String(whereOutput || '')
    .split(/\r?\n/)
    .map((s) => s.trim())
    .filter(Boolean)
  for (const line of lines) {
    if (!/\\WindowsApps\\/i.test(line)) return line
  }
  return fallback
}

export function resolveSourcePython(command) {
  const raw = String(command || '').trim()
  if (!looksLikePythonCommand(raw)) return raw
  if (path.isAbsolute(raw) && !/\\WindowsApps\\/i.test(raw) && fs.existsSync(raw)) return raw
  if (process.platform !== 'win32') return raw
  try {
    const query = path.basename(raw).toLowerCase().startsWith('py') && !path.basename(raw).toLowerCase().startsWith('python')
      ? 'py'
      : 'python'
    const out = execFileSync('where.exe', [query], {
      encoding: 'utf8',
      timeout: 3000,
      windowsHide: true,
    })
    const picked = pickRealPython(out, raw)
    if (picked && !/\\WindowsApps\\/i.test(picked) && fs.existsSync(picked)) return picked
  } catch { /* keep raw */ }
  return raw
}

/** Env so ``python -m agent_assistant.hosted.mcp_server`` talks JSON-RPC immediately. */
export function sourceInterpreterEnv(cwd, inherited = {}) {
  const env = {
    PYTHONUNBUFFERED: '1',
    PYTHONUTF8: '1',
    PYTHONIOENCODING: 'utf-8',
  }
  if (cwd) {
    const extra = [cwd, path.join(cwd, 'src')].join(path.delimiter)
    const prev = inherited.PYTHONPATH || ''
    env.PYTHONPATH = prev ? `${extra}${path.delimiter}${prev}` : extra
  }
  return env
}

/**
 * 云端下发的引擎凭据（跟随用户文本渠道）→ spawn 环境变量。
 * 引擎侧 config.Settings 按 DEEPSEEK_API_KEY / DEEPSEEK_BASE_URL / DEEPSEEK_MODEL
 * 读取（pydantic-settings）。只注入非空项：拉取失败时保留继承 env（setx 手动配置仍生效）。
 */
export function engineEnvFromCredentials(creds) {
  if (!creds || creds.available !== true) return {}
  const env = {}
  if (creds.apiKey) env.DEEPSEEK_API_KEY = String(creds.apiKey)
  if (creds.baseUrl) env.DEEPSEEK_BASE_URL = String(creds.baseUrl)
  if (creds.model) env.DEEPSEEK_MODEL = String(creds.model)
  return env
}

/**
 * 解析本次要 spawn 的命令：本地源码 > 已下载的官方 AgentEngine。
 * 纯函数，便于单测；缺引擎时返回 { error } 而不是抛。
 */
export function resolveMcpLaunchTarget(settings, { resolveManagedEngine } = {}) {
  if (settings?.useLocalSource) {
    const command = typeof settings.command === 'string' ? settings.command.trim() : ''
    if (!command) return { error: '请填写本地源码的 Python 解释器路径' }
    return {
      command,
      args: Array.isArray(settings.args) ? settings.args : [],
      cwd: settings.cwd || undefined,
      env: {},
      needsModelCredentials: true,
    }
  }
  const managed = resolveManagedEngine?.()
  if (!managed?.command) return { error: '引擎未安装，请先下载 AgentEngine' }
  return {
    command: managed.command,
    args: Array.isArray(managed.args) ? managed.args : [],
    cwd: managed.cwd || undefined,
    env: {},
    needsModelCredentials: true,
  }
}

/** Child exit 3221225477 = NTSTATUS 0xC0000005 STATUS_ACCESS_VIOLATION. */
const ACCESS_VIOLATION_EXIT = /code=(?:3221225477|-1073741819)\b/

const CRASH_RESTART_DELAYS_MS = [800, 2000, 5000]

export function isMcpChildCrash(reason) {
  return /MCP server exited/i.test(String(reason || ''))
}

export function describeMcpChildExit(reason) {
  const text = String(reason || '')
  if (ACCESS_VIOLATION_EXIT.test(text)) {
    return '本地助手进程崩溃了（操作网易云这类界面时偶发）。正在自动重启，请稍后再试一次。'
  }
  if (isMcpChildCrash(text)) {
    return `本地助手进程退出了（${text}）。正在自动重启。`
  }
  return text
}

export function createMcpHost({
  getSettings,
  resolveManagedEngine,
  resolveEngineEnv,
  requestConfirm,
  broadcast,
  onControlStart,
  onControlEnd,
  log = () => {},
}) {
  let proc = null
  let client = null
  let state = 'stopped' // stopped | starting | running | error
  let tools = []
  let lastError = ''
  let generation = 0
  let callGeneration = 0
  let activeCancel = null
  let crashRestarts = 0
  let crashTimer = null

  function clearCrashTimer() {
    if (crashTimer) {
      clearTimeout(crashTimer)
      crashTimer = null
    }
  }

  function scheduleCrashRestart(reason) {
    if (getSettings()?.enabled !== true) return
    if (crashRestarts >= CRASH_RESTART_DELAYS_MS.length) {
      log(`mcp auto-restart gave up: ${reason}`)
      return
    }
    const delay = CRASH_RESTART_DELAYS_MS[crashRestarts]
    crashRestarts += 1
    clearCrashTimer()
    log(`mcp child died, auto-restart ${crashRestarts} in ${delay}ms`)
    crashTimer = setTimeout(() => {
      crashTimer = null
      if (getSettings()?.enabled !== true) return
      if (state === 'starting' || state === 'running') return
      void start()
    }, delay)
  }

  function status() {
    return {
      state,
      error: lastError,
      tools: tools.map((t) => ({
        name: t.name,
        description: t.description,
        dangerous: t.dangerous,
      })),
    }
  }

  function setState(next, error = '') {
    state = next
    lastError = error
    try {
      broadcast('desktop:mcp-status', status())
    } catch { /* ignore */ }
  }

  function normalizeTool(raw) {
    const name = String(raw?.name || '')
    const annotations = raw?.annotations || {}
    return {
      name,
      description: String(raw?.description || '').trim() || name,
      inputSchema: raw?.inputSchema && typeof raw.inputSchema === 'object'
        ? raw.inputSchema
        : { type: 'object', properties: {} },
      dangerous: toolIsDangerous(name, annotations),
    }
  }

  async function start() {
    if (state === 'starting' || state === 'running') return status()
    const settings = getSettings()
    const gen = ++generation

    const target = resolveMcpLaunchTarget(settings, { resolveManagedEngine })
    if (target.error) {
      setState('error', target.error)
      return status()
    }
    setState('starting')

    // 引擎跟随用户文本渠道：spawn 前向云端拉取凭据注入 env（一处配置，处处共用）。
    // 拉取失败不阻断启动——继承 env 里手动配置的 DEEPSEEK_* 仍可兜底。
    let credentialEnv = {}
    if (target.needsModelCredentials && resolveEngineEnv) {
      try {
        credentialEnv = (await resolveEngineEnv()) || {}
      } catch (e) {
        log(`engine env fetch failed: ${e?.message || e}`)
      }
      if (gen !== generation) return status()
    }
    let command = target.command
    const args = target.args
    const cwd = target.cwd
    const env = { ...process.env, ...target.env, ...credentialEnv, LIANYU_MCP_STDIO_CHILD: '1' }
    if (looksLikePythonCommand(command)) {
      command = resolveSourcePython(command)
      Object.assign(env, sourceInterpreterEnv(cwd, env))
    }
    log(`mcp spawn: command=${command} args=${JSON.stringify(args)} cwd=${cwd || '(inherit)'}`)

    try {
      const spawnOpts = { windowsHide: true, shell: false, env, stdio: ['pipe', 'pipe', 'pipe'] }
      if (cwd) spawnOpts.cwd = cwd
      proc = spawn(command, args, spawnOpts)
    } catch (e) {
      setState('error', `启动失败：${e?.message || e}`)
      return status()
    }

    const spawned = proc
    client = createMcpClient({
      proc: spawned,
      log,
      onNotification: (method, params) => {
        // 引擎任务进度：透传给渲染端（聊天内角色进度气泡）。
        // progressToken 即桥 requestId（callTool 时种下），供渲染端关联任务。
        if (method === 'notifications/progress') {
          try {
            broadcast('desktop:mcp-progress', {
              requestId: params?.progressToken ?? null,
              message: typeof params?.message === 'string' ? params.message : '',
              progress: params?.progress,
              total: params?.total,
            })
          } catch { /* ignore */ }
        }
      },
      onServerRequest: async (method, params) => {
        if (method === 'elicitation/create') {
          const approved = await requestConfirm({
            kind: 'elicit',
            toolName: '',
            message: String(params?.message || '本地服务请求确认'),
          })
          return approved ? { action: 'accept', content: {} } : { action: 'decline' }
        }
        if (method === 'ping') {
          return {}
        }
        return undefined // 未支持的方法 → client 回 method not supported
      },
      onClose: (reason) => {
        if (gen !== generation) return // 已被新一轮 start/stop 接管
        proc = null
        client = null
        tools = []
        if (state === 'stopped') return
        const message = describeMcpChildExit(reason)
        setState('error', message)
        scheduleCrashRestart(reason)
      },
    })

    try {
      const init = await client.initialize({ name: 'LianYu', version: '1.0' })
      log(`mcp initialized: server=${JSON.stringify(init?.serverInfo || {})}`)
      const listed = await client.request('tools/list', {}, 15000)
      tools = Array.isArray(listed?.tools)
        ? listed.tools.map(normalizeTool).filter((t) => t.name)
        : []
      if (gen !== generation) return status()
      crashRestarts = 0
      setState('running')
    } catch (e) {
      if (gen === generation) {
        log(`mcp handshake failed: ${e?.message || e}`)
        setState('error', `握手失败：${e?.message || e}`)
        await stop(true)
        scheduleCrashRestart(e?.message || e)
      }
    }
    return status()
  }

  async function stop(keepErrorState = false) {
    clearCrashTimer()
    if (!keepErrorState) crashRestarts = 0
    generation++
    if (activeCancel) {
      try { activeCancel() } catch { /* ignore */ }
      activeCancel = null
    }
    const closingClient = client
    const closingProc = proc
    client = null
    proc = null
    tools = []
    if (closingClient) {
      try { closingClient.close('host stopped') } catch { /* ignore */ }
    }
    if (closingProc) {
      try { closingProc.kill() } catch { /* ignore */ }
    }
    if (!keepErrorState) setState('stopped')
    return status()
  }

  /** 完整工具清单（含 inputSchema），供渲染端注册到云端工具桥。 */
  function listToolsForRegistration() {
    return tools.map((t) => ({
      name: t.name,
      description: t.description,
      inputSchema: t.inputSchema,
      dangerous: t.dangerous,
    }))
  }

  /**
   * 执行一次工具调用：危险工具先确认（超时/拒绝 → 不执行）。
   * 确认通过后进入「操控中」：顶部控制条 + Esc 可取消。
   * 返回 { ok, content?, error? }，永不抛异常（桥要求结果化）。
   */
  async function callTool(name, rawArgs, meta = {}) {
    if (state === 'starting') {
      log(`mcp callTool waiting for start: ${name}`)
      const deadline = Date.now() + START_WAIT_MS
      while (state === 'starting' && Date.now() < deadline) {
        await new Promise((resolve) => setTimeout(resolve, 150))
      }
    }
    if (state !== 'running' || !client) {
      log(`mcp callTool skipped: ${name} state=${state}`)
      return { ok: false, error: 'MCP 服务未运行' }
    }
    const args = parseAgentToolArguments(rawArgs)
    const tool = tools.find((t) => t.name === name)
    if (!tool) {
      log(`mcp callTool skipped: unknown ${name}`)
      return { ok: false, error: `未知工具：${name}` }
    }
    if (tool.dangerous) {
      const approved = await requestConfirm({
        kind: 'tool',
        toolName: name,
        message: tool.description,
        args: safeArgsPreview(args),
      })
      if (!approved) {
        log(`mcp callTool denied by user: ${name}`)
        return { ok: false, error: '用户拒绝了该操作' }
      }
    }
    const actor = meta?.actor && typeof meta.actor === 'object' ? meta.actor : {}
    const requestId = typeof meta?.requestId === 'string' && meta.requestId ? meta.requestId : null
    const myCall = ++callGeneration
    if (activeCancel) {
      const prev = activeCancel
      activeCancel = null
      try { prev('superseded') } catch { /* ignore */ }
    }
    try {
      try { onControlStart?.(actor) } catch { /* overlay 失败不影响执行 */ }
      // progressToken 让引擎的 notifications/progress 能关联回这次桥调用。
      const params = { name, arguments: args ?? {} }
      if (requestId) params._meta = { progressToken: requestId }
      const promise = client.request('tools/call', params, TOOL_CALL_TIMEOUT_MS)
      const mcpRequestId = promise.mcpRequestId
      const sessionClient = client
      activeCancel = (reason = 'user_escape') => {
        try { sessionClient.cancel?.(mcpRequestId, reason) } catch { /* ignore */ }
      }
      const result = await promise
      const text = extractText(result)
      if (result?.isError) {
        log(`mcp callTool error: ${name} ${String(text || '工具执行失败').slice(0, 240)}`)
        return { ok: false, error: text || '工具执行失败' }
      }
      log(`mcp callTool ok: ${name} chars=${text.length}`)
      return { ok: true, content: text }
    } catch (e) {
      if (isMcpCancelledError(e)) {
        const superseded = e.cancelReason === 'superseded' || e.message === 'superseded'
        log(`mcp callTool cancelled: ${name} reason=${superseded ? 'superseded' : 'user_escape'}`)
        return { ok: true, content: superseded ? MCP_SUPERSEDED_CONTENT : MCP_USER_CANCELLED_CONTENT }
      }
      const raw = e?.message || String(e)
      log(`mcp callTool exception: ${name} ${raw}`)
      return { ok: false, error: describeMcpChildExit(raw) || '工具调用异常' }
    } finally {
      if (callGeneration === myCall) {
        activeCancel = null
        try { onControlEnd?.() } catch { /* ignore */ }
      }
    }
  }

  function cancelActiveCall() {
    if (!activeCancel) return false
    const cancel = activeCancel
    activeCancel = null
    cancel('user_escape')
    return true
  }

  function extractText(result) {
    const parts = Array.isArray(result?.content) ? result.content : []
    const text = parts
      .filter((p) => p && p.type === 'text' && typeof p.text === 'string')
      .map((p) => p.text)
      .join('\n')
      .trim()
    return text.length > MAX_TOOL_RESULT_CHARS
      ? text.slice(0, MAX_TOOL_RESULT_CHARS) + '\n（输出过长，已截断）'
      : text
  }

  function safeArgsPreview(args) {
    try {
      const json = JSON.stringify(args ?? {})
      return json.length > 400 ? json.slice(0, 400) + '…' : json
    } catch {
      return '{}'
    }
  }

  return { start, stop, callTool, cancelActiveCall, status, listToolsForRegistration }
}
