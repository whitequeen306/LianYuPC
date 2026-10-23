import http from './httpCore'

/** 注册/整体替换本地工具清单（桌面端 MCP 服务就绪后调用） */
export function registerAgentTools(tools) {
  return http.post('/agent-bridge/tools', { tools })
}

/** 桥心跳（30s 一次维持在线） */
export function agentBridgeHeartbeat() {
  return http.post('/agent-bridge/heartbeat')
}

/** 注销工具桥（MCP 服务停止/退出登录时调用） */
export function unregisterAgentBridge() {
  return http.delete('/agent-bridge')
}

/**
 * 回传一次工具调用结果。
 *
 * 能力层（desk_*）会额外带 `images`（截图帧，只给视觉分析用）与 `policy`（本地策略结论）；
 * 两者都可省略——旧引擎的结果形状仍然成立。
 */
export function postAgentToolResult({ requestId, ok, content, error, images, policy }) {
  const payload = { requestId, ok, content, error }
  if (Array.isArray(images) && images.length) payload.images = images
  if (policy && typeof policy === 'object') payload.policy = policy
  return http.post('/agent-bridge/result', payload)
}
