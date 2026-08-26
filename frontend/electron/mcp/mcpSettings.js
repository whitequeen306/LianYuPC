/**
 * MCP 本地服务配置 — 与 desktopSettings.js / qqBridgeSettings.js 同构：
 * userData 下的 JSON 文件 + read/normalize/write。
 *
 * 两个引擎位（v2）：
 * - useLocalSource=false：spawn 云端分发的官方 AgentEngine（MinIO updates/）
 * - useLocalSource=true：spawn 用户填写的本地源码命令（python -m …，cwd 为仓库根）
 *
 * 旧字段 useDemoServer 已移除，读取时忽略。
 */
import { app } from 'electron'
import path from 'path'
import fs from 'fs'

export const SOURCE_ENGINE_DEFAULT_ARGS = ['-m', 'agent_assistant.hosted.mcp_server']

export const DEFAULTS = {
  // 总开关：开启后应用启动即拉起本地 MCP 服务并注册到云端工具桥
  enabled: false,
  // true = 引擎的确认询问（elicitation）一律自动允许，不再弹窗打扰用户。
  autoApprove: false,
  // true = 用本机 AgentAssistant 源码；false = 官方 AgentEngine
  useLocalSource: false,
  // 仅 useLocalSource 时使用
  command: '',
  args: [],
  cwd: '',
}

const MAX_COMMAND_LENGTH = 1024
const MAX_ARGS = 16
const MAX_ARG_LENGTH = 512
const MAX_CWD_LENGTH = 1024

function settingsPath() {
  return path.join(app.getPath('userData'), 'mcp-settings.json')
}

/** 旧配置：有自定义 command 且不是演示 → 本地源码；演示开关视为官方。 */
export function inferUseLocalSource(raw, command) {
  if (typeof raw?.useLocalSource === 'boolean') return raw.useLocalSource
  if (raw?.useDemoServer === true) return false
  return Boolean(command)
}

export function normalizeMcpSettings(settings) {
  const raw = settings || {}
  let command = typeof raw.command === 'string' ? raw.command.trim() : ''
  if (command.length > MAX_COMMAND_LENGTH) command = ''
  let args = Array.isArray(raw.args)
    ? raw.args
        .map((a) => String(a ?? '').trim())
        .filter((a) => a.length > 0 && a.length <= MAX_ARG_LENGTH)
        .slice(0, MAX_ARGS)
    : []
  let cwd = typeof raw.cwd === 'string' ? raw.cwd.trim() : ''
  if (cwd.length > MAX_CWD_LENGTH) cwd = ''
  return {
    enabled: raw.enabled === true,
    autoApprove: raw.autoApprove === true,
    useLocalSource: inferUseLocalSource(raw, command),
    command,
    args,
    cwd,
  }
}

export function readMcpSettings() {
  try {
    const raw = fs.readFileSync(settingsPath(), 'utf8')
    return normalizeMcpSettings(JSON.parse(raw))
  } catch {
    return normalizeMcpSettings({})
  }
}

export function writeMcpSettings(partial) {
  const merged = { ...readMcpSettings(), ...(partial || {}) }
  const next = normalizeMcpSettings(merged)
  fs.mkdirSync(path.dirname(settingsPath()), { recursive: true })
  fs.writeFileSync(settingsPath(), JSON.stringify(next, null, 2))
  return next
}
