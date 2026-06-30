import fs from 'fs'
import path from 'path'
import { decodeRuntimeSecretsBuffer } from './runtimeSecretsCrypto.js'

const DEFAULT_API_ORIGIN = 'http://localhost:8080'
export const SECRETS_FILENAME = 'rtcfg.dat'

/** @type {{ apiOrigin: string, certFingerprint: string, pinnedSpki: string } | null} */
let cachedSecrets = null

function readLegacyMeta(secretsDir, metaPath) {
  if (metaPath && fs.existsSync(metaPath)) {
    try {
      return JSON.parse(fs.readFileSync(metaPath, 'utf8'))
    } catch {
      return null
    }
  }
  const fallback = path.join(secretsDir, 'client-build.json')
  if (!fs.existsSync(fallback)) return null
  try {
    return JSON.parse(fs.readFileSync(fallback, 'utf8'))
  } catch {
    return null
  }
}

/**
 * @param {{ secretsDir: string, metaPath?: string, isPackaged: boolean, isDev: boolean }} opts
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

  const binPath = path.join(secretsDir, SECRETS_FILENAME)
  const legacyPath = path.join(secretsDir, 'runtime-secrets.bin')
  const buf = fs.existsSync(binPath)
    ? fs.readFileSync(binPath)
    : fs.existsSync(legacyPath)
      ? fs.readFileSync(legacyPath)
      : null
  if (!buf) {
    throw new Error(`${SECRETS_FILENAME} missing`)
  }

  const legacyMeta = buf[0] === 1 ? readLegacyMeta(secretsDir, metaPath) : null
  const decoded = decodeRuntimeSecretsBuffer(buf, legacyMeta)
  if (!decoded?.apiOrigin) {
    throw new Error(`${SECRETS_FILENAME} decode failed`)
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
