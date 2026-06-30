/**
 * NapCat 运行时——子进程管理（B 方案自管托管）。
 *
 * 职责：spawn 入口（exe 或经 cmd /c 调 bat）、采集 stdout/stderr 逐行上抛、
 * 尽力解析 WebUI URL/就绪线索、崩溃指数退避自动重启（封顶 maxRestarts 次）、
 * Windows 下用 taskkill /F /T 杀整棵进程树（NapCat 会派生 QQ 子进程）。
 *
 * 与 napCatClient.js 同构：onStatus 风格、自管定时器、idempotent stop。
 * 本模块不感知桥接；就绪判定以 stdout 线索为主，真实就绪以桥接 WS 连成
 * 为准（由 host 监听 bridge 状态），二者解耦。
 */
import { spawn, spawnSync } from 'node:child_process'

const WEBUI_URL_RE = /https?:\/\/127\.0\.0\.1:(\d+)\/webui\?token=([0-9a-zA-Z]+)/i
// 就绪线索：匹配任一即上报 onReady（启发式早判定，非强保证；漏判由 host 的
// onWebui/webui-URL 与 8s 兜底定时器兜住）。收窄原则——每条须同时命中"主体
// +服务态动词"，避免 /webui/i、/onebot/i 这类单词在配置加载阶段误触；webui
// URL 本身由 WEBUI_URL_RE 单独捕获走 onWebui，不在此重复。
const READY_HINTS = [
  /\bwebsocket.*\blisten/i, // WebSocket 服务监听（正向 WS 就绪，最可靠）
  /\bonebot.*\b(listen|start|ready|running)/i, // OneBot 协议端进入服务态（前缀 \b 兼容 OneBot11）
  /\bnapcat.*\b(start|ready|running)/i, // NapCat 自报已启动（前缀 \b 兼容 NapCatShell）
]

/**
 * @param {{ command: string, args?: string[], cwd?: string, env?: Record<string,string>, onLog?: (line:string)=>void, onWebui?: (info:{port:number,token:string,url:string})=>void, onReady?: (info:{hint:string})=>void, onExit?: (info:{code:number|null,signal:string|null,expected:boolean})=>void, onError?: (info:{state:string})=>void, maxRestarts?: number, restartBaseMs?: number, restartMaxMs?: number }} opts
 */
export function createNapCatProcess({
  command,
  args = [],
  cwd,
  env,
  onLog,
  onWebui,
  onReady,
  onExit,
  onError,
  maxRestarts = 5,
  restartBaseMs = 2000,
  restartMaxMs = 30000,
} = {}) {
  let child = null
  let stopped = true
  let restartCount = 0
  let restartTimer = null
  let readyReported = false
  let webuiReported = false
  let currentState = 'stopped'
  let lastPid = null

  function emit(state) {
    currentState = state
    // 重启耗尽进入 error：上抛 host，使其把状态从 'running' 降级（否则进程已死
    // 而 host 仍报 running，状态条误导）。其余状态不上抛——host 靠 onWebui/onReady/onExit 驱动。
    if (state === 'error') {
      try { onError?.({ state }) } catch { /* 回调异常不影响进程模块 */ }
    }
  }

  function handleLine(line) {
    if (!line) return
    onLog?.(line)
    if (!webuiReported) {
      const m = line.match(WEBUI_URL_RE)
      if (m) {
        webuiReported = true
        onWebui?.({ port: Number(m[1]), token: m[2], url: m[0] })
      }
    }
    if (!readyReported && READY_HINTS.some((re) => re.test(line))) {
      readyReported = true
      onReady?.({ hint: line.trim() })
    }
  }

  function drainBuffer(buf, prefix) {
    // split 一次拿到所有完整行，避免 indexOf+slice 循环对尾部反复拷贝的 O(n²)；
    // 末段是无尾换行的残留，留作下次与新 chunk 拼接
    const parts = buf.split('\n')
    const remaining = parts.pop()
    for (let i = 0; i < parts.length; i++) {
      const line = parts[i].replace(/\r$/, '')
      handleLine(prefix ? `${prefix} ${line}` : line)
    }
    return remaining
  }

  function spawnOnce() {
    let proc
    try {
      proc = spawn(command, args, { cwd, env, windowsHide: true, shell: false })
    } catch (e) {
      onLog?.(`[process] spawn error: ${e?.message || e}`)
      scheduleRestartAfterSpawnError()
      return
    }
    child = proc
    lastPid = proc.pid ?? null
    readyReported = false
    webuiReported = false
    emit('running')

    let stdoutBuf = ''
    let stderrBuf = ''
    proc.stdout?.on('data', (chunk) => {
      stdoutBuf = drainBuffer(stdoutBuf + chunk.toString('utf8'), '')
    })
    proc.stderr?.on('data', (chunk) => {
      stderrBuf = drainBuffer(stderrBuf + chunk.toString('utf8'), '[stderr]')
    })

    proc.on('exit', (code, signal) => {
      child = null
      if (stopped) {
        emit('stopped')
        onExit?.({ code, signal, expected: true })
        return
      }
      // exit code=0：进程正常结束，非崩溃。NapCatWinBootMain.exe 这类 launcher
      // 启动 QQ（注入 hook）后即以 code=0 退出——属正常完成，不应退避重启
      // （否则会把 launcher 的正常退出当成崩溃循环，撞 maxRestarts 后转 error）。
      // NapCat 实际生命周期在被注入的 QQ 进程内，由 host 的 webui/bridge 状态判活。
      if (code === 0) {
        onLog?.(`[process] exited normally (code=0), launcher done — not restarting`)
        onExit?.({ code, signal, expected: true })
        return
      }
      onExit?.({ code, signal, expected: false })
      if (restartCount < maxRestarts) {
        restartCount += 1
        const delay = Math.min(restartBaseMs * (1 << Math.min(restartCount - 1, 5)), restartMaxMs)
        emit('restarting')
        onLog?.(`[process] crashed (code=${code}, signal=${signal}), restart ${restartCount}/${maxRestarts} in ${delay}ms`)
        restartTimer = setTimeout(() => {
          restartTimer = null
          spawnOnce()
        }, delay)
      } else {
        emit('error')
        onLog?.(`[process] restart limit reached (${maxRestarts}), giving up`)
      }
    })

    proc.on('error', (err) => {
      onLog?.(`[process] error: ${err?.message || err}`)
      // spawn 阶段错误通常不会触发 exit；按崩溃处理一次
      if (child === proc) {
        child = null
        if (!stopped) scheduleRestartAfterSpawnError()
      }
    })
  }

  function scheduleRestartAfterSpawnError() {
    if (stopped) return
    if (restartCount < maxRestarts) {
      restartCount += 1
      const delay = Math.min(restartBaseMs * (1 << Math.min(restartCount - 1, 5)), restartMaxMs)
      emit('restarting')
      restartTimer = setTimeout(() => {
        restartTimer = null
        spawnOnce()
      }, delay)
    } else {
      emit('error')
    }
  }

  function killTree(pid) {
    if (process.platform !== 'win32') {
      try {
        child?.kill()
      } catch {
        /* ignore */
      }
      return
    }
    if (!pid) return
    try {
      spawnSync('taskkill', ['/F', '/T', '/PID', String(pid)], { windowsHide: true, stdio: 'ignore' })
    } catch {
      try {
        child?.kill()
      } catch {
        /* ignore */
      }
    }
  }

  function start() {
    if (!stopped) return
    if (!command) {
      onLog?.('[process] start aborted: no command')
      return
    }
    stopped = false
    restartCount = 0
    readyReported = false
    webuiReported = false
    spawnOnce()
  }

  function stop() {
    stopped = true
    if (restartTimer) {
      clearTimeout(restartTimer)
      restartTimer = null
    }
    const pid = child?.pid
    if (child) {
      try {
        child.removeAllListeners()
      } catch {
        /* ignore */
      }
      killTree(pid)
      try {
        child.kill()
      } catch {
        /* ignore */
      }
      child = null
    }
    emit('stopped')
  }

  function getStatus() {
    return { state: currentState, pid: lastPid, restartCount }
  }

  return { start, stop, getStatus }
}
