import path from 'node:path'
import crypto from 'node:crypto'
import fs from 'node:fs'
import { fileURLToPath } from 'node:url'
import asarmor from 'asarmor'
import rcedit from 'rcedit'

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..')

/** asarmor 会拖慢 asar 读取，默认关闭以优先启动/桌宠速度 */
const ENABLE_ASARMOR = process.env.LIANYU_ENABLE_ASARMOR === '1'

function sleepSync(ms) {
  const end = Date.now() + ms
  while (Date.now() < end) { /* spin */ }
}

function removeIfExists(filePath) {
  if (fs.existsSync(filePath)) fs.unlinkSync(filePath)
}

function replaceFileWithRetry(src, dest, { retries = 12, delayMs = 250 } = {}) {
  for (let attempt = 1; attempt <= retries; attempt += 1) {
    try {
      removeIfExists(dest)
      fs.renameSync(src, dest)
      return
    } catch (err) {
      if (attempt === retries) throw err
      console.warn(`replace ${path.basename(dest)} attempt ${attempt}/${retries}: ${err.message}`)
      sleepSync(delayMs * attempt)
    }
  }
}

function sha256File(filePath) {
  const hash = crypto.createHash('sha256')
  hash.update(fs.readFileSync(filePath))
  return hash.digest('hex')
}

/** asarmor header patch — write to side file first to avoid app.asar rename lock on Windows */
async function patchAsarArchive(asarPath) {
  if (!fs.existsSync(asarPath)) {
    console.log('app.asar not found, skipping asarmor patch')
    return false
  }

  const patchedPath = `${asarPath}.patched`
  for (const stale of [`${asarPath}.tmp`, patchedPath, `${patchedPath}.tmp`]) {
    removeIfExists(stale)
  }

  console.log(`asarmor patching ${asarPath}`)
  const archive = await asarmor.open(asarPath)
  archive.patch()
  await archive.write(patchedPath)
  replaceFileWithRetry(patchedPath, asarPath)
  console.log('asarmor patch applied')
  return true
}

/** 写入 resources/asar-integrity.hex（须在 asarmor patch 之后，对最终 asar 算哈希） */
function writeAsarIntegrityHex(context) {
  const asarPath = path.join(context.appOutDir, 'resources', 'app.asar')
  if (!fs.existsSync(asarPath)) {
    console.log('app.asar not found, skipping integrity hash')
    return
  }

  const hexPath = path.join(context.appOutDir, 'resources', 'asar-integrity.hex')
  const hex = sha256File(asarPath)
  fs.writeFileSync(hexPath, hex, 'utf8')
  console.log(`asar integrity SHA-256: ${hex}`)
}

/** 把 logo 写进 exe，桌面快捷方式才会显示正确图标 */
export default async function afterPack(context) {
  if (context.electronPlatformName !== 'win32') return

  const asarPath = path.join(context.appOutDir, 'resources', 'app.asar')
  if (ENABLE_ASARMOR) {
    await patchAsarArchive(asarPath)
  } else {
    console.log('Skipped asarmor patch (set LIANYU_ENABLE_ASARMOR=1 to enable)')
  }

  const exeName = `${context.packager.appInfo.productFilename}.exe`
  const exePath = path.join(context.appOutDir, exeName)
  const iconPath = path.join(root, 'build', 'icon.ico')

  await rcedit(exePath, { icon: iconPath })
  writeAsarIntegrityHex(context)
}
