/**
 * 语音回合收尾门：让「状态变 idle」等本地播放队列真正排空。
 *
 * 背景（2026-10-22 语音回归遗留）：服务端 `turn.done` 表示不再产音频，但客户端
 * 播放队列通常还压着 2-5 秒尾音（单片合成 0.3-1s、单片播放 2-5s）。此前一到
 * `turn.done` 就 `phase='idle'` + `resetBargeState()`，而 `maybeBargeIn` 要求
 * `phase === 'speaking' && bargeArmed` —— 于是「角色还在说话」的这段时间里
 * 用户打断完全无效，表现就是文档里记的那条遗留问题。
 *
 * 抽成纯逻辑模块而不是内联进 `useVoiceDuplex`：定时器与回调时序可以单测，
 * 不必造 WebSocket / AudioContext。
 */

/**
 * @param {object} options
 * @param {() => boolean} options.isPlaying 本地播放队列是否还有声音
 * @param {() => void} options.onIdle 尾音排空（或本来就没声音）时回调
 * @param {number} [options.intervalMs] 轮询间隔
 * @returns {{ settle: () => void, stop: () => void, pending: () => boolean }}
 */
export function createTurnTailGate({ isPlaying, onIdle, intervalMs = 120 } = {}) {
  let timer = null

  function stop() {
    if (timer !== null) {
      clearInterval(timer)
      timer = null
    }
  }

  /**
   * 回合收尾：没尾音就立刻 idle；有尾音则保持现状（调用方仍处于 speaking，
   * 打断依然可用），等播放排空后再回调。
   */
  function settle() {
    stop()
    if (isPlaying?.()) {
      timer = setInterval(() => {
        if (!isPlaying?.()) {
          stop()
          onIdle?.()
        }
      }, intervalMs)
      return
    }
    onIdle?.()
  }

  return {
    settle,
    stop,
    pending: () => timer !== null,
  }
}
