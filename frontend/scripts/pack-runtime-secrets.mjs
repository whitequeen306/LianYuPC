import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'

/**
 * 生成 runtime-secrets.bin：仅轻度混淆（obfuscation-only），非加密、不提供机密性。
 * 反混淆材料（version/buildId + 源码 PEPPER）随客户端发行，本地可逆。
 * 详见 electron/runtimeSecrets.js 文件头。永不在此打包真正敏感的值。
 */
/** Must match frontend/electron/runtimeSecrets.js */
const RUNTIME_SECRETS_PEPPER = 'LianYu-RtSec-v1-8F3C2A1B'
const PINNED_SPKI = 'EdDpp/Z9REuRjqZLzXXrOW8opTtR8Yph2YM0s+xuLss='

function deriveKey(version, buildId) {
  return crypto
    .createHash('sha256')
    .update(`${version}:${buildId}:${RUNTIME_SECRETS_PEPPER}`)
    .digest()
}

/**
 * @param {{ version: string, buildId: string, apiOrigin: string, certFingerprint: string, outPath: string }} opts
 */
export function packRuntimeSecrets(opts) {
  const apiOrigin = String(opts.apiOrigin || '').trim().replace(/\/$/, '')
  const certFingerprint = String(opts.certFingerprint || '').trim()
  if (!apiOrigin) {
    throw new Error('packRuntimeSecrets: apiOrigin is required')
  }

  const payload = JSON.stringify({
    apiOrigin,
    certFingerprint,
    pinnedSpki: PINNED_SPKI,
  })
  const nonce = crypto.randomBytes(16)
  const data = Buffer.from(payload, 'utf8')
  // 仅轻度混淆（obfuscation-only），非加密；见文件头。
  const key = deriveKey(opts.version, opts.buildId)
  const xored = Buffer.alloc(data.length)
  for (let i = 0; i < data.length; i++) {
    xored[i] = data[i] ^ key[i % key.length] ^ nonce[i % nonce.length]
  }

  const header = Buffer.alloc(19)
  header[0] = 1
  nonce.copy(header, 1)
  header.writeUInt16BE(data.length, 17)

  fs.mkdirSync(path.dirname(opts.outPath), { recursive: true })
  fs.writeFileSync(opts.outPath, Buffer.concat([header, xored]))
  console.log(`Runtime secrets packed: ${path.basename(opts.outPath)}`)
}
