/**
 * 「返回」到底该 back 还是 push，统一在这里判断。
 *
 * 为什么不能直接 router.back()：桌面端有两条路会把页面「整页 load」进来
 * （Electron 主进程 loadRoute → loadFile(hash)，以及启动时 prepareAuthRoute 用
 * router.replace 恢复上次路由），这两种情况下浏览器的历史栈里没有上一条业务页，
 * history.go(-1) 是个静默的空操作 —— 用户点了按钮什么都没发生，就是「返回无效」。
 *
 * vue-router 4 会把上一页的 fullPath 写进 history.state.back；为空就说明没得回。
 * 只认 /app 开头，避免退到落地页再被守卫弹回来（那同样像「没反应」）。
 */
export const DEFAULT_BACK_FALLBACK = '/app/settings'

export function resolveBack(historyState, fallback = DEFAULT_BACK_FALLBACK) {
  const back = historyState && typeof historyState === 'object' ? historyState.back : null
  if (typeof back === 'string' && back.startsWith('/app')) {
    return { type: 'back' }
  }
  return { type: 'push', path: fallback }
}
