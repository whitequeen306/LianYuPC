// 实时 TTS 延迟探针：量化「更流畅」到底差多少。
//
// 测三段时间：
//   handshake  = TCP/TLS/WS 握手 → open
//   sessionRtt = 发 session.update → 收到 session.updated
//   ttfa       = input_text_buffer.commit → 第一帧 response.audio.delta（首字延迟，最影响体感）
// 另外统计总时长与 PCM 字节数。
//
// 用法: $env:DSK='sk-...'; $env:RT_MODEL='qwen-audio-3.1-realtime-plus'; $env:RT_VOICE='...' node scripts/_probe_rt_latency.mjs
import { createRequire } from 'node:module'

const require = createRequire('C:/Users/hp/Desktop/LianYu-PC/frontend/package.json')
const WebSocket = require('ws')

const KEY = process.env.DSK
const MODEL = process.env.RT_MODEL
const VOICE = process.env.RT_VOICE
const TEXT = process.env.RT_TEXT || '你好，我是林晚，今天也想和你说说话。'

if (!KEY || !MODEL || !VOICE) {
  console.error('需要 DSK / RT_MODEL / RT_VOICE')
  process.exit(1)
}

const url = `wss://dashscope.aliyuncs.com/api-ws/v1/realtime?model=${encodeURIComponent(MODEL)}`
const t0 = Date.now()
const mark = {}
let firstDeltaAt = null
let audioBytes = 0
let deltas = 0
let settled = false

const ws = new WebSocket(url, { headers: { Authorization: `Bearer ${KEY}` } })

function finish(verdict, detail, { graceful = true } = {}) {
  if (settled) return
  settled = true
  clearTimeout(timer)
  try {
    // 显式结束会话再关闭：不 finish 的话服务端会一直占着这个 session，
    // 后续连接可能因为并发会话上限而拿不到音频（本轮实测踩到过）。
    if (graceful && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ event_id: `event_fin_${Date.now()}`, type: 'session.finish' }))
    }
  } catch {}
  try { ws.close() } catch {}
  const out = {
    model: MODEL,
    mode: MODE,
    voice: VOICE.slice(0, 52),
    verdict,
    detail,
    handshakeMs: mark.open ? mark.open - t0 : null,
    sessionUpdateMs: mark.updated && mark.updateSent ? mark.updated - mark.updateSent : null,
    ttfaMs: firstDeltaAt && mark.commitSent ? firstDeltaAt - mark.commitSent : null,
    totalMs: Date.now() - t0,
    audioDeltas: deltas,
    audioBytes,
    events: mark.events || [],
  }
  console.log(JSON.stringify(out, null, 2))
  process.exit(verdict === 'OK' ? 0 : 2)
}

const MODE = process.env.RT_MODE || 'server_commit'

const timer = setTimeout(() => finish('TIMEOUT', '45s'), 45000)

ws.on('unexpected-response', (_r, res) => {
  let b = ''
  res.on('data', (c) => { b += c })
  res.on('end', () => finish('FAIL', `handshake http=${res.statusCode} ${b.slice(0, 160)}`))
})
ws.on('open', () => {
  mark.open = Date.now()
  mark.events = ['open']
})
ws.on('message', (buf) => {
  let m
  try { m = JSON.parse(buf.toString()) } catch { return }
  const t = m.type || '?'
  if (!mark.events.includes(t)) mark.events.push(t)
  const now = Date.now()

  if (t === 'session.created') {
    mark.updateSent = now
    ws.send(JSON.stringify({
      event_id: `event_${now}`,
      type: 'session.update',
      session: { mode: MODE, voice: VOICE, language_type: 'Chinese', response_format: 'pcm', sample_rate: 24000 },
    }))
    return
  }
  if (t === 'session.updated') {
    mark.updated = now
    mark.commitSent = Date.now()
    ws.send(JSON.stringify({ event_id: `event_a_${now}`, type: 'input_text_buffer.append', text: TEXT }))
    // server_commit 模式下 append 就够；commit 模式必须显式提交
    if (MODE === 'commit') {
      ws.send(JSON.stringify({ event_id: `event_c_${now}`, type: 'input_text_buffer.commit' }))
    }
    return
  }
  if (t === 'response.audio.delta') {
    if (firstDeltaAt === null) firstDeltaAt = now
    deltas += 1
    audioBytes += Buffer.from(m.delta || '', 'base64').length
    return
  }
  if (t === 'response.audio.done' || t === 'response.done') {
    finish(audioBytes > 0 ? 'OK' : 'FAIL', audioBytes > 0 ? `${audioBytes} 字节 PCM` : '完成但无音频')
    return
  }
  if (t === 'error' || t === 'session.error') {
    finish('FAIL', JSON.stringify(m).slice(0, 200))
  }
})
ws.on('error', (e) => finish('FAIL', `ws error: ${e.message}`))
ws.on('close', (code, reason) => {
  if (!settled) finish('FAIL', `close code=${code} ${String(reason).slice(0, 80)}`)
})
