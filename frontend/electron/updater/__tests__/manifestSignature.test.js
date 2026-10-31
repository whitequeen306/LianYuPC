import crypto from 'crypto'
import { describe, expect, it } from 'vitest'
import { verifyManifestSignature } from '../manifestSignature.js'

function makeKeyPair() {
  const { publicKey, privateKey } = crypto.generateKeyPairSync('ed25519')
  const publicKeyBase64 = publicKey
    .export({ format: 'der', type: 'spki' })
    .toString('base64')
  return { privateKey, publicKeyBase64 }
}

function sign(payload, privateKey) {
  return crypto.sign(null, Buffer.from(payload, 'utf8'), privateKey).toString('base64')
}

describe('verifyManifestSignature', () => {
  const manifest = 'version: 0.3.13\nurl: YuNian-Setup-0.3.13.exe\nsha512: abc\n'

  it('接受用对应私钥签名的原始字节', () => {
    const { privateKey, publicKeyBase64 } = makeKeyPair()
    const signatureBase64 = sign(manifest, privateKey)

    expect(verifyManifestSignature({ payload: manifest, signatureBase64, publicKeyBase64 })).toBe(true)
  })

  it('清单被改一个字节就失败（签名覆盖原始字节）', () => {
    const { privateKey, publicKeyBase64 } = makeKeyPair()
    const signatureBase64 = sign(manifest, privateKey)
    const tampered = manifest.replace('0.3.13', '0.3.14')

    expect(verifyManifestSignature({ payload: tampered, signatureBase64, publicKeyBase64 })).toBe(false)
  })

  it('换一把公钥就失败', () => {
    const { privateKey } = makeKeyPair()
    const { publicKeyBase64: otherPublicKey } = makeKeyPair()
    const signatureBase64 = sign(manifest, privateKey)

    expect(verifyManifestSignature({ payload: manifest, signatureBase64, publicKeyBase64: otherPublicKey })).toBe(false)
  })

  it('缺字段返回 false 而不是抛异常', () => {
    expect(verifyManifestSignature({ payload: manifest })).toBe(false)
    expect(verifyManifestSignature({})).toBe(false)
  })

  it('非法 base64 / 非 Ed25519 密钥返回 false', () => {
    expect(verifyManifestSignature({
      payload: manifest,
      signatureBase64: 'not-base64!!',
      publicKeyBase64: 'also-not-base64!!',
    })).toBe(false)

    // RSA 公钥不是 Ed25519，必须被判为无效而不是误放行
    const { publicKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 })
    const rsaPublicKeyBase64 = publicKey.export({ format: 'der', type: 'spki' }).toString('base64')
    expect(verifyManifestSignature({
      payload: manifest,
      signatureBase64: sign(manifest, makeKeyPair().privateKey),
      publicKeyBase64: rsaPublicKeyBase64,
    })).toBe(false)
  })
})
