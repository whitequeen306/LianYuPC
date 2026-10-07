import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'

// 主动消息推送（fanoutQqProactive）单测：链了 electron / apiProxy / napCatClient /
// logger 重依赖，全部 mock；qqBridgeSettings 只 mock read/write（保留 DEFAULTS 与
// 放行判定真实现），让 reply 参数可注入、且不读写真实 userData。
vi.mock('electron', () => ({ app: { getPath: () => '' } }))
vi.mock('../../apiProxy.js', () => ({ performApiRequest: vi.fn() }))
vi.mock('../../logger.js', () => ({
  debug: vi.fn(),
  info: vi.fn(),
  warn: vi.fn(),
  error: vi.fn(),
  log: vi.fn(),
  getLogContent: vi.fn(() => ''),
}))
vi.mock('../napCatClient.js', () => ({ createNapCatClient: vi.fn() }))
vi.mock('../qqBridgeSettings.js', async (importOriginal) => {
  const actual = await importOriginal()
  return {
    ...actual,
    readQqBridgeSettings: vi.fn(),
    writeQqBridgeSettings: vi.fn(),
  }
})

import { performApiRequest } from '../../apiProxy.js'
import { createNapCatClient } from '../napCatClient.js'
import { readQqBridgeSettings } from '../qqBridgeSettings.js'
import {
  startQqBridge,
  stopQqBridge,
  fanoutQqProactive,
  resolveProactiveTargets,
  pickProactivePieces,
} from '../qqBridge.js'

function makeClient({ state = 'ready' } = {}) {
  return {
    start: vi.fn(),
    stop: vi.fn(),
    getStatus: vi.fn(() => ({ state, selfId: '10001' })),
    sendPrivateMsg: vi.fn(async () => {}),
    sendGroupMsg: vi.fn(async () => {}),
  }
}

function baseSettings(overrides = {}) {
  return {
    enabled: true,
    napcat: { wsUrl: 'ws://127.0.0.1:3001', accessToken: '' },
    binding: {
      conversationId: '42',
      characterName: '',
      sessionMap: { 'private:10001': '42' },
    },
    reply: { segmentDelayMs: 0, segmentJitterMs: 0, appendCharacterSuffix: false },
    ...overrides,
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  performApiRequest.mockReset()
  readQqBridgeSettings.mockReturnValue(baseSettings())
  createNapCatClient.mockImplementation(() => makeClient())
})

afterEach(() => {
  stopQqBridge()
})

describe('resolveProactiveTargets', () => {
  it('按 conversationId 反查 private:<qq> 目标', () => {
    expect(resolveProactiveTargets({ 'private:10001': '42' }, '42')).toEqual([10001])
    expect(resolveProactiveTargets({ 'private:10001': 42 }, 42)).toEqual([10001])
  })

  it('群会话（group: 键）不参与反查——服务端只为 SINGLE 会话生成主动消息', () => {
    expect(resolveProactiveTargets({ 'group:888': '42', 'private:10001': '42' }, '42')).toEqual([10001])
  })

  it('无命中 / 空入参返回空数组', () => {
    expect(resolveProactiveTargets({ 'private:10001': '42' }, '99')).toEqual([])
    expect(resolveProactiveTargets({}, '42')).toEqual([])
    expect(resolveProactiveTargets(null, '42')).toEqual([])
    expect(resolveProactiveTargets({ 'private:10001': '42' }, null)).toEqual([])
  })

  it('非法键值跳过（private:abc / private:-1）', () => {
    expect(resolveProactiveTargets({ 'private:abc': '42', 'private:-1': '42' }, '42')).toEqual([])
  })
})

describe('pickProactivePieces', () => {
  it('取最后一条 USER 之后的全部 ASSISTANT 分段（多段主动回复全发）', () => {
    const records = [
      { role: 'USER', content: '在吗' },
      { role: 'ASSISTANT', content: '在的' },
      { role: 'USER', content: '那没事了' },
      { role: 'ASSISTANT', content: '早安呀' },
      { role: 'ASSISTANT', content: '今天也想见你' },
    ]
    expect(pickProactivePieces(records)).toEqual(['早安呀', '今天也想见你'])
  })

  it('过滤空 content 的语音类记录（audioUrl 无文案）', () => {
    const records = [
      { role: 'USER', content: 'hi' },
      { role: 'ASSISTANT', content: '', audioUrl: 'pet/noon.mp3' },
      { role: 'ASSISTANT', content: '中午啦，记得吃饭' },
    ]
    expect(pickProactivePieces(records)).toEqual(['中午啦，记得吃饭'])
  })

  it('会话尚无 USER 消息时取全部 ASSISTANT；非数组入参返回空', () => {
    expect(pickProactivePieces([{ role: 'ASSISTANT', content: '破冰' }])).toEqual(['破冰'])
    expect(pickProactivePieces(null)).toEqual([])
    expect(pickProactivePieces('x')).toEqual([])
  })

  it('role 大小写不敏感', () => {
    expect(pickProactivePieces([
      { role: 'user', content: 'a' },
      { role: 'assistant', content: 'b' },
    ])).toEqual(['b'])
  })
})

describe('fanoutQqProactive', () => {
  it('按 sessionMap 反查目标，拉取尾部 assistant 分段逐条发送', async () => {
    const client = makeClient()
    createNapCatClient.mockImplementation(() => client)
    performApiRequest.mockResolvedValue({
      status: 200,
      data: JSON.stringify({ data: { records: [
        { role: 'USER', content: '晚安' },
        { role: 'ASSISTANT', content: '做个好梦' },
        { role: 'ASSISTANT', content: '明天见' },
      ] } }),
    })
    startQqBridge({
      apiOrigin: 'https://api.test',
      authToken: 'tok',
      settings: baseSettings(),
    })
    const result = await fanoutQqProactive({
      conversationId: '42',
      characterId: '7',
      preview: '做个好梦',
    })
    expect(result).toEqual({ ok: true })
    expect(client.sendPrivateMsg).toHaveBeenCalledTimes(2)
    expect(client.sendPrivateMsg).toHaveBeenNthCalledWith(1, 10001, '做个好梦')
    expect(client.sendPrivateMsg).toHaveBeenNthCalledWith(2, 10001, '明天见')
    // 只拉一次消息页
    expect(performApiRequest).toHaveBeenCalledTimes(1)
    expect(performApiRequest.mock.calls[0][0].url).toContain('/api/conversation/42/messages?limit=20')
  })

  it('绑定角色名存在且开关开启时带尾缀（与回复链路一致）', async () => {
    const client = makeClient()
    createNapCatClient.mockImplementation(() => client)
    performApiRequest.mockResolvedValue({
      status: 200,
      data: JSON.stringify({ data: { records: [{ role: 'ASSISTANT', content: '想你啦' }] } }),
    })
    readQqBridgeSettings.mockReturnValue(baseSettings({
      binding: { conversationId: '42', characterName: '小雪', sessionMap: { 'private:10001': '42' } },
      reply: { segmentDelayMs: 0, segmentJitterMs: 0, appendCharacterSuffix: true },
    }))
    startQqBridge({
      apiOrigin: 'https://api.test',
      authToken: 'tok',
      settings: baseSettings({
        binding: { conversationId: '42', characterName: '小雪', sessionMap: { 'private:10001': '42' } },
      }),
    })
    const result = await fanoutQqProactive({ conversationId: '42', preview: '想你啦' })
    expect(result).toEqual({ ok: true })
    expect(client.sendPrivateMsg).toHaveBeenCalledWith(10001, '想你啦（本次回复由虚拟角色:小雪回复）')
  })

  it('15s 窗口内相同载荷去重（QuickChat + 主窗口双 STOMP 各发一次 IPC）', async () => {
    createNapCatClient.mockImplementation(() => makeClient())
    startQqBridge({
      apiOrigin: 'https://api.test',
      authToken: 'tok',
      settings: baseSettings(),
    })
    const payload = { conversationId: '42', characterId: '7', preview: ' dedup-me ' }
    const first = await fanoutQqProactive(payload)
    const second = await fanoutQqProactive({ ...payload })
    expect(first.ok).toBe(true)
    expect(second).toEqual({ ok: false, reason: 'duplicate' })
  })

  it('会话未绑定任何 QQ 用户（not_qq_linked）时跳过且不拉消息', async () => {
    createNapCatClient.mockImplementation(() => makeClient())
    startQqBridge({
      apiOrigin: 'https://api.test',
      authToken: 'tok',
      settings: baseSettings(),
    })
    const result = await fanoutQqProactive({ conversationId: '99', preview: 'hi' })
    expect(result).toEqual({ ok: false, reason: 'not_qq_linked' })
    expect(performApiRequest).not.toHaveBeenCalled()
  })

  it('拉取消息失败时回退通知 preview 单段', async () => {
    const client = makeClient()
    createNapCatClient.mockImplementation(() => client)
    performApiRequest.mockRejectedValue(new Error('network down'))
    startQqBridge({
      apiOrigin: 'https://api.test',
      authToken: 'tok',
      settings: baseSettings(),
    })
    const result = await fanoutQqProactive({ conversationId: '42', preview: '（兜底预览）' })
    expect(result).toEqual({ ok: true })
    expect(client.sendPrivateMsg).toHaveBeenCalledWith(10001, '（兜底预览）')
  })

  it('桥接未运行时跳过（bridge_stopped）', async () => {
    const result = await fanoutQqProactive({ conversationId: '42', preview: 'hi' })
    expect(result).toEqual({ ok: false, reason: 'bridge_stopped' })
  })

  it('WS 断开状态下跳过（ws_disconnected）', async () => {
    createNapCatClient.mockImplementation(() => makeClient({ state: 'disconnected' }))
    startQqBridge({
      apiOrigin: 'https://api.test',
      authToken: 'tok',
      settings: baseSettings(),
    })
    const result = await fanoutQqProactive({ conversationId: '42', preview: 'hi-offline' })
    expect(result).toEqual({ ok: false, reason: 'ws_disconnected' })
  })
})
