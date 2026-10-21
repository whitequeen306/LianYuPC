// 只改「包版本」这两处，不碰任何依赖版本。
//   frontend/package.json      顶层 version
//   frontend/package-lock.json 顶层 version + packages[""].version
//
// 用法: node scripts/release-bump-version.mjs 0.3.4
import fs from 'node:fs'

const target = process.argv[2]
if (!/^\d+\.\d+\.\d+$/.test(target || '')) {
  console.error('用法: node scripts/release-bump-version.mjs <x.y.z>')
  process.exit(1)
}

const pkgPath = 'frontend/package.json'
const lockPath = 'frontend/package-lock.json'

const pkgRaw = fs.readFileSync(pkgPath, 'utf8')
if (pkgRaw.charCodeAt(0) === 0xfeff) throw new Error(`${pkgPath} has BOM`)
const current = JSON.parse(pkgRaw).version
if (current === target) {
  console.log(`已经是 ${target}，无需改动`)
  process.exit(0)
}

// 只替换第 3 行那句顶层 version（前面没有缩进的那一行）
const pkgOut = pkgRaw.replace(/^(\s{2}"version":\s*")[^"]+(")/m, `$1${target}$2`)
if (pkgOut === pkgRaw) throw new Error(`${pkgPath}: 没找到顶层 version 行`)
fs.writeFileSync(pkgPath, pkgOut, 'utf8')

const lockRaw = fs.readFileSync(lockPath, 'utf8')
if (lockRaw.charCodeAt(0) === 0xfeff) throw new Error(`${lockPath} has BOM`)
const lockLevel = JSON.parse(lockRaw).version
// package-lock 里有两处要改：顶层 version 与 packages[""].version。
// 用精确字符串替换，避免误伤依赖（例如 tinyexec 也恰好是 0.3.2）。
const lockOut = lockRaw
  .replace(`\n  "version": "${lockLevel}",`, `\n  "version": "${target}",`)
  .replace(`\n      "version": "${lockLevel}",`, `\n      "version": "${target}",`)
fs.writeFileSync(lockPath, lockOut, 'utf8')

const after = {
  pkg: JSON.parse(fs.readFileSync(pkgPath, 'utf8')).version,
  lock: JSON.parse(fs.readFileSync(lockPath, 'utf8')).version,
  lockRoot: JSON.parse(fs.readFileSync(lockPath, 'utf8')).packages[''].version,
}
console.log(`${current} -> ${target}`)
console.log(after)
