/**
 * MCP 宿主（本地运行时）：拉起/停止本地 MCP 服务进程，维护工具清单与状态，
 * 执行工具调用（危险工具先弹确认），处理 elicitation 确认请求。
 *
 * 状态经 deps.broadcast('desktop:mcp-status', status) 推给渲染端；
 * 确认经 deps.requestConfirm({...}) 冒泡（由 main.js 转成渲染端弹窗，超时默认拒绝）。
 */
import { spawn } from 'node:child_process'
import { createMcpClient } from './mcpClient.js'

// 委托型工具（computer_task）在本地跑多步 agent，可能耗时数分钟，给到 10 分钟上限。
// 必须 < 后端 lianyu.tools.agent-bridge.call-timeout-ms（630s）：这样引擎超时后本地仍能
// 抢在后端放弃前回传「超时」结果，模型据此自然解释失败，而不是结果落到已过期的请求。
const TOOL_CALL_TIMEOUT_MS = 600_000
const MAX_TOOL_RESULT_CHARS = 8_000

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
 * 解析本次要 spawn 的命令：演示服务 > 自定义 command > 已下载的托管引擎。
 * 纯函数，便于单测；缺引擎时返回 { error } 而不是抛。
 */
export function resolveMcpLaunchTarget(settings, { resolveDemoServerCommand, resolveManagedEngine } = {}) {
  if (settings?.useDemoServer) {
    const demo = resolveDemoServerCommand?.()
    if (!demo?.command) return { error: '内置演示服务不可用' }
    return {
      command: demo.command,
      args: Array.isArray(demo.args) ? demo.args : [],
      cwd: undefined,
      env: demo.env && typeof demo.env === 'object' ? demo.env : {},
      needsModelCredentials: false,
    }
  }
  if (settings?.command) {
    return {
      command: settings.command,
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

export function createMcpHost({
  getSettings,
  resolveDemoServerCommand,
  resolveManagedEngine,
  resolveEngineEnv,
  requestConfirm,
  broadcast,
  log = () => {},
}) {
  let proc = null
  let client = null
  let state = 'stopped' // stopped | starting | running | error
  let tools = []
  let lastError = ''
  let generation = 0

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
    const annotations = raw?.annotations || {}
    return {
      name: String(raw?.name || ''),
      description: String(raw?.description || '').trim() || String(raw?.name || ''),
      inputSchema: raw?.inputSchema && typeof raw.inputSchema === 'object'
        ? raw.inputSchema
        : { type: 'object', properties: {} },
      // readOnlyHint=true 一定安全；destructiveHint 缺省时按 MCP 规范视为 true（保守）
      dangerous: annotations.readOnlyHint === true ? false : annotations.destructiveHint !== false,
    }
  }

  async function start() {
    if (state === 'starting' || state === 'running') return status()
    const settings = getSettings()
    const gen = ++generation

    const target = resolveMcpLaunchTarget(settings, { resolveDemoServerCommand, resolveManagedEngine })
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
    const command = target.command
    const args = target.args
    const cwd = target.cwd
    const env = { ...process.env, ...target.env, ...credentialEnv }

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
        // v1 只透传进度类通知给渲染端（将来展示任务进度气泡）
        if (method === 'notifications/progress') {
          try {
            broadcast('desktop:mcp-progress', params)
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
        if (state !== 'stopped') {
          setState(state === 'starting' ? 'error' : 'stopped', reason || '')
        }
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
      setState('running')
    } catch (e) {
      if (gen === generation) {
        setState('error', `握手失败：${e?.message || e}`)
        await stop(true)
      }
    }
    return status()
  }

  async function stop(keepErrorState = false) {
    generation++
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
   * 返回 { ok, content?, error? }，永不抛异常（桥要求结果化）。
   */
  async function callTool(name, args) {
    if (state !== 'running' || !client) {
      return { ok: false, error: 'MCP 服务未运行' }
    }
    const tool = tools.find((t) => t.name === name)
    if (!tool) {
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
        return { ok: false, error: '用户拒绝了该操作' }
      }
    }
    try {
      const result = await client.request('tools/call', { name, arguments: args ?? {} }, TOOL_CALL_TIMEOUT_MS)
      const text = extractText(result)
      if (result?.isError) {
        return { ok: false, error: text || '工具执行失败' }
      }
      return { ok: true, content: text }
    } catch (e) {
      return { ok: false, error: e?.message || '工具调用异常' }
    }
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

  return { start, stop, callTool, status, listToolsForRegistration }
}
