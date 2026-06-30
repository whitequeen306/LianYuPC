import { execSync } from 'node:child_process'
import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { buildSync } from 'esbuild'
import JavaScriptObfuscator from 'javascript-obfuscator'
import { packRuntimeSecrets } from './pack-runtime-secrets.mjs'

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..')
process.chdir(root)
process.env.CSC_IDENTITY_AUTO_DISCOVERY = 'false'
process.env.ELECTRON_BUILD = '1'

/** 主进程 bundle 轻度混淆：抬高逆向成本，不做控制流平坦化以免影响启动性能 */
const MAIN_BUNDLE_LIGHT_OBFUSCATION = {
  compact: true,
  controlFlowFlattening: false,
  deadCodeInjection: false,
  debugProtection: false,
  selfDefending: false,
  stringArray: true,
  stringArrayThreshold: 0.75,
  stringArrayEncoding: ['base64'],
  splitStrings: false,
  simplify: true,
  renameGlobals: false,
  reservedNames: ['require', 'module', 'exports', '__dirname', '__filename'],
}

function obfuscateMainBundleLight() {
  const filePath = path.join(root, 'dist-electron', 'main-bundle.cjs')
  if (!fs.existsSync(filePath)) return
  const source = fs.readFileSync(filePath, 'utf8')
  const obfuscated = JavaScriptObfuscator.obfuscate(source, MAIN_BUNDLE_LIGHT_OBFUSCATION).getObfuscatedCode()
  fs.writeFileSync(filePath, obfuscated, 'utf8')
  console.log('Applied light obfuscation to dist-electron/main-bundle.cjs')
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
    'rtcfg.dat',
  ])
  for (const name of fs.readdirSync(electronDir)) {
    if (keep.has(name)) continue
    fs.unlinkSync(path.join(electronDir, name))
    console.log(`Removed non-ship electron artifact: dist-electron/${name}`)
  }
}

function createBuildId(version) {
  return crypto
    .createHash('sha256')
    .update(`${version}-${Date.now()}-${crypto.randomBytes(8).toString('hex')}`)
    .digest('hex')
    .slice(0, 32)
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
    external: ['electron', 'active-win', 'bytenode'],
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

const repoEnv = loadEnvFile(path.join(root, '..', '.env'))
const cloudEnv = loadEnvFile(path.join(root, '.env.production.cloud'))
const packEnv = { ...repoEnv, ...cloudEnv }
const packApiOrigin = packEnv.VITE_LIANYU_API_ORIGIN || 'http://localhost:8080'
const packCertFingerprint = packEnv.VITE_LIANYU_CERT_FINGERPRINT || ''
if (!packEnv.LIANYU_RUNTIME_SECRETS_PEPPER && !process.env.LIANYU_RUNTIME_SECRETS_PEPPER) {
  console.error('Missing LIANYU_RUNTIME_SECRETS_PEPPER in repo .env — required for Electron release pack.')
  process.exit(1)
}
process.env.LIANYU_RUNTIME_SECRETS_PEPPER = process.env.LIANYU_RUNTIME_SECRETS_PEPPER || packEnv.LIANYU_RUNTIME_SECRETS_PEPPER

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
  const installer = path.join(primaryFull, `LianYu Setup ${version}.exe`)
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

function killLianYuProcesses() {
  if (process.platform !== 'win32') return
  try {
    execSync('taskkill /F /IM LianYu.exe /T', { stdio: 'ignore' })
    console.log('Stopped running LianYu.exe before packaging')
  } catch {
    /* not running */
  }
}

function removePartialReleaseArtifacts() {
  const installer = path.join(outDirFull, `LianYu Setup ${pkg.version}.exe`)
  const winUnpacked = path.join(outDirFull, 'win-unpacked')
  if (fs.existsSync(installer)) return
  if (!fs.existsSync(winUnpacked)) return
  try {
    fs.rmSync(winUnpacked, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 })
    console.log(`Removed partial release folder: ${path.relative(root, winUnpacked)}`)
  } catch (err) {
    console.warn(`Could not remove partial release folder (continuing): ${err.message}`)
  }
}

execSync('python scripts/regenerate-icon.py', { stdio: 'inherit' })
removePreViteStaleEntries()
execSync('npx vite build', { stdio: 'inherit', env: viteEnv })

buildMainCjsBundle()

const buildId = createBuildId(pkg.version)
console.log(`Client build meta: electron/${pkg.version}/${buildId}`)
packRuntimeSecrets({
  version: pkg.version,
  buildId,
  apiOrigin: packApiOrigin,
  certFingerprint: packCertFingerprint,
  outPath: path.join(root, 'dist-electron', 'rtcfg.dat'),
})

applyPlainMainPackaging()
obfuscateMainBundleLight()

console.log('\n--- Launcher smoke test (pre-pack) ---')
execSync('node scripts/smoke-launcher.mjs', { stdio: 'inherit', cwd: root })

killLianYuProcesses()
removePartialReleaseArtifacts()

const outputArg = `--config.directories.output=${outDir.replace(/\\/g, '/')}`
execSync(`npx electron-builder --win ${outputArg}`, {
  stdio: 'inherit',
  env: process.env,
})

console.log(`\nRelease installer: ${outDir}/LianYu Setup ${pkg.version}.exe`)
