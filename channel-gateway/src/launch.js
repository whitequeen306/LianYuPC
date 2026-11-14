/**
 * 单个号的启动规格与子进程包装。
 *
 * 启动拼装照抄桌面端 frontend/electron/napcatRuntime/napcatHost.js 的
 * resolveLaunchTarget（那条路径曾在生产跑通过，QQ/NapCat 构建号匹配的年代），
 * 差异只有两处：
 * 1. 多一个 NAPCAT_WORKDIR —— 一机多号靠它隔离 config/cache/logs；
 * 2. loadNapCat.js 放在各自 workdir 里，不写共享 shell（避免多号并发改同一文件）。
 *
 * 子进程生命周期用本目录 supervisor.js（原桌面端 napcatProcess.js 平移过来）：
 * 崩溃指数退避重启、taskkill /T 杀树、launcher code=0 视为正常退出不重启。
 */
import fs from 'node:fs'
import path from 'node:path'
import { createNapCatProcess } from './supervisor.js'
import { accountDir, ensureAccountConfig, profileDir } from './accountStore.js'

/** 把路径换成正斜杠——NapCat 内部按 URL 处理这个值 */
function toPosix(p) {
  return String(p).replace(/\\/g, '/')
}

export function buildLaunchSpec(cfg, account) {
  const shell = cfg.shellRoot
  const dir = accountDir(cfg, account.key)
  const entry = path.join(shell, 'NapCatWinBootMain.exe')
  const hook = path.join(shell, 'NapCatWinBootHook.dll')
  const mainMjs = path.join(shell, 'napcat.mjs')

  return {
    command: entry,
    args: [cfg.qqExe, hook, `--user-data-dir=${profileDir(cfg, account.key)}`],
    cwd: dir,
    env: {
      ...process.env,
      // NapCat 的写入根（config/logs/cache/plugins）——一机多号的关键
      NAPCAT_WORKDIR: dir,
      NAPCAT_PATCH_PACKAGE: path.join(shell, 'qqnt.json'),
      NAPCAT_LOAD_PATH: path.join(dir, 'loadNapCat.js'),
      NAPCAT_INJECT_PATH: hook,
      NAPCAT_LAUNCHER_PATH: entry,
      NAPCAT_MAIN_PATH: toPosix(mainMjs),
      // 见到这个 env，NapCat init 会强行把 webui token 改成它（胜过它自己的随机重生成）
      NAPCAT_WEBUI_SECRET_KEY: account.webuiToken,
    },
  }
}

/**
 * 起一个号。
 * @param {object} cfg
 * @param {object} account
 * @param {{ onLog?: (line:string)=>void, onExit?: (info:object)=>void, onError?: (info:object)=>void }} hooks
 */
export function startAccountProcess(cfg, account, hooks = {}) {
  ensureAccountConfig(cfg, account)
  const spec = buildLaunchSpec(cfg, account)

  for (const file of [spec.command, cfg.qqExe]) {
    if (!file || !fs.existsSync(file)) {
      throw new Error(`missing_file:${file}`)
    }
  }

  const proc = createNapCatProcess({
    command: spec.command,
    args: spec.args,
    cwd: spec.cwd,
    env: spec.env,
    onLog: (line) => hooks.onLog?.(line),
    onExit: (info) => hooks.onExit?.(info),
    onError: (info) => hooks.onError?.(info),
    maxRestarts: 3,
    restartBaseMs: 3000,
    restartMaxMs: 30000,
  })
  proc.start()
  return { proc, spec }
}
