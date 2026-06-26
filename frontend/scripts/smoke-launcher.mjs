/**
 * 打包前桌宠冒烟：启动 Electron 主进程，验证 launcher preload + toggleCharacterPicker IPC。
 * 由 electron-pack.mjs 在 bytecode 编译后、electron-builder 前调用。
 */
import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..')
const electronBin =
  process.platform === 'win32'
    ? path.join(root, 'node_modules', 'electron', 'dist', 'electron.exe')
    : path.join(root, 'node_modules', 'electron', 'dist', 'electron')

const mainEntry = path.join(root, 'dist-electron', 'main.js')
const distIndex = path.join(root, 'dist', 'index.html')

if (!fs.existsSync(mainEntry)) {
  console.error('smoke-launcher: missing dist-electron/main.js — run vite build first')
  process.exit(1)
}
if (!fs.existsSync(distIndex)) {
  console.error('smoke-launcher: missing dist/index.html — run vite build first')
  process.exit(1)
}
if (!fs.existsSync(electronBin)) {
  console.error('smoke-launcher: electron binary not found — run npm ci')
  process.exit(1)
}

console.log('smoke-launcher: starting Electron launcher smoke test...')
const result = spawnSync(electronBin, [mainEntry], {
  cwd: path.join(root, 'dist-electron'),
  env: { ...process.env, LIANYU_LAUNCHER_SMOKE: '1' },
  encoding: 'utf8',
  timeout: 120000,
})

const output = `${result.stdout || ''}\n${result.stderr || ''}`
if (result.status !== 0 || !output.includes('LAUNCHER_SMOKE_OK')) {
  console.error('smoke-launcher: FAILED')
  console.error(output)
  process.exit(1)
}

console.log('smoke-launcher: PASSED')
if (output.includes('LAUNCHER_SMOKE_OK')) {
  console.log(output.split('\n').filter((l) => l.includes('LAUNCHER_SMOKE')).join('\n'))
}
