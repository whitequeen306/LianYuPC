/**
 * 单实例锁（Windows）。
 *
 * 为什么需要：双击快捷方式、开机自启与手动启动可能同时拉起两个进程。两个实例会
 * 各自创建托盘、桌宠窗口与桌面观察者（截图频率翻倍），并争抢同一份 QQ 桥正向 WS
 * 端口（NapCat 只接受一个客户端），表现为「托盘图标重复、桌宠叠两个、桥随机断开」。
 *
 * 语义：拿到锁的实例负责响应 `second-instance`——把「用户又点了一次图标」翻译成
 * 「把已有主窗拉到前台」；没拿到锁的实例立即退出，不建任何窗口/托盘/桥。
 *
 * 抽成独立模块而不是内联在 main.js：main.js 已有 3000+ 行且无法单测，
 * 这里的判定与异常兜底可以独立验证。
 */

/**
 * @param {object} options
 * @param {import('electron').App} options.app
 * @param {(payload: { argv: string[] }) => void} [options.onSecondInstance]
 * @param {(message: string) => void} [options.log]
 * @returns {boolean} true = 本进程持有锁，可继续启动；false = 已有实例，本进程应退出
 */
export function acquireSingleInstanceLock({ app, onSecondInstance, log } = {}) {
  // 单测 / 纯 node 环境下没有 Electron app，不阻塞启动。
  if (!app || typeof app.requestSingleInstanceLock !== 'function') {
    return true
  }

  const acquired = app.requestSingleInstanceLock()
  if (!acquired) {
    log?.('single instance: lock held by another process, quitting this one')
    app.quit()
    return false
  }

  app.on('second-instance', (_event, argv) => {
    log?.('single instance: second-instance detected, surfacing existing window')
    try {
      onSecondInstance?.({ argv: Array.isArray(argv) ? argv : [] })
    } catch (err) {
      // 第二个实例只代表「用户点了图标」，唤起失败不能拖垮已经在跑的实例。
      log?.(`single instance: onSecondInstance failed: ${err?.message || err}`)
    }
  })

  return true
}
