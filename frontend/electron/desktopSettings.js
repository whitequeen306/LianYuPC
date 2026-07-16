import { app } from 'electron'
import path from 'path'
import fs from 'fs'

const ALLOWED_PET_IDS = [
  'raiden', 'ayaka', 'furina', 'ganyu', 'hu-tao', 'klee', 'yoimiya',
  'anya', 'conan', 'kid', 'shinchan', 'baobao',
  'lappland', 'chen', 'new-covenant-exusiai', 'march-7th', 'elysia', 'kurumi',
]

const DEFAULTS = {
  closeToTray: true,
  showDesktopPet: true,
  showLauncherLogo: true,
  allowScreenObserve: false,
  launchAtLogin: false,
  closeHintShown: false,
  launcherPetId: 'raiden',
}

function settingsPath() {
  return path.join(app.getPath('userData'), 'desktop-settings.json')
}

function normalizeDesktopSettings(settings) {
  const next = { ...DEFAULTS, ...settings }
  if (settings.showDesktopPet == null && settings.showLauncherLogo != null) {
    next.showDesktopPet = settings.showLauncherLogo !== false
  }
  next.showLauncherLogo = next.showDesktopPet !== false
  return next
}

export function readDesktopSettings() {
  try {
    const raw = fs.readFileSync(settingsPath(), 'utf8')
    return normalizeDesktopSettings(JSON.parse(raw))
  } catch {
    return { ...DEFAULTS }
  }
}

export function isDesktopPetEnabled(settings) {
  return settings?.showDesktopPet !== false && settings?.showLauncherLogo !== false
}

export function writeDesktopSettings(partial) {
  const sanitized = { ...(partial || {}) }
  if (sanitized.launcherPetId && !ALLOWED_PET_IDS.includes(sanitized.launcherPetId)) {
    delete sanitized.launcherPetId
  }
  if (sanitized.showDesktopPet != null) {
    sanitized.showLauncherLogo = sanitized.showDesktopPet
  } else if (sanitized.showLauncherLogo != null) {
    sanitized.showDesktopPet = sanitized.showLauncherLogo
  }
  const next = normalizeDesktopSettings({ ...readDesktopSettings(), ...sanitized })
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
