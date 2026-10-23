import { execFileSync, execSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { resolveGithubToken } from './electron-release-token.js'

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..')
const bump = ['patch', 'minor', 'major'].includes(process.argv[2]) ? process.argv[2] : 'patch'

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

const ghToken = resolveGithubToken({
  env: process.env,
  credentialFillOutput: readGithubCredentialFill(),
})

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
const brandedAssets = [
  path.join(releaseDir, installerName),
  path.join(releaseDir, `${installerName}.blockmap`),
  path.join(releaseDir, 'latest.yml'),
]
execFileSync('gh', ['release', 'upload', tag, ...brandedAssets, '--clobber'], {
  stdio: 'inherit',
  env: process.env,
})
execFileSync('gh', ['release', 'edit', tag, '--draft=false'], {
  stdio: 'inherit',
  env: process.env,
})
execSync(`python ../scripts/_upload_update_assets.py --version ${pkg.version}`, { stdio: 'inherit' })

// 桌面能力层已改为自研（Electron 内置 PowerShell actor），不再随 Electron 分发 AgentEngine 引擎包。
// AgentAssistant / agent-latest.yml / AgentEngine-*.zip 相关上传链路与 AGENT_ENGINE_* 环境变量一并移除。
