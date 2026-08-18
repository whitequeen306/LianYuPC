/**
 * WeChat ClawBot host line protocol + proactive filters.
 * Host stdin/stdout is one JSON object per line. Tokens never belong in logs.
 */
import crypto from 'node:crypto'

export const HOST_MSG = {
  QR: 'qr',
  LOGGED_IN: 'logged_in',
  INBOUND: 'inbound',
  SENT: 'sent',
  ERROR: 'error',
  STATUS: 'status',
}

export const HOST_CMD = {
  LOGIN: 'login',
  START_POLL: 'start_poll',
  SEND_TEXT: 'send_text',
  STOP: 'stop',
}

export function parseHostLine(line) {
  const raw = String(line || '').trim()
  if (!raw) return null
  try {
    const obj = JSON.parse(raw)
    if (!obj || typeof obj !== 'object' || typeof obj.type !== 'string') return null
    return obj
  } catch {
    return null
  }
}

export function encodeHostCommand(cmd) {
  if (!cmd || typeof cmd !== 'object') return ''
  return `${JSON.stringify(cmd)}\n`
}

/** iLink sendmessage body. Unique client_id per send — missing it makes later replies vanish. */
export function buildWeixinSendMessage({ text, toUserId, contextToken }) {
  const bodyText = typeof text === 'string' ? text : ''
  const to = typeof toUserId === 'string' ? toUserId : ''
  const token = typeof contextToken === 'string' ? contextToken : ''
  if (!bodyText.trim()) throw new Error('empty text')
  if (!to || !token) throw new Error('missing context')
  return {
    msg: {
      from_user_id: '',
      to_user_id: to,
      client_id: `lianyu-${crypto.randomBytes(8).toString('hex')}`,
      message_type: 2,
      message_state: 2,
      context_token: token,
      item_list: [{ type: 1, text_item: { text: bodyText } }],
    },
  }
}

export function extractInboundPayload(msg) {
  if (!msg || msg.type !== HOST_MSG.INBOUND) return null
  const text = typeof msg.text === 'string' ? msg.text.trim() : ''
  const imageBase64 = typeof msg.imageBase64 === 'string' ? msg.imageBase64 : ''
  const mime = typeof msg.mime === 'string' && msg.mime ? msg.mime : 'image/jpeg'
  const contextToken = typeof msg.contextToken === 'string' ? msg.contextToken : ''
  const fromUserId = typeof msg.fromUserId === 'string' ? msg.fromUserId : ''
  const toUserId = typeof msg.toUserId === 'string' ? msg.toUserId : ''
  if (!text && !imageBase64) return null
  return { text, imageBase64, mime, contextToken, fromUserId, toUserId }
}

/** Skip App-open "enter" fixed voice when fanning out to ClawBot. */
export function shouldSkipWechatProactive(message) {
  const audio = String(message?.audioUrl || '').replace(/\\/g, '/')
  if (!audio) return false
  return /(^|\/)enter\.wav$/i.test(audio)
}

export function pickLatestAssistant(records) {
  if (!Array.isArray(records) || !records.length) return null
  const assistants = records.filter((m) => String(m?.role || '').toUpperCase() === 'ASSISTANT')
  if (!assistants.length) return null
  return assistants.reduce((best, cur) => {
    const a = Number(best?.seq) || 0
    const b = Number(cur?.seq) || 0
    return b >= a ? cur : best
  })
}
