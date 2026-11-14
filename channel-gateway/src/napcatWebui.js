/**
 * NapCat WebUI HTTP API 客户端（拿二维码 / 查登录态）。
 *
 * 端点与报文形状均取自 NapCat 打包产物 napcat.mjs（2026-11 版本）实测字符串：
 *   POST /api/auth/login            body { hash }        → data.Credential（base64 JSON）
 *   POST /api/QQLogin/GetQQLoginQrcode                   → data.qrcode（二维码里编码的 URL）
 *   POST /api/QQLogin/CheckLoginStatus                    → data{isLogin,isOffline,qrcodeurl,loginError}
 * 其中 hash = sha256(token + ".napcat") 的 hex（同 NapCat 内部 generatePasswordHash）。
 *
 * 注意 GetQQLoginQrcode 返回的是"二维码内容 URL"，不是图片；图片由 NapCat 自己
 * 落在 <workdir>/cache/qrcode.png —— 直接读文件即可，服务端不必引二维码库。
 */
import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'

const REQUEST_TIMEOUT_MS = 8000

export function passwordHash(token) {
  return crypto.createHash('sha256').update(`${token}.napcat`).digest('hex')
}

/** 兼容两种包装：{code:0,data} 与裸对象 */
function unwrap(body) {
  if (body && typeof body === 'object' && 'data' in body && body.data !== undefined) {
    return body.data
  }
  return body
}

async function postJson(url, payload, { credential } = {}) {
  const headers = { 'content-type': 'application/json' }
  if (credential) headers.authorization = `Bearer ${credential}`
  const res = await fetch(url, {
    method: 'POST',
    headers,
    body: JSON.stringify(payload ?? {}),
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
  })
  const text = await res.text()
  let body = null
  try {
    body = text ? JSON.parse(text) : null
  } catch {
    body = { raw: text }
  }
  if (!res.ok) {
    throw new Error(`webui_http_${res.status}:${JSON.stringify(body).slice(0, 200)}`)
  }
  if (body && typeof body === 'object' && body.code !== undefined && body.code !== 0) {
    throw new Error(`webui_code_${body.code}:${body.message || ''}`)
  }
  return unwrap(body)
}

export function webuiBase(account) {
  return `http://127.0.0.1:${account.webuiPort}`
}

/** 登录取 Credential；WebUI 未起来时抛错，由轮询方忽略重试 */
export async function login(account) {
  const data = await postJson(`${webuiBase(account)}/api/auth/login`, {
    hash: passwordHash(account.webuiToken),
  })
  const credential = data?.Credential
  if (!credential) throw new Error('webui_login_no_credential')
  return credential
}

export async function checkLoginStatus(account, credential) {
  return postJson(`${webuiBase(account)}/api/QQLogin/CheckLoginStatus`, {}, { credential })
}

export async function getQrcode(account, credential) {
  return postJson(`${webuiBase(account)}/api/QQLogin/GetQQLoginQrcode`, {}, { credential })
}

/**
 * 读 NapCat 落在 workdir 的二维码 PNG。
 * @returns {Buffer|null}
 */
export function readQrcodeImage(workdir) {
  const file = path.join(workdir, 'cache', 'qrcode.png')
  try {
    const stat = fs.statSync(file)
    if (!stat.isFile() || stat.size === 0) return null
    return fs.readFileSync(file)
  } catch {
    return null
  }
}
