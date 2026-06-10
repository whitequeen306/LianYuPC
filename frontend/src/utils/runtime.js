/** 是否在 Electron 桌面端运行 */
export function isElectronRuntime() {
  return typeof window !== 'undefined' && (
    window.electronAPI?.isElectron === true
    || /Electron/i.test(window.navigator.userAgent)
  )
}

const DEFAULT_API_ORIGIN = 'http://localhost:8080'

function readConfiguredApiOrigin() {
  const fromImportMeta = import.meta.env.VITE_LIANYU_API_ORIGIN
  if (fromImportMeta && String(fromImportMeta).trim()) {
    return String(fromImportMeta).trim()
  }
  // electron-pack / vite define 注入（ELECTRON_BUILD=1）
  if (typeof process !== 'undefined' && process.env) {
    const fromProcess = process.env.LIANYU_API_ORIGIN || process.env.VITE_LIANYU_API_ORIGIN
    if (fromProcess && String(fromProcess).trim()) {
      return String(fromProcess).trim().replace(/\/$/, '')
    }
  }
  return ''
}

/** Electron 下 API 根地址（云端构建注入，本地开发默认 localhost） */
export function resolveApiOrigin() {
  return readConfiguredApiOrigin() || DEFAULT_API_ORIGIN
}

/** HTTP API 根（Electron 直连后端；浏览器走同源 nginx） */
export function apiOrigin() {
  return isElectronRuntime() ? resolveApiOrigin() : ''
}

/** REST API 前缀，与 axios baseURL 一致 */
export function apiBasePath() {
  return `${apiOrigin()}/api`
}

/** WebSocket STOMP 地址 */
export function buildWsUrl() {
  if (isElectronRuntime()) {
    const origin = resolveApiOrigin()
    try {
      const url = new URL(origin)
      const wsProtocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
      return `${wsProtocol}//${url.host}/ws`
    } catch {
      return 'ws://localhost:8080/ws'
    }
  }
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${window.location.host}/ws`
}
