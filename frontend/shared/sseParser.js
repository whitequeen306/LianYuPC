/**
 * SSE 响应解析器 —— 全仓唯一实现（单源）。
 *
 * 后端流式端点契约（与 Java SseChatStreamHelper 一致）：
 *   {"content":"<delta>"}                        逐 token 增量
 *   {"replace":"<全文>"}                          语言门重写后的整体替换
 *   {"replace":"<规范化>","pieces":["段1",...]}    beforeStreamComplete 的分段
 *   {"error":"<msg>"}                            失败收尾（随后仍有 [DONE]）
 *   [DONE]                                       终止标记
 *   : keep-alive 注释行                          心跳
 *
 * 本模块聚合三处历史实现（渲染进程 assistantStreamDrain / 主进程 qqBridge / wechatBridge）
 * 的语义并集，分叉行为以最完整的 qqBridge 版为准：
 *   - 块分隔用 /\r?\n\r?\n/（兼容 CRLF）
 *   - 跳过 ':' 注释行（心跳）
 *   - 多行 data: 拼接为一个事件
 *   - pieces/replace/content/error 四字段同帧出现时全部生效（与 Java 写入顺序一致）
 *
 * 消费方：
 *   - src/utils/assistantStreamDrain.js（渲染进程，流式增量消费）
 *   - electron/qqBridge/qqBridge.js（主进程，整流解析）
 *   - electron/wechatBridge/wechatBridge.js（主进程，整流解析）
 */

/**
 * 解析一行 SSE data 的 payload，返回应应用的字段更新。
 * 返回 null 表示该行可忽略（空 / [DONE] / 非 JSON）。
 * @param {string} data data: 后的原始字符串（多行已拼接）
 * @returns {{ content?: string, replace?: string, pieces?: string[], error?: string } | null}
 */
export function parseSseDataPayload(data) {
  if (typeof data !== 'string') return null
  const trimmed = data.trim()
  if (!trimmed || trimmed === '[DONE]') return null
  let obj
  try {
    obj = JSON.parse(trimmed)
  } catch {
    return null
  }
  if (!obj || typeof obj !== 'object') return null
  const out = {}
  if (typeof obj.error === 'string' && obj.error) out.error = obj.error
  if (typeof obj.content === 'string' && obj.content) out.content = obj.content
  if (typeof obj.replace === 'string') out.replace = obj.replace
  if (Array.isArray(obj.pieces) && obj.pieces.length) {
    out.pieces = obj.pieces.map((s) => String(s ?? ''))
  }
  return Object.keys(out).length ? out : null
}

/**
 * 把一段原始 SSE 文本解析为聚合结果（整流模式：主进程桥用）。
 * @param {string} raw
 * @returns {{ pieces: string[], replace: string, content: string, error: string }}
 */
export function parseSseStream(raw) {
  const out = { pieces: [], replace: '', content: '', error: '' }
  if (typeof raw !== 'string' || !raw) return out
  for (const block of raw.split(/\r?\n\r?\n/)) {
    const dataLines = []
    for (const line of block.split(/\r?\n/)) {
      if (line.startsWith(':')) continue // 心跳注释行
      if (line.startsWith('data:')) dataLines.push(line.slice(5).replace(/^ /, ''))
    }
    if (!dataLines.length) continue
    const applied = parseSseDataPayload(dataLines.join('\n'))
    if (!applied) continue
    if (Array.isArray(applied.pieces)) out.pieces = applied.pieces
    if (typeof applied.replace === 'string') out.replace = applied.replace
    if (typeof applied.content === 'string') out.content += applied.content
    if (typeof applied.error === 'string' && applied.error) out.error = applied.error
  }
  return out
}

/**
 * 流式增量折叠器（渲染进程用）：逐行喂入，实时累积 fullContent/pieces。
 * @returns {{ feed(line: string): void, result(): { fullContent: string, pieces: string[] | null } }}
 */
export function createSseAccumulator() {
  let fullContent = ''
  let serverPieces = null
  return {
    feed(line) {
      const trimmed = String(line || '').trim()
      if (!trimmed.startsWith('data:')) return
      const applied = parseSseDataPayload(trimmed.slice(5).trim())
      if (!applied) return
      if (applied.error) {
        throw new Error(applied.error)
      }
      if (applied.content) {
        fullContent += applied.content
      }
      if (typeof applied.replace === 'string') {
        fullContent = applied.replace
      }
      if (Array.isArray(applied.pieces) && applied.pieces.length) {
        serverPieces = applied.pieces.map((p) => String(p ?? '').trim()).filter(Boolean)
      }
    },
    result() {
      return { fullContent, pieces: serverPieces }
    },
  }
}
