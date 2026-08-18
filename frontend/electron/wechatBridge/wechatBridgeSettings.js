/**
 * WeChat ClawBot local settings — per-install JSON under userData.
 */
import { app } from 'electron'
import path from 'path'
import fs from 'fs'

export const DEFAULTS = {
  enabled: false,
  binding: {
    conversationId: '',
    characterId: '',
    provider: '',
    model: '',
  },
  reply: {
    fallbackText: '（服务暂时不可用，稍后再试）',
    timeoutMs: 120000,
  },
  hosting: {
    version: '',
    consented: false,
  },
}

function settingsPath() {
  return path.join(app.getPath('userData'), 'wechat-bridge-settings.json')
}

export function normalizeWechatBridgeSettings(settings) {
  const raw = settings || {}
  const binding = { ...DEFAULTS.binding, ...(raw.binding || {}) }
  const reply = { ...DEFAULTS.reply, ...(raw.reply || {}) }
  const hosting = { ...DEFAULTS.hosting, ...(raw.hosting || {}) }
  binding.conversationId = String(binding.conversationId || '').trim()
  binding.characterId = String(binding.characterId || '').trim()
  binding.provider = String(binding.provider || '').trim()
  binding.model = String(binding.model || '').trim()
  reply.fallbackText = typeof reply.fallbackText === 'string' ? reply.fallbackText : DEFAULTS.reply.fallbackText
  const timeout = Number(reply.timeoutMs)
  reply.timeoutMs = Number.isFinite(timeout) && timeout >= 5000 ? timeout : DEFAULTS.reply.timeoutMs
  hosting.version = String(hosting.version || '').trim()
  hosting.consented = hosting.consented === true
  return {
    enabled: raw.enabled === true,
    binding,
    reply,
    hosting,
  }
}

export function readWechatBridgeSettings() {
  try {
    const raw = fs.readFileSync(settingsPath(), 'utf8')
    return normalizeWechatBridgeSettings(JSON.parse(raw))
  } catch {
    return normalizeWechatBridgeSettings(DEFAULTS)
  }
}

export function writeWechatBridgeSettings(partial) {
  const current = readWechatBridgeSettings()
  const next = normalizeWechatBridgeSettings({
    ...current,
    ...(partial || {}),
    binding: { ...current.binding, ...(partial?.binding || {}) },
    reply: { ...current.reply, ...(partial?.reply || {}) },
    hosting: { ...current.hosting, ...(partial?.hosting || {}) },
  })
  const dest = settingsPath()
  fs.mkdirSync(path.dirname(dest), { recursive: true })
  fs.writeFileSync(dest, JSON.stringify(next, null, 2))
  return next
}
