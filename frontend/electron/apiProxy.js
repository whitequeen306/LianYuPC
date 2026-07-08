/**
 * 主进程 HTTP 代理 — 与桌宠 observe 相同走 net.request + defaultSession 证书 pin。
 * 渲染进程 axios 在打包版经 IPC 调用，避免 partition/CORS/axios-fetch 差异导致请求发不出去。
 *
 * 出口加固（issue #6）：
 *  - 仅允许目标为 API origin（host:port 精确匹配）的 http(s) 请求，拒绝 file/data/javascript 及内网/元数据 SSRF；
 *  - 渲染层不得自带 lianyu-token / authorization 鉴权头（防伪造、防 token 经 IPC 明文回读），由主进程统一注入；
 *  - 出口限流（令牌桶）+ 审计日志，便于发现异常外联。
 */
import { net } from 'electron'

const MAX_BODY_BYTES = 20 * 1024 * 1024

/**
 * 是否允许的出口 URL：必须 http(s)、host:port 与 API origin 精确一致。
 * 非 API host 既不发出请求也不返回响应体（issue #6：阻断 SSRF / CSP 绕过 / pin 绕过）。
 */
export function isAllowedEgressUrl(url, apiOrigin, extraOrigin = '') {
  if (!url || typeof url !== 'string') return false
  let parsed
  try { parsed = new URL(url) } catch { return false }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') return false
  const allowedHosts = new Set()
  for (const origin of [apiOrigin, extraOrigin]) {
    if (!origin) continue
    try {
      allowedHosts.add(new URL(origin).host)
    } catch {
      // ignore malformed origin
    }
  }
  // host 含端口；hostname 相同但端口不同（如 API 在 8443，攻击者指向同主机 1234）仍视为越权
  return allowedHosts.has(parsed.host)
}

/**
 * 规范化出口头：剔除渲染层自带的鉴权头（lianyu-token/authorization，大小写不敏感），
 * 由主进程按 authToken 统一注入。渲染层无法伪造他人 token，亦无法借 IPC 把 token 明文回读。
 */
export function sanitizeEgressHeaders(rawHeaders = {}, authToken = '') {
  const out = {}
  for (const [key, value] of Object.entries(rawHeaders)) {
    if (value === undefined || value === null) continue
    const lower = String(key).toLowerCase()
    if (lower === 'lianyu-token' || lower === 'authorization') continue
    out[String(key)] = Array.isArray(value) ? value.join(', ') : String(value)
  }
  if (authToken) out['lianyu-token'] = authToken
  return out
}

/**
 * 令牌桶出口限流：容量内可瞬时突发，之后按 refillPerSec 持续补给。
 * 阈值宽松（仅兜住 XSS 驱动的失控外联循环），不影响正常页面加载的并发请求。
 */
export class TokenBucket {
  constructor(capacity, refillPerSec) {
    this.capacity = Math.max(1, Number(capacity) || 1)
    this.refillPerMs = Math.max(0, Number(refillPerSec) || 0) / 1000
    this.tokens = this.capacity
    this.lastRefill = Date.now()
  }
  tryAcquire() {
    const now = Date.now()
    if (this.refillPerMs > 0) {
      const elapsed = now - this.lastRefill
      if (elapsed > 0) {
        this.tokens = Math.min(this.capacity, this.tokens + elapsed * this.refillPerMs)
        this.lastRefill = now
      }
    }
    if (this.tokens >= 1) {
      this.tokens -= 1
      return true
    }
    return false
  }
}

// 出口限流默认实例：120 突发 + 40/s 补给（页面加载并发 ~10-20 远低于此；XSS 循环会在 120 后被限）
export const egressLimiter = new TokenBucket(120, 40)

function collectBody(body) {
  if (body === undefined || body === null || body === '') return null
  if (typeof body === 'string') return body
  if (Buffer.isBuffer(body)) return body
  return JSON.stringify(body)
}

export function performApiRequest({ method = 'GET', url, headers = {}, body, timeoutMs = 60000, apiOrigin = '', authToken = '', binary = false }) {
  return new Promise((resolve, reject) => {
    if (!isAllowedEgressUrl(url, apiOrigin)) {
      const err = new Error('egress_host_not_allowed')
      err.code = 'EGRESS_BLOCKED'
      reject(err)
      return
    }

    const payload = collectBody(body)
    if (payload && Buffer.byteLength(payload) > MAX_BODY_BYTES) {
      reject(new Error('api:request body too large'))
      return
    }

    const req = net.request({
      method: (method || 'GET').toUpperCase(),
      url,
      headers: sanitizeEgressHeaders(headers, authToken),
    })

    let settled = false
    const finish = (fn, value) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      fn(value)
    }

    const timer = setTimeout(() => {
      try {
        req.abort()
      } catch {
        /* ignore */
      }
      finish(reject, new Error('api:request timeout'))
    }, Math.max(1000, timeoutMs || 60000))

    req.on('response', (res) => {
      const chunks = []
      res.on('data', (chunk) => {
        chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk))
      })
      res.on('end', () => {
        // binary:true 返回原始 Buffer（下载图片字节用）；默认 utf-8 字符串（JSON/文本）
        const buf = Buffer.concat(chunks)
        const data = binary ? buf : buf.toString('utf-8')
        finish(resolve, {
          status: res.statusCode || 0,
          statusText: res.statusMessage || '',
          headers: res.headers || {},
          data,
        })
      })
      res.on('error', (err) => finish(reject, err))
    })

    req.on('error', (err) => finish(reject, err))

    if (payload) {
      req.end(payload)
    } else {
      req.end()
    }
  })
}
