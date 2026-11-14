/**
 * 网关 HTTP 面：给运维/后续 App 用的最小控制台 + JSON API。
 *
 * 默认只监听 127.0.0.1（NapCat 的 WebUI 端口本身对本机任意进程可达是已知风险，
 * 网关这一层不再扩大暴露面）。要开放到内网/公网必须同时设置 GATEWAY_TOKEN。
 *
 * 路由：
 *   GET    /api/health
 *   GET    /api/accounts
 *   POST   /api/accounts                { key?, note? }  创建并启动
 *   POST   /api/accounts/:key/start
 *   POST   /api/accounts/:key/stop
 *   DELETE /api/accounts/:key
 *   GET    /api/accounts/:key/qrcode.png
 *   GET    /api/accounts/:key/logs?tail=200
 *   GET    /                        控制台页面
 */
import fs from 'node:fs'
import http from 'node:http'
import path from 'node:path'
import crypto from 'node:crypto'
import { fileURLToPath } from 'node:url'
import { loadConfig, preflight } from './config.js'
import { allocatePort } from './ports.js'
import { createAccount, isValidKey, listAccounts, readAccount } from './accountStore.js'
import { createAccountPool } from './pool.js'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const PUBLIC_DIR = path.join(HERE, '..', 'public')
const MAX_BODY = 64 * 1024

export function createServer(deps = {}) {
  const cfg = deps.cfg || loadConfig()
  const guard = () => preflight(cfg)
  const pool = createAccountPool({ cfg, preflight: guard })

  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, `http://${req.headers.host || '127.0.0.1'}`)
    const pathname = url.pathname

    try {
      if (pathname === '/' || pathname === '/index.html') {
        return sendFile(res, path.join(PUBLIC_DIR, 'index.html'), 'text/html; charset=utf-8')
      }
      if (pathname === '/api/health') {
        const p = guard()
        return json(res, 200, {
          ok: p.ok,
          problems: p.problems,
          dataRoot: cfg.dataRoot,
          shellRoot: cfg.shellRoot,
          qqExe: cfg.qqExe,
          accounts: listAccounts(cfg).length,
        })
      }

      if (!pathname.startsWith('/api/')) return json(res, 404, { error: 'not_found' })
      if (!authorized(req, cfg)) return json(res, 401, { error: 'unauthorized' })

      if (pathname === '/api/accounts' && req.method === 'GET') {
        return json(res, 200, { accounts: pool.statusList() })
      }

      if (pathname === '/api/accounts' && req.method === 'POST') {
        const body = await readJsonBody(req)
        const key = String(body.key || `acct-${crypto.randomBytes(3).toString('hex')}`)
        if (!isValidKey(key)) return json(res, 400, { error: 'invalid_key' })
        if (!readAccount(cfg, key) && listAccounts(cfg).length >= cfg.maxAccounts) {
          return json(res, 409, { error: 'max_accounts_reached', max: cfg.maxAccounts })
        }
        const account = await createAccount(cfg, { key, note: body.note || '' }, { allocatePort })
        const snapshot = await pool.start(key)
        return json(res, 201, { account, snapshot })
      }

      const m = pathname.match(/^\/api\/accounts\/([^/]+)(?:\/(start|stop|logs|qrcode\.png))?$/)
      if (m) {
        const key = decodeURIComponent(m[1])
        const action = m[2] || ''
        if (!isValidKey(key)) return json(res, 400, { error: 'invalid_key' })

        if (!action && req.method === 'GET') return json(res, 200, { snapshot: pool.status(key) })
        if (!action && req.method === 'DELETE') {
          const dir = pool.remove(key)
          await fs.promises.rm(dir, { recursive: true, force: true })
          return json(res, 200, { removed: key })
        }
        if (action === 'start' && req.method === 'POST') return json(res, 200, { snapshot: await pool.start(key) })
        if (action === 'stop' && req.method === 'POST') return json(res, 200, { snapshot: pool.stop(key) })
        if (action === 'logs' && req.method === 'GET') {
          const tail = Number(url.searchParams.get('tail') || 200)
          return json(res, 200, { lines: pool.logs(key, Number.isFinite(tail) ? tail : 200) })
        }
        if (action === 'qrcode.png' && req.method === 'GET') {
          const png = pool.qrcode(key)
          if (!png) return json(res, 404, { error: 'qrcode_not_ready' })
          res.writeHead(200, { 'content-type': 'image/png', 'cache-control': 'no-store' })
          return res.end(png)
        }
        return json(res, 405, { error: 'method_not_allowed' })
      }

      return json(res, 404, { error: 'not_found' })
    } catch (err) {
      const message = err?.message || String(err)
      const status = /not_found/.test(message) ? 404 : /preflight_failed|missing_file/.test(message) ? 409 : 500
      return json(res, status, { error: message })
    }
  })

  return { server, cfg, pool }
}

function authorized(req, cfg) {
  if (!cfg.gatewayToken) return true
  const token = req.headers['x-gateway-token'] || ''
  const a = Buffer.from(String(token))
  const b = Buffer.from(cfg.gatewayToken)
  return a.length === b.length && crypto.timingSafeEqual(a, b)
}

function json(res, status, payload) {
  const body = JSON.stringify(payload)
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' })
  res.end(body)
}

function sendFile(res, file, type) {
  try {
    const buf = fs.readFileSync(file)
    res.writeHead(200, { 'content-type': type, 'cache-control': 'no-store' })
    res.end(buf)
  } catch {
    json(res, 404, { error: 'not_found' })
  }
}

function readJsonBody(req) {
  return new Promise((resolve, reject) => {
    let size = 0
    const chunks = []
    req.on('data', (chunk) => {
      size += chunk.length
      if (size > MAX_BODY) {
        reject(new Error('body_too_large'))
        req.destroy()
        return
      }
      chunks.push(chunk)
    })
    req.on('end', () => {
      if (!chunks.length) return resolve({})
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString('utf8')))
      } catch {
        reject(new Error('invalid_json'))
      }
    })
    req.on('error', reject)
  })
}

// ---- 进程入口 ----
const isMain = process.argv[1] && path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url))

if (isMain) {
  const { server, cfg, pool } = createServer()
  fs.mkdirSync(cfg.accountsRoot, { recursive: true })

  const p = preflight(cfg)
  for (const problem of p.problems) console.warn(`[preflight] ${problem.code}: ${problem.detail}`)

  server.listen(cfg.gatewayPort, cfg.gatewayHost, () => {
    console.log(`[gateway] listening http://${cfg.gatewayHost}:${cfg.gatewayPort}`)
    console.log(`[gateway] dataRoot=${cfg.dataRoot}`)
    console.log(`[gateway] shellRoot=${cfg.shellRoot}`)
    console.log(`[gateway] qqExe=${cfg.qqExe || '(unset)'}`)
    const autoStart = process.env.AUTO_START || ''
    if (autoStart) {
      const keys = autoStart === 'all' ? listAccounts(cfg).map((a) => a.key) : autoStart.split(',').map((s) => s.trim())
      for (const key of keys.filter(Boolean)) {
        pool.start(key).then(
          () => console.log(`[gateway] auto-started ${key}`),
          (err) => console.warn(`[gateway] auto-start ${key} failed: ${err.message}`),
        )
      }
    }
  })

  const shutdown = (signal) => {
    console.log(`[gateway] ${signal} received, stopping all accounts`)
    pool.stopAll()
    server.close(() => process.exit(0))
    setTimeout(() => process.exit(0), 5000).unref()
  }
  process.on('SIGINT', () => shutdown('SIGINT'))
  process.on('SIGTERM', () => shutdown('SIGTERM'))
  process.on('unhandledRejection', (err) => console.error('[gateway] unhandledRejection', err))
}
