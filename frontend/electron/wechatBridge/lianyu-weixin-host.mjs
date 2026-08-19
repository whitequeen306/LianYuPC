#!/usr/bin/env node
/**
 * LianYu WeChat ClawBot host — iLink HTTP only (no OpenClaw agent).
 * Stdin/stdout: one JSON object per line. Credentials on disk, never printed.
 *
 * Official endpoints (Tencent @tencent-weixin/openclaw-weixin README):
 *   GET  ilink/bot/get_bot_qrcode
 *   GET  ilink/bot/get_qrcode_status
 *   POST ilink/bot/getupdates | sendmessage | getconfig | sendtyping
 *        | msg/notifystart | msg/notifystop
 */
import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import readline from 'node:readline'
import { fileURLToPath } from 'node:url'
import {
  buildWeixinSendMessage,
  HOST_CMD,
  TYPING_HARD_CAP_MS,
  TYPING_KEEPALIVE_MS,
  TYPING_TICKET_TTL_MS,
} from './wechatProtocol.js'

const DEFAULT_BASE = 'https://ilinkai.weixin.qq.com'
const ILINK_APP_ID = 'bot'
const PLUGIN_VERSION = '2.4.6'
const BOT_AGENT = process.env.LIANYU_BOT_AGENT || 'LianYu/0.2.353'

function buildClientVersion(version) {
  const parts = String(version).split('.').map((p) => parseInt(p, 10) || 0)
  return ((parts[0] & 0xff) << 16) | ((parts[1] & 0xff) << 8) | (parts[2] & 0xff)
}

const ILINK_APP_CLIENT_VERSION = String(buildClientVersion(PLUGIN_VERSION))

function emit(obj) {
  process.stdout.write(`${JSON.stringify(obj)}\n`)
}

function parseArgs(argv) {
  const out = { cred: '', state: '', base: DEFAULT_BASE }
  for (let i = 2; i < argv.length; i += 1) {
    const a = argv[i]
    const next = argv[i + 1]
    if (a === '--cred' && next) { out.cred = next; i += 1 }
    else if (a === '--state' && next) { out.state = next; i += 1 }
    else if (a === '--base' && next) { out.base = next; i += 1 }
  }
  return out
}

function ensureTrailingSlash(url) {
  return url.endsWith('/') ? url : `${url}/`
}

function randomWechatUin() {
  const uint32 = crypto.randomBytes(4).readUInt32BE(0)
  return Buffer.from(String(uint32), 'utf-8').toString('base64')
}

function commonHeaders() {
  return {
    'iLink-App-Id': ILINK_APP_ID,
    'iLink-App-ClientVersion': ILINK_APP_CLIENT_VERSION,
  }
}

function authHeaders(token) {
  const headers = {
    'Content-Type': 'application/json',
    AuthorizationType: 'ilink_bot_token',
    'X-WECHAT-UIN': randomWechatUin(),
    ...commonHeaders(),
  }
  if (token) headers.Authorization = `Bearer ${token}`
  return headers
}

function buildBaseInfo() {
  return { channel_version: PLUGIN_VERSION, bot_agent: BOT_AGENT }
}

async function apiGet(baseUrl, endpoint, timeoutMs = 20000) {
  const url = new URL(endpoint, ensureTrailingSlash(baseUrl))
  const controller = new AbortController()
  const t = setTimeout(() => controller.abort(), timeoutMs)
  try {
    const res = await fetch(url.toString(), { method: 'GET', headers: commonHeaders(), signal: controller.signal })
    const text = await res.text()
    if (!res.ok) throw new Error(`GET ${endpoint} ${res.status}`)
    return JSON.parse(text)
  } finally {
    clearTimeout(t)
  }
}

async function apiPost(baseUrl, endpoint, body, token, timeoutMs = 20000) {
  const url = new URL(endpoint, ensureTrailingSlash(baseUrl))
  const controller = new AbortController()
  const t = setTimeout(() => controller.abort(), timeoutMs)
  try {
    const res = await fetch(url.toString(), {
      method: 'POST',
      headers: authHeaders(token),
      body: JSON.stringify({ ...body, base_info: buildBaseInfo() }),
      signal: controller.signal,
    })
    const text = await res.text()
    if (!res.ok) throw new Error(`POST ${endpoint} ${res.status}`)
    return text ? JSON.parse(text) : {}
  } finally {
    clearTimeout(t)
  }
}

function readJson(filePath) {
  try {
    return JSON.parse(fs.readFileSync(filePath, 'utf8'))
  } catch {
    return null
  }
}

function writeJson(filePath, obj) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true })
  fs.writeFileSync(filePath, JSON.stringify(obj, null, 2))
}

async function qrDataUrl(content) {
  try {
    const QRCode = (await import('qrcode')).default
    return await QRCode.toDataURL(content, { width: 280, margin: 2, errorCorrectionLevel: 'M' })
  } catch {
    return ''
  }
}

function aes128EcbDecrypt(buf, keyRaw) {
  if (!buf || !buf.length || !keyRaw) return buf
  let key = Buffer.isBuffer(keyRaw) ? keyRaw : Buffer.from(String(keyRaw), 'base64')
  if (key.length !== 16) {
    try {
      const hex = Buffer.from(String(keyRaw), 'hex')
      if (hex.length === 16) key = hex
    } catch {
      return buf
    }
  }
  if (key.length !== 16) return buf
  try {
    const decipher = crypto.createDecipheriv('aes-128-ecb', key, null)
    decipher.setAutoPadding(true)
    return Buffer.concat([decipher.update(buf), decipher.final()])
  } catch {
    return buf
  }
}

function sniffMime(buf) {
  if (!buf || buf.length < 4) return 'image/jpeg'
  if (buf[0] === 0x89 && buf[1] === 0x50) return 'image/png'
  if (buf[0] === 0x47 && buf[1] === 0x49) return 'image/gif'
  if (buf[0] === 0xff && buf[1] === 0xd8) return 'image/jpeg'
  if (buf[0] === 0x52 && buf[1] === 0x49) return 'image/webp'
  return 'image/jpeg'
}

async function downloadImageItem(item) {
  const image = item?.image_item
  if (!image) return null
  const media = image.media || {}
  const url = image.url || media.full_url || ''
  if (!url || !/^https?:\/\//i.test(url)) return null
  const controller = new AbortController()
  const t = setTimeout(() => controller.abort(), 20000)
  try {
    const res = await fetch(url, { signal: controller.signal, redirect: 'follow' })
    if (!res.ok) return null
    let buf = Buffer.from(await res.arrayBuffer())
    const key = image.aeskey || media.aes_key
    if (key) buf = aes128EcbDecrypt(buf, key)
    if (buf.length > 5 * 1024 * 1024) return null
    return { base64: buf.toString('base64'), mime: sniffMime(buf) }
  } catch {
    return null
  } finally {
    clearTimeout(t)
  }
}

function extractText(msg) {
  const items = Array.isArray(msg?.item_list) ? msg.item_list : []
  let text = ''
  for (const it of items) {
    if (it?.type === 1 && it.text_item?.text) text += it.text_item.text
  }
  return text.trim()
}

const args = parseArgs(process.argv)
const DEFAULT_POLL_TIMEOUT_MS = 35000
const POLL_OK_GAP_MS = 50
const POLL_ERR_GAP_MS = 1000
const POLL_TIMEOUT_MIN_MS = 5000
const POLL_TIMEOUT_MAX_MS = 120000

let credPath = args.cred
let statePath = args.state
let baseUrl = args.base || DEFAULT_BASE
let token = ''
let pollBuf = ''
let running = true
let polling = false
let pollTimeoutMs = DEFAULT_POLL_TIMEOUT_MS
const typingTickets = new Map()
const typingSessions = new Map()

function hostLog(message) {
  process.stderr.write(`[weixin-host] ${message}\n`)
}

function loadCreds() {
  const cred = readJson(credPath)
  if (cred?.botToken) {
    token = String(cred.botToken)
    if (cred.baseUrl) baseUrl = String(cred.baseUrl)
  }
  const state = readJson(statePath)
  if (state?.getUpdatesBuf) pollBuf = String(state.getUpdatesBuf)
}

function saveCreds(extra = {}) {
  const prev = readJson(credPath) || {}
  writeJson(credPath, {
    ...prev,
    botToken: token,
    baseUrl,
    savedAt: new Date().toISOString(),
    ...extra,
  })
}

function saveState() {
  writeJson(statePath, { getUpdatesBuf: pollBuf, savedAt: new Date().toISOString() })
}

async function doLogin() {
  emit({ type: 'status', phase: 'login' })
  const qr = await apiGet(baseUrl, `ilink/bot/get_bot_qrcode?bot_type=${encodeURIComponent('3')}`, 20000)
  const key = qr.qrcode || ''
  const content = qr.qrcode_img_content || qr.qrcode_url || ''
  if (!key || !content) throw new Error('qrcode missing')
  const dataUrl = await qrDataUrl(content)
  emit({ type: 'qr', dataUrl: dataUrl || undefined, text: dataUrl ? undefined : content })

  const deadline = Date.now() + 8 * 60 * 1000
  while (Date.now() < deadline && running) {
    await new Promise((r) => setTimeout(r, 2000))
    const st = await apiGet(baseUrl, `ilink/bot/get_qrcode_status?qrcode=${encodeURIComponent(key)}`, 35000)
    const status = String(st.status || '')
    if (status === 'expired') throw new Error('qr expired')
    if (st.bot_token) {
      token = String(st.bot_token)
      if (st.baseurl) baseUrl = String(st.baseurl)
      saveCreds()
      emit({ type: 'logged_in' })
      return
    }
  }
  throw new Error('login timeout')
}

function emitInbound(msg, text, image) {
  if (!running) return
  if (!text && !image) return
  hostLog(`inbound text=${text ? 'yes' : 'no'} image=${image ? 'yes' : 'no'}`)
  emit({
    type: 'inbound',
    text: text || '',
    imageBase64: image?.base64 || '',
    mime: image?.mime || '',
    contextToken: msg.context_token || '',
    fromUserId: msg.from_user_id || '',
    toUserId: msg.to_user_id || '',
  })
}

async function pollOnce() {
  const timeout = pollTimeoutMs
  try {
    const resp = await apiPost(
      baseUrl,
      'ilink/bot/getupdates',
      { get_updates_buf: pollBuf || '' },
      token,
      timeout + 5000,
    )
    if (resp.ret && resp.ret !== 0) {
      hostLog(`getupdates ret=${resp.ret}`)
      emit({ type: 'error', message: `getupdates ret=${resp.ret}` })
      return 'error'
    }
    const suggested = Number(resp.longpolling_timeout_ms)
    if (Number.isFinite(suggested) && suggested > 0) {
      pollTimeoutMs = Math.min(POLL_TIMEOUT_MAX_MS, Math.max(POLL_TIMEOUT_MIN_MS, suggested))
    }
    if (typeof resp.get_updates_buf === 'string') {
      pollBuf = resp.get_updates_buf
      saveState()
    }
    const msgs = Array.isArray(resp.msgs) ? resp.msgs : []
    for (const msg of msgs) {
      if (msg.message_type !== 1) continue
      const text = extractText(msg)
      const items = Array.isArray(msg.item_list) ? msg.item_list : []
      const imgItem = items.find((it) => it?.type === 2)
      if (!imgItem) {
        emitInbound(msg, text, null)
        continue
      }
      void downloadImageItem(imgItem)
        .then((image) => emitInbound(msg, text, image))
        .catch(() => emitInbound(msg, text, null))
    }
    return 'ok'
  } catch (err) {
    if (err?.name === 'AbortError') return 'ok'
    emit({ type: 'error', message: String(err?.message || err) })
    return 'error'
  }
}

async function startPoll() {
  if (!token) {
    emit({ type: 'error', message: 'not logged in' })
    return
  }
  if (polling) return
  polling = true
  try {
    await apiPost(baseUrl, 'ilink/bot/msg/notifystart', {}, token, 10000)
  } catch {
    /* optional */
  }
  emit({ type: 'status', phase: 'polling' })
  const loop = async () => {
    while (running) {
      const result = await pollOnce()
      const gap = result === 'error' ? POLL_ERR_GAP_MS : POLL_OK_GAP_MS
      await new Promise((r) => setTimeout(r, gap))
    }
  }
  loop()
    .catch((err) => emit({ type: 'error', message: String(err?.message || err) }))
    .finally(() => { polling = false })
}

async function getTypingTicket(userId, contextToken) {
  const cached = typingTickets.get(userId)
  if (cached?.ticket && cached.expiry > Date.now()) return cached.ticket
  const resp = await apiPost(baseUrl, 'ilink/bot/getconfig', {
    ilink_user_id: userId,
    context_token: contextToken || undefined,
  }, token, 10000)
  const ticket = typeof resp.typing_ticket === 'string' ? resp.typing_ticket : ''
  if (!ticket || (resp.ret && resp.ret !== 0)) {
    throw new Error(`getconfig ret=${resp.ret || 'empty'}`)
  }
  typingTickets.set(userId, { ticket, expiry: Date.now() + TYPING_TICKET_TTL_MS })
  return ticket
}

async function sendTypingStatus(userId, contextToken, status) {
  if (!token || !userId) return
  const ticket = await getTypingTicket(userId, contextToken)
  const resp = await apiPost(baseUrl, 'ilink/bot/sendtyping', {
    ilink_user_id: userId,
    typing_ticket: ticket,
    status,
  }, token, 10000)
  if (resp.ret && resp.ret !== 0) {
    throw new Error(`sendtyping ret=${resp.ret}`)
  }
}

function clearTypingSession(userId) {
  const session = typingSessions.get(userId)
  if (!session) return
  session.cancelled = true
  if (session.interval) clearInterval(session.interval)
  if (session.cap) clearTimeout(session.cap)
  typingSessions.delete(userId)
}

async function startTyping(cmd) {
  const userId = typeof cmd.toUserId === 'string' ? cmd.toUserId : ''
  const contextToken = typeof cmd.contextToken === 'string' ? cmd.contextToken : ''
  if (!userId) return
  clearTypingSession(userId)
  const session = { cancelled: false, interval: null, cap: null }
  typingSessions.set(userId, session)
  try {
    await sendTypingStatus(userId, contextToken, 1)
    if (session.cancelled) return
    session.interval = setInterval(() => {
      void sendTypingStatus(userId, contextToken, 1).catch((err) => {
        hostLog(`typing keepalive failed: ${err?.message || err}`)
      })
    }, TYPING_KEEPALIVE_MS)
    session.cap = setTimeout(() => {
      void stopTyping({ toUserId: userId, contextToken })
    }, TYPING_HARD_CAP_MS)
  } catch (err) {
    hostLog(`typing start failed: ${err?.message || err}`)
    clearTypingSession(userId)
  }
}

async function stopTyping(cmd) {
  const userId = typeof cmd.toUserId === 'string' ? cmd.toUserId : ''
  const contextToken = typeof cmd.contextToken === 'string' ? cmd.contextToken : ''
  if (userId) clearTypingSession(userId)
  try {
    await sendTypingStatus(userId, contextToken, 2)
  } catch (err) {
    hostLog(`typing stop failed: ${err?.message || err}`)
  }
}

async function sendText(cmd) {
  if (!token) throw new Error('not logged in')
  const body = buildWeixinSendMessage(cmd)
  const resp = await apiPost(baseUrl, 'ilink/bot/sendmessage', body, token, 15000)
  if (resp.ret && resp.ret !== 0) {
    throw new Error(`sendmessage ret=${resp.ret}`)
  }
  hostLog('sendmessage ok')
  emit({ type: 'sent' })
}

async function shutdown() {
  running = false
  polling = false
  for (const userId of [...typingSessions.keys()]) {
    clearTypingSession(userId)
  }
  if (token) {
    try {
      await apiPost(baseUrl, 'ilink/bot/msg/notifystop', {}, token, 8000)
    } catch {
      /* ignore */
    }
  }
}

loadCreds()

const rl = readline.createInterface({ input: process.stdin })
let commandTail = Promise.resolve()
rl.on('line', (line) => {
  commandTail = commandTail.then(async () => {
    const raw = String(line || '').trim()
    if (!raw) return
    let cmd
    try {
      cmd = JSON.parse(raw)
    } catch {
      emit({ type: 'error', message: 'bad command' })
      return
    }
    try {
      if (cmd.type === HOST_CMD.LOGIN || cmd.type === 'login') await doLogin()
      else if (cmd.type === HOST_CMD.START_POLL || cmd.type === 'start_poll') await startPoll()
      else if (cmd.type === HOST_CMD.SEND_TEXT || cmd.type === 'send_text') await sendText(cmd)
      else if (cmd.type === HOST_CMD.TYPING_START || cmd.type === 'typing_start') {
        void startTyping(cmd).catch((err) => hostLog(`typing_start: ${err?.message || err}`))
      }
      else if (cmd.type === HOST_CMD.TYPING_STOP || cmd.type === 'typing_stop') {
        void stopTyping(cmd).catch((err) => hostLog(`typing_stop: ${err?.message || err}`))
      }
      else if (cmd.type === HOST_CMD.STOP || cmd.type === 'stop') {
        await shutdown()
        process.exit(0)
      }
    } catch (err) {
      hostLog(`cmd ${cmd?.type || '?'} failed: ${err?.message || err}`)
      emit({ type: 'error', message: String(err?.message || err) })
    }
  })
})

process.on('SIGINT', () => { shutdown().finally(() => process.exit(0)) })
process.on('SIGTERM', () => { shutdown().finally(() => process.exit(0)) })

void fileURLToPath
