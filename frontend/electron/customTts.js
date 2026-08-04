/**
 * Client-side GPT-SoVITS TTS. Backend never fetches user endpoints (SSRF-safe).
 * Supports common GPT-SoVITS api_v2 `/tts` JSON and legacy query-style `/`.
 */
import fs from 'fs'
import os from 'os'
import path from 'path'
import { randomUUID } from 'crypto'

const LOOPBACK_OR_PRIVATE =
  /^(https?):\/\/(127\.0\.0\.1|localhost|\[::1\]|(\d{1,3}\.){3}\d{1,3}|[\w-]+\.local)(:\d+)?(\/.*)?$/i

export function isAllowedEndpoint(endpoint) {
  if (!endpoint || typeof endpoint !== 'string') return false
  const e = endpoint.trim()
  if (e.length > 512 || !LOOPBACK_OR_PRIVATE.test(e)) return false
  try {
    const u = new URL(e)
    const host = (u.hostname || '').toLowerCase()
    if (host === 'localhost' || host === '127.0.0.1' || host === '::1' || host.endsWith('.local')) {
      return true
    }
    const m = host.match(/^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/)
    if (!m) return false
    const a = +m[1]
    const b = +m[2]
    if (a === 10 || a === 127) return true
    if (a === 192 && b === 168) return true
    if (a === 172 && b >= 16 && b <= 31) return true
    return false
  } catch {
    return false
  }
}

async function downloadToTemp(url, token) {
  const headers = {}
  if (token) headers.Authorization = `Bearer ${token}`
  const resp = await fetch(url, { headers })
  if (!resp.ok) {
    throw new Error(`ref audio download failed: ${resp.status}`)
  }
  const buf = Buffer.from(await resp.arrayBuffer())
  if (buf.length < 1024 || buf.length > 20 * 1024 * 1024) {
    throw new Error('ref audio size invalid')
  }
  const ext = url.includes('.mp3') ? '.mp3' : '.wav'
  const file = path.join(os.tmpdir(), `ly-ref-${randomUUID()}${ext}`)
  fs.writeFileSync(file, buf)
  return file
}

export async function synthesizeSovits({ endpoint, text, refAudioUrl, refText, authToken }) {
  const base = String(endpoint || '').replace(/\/+$/, '')
  if (!isAllowedEndpoint(base)) {
    return { ok: false, error: 'invalid_endpoint' }
  }
  const spoken = String(text || '').trim()
  if (!spoken || spoken.length > 2000) {
    return { ok: false, error: 'invalid_text' }
  }
  const prompt = String(refText || '').trim()
  if (!prompt) {
    return { ok: false, error: 'missing_ref_text' }
  }
  if (!refAudioUrl || typeof refAudioUrl !== 'string') {
    return { ok: false, error: 'missing_ref_audio' }
  }

  let refPath = null
  try {
    refPath = await downloadToTemp(refAudioUrl, authToken)
    const body = {
      text: spoken,
      text_lang: 'zh',
      ref_audio_path: refPath,
      prompt_text: prompt,
      prompt_lang: 'zh',
      media_type: 'wav',
      streaming_mode: false,
    }
    let resp = await fetch(`${base}/tts`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
    if (!resp.ok) {
      const qs = new URLSearchParams({
        text: spoken,
        text_language: 'zh',
        refer_wav_path: refPath,
        prompt_text: prompt,
        prompt_language: 'zh',
      })
      resp = await fetch(`${base}/?${qs.toString()}`)
    }
    if (!resp.ok) {
      return { ok: false, error: `sovits_http_${resp.status}` }
    }
    const audio = Buffer.from(await resp.arrayBuffer())
    if (!audio.length) {
      return { ok: false, error: 'empty_audio' }
    }
    const contentType = resp.headers.get('content-type') || 'audio/wav'
    return {
      ok: true,
      mime: contentType.includes('mpeg') ? 'audio/mpeg' : 'audio/wav',
      base64: audio.toString('base64'),
    }
  } catch (e) {
    return { ok: false, error: e?.message || String(e) }
  } finally {
    if (refPath) {
      try {
        fs.unlinkSync(refPath)
      } catch {
        /* ignore */
      }
    }
  }
}
