/**
 * Runtime config blob — obfuscation-only, NOT confidentiality protection.
 * XOR + build metadata hides API origin / cert hints from casual inspection only.
 * Never store real secrets (API keys, tokens, passwords) in runtime-secrets.bin.
 */
import fs from 'fs'
import crypto from 'crypto'
import path from 'path'

/** Must match frontend/scripts/pack-runtime-secrets.mjs */
export const RUNTIME_SECRETS_PEPPER = 'LianYu-RtSec-v1-8F3C2A1B'

const DEFAULT_API_ORIGIN = 'http://localhost:8080'
const SECRETS_FILENAME = 'runtime-secrets.bin'

/** @type {{ apiOrigin: string, certFingerprint: string, pinnedSpki: string } | null} */
let cachedSecrets = null

function deriveKey(version, buildId) {
  return crypto
    .createHash('sha256')
    .update(`${version}:${buildId}:${RUNTIME_SECRETS_PEPPER}`)
    .digest()
}

export function decodeRuntimeSecretsBuffer(buf, version, buildId) {
  if (!buf || buf.length < 19) return null
  const versionByte = buf[0]
  if (versionByte !== 1) return null
  const nonce = buf.subarray(1, 17)
  const len = buf.readUInt16BE(17)
  if (19 + len > buf.length) return null
  const xored = buf.subarray(19, 19 + len)
  const key = deriveKey(version, buildId)
  const plain = Buffer.alloc(len)
  for (let i = 0; i < len; i++) {
    plain[i] = xored[i] ^ key[i % key.length] ^ nonce[i % nonce.length]
  }
  try {
    return JSON.parse(plain.toString('utf8'))
  } catch {
    return null
  }
}

/**
 * @param {{ secretsDir: string, metaPath: string, isPackaged: boolean, isDev: boolean }} opts
 */
export function loadRuntimeSecrets(opts) {
  if (cachedSecrets) return cachedSecrets

  const { secretsDir, metaPath, isPackaged, isDev } = opts

  if (isDev || !isPackaged) {
    const apiOrigin = (
      process.env.LIANYU_API_ORIGIN
      || process.env.VITE_LIANYU_API_ORIGIN
      || DEFAULT_API_ORIGIN
    )
      .trim()
      .replace(/\/$/, '')
    cachedSecrets = {
      apiOrigin,
      certFingerprint: (
        process.env.LIANYU_CERT_FINGERPRINT
        || process.env.VITE_LIANYU_CERT_FINGERPRINT
        || ''
      ).trim(),
      pinnedSpki: 'EdDpp/Z9REuRjqZLzXXrOW8opTtR8Yph2YM0s+xuLss=',
    }
    return cachedSecrets
  }

  const meta = JSON.parse(fs.readFileSync(metaPath, 'utf8'))
  const binPath = path.join(secretsDir, SECRETS_FILENAME)
  const buf = fs.readFileSync(binPath)
  const decoded = decodeRuntimeSecretsBuffer(buf, meta.version, meta.buildId)
  if (!decoded?.apiOrigin) {
    console.error('[runtimeSecrets] runtime-secrets.bin decode failed')
    return null
  }
  cachedSecrets = {
    apiOrigin: String(decoded.apiOrigin).trim().replace(/\/$/, ''),
    certFingerprint: String(decoded.certFingerprint || '').trim(),
    pinnedSpki: String(decoded.pinnedSpki || '').trim(),
  }
  return cachedSecrets
}

export function getRuntimeSecrets() {
  return cachedSecrets
}
