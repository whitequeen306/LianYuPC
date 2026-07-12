import { app } from 'electron'
import path from 'path'
import fs from 'fs'

function storePath() {
  return path.join(app.getPath('userData'), 'last-chat-model.json')
}

export function readLastChatModel() {
  try {
    const raw = fs.readFileSync(storePath(), 'utf8')
    const obj = JSON.parse(raw)
    return {
      provider: typeof obj.provider === 'string' ? obj.provider : '',
      model: typeof obj.model === 'string' ? obj.model : '',
    }
  } catch {
    return { provider: '', model: '' }
  }
}

export function writeLastChatModel({ provider, model } = {}) {
  const next = {
    provider: typeof provider === 'string' ? provider.trim().slice(0, 64) : '',
    model: typeof model === 'string' ? model.trim().slice(0, 128) : '',
  }
  try {
    fs.mkdirSync(path.dirname(storePath()), { recursive: true })
    fs.writeFileSync(storePath(), JSON.stringify(next, null, 2))
  } catch {
    // ignore disk errors; non-critical
  }
  return next
}
