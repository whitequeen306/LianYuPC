import { execSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..')
const bump = ['patch', 'minor', 'major'].includes(process.argv[2]) ? process.argv[2] : 'patch'

// 发版需上传 GitHub Releases，必须配 GH_TOKEN（PAT，repo 权限）
// 本地仅打包测试请用 npm run electron:build（不校验、不上传）
if (!process.env.GH_TOKEN) {
  console.error('\n[electron:release] 缺少 GH_TOKEN 环境变量，无法发布到 GitHub Releases。')
  console.error('  请先创建 GitHub Personal Access Token（classic，勾选 repo 权限），然后：')
  console.error('  PowerShell:  $env:GH_TOKEN = "ghp_xxxx"')
  console.error('  cmd:         set GH_TOKEN=ghp_xxxx')
  console.error('  仅本地打包不上传请改用：npm run electron:build\n')
  process.exit(1)
}

process.chdir(root)
execSync(`npm version ${bump} --no-git-tag-version`, { stdio: 'inherit' })
execSync('node scripts/electron-pack.mjs', { stdio: 'inherit' })
const pkg = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'))
execSync(`python ../scripts/_upload_update_assets.py --version ${pkg.version}`, { stdio: 'inherit' })
