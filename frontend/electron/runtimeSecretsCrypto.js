import crypto from 'crypto'

/** v1 XOR（旧包兼容，仅当存在 client-build.json 时使用） */
export const LEGACY_PEPPER = 'LianYu-RtSec-v1-8F3C2A1B'

/** v2：拆成片段，避免源码里一整条 grep 命中 */
const BOOTSTRAP_PARTS = ['Lian', 'Yu-', 'Rt', 'Boot-', 'v2']

export function bootstrapSalt() {
  return BOOTSTRAP_PARTS.join('')
}

export function deriveLegacyKey(version, buildId) {
  return crypto.createHash('sha256').update(`${version}:${buildId}:${LEGACY_PEPPER}`).digest()
}

export function deriveBootstrapKey(version, buildId) {
  return crypto.createHash('sha256').update(`pepper-wrap:${version}:${buildId}:${bootstrapSalt()}`).digest()
}

export function derivePayloadKey(version, buildId, pepper) {
  return crypto.createHash('sha256').update(`payload:${version}:${buildId}:${pepper}`).digest()
}

function aesGcmEncrypt(key, plaintext) {
  const iv = crypto.randomBytes(12)
  const cipher = crypto.createCipheriv('aes-256-gcm', key.subarray(0, 32), iv)
  const encrypted = Buffer.concat([cipher.update(plaintext), cipher.final()])
  const tag = cipher.getAuthTag()
  return Buffer.concat([iv, tag, encrypted])
}

function aesGcmDecrypt(key, blob) {
  if (!blob || blob.length < 29) return null
  const iv = blob.subarray(0, 12)
  const tag = blob.subarray(12, 28)
  const encrypted = blob.subarray(28)
  try {
    const decipher = crypto.createDecipheriv('aes-256-gcm', key.subarray(0, 32), iv)
    decipher.setAuthTag(tag)
    return Buffer.concat([decipher.update(encrypted), decipher.final()])
  } catch {
    return null
  }
}

export function decodeLegacyXor(buf, version, buildId) {
  if (!buf || buf.length < 19) return null
  if (buf[0] !== 1) return null
  const nonce = buf.subarray(1, 17)
  const len = buf.readUInt16BE(17)
  if (19 + len > buf.length) return null
  const xored = buf.subarray(19, 19 + len)
  const key = deriveLegacyKey(version, buildId)
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
 * v2 layout:
 * [0] u8 format=2
 * [1..2] u16 versionLen + version utf8
 * [...] buildId 16 bytes
 * u16 pepperLen + pepperBlob (AES-GCM)
 * u16 payloadLen + payloadBlob (AES-GCM)
 */
export function encodeSecretsV2({ version, buildId, pepper, payload }) {
  const versionBuf = Buffer.from(String(version), 'utf8')
  const buildIdBuf = Buffer.from(String(buildId), 'hex')
  if (buildIdBuf.length !== 16) {
    throw new Error('encodeSecretsV2: buildId must be 32 hex chars')
  }
  const pepperStr = String(pepper || '')
  if (!pepperStr || pepperStr.length > 128) {
    throw new Error('encodeSecretsV2: invalid pepper')
  }

  const bootstrapKey = deriveBootstrapKey(version, buildId)
  const pepperBlob = aesGcmEncrypt(bootstrapKey, Buffer.from(pepperStr, 'utf8'))
  const payloadKey = derivePayloadKey(version, buildId, pepperStr)
  const payloadBlob = aesGcmEncrypt(payloadKey, Buffer.from(JSON.stringify(payload), 'utf8'))

  return Buffer.concat([
    Buffer.from([2]),
    Buffer.from([(versionBuf.length >> 8) & 0xff, versionBuf.length & 0xff]),
    versionBuf,
    buildIdBuf,
    Buffer.from([(pepperBlob.length >> 8) & 0xff, pepperBlob.length & 0xff]),
    pepperBlob,
    Buffer.from([(payloadBlob.length >> 8) & 0xff, payloadBlob.length & 0xff]),
    payloadBlob,
  ])
}

export function decodeSecretsV2(buf) {
  if (!buf || buf.length < 3 || buf[0] !== 2) return null
  const versionLen = buf.readUInt16BE(1)
  let offset = 3 + versionLen + 16
  if (offset + 4 > buf.length) return null
  const version = buf.subarray(3, 3 + versionLen).toString('utf8')
  const buildId = buf.subarray(3 + versionLen, 3 + versionLen + 16).toString('hex')

  const pepperLen = buf.readUInt16BE(offset)
  offset += 2
  if (offset + pepperLen + 2 > buf.length) return null
  const pepperBlob = buf.subarray(offset, offset + pepperLen)
  offset += pepperLen

  const payloadLen = buf.readUInt16BE(offset)
  offset += 2
  if (offset + payloadLen > buf.length) return null
  const payloadBlob = buf.subarray(offset, offset + payloadLen)

  const bootstrapKey = deriveBootstrapKey(version, buildId)
  const pepperPlain = aesGcmDecrypt(bootstrapKey, pepperBlob)
  if (!pepperPlain) return null
  const pepper = pepperPlain.toString('utf8')
  const payloadKey = derivePayloadKey(version, buildId, pepper)
  const payloadPlain = aesGcmDecrypt(payloadKey, payloadBlob)
  if (!payloadPlain) return null
  try {
    return { version, buildId, payload: JSON.parse(payloadPlain.toString('utf8')) }
  } catch {
    return null
  }
}

export function decodeRuntimeSecretsBuffer(buf, legacyMeta) {
  const v2 = decodeSecretsV2(buf)
  if (v2?.payload) return v2.payload
  if (legacyMeta?.version && legacyMeta?.buildId) {
    return decodeLegacyXor(buf, legacyMeta.version, legacyMeta.buildId)
  }
  return null
}
