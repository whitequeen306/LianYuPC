/**
 * 渲染进程反调试 — 只在 Electron 生产环境下激活。
 * - debugger 语句检测循环：每 100ms 检查，发现则强制退出
 * - 页面隐藏时暂停检测，避免 Chromium 后台节流导致误报
 * - Console 清洗：生产环境抹掉 console.log/warn/error 痕迹
 */

let timer = null
let debuggerLast = 0
let hiddenAt = 0

/** 反调试主入口（仅 Electron 生产环境执行） */
export function initAntiDebug() {
  if (import.meta.env.DEV) return
  if (!window.electronAPI?.isElectron) return

  initDebuggerLoop()
  initConsoleHardening()
}

function initDebuggerLoop() {
  // 使用 debugger 语句检测调试器是否附加
  // 如果浏览器 DevTools 打开，遇到 debugger 语句会暂停；否则直接跳过
  debuggerLast = performance.now()

  function check() {
    // 页面隐藏（最小化/关闭到托盘）时：Chromium 会节流 setTimeout，
    // 导致间隔被拉长到数秒，此时检查无意义且会导致误杀 —— 直接跳过本次检测
    if (document.hidden) {
      timer = setTimeout(check, 100)
      return
    }

    const now = performance.now()
    // 若两次检查间隔超过 500ms，说明 debugger 语句触发了暂停（调试器已附加）
    if (now - debuggerLast > 500) {
      // 检测到调试器！清理关键信息并通知主进程退出
      localStorage.clear()
      sessionStorage.clear()
      window.electronAPI?.quitApp?.()
      return
    }
    debuggerLast = now
    // eslint-disable-next-line no-debugger
    debugger
    timer = setTimeout(check, 100)
  }

  // 页面可见性变化时重置时间基准，避免后台节流累积时长
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) {
      hiddenAt = performance.now()
    } else {
      // 恢复时补偿隐藏期间的时差
      const hiddenDuration = performance.now() - hiddenAt
      debuggerLast += hiddenDuration
    }
  })

  timer = setTimeout(check, 100)
}

function initConsoleHardening() {
  // 生产环境拆除 console（防止通过 console 注入或泄露信息）
  const noop = () => {}
  const methods = ['log', 'info', 'debug', 'warn', 'error', 'trace', 'dir', 'table', 'group', 'groupEnd', 'time', 'timeEnd', 'count', 'clear']
  for (const m of methods) {
    try {
      Object.defineProperty(window.console, m, {
        get() { return noop },
        set() {},
        configurable: false,
        enumerable: true,
      })
    } catch {
      // 可能已被冻结，忽略
    }
  }
}
