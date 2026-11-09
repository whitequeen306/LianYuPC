import { execFileSync, execSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { resolveGithubToken } from './electron-release-token.js'

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..')
const argv = process.argv.slice(2)
const bump = argv.find((arg) => ['patch', 'minor', 'major'].includes(arg)) || 'patch'

/**
 * 品牌安装包的体积下限。原生 NSIS 约 168MB，品牌 WPF 壳约 231MB。
 * 2026-11-09 v0.3.17 事故：gh release upload 撞 GitHub 502 后，Release 上留着
 * electron-builder 自己传的 168MB 原生包，脚本却「成功」退出 —— 用户装完是白框。
 * 所以体积必须当成契约校验，不能靠肉眼看日志。
 */
const MIN_BRANDED_BYTES = 200 * 1024 * 1024

const verifyOnlyIdx = argv.indexOf('--verify-only')
const verifyOnly = verifyOnlyIdx >= 0 ? argv[verifyOnlyIdx + 1] : null
// 注意：--verify-only 后面漏写版本号时是 undefined，不能用 falsy 判断，
// 否则会掉进「正常发版」分支，直接把版本号推上去开始打包。
if (verifyOnlyIdx >= 0 && (!verifyOnly || verifyOnly.startsWith('--'))) {
  console.error('[electron:release] --verify-only 需要版本号，例如 --verify-only 0.3.18')
  process.exit(2)
}
const minMbIdx = argv.indexOf('--min-mb')
const minMb = minMbIdx >= 0 ? argv[minMbIdx + 1] : null
if (minMbIdx >= 0 && (!minMb || minMb.startsWith('--'))) {
  console.error('[electron:release] --min-mb 需要一个正数，例如 --min-mb 200')
  process.exit(2)
}
const minBytes = minMb ? Math.round(Number(minMb) * 1024 * 1024) : MIN_BRANDED_BYTES
if (!Number.isFinite(minBytes) || minBytes <= 0) {
  console.error('[electron:release] --min-mb 需要一个正数，例如 --min-mb 200')
  process.exit(2)
}

function readGithubCredentialFill() {
  try {
    return execSync('git credential fill', {
      input: 'protocol=https\nhost=github.com\n\n',
      encoding: 'utf8',
      stdio: ['pipe', 'pipe', 'ignore'],
    })
  } catch {
    return ''
  }
}

function resolveToken() {
  return resolveGithubToken({
    env: process.env,
    credentialFillOutput: readGithubCredentialFill(),
  })
}

function mb(bytes) {
  return `${(bytes / 1024 / 1024).toFixed(1)}MB`
}

/**
 * 校验 Release 上的安装包确实是「品牌壳」那一份。
 * 两种失败各自说清楚，别让下一个人再去翻 500 行打包日志：
 *  - 体积过小 → 传上去的是原生 NSIS，装出来是白框；
 *  - 与本地文件不等 → 上传被截断（网络抽风 / 502）。
 */
function assertBrandedAsset(version, { localExe } = {}) {
  const tag = `v${version}`
  const installerName = `YuNian-Setup-${version}.exe`
  let assets
  try {
    assets = JSON.parse(
      execFileSync('gh', ['release', 'view', tag, '--json', 'assets'], {
        encoding: 'utf8',
        env: process.env,
        stdio: ['ignore', 'pipe', 'pipe'],
      }),
    ).assets
  } catch (error) {
    throw new Error(`读取 Release ${tag} 的资产失败：${error.message}`)
  }

  const exe = assets.find((asset) => asset.name === installerName)
  if (!exe) {
    const names = assets.map((asset) => asset.name).join(', ') || '(空)'
    throw new Error(`Release ${tag} 上没有 ${installerName}，当前资产：${names}`)
  }
  if (exe.size < minBytes) {
    throw new Error(
      `Release ${tag} 的 ${installerName} 只有 ${mb(exe.size)}，低于 ${mb(minBytes)} —— ` +
        '这是原生 NSIS 包，装出来是白框。品牌壳由 scripts/prepare-branded-release.mjs 生成，' +
        '请重新上传 release/ 下的品牌包（见 AGENTS.md §7 排障索引）。',
    )
  }
  if (localExe && fs.existsSync(localExe)) {
    const localSize = fs.statSync(localExe).size
    if (localSize !== exe.size) {
      throw new Error(
        `Release ${tag} 的 ${installerName} 是 ${exe.size} 字节，本地却是 ${localSize} 字节 —— ` +
          '上传被截断，请重跑 gh release upload ... --clobber。',
      )
    }
  }
  return exe
}

if (verifyOnly) {
  const token = resolveToken()
  if (!token) {
    console.error('[electron:release] 校验 Release 需要 GitHub 凭据（GH_TOKEN 或 git credential.helper）')
    process.exit(1)
  }
  process.env.GH_TOKEN = token
  try {
    const exe = assertBrandedAsset(verifyOnly, {
      localExe: path.join(root, 'release', `v${verifyOnly}`, `YuNian-Setup-${verifyOnly}.exe`),
    })
    console.log(`[electron:release] OK v${verifyOnly} ${exe.name} ${mb(exe.size)} (${exe.size} 字节)`)
    process.exit(0)
  } catch (error) {
    console.error(`[electron:release] 校验失败：${error.message}`)
    process.exit(1)
  }
}

const ghToken = resolveToken()

// 发版需上传 GitHub Releases，优先用 GH_TOKEN 环境变量；若未设置则回退到
// Windows Credential Manager / git credential helper=manager 中保存的 github.com 凭据。
// 本地仅打包测试请用 npm run electron:build（不校验、不上传）
if (!ghToken) {
  console.error('\n[electron:release] 缺少 GitHub 发布凭据，无法发布到 GitHub Releases。')
  console.error('  可用任一方式提供：')
  console.error('  1. PowerShell 临时设置： $env:GH_TOKEN = "ghp_xxxx"')
  console.error('  2. 先用 git/GitHub Desktop 登录，让 git credential.helper=manager 能返回 github.com 凭据')
  console.error('  仅本地打包不上传请改用：npm run electron:build\n')
  process.exit(1)
}

process.env.GH_TOKEN = ghToken

process.chdir(root)
execSync(`npm version ${bump} --no-git-tag-version`, { stdio: 'inherit' })
execSync('node scripts/electron-pack.mjs', { stdio: 'inherit' })
const pkg = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'))
execSync('node scripts/prepare-branded-release.mjs', { stdio: 'inherit' })

const tag = `v${pkg.version}`
const releaseDir = path.join(root, 'release', tag)
const installerName = `YuNian-Setup-${pkg.version}.exe`
const localInstaller = path.join(releaseDir, installerName)
const brandedAssets = [localInstaller, path.join(releaseDir, `${installerName}.blockmap`), path.join(releaseDir, 'latest.yml')]

execFileSync('gh', ['release', 'upload', tag, ...brandedAssets, '--clobber'], {
  stdio: 'inherit',
  env: process.env,
})

// 上传完先校验再发布：否则 502/截断会留下一个「看起来成功」的坏 Release。
const verified = assertBrandedAsset(pkg.version, { localExe: localInstaller })
console.log(`[electron:release] Release 资产校验通过：${verified.name} ${mb(verified.size)}`)

execFileSync('gh', ['release', 'edit', tag, '--draft=false'], {
  stdio: 'inherit',
  env: process.env,
})
execSync(`python ../scripts/_upload_update_assets.py --version ${pkg.version}`, { stdio: 'inherit' })

// 桌面能力层已改为自研（Electron 内置 PowerShell actor），不再随 Electron 分发 AgentEngine 引擎包。
// AgentAssistant / agent-latest.yml / AgentEngine-*.zip 相关上传链路与 AGENT_ENGINE_* 环境变量一并移除。
