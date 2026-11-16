/**
 * 「返回」到底该 back 还是 push，统一在这里判断。
 *
 * 为什么不能直接 router.back()：桌面端有两条路会把页面「整页 load」进来
 * （Electron 主进程 loadRoute → loadFile(hash)，以及启动时 prepareAuthRoute 用
 * router.replace 恢复上次路由），这两种情况下浏览器的历史栈里没有上一条业务页，
 * history.go(-1) 是个静默的空操作 —— 用户点了按钮什么都没发生，就是「返回无效」。
 *
 * 为什么光看 history.state.back 也不够：真实安装版里实测过一种情况 ——
 * history.state.back 有值（甚至 replaced 标记都在），但 router.back() 之后 hash 一动不动
 * （整页 load 后直接改 hash 造出来的历史项，vue-router 的 go(-1) 会落空）。
 * 所以最终做法是「先试 back，一小会儿后确认真的走了；没走就 push 兜底」。
 *
 * 只认 /app 开头的上一页，避免退到落地页/登录页再被守卫弹回来（那同样像「没反应」）。
 */
export const DEFAULT_BACK_FALLBACK = '/app/settings'

/** back() 之后等多久确认「真的走了」。路由守卫是内存里的同步判断，400ms 足够。 */
export const BACK_VERIFY_MS = 400

export function resolveBack(historyState, fallback = DEFAULT_BACK_FALLBACK) {
  const back = historyState && typeof historyState === 'object' ? historyState.back : null
  if (typeof back === 'string' && back.startsWith('/app')) {
    return { type: 'back' }
  }
  return { type: 'push', path: fallback }
}

/**
 * 点「返回」时统一走这里：保证一定有跳转，绝不会静默无反应。
 *
 * @param {import('vue-router').Router} router
 * @param {object|null} historyState 传 window.history.state
 * @param {string} fallback 没得回时去哪
 * @param {{ verifyMs?: number, setTimer?: Function }} [options] 便于测试注入
 */
export function navigateBack(router, historyState, fallback = DEFAULT_BACK_FALLBACK, options = {}) {
  const next = resolveBack(historyState, fallback)
  if (next.type === 'push') {
    router.push(next.path)
    return
  }

  const verifyMs = typeof options.verifyMs === 'number' ? options.verifyMs : BACK_VERIFY_MS
  const setTimer = options.setTimer || ((fn, ms) => window.setTimeout(fn, ms))
  const before = window.location.hash

  router.back()
  setTimer(() => {
    if (window.location.hash === before) {
      // back 落空（整页 load 后 go(-1) 无效果）：退回兜底路径，保证用户一定离开当前页。
      router.push(fallback)
    }
  }, verifyMs)
}
