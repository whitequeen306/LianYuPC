/**
 * 桌面端 QQ 桥接（B 方案）编排器。
 *
 * 链路：本地 NapCat（正向 WS）收到 OneBot 消息
 *   → 抽取文本/图片
 *   → 调云端 POST /api/conversation/{id}/messages（lianyu-token 鉴权，复用用户登录态）
 *   → 该端点同步返回 AI 回复（data.content）
 *   → 经 NapCat send_private_msg / send_group_msg 回发 QQ。
 *
 * 与 desktopObserver.js 同构：start/stop 对偶、模块级状态、stop 时擦除 token。
 * 云端请求走 performApiRequest（Electron net + 证书 pin）；NapCat 连接走 ws（仅本地）。
 */
import { createNapCatClient } from './napCatClient.js'
import { extractMessageContent, resolveReplyTarget, resolveSender } from './messageExtractor.js'
import { performApiRequest } from '../apiProxy.js'
import { isAllowedByBinding, readQqBridgeSettings, writeQqBridgeSettings, DEFAULTS } from './qqBridgeSettings.js'
import { app } from 'electron'
import path from 'node:path'
import fs from 'node:fs'
import * as logger from '../logger.js'

// 桥接诊断日志：委托给全局 logger（带级别、轮转、全局错误捕获）。排查「发消息收到兜底
// 文案」类问题——记录后端调用的耗时/状态码/异常，定位是 AI 生成超时(>120s)还是连接错误。
// 只记耗时/状态/内容长度，不记 token 与正文，避免泄露。
function log(...args) {
  logger.info('qqBridge', ...args)
}

let client = null
let lastApiOrigin = ''
let lastAuthToken = ''
let lastSettings = null
let onStatus = null
// QQ 单聊会话隔离：sessionMap=userId→conversationId 映射（内存镜像，持久化在 settings.binding.sessionMap）；
// bindingCharacterId=绑定角色 id（懒建 QQ 会话复用，首次反查后缓存）；inflight=per-key 并发锁防重复建会话。
let sessionMap = {}
let bindingCharacterId = ''
let bindingCharacterName = ''
const inflight = {}

export function startQqBridge({ apiOrigin, authToken, settings, onStatus: cb } = {}) {
  stopQqBridge()
  if (!apiOrigin || !authToken || !settings) {
    log('start aborted: missing apiOrigin/authToken/settings')
    return false
  }
  const wsUrl = settings.napcat?.wsUrl
  if (!wsUrl) {
    log('start aborted: napcat.wsUrl not configured')
    return false
  }
  if (!settings.binding?.conversationId && !settings.binding?.characterId) {
    log('start aborted: binding.conversationId/characterId not configured')
    return false
  }
  lastApiOrigin = apiOrigin
  lastAuthToken = authToken
  lastSettings = settings
  // 加载持久化的会话隔离映射 + 绑定角色缓存（重启后复用，免重复建会话/反查）
  sessionMap = { ...((settings.binding || {}).sessionMap || {}) }
  bindingCharacterId = String(settings.binding?.characterId || '')
  bindingCharacterName = settings.binding?.characterName || ''
  onStatus = cb
  client = createNapCatClient({
    wsUrl,
    accessToken: settings.napcat?.accessToken || '',
    connectTimeoutMs: settings.napcat?.connectTimeoutMs || 10000,
    reconnectBaseMs: settings.napcat?.reconnectBaseMs || 600,
    reconnectMaxMs: settings.napcat?.reconnectMaxMs || 6000,
    onMessage: handleOneBotMessage,
    onStatus: (s) => onStatus?.(s),
  })
  client.start()
  return true
}

export function stopQqBridge() {
  if (client) {
    try {
      client.stop()
    } catch {
      /* ignore */
    }
    client = null
  }
  lastAuthToken = ''
  sessionMap = {}
  bindingCharacterId = ''
  bindingCharacterName = ''
  lastSettings = null
  onStatus = null
}

export function getQqBridgeStatus() {
  if (!client) return { state: 'stopped', selfId: '' }
  return client.getStatus()
}

async function handleOneBotMessage(event) {
  // 每条消息重读完整设置（binding 白名单 + reply）：UI 保存后立即对下条消息生效，无需重启桥接。
  // napcat（wsUrl/token）改动仍需重启桥接重连，不在热更新范围。
  const settings = readQqBridgeSettings()
  if (!settings || !client) return

  // 角色绑定热更新：UI 改了 characterId 后，下条消息自动感知并清空旧角色缓存（sessionMap + 角色名），
  // 后续 resolveConversationId 会用新角色创建独占会话。无需重启桥接。
  const newCharacterId = String(settings.binding?.characterId || '')
  if (newCharacterId && newCharacterId !== bindingCharacterId) {
    sessionMap = {}
    bindingCharacterId = newCharacterId
    bindingCharacterName = ''
    persistSessionMap()
    persistBindingCharacterId()
  }

  // 放行判定：allowlist 模式默认拒绝（私聊须命中 allowUsers），open 模式不限制。
  // 见 isAllowedByBinding（issue #11：杜绝空表=静默全放行）
  const sender = resolveSender(event)
  if (!isAllowedByBinding(settings, sender)) return

  // 按 QQ 用户隔离会话：每个 userId 独立 conversationId + 独立上下文，绝不串台。
  // 首次收到该用户时懒建一个复用绑定角色的 SINGLE 会话，存映射后续复用。
  let conversationId = await resolveConversationId(sender)
  if (!conversationId) return

  const selfId = client.getSelfId()
  const { text, imageUrl, imageFile } = extractMessageContent(event, selfId)
  if (!text && !imageUrl && !imageFile) return

  // 入向图片：NapCat 给的 url 多为本地/localhost，云端后端拉不到。先在本地下载字节，
  // 上传后端 /conversation/chat-image 换取可访问 imageUrl 再随消息发出；失败退回原 url。
  let backendImageUrl = ''
  if (imageUrl || imageFile) {
    try {
      backendImageUrl = await uploadInputImage({ url: imageUrl, file: imageFile })
      log('input image uploaded:', String(backendImageUrl).slice(0, 64))
    } catch (e) {
      log('upload input image failed, fallback raw url:', e?.message || e)
      backendImageUrl = imageUrl || '' // 公网 CDN 后端或可直接拉
    }
  }

  const body = {
    provider: settings.binding?.provider || 'platform',
    content: text || '',
    ...(backendImageUrl ? { imageUrl: backendImageUrl } : {}),
    ...(settings.binding?.model ? { model: settings.binding.model } : {}),
  }

  // 走流式端点：非流式 POST /messages 只回 replies.get(0) 第一段，QQ 侧无分段；
  // 流式端点在 [DONE] 前推送 {"pieces":["段1","段2",...]}（后端 assistantReplyService 拆分），
  // buffer 整条 SSE 后解析 pieces，逐条发 QQ 即还原 App 内多气泡分段。纯 bridge 层，后端零改动。
  let replySegments = []
  let relayed = false
  for (let attempt = 0; attempt < 2 && !relayed; attempt++) {
    const t0 = Date.now()
    try {
      const res = await performApiRequest({
        method: 'POST',
        url: `${lastApiOrigin}/api/conversation/${conversationId}/messages/stream`,
        // 出口校验 + token 注入均须走 performApiRequest 的参数：apiOrigin 供 isAllowedEgressUrl
        // 比 host 放行；authToken 由 sanitizeEgressHeaders 统一注入（自填的 lianyu-token header
        // 会被剔除防伪造）。此前两样都漏传 → egress_host_not_allowed(0ms) → 兜底回复。
        apiOrigin: lastApiOrigin,
        authToken: lastAuthToken,
        headers: {
          'Content-Type': 'application/json',
          Accept: 'text/event-stream',
        },
        body,
        timeoutMs: settings.reply?.timeoutMs || 120000,
      })
      const elapsed = Date.now() - t0
      if (res.status !== 200) {
        // 非流式错误信封（限流429/鉴权403/会话不存在404 等）：body 多为 {code,message}
        const parsed = safeJsonParse(res.data)
        log('cloud reject:', res.status, `${elapsed}ms`, parsed?.message || String(res.data || '').slice(0, 120))
        // 故障自动排障：会话不存在(404) → 失效该发送者的会话缓存，重解析（find/create 角色 SINGLE 会话），重试一次
        if (res.status === 404 && attempt === 0) {
          const key = `private:${sender?.userId || 0}`
          delete sessionMap[key]
          const freshId = await resolveConversationId(sender, { force: true })
          if (freshId && freshId !== conversationId) {
            log('auto-recover: re-resolved conversation', freshId, '(was', conversationId + '), retrying message')
            conversationId = freshId
            continue
          }
          log('auto-recover: could not re-resolve conversation (fresh=', freshId || 'null', '), give up')
        }
        break
      }
      // 状态 200：解析整条 SSE 流，取 pieces（后端 beforeStreamComplete 推送的分段）
      const sse = parseSseStream(res.data)
      if (sse.error) {
        log('cloud stream error:', `${elapsed}ms`, sse.error)
        replySegments = settings.reply?.fallbackText ? [settings.reply.fallbackText] : []
        relayed = true
        break
      }
      // 优先 pieces；缺失时回退 replace（规范化全文）或拼接的 delta；皆空则静默
      if (sse.pieces.length) {
        replySegments = sse.pieces.map(s => s.trim()).filter(s => s.length)
      } else if (sse.replace.trim()) {
        replySegments = [sse.replace.trim()]
      } else if (sse.content.trim()) {
        replySegments = [sse.content.trim()]
      }
      const totalChars = replySegments.reduce((n, s) => n + s.length, 0)
      log('cloud ok:', res.status, `${elapsed}ms`, `pieces=${replySegments.length}/${totalChars}字`)
      relayed = true
    } catch (e) {
      // 网络异常/超时：按配置兜底回复一次。记录 elapsed+error 定位是 AI 生成超时(>120s)还是连接错误
      const elapsed = Date.now() - t0
      log('message relay failed:', `${elapsed}ms`, e?.message || String(e))
      replySegments = settings.reply?.fallbackText ? [settings.reply.fallbackText] : []
      relayed = true // 兜底已设定，跳出循环进入回发
    }
  }
  if (!relayed) return // 两次均 reject（非网络异常），静默
  if (!replySegments.length) return // AI 未产出正文且无兜底，静默

  if (!client) return
  const target = resolveReplyTarget(event)
  // 分段逐条发送：条间小幅延迟 + 随机抖动，避免 napcat/QQ 风控且更像真人连发
  // 允许 0（不延迟）：显式取非负有限数，|| 会把 0 当假值误用 500
  const segDelayMs = Number(settings.reply?.segmentDelayMs)
  const delayMs = Number.isFinite(segDelayMs) && segDelayMs >= 0 ? segDelayMs : DEFAULTS.reply.segmentDelayMs
  const segJitterMs = Number(settings.reply?.segmentJitterMs)
  const jitterMs = Number.isFinite(segJitterMs) && segJitterMs >= 0 ? segJitterMs : DEFAULTS.reply.segmentJitterMs
  try {
    for (let i = 0; i < replySegments.length; i++) {
      if (!client) break
      // 尾缀受 reply.appendCharacterSuffix 开关控制（默认开）；关闭后即使绑了角色也不带尾缀
      const appendSuffix = bindingCharacterName && settings.reply?.appendCharacterSuffix !== false
      const seg = appendSuffix
        ? `${replySegments[i]}（本次回复由虚拟角色:${bindingCharacterName}回复）`
        : replySegments[i]
      if (target.kind === 'group') {
        await client.sendGroupMsg(target.groupId, seg)
      } else {
        await client.sendPrivateMsg(target.userId, seg)
      }
      if (i < replySegments.length - 1) {
        await sleep(delayMs + Math.floor(Math.random() * (jitterMs + 1)))
      }
    }
    // 出向图片：AI 回复若含图（流里不带，须流后轮询 messages 取最新 assistant 记录），
    // 下载字节转 base64，用 OneBot image 段回发 QQ。失败仅告警，不影响已发的文本。
    try {
      const b64 = await fetchAssistantImageBase64(conversationId)
      if (b64 && client) {
        if (target.kind === 'group') {
          await client.sendGroupMsgImage(target.groupId, b64)
        } else {
          await client.sendPrivateMsgImage(target.userId, b64)
        }
      }
    } catch (e) {
      console.warn('[qqBridge] reply image send failed:', e?.message || e)
    }
  } catch (e) {
    console.warn('[qqBridge] reply send failed:', e?.message || e)
  }
}

/**
 * 按 QQ 发送者解析其专属会话 id（单聊隔离）。
 * sessionMap 命中则复用；否则懒建一个复用绑定角色的 SINGLE 会话，存映射并持久化。
 * per-key inflight 锁防同一用户并发首消息时重复建会话。群聊已在调用前丢弃，此处只处理私聊。
 */
async function resolveConversationId(sender, { force = false } = {}) {
  // 群聊：binding.conversationId 优先；空则懒建群聊共享会话（按 group 缓存）
  if (sender?.messageType === 'group') {
    const bound = readQqBridgeSettings().binding?.conversationId || ''
    if (bound) return bound
    const groupKey = `group:${sender?.groupId || 0}`
    if (sessionMap[groupKey]) return sessionMap[groupKey]
    const characterId = await resolveBindingCharacterId()
    if (!characterId) return ''
    const created = await createConversation(characterId)
    if (created?.id) {
      if (created?.characterName) {
        bindingCharacterName = created.characterName
        persistBindingCharacterId()
      }
      sessionMap[groupKey] = String(created.id)
      persistSessionMap()
    }
    return created?.id ? String(created.id) : ''
  }
  const key = `private:${sender?.userId || 0}`
  if (!force && sessionMap[key]) return sessionMap[key]
  if (inflight[key]) return inflight[key]
  inflight[key] = (async () => {
    try {
      const characterId = await resolveBindingCharacterId()
      if (!characterId) {
        log('resolveConversationId: no binding characterId — cannot create session for', key)
        return null
      }
      const created = await createConversation(characterId)
      if (!created?.id) {
        log('resolveConversationId: create conversation returned no id for', key)
        return null
      }
      if (created?.characterName) {
        bindingCharacterName = created.characterName
        persistBindingCharacterId()
      }
      sessionMap[key] = String(created.id)
      persistSessionMap()
      log(force ? 're-resolved conversation' : 'created conversation', sessionMap[key], 'for', key, '(character', characterId + ')')
      return sessionMap[key]
    } catch (e) {
      log('resolveConversationId failed for', key, ':', e?.message || e)
      return null
    } finally {
      delete inflight[key]
    }
  })()
  return inflight[key]
}

/**
 * 解析绑定会话的角色 id（懒建 QQ 会话时复用，保证所有 QQ 用户用同一角色）。
 * binding.characterId 命中则复用；否则反查 binding.conversationId 详情拿 characterId，缓存并持久化。
 */
async function resolveBindingCharacterId() {
  if (bindingCharacterId) return bindingCharacterId
  const settings = lastSettings
  const convId = settings?.binding?.conversationId
  if (!convId || !lastApiOrigin || !lastAuthToken) return ''
  try {
    const res = await performApiRequest({
      method: 'GET',
      url: `${lastApiOrigin}/api/conversation/${convId}`,
      apiOrigin: lastApiOrigin,
      authToken: lastAuthToken,
      timeoutMs: 30000,
    })
    const parsed = safeJsonParse(res.data)
    // 宽松解析：data.characterId 或 data.character.id（后端两种可能形态都兜住）
    const cid = parsed?.data?.characterId || parsed?.data?.character?.id
    if (cid) {
      bindingCharacterId = String(cid)
      bindingCharacterName = parsed?.data?.characterName || parsed?.data?.character?.name || ''
      persistBindingCharacterId()
      log('resolved binding characterId', bindingCharacterId, 'name=', bindingCharacterName, 'from conversation', convId)
    } else {
      log('resolveBindingCharacterId: no characterId in conversation', convId, 'response:', String(res.data || '').slice(0, 120))
    }
  } catch (e) {
    log('resolveBindingCharacterId failed:', e?.message || e)
  }
  return bindingCharacterId
}

/**
 * 调后端新建一个 SINGLE 会话（复用绑定角色），供某 QQ 用户独占上下文。
 */
async function createConversation(characterId) {
  const res = await performApiRequest({
    method: 'POST',
    url: `${lastApiOrigin}/api/conversation`,
    apiOrigin: lastApiOrigin,
    authToken: lastAuthToken,
    headers: { 'Content-Type': 'application/json' },
    body: { characterId: String(characterId), mode: 'SINGLE' },
    timeoutMs: 30000,
  })
  const parsed = safeJsonParse(res.data)
  return parsed?.data || null
}

/**
 * 持久化 sessionMap 到 settings.binding.sessionMap。
 * writeQqBridgeSettings 对 binding 是浅合并，故须传完整 binding 免覆盖 conversationId 等。
 */
function persistSessionMap() {
  try {
    const prev = readQqBridgeSettings()
    writeQqBridgeSettings({ binding: { ...(prev.binding || {}), sessionMap: { ...sessionMap } } })
  } catch (e) {
    log('persistSessionMap failed:', e?.message || e)
  }
}

/**
 * 持久化 binding.characterId 缓存，免下次再反查。
 */
function persistBindingCharacterId() {
  try {
    const prev = readQqBridgeSettings()
    writeQqBridgeSettings({ binding: { ...(prev.binding || {}), characterId: bindingCharacterId, characterName: bindingCharacterName } })
  } catch (e) {
    log('persistBindingCharacterId failed:', e?.message || e)
  }
}

function safeJsonParse(raw) {
  if (typeof raw !== 'string' || !raw) return null
  try {
    return JSON.parse(raw)
  } catch {
    return null
  }
}

/**
 * 解析整条 SSE 文本，提取分段信息。
 * 后端流式端点依次推送：{"content":"<delta>"}（逐 token）、{"replace":"<全文>"}（语言校正）、
 * {"replace":"<规范化>","pieces":["段1",...]}（beforeStreamComplete 的分段，[DONE] 前）、
 * {"error":"<msg>"}（失败）以及 : keep-alive 注释行。bridge 只关心最终 pieces/replace/error。
 */
function parseSseStream(raw) {
  const out = { pieces: [], replace: '', content: '', error: '' }
  if (typeof raw !== 'string' || !raw) return out
  for (const block of raw.split(/\r?\n\r?\n/)) {
    const dataLines = []
    for (const line of block.split(/\r?\n/)) {
      if (line.startsWith(':')) continue // 注释行（心跳）
      if (line.startsWith('data:')) dataLines.push(line.slice(5).replace(/^ /, ''))
    }
    if (!dataLines.length) continue
    const dataStr = dataLines.join('\n')
    if (dataStr === '[DONE]') continue
    const obj = safeJsonParse(dataStr)
    if (!obj) continue
    if (Array.isArray(obj.pieces) && obj.pieces.length) out.pieces = obj.pieces.map(s => String(s ?? ''))
    if (typeof obj.replace === 'string') out.replace = obj.replace
    if (typeof obj.content === 'string') out.content += obj.content
    if (typeof obj.error === 'string' && obj.error) out.error = obj.error
  }
  return out
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms))
}

// ===== 图片双向支持 =====
// 入向（用户→AI）：NapCat 给的图片 url 多为本地/localhost 或 QQ CDN，云端后端拉不到。
//  先在本地把字节下载下来（Node 全局 fetch，绕过 performApiRequest 的 SSRF host 锁——
//  这是用户发来的图片，非 API 调用），再 multipart 上传到后端 /conversation/chat-image，
//  换取后端可访问的 imageUrl 随消息流发出。失败则退回原 url（公网 CDN 后端或可直接拉）。
// 出向（AI→用户）：AI 回复的图片不在 SSE 流里（App 是流后轮询 messages 取最新 assistant 记录）。
//  流结束后 GET /messages?limit=50 取最新 assistant 的 imageUrl，resolveMediaUrl 拼成后端
//  公开文件 URL，经 performApiRequest(binary) 下载（自签名证书须走 net+pin，不能用全局 fetch），
//  base64 后用 sendPrivateMsgImage 发回 QQ。
const INPUT_IMAGE_MAX_BYTES = 5 * 1024 * 1024 // 5MB

function buildMultipartBuffer(fieldName, filename, buf, mime) {
  const boundary = '----qqbridge' + Math.random().toString(16).slice(2)
  const head = Buffer.from(
    `--${boundary}\r\nContent-Disposition: form-data; name="${fieldName}"; filename="${filename}"\r\nContent-Type: ${mime}\r\n\r\n`,
  )
  const tail = Buffer.from(`\r\n--${boundary}--\r\n`)
  return { boundary, body: Buffer.concat([head, buf, tail]) }
}

// 私有/内网 host 拦截（防 SSRF：构造 data.url 让桥接去扫内网/云元数据）。
// 127.0.0.1/localhost/::1 放行——NapCat 本地 file server 就在 127.0.0.1:6099，必须能下；
// 其他私网段（10.x/172.16-31/192.168/169.254 链路本地·云元数据）一律拒。
export function isPrivateImageHost(hostname) {
  const h = String(hostname || '').toLowerCase().replace(/^\[|\]$/g, '')
  if (h === 'localhost' || h === '127.0.0.1' || h === '::1' || h === '0.0.0.0') return false
  const m = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(h)
  if (m) {
    const a = +m[1], b = +m[2]
    if (a === 10) return true
    if (a === 172 && b >= 16 && b <= 31) return true
    if (a === 192 && b === 168) return true
    if (a === 169 && b === 254) return true // 链路本地 / 云元数据 169.254.169.254
    if (a === 127) return true // 其他 127.x 保守拒绝
    return false // 公网 IPv4
  }
  if (h.startsWith('fe80:') || h.startsWith('fc') || h.startsWith('fd')) return true // IPv6 链路本地/唯一本地
  return false
}

// loopback 判定（NapCat 本地 file server 在 127.0.0.1:6099，初始请求须放行）。
// 用于重定向逐跳校验：公网 URL 跳到 loopback 即 SSRF，须拒；仅当初始即本地(NapCat)时
// 才允许 loopback→loopback 的重定向（NapCat 本地服务的目录跳转等）。
export function isLoopbackImageHost(hostname) {
  const h = String(hostname || '').toLowerCase().replace(/^\[|\]$/g, '')
  return h === 'localhost' || h === '127.0.0.1' || h === '::1' || h === '0.0.0.0'
}

const IMAGE_REDIRECT_STATUSES = new Set([301, 302, 303, 307, 308])
const IMAGE_MAX_REDIRECTS = 5

// 把 NapCat 图片源读成 Buffer。仅支持 http(s) 与 base64://：
// file:// 与裸本地路径已移除——曾可被构造 data.file 读取本机任意文件并上传外泄，
// 风险大于收益（NapCat 正常给 http url 或 base64）。http 下载逐跳过 SSRF 校验：
// redirect:'manual' 手动跟 Location，每一跳都过 isPrivateImageHost；重定向到 loopback
// 仅当初始 URL 即本地(NapCat)时放行，否则拒——堵「公网 URL 302 到 127.0.0.1/169.254」
// 的重定向 SSRF 绕过（原 redirect:'follow' 只校验首跳，跳板可绕过私网拦截）。
export async function fetchImageBytes({ url, file }) {
  const src = (url || file || '').trim()
  if (!src) return null
  if (src.startsWith('http://') || src.startsWith('https://')) {
    let parsed
    try { parsed = new URL(src) } catch { throw new Error('invalid image url') }
    if (isPrivateImageHost(parsed.hostname)) {
      throw new Error('blocked private/loopback image host: ' + parsed.hostname)
    }
    const initialIsLoopback = isLoopbackImageHost(parsed.hostname)
    let currentUrl = src
    for (let hop = 0; hop <= IMAGE_MAX_REDIRECTS; hop++) {
      const r = await fetch(currentUrl, { redirect: 'manual' })
      // 手动处理重定向：对每一跳 Location 都做 SSRF 校验，杜绝跳板绕过
      if (IMAGE_REDIRECT_STATUSES.has(r.status)) {
        const loc = r.headers.get('location')
        if (!loc) throw new Error(`image redirect ${r.status} without location`)
        let nextUrl
        try { nextUrl = new URL(loc, currentUrl) } catch {
          throw new Error('invalid image redirect location: ' + String(loc).slice(0, 60))
        }
        if (isPrivateImageHost(nextUrl.hostname)) {
          throw new Error('blocked private redirect host: ' + nextUrl.hostname)
        }
        if (isLoopbackImageHost(nextUrl.hostname) && !initialIsLoopback) {
          throw new Error('blocked loopback redirect host: ' + nextUrl.hostname)
        }
        currentUrl = nextUrl.toString()
        continue
      }
      if (!r.ok) throw new Error(`fetch image http ${r.status}`)
      return Buffer.from(await r.arrayBuffer())
    }
    throw new Error('image download exceeded redirect limit')
  }
  if (src.startsWith('base64://')) {
    return Buffer.from(src.slice('base64://'.length), 'base64')
  }
  throw new Error('unsupported image source (only http(s)/base64 accepted): ' + String(src).slice(0, 40))
}

async function uploadInputImage({ url, file }) {
  const buf = await fetchImageBytes({ url, file })
  if (!buf || !buf.length) throw new Error('empty image bytes')
  if (buf.length > INPUT_IMAGE_MAX_BYTES) throw new Error(`image too large: ${buf.length}B`)
  const { boundary, body } = buildMultipartBuffer('file', 'image.jpg', buf, 'image/jpeg')
  const res = await performApiRequest({
    method: 'POST',
    url: `${lastApiOrigin}/api/conversation/chat-image`,
    apiOrigin: lastApiOrigin,
    authToken: lastAuthToken,
    headers: { 'Content-Type': `multipart/form-data; boundary=${boundary}` },
    body,
    timeoutMs: 30000,
  })
  if (res.status < 200 || res.status >= 300) throw new Error(`chat-image HTTP ${res.status}`)
  const parsed = safeJsonParse(res.data)
  const imageUrl = parsed?.data?.imageUrl || parsed?.imageUrl
  if (!imageUrl) throw new Error('chat-image returned no imageUrl')
  return imageUrl
}

// AI 回复图片的 imageUrl 可能是后端对象 key（如 chat-images/xxx.jpg）或完整 http URL。
// 对象 key 拼成后端公开文件 URL（/api/public/files/ 无需鉴权，renderer <img> 也这么用）。
function resolveMediaUrl(imageUrl) {
  if (!imageUrl || typeof imageUrl !== 'string') return ''
  if (/^https?:\/\//i.test(imageUrl)) return imageUrl
  return `${lastApiOrigin}/api/public/files/${imageUrl.replace(/^\/+/, '')}`
}

// 流结束后取最新 assistant 消息的图片，下载转 base64 供 OneBot image 段回发。
// [DONE] 后本轮 assistant 记录可能尚未落库，短轮询最多 3 次（间隔 500ms），取 seq 最大的
// 最新 assistant；仅当它本身带 imageUrl 才回发，避免误发历史旧图。
async function fetchAssistantImageBase64(conversationId) {
  if (!lastApiOrigin || !lastAuthToken) return ''
  try {
    let best = null // { seq, record } seq 最大的最新 assistant
    for (let attempt = 0; attempt < 3; attempt++) {
      const res = await performApiRequest({
        method: 'GET',
        url: `${lastApiOrigin}/api/conversation/${conversationId}/messages?limit=50`,
        apiOrigin: lastApiOrigin,
        authToken: lastAuthToken,
        timeoutMs: 15000,
      })
      if (res.status >= 200 && res.status < 300) {
        const parsed = safeJsonParse(res.data)
        const records = Array.isArray(parsed?.data?.records)
          ? parsed.data.records
          : (Array.isArray(parsed?.records) ? parsed.records : [])
        // records 升序，末尾即最新 assistant
        for (let i = records.length - 1; i >= 0; i--) {
          const r = records[i]
          if (r && r.role === 'assistant') {
            const seq = Number(r.seq) || 0
            if (!best || seq > best.seq) best = { seq, record: r }
            break
          }
        }
      }
      if (attempt < 2) await sleep(500) // 给本轮记录落库时间
    }
    if (best && best.record && best.record.imageUrl) {
      const mediaUrl = resolveMediaUrl(best.record.imageUrl)
      if (mediaUrl) {
        const imgRes = await performApiRequest({
          method: 'GET',
          url: mediaUrl,
          apiOrigin: lastApiOrigin,
          authToken: lastAuthToken,
          timeoutMs: 30000,
          binary: true,
        })
        if (imgRes.status < 200 || imgRes.status >= 300) return ''
        const buf = Buffer.isBuffer(imgRes.data) ? imgRes.data : Buffer.from(imgRes.data)
        if (!buf.length) return ''
        if (buf.length > 10 * 1024 * 1024) return '' // 10MB 上限，免 base64 过大撑爆 napcat
        return buf.toString('base64')
      }
    }
  } catch (e) {
    log('fetchAssistantImageBase64 failed:', e?.message || e)
  }
  return ''
}
