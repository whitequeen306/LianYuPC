/**
 * Token 内存态存储（#14：移除 localStorage 加密剧场）。
 *
 * 历史：曾用 AES-GCM 把 token 加密后存 localStorage，密钥（_lkt）也明文放 localStorage——
 * 一旦渲染层被 XSS，攻击者直接读 localStorage 即可拿到 key+ciphertext 还原明文 token，
 * “加密”只是表演，并未提升安全基线（密钥与密文同处一域）。
 *
 * 现状：token 仅驻留渲染进程内存（rawToken），不再落 localStorage。
 *  - 登录后 stores/user setAuth 写内存 + 经 auth:set-session 同步主进程（safeStorage 落盘）。
 *  - 重载后内存丢失，由 auth/bootstrap prepareAuthRoute / bootstrapLauncherSession 向主进程
 *    auth:bootstrap-token 一次性取回明文注入内存（主进程 safeStorage 仍是唯一持久存储）。
 *
 * 为什么不彻底收归主进程、渲染层零接触 token？
 *   STOMP over WebSocket 的鉴权（connectHeaders.token）在 WS 握手成功后的帧里发送，
 *   主进程 webRequest 只能改 HTTP/Upgrade 头，无法注入 WS 帧内的 STOMP 头，
 *   故 STOMP 鉴权仍需渲染层持有 token 明文。彻底收归需后端引入一次性 ticket 机制，属另一议题。
 *   运行期内存暴露（XSS 可读 rawToken）为该方案已知残留面，已通过 #6 出口限流 + host 白名单、
 *   #9 asar 完整性等多层收敛攻击面。
 *
 * 对外接口（syncSetTokenCache/storeToken/readToken/syncToken/clearTokenStorage）保持不变，
 * 调用方（api 拦截器、STOMP、路由守卫、bootstrap）无需改动。
 */

/** 原始 token，仅驻留内存 */
let rawToken = null

/**
 * 登录成功后立即写入内存缓存，避免 persistSession 异步完成前 API 读不到 token
 */
export function syncSetTokenCache(value) {
  rawToken = value || null
}

/**
 * 登录后调用：写入内存缓存（#14 后不再落 localStorage）
 */
export async function storeToken(value) {
  syncSetTokenCache(value)
}

/**
 * 读取 token（启动恢复时用）。#14 后内存态重载即丢，实际恢复由 bootstrap.js
 * 经主进程 auth:bootstrap-token 注入；此函数保留以兼容非 Electron / 兜底路径。
 */
export async function readToken() {
  return rawToken
}

/**
 * 同步读取已缓存的 token（供 EventSource / 路由守卫 / STOMP 使用）
 * 注意：首次使用前必须先经 bootstrap 注入或 syncSetTokenCache 写入
 */
export function syncToken() {
  return rawToken || null
}

/**
 * 清除内存 token（登出时调用）
 */
export function clearTokenStorage() {
  rawToken = null
}
