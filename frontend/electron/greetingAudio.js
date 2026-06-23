/**
 * 将问候 TTS 落盘为临时文件，供桌宠窗口用 file:// 播放（比 data: URI 更可靠）。
 */
import fs from 'fs'
import os from 'os'
import path from 'path'
import { pathToFileURL } from 'url'

const MAX_KEEP = 6
const recentFiles = []

function greetingAudioDir() {
  return path.join(os.tmpdir(), 'lianyu-greeting-audio')
}

/**
 * @param {string} base64
 * @param {string} [mimeType]
 * @returns {string | null} file:// URL
 */
export function materializeGreetingAudio(base64, mimeType = 'audio/wav') {
  if (!base64 || typeof base64 !== 'string') return null
  try {
    const ext = mimeType.includes('mpeg') || mimeType.includes('mp3') ? 'mp3' : 'wav'
    const dir = greetingAudioDir()
    fs.mkdirSync(dir, { recursive: true })
    const file = path.join(dir, `greeting-${Date.now()}-${Math.random().toString(36).slice(2, 8)}.${ext}`)
    fs.writeFileSync(file, Buffer.from(base64, 'base64'))
    recentFiles.push(file)
    while (recentFiles.length > MAX_KEEP) {
      const old = recentFiles.shift()
      try {
        fs.unlinkSync(old)
      } catch {
        // ignore stale temp file
      }
    }
    return pathToFileURL(file).href
  } catch (e) {
    console.warn('[greetingAudio] materialize failed:', e?.message || e)
    return null
  }
}

/**
 * @param {{ text?: string, audioBase64?: string, audioMimeType?: string, audioUrl?: string }} payload
 * @returns {{ text: string, audioUrl?: string }}
 */
export function prepareGreetingPayload(payload = {}) {
  const text = payload.text || ''
  if (payload.audioUrl) {
    return { text, audioUrl: payload.audioUrl }
  }
  const audioUrl = materializeGreetingAudio(payload.audioBase64, payload.audioMimeType)
  if (audioUrl) {
    return { text, audioUrl }
  }
  return { text }
}
