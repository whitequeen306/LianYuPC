/**
 * OneBot 11 消息段解析 — 抽取文本/图片，并解析回发目标与发送者。
 * 字段与后端 OneBotModels.MessageEvent 对齐：message 段数组、message_type、user_id、group_id、sender。
 */

/** 从消息段中抽取纯文本内容与首张图片；@机器人 本身的段会被丢弃。
 *  图片同时保留 data.url（http 直链，可能本地/localhost）与 data.file（base64:// 或 file:// 路径），
 *  供桥接按最佳来源下载字节后转传后端。 */
export function extractMessageContent(event, selfId = '') {
  const segments = Array.isArray(event?.message) ? event.message : []
  let text = ''
  let imageUrl = ''
  let imageFile = ''
  for (const seg of segments) {
    if (!seg || typeof seg !== 'object') continue
    const type = seg.type
    const data = seg.data || {}
    if (type === 'text' && typeof data.text === 'string') {
      text += data.text
    } else if (type === 'at') {
      const qq = String(data.qq ?? '')
      if (selfId && qq === selfId) continue // 丢弃 @机器人 本身
      if (qq) text += `@${qq} `
    } else if (type === 'reply') {
      continue // 引用回复：保留触发，不展开原消息
    } else if (type === 'image' && !imageUrl && !imageFile) {
      if (typeof data.url === 'string' && data.url) imageUrl = data.url
      if (typeof data.file === 'string' && data.file) imageFile = data.file
    }
  }
  text = text.trim()
  // 无段文本时退回 raw_message（NapCat 会同时给字符串形式）
  if (!text && !imageUrl && !imageFile && typeof event?.raw_message === 'string') {
    text = event.raw_message.trim()
  }
  return { text, imageUrl, imageFile }
}

/** 解析回发目标：群消息回群，否则回私聊 */
export function resolveReplyTarget(event) {
  if (event?.message_type === 'group') {
    return { kind: 'group', groupId: Number(event.group_id) }
  }
  return { kind: 'private', userId: Number(event.user_id) }
}

/** 解析发送者身份，用于白名单与日志 */
export function resolveSender(event) {
  return {
    userId: Number(event?.user_id) || 0,
    groupId: Number(event?.group_id) || 0,
    messageType: event?.message_type || '',
    nickname: event?.sender?.nickname || '',
  }
}
