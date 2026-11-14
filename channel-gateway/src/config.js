/**
 * 网关配置：全部来自环境变量，默认值面向「本机先跑通」。
 *
 * 为什么不复用桌面端（已删除）的 napcatRelease.getNapCatInstallRoot：
 * 那支模块顶部 `import { app } from 'electron'`，且既有前端测试用 vi.mock('electron')
 * 注入 app.getPath；改成惰性 require 会连带打红一批用例，而云端网关根本不该依赖 electron。
 * 故网关自己持有「数据根」概念，shell 由运维预置、路径由环境变量给。
 */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
export const REPO_ROOT = path.resolve(HERE, '..', '..')

/** NapCat shell 必须齐全的文件（缺任一都启动不了，先拦住别白跑） */
const SHELL_REQUIRED = [
  'NapCatWinBootMain.exe',
  'NapCatWinBootHook.dll',
  'napcat.mjs',
  'qqnt.json',
]

function num(value, fallback) {
  const n = Number(value)
  return Number.isFinite(n) ? n : fallback
}

export function loadConfig(env = process.env) {
  const dataRoot = path.resolve(env.DATA_ROOT || path.join(REPO_ROOT, 'local', 'qq-gateway'))
  const shellRoot = path.resolve(env.NAPCAT_SHELL || path.join(dataRoot, 'napcat-shell'))

  return {
    gatewayHost: env.GATEWAY_HOST || '127.0.0.1',
    gatewayPort: num(env.GATEWAY_PORT, 18080),
    // 非空则所有 /api 请求要求 x-gateway-token 匹配；开放到内网/公网时必须设置
    gatewayToken: env.GATEWAY_TOKEN || '',
    dataRoot,
    shellRoot,
    accountsRoot: path.join(dataRoot, 'accounts'),
    // QQ 安装目录里的 QQ.exe；不做注册表探测——服务器上写死路径比猜更可靠
    qqExe: env.QQ_EXE ? path.resolve(env.QQ_EXE) : '',
    maxAccounts: num(env.MAX_ACCOUNTS, 20),
    wsPortBase: num(env.WS_PORT_BASE, 30000),
    webuiPortBase: num(env.WEBUI_PORT_BASE, 31000),
    // 单号磁盘配额之外的软护栏：每号日志保留行数（仅内存环形缓冲）
    logRingSize: num(env.LOG_RING_SIZE, 400),
  }
}

/** 返回 shell 目录缺失项（空数组=可用）；不抛异常，交给调用方决定怎么报 */
export function inspectShell(shellRoot) {
  if (!fs.existsSync(shellRoot)) {
    return { ok: false, missing: SHELL_REQUIRED, reason: `shell_dir_missing:${shellRoot}` }
  }
  const missing = SHELL_REQUIRED.filter((name) => !fs.existsSync(path.join(shellRoot, name)))
  return { ok: missing.length === 0, missing, reason: missing.length ? 'shell_incomplete' : '' }
}

/** 启动前的硬前置：shell 齐全 + QQ_EXE 指向真实文件 */
export function preflight(cfg) {
  const shell = inspectShell(cfg.shellRoot)
  const problems = []
  if (!shell.ok) {
    problems.push({
      code: 'shell_incomplete',
      detail: `NapCat shell 缺少 ${shell.missing.join(', ')}（目录 ${cfg.shellRoot}）`,
    })
  }
  if (!cfg.qqExe) {
    problems.push({ code: 'qq_exe_unset', detail: '未设置 QQ_EXE（指向 QQNT 安装目录下的 QQ.exe）' })
  } else if (!fs.existsSync(cfg.qqExe)) {
    problems.push({ code: 'qq_exe_missing', detail: `QQ_EXE 不存在：${cfg.qqExe}` })
  }
  return { ok: problems.length === 0, problems }
}
