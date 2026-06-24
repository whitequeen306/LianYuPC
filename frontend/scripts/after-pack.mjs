import path from 'node:path'
import crypto from 'node:crypto'
import fs from 'node:fs'
import { fileURLToPath } from 'node:url'
import rcedit from 'rcedit'
import { extractAll, createPackage } from '@electron/asar'

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..')
const ASAR_HASH_PLACEHOLDER = '__ASAR_HASH__'
const MAIN_REL = path.join('dist-electron', 'main.js')

function sha256File(filePath) {
  const hash = crypto.createHash('sha256')
  hash.update(fs.readFileSync(filePath))
  return hash.digest('hex')
}

function patchMainJsEmbeddedHash(mainJsPath, expectedHex) {
  let content = fs.readFileSync(mainJsPath, 'utf8')
  if (!content.includes(ASAR_HASH_PLACEHOLDER)) {
    throw new Error(`ASAR hash placeholder not found in ${mainJsPath}`)
  }
  content = content.replaceAll(ASAR_HASH_PLACEHOLDER, expectedHex)
  fs.writeFileSync(mainJsPath, content, 'utf8')
}

/** 计算 app.asar SHA-256，写入 resources/asar-integrity.hex，并注入 main.js 常量 */
async function computeAndInjectAsarIntegrity(context) {
  const asarPath = path.join(context.appOutDir, 'resources', 'app.asar')
  if (!fs.existsSync(asarPath)) {
    console.log('app.asar not found, skipping integrity hash')
    return
  }

  const tmpDir = path.join(context.appOutDir, '_asar_integrity_patch')
  fs.rmSync(tmpDir, { recursive: true, force: true })

  const hexPath = path.join(context.appOutDir, 'resources', 'asar-integrity.hex')
  const preInjectHex = sha256File(asarPath)

  extractAll(asarPath, tmpDir)
  const mainJsPath = path.join(tmpDir, MAIN_REL)
  if (!fs.existsSync(mainJsPath)) {
    throw new Error(`main.js missing in asar: ${MAIN_REL}`)
  }

  patchMainJsEmbeddedHash(mainJsPath, preInjectHex)
  await createPackage(tmpDir, asarPath)

  const finalHex = sha256File(asarPath)
  fs.writeFileSync(hexPath, finalHex, 'utf8')
  console.log(`asar integrity SHA-256: ${finalHex}`)

  fs.rmSync(tmpDir, { recursive: true, force: true })
}

/** 把 logo 写进 exe，桌面快捷方式才会显示正确图标 */
export default async function afterPack(context) {
  if (context.electronPlatformName !== 'win32') return

  const exeName = `${context.packager.appInfo.productFilename}.exe`
  const exePath = path.join(context.appOutDir, exeName)
  const iconPath = path.join(root, 'build', 'icon.ico')

  await rcedit(exePath, { icon: iconPath })
  await computeAndInjectAsarIntegrity(context)
}
