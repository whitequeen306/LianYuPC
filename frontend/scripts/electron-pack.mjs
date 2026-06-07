import { execSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'
import { fileURLToPath } from 'node:url'

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..')
process.chdir(root)
process.env.CSC_IDENTITY_AUTO_DISCOVERY = 'false'
process.env.ELECTRON_BUILD = '1'

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

/** 生成 ASAR 加密密钥（Hex），仅在不存在时创建 */
function ensureAsarKey() {
  const keyPath = path.join(root, 'build', 'asar-key.txt')
  if (fs.existsSync(keyPath)) return
  fs.mkdirSync(path.dirname(keyPath), { recursive: true })
  const key = crypto.randomBytes(32).toString('hex')
  fs.writeFileSync(keyPath, key, 'utf8')
  console.log('asar encryption key generated')
}

const pkg = JSON.parse(fs.readFileSync('package.json', 'utf8'))
const outDir = path.join('release', `v${pkg.version}`)
fs.mkdirSync(path.join(root, outDir), { recursive: true })

ensureAsarKey()

execSync('npx vite build', { stdio: 'inherit', env: process.env })

const outputArg = `--config.directories.output=${outDir.replace(/\\/g, '/')}`
execSync(`npx electron-builder --win ${outputArg}`, {
  stdio: 'inherit',
  env: process.env,
})

console.log(`\n安装包已生成: ${outDir}/LianYu Setup ${pkg.version}.exe`)
console.log(`API Origin: ${process.env.LIANYU_API_ORIGIN}`)
