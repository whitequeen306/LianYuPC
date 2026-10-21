// 三级实测阿里云 DashScope 语音能力（不写死 key，从环境变量 DSK 读）。
//
// 为什么要分三级：之前那把 key 卡在第二级（qwen3-tts-vc-* 合成 404），
// 而语音通话走的是第三级（realtime WS）。三级分开测才能定位到底哪一层能用。
//
// 用法:  $env:DSK = 'sk-...'; node scripts/_probe_dashscope_voice.mjs
// 协议来源: backend/lianyu-service/.../DashScopeTtsRealtimeService.java（我们自己已上线的实现）

import https from 'node:https'
import { createRequire } from 'node:module'

const require = createRequire('C:/Users/hp/Desktop/LianYu-PC/frontend/package.json')
const WebSocket = require('ws')

const KEY = process.env.DSK
if (!KEY) {
  console.error('缺少环境变量 DSK')
  process.exit(1)
}

const HTTP_TTS_URL =
  'https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation'
const REALTIME_URL = 'wss://dashscope.aliyuncs.com/api-ws/v1/realtime'

const TEXT = '你好，我是林晚，今天也想和你说说话。'

function postJson(url, body, timeoutMs = 60000) {
  return new Promise((resolve) => {
    const payload = Buffer.from(JSON.stringify(body), 'utf8')
    const req = https.request(
      url,
      {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${KEY}`,
          'Content-Type': 'application/json',
          'Content-Length': payload.length,
        },
        timeout: timeoutMs,
      },
      (res) => {
        let raw = ''
        res.on('data', (c) => { raw += c })
        res.on('end', () => resolve({ status: res.statusCode, raw }))
      },
    )
    req.on('timeout', () => { req.destroy(new Error('timeout')) })
    req.on('error', (e) => resolve({ status: -1, raw: String(e.message) }))
    req.write(payload)
    req.end()
  })
}

/** 一级/二级共用的 HTTP TTS 探测 */
async function probeHttp(label, model, voice) {
  const body = {
    model,
    input: { text: TEXT, voice, language_type: 'Chinese' },
  }
  const r = await postJson(HTTP_TTS_URL, body)
  let verdict = 'FAIL'
  let detail = ''
  try {
    const j = JSON.parse(r.raw)
    if (r.status === 200 && j.output?.audio?.url) {
      verdict = 'OK'
      detail = `audio.url 拿到（${j.output.audio.url.slice(0, 60)}...）`
    } else {
      detail = `${j.code || ''} ${j.message || ''}`.trim() || r.raw.slice(0, 160)
    }
  } catch {
    detail = r.raw.slice(0, 160)
  }
  console.log(`[${label}] model=${model}`)
  console.log(`        voice=${voice}`)
  console.log(`        http=${r.status}  ${verdict}  ${detail}`)
  return verdict === 'OK'
}

/** 三级：实时 WS（语音通话实际走的那条） */
function probeRealtime(model, voice, timeoutMs = 30000) {
  return new Promise((resolve) => {
    const url = `${REALTIME_URL}?model=${encodeURIComponent(model)}`
    const events = []
    let audioBytes = 0
    let deltas = 0
    let settled = false
    const done = (verdict, detail) => {
      if (settled) return
      settled = true
      try { ws.close() } catch {}
      console.log(`[三] 实时 WS  model=${model}`)
      console.log(`        voice=${voice}`)
      console.log(`        ${verdict}  ${detail}`)
      console.log(`        收到事件: ${events.slice(0, 10).join(', ')}${events.length > 10 ? ' …' : ''}`)
      console.log(`        audio delta 帧=${deltas}  累计字节=${audioBytes}`)
      resolve(verdict === 'OK')
    }

    const ws = new WebSocket(url, {
      headers: { Authorization: `Bearer ${KEY}` },
    })
    const timer = setTimeout(() => done('TIMEOUT', `${timeoutMs}ms 内没有拿到音频`), timeoutMs)

    const send = (obj) => ws.send(JSON.stringify({ event_id: `evt_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`, ...obj }))

    ws.on('unexpected-response', (_req, res) => {
      let body = ''
      res.on('data', (c) => { body += c })
      res.on('end', () => {
        clearTimeout(timer)
        done('FAIL', `握手 http=${res.statusCode} ${body.slice(0, 200)}`)
      })
    })

    ws.on('open', () => {
      events.push('open')
      send({
        type: 'session.update',
        session: { voice, language_type: 'Chinese', mode: 'commit', response_format: 'pcm', sample_rate: 24000 },
      })
    })

    ws.on('message', (buf) => {
      let msg
      try { msg = JSON.parse(buf.toString()) } catch { return }
      const type = msg.type || '(no type)'
      if (!events.includes(type)) events.push(type)

      if (type === 'session.updated' || type === 'session.created') {
        // 会话就绪后才能 append；协议上 session.update 完成前发送会被丢
        if (type === 'session.updated') {
          send({ type: 'input_text_buffer.append', text: TEXT })
          send({ type: 'input_text_buffer.commit' })
        }
        return
      }
      if (type === 'response.audio.delta' || type === 'response.output_audio.delta') {
        deltas += 1
        const b64 = msg.delta || msg.audio || ''
        audioBytes += Buffer.from(b64, 'base64').length
        return
      }
      if (type === 'response.audio.done' || type === 'response.done') {
        clearTimeout(timer)
        done(audioBytes > 0 ? 'OK' : 'FAIL', audioBytes > 0 ? `拿到 PCM ${audioBytes} 字节` : '完成但没收到音频')
        return
      }
      if (type === 'error' || type === 'session.error') {
        clearTimeout(timer)
        done('FAIL', JSON.stringify(msg).slice(0, 220))
      }
    })

    ws.on('error', (e) => { clearTimeout(timer); done('FAIL', `ws error: ${e.message}`) })
    ws.on('close', (code) => {
      clearTimeout(timer)
      if (!settled) done('FAIL', `连接被关闭 code=${code}（未收到音频）`)
    })
  })
}

const results = {}
results.httpFlash = await probeHttp('一', 'qwen3-tts-flash', 'Cherry')
results.httpVc = await probeHttp('二', 'qwen3-tts-vc-2026-01-22', 'qwen-tts-vc-klee-voice-20260616133001848-5f9b')
results.realtime = await probeRealtime(
  'qwen3-tts-vc-realtime-2026-01-15',
  'qwen-tts-vc-klee_rt-voice-20260801194458612-ede2',
)

console.log('\n===== 汇总 =====')
console.log(JSON.stringify(results, null, 2))
