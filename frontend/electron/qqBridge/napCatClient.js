/**
 * NapCat 正向 WebSocket 客户端（OneBot 11）。
 *
 * 与后端 lianyu-qq-bridge 的 NapCatClient（Java）同构：
 * - echo 配对 API 请求/响应（sendApi 返回 Promise<data>）
 * - 收到 post_type==="message" 的事件经 onMessage 回调上抛
 * - 断线指数退避重连（base * 2^min(n,5)，封顶 reconnectMaxMs）
 *
 * 仅用于主进程直连本地 NapCat（ws://127.0.0.1:...），不经过 Electron net/证书 pin；
 * 云端 API 调用另走 apiProxy.performApiRequest。
 */
import WebSocket from 'ws'

const API_TIMEOUT_MS = 15000

export function createNapCatClient({
  wsUrl,
  accessToken = '',
  connectTimeoutMs = 10000,
  reconnectBaseMs = 600,
  reconnectMaxMs = 6000,
  onMessage,
  onStatus,
} = {}) {
  let ws = null
  let selfId = ''
  let currentState = 'stopped'
  let echoSeq = 0
  const pending = new Map() // echo -> { resolve, reject, timer }
  let reconnectTimer = null
  let connectTimer = null
  let selfIdProbeTimer = null
  let probeEpoch = 0 // 递增计数器：kick 事件递增，使 in-flight 的 get_login_info 响应作废
  let reconnectAttempts = 0
  let stopped = true
  let connecting = false

  function emitStatus(state, extra = {}) {
    currentState = state
    try {
      onStatus?.({ state, selfId, ...extra })
    } catch {
      /* 回调异常不影响连接 */
    }
  }

  function clearReconnectTimer() {
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
  }

  function clearConnectTimer() {
    if (connectTimer) {
      clearTimeout(connectTimer)
      connectTimer = null
    }
  }

  // 登录态探测 + 在线 watchdog：
  // - 未登录时每 5s 快速补探，拿到 selfId 即升 ready；
  // - 已登录后切到 30s 慢速 watchdog，验证 QQ 是否仍在线；
  // - watchdog 发现 get_login_info 返回空（QQ 被踢下线）→ 清 selfId、降级 connected、重启快速补探。
  // 断线/停止时一并清掉定时器。
  function clearSelfIdProbe() {
    if (selfIdProbeTimer) {
      clearInterval(selfIdProbeTimer)
      selfIdProbeTimer = null
    }
  }

  function probeSelfId() {
    if (stopped || !ws || ws.readyState !== WebSocket.OPEN) return
    const epoch = probeEpoch
    sendApi('get_login_info')
      .then((data) => {
        if (epoch !== probeEpoch) return // 过时响应（kick 事件已递增 epoch），丢弃
        const id = data && data.user_id != null ? String(data.user_id) : ''
        if (id) {
          if (!selfId) {
            // 首次拿到 selfId：升级到 ready，切慢速 watchdog
            selfId = id
            emitStatus('ready', { selfId })
            clearSelfIdProbe()
            selfIdProbeTimer = setInterval(probeSelfId, 30000)
          }
          // selfId 已存在且仍在线：无需变化
        } else if (selfId) {
          // QQ 下线了（之前有 selfId，现在 get_login_info 返回空）
          selfId = ''
          emitStatus('connected', { kicked: true })
          clearSelfIdProbe()
          selfIdProbeTimer = setInterval(probeSelfId, 5000)
        }
      })
      .catch(() => {
        /* 未登录或暂不可用，下个周期再探 */
      })
  }

  function startSelfIdProbe() {
    clearSelfIdProbe()
    selfIdProbeTimer = setInterval(probeSelfId, 5000)
    probeSelfId() // 立即探一次，缩短感知延迟
  }

  function failPending(error) {
    for (const { reject, timer } of pending.values()) {
      clearTimeout(timer)
      reject(error)
    }
    pending.clear()
  }

  function buildWsUrl(baseUrl, token) {
    if (!token) return baseUrl
    try {
      const u = new URL(baseUrl)
      if (!u.searchParams.has('access_token')) {
        u.searchParams.set('access_token', token)
      }
      return u.toString()
    } catch {
      const sep = baseUrl.includes('?') ? '&' : '?'
      return `${baseUrl}${sep}access_token=${encodeURIComponent(token)}`
    }
  }

  function start() {
    stopped = false
    connect()
  }

  function stop() {
    stopped = true
    clearReconnectTimer()
    clearConnectTimer()
    clearSelfIdProbe()
    failPending(new Error('napcat client stopped'))
    if (ws) {
      try {
        ws.removeAllListeners()
        ws.close()
      } catch {
        /* ignore */
      }
      ws = null
    }
    selfId = ''
    emitStatus('stopped')
  }

  function connect() {
    if (stopped || connecting) return
    if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return
    connecting = true
    clearConnectTimer()
    emitStatus('connecting')

    let socket
    try {
      socket = new WebSocket(buildWsUrl(wsUrl, accessToken))
    } catch (e) {
      connecting = false
      emitStatus('error', { error: e?.message || String(e) })
      scheduleReconnect()
      return
    }
    ws = socket

    // 自管握手超时：到期仍未 OPEN 则强断，触发 onClose 走重连
    connectTimer = setTimeout(() => {
      if (socket.readyState !== WebSocket.OPEN) {
        try {
          socket.terminate()
        } catch {
          /* ignore */
        }
      }
    }, Math.max(1000, connectTimeoutMs))

    socket.on('open', onOpen)
    socket.on('message', onRawMessage)
    socket.on('close', onClose)
    socket.on('error', onError)
  }

  function onOpen() {
    connecting = false
    clearConnectTimer()
    reconnectAttempts = 0
    emitStatus('connected')
    // 拉取自身 QQ 号，确认登录态；失败仍保持连接（消息照收）
    sendApi('get_login_info')
      .then((data) => {
        selfId = data && data.user_id != null ? String(data.user_id) : ''
        if (selfId) {
          emitStatus('ready', { selfId })
          // 已登录：启动 30s 慢速 watchdog，监测 QQ 是否被踢下线
          clearSelfIdProbe()
          selfIdProbeTimer = setInterval(probeSelfId, 30000)
        } else {
          // 未登录：开启补探，等用户登录 QQ 后自动升 ready（修复登录后状态长时间不变绿）
          startSelfIdProbe()
        }
      })
      .catch((e) => {
        console.warn('[qqBridge] get_login_info failed:', e?.message || e)
        startSelfIdProbe()
      })
  }

  function onRawMessage(raw) {
    let payload
    try {
      payload = JSON.parse(raw.toString('utf-8'))
    } catch {
      return
    }
    // API 响应：按 echo 配对兑现
    const echo = payload?.echo
    if (echo != null && pending.has(echo)) {
      const entry = pending.get(echo)
      pending.delete(echo)
      clearTimeout(entry.timer)
      if (payload.retcode != null && payload.retcode !== 0) {
        entry.reject(
          new Error(`napcat api error: retcode=${payload.retcode} ${payload.msg || payload.wording || ''}`.trim()),
        )
      } else {
        entry.resolve(payload.data)
      }
      return
    }
    // 事件：仅上抛消息事件
    if (payload.post_type === 'message') {
      try {
        onMessage?.(payload)
      } catch (e) {
        console.warn('[qqBridge] onMessage handler error:', e?.message || e)
      }
      return
    }
    // meta_event / notice：检测 QQ 下线（被踢/主动下线/网络断开）
    // NapCat 在 QQ 被踢时会发 lifecycle disable 或自定义 notice，get_login_info 可能仍返回缓存 user_id，
    // 故必须靠事件检测，不能只靠轮询。
    // 注意：sub_type 里的 'kick' 不能裸匹配——group_decrease 群成员被踢的 sub_type 也是 'kick'，
    // 那是群事件不是机器人掉线。只匹配 notice_type 含 offline、或 sub_type 为 disable/offline。
    if (payload.post_type === 'meta_event' || payload.post_type === 'notice') {
      const sub = payload.sub_type || ''
      const ntype = payload.notice_type || ''
      const isOffline = (
        (payload.meta_event_type === 'lifecycle' && sub === 'disable') ||
        /offline/i.test(ntype) ||
        /offline|disable/i.test(sub)
      )
      if (isOffline && selfId) {
        console.warn('[napcat] QQ offline event:', payload.meta_event_type || ntype, sub)
        selfId = ''
        probeEpoch++ // 使可能 in-flight 的 get_login_info 响应作废，防止撤销掉线检测
        clearSelfIdProbe()
        emitStatus('connected', { kicked: true })
        selfIdProbeTimer = setInterval(probeSelfId, 5000)
      }
      return
    }
  }

  function onClose(code, reasonBuf) {
    connecting = false
    clearConnectTimer()
    clearSelfIdProbe()
    failPending(new Error('napcat ws closed'))
    const reason = reasonBuf ? reasonBuf.toString('utf-8') : ''
    if (!stopped) {
      emitStatus('disconnected', { code, reason })
      scheduleReconnect()
    }
  }

  function onError(err) {
    // 'close' 会随后触发并负责重连；此处仅记录，避免重复调度
    console.warn('[qqBridge] ws error:', err?.message || err)
    connecting = false
    clearConnectTimer()
  }

  function scheduleReconnect() {
    if (stopped) return
    clearReconnectTimer()
    const exp = Math.min(reconnectBaseMs * (1 << Math.min(reconnectAttempts, 5)), reconnectMaxMs)
    reconnectAttempts += 1
    emitStatus('reconnecting', { attempt: reconnectAttempts, delayMs: exp })
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null
      connect()
    }, exp)
  }

  function sendApi(action, params = {}, timeoutMs = API_TIMEOUT_MS) {
    return new Promise((resolve, reject) => {
      if (!ws || ws.readyState !== WebSocket.OPEN) {
        reject(new Error(`napcat ws not open: ${action}`))
        return
      }
      const echo = String(++echoSeq)
      const timer = setTimeout(() => {
        if (pending.delete(echo)) reject(new Error(`napcat api timeout: ${action}`))
      }, timeoutMs)
      pending.set(echo, { resolve, reject, timer })
      try {
        ws.send(JSON.stringify({ action, params, echo }))
      } catch (e) {
        pending.delete(echo)
        clearTimeout(timer)
        reject(e)
      }
    })
  }

  function sendPrivateMsg(userId, text) {
    return sendApi('send_private_msg', {
      user_id: Number(userId),
      message: [{ type: 'text', data: { text } }],
    })
  }

  function sendGroupMsg(groupId, text) {
    return sendApi('send_group_msg', {
      group_id: Number(groupId),
      message: [{ type: 'text', data: { text } }],
    })
  }

  // 发送图片（OneBot 11 image 段）：NapCat 支持 base64:// 内联，免去公网回拉。
  // base64 须不含 data:image/... 前缀，仅裸 base64。
  function sendPrivateMsgImage(userId, base64) {
    return sendApi('send_private_msg', {
      user_id: Number(userId),
      message: [{ type: 'image', data: { file: `base64://${base64}` } }],
    })
  }

  function sendGroupMsgImage(groupId, base64) {
    return sendApi('send_group_msg', {
      group_id: Number(groupId),
      message: [{ type: 'image', data: { file: `base64://${base64}` } }],
    })
  }

  function getSelfId() {
    return selfId
  }

  function getStatus() {
    return { state: currentState, selfId }
  }

  return { start, stop, sendApi, sendPrivateMsg, sendGroupMsg, sendPrivateMsgImage, sendGroupMsgImage, getSelfId, getStatus }
}
