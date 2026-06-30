/**
 * 下载中断/无进度看门狗冒烟测试——本地 stall 服务器（不依赖 GitHub），验证：
 *  - 外部 signal abort：快速拒绝（非 stall 路径），.part 句柄释放
 *  - 无进度看门狗：stallMs 后抛 'download stalled'，.part 句柄释放
 * 运行：esbuild --bundle → electron dist-electron/smoke-abort.cjs；退出码 0=全过。
 */
import { app } from 'electron'
import http from 'http'
import os from 'os'
import path from 'path'
import fs from 'fs'
import { getNapCatInstallRoot, wipeNapCatInstall } from './napcatRuntime/napcatRelease.js'
import { downloadAndExtractNapCat } from './napcatRuntime/napcatDownloader.js'

const TMP_USERDATA = path.join(os.tmpdir(), 'lianyu-napcat-abort')
function log(...a) { console.log('[abort]', ...a) }

// 本地 stall 服务器：发一个 chunk 后永不结束（模拟网络 stall）
function startStallServer() {
  return new Promise((resolve) => {
    const server = http.createServer((req, res) => {
      res.writeHead(200, { 'Content-Type': 'application/zip' })
      res.write(Buffer.alloc(8192))
      // 不 res.end() → 流不结束，模拟 stall
    })
    server.listen(0, '127.0.0.1', () => resolve({ server, port: server.address().port }))
  })
}

function assert(cond, msg) { if (!cond) throw new Error('ASSERT FAIL: ' + msg) }

async function main() {
  await app.whenReady()
  fs.rmSync(TMP_USERDATA, { recursive: true, force: true })
  fs.mkdirSync(TMP_USERDATA, { recursive: true })
  app.setPath('userData', TMP_USERDATA)
  log('userData =', app.getPath('userData'))

  const installRoot = getNapCatInstallRoot()
  const { server, port } = await startStallServer()
  const assetUrl = `http://127.0.0.1:${port}/napcat.zip`
  // sha256/size 是假的：stall 不会进入 finalize，无需真实校验
  const release = { assetUrl, sha256: '0'.repeat(64), size: 999999, tag: 'test' }
  const partFile = `${installRoot}.zip.part`

  // ---- Test A: 外部 signal abort（stallMs 默认 60s 不会先触发）----
  log('--- Test A: external abort ---')
  wipeNapCatInstall()
  const ctrl = new AbortController()
  let aErr = null
  const aStart = Date.now()
  setTimeout(() => ctrl.abort(), 500)
  try {
    await downloadAndExtractNapCat({ release, signal: ctrl.signal })
    throw new Error('Test A: expected rejection but resolved')
  } catch (e) {
    aErr = e
  }
  const aDur = Date.now() - aStart
  log('A rejected in', aDur, 'ms:', aErr?.message)
  assert(aDur < 4000, `Test A: abort too slow (${aDur}ms)`)
  assert(!/stalled/.test(aErr?.message || ''), 'Test A: should not be stall path')
  fs.rmSync(partFile, { force: true }) // 句柄释放：不应抛 EBUSY
  log('A .part handle released (rmSync ok)')

  // ---- Test B: 无进度看门狗（stallMs=300，无外部 signal）----
  log('--- Test B: stall watchdog ---')
  wipeNapCatInstall()
  let bErr = null
  const bStart = Date.now()
  try {
    await downloadAndExtractNapCat({ release, stallMs: 300 })
    throw new Error('Test B: expected rejection but resolved')
  } catch (e) {
    bErr = e
  }
  const bDur = Date.now() - bStart
  log('B rejected in', bDur, 'ms:', bErr?.message)
  assert(bDur < 4000, `Test B: stall abort too slow (${bDur}ms)`)
  assert(/stalled/.test(bErr?.message || ''), `Test B: expected 'stalled' error, got: ${bErr?.message}`)
  fs.rmSync(partFile, { force: true })
  log('B .part handle released (rmSync ok)')

  server.close()
  log('ALL CHECKS PASSED')
}

main().then(() => app.exit(0)).catch((e) => { console.error('[abort] ERROR:', e?.stack || e); app.exit(1) })
