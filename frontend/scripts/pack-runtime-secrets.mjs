import fs from 'fs'
import path from 'path'
import { encodeSecretsV2 } from '../electron/runtimeSecretsCrypto.js'

const PINNED_SPKI = 'EdDpp/Z9REuRjqZLzXXrOW8opTtR8Yph2YM0s+xuLss='

function resolvePackPepper(explicit) {
  const fromArg = String(explicit || '').trim()
  if (fromArg) return fromArg
  const fromEnv = String(process.env.LIANYU_RUNTIME_SECRETS_PEPPER || '').trim()
  if (fromEnv) return fromEnv
  throw new Error(
    'packRuntimeSecrets: set LIANYU_RUNTIME_SECRETS_PEPPER in repo .env or environment before electron:release',
  )
}

/**
 * @param {{ version: string, buildId: string, apiOrigin: string, certFingerprint: string, pepper?: string, outPath: string }} opts
 */
export function packRuntimeSecrets(opts) {
  const apiOrigin = String(opts.apiOrigin || '').trim().replace(/\/$/, '')
  const certFingerprint = String(opts.certFingerprint || '').trim()
  if (!apiOrigin) {
    throw new Error('packRuntimeSecrets: apiOrigin is required')
  }

  const pepper = resolvePackPepper(opts.pepper)
  const payload = {
    apiOrigin,
    certFingerprint,
    pinnedSpki: PINNED_SPKI,
  }

  const encoded = encodeSecretsV2({
    version: opts.version,
    buildId: opts.buildId,
    pepper,
    payload,
  })

  fs.mkdirSync(path.dirname(opts.outPath), { recursive: true })
  fs.writeFileSync(opts.outPath, encoded)
  console.log(`Runtime secrets packed (v2 AES-GCM): ${path.basename(opts.outPath)}`)
}
