/**
 * 语音 WebSocket 复用的判定（纯函数，便于单测）。
 *
 * 背景（2026-10-31 线上反馈「点开始通话不报错，但说话没反应，挂断才报错」）：
 * 旧实现把 `CONNECTING` 也当成「已在连接」直接 return，于是握手失败的残留
 * socket 会让后续每次点击都静默跳过握手 —— 麦克风照常打开，PCM 帧却因为
 * `readyState !== OPEN` 被全部丢弃，服务端连一条 /ws/voice 记录都没有。
 *
 * 结论：只有真正 OPEN 的连接可以复用；CONNECTING/CLOSING/CLOSED 一律重建。
 */

/** WebSocket.OPEN，避免在纯函数里依赖全局 WebSocket（Node 测试环境没有） */
export const WS_OPEN = 1

/**
 * @param {{ readyState?: number } | null | undefined} socket
 * @returns {boolean}
 */
export function shouldReuseVoiceSocket(socket) {
  return socket?.readyState === WS_OPEN
}
