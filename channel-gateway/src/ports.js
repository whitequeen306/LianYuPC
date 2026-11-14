/**
 * 端口分配：先试绑定再放行（"能 bind 才算空闲"）。
 *
 * 只做一次性判定，不长期占坑——真正的占用由 NapCat 子进程负责。两台号之间
 * 的分配间隔由调用方用 reserved 集合保证，避免并发分配同一个端口。
 */
import net from 'node:net'

export function isPortFree(port, host = '127.0.0.1', timeoutMs = 1500) {
  return new Promise((resolve) => {
    const server = net.createServer()
    let settled = false
    const done = (free) => {
      if (settled) return
      settled = true
      try {
        server.close()
      } catch {
        /* already closed */
      }
      resolve(free)
    }
    server.once('error', () => done(false))
    server.once('listening', () => done(true))
    // 某些防火墙策略下 listen 既不成功也不报错，用超时兜住，按"占用"处理更安全
    const timer = setTimeout(() => done(false), timeoutMs)
    timer.unref?.()
    server.listen({ port, host, exclusive: true })
  })
}

/**
 * 从 base 起递增找第一个空闲且未被预留的端口。
 * @param {number} base
 * @param {{ host?: string, reserved?: Set<number>, span?: number }} [opts]
 */
export async function allocatePort(base, { host = '127.0.0.1', reserved = new Set(), span = 2000 } = {}) {
  for (let port = base; port < base + span; port++) {
    if (reserved.has(port)) continue
    // eslint-disable-next-line no-await-in-loop -- 顺序探测是语义要求：先到先得
    if (await isPortFree(port, host)) return port
  }
  throw new Error(`no_free_port:${base}`)
}
