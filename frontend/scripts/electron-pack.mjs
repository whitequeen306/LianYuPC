import { execSync } from 'node:child_process'
import crypto from 'node:crypto'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { buildSync } from 'esbuild'
import { packRuntimeSecrets } from './pack-runtime-secrets.mjs'

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..')
process.chdir(root)
process.env.CSC_IDENTITY_AUTO_DISCOVERY = 'false'
process.env.ELECTRON_BUILD = '1'

/** 渲染进程混淆会显著拖慢启动与二次窗口加载，默认关闭以优先体验 */
const ENABLE_RENDERER_OBFUSCATION = false

function obfuscateRendererBundles() {
  if (!ENABLE_RENDERER_OBFUSCATION) {
    console.log('Skipped renderer obfuscation (disabled for performance)')
    return
  }
}

/** Remove artifacts from prior packs before a fresh vite build. */
const PRE_VITE_STALE = ['main.cjs', 'main.cjs.map', 'main.jsc', 'preload.jsc', 'main-src.cjs', 'preload-src.cjs']

function removePreViteStaleEntries() {
  const electronDir = path.join(root, 'dist-electron')
  if (!fs.existsSync(electronDir)) return
  for (const name of PRE_VITE_STALE) {
    const filePath = path.join(electronDir, name)
    if (!fs.existsSync(filePath)) continue
    fs.unlinkSync(filePath)
    console.log(`Removed stale electron bundle: dist-electron/${name}`)
  }
}

function cleanDistElectronShipSet() {
  const electronDir = path.join(root, 'dist-electron')
  if (!fs.existsSync(electronDir)) return
  const keep = new Set([
    'main.js',
    'main-bundle.cjs',
    'preload.cjs',
    'client-build.json',
    'runtime-secrets.bin',
  ])
  for (const name of fs.readdirSync(electronDir)) {
    if (keep.has(name)) continue
    fs.unlinkSync(path.join(electronDir, name))
    console.log(`Removed non-ship electron artifact: dist-electron/${name}`)
  }
}

function writeClientBuildMeta(version) {
  const buildId = crypto
    .createHash('sha256')
    .update(`${version}-${Date.now()}-${crypto.randomBytes(8).toString('hex')}`)
    .digest('hex')
    .slice(0, 16)
  fs.writeFileSync(
    path.join(root, 'dist-electron', 'client-build.json'),
    JSON.stringify({ version, buildId }, null, 2),
    'utf8',
  )
  console.log(`Client build meta: electron/${version}/${buildId}`)
  return buildId
}

function loadEnvFile(filePath) {
  if (!fs.existsSync(filePath)) return {}
  const out = {}
  for (const line of fs.readFileSync(filePath, 'utf8').split('\n')) {
    const trimmed = line.trim()
    if (!trimmed || trimmed.startsWith('#')) continue
    const eq = trimmed.indexOf('=')
    if (eq <= 0) continue
    const key = trimmed.slice(0, eq).trim()
    const value = trimmed.slice(eq + 1).trim()
    out[key] = value
  }
  return out
}

function buildMainCjsBundle() {
  const outfile = path.join(root, 'dist-electron', 'main-src.cjs')
  buildSync({
    entryPoints: [path.join(root, 'electron', 'main.js')],
    outfile,
    bundle: true,
    platform: 'node',
    format: 'cjs',
    external: ['electron', 'active-win', 'bytenode', 'ws', 'extract-zip'],
    packages: 'external',
    minify: true,
    sourcemap: false,
    banner: {
      js: 'const __import_meta_url=require("url").pathToFileURL(__filename).href;',
    },
    define: {
      'import.meta.url': '__import_meta_url',
    },
    logLevel: 'info',
  })
  console.log(`Bundled main (CJS for bytecode): ${path.relative(root, outfile)}`)
}

/** 主进程直接 ship CJS bundle，跳过 bytenode 以缩短冷启动 */
function applyPlainMainPackaging() {
  const electronDir = path.join(root, 'dist-electron')
  const mainSrc = path.join(electronDir, 'main-src.cjs')
  const preloadSrc = path.join(electronDir, 'preload-src.cjs')
  const mainBundle = path.join(electronDir, 'main-bundle.cjs')
  const preloadDest = path.join(electronDir, 'preload.cjs')
  const mainStub = path.join(electronDir, 'main.js')

  if (!fs.existsSync(mainSrc) || !fs.existsSync(preloadSrc)) {
    throw new Error('Missing dist-electron main/preload bundle for packaging')
  }

  fs.copyFileSync(mainSrc, mainBundle)
  fs.unlinkSync(mainSrc)
  fs.copyFileSync(preloadSrc, preloadDest)
  fs.unlinkSync(preloadSrc)

  fs.writeFileSync(
    mainStub,
    `import { createRequire } from 'module'
const require = createRequire(import.meta.url)
require('./main-bundle.cjs')
`,
    'utf8',
  )
  console.log('Plain main bundle shipped (bytenode skipped)')
  cleanDistElectronShipSet()
}

const cloudEnv = loadEnvFile(path.join(root, '.env.production.cloud'))
const packApiOrigin = cloudEnv.VITE_LIANYU_API_ORIGIN || 'http://localhost:8080'
const packCertFingerprint = cloudEnv.VITE_LIANYU_CERT_FINGERPRINT || ''

const viteEnv = {
  ...process.env,
  ELECTRON_BUILD: '1',
  VITE_LIANYU_API_ORIGIN: '',
  VITE_LIANYU_CERT_FINGERPRINT: '',
  VITE_LIANYU_PACKED_API_ORIGIN: packApiOrigin,
}

const pkg = JSON.parse(fs.readFileSync('package.json', 'utf8'))

function resolveReleaseOutDir(version) {
  const primary = path.join('release', `v${version}`)
  const primaryFull = path.join(root, primary)
  const installer = path.join(primaryFull, `YuNian-Setup-${version}.exe`)
  if (fs.existsSync(installer)) return primary

  const winUnpacked = path.join(primaryFull, 'win-unpacked')
  if (fs.existsSync(winUnpacked)) {
    const fallback = `${primary}-rebuild`
    console.warn(`Partial release folder exists without installer (${primary}), using ${fallback}`)
    return fallback
  }
  return primary
}

const outDir = resolveReleaseOutDir(pkg.version)
const outDirFull = path.join(root, outDir)
fs.mkdirSync(outDirFull, { recursive: true })
// Windows: unpack outside the Cursor workspace. The workspace file watcher
// otherwise holds handles on win-unpacked.tmp and electron-builder's rename
// fails with EPERM.
const builderOutDir = process.platform === 'win32'
  ? path.join(os.tmpdir(), 'lianyu-electron-release', path.basename(outDir))
  : outDirFull
fs.mkdirSync(builderOutDir, { recursive: true })

function killYuNianProcesses() {
  if (process.platform !== 'win32') return
  try {
    execSync('taskkill /F /IM YuNian.exe /T', { stdio: 'ignore' })
    console.log('Stopped running YuNian.exe before packaging')
  } catch {
    /* not running */
  }
}

function removePartialReleaseArtifacts() {
  const installerName = `YuNian-Setup-${pkg.version}.exe`
  if (fs.existsSync(path.join(outDirFull, installerName)) || fs.existsSync(path.join(builderOutDir, installerName))) {
    return
  }
  for (const dir of [
    path.join(outDirFull, 'win-unpacked'),
    path.join(outDirFull, 'win-unpacked.tmp'),
    path.join(builderOutDir, 'win-unpacked'),
    path.join(builderOutDir, 'win-unpacked.tmp'),
  ]) {
    if (!fs.existsSync(dir)) continue
    try {
      fs.rmSync(dir, { recursive: true, force: true, maxRetries: 8, retryDelay: 300 })
      console.log(`Removed partial release folder: ${dir}`)
    } catch (err) {
      console.warn(`Could not remove ${dir} (continuing): ${err.message}`)
    }
  }
}

function copyBuilderArtifactsToRepo() {
  if (builderOutDir === outDirFull) return
  const names = [
    `YuNian-Setup-${pkg.version}.exe`,
    `YuNian-Setup-${pkg.version}.exe.blockmap`,
    'latest.yml',
  ]
  fs.mkdirSync(outDirFull, { recursive: true })
  for (const name of names) {
    const src = path.join(builderOutDir, name)
    if (!fs.existsSync(src)) continue
    fs.copyFileSync(src, path.join(outDirFull, name))
  }
  console.log(`Copied installer artifacts to ${outDir}`)
}

execSync('python scripts/regenerate-icon.py', { stdio: 'inherit' })
removePreViteStaleEntries()
execSync('npx vite build', { stdio: 'inherit', env: viteEnv })

buildMainCjsBundle()

// runtime-secrets.bin 仅轻度混淆（obfuscation-only），非加密；不打包真敏感项（见 runtimeSecrets.js 文件头）。
const buildId = writeClientBuildMeta(pkg.version)
packRuntimeSecrets({
  version: pkg.version,
  buildId,
  apiOrigin: packApiOrigin,
  updateOrigin: cloudEnv.VITE_LIANYU_UPDATE_ORIGIN || '',
  certFingerprint: packCertFingerprint,
  updateManifestPublicKey: cloudEnv.VITE_LIANYU_UPDATE_PUBKEY || '',
  outPath: path.join(root, 'dist-electron', 'runtime-secrets.bin'),
})

obfuscateRendererBundles()
applyPlainMainPackaging()

console.log('\n--- Launcher smoke test (pre-pack) ---')
// 冒烟要真正启动一次 Electron；在无交互桌面的会话里（CI、远程 agent、服务身份）
// electron.exe 连 --version 都起不来（Windows 退出码 0x80000003），冒烟必然失败。
// 那种情况下允许显式跳过，但必须留下响亮的一行 —— 跳过了就意味着**这个包没被启动验证过**。
if (process.env.LIANYU_SKIP_LAUNCHER_SMOKE === '1') {
  console.warn('!!! LIANYU_SKIP_LAUNCHER_SMOKE=1：已跳过打包前冒烟，本次产物未被启动验证 !!!')
} else {
  execSync('node scripts/smoke-launcher.mjs', { stdio: 'inherit', cwd: root })
}

killYuNianProcesses()
removePartialReleaseArtifacts()

const outputArg = `--config.directories.output=${builderOutDir.replace(/\\/g, '/')}`
// 配了 GH_TOKEN 才上传 Releases；否则只产本地包，避免误传
const publishArg = process.env.GH_TOKEN ? '--publish always' : '--publish never'
if (process.env.GH_TOKEN) {
  console.log('GH_TOKEN detected -> will publish to GitHub Releases')
  // electron-builder 会并行上传 exe / blockmap / latest.yml；若 Release 尚不存在，
  // 三者会竞态各自 create draft，只留下部分资产（常见：只有 .blockmap）。
  // 先预建空 draft，再让所有上传落到同一 Release。
  const tag = `v${pkg.version}`
  try {
    execSync(`gh release view ${tag}`, {
      stdio: 'pipe',
      env: process.env,
    })
    console.log(`GitHub release ${tag} already exists — reuse`)
  } catch {
    execSync(
      `gh release create ${tag} --draft --title "${tag}" --notes "YuNian PC ${tag}"`,
      { stdio: 'inherit', env: process.env },
    )
    console.log(`Pre-created draft release ${tag} (avoids dual-draft race)`)
  }
} else {
  console.log('GH_TOKEN not set -> local build only (no upload)')
}
const electronBuilderEnv = { ...process.env }
electronBuilderEnv.NODE_OPTIONS = `${process.env.NODE_OPTIONS || ''} --use-system-ca`.trim()
const builderCmd = `npx electron-builder --win ${outputArg} ${publishArg}`
const builderAttempts = 3
for (let attempt = 1; attempt <= builderAttempts; attempt++) {
  killYuNianProcesses()
  removePartialReleaseArtifacts()
  try {
    execSync(builderCmd, {
      stdio: 'inherit',
      env: electronBuilderEnv,
    })
    break
  } catch (err) {
    if (attempt === builderAttempts) throw err
    console.warn(`electron-builder failed (attempt ${attempt}/${builderAttempts}); retrying in case of Windows file lock...`)
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 4000)
  }
}
copyBuilderArtifactsToRepo()
raiseInstallerIntegrityLevel()

/**
 * 把安装包的完整性级别提到 Medium。
 *
 * 为什么必须做：如果仓库目录本身带 `Low Mandatory Level` 标签（本机的
 * `Desktop\LianYu-PC` 就是，见 `icacls` 的 `Mandatory Label\Low Mandatory Level:(I)(NW)`），
 * 那么在里面构建出来的 exe 会**继承低完整性级别**。用户（Explorer 是 Medium）双击
 * 一个 Low 的 exe 时，Windows 会拦下来 —— 表现为「Windows 已保护你的电脑」或
 * 「未知发布者」，而**同一份字节拷到桌面/%TEMP% 就没事**，因为那边是 Medium。
 *
 * 2026-10-21 排查了很久才定位到这一点：字节完全相同（sha256 一致）、都未签名、
 * 都没有 MotW，唯一差别就是完整性级别。所以构建后统一提回 Medium。
 *
 * 失败不作为构建失败：非 Windows、或没有权限时不该让出包流程挂掉，
 * 但要响一声，否则下次又会对着"只有本机构建被拦"发懵。
 */
function raiseInstallerIntegrityLevel() {
  if (process.platform !== 'win32') return
  const installer = path.join(outDirFull, `YuNian-Setup-${pkg.version}.exe`)
  if (!fs.existsSync(installer)) return
  try {
    execSync(`icacls "${installer}" /setintegritylevel Medium`, { stdio: 'pipe' })
    console.log('Installer integrity level raised to Medium (Low IL files get blocked by Windows)')
  } catch (err) {
    console.warn(`Could not raise installer integrity level (continuing): ${err.message}`)
    console.warn(`  -> 本机构建产物可能继承 Low IL，双击会被 Windows 拦；手动修：icacls "${installer}" /setintegritylevel Medium`)
  }
}

console.log(`\nInstaller: ${outDir}/YuNian-Setup-${pkg.version}.exe`)
console.log(`API Origin (packed in runtime-secrets.bin): ${packApiOrigin}`)
