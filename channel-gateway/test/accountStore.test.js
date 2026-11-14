import { test } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { loadConfig } from '../src/config.js'
import {
  accountDir,
  createAccount,
  ensureAccountConfig,
  isValidKey,
  listAccounts,
  profileDir,
  readAccount,
  writeAccount,
} from '../src/accountStore.js'

function tmpCfg() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'gw-acct-'))
  return { cfg: loadConfig({ DATA_ROOT: root }), root }
}

// 固定端口的假分配器：让用例不依赖机器端口占用情况
function fakeAllocator(seq = [30001, 31001, 30002, 31002]) {
  let i = 0
  return async () => seq[i++ % seq.length]
}

test('isValidKey 只放行安全字符且拒绝路径穿越', () => {
  assert.equal(isValidKey('u1001-char7'), true)
  assert.equal(isValidKey('a'), true)
  assert.equal(isValidKey('../etc'), false)
  assert.equal(isValidKey('a/b'), false)
  assert.equal(isValidKey(''), false)
  assert.equal(isValidKey('x'.repeat(33)), false)
})

test('createAccount 落盘账号记录且端口/令牌各号不同', async () => {
  const { cfg } = tmpCfg()
  const alloc = fakeAllocator()
  const a = await createAccount(cfg, { key: 'acct-a' }, { allocatePort: alloc })
  const b = await createAccount(cfg, { key: 'acct-b' }, { allocatePort: alloc })
  assert.equal(a.wsPort, 30001)
  assert.equal(a.webuiPort, 31001)
  assert.equal(b.wsPort, 30002)
  assert.equal(b.webuiPort, 31002)
  assert.notEqual(a.wsToken, b.wsToken)
  assert.equal(a.wsToken.length, 32)
  assert.deepEqual(readAccount(cfg, 'acct-a'), a)
  assert.equal(listAccounts(cfg).length, 2)
})

test('createAccount 幂等：重复创建不重置令牌（避免踢掉在线号）', async () => {
  const { cfg } = tmpCfg()
  const first = await createAccount(cfg, { key: 'acct-a' }, { allocatePort: fakeAllocator() })
  writeAccount(cfg, 'acct-a', { ...first, wsToken: 'fixed-token' })
  const again = await createAccount(cfg, { key: 'acct-a' }, { allocatePort: fakeAllocator([39999, 39998]) })
  assert.equal(again.wsToken, 'fixed-token')
  assert.equal(again.wsPort, first.wsPort)
})

test('ensureAccountConfig 生成工作目录、配置与 loadNapCat.js', async () => {
  const { cfg } = tmpCfg()
  const account = await createAccount(cfg, { key: 'acct-a' }, { allocatePort: fakeAllocator() })
  ensureAccountConfig(cfg, account)

  const dir = accountDir(cfg, 'acct-a')
  const configDir = path.join(dir, 'config')
  for (const sub of ['cache', 'logs', 'plugins', 'qq-profile']) {
    assert.ok(fs.existsSync(path.join(dir, sub)), `缺少 ${sub}`)
  }

  const onebot = JSON.parse(fs.readFileSync(path.join(configDir, 'onebot11.json'), 'utf8'))
  const srv = onebot.network.websocketServers[0]
  assert.equal(srv.port, account.wsPort)
  assert.equal(srv.token, account.wsToken)
  assert.equal(srv.host, '127.0.0.1')
  assert.equal(srv.messagePostFormat, 'array')

  const webui = JSON.parse(fs.readFileSync(path.join(configDir, 'webui.json'), 'utf8'))
  assert.equal(webui.port, account.webuiPort)
  assert.equal(webui.token, account.webuiToken)

  const napcat = JSON.parse(fs.readFileSync(path.join(configDir, 'napcat.json'), 'utf8'))
  assert.equal(napcat.o3HookMode, 1)
  for (const key of ['hook', 'window', 'module', 'process', 'container', 'js']) {
    assert.equal(napcat.bypass[key], true)
  }

  const loader = fs.readFileSync(path.join(dir, 'loadNapCat.js'), 'utf8')
  assert.match(loader, /await import\("file:\/\/\/.*napcat\.mjs"\)/)
  assert.ok(loader.includes(cfg.shellRoot.replace(/\\/g, '/')))
  assert.equal(profileDir(cfg, 'acct-a'), path.join(dir, 'qq-profile'))
})

test('ensureAccountConfig 覆盖历史 per-account 配置里的旧 token/端口', async () => {
  const { cfg } = tmpCfg()
  const account = await createAccount(cfg, { key: 'acct-a' }, { allocatePort: fakeAllocator() })
  ensureAccountConfig(cfg, account)

  const perAccount = path.join(accountDir(cfg, 'acct-a'), 'config', 'onebot11_755991743.json')
  fs.writeFileSync(
    perAccount,
    JSON.stringify({ network: { websocketServers: [{ enable: true, host: '127.0.0.1', port: 3001, token: 'old' }] } }),
  )

  ensureAccountConfig(cfg, account)
  const fixed = JSON.parse(fs.readFileSync(perAccount, 'utf8')).network.websocketServers[0]
  assert.equal(fixed.token, account.wsToken)
  assert.equal(fixed.port, account.wsPort)
})

test('ensureAccountConfig 幂等：二次调用不改变既有文件内容', async () => {
  const { cfg } = tmpCfg()
  const account = await createAccount(cfg, { key: 'acct-a' }, { allocatePort: fakeAllocator() })
  ensureAccountConfig(cfg, account)
  const onebotPath = path.join(accountDir(cfg, 'acct-a'), 'config', 'onebot11.json')
  const before = fs.readFileSync(onebotPath, 'utf8')
  ensureAccountConfig(cfg, account)
  assert.equal(fs.readFileSync(onebotPath, 'utf8'), before)
})

test('accountDir 拒绝非法 key', () => {
  const { cfg } = tmpCfg()
  assert.throws(() => accountDir(cfg, '../evil'), /invalid_account_key/)
})
