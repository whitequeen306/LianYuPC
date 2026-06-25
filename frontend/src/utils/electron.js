/** @returns {import('electron').ElectronAPI | null} */
export function getElectronAPI() {
  return typeof window !== 'undefined' ? window.electronAPI || null : null
}

/** 与 runtime.isElectronRuntime 一致：优先 electronAPI，回退 UA 检测 */
export function isElectronApp() {
  if (typeof window === 'undefined') return false
  if (getElectronAPI()?.isElectron === true) return true
  return /Electron/i.test(window.navigator.userAgent)
}

/** IPC 失败时返回 { ok: false }，统一转为 null */
export function normalizeAuthSession(raw) {
  if (!raw || typeof raw !== 'object' || raw.ok === false) return null
  return raw
}
