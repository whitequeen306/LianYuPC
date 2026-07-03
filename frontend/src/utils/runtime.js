/** 是否在 Electron 桌面端运行 */
export function isElectronRuntime() {
  return typeof window !== 'undefined' && (
    window.electronAPI?.isElectron === true
    || /Electron/i.test(window.navigator.userAgent)
  )
}

const DEFAULT_API_ORIGIN = 'http://localhost:8080'
const PACKED_API_ORIGIN = (
  typeof import.meta.env.VITE_LIANYU_PACKED_API_ORIGIN === 'string'
    ? import.meta.env.VITE_LIANYU_PACKED_API_ORIGIN
    : ''
).trim().replace(/\/$/, '')

/** @type {string | null} */
let cachedElectronOrigin = PACKED_API_ORIGIN || null
/** @type {Promise<void> | null} */
let initPromise = null

/** Electron 打包版：从主进程 IPC 拉取 API 根地址（renderer 不嵌入 VITE_LIANYU_API_ORIGIN） */
export async function initElectronRuntimeConfig() {
  if (!isElectronRuntime()) return
  if (cachedElectronOrigin) return
  if (initPromise) return initPromise

  initPromise = (async () => {
    const api = window.electronAPI
    if (api?.getRuntimeConfig) {
      try {
        const cfg = await api.getRuntimeConfig()
        if (cfg?.apiOrigin) {
          cachedElectronOrigin = String(cfg.apiOrigin).trim().replace(/\/$/, '')
          return
        }
      } catch {
        /* fall through to packed origin */
      }
    }
    if (PACKED_API_ORIGIN) {
      cachedElectronOrigin = PACKED_API_ORIGIN
    }
  })()

  return initPromise
}

export async function ensureApiOriginReady() {
  if (isElectronRuntime() && !cachedElectronOrigin) {
    await initElectronRuntimeConfig()
  }
}

/** Electron 下 API 根地址（云端由主进程 secrets 提供；本地开发默认 localhost） */
export function resolveApiOrigin() {
  if (isElectronRuntime()) {
    return cachedElectronOrigin || DEFAULT_API_ORIGIN
  }
  return ''
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
