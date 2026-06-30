/**
 * 渲染进程反调试 — 只在 Electron 生产环境下激活。
 * - debugger 语句检测循环：每 100ms 测量 debugger 语句自身耗时，被调试器暂停则强制退出
 * - 页面隐藏时跳过检测，避免无意义占用
 * - Console 清洗：生产环境抹掉 console.log/warn/error 痕迹
 */

let timer = null

/** 反调试主入口（仅 Electron 生产环境执行） */
export function initAntiDebug() {
  if (import.meta.env.DEV) return
  if (!window.electronAPI?.isElectron) return
  const hash = (window.location.hash.replace(/^#/, '') || '/').split('?')[0]
  if (hash === '/launcher' || hash.startsWith('/launcher/')) return

  initDebuggerLoop()
  initConsoleHardening()
}

function initDebuggerLoop() {
  function check() {
    // 页面隐藏（最小化/关闭到托盘）时跳过：此时检测无意义，且避免后台占用
    if (document.hidden) {
      timer = setTimeout(check, 100)
      return
    }

    // 直接测量 debugger 语句自身的暂停耗时。只有 DevTools 真正附加并停在
    // debugger 上时这一句才会耗时；setTimeout 节流或主线程繁忙只会推迟
    // 「下次 check 何时被调用」，不影响此处对 debugger 语句本身的计时，
    // 从而消除启动期主线程繁忙/后台节流导致的误报（v0.2.170 启动即退根因）。
    const t0 = performance.now()
    // eslint-disable-next-line no-debugger
    debugger
    if (performance.now() - t0 > 100) {
      // 检测到调试器！清理关键信息并通知主进程退出
      localStorage.clear()
      sessionStorage.clear()
      window.electronAPI?.quitApp?.()
      return
    }
    timer = setTimeout(check, 100)
  }

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
