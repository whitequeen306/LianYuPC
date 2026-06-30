import { describe, it, expect, beforeEach, vi } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import os from 'node:os'

// napcatConfig 经由 napcatRelease 间接依赖 electron；mock app.getPath 指向 tmp，
// 以免在 vitest 环境加载真实 electron 报错（ensureNapCatConfig 本身不触达 app，
// 但模块加载链需要）。
const { state } = vi.hoisted(() => ({ state: { userData: '' } }))
vi.mock('electron', () => ({ app: { getPath: () => state.userData } }))

// ensureNapCatConfig 仅用 getNapCatInstallRoot / locateNapCatEntry；mock 成 tmp 下
// 固定路径，使其写入真实文件供断言。entry.cwd=installRoot 使 resolveConfigDir
// 落到 installRoot/config（与运行时 NapCat 读取位置一致）。
const { mocks } = vi.hoisted(() => ({
  mocks: { installRoot: '', entry: null },
}))
vi.mock('../napcatRelease.js', () => ({
  getNapCatInstallRoot: () => mocks.installRoot,
  locateNapCatEntry: () => mocks.entry,
}))

import { ensureNapCatConfig } from '../napcatConfig.js'

let tmp
let configDir

beforeEach(() => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'napcat-cfg-'))
  state.userData = tmp
  mocks.installRoot = path.join(tmp, 'napcat')
  mocks.entry = { exe: path.join(mocks.installRoot, 'NapCatWinBootMain.exe'), cwd: mocks.installRoot }
  fs.mkdirSync(mocks.installRoot, { recursive: true })
  configDir = path.join(mocks.installRoot, 'config')
})

function readJson(name) {
  return JSON.parse(fs.readFileSync(path.join(configDir, name), 'utf8'))
}

describe('ensureNapCatConfig — token 定死 lianyupc', () => {
  it('首启：onebot11.json + webui.json 新建时 token 即 lianyupc，返回值亦然', () => {
    const cfg = ensureNapCatConfig()
    expect(cfg.wsToken).toBe('lianyupc')
    expect(cfg.webuiToken).toBe('lianyupc')
    expect(readJson('onebot11.json').network.websocketServers[0].token).toBe('lianyupc')
    expect(readJson('webui.json').token).toBe('lianyupc')
  })

  it('残留旧随机 token：onebot11.json + webui.json 强制覆盖回 lianyupc', () => {
    fs.mkdirSync(configDir, { recursive: true })
    fs.writeFileSync(
      path.join(configDir, 'onebot11.json'),
      JSON.stringify({
        network: {
          websocketServers: [
            { name: 'old', enable: true, host: '127.0.0.1', port: 3001, token: 'OLD9721739493a002f1f6f6c5c9242679df' },
          ],
          httpServers: [],
          httpClients: [],
          websocketClients: [],
        },
      }),
    )
    fs.writeFileSync(
      path.join(configDir, 'webui.json'),
      JSON.stringify({ host: '127.0.0.1', port: 6099, token: 'OLDWEBUIeb1c110543e68abf49fb4972952428e6', loginRate: 3, debug: false }),
    )

    ensureNapCatConfig()

    expect(readJson('onebot11.json').network.websocketServers[0].token).toBe('lianyupc')
    expect(readJson('webui.json').token).toBe('lianyupc')
  })

  it('残留 per-account onebot11_<uin>.json：token 覆盖为 lianyupc（修复 bridge 鉴权失败）', () => {
    fs.mkdirSync(configDir, { recursive: true })
    fs.writeFileSync(
      path.join(configDir, 'onebot11.json'),
      JSON.stringify({
        network: {
          websocketServers: [{ name: 'lianyu-ws', enable: true, host: '127.0.0.1', port: 3001, token: 'OLD' }],
          httpServers: [], httpClients: [], websocketClients: [],
        },
      }),
    )
    // per-account 残留旧随机 token：NapCat 首登时从模板复制、之后读的就是这份
    fs.writeFileSync(
      path.join(configDir, 'onebot11_3951775904.json'),
      JSON.stringify({
        network: {
          websocketServers: [{ name: 'lianyu-ws', enable: true, host: '127.0.0.1', port: 3001, token: 'OLD9721739493a002f1f6f6c5c9242679df', messagePostFormat: 'array' }],
          httpServers: [], httpClients: [], websocketClients: [], httpSseServers: [], plugins: [],
        },
      }),
    )

    ensureNapCatConfig()

    expect(readJson('onebot11_3951775904.json').network.websocketServers[0].token).toBe('lianyupc')
    // 模板也被覆盖
    expect(readJson('onebot11.json').network.websocketServers[0].token).toBe('lianyupc')
    // per-account 的非 token 字段保留（仅覆盖 token，不破坏其余配置）
    expect(readJson('onebot11_3951775904.json').network.websocketServers[0].messagePostFormat).toBe('array')
  })

  it('已是 lianyupc：幂等，token 不变', () => {
    fs.mkdirSync(configDir, { recursive: true })
    fs.writeFileSync(
      path.join(configDir, 'onebot11.json'),
      JSON.stringify({
        network: {
          websocketServers: [{ name: 'lianyu-ws', enable: true, host: '127.0.0.1', port: 3001, token: 'lianyupc' }],
          httpServers: [], httpClients: [], websocketClients: [],
        },
      }),
    )
    fs.writeFileSync(
      path.join(configDir, 'onebot11_3951775904.json'),
      JSON.stringify({
        network: {
          websocketServers: [{ name: 'lianyu-ws', enable: true, host: '127.0.0.1', port: 3001, token: 'lianyupc' }],
          httpServers: [], httpClients: [], websocketClients: [],
        },
      }),
    )

    ensureNapCatConfig()

    expect(readJson('onebot11.json').network.websocketServers[0].token).toBe('lianyupc')
    expect(readJson('onebot11_3951775904.json').network.websocketServers[0].token).toBe('lianyupc')
  })
})
