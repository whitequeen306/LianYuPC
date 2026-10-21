// 按官方文档的正确模型名重测 DashScope 语音能力矩阵。
// 用法: $env:DSK='sk-...'; node scripts/_probe_dashscope_matrix.mjs
//
// 与上一版探针的差别：
// 1) 实时 WS 先等 session.created，再发 session.update（上一版在 open 就发，导致 1007）
// 2) 模型名改成文档里的 qwen3-tts-flash / qwen3-tts-flash-realtime
// 3) 同时测「系统音色」与「我们已注册的复刻音色」，确认复刻音色在哪个模型下能用

import https from 'node:https'
import { createRequire } from 'node:module'

const require = createRequire('C:/Users/hp/Desktop/LianYu-PC/frontend/package.json')
const WebSocket = require('ws')

const KEY = process.env.DSK
if (!KEY) { console.error('缺少 DSK'); process.exit(1) }

const TEXT = '你好，我是林晚，今天也想和你说说话。'
const KLEE_HTTP = 'qwen-tts-vc-klee-voice-20260616133001848-5f9b'
const KLEE_RT = 'qwen-tts-vc-klee_rt-voice-20260801194458612-ede2'

const ENDPOINTS = {
  legacy: 'https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation',
  speechSynth: 'https://dashscope.aliyuncs.com/api/v1/services/audio/tts/SpeechSynthesizer',
}

function postJson(url, body, timeoutMs = 90000) {
  return new Promise((resolve) => {
    const payload = Buffer.from(JSON.stringify(body), 'utf8')
    const req = https.request(url, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${KEY}`,
        'Content-Type': 'application/json',
        'Content-Length': payload.length,
      },
      timeout: timeoutMs,
    }, (res) => {
      let raw = ''
      res.on('data', (c) => { raw += c })
      res.on('end', () => resolve({ status: res.statusCode, raw }))
    })
    req.on('timeout', () => req.destroy(new Error('timeout')))
    req.on('error', (e) => resolve({ status: -1, raw: e.message }))
    req.write(payload)
    req.end()
  })
}

async function http(label, endpointKey, model, voice) {
  const r = await postJson(ENDPOINTS[endpointKey], {
    model,
    input: { text: TEXT, voice, language_type: 'Chinese' },
  })
  let verdict = 'FAIL'
  let detail = ''
  try {
    const j = JSON.parse(r.raw)
    if (r.status === 200 && j.output?.audio?.url) { verdict = 'OK'; detail = '有 audio.url' }
    else detail = `${j.code || ''} ${j.message || ''}`.trim().slice(0, 120) || r.raw.slice(0, 120)
  } catch { detail = r.raw.slice(0, 120) }
  console.log(`  [${label}] ${endpointKey.padEnd(12)} model=${model}`)
  console.log(`      voice=${voice.slice(0, 46)}`)
  console.log(`      http=${r.status} ${verdict} ${detail}`)
  return verdict === 'OK'
}

function realtime(model, voice, timeoutMs = 40000) {
  return new Promise((resolve) => {
    const url = `wss://dashscope.aliyuncs.com/api-ws/v1/realtime?model=${encodeURIComponent(model)}`
    const events = []
    let audioBytes = 0, deltas = 0, settled = false, updated = false
    const ws = new WebSocket(url, { headers: { Authorization: `Bearer ${KEY}` } })
    const finish = (verdict, detail) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      try { ws.close() } catch {}
      console.log(`  [实时] model=${model}`)
      console.log(`      voice=${voice.slice(0, 46)}`)
      console.log(`      ${verdict} ${detail}`)
      console.log(`      事件流: ${events.slice(0, 12).join(' → ')}`)
      console.log(`      audio delta=${deltas} 帧, 累计 ${audioBytes} 字节`)
      resolve(verdict === 'OK')
    }
    const timer = setTimeout(() => finish('TIMEOUT', `${timeoutMs}ms`), timeoutMs)
    const send = (o) => ws.send(JSON.stringify({ event_id: `event_${Math.random().toString(36).slice(2)}`, ...o }))

    ws.on('unexpected-response', (_r, res) => {
      let b = ''
      res.on('data', (c) => { b += c })
      res.on('end', () => finish('FAIL', `握手 http=${res.statusCode} ${b.slice(0, 160)}`))
    })
    ws.on('open', () => events.push('open'))
    ws.on('message', (buf) => {
      let m
      try { m = JSON.parse(buf.toString()) } catch { return }
      const t = m.type || '?'
      if (!events.includes(t)) events.push(t)

      if (t === 'session.created') {
        // 文档顺序：先拿到 session.created，再 session.update
        send({
          type: 'session.update',
          session: { mode: 'commit', voice, language_type: 'Chinese', response_format: 'pcm', sample_rate: 24000 },
        })
        return
      }
      if (t === 'session.updated') {
        updated = true
        send({ type: 'input_text_buffer.append', text: TEXT })
        send({ type: 'input_text_buffer.commit' })
        return
      }
      if (t === 'response.audio.delta') {
        deltas += 1
        audioBytes += Buffer.from(m.delta || '', 'base64').length
        return
      }
      if (t === 'response.audio.done' || t === 'response.done') {
        finish(audioBytes > 0 ? 'OK' : 'FAIL', `拿到 PCM ${audioBytes} 字节`)
        return
      }
      if (t === 'error' || t === 'session.error') {
        finish('FAIL', JSON.stringify(m).slice(0, 200))
      }
    })
    ws.on('error', (e) => finish('FAIL', `ws error: ${e.message}`))
    ws.on('close', (code, reason) => {
      if (!settled) finish('FAIL', `close code=${code} reason=${String(reason).slice(0, 80)}${updated ? '' : '（会话未 updated）'}`)
    })
  })
}

const out = {}
console.log('===== 非实时（HTTP）=====')
out.flashLegacy = await http('一', 'legacy', 'qwen3-tts-flash', 'Cherry')
out.flashSpeechSynth = await http('二', 'speechSynth', 'qwen3-tts-flash', 'Cherry')
out.flashCloneLegacy = await http('三', 'legacy', 'qwen3-tts-flash', KLEE_HTTP)
out.audio31 = await http('四', 'speechSynth', 'qwen-audio-3.1-tts-flash', 'longanhuan_v3.1')

console.log('\n===== 实时（WS）=====')
out.rtCherry = await realtime('qwen3-tts-flash-realtime', 'Cherry')
out.rtClone = await realtime('qwen3-tts-flash-realtime', KLEE_RT)

console.log('\n===== 汇总 =====')
console.log(JSON.stringify(out, null, 2))
