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
