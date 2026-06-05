import { app } from 'electron'
import path from 'path'
import fs from 'fs'

const ALLOWED_PET_IDS = ['raiden', 'ayaka', 'furina', 'ganyu', 'hu-tao', 'klee']

const DEFAULTS = {
  closeToTray: true,
  showLauncherLogo: true,
  launchAtLogin: false,
  closeHintShown: false,
  launcherPetId: 'raiden',
}

function settingsPath() {
  return path.join(app.getPath('userData'), 'desktop-settings.json')
}

export function readDesktopSettings() {
  try {
    const raw = fs.readFileSync(settingsPath(), 'utf8')
    return { ...DEFAULTS, ...JSON.parse(raw) }
  } catch {
    return { ...DEFAULTS }
  }
}

export function writeDesktopSettings(partial) {
  const sanitized = { ...(partial || {}) }
  if (sanitized.launcherPetId && !ALLOWED_PET_IDS.includes(sanitized.launcherPetId)) {
    delete sanitized.launcherPetId
  }
  const next = { ...readDesktopSettings(), ...sanitized }
  fs.mkdirSync(path.dirname(settingsPath()), { recursive: true })
  fs.writeFileSync(settingsPath(), JSON.stringify(next, null, 2))
  applyLaunchAtLogin(next.launchAtLogin)
  return next
}

export function applyLaunchAtLogin(enabled) {
  try {
    app.setLoginItemSettings({
      openAtLogin: !!enabled,
      openAsHidden: true,
    })
  } catch {
    // ignore on unsupported platforms
  }
}

export function launcherPositionPath() {
  return path.join(app.getPath('userData'), 'launcher-position.json')
}

export function readLauncherPosition() {
  try {
    const raw = fs.readFileSync(launcherPositionPath(), 'utf8')
    const parsed = JSON.parse(raw)
    if (Number.isFinite(parsed.x) && Number.isFinite(parsed.y)) {
      return { x: parsed.x, y: parsed.y }
    }
  } catch {
    // ignore
  }
  return null
}

export function writeLauncherPosition(x, y) {
  fs.mkdirSync(path.dirname(launcherPositionPath()), { recursive: true })
  fs.writeFileSync(launcherPositionPath(), JSON.stringify({ x, y }))
}
