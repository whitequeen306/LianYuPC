/**
 * NapCat 托管链冒烟测试（Phase 4）——无 GUI、不spawn QQ、不触碰真实 userData。
 *
 * 覆盖新代码风险面：
 *  - resolveLatestNapCatRelease（GitHub API / 缓存 / 兜底）
 *  - downloadAndExtractNapCat 全新下载：.part 落盘 → sha256 强校验 → extract-zip
 *  - downloadAndExtractNapCat 断点续传：预制 40% 残件 + sidecar → Range 206 续传 → 校验解压
 *  - ensureNapCatConfig：config 目录解析 + onebot11/webui 幂等写入
 *
 * 运行：npx esbuild ... --bundle → npx electron dist-electron/smoke-napcat.cjs
 * 退出码 0=全过；任一阶段失败即抛错退出 1。
 */
import { app } from 'electron'
import os from 'os'
import path from 'path'
import fs from 'fs'
import { resolveLatestNapCatRelease, getNapCatInstallRoot, locateNapCatEntry, isNapCatInstalled, wipeNapCatInstall } from './napcatRuntime/napcatRelease.js'
import { downloadAndExtractNapCat } from './napcatRuntime/napcatDownloader.js'
import { ensureNapCatConfig } from './napcatRuntime/napcatConfig.js'

const TMP_USERDATA = path.join(os.tmpdir(), 'lianyu-napcat-smoke')

function log(...a) { console.log('[smoke]', ...a) }

// 网络抖动重试（GitHub release 在本网络偶发 fetch failed / 超时）
async function withRetry(label, fn, attempts = 6) {
  let lastErr
  for (let i = 1; i <= attempts; i++) {
    try {
      return await fn()
    } catch (e) {
      lastErr = e
      log(`${label} attempt ${i}/${attempts} failed: ${e?.message || e}`)
      if (i < attempts) await new Promise((r) => setTimeout(r, 1500 * i))
    }
  }
  throw lastErr
}

async function main() {
  await app.whenReady()
  // 重定向 userData 到临时目录：install root / config / release cache 全部落入其中，
  // 不污染真实应用状态
  fs.rmSync(TMP_USERDATA, { recursive: true, force: true })
  fs.mkdirSync(TMP_USERDATA, { recursive: true })
  app.setPath('userData', TMP_USERDATA)
  log('userData =', app.getPath('userData'))

  // ---- 1. 发行解析 ----
  const release = await resolveLatestNapCatRelease({ force: true })
  log('release =', release.tag, 'sha=', release.sha256.slice(0, 12), 'size=', release.size, 'url=', release.assetUrl)
  if (!release.assetUrl || !release.sha256) throw new Error('release resolve returned incomplete info')

  const installRoot = getNapCatInstallRoot()
  log('installRoot =', installRoot)

  // ---- 2. 全新下载 → 校验 → 解压 ----
  log('--- Phase A: fresh download ---')
  const mon = setInterval(() => {
    const p = `${installRoot}.zip.part`, s = `${installRoot}.zip.part.json`
    let pz = -1
    try { pz = fs.statSync(p).size } catch { /* */ }
    log('monitor partExists=', pz !== -1, 'partSize=', pz, 'sidecarExists=', fs.existsSync(s))
  }, 3000)
  const firstBytesSeen = { value: 0 }
  const r1 = await withRetry('A download', () => downloadAndExtractNapCat({
    release,
    onProgress: (p) => {
      if (p.phase === 'downloading' && firstBytesSeen.value === 0 && p.received) firstBytesSeen.value = p.received
      if (p.phase === 'done') log('A progress done skipped=', p.skipped)
    },
  }))
  clearInterval(mon)
  log('A result =', r1, 'firstBytesSeen=', firstBytesSeen.value)
  if (!isNapCatInstalled(installRoot)) throw new Error('fresh download: entry not found after extract')
  const entry1 = locateNapCatEntry(installRoot)
  log('A entry =', entry1 && { exe: entry1.exe, bat: entry1.bat, cwd: entry1.cwd })
  if (!entry1 || (!entry1.exe && !entry1.bat)) throw new Error('fresh download: entry missing exe/bat')
  // .part 应已清理
  if (fs.existsSync(`${installRoot}.zip.part`)) throw new Error('fresh download: .zip.part not cleaned')

  // ---- 3. 断点续传：清空安装根 → 预制 40% 残件 + sidecar → 续传 ----
  log('--- Phase B: resume from 40% partial ---')
  wipeNapCatInstall()
  if (isNapCatInstalled(installRoot)) throw new Error('wipe failed: still installed')
  // 用 Range 只取前 40% 字节构造残件（避免拉整包；服务器忽略 Range 返 200 时退回截取整包）
  const partialLen = Math.max(1, Math.floor(Number(release.size) * 0.4))
  const partialBuf = await withRetry('B partial-fetch', async () => {
    const res = await fetch(release.assetUrl, { redirect: 'follow', headers: { Range: `bytes=0-${partialLen - 1}` } })
    if (!res.ok && res.status !== 206) throw new Error(`partial-fetch HTTP ${res.status}`)
    return Buffer.from(await res.arrayBuffer()).slice(0, partialLen)
  })
  fs.mkdirSync(path.dirname(installRoot), { recursive: true })
  fs.writeFileSync(`${installRoot}.zip.part`, partialBuf)
  fs.writeFileSync(`${installRoot}.zip.part.json`, JSON.stringify({ sha256: release.sha256, size: release.size, assetUrl: release.assetUrl }))
  log('B pre-created partial =', partialBuf.length, 'bytes (target =', partialLen, ', total =', release.size, ')')

  // 第 1 次 'downloading' 是 fetch 前的续传起点预发（received=resumeFrom=partialLen），不反映流式字节，须跳过；
  // 取其后所有流式 emit 的最小 received：真续传恒 >= partialLen；服务器忽略 Range 从头下时会出现 < partialLen。
  // 计数器放在 withRetry 回调内，每次尝试独立计数，只采信最终成功那次。
  const { r: r2, minStreamReceived, dlEmitCount } = await withRetry('B resume-download', async () => {
    let dlEmitCount = 0
    let minStreamReceived = Infinity
    const r = await downloadAndExtractNapCat({
      release,
      onProgress: (p) => {
        if (p.phase !== 'downloading') return
        dlEmitCount++
        if (dlEmitCount > 1 && typeof p.received === 'number' && p.received < minStreamReceived) {
          minStreamReceived = p.received
        }
      },
    })
    return { r, minStreamReceived, dlEmitCount }
  })
  log('B result =', r2, 'minStreamReceived =', minStreamReceived, 'dlEmitCount =', dlEmitCount)
  if (minStreamReceived === Infinity) throw new Error('resume: no streaming progress emit observed (download too fast for throttle?)')
  if (minStreamReceived < partialLen) throw new Error(`resume did not continue from partial: minStream=${minStreamReceived} partial=${partialLen}`)
  if (!isNapCatInstalled(installRoot)) throw new Error('resume download: entry not found after extract')
  if (fs.existsSync(`${installRoot}.zip.part`)) throw new Error('resume download: .zip.part not cleaned')

  // ---- 4. 配置写入 ----
  log('--- Phase C: ensureNapCatConfig ---')
  const cfg = ensureNapCatConfig({ wsPort: 3001, wsToken: 'smoke-ws', webuiPort: 6099, webuiToken: 'smoke-webui' })
  log('C config =', cfg)
  if (!cfg.configDir) throw new Error('config: no configDir')
  if (Number(cfg.wsPort) !== 3001 || Number(cfg.webuiPort) !== 6099) throw new Error('config: ports not reflected')
  if (cfg.wsToken !== 'smoke-ws' || cfg.webuiToken !== 'smoke-webui') throw new Error('config: tokens not reflected')

  log('ALL CHECKS PASSED')
  app.quit()
}

// 硬超时兜底（网络卡死 / 重试退避累积时强制退出）
setTimeout(() => { console.error('[smoke] TIMEOUT'); process.exit(2) }, 540000).unref()

main().catch((e) => { console.error('[smoke] ERROR:', e?.stack || e); process.exit(1) })
