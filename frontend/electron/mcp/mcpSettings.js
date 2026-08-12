/**
 * MCP 本地服务配置 — 与 desktopSettings.js / qqBridgeSettings.js 同构：
 * userData 下的 JSON 文件 + read/normalize/write。
 *
 * v1 只托管一个服务位（AgentAssistant 引擎）：
 * - useDemoServer=true：用应用内置的演示 MCP 服务（高级选项）
 * - useDemoServer=false 且 command 为空：spawn 已下载的托管引擎（MinIO updates/）
 * - useDemoServer=false 且 command 有值：spawn 用户配置的自定义命令
 */
import { app } from 'electron'
import path from 'path'
import fs from 'fs'

export const DEFAULTS = {
  // 总开关：开启后应用启动即拉起本地 MCP 服务并注册到云端工具桥
  enabled: false,
  // true = 使用内置演示服务（高级选项）；默认 false，走云端分发的 AgentEngine
  useDemoServer: false,
  // 自定义引擎命令（可执行文件路径，或 python 等）；留空则使用已下载的托管引擎
  command: '',
  // 命令参数（字符串数组），例如 ['-m','agent_assistant.hosted.mcp_server']
  args: [],
  // 工作目录（可选）；源码方式跑 python -m 时须指向 AgentAssistant 仓库根，模块才可解析
  cwd: '',
}

const MAX_COMMAND_LENGTH = 1024
const MAX_ARGS = 16
const MAX_ARG_LENGTH = 512
const MAX_CWD_LENGTH = 1024

function settingsPath() {
  return path.join(app.getPath('userData'), 'mcp-settings.json')
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
    useDemoServer: raw.useDemoServer === true,
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
