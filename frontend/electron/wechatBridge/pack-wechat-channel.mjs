/**
 * Build WechatChannel-win-x64-x.y.z.zip for MinIO updates/.
 * Run on a build machine (not the end-user PC). Downloads portable Node,
 * copies host.mjs, npm-installs qrcode (for login QR PNG). No OpenClaw Gateway.
 *
 * We do not `npm install @tencent-weixin/openclaw-weixin`: that package’s
 * Gateway agent would reply on its own and race us for getUpdates. The host
 * speaks the same iLink endpoints documented by that plugin (PLUGIN_VERSION
 * in lianyu-weixin-host.mjs).
 *
 * Usage:
 *   node frontend/electron/wechatBridge/pack-wechat-channel.mjs [version]
 */
import crypto from 'node:crypto'
import fs from 'node:fs'
import https from 'node:https'
import os from 'node:os'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { createWriteStream } from 'node:fs'
import { pipeline } from 'node:stream/promises'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const VERSION = process.argv[2] || '0.1.0'
const NODE_VERSION = '22.22.3'
const NODE_DIST = `https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-win-x64.zip`
const OUT_NAME = `WechatChannel-win-x64-${VERSION}.zip`

function download(url, dest) {
  return new Promise((resolve, reject) => {
    const go = (u) => {
      https.get(u, (res) => {
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
          go(res.headers.location)
          return
        }
        if (res.statusCode !== 200) {
          reject(new Error(`GET ${u} ${res.statusCode}`))
          return
        }
        pipeline(res, createWriteStream(dest)).then(resolve).catch(reject)
      }).on('error', reject)
    }
    go(url)
  })
}

async function sha256File(filePath) {
  const hash = crypto.createHash('sha256')
  const rs = fs.createReadStream(filePath)
  for await (const chunk of rs) hash.update(chunk)
  return hash.digest('hex')
}

async function main() {
  const staging = fs.mkdtempSync(path.join(os.tmpdir(), 'ly-wechat-ch-'))
  const nodeZip = path.join(staging, 'node.zip')
  console.log('download node', NODE_DIST)
  await download(NODE_DIST, nodeZip)

  const extract = (await import('extract-zip')).default
  const nodeDir = path.join(staging, 'node')
  fs.mkdirSync(nodeDir, { recursive: true })
  await extract(nodeZip, { dir: nodeDir })

  const unpacked = fs.readdirSync(nodeDir).map((n) => path.join(nodeDir, n))
  const nodeRoot = unpacked.find((p) => fs.existsSync(path.join(p, 'node.exe'))) || nodeDir
  const payload = path.join(staging, 'payload')
  fs.mkdirSync(payload, { recursive: true })
  fs.copyFileSync(path.join(nodeRoot, 'node.exe'), path.join(payload, 'node.exe'))
  fs.copyFileSync(path.join(__dirname, 'lianyu-weixin-host.mjs'), path.join(payload, 'host.mjs'))
  fs.writeFileSync(path.join(payload, 'package.json'), JSON.stringify({
    name: 'lianyu-wechat-channel',
    version: VERSION,
    type: 'module',
    private: true,
  }, null, 2))

  const npm = process.platform === 'win32' ? 'npm.cmd' : 'npm'
  const npmRes = spawnSync(npm, ['install', '--omit=dev', '--omit=peer', 'qrcode@1.5.4'], {
    cwd: payload,
    stdio: 'inherit',
    shell: true,
  })
  if (npmRes.status !== 0) throw new Error('npm install qrcode failed')

  const outDir = path.join(__dirname, '../../../artifacts/wechat-channel')
  fs.mkdirSync(outDir, { recursive: true })
  const outZip = path.join(outDir, OUT_NAME)

  const { execFileSync } = await import('node:child_process')
  if (process.platform === 'win32') {
    execFileSync('powershell.exe', [
      '-NoProfile', '-Command',
      `Compress-Archive -Path '${payload}\\*' -DestinationPath '${outZip}' -Force`,
    ], { stdio: 'inherit' })
  } else {
    execFileSync('zip', ['-r', outZip, '.'], { cwd: payload, stdio: 'inherit' })
  }

  const digest = await sha256File(outZip)
  const size = fs.statSync(outZip).size
  const yml = [
    `version: ${VERSION}`,
    `url: ${OUT_NAME}`,
    `sha256: ${digest}`,
    `size: ${size}`,
    '',
  ].join('\n')
  fs.writeFileSync(path.join(outDir, 'wechat-channel-latest.yml'), yml)
  console.log('wrote', outZip)
  console.log(yml)
  fs.rmSync(staging, { recursive: true, force: true })
}

main().catch((err) => {
  console.error(err)
  process.exit(1)
})
