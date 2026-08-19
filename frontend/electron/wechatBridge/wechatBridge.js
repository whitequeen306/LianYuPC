/**
 * WeChat inbound turns → LianYu conversation; outbound text back to ClawBot.
 */
import { extractInboundPayload, pickLatestAssistant, shouldSkipWechatProactive } from './wechatProtocol.js'
import { readWechatBridgeSettings } from './wechatBridgeSettings.js'

const INPUT_IMAGE_MAX_BYTES = 5 * 1024 * 1024
let session = {
  apiOrigin: '',
  authToken: '',
  performApiRequest: null,
  host: null,
  log: () => {},
}
let inflight = Promise.resolve()
let lastPeer = { toUserId: '', contextToken: '' }
let pendingTurns = 0

export function configureWechatBridge(deps) {
  session = {
    apiOrigin: deps.apiOrigin || '',
    authToken: deps.authToken || '',
    performApiRequest: deps.performApiRequest,
    host: deps.host || null,
    log: deps.log || (() => {}),
  }
  pendingTurns = 0
}

export function getWechatLastPeer() {
  return { ...lastPeer }
}

export function rememberWechatPeer({ toUserId, contextToken }) {
  if (toUserId) lastPeer.toUserId = toUserId
  if (contextToken) lastPeer.contextToken = contextToken
}

export function clearWechatPeer() {
  lastPeer = { toUserId: '', contextToken: '' }
  pendingTurns = 0
}

function setTyping(on, inbound) {
  const toUserId = inbound?.toUserId || lastPeer.toUserId
  const contextToken = inbound?.contextToken || lastPeer.contextToken
  if (!toUserId) return
  session.host?.setTyping?.({ toUserId, contextToken, on })
}

function unwrap(res, path) {
  if (!res || res.status < 200 || res.status >= 300) throw new Error(`api:${path} HTTP ${res?.status}`)
  const body = typeof res.data === 'string' ? JSON.parse(res.data || '{}') : (res.data || {})
  if (typeof body.code === 'number') {
    if (body.code !== 200) throw new Error(`api:${path} code ${body.code}`)
    return body.data
  }
  return body
}

function parseSseStream(raw) {
  const out = { pieces: [], replace: '', content: '', error: '' }
  const text = String(raw || '')
  for (const block of text.split(/\n\n+/)) {
    const dataLines = block.split('\n').filter((l) => l.startsWith('data:')).map((l) => l.slice(5).trim())
    const payload = dataLines.join('\n')
    if (!payload || payload === '[DONE]') continue
    let obj
    try { obj = JSON.parse(payload) } catch { continue }
    if (Array.isArray(obj.pieces) && obj.pieces.length) out.pieces = obj.pieces.map((s) => String(s ?? ''))
    if (typeof obj.replace === 'string') out.replace = obj.replace
    if (typeof obj.content === 'string') out.content += obj.content
    if (typeof obj.error === 'string' && obj.error) out.error = obj.error
  }
  return out
}

function buildMultipartBuffer(fieldName, filename, buf, mime) {
  const boundary = '----wechatbridge' + Math.random().toString(16).slice(2)
  const head = Buffer.from(
    `--${boundary}\r\nContent-Disposition: form-data; name="${fieldName}"; filename="${filename}"\r\nContent-Type: ${mime}\r\n\r\n`,
  )
  const tail = Buffer.from(`\r\n--${boundary}--\r\n`)
  return { boundary, body: Buffer.concat([head, buf, tail]) }
}

async function uploadImageBase64(imageBase64, mime) {
  const buf = Buffer.from(imageBase64, 'base64')
  if (!buf.length) throw new Error('empty image')
  if (buf.length > INPUT_IMAGE_MAX_BYTES) throw new Error('image too large')
  const ext = mime.includes('png') ? 'png' : mime.includes('gif') ? 'gif' : mime.includes('webp') ? 'webp' : 'jpg'
  const { boundary, body } = buildMultipartBuffer('file', `image.${ext}`, buf, mime || 'image/jpeg')
  const res = await session.performApiRequest({
    method: 'POST',
    url: `${session.apiOrigin}/api/conversation/chat-image`,
    apiOrigin: session.apiOrigin,
    authToken: session.authToken,
    headers: { 'Content-Type': `multipart/form-data; boundary=${boundary}` },
    body,
    timeoutMs: 30000,
  })
  const data = unwrap(res, '/conversation/chat-image')
  const imageUrl = data?.imageUrl || data?.url
  if (!imageUrl) throw new Error('chat-image returned no imageUrl')
  return imageUrl
}

async function relayTurn(inbound) {
  const settings = readWechatBridgeSettings()
  const conversationId = settings.binding?.conversationId
  const provider = (settings.binding?.provider || '').trim()
  if (!conversationId) {
    session.log('[wechatBridge] skip: no conversation bound')
    return
  }
  if (!provider || provider.toLowerCase() === 'platform') {
    session.log('[wechatBridge] skip: provider must be user vault')
    session.host?.sendText({
      toUserId: inbound.toUserId,
      contextToken: inbound.contextToken,
      text: '请先在恋语设置中配置自己的文本模型，再使用微信 ClawBot',
    })
    return
  }

  let imageUrl = ''
  if (inbound.imageBase64) {
    try {
      imageUrl = await uploadImageBase64(inbound.imageBase64, inbound.mime)
    } catch (e) {
      session.log(`[wechatBridge] image upload failed: ${e?.message || e}`)
    }
  }

  const body = {
    provider,
    content: inbound.text || '',
    ...(imageUrl ? { imageUrl } : {}),
    ...(settings.binding?.model ? { model: settings.binding.model } : {}),
  }
  const res = await session.performApiRequest({
    method: 'POST',
    url: `${session.apiOrigin}/api/conversation/${conversationId}/messages/stream`,
    apiOrigin: session.apiOrigin,
    authToken: session.authToken,
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body,
    timeoutMs: settings.reply?.timeoutMs || 120000,
  })
  if (res.status !== 200) {
    session.log(`[wechatBridge] cloud reject ${res.status}`)
    const fallback = settings.reply?.fallbackText
    if (fallback) {
      session.host?.sendText({
        toUserId: inbound.toUserId,
        contextToken: inbound.contextToken,
        text: fallback,
      })
    }
    return
  }
  const sse = parseSseStream(res.data)
  let pieces = []
  if (sse.pieces.length) pieces = sse.pieces.map((s) => s.trim()).filter(Boolean)
  else if (sse.replace.trim()) pieces = [sse.replace.trim()]
  else if (sse.content.trim()) pieces = [sse.content.trim()]
  for (const text of pieces) {
    session.host?.sendText({
      toUserId: inbound.toUserId,
      contextToken: inbound.contextToken,
      text,
    })
  }
}

export function handleWechatHostMessage(msg) {
  const inbound = extractInboundPayload(msg)
  if (!inbound) return
  rememberWechatPeer({
    toUserId: inbound.fromUserId || inbound.toUserId,
    contextToken: inbound.contextToken,
  })
  const replyTo = inbound.fromUserId || inbound.toUserId
  const turn = { ...inbound, toUserId: replyTo }
  pendingTurns += 1
  if (pendingTurns === 1) setTyping(true, turn)
  inflight = inflight.then(async () => {
    try {
      await relayTurn(turn)
    } catch (e) {
      session.log(`[wechatBridge] turn failed: ${e?.message || e}`)
    } finally {
      pendingTurns = Math.max(0, pendingTurns - 1)
      if (pendingTurns === 0) setTyping(false, turn)
    }
  })
}

export async function fanoutWechatProactive(payload = {}) {
  try {
    const settings = readWechatBridgeSettings()
    const boundId = String(settings.binding?.conversationId || '')
    const incomingId = payload?.conversationId != null ? String(payload.conversationId) : ''
    if (!boundId || !incomingId || boundId !== incomingId) return { ok: false, reason: 'conversation_mismatch' }
    if (!session.host?.getStatus?.().running) return { ok: false, reason: 'host_stopped' }
    if (!lastPeer.contextToken || !lastPeer.toUserId) return { ok: false, reason: 'no_context_token' }
    if (!session.performApiRequest || !session.apiOrigin) return { ok: false, reason: 'no_api' }

    const res = await session.performApiRequest({
      method: 'GET',
      url: `${session.apiOrigin}/api/conversation/${boundId}/messages?limit=20`,
      apiOrigin: session.apiOrigin,
      authToken: session.authToken,
      timeoutMs: 15000,
    })
    const data = unwrap(res, '/messages')
    const records = data?.records || data || []
    const latest = pickLatestAssistant(records)
    if (shouldSkipWechatProactive(latest)) return { ok: false, reason: 'enter_voice' }
    const text = String(latest?.content || payload.preview || '').trim()
    if (!text) return { ok: false, reason: 'empty' }
    session.host.sendText({
      toUserId: lastPeer.toUserId,
      contextToken: lastPeer.contextToken,
      text,
    })
    return { ok: true }
  } catch (e) {
    session.log(`[wechatBridge] proactive fanout failed: ${e?.message || e}`)
    return { ok: false, reason: 'send_failed' }
  }
}
