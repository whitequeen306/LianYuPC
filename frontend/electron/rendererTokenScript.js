/**
 * 在渲染进程 executeJavaScript 中解密 secureToken 存储的 _ltt，供主进程 observe 等模块取 token。
 * 逻辑与 src/utils/secureToken.js 保持一致。
 */
export const RENDERER_AUTH_TOKEN_SCRIPT = `
(async function () {
  const KEY_STORE_KEY = '_lkt'
  const TOKEN_STORE_KEY = '_ltt'
  function hexToBytes(hex) {
    const bytes = new Uint8Array(hex.length / 2)
    for (let i = 0; i < hex.length; i += 2) {
      bytes[i / 2] = parseInt(hex.substring(i, i + 2), 16)
    }
    return bytes
  }
  function bytesToText(bytes) {
    return new TextDecoder().decode(bytes)
  }
  try {
    const combinedHex = localStorage.getItem(TOKEN_STORE_KEY)
    const keyHex = localStorage.getItem(KEY_STORE_KEY)
    if (!combinedHex || !keyHex) return null
    const raw = hexToBytes(keyHex)
    const key = await crypto.subtle.importKey(
      'raw', raw, { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']
    )
    const combined = hexToBytes(combinedHex)
    if (combined.length < 13) return null
    const iv = combined.slice(0, 12)
    const encrypted = combined.slice(12)
    const decrypted = await crypto.subtle.decrypt(
      { name: 'AES-GCM', iv }, key, encrypted
    )
    return bytesToText(new Uint8Array(decrypted))
  } catch {
    return null
  }
})()
`
