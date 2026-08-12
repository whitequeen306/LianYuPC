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

/** 回传一次工具调用结果 */
export function postAgentToolResult({ requestId, ok, content, error }) {
  return http.post('/agent-bridge/result', { requestId, ok, content, error })
}
