import path from 'node:path'
import { fileURLToPath } from 'node:url'
import rcedit from 'rcedit'

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..')

/**
 * afterPack：把 logo 写进 exe，桌面快捷方式才会显示正确图标。
 *
 * 客户端完整性由 Electron 原生 asar-integrity fuse 守护
 * （package.json electronFuses.enableEmbeddedAsarIntegrityValidation: true）：
 * electron-builder 在 beforeCopyExtraFiles 阶段把 app.asar 头哈希嵌入二进制，
 * Electron 在 main 运行前校验，攻击者无法在不改二进制的前提下绕过。
 *
 * 故不再需要：
 *  - 自研 resources/asar-integrity.hex 同目录自校验（校验值与被校验对象同目录、
 *    且校验运行在攻击者可控的 app 进程内，可被重算覆盖绕过）；
 *  - asarmor 头补丁：asarmor 会改动 asar 头，与 fuse 预嵌入的哈希冲突，
 *    会导致 Electron 启动期完整性校验失败、应用无法启动。
 *    （electron-builder 26.8.1：integrity 在 beforeCopyExtraFiles 嵌入，早于 afterPack；
 *     见 app-builder-lib/out/platformPackager.js doPack。）
 */
export default async function afterPack(context) {
  if (context.electronPlatformName !== 'win32') return

  const exeName = `${context.packager.appInfo.productFilename}.exe`
  const exePath = path.join(context.appOutDir, exeName)
  const iconPath = path.join(root, 'build', 'icon.ico')

  await rcedit(exePath, { icon: iconPath })
}
