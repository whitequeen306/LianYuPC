import { execSync } from 'node:child_process'
import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { buildSync } from 'esbuild'
import JavaScriptObfuscator from 'javascript-obfuscator'
import { compileBytecode, writeMainStub, writePreloadStub } from './compile-bytecode.mjs'
import { packRuntimeSecrets } from './pack-runtime-secrets.mjs'

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..')
process.chdir(root)
process.env.CSC_IDENTITY_AUTO_DISCOVERY = 'false'
process.env.ELECTRON_BUILD = '1'

const RENDERER_OBFUSCATOR = {
  compact: true,
  controlFlowFlattening: true,
  controlFlowFlatteningThreshold: 0.75,
  deadCodeInjection: true,
  deadCodeInjectionThreshold: 0.1,
  splitStrings: true,
  splitStringsChunkLength: 5,
  stringArray: true,
  stringArrayThreshold: 0.75,
  stringArrayRotate: true,
  stringArrayShuffle: true,
  stringArrayEncoding: ['rc4'],
  rotateStringArray: true,
  identifierNamesGenerator: 'mangled-shuffled',
  selfDefending: true,
  numbersToExpressions: true,
  transformObjectKeys: false,
  reservedNames: ['electronAPI'],
  reservedStrings: ['electronAPI', 'isElectron'],
  unicodeEscapeSequence: false,
  disableConsoleOutput: false,
  simplify: false,
}

function obfuscateRendererBundles() {
  const assetsDir = path.join(root, 'dist', 'assets')
  if (!fs.existsSync(assetsDir)) return
  for (const name of fs.readdirSync(assetsDir)) {
    if (!name.endsWith('.js')) continue
    const filePath = path.join(assetsDir, name)
    const source = fs.readFileSync(filePath, 'utf8')
    const obfuscated = JavaScriptObfuscator.obfuscate(source, RENDERER_OBFUSCATOR).getObfuscatedCode()
    fs.writeFileSync(filePath, obfuscated, 'utf8')
    console.log(`Obfuscated (renderer): ${path.relative(root, filePath)}`)
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
    'preload.cjs',
    'main.jsc',
    'preload.jsc',
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

function applyBytecodePackaging() {
  const electronDir = path.join(root, 'dist-electron')
  const mainSrc = path.join(electronDir, 'main-src.cjs')
  const preloadSrc = path.join(electronDir, 'preload-src.cjs')
  const mainJsc = path.join(electronDir, 'main.jsc')
  const preloadJsc = path.join(electronDir, 'preload.jsc')

  compileBytecode(root, [
    { src: mainSrc, out: mainJsc },
    { src: preloadSrc, out: preloadJsc },
  ])

  fs.unlinkSync(mainSrc)
  fs.unlinkSync(preloadSrc)

  writeMainStub(path.join(electronDir, 'main.js'))
  writePreloadStub(path.join(electronDir, 'preload.cjs'))
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
const outDir = path.join('release', `v${pkg.version}`)
fs.mkdirSync(path.join(root, outDir), { recursive: true })

execSync('python scripts/regenerate-icon.py', { stdio: 'inherit' })
removePreViteStaleEntries()
execSync('npx vite build', { stdio: 'inherit', env: viteEnv })

buildMainCjsBundle()

const buildId = writeClientBuildMeta(pkg.version)
packRuntimeSecrets({
  version: pkg.version,
  buildId,
  apiOrigin: packApiOrigin,
  certFingerprint: packCertFingerprint,
  outPath: path.join(root, 'dist-electron', 'runtime-secrets.bin'),
})

obfuscateRendererBundles()
applyBytecodePackaging()

const outputArg = `--config.directories.output=${outDir.replace(/\\/g, '/')}`
execSync(`npx electron-builder --win ${outputArg}`, {
  stdio: 'inherit',
  env: process.env,
})

console.log(`\n安装包已生成: ${outDir}/LianYu Setup ${pkg.version}.exe`)
console.log(`API Origin (packed in runtime-secrets.bin): ${packApiOrigin}`)
