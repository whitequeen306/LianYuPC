/**
 * NapCat 运行时——下载 + 校验 + 解压（B 方案自管托管）。
 *
 * 流程：fetch Shell zip（支持 HTTP Range 断点续传 + .part 残文件复用 +
 *   sha256 实时累加 + 进度回调）→ 与发行 digest 强校验（不符删档抛错）
 *   → extract-zip 解压到安装根 → 清理临时 .part。
 *
 * 续传策略：下载落盘为 `<installRoot>.zip.part`，旁挂 `.part.json` 记录
 *   {sha256,size,assetUrl}。中断后再次进入时，若 sidecar 与当前发行一致且
 *   .part 实际大小 <= size，则带 `Range: bytes=<已下>-` 续传；服务器返回 206
 *   则追加并先用已落盘字节播种哈希，返回 200 则整包重下。Shell 是 ~29MB 整包，
 *   续传时重读已落盘部分播种哈希有一次顺序读盘开销（百毫秒级，远小于重下）。
 *   最终以 sha256 强校验为准——即便残文件损坏，校验失败也会删档重来，至多浪费一个周期。
 *
 * Shell 是 ~29MB 的自包含整包（含 napcat.mjs 核心）；QQ.exe 由用户预先安装，
 * NapCat 不再另行拉取——故 onProgress 覆盖的 zip 下载/解压即安装全过程，
 * 下载完成即可进入 'launching'。
 *
 * 仅主进程使用；不经过 Electron net（直连 github.com / objects.githubusercontent.com）。
 */
import fs from 'fs'
import path from 'path'
import crypto from 'crypto'
import { Readable } from 'stream'
import extract from 'extract-zip'
import { getNapCatInstallRoot, isNapCatInstalled } from './napcatRelease.js'

/** 下载临时件后缀（仅在 sha256 校验通过后才解压并删除） */
const PART_EXT = '.zip.part'
const SIDE_EXT = '.zip.part.json'

function partPath(installRoot) {
  return `${installRoot}${PART_EXT}`
}
function sidecarPath(installRoot) {
  return `${installRoot}${SIDE_EXT}`
}

function readSidecar(installRoot) {
  try {
    return JSON.parse(fs.readFileSync(sidecarPath(installRoot), 'utf8'))
  } catch {
    return null
  }
}

function writeSidecar(installRoot, obj) {
  try {
    fs.writeFileSync(sidecarPath(installRoot), JSON.stringify(obj))
  } catch {
    /* sidecar 写入失败不阻断：最坏情况是丢失续传上下文、整包重下 */
  }
}

function clearPart(installRoot) {
  for (const p of [partPath(installRoot), sidecarPath(installRoot)]) {
    try {
      fs.rmSync(p, { force: true })
    } catch {
      /* ignore */
    }
  }
}

/** 续传时用已落盘字节播种哈希，使最终 digest 覆盖整包。返回可继续 update 的哈希对象。 */
async function seedHashFromFile(filePath) {
  const h = crypto.createHash('sha256')
  const rs = fs.createReadStream(filePath, { highWaterMark: 64 * 1024 })
  for await (const chunk of rs) h.update(chunk)
  return h
}

/**
 * 下载并解压 NapCat Shell。若已安装则跳过；未完成则断点续传。
 * @param {{ release: { assetUrl: string, sha256: string, size: number }, onProgress?: (p:{phase:string,received?:number,total?:number,percent?:number,speed?:number})=>void, signal?: AbortSignal, stallMs?: number }} opts
 * @returns {Promise<{ installRoot: string, skipped: boolean }>}
 */
export async function downloadAndExtractNapCat({ release, onProgress, signal, stallMs } = {}) {
  if (!release || !release.assetUrl || !release.sha256) {
    throw new Error('download aborted: release missing assetUrl/sha256')
  }
  const installRoot = getNapCatInstallRoot()
  if (isNapCatInstalled(installRoot)) {
    onProgress?.({ phase: 'done', skipped: true })
    return { installRoot, skipped: true }
  }

  const part = partPath(installRoot)
  fs.mkdirSync(path.dirname(part), { recursive: true })

  const expected = String(release.sha256).toLowerCase()
  const total = Number(release.size) || 0

  // 解析续传上下文：sidecar 与当前发行一致且 .part 大小合理才续传，否则整包重下
  let resumeFrom = 0
  const sidecar = readSidecar(installRoot)
  if (
    sidecar &&
    String(sidecar.sha256).toLowerCase() === expected &&
    sidecar.assetUrl === release.assetUrl &&
    fs.existsSync(part)
  ) {
    let onDisk = 0
    try {
      onDisk = fs.statSync(part).size
    } catch {
      onDisk = 0
    }
    if (onDisk > 0 && (!total || onDisk <= total)) {
      resumeFrom = onDisk
    }
  }
  if (resumeFrom === 0) clearPart(installRoot)
  // 落盘 sidecar（续传上下文），中断后据此判断是否同发行可续
  writeSidecar(installRoot, { sha256: expected, size: total, assetUrl: release.assetUrl })

  onProgress?.({
    phase: 'downloading',
    received: resumeFrom,
    total,
    percent: total ? Math.min(100, Math.round((resumeFrom / total) * 100)) : 0,
  })

  // 已下完但未及校验（中断在写完之后）：直接进入校验，不再发请求
  if (resumeFrom > 0 && total && resumeFrom >= total) {
    return finalizeDownload(installRoot, part, expected, release, onProgress, null, resumeFrom)
  }

  // 组合外部中断（stop/重装）与本地无进度看门狗：任一触发都 abort fetch。
  // 外部 signal 由 napcatHost 持有（stop 时 abort）；本地看门狗在 STALL_MS 内
  // 未收到新字节（含 TTFB）即 abort，防止网络 stall 永久挂起进度条。
  const controller = new AbortController()
  if (signal) {
    if (signal.aborted) controller.abort()
    else signal.addEventListener('abort', () => controller.abort(), { once: true })
  }
  const STALL_MS = Number(stallMs) > 0 ? Number(stallMs) : 60_000
  let stallTimer = null
  let stallAborted = false
  const touchStall = () => {
    if (stallTimer) clearTimeout(stallTimer)
    stallTimer = setTimeout(() => {
      stallAborted = true
      controller.abort()
    }, STALL_MS)
  }

  try {
    const headers = {}
    if (resumeFrom > 0) headers.Range = `bytes=${resumeFrom}-`
    touchStall()
    const res = await fetch(release.assetUrl, { redirect: 'follow', headers, signal: controller.signal })

    // 416 = 续传起点已到/超过文件末尾（已完整），视作下完直接校验
    if (res.status === 416) {
      return finalizeDownload(installRoot, part, expected, release, onProgress, null, resumeFrom)
    }
    if (!res.ok || !res.body) {
      throw new Error(`download failed: HTTP ${res.status}`)
    }

    const isPartial = res.status === 206 && resumeFrom > 0
    // 服务器忽略 Range 返回整包（200）：从头写。flags:'w' 会截断旧 .part，
    // 但显式删除以杜绝极少数写入失败时残留旧字节；sidecar 仍属同发行，保留不动
    // （切勿 clearPart——那会删掉刚写入的 sidecar，导致中断后再进无法续传）
    if (!isPartial) {
      resumeFrom = 0
      try {
        fs.rmSync(part, { force: true })
      } catch {
        /* ignore */
      }
    }

    // 续传：先读已落盘字节播种哈希；整包则新建哈希对象
    const hash = isPartial ? await seedHashFromFile(part) : crypto.createHash('sha256')
    const out = fs.createWriteStream(part, isPartial ? { flags: 'a' } : { flags: 'w' })
    let received = resumeFrom
    let lastReport = 0
    // 下载速率采样：每帧记 received 增量与时间增量算瞬时速率，用 EMA(0.5/0.5) 平滑
    // 避免单帧抖动；受限网络 RST 频发时瞬时速率会跳水，EMA 让显示稳定可读。
    let lastReceived = resumeFrom
    let lastSpeedTime = 0
    let emaSpeed = 0
    // Readable.fromWeb 把 fetch 的 web ReadableStream 转成 Node 流，便于逐块消费
    const nodeStream = Readable.fromWeb(res.body)
    try {
      for await (const chunk of nodeStream) {
        // 主动中断（stop/重装/无进度看门狗）：尽早抛出，交由 catch 收尾
        if (controller.signal.aborted) throw new Error('download aborted')
        // 处理背压：write 返回 false 时等待 drain；期间也须能被 abort 打断
        if (!out.write(chunk)) {
          await new Promise((resolve, reject) => {
            if (controller.signal.aborted) return reject(new Error('download aborted'))
            const onAbort = () => reject(new Error('download aborted'))
            out.once('drain', () => { controller.signal.removeEventListener('abort', onAbort); resolve() })
            controller.signal.addEventListener('abort', onAbort, { once: true })
          })
        }
        hash.update(chunk)
        received += chunk.length
        touchStall()
        const now = Date.now()
        if (onProgress && now - lastReport > 100) {
          lastReport = now
          // 首帧(lastSpeedTime=0)只采样基线不算速率；之后按 received 增量/时间增量
          // 算瞬时 bytes/sec，EMA 平滑。payload 多带 speed 供前端显示「x MB/s」。
          if (lastSpeedTime > 0) {
            const dt = now - lastSpeedTime
            if (dt > 0) {
              const inst = ((received - lastReceived) / dt) * 1000
              emaSpeed = emaSpeed > 0 ? emaSpeed * 0.5 + inst * 0.5 : inst
            }
          }
          lastSpeedTime = now
          lastReceived = received
          onProgress({
            phase: 'downloading',
            received,
            total,
            percent: total ? Math.min(100, Math.round((received / total) * 100)) : 0,
            speed: emaSpeed > 0 ? Math.round(emaSpeed) : 0,
          })
        }
      }
    } catch (e) {
      // 中断/失败：销毁流与写句柄，避免 .part 句柄泄漏（Windows 下致后续 rm EBUSY）
      try { nodeStream.destroy() } catch { /* ignore */ }
      try { out.destroy() } catch { /* ignore */ }
      throw e
    }
    await new Promise((resolve, reject) => {
      out.end((err) => (err ? reject(err) : resolve()))
    })

    return finalizeDownload(installRoot, part, expected, release, onProgress, hash, received)
  } catch (e) {
    // 无进度看门狗触发：抛出可读错误（区别于外部 stop 的 'download aborted'）
    if (stallAborted) throw new Error(`download stalled: no progress for ${STALL_MS}ms`)
    throw e
  } finally {
    if (stallTimer) { clearTimeout(stallTimer); stallTimer = null }
  }
}

/** sha256 强校验 → 通过则解压并清理 .part；不符则删档抛错（防断点/残文件损坏）。中间人防护不在本层——expected digest 由 napcatRelease 的 out-of-band PINNED_RELEASES 锚点在信道外提供，与下载本身的可被 MITM 通道分离（issue #5） */
async function finalizeDownload(installRoot, part, expected, release, onProgress, hash, received) {
  const total = Number(release.size) || 0
  const h = hash || (await seedHashFromFile(part))
  const digest = h.digest('hex')
  if (digest.toLowerCase() !== expected.toLowerCase()) {
    clearPart(installRoot)
    throw new Error(`sha256 mismatch: expected ${release.sha256}, got ${digest}`)
  }

  onProgress?.({ phase: 'extracting', received, total, percent: 100 })

  fs.mkdirSync(installRoot, { recursive: true })
  await extract(part, { dir: installRoot })

  // 解压成功后清理临时件（保留 .part 无意义）
  try {
    fs.rmSync(part, { force: true })
  } catch {
    /* 临时件清理失败不阻断 */
  }
  clearPart(installRoot)

  onProgress?.({ phase: 'done', skipped: false })
  return { installRoot, skipped: false }
}
