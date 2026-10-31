/**
 * 更新清单（latest.yml）的分离签名校验。
 *
 * 为什么需要：当前更新链只校验安装包的 SHA-512，而 SHA-512 本身来自 latest.yml。
 * 一旦清单被替换（MinIO 被写、反向代理被改、发版脚本被投毒），攻击者可以把
 * 「安装包 + 摘要」一起换掉，客户端无从分辨。证书 SPKI pin 只保护传输中的
 * 「是不是我这台服务器」，保护不了「服务器上的内容是不是我发的」。
 *
 * 方案：Ed25519 分离签名。签名覆盖 latest.yml 的**原始字节**（任何字段改动都会失效），
 * 私钥只存在于发版机器（`LIANYU_UPDATE_SIGNING_KEY`），公钥随客户端打包
 * （`updateManifestPublicKey`）。签名文件为同目录的 `latest.yml.sig`（base64）。
 *
 * 为什么不用 Authenticode：项目暂无代码签名证书（`signAndEditExecutable:false`），
 * 校验 Authenticode 会把所有正常更新判为失败。
 *
 * 上线顺序（必须遵守，否则会中断更新）：
 *   1. 部署带 `latest.yml.sig` 路由的 nginx；
 *   2. 用 `scripts/sign_update_manifest.py` 签一版并随 latest.yml 上传；
 *   3. 最后把公钥写进 `.env.production.cloud` 并重打客户端。
 * 客户端只有拿到公钥才强制校验；没有公钥时保持旧行为（只打一次告警）。
 */
import crypto from 'crypto'

/**
 * @param {object} input
 * @param {string} input.payload latest.yml 原始文本
 * @param {string} input.signatureBase64 64 字节签名的 base64
 * @param {string} input.publicKeyBase64 SPKI DER 公钥的 base64
 * @returns {boolean}
 */
export function verifyManifestSignature({ payload, signatureBase64, publicKeyBase64 }) {
  if (!payload || !signatureBase64 || !publicKeyBase64) return false
  try {
    const publicKey = crypto.createPublicKey({
      key: Buffer.from(String(publicKeyBase64).trim(), 'base64'),
      format: 'der',
      type: 'spki',
    })
    return crypto.verify(
      null,
      Buffer.from(String(payload), 'utf8'),
      publicKey,
      Buffer.from(String(signatureBase64).trim(), 'base64'),
    )
  } catch {
    return false
  }
}
