import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { buildBlockMap } from 'app-builder-lib/out/targets/blockmap/blockmap.js'

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..')
const repoRoot = path.resolve(root, '..')
const pkg = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'))
const version = pkg.version
const releaseDir = path.join(root, 'release', `v${version}`)
// 命名契约唯一真相源：scripts/release_assets.json
const releaseAssets = JSON.parse(fs.readFileSync(path.join(repoRoot, 'scripts', 'release_assets.json'), 'utf8'))
const installerName = releaseAssets.installer.exeName.replace('{version}', version)
const installerPath = path.join(releaseDir, installerName)
const blockmapPath = `${installerPath}${releaseAssets.installer.blockmapSuffix}`

fs.mkdirSync(releaseDir, { recursive: true })
execFileSync('powershell.exe', [
  '-NoProfile',
  '-ExecutionPolicy', 'Bypass',
  '-File', path.join(repoRoot, 'installer', 'build-offline-installer.ps1'),
  '-Version', version,
  '-Output', installerPath,
], { stdio: 'inherit', cwd: repoRoot })

const { size, sha512 } = await buildBlockMap(installerPath, 'gzip', blockmapPath)

// 与 electron-pack.mjs 同样的理由：仓库目录若带 Low 完整性标签，产物会继承它，
// 用户双击时被 Windows 拦（同一份字节拷到桌面就没事）。详见 electron-pack.mjs 的注释。
if (process.platform === 'win32' && fs.existsSync(installerPath)) {
  try {
    execFileSync('icacls', [installerPath, '/setintegritylevel', 'Medium'], { stdio: 'pipe' })
    console.log('Installer integrity level raised to Medium')
  } catch (err) {
    console.warn(`Could not raise installer integrity level (continuing): ${err.message}`)
  }
}

const latest = [
  `version: ${version}`,
  'files:',
  `  - url: ${installerName}`,
  `    sha512: ${sha512}`,
  `    size: ${size}`,
  `path: ${installerName}`,
  `sha512: ${sha512}`,
  `releaseDate: '${new Date().toISOString()}'`,
  '',
].join('\n')
fs.writeFileSync(path.join(releaseDir, 'latest.yml'), latest, 'utf8')

console.log(`Branded installer: ${installerPath}`)
console.log(`Size: ${size}`)
console.log(`SHA-512: ${sha512}`)
