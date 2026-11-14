import { test } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { loadConfig, inspectShell, preflight } from '../src/config.js'

function tmpDir() {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'gw-cfg-'))
}

test('loadConfig 默认值面向本机试跑', () => {
  const cfg = loadConfig({})
  assert.equal(cfg.gatewayPort, 18080)
  assert.equal(cfg.gatewayHost, '127.0.0.1')
  assert.ok(cfg.dataRoot.replace(/\\/g, '/').endsWith('local/qq-gateway'))
  assert.equal(cfg.shellRoot, path.join(cfg.dataRoot, 'napcat-shell'))
  assert.equal(cfg.maxAccounts, 20)
})

test('loadConfig 读取环境变量并解析绝对路径', () => {
  const root = tmpDir()
  const cfg = loadConfig({
    DATA_ROOT: root,
    GATEWAY_PORT: '19999',
    MAX_ACCOUNTS: '5',
    QQ_EXE: path.join(root, 'QQ.exe'),
  })
  assert.equal(cfg.dataRoot, path.resolve(root))
  assert.equal(cfg.gatewayPort, 19999)
  assert.equal(cfg.maxAccounts, 5)
  assert.equal(cfg.qqExe, path.resolve(root, 'QQ.exe'))
})

test('inspectShell 目录不存在时给出 shell_dir_missing', () => {
  const r = inspectShell(path.join(tmpDir(), 'nope'))
  assert.equal(r.ok, false)
  assert.match(r.reason, /^shell_dir_missing:/)
})

test('inspectShell 列出缺失的具体文件', () => {
  const root = tmpDir()
  fs.writeFileSync(path.join(root, 'napcat.mjs'), '// stub')
  const r = inspectShell(root)
  assert.equal(r.ok, false)
  assert.deepEqual(r.missing.sort(), ['NapCatWinBootHook.dll', 'NapCatWinBootMain.exe', 'qqnt.json'])
})

test('preflight 同时报 shell 与 QQ_EXE 两类问题', () => {
  const root = tmpDir()
  const cfg = loadConfig({ DATA_ROOT: root })
  const r = preflight(cfg)
  assert.equal(r.ok, false)
  assert.deepEqual(r.problems.map((p) => p.code).sort(), ['qq_exe_unset', 'shell_incomplete'])
})

test('preflight 在 shell 齐全且 QQ_EXE 存在时通过', () => {
  const root = tmpDir()
  const shell = path.join(root, 'napcat-shell')
  fs.mkdirSync(shell, { recursive: true })
  for (const name of ['NapCatWinBootMain.exe', 'NapCatWinBootHook.dll', 'napcat.mjs', 'qqnt.json']) {
    fs.writeFileSync(path.join(shell, name), '')
  }
  const qqExe = path.join(root, 'QQ.exe')
  fs.writeFileSync(qqExe, '')
  const cfg = loadConfig({ DATA_ROOT: root, QQ_EXE: qqExe })
  assert.deepEqual(preflight(cfg), { ok: true, problems: [] })
})
