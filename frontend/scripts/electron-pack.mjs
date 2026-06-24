import { execSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import JavaScriptObfuscator from 'javascript-obfuscator'

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..')
process.chdir(root)
process.env.CSC_IDENTITY_AUTO_DISCOVERY = 'false'
process.env.ELECTRON_BUILD = '1'

const OBFUSCATOR_OPTIONS = {
  compact: true,
  controlFlowFlattening: true,
  controlFlowFlatteningThreshold: 0.5,
  deadCodeInjection: false,
  stringArray: true,
  stringArrayThreshold: 0.7,
  stringArrayRotate: true,
  stringArrayShuffle: true,
  stringArrayEncoding: ['base64'],
  rotateStringArray: true,
  identifierNamesGenerator: 'mangled-shuffled',
  selfDefending: true,
  transformObjectKeys: false,
  unicodeEscapeSequence: false,
  disableConsoleOutput: false,
}

function obfuscateRendererBundle() {
  const assetsDir = path.join(root, 'dist', 'assets')
  if (!fs.existsSync(assetsDir)) return
  for (const name of fs.readdirSync(assetsDir)) {
    if (!name.endsWith('.js')) continue
    const filePath = path.join(assetsDir, name)
    const source = fs.readFileSync(filePath, 'utf8')
    const obfuscated = JavaScriptObfuscator.obfuscate(source, OBFUSCATOR_OPTIONS).getObfuscatedCode()
    fs.writeFileSync(filePath, obfuscated, 'utf8')
    console.log(`Obfuscated renderer bundle: ${name}`)
  }
}

function loadEnvFile(filePath) {
  if (!fs.existsSync(filePath)) return
  for (const line of fs.readFileSync(filePath, 'utf8').split('\n')) {
    const trimmed = line.trim()
    if (!trimmed || trimmed.startsWith('#')) continue
    const eq = trimmed.indexOf('=')
    if (eq <= 0) continue
    const key = trimmed.slice(0, eq).trim()
    const value = trimmed.slice(eq + 1).trim()
    if (!process.env[key]) {
      process.env[key] = value
    }
  }
}

loadEnvFile(path.join(root, '.env.production.cloud'))
process.env.LIANYU_API_ORIGIN = process.env.VITE_LIANYU_API_ORIGIN || 'http://localhost:8080'
process.env.LIANYU_CERT_FINGERPRINT = process.env.VITE_LIANYU_CERT_FINGERPRINT || ''

const pkg = JSON.parse(fs.readFileSync('package.json', 'utf8'))
const outDir = path.join('release', `v${pkg.version}`)
fs.mkdirSync(path.join(root, outDir), { recursive: true })

execSync('python scripts/regenerate-icon.py', { stdio: 'inherit' })
execSync('npx vite build', { stdio: 'inherit', env: process.env })
obfuscateRendererBundle()

const outputArg = `--config.directories.output=${outDir.replace(/\\/g, '/')}`
execSync(`npx electron-builder --win ${outputArg}`, {
  stdio: 'inherit',
  env: process.env,
})

console.log(`\n安装包已生成: ${outDir}/LianYu Setup ${pkg.version}.exe`)
console.log(`API Origin: ${process.env.LIANYU_API_ORIGIN}`)
