/**
 * 模拟新用户启动：空 userData + capturePage 截图 + DOM 探针。
 * 优先用 dist-electron（含 MAIN_STARTUP_SMOKE）；若已打包则再测 win-unpacked。
 */
import { execSync, spawnSync } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..')
const pkg = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'))
const version = pkg.version
const outDir = path.join(root, 'release', `v${version}`)
const screenshotPath = path.join(outDir, 'smoke-main-startup.png')
const userData = path.join(os.tmpdir(), `lianyu-smoke-${Date.now()}`)
const electronBin = process.platform === 'win32'
  ? path.join(root, 'node_modules', 'electron', 'dist', 'electron.exe')
  : path.join(root, 'node_modules', 'electron', 'dist', 'electron')
const mainEntry = path.join(root, 'dist-electron', 'main.js')
const unpackedExe = path.join(outDir, 'win-unpacked', 'LianYu.exe')
const captureScript = path.join(root, 'scripts', 'capture-window.ps1')

fs.mkdirSync(outDir, { recursive: true })
fs.mkdirSync(userData, { recursive: true })

function killApp() {
  if (process.platform === 'win32') {
    try { spawnSync('taskkill', ['/F', '/IM', 'LianYu.exe', '/T'], { stdio: 'ignore' }) } catch { /* ignore */ }
    try { spawnSync('taskkill', ['/F', '/IM', 'electron.exe', '/T'], { stdio: 'ignore' }) } catch { /* ignore */ }
  }
}

function ensureDistElectron() {
  if (!fs.existsSync(mainEntry)) {
    console.log('smoke-main-startup: building dist-electron...')
    execSync('npx vite build', {
      stdio: 'inherit',
      cwd: root,
      env: { ...process.env, ELECTRON_BUILD: '1' },
    })
    execSync('node scripts/electron-pack-main-only.mjs', { stdio: 'inherit', cwd: root })
  }
}

function runElectronSmoke() {
  if (!fs.existsSync(electronBin)) {
    console.error('smoke-main-startup: electron binary missing — run npm ci')
    return { ok: false, output: '' }
  }
  ensureDistElectron()
  console.log(`smoke-main-startup: electron smoke userData=${userData}`)
  const result = spawnSync(electronBin, [mainEntry], {
    cwd: path.join(root, 'dist-electron'),
    env: {
      ...process.env,
      LIANYU_MAIN_STARTUP_SMOKE: '1',
      LIANYU_SMOKE_USER_DATA: userData,
      LIANYU_SMOKE_SCREENSHOT: screenshotPath,
    },
    encoding: 'utf8',
    timeout: 120000,
  })
  const output = `${result.stdout || ''}\n${result.stderr || ''}`
  const ok = result.status === 0 && output.includes('MAIN_STARTUP_SMOKE_OK') && fs.existsSync(screenshotPath)
  return { ok, output }
}

function runPackagedVisualSmoke() {
  if (!fs.existsSync(unpackedExe)) {
    console.log('smoke-main-startup: skip packaged visual (win-unpacked missing)')
    return { ok: true, output: '' }
  }
  const packagedUserData = path.join(os.tmpdir(), `lianyu-packaged-smoke-${Date.now()}`)
  fs.mkdirSync(packagedUserData, { recursive: true })
  const packagedShot = path.join(outDir, 'smoke-main-startup-packaged.png')
  console.log(`smoke-main-startup: packaged exe userData=${packagedUserData}`)
  spawnSync(unpackedExe, [`--user-data-dir=${packagedUserData}`], {
    detached: true,
    stdio: 'ignore',
    windowsHide: false,
  })
  const cap = spawnSync(
    'powershell',
    ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', captureScript, '-OutPath', packagedShot, '-WaitMs', '28000'],
    { encoding: 'utf8', timeout: 120000 },
  )
  const ok = cap.status === 0 && fs.existsSync(packagedShot)
  return { ok, output: `${cap.stdout || ''}\n${cap.stderr || ''}`, shot: packagedShot }
}

killApp()

const electronResult = runElectronSmoke()
if (electronResult.ok) {
  console.log(electronResult.output.split('\n').filter((l) => l.includes('MAIN_STARTUP_SMOKE')).join('\n'))
  console.log(`smoke-main-startup: electron screenshot OK -> ${screenshotPath}`)
} else {
  console.error('smoke-main-startup: electron smoke FAILED')
  console.error(electronResult.output)
}

killApp()
const packagedResult = runPackagedVisualSmoke()
killApp()

if (packagedResult.ok && packagedResult.shot) {
  console.log(`smoke-main-startup: packaged screenshot -> ${packagedResult.shot}`)
} else if (!packagedResult.ok) {
  console.error('smoke-main-startup: packaged visual FAILED')
  console.error(packagedResult.output)
}

const startupLog = path.join(userData, 'startup.log')
if (fs.existsSync(startupLog)) {
  console.log('--- startup.log (tail) ---')
  console.log(fs.readFileSync(startupLog, 'utf8').split('\n').slice(-12).join('\n'))
}

const pass = electronResult.ok
process.exit(pass ? 0 : 1)
