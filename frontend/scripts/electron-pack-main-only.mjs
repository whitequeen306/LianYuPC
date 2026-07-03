/** 仅重建 dist-electron 主进程 bundle（smoke / 快速迭代用） */
import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { buildSync } from 'esbuild'
import { packRuntimeSecrets } from './pack-runtime-secrets.mjs'

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..')
process.chdir(root)

function loadEnvFile(filePath) {
  if (!fs.existsSync(filePath)) return {}
  const out = {}
  for (const line of fs.readFileSync(filePath, 'utf8').split('\n')) {
    const trimmed = line.trim()
    if (!trimmed || trimmed.startsWith('#')) continue
    const eq = trimmed.indexOf('=')
    if (eq <= 0) continue
    out[trimmed.slice(0, eq).trim()] = trimmed.slice(eq + 1).trim()
  }
  return out
}

const cloudEnv = loadEnvFile(path.join(root, '.env.production.cloud'))
const pkg = JSON.parse(fs.readFileSync('package.json', 'utf8'))
const electronDir = path.join(root, 'dist-electron')
fs.mkdirSync(electronDir, { recursive: true })

buildSync({
  entryPoints: [path.join(root, 'electron', 'main.js')],
  outfile: path.join(electronDir, 'main-src.cjs'),
  bundle: true,
  platform: 'node',
  format: 'cjs',
  external: ['electron', 'active-win', 'bytenode'],
  packages: 'external',
  minify: true,
  banner: { js: 'const __import_meta_url=require("url").pathToFileURL(__filename).href;' },
  define: { 'import.meta.url': '__import_meta_url' },
})

const buildId = crypto.createHash('sha256').update(`${pkg.version}-${Date.now()}`).digest('hex').slice(0, 16)
packRuntimeSecrets({
  version: pkg.version,
  buildId,
  apiOrigin: cloudEnv.VITE_LIANYU_API_ORIGIN || 'http://localhost:8080',
  certFingerprint: cloudEnv.VITE_LIANYU_CERT_FINGERPRINT || '',
  outPath: path.join(electronDir, 'runtime-secrets.bin'),
})

fs.copyFileSync(path.join(electronDir, 'main-src.cjs'), path.join(electronDir, 'main-bundle.cjs'))
fs.unlinkSync(path.join(electronDir, 'main-src.cjs'))
if (!fs.existsSync(path.join(electronDir, 'preload.cjs'))) {
  buildSync({
    entryPoints: [path.join(root, 'electron', 'preload.js')],
    outfile: path.join(electronDir, 'preload-src.cjs'),
    bundle: true,
    platform: 'node',
    format: 'cjs',
    external: ['electron'],
  })
  fs.copyFileSync(path.join(electronDir, 'preload-src.cjs'), path.join(electronDir, 'preload.cjs'))
  fs.unlinkSync(path.join(electronDir, 'preload-src.cjs'))
}
fs.writeFileSync(
  path.join(electronDir, 'main.js'),
  `import { createRequire } from 'module'\nconst require = createRequire(import.meta.url)\nrequire('./main-bundle.cjs')\n`,
)
fs.writeFileSync(path.join(electronDir, 'client-build.json'), JSON.stringify({ version: pkg.version, buildId }, null, 2))
console.log('dist-electron main bundle rebuilt')
