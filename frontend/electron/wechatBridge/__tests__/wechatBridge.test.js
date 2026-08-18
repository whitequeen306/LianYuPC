import { describe, it, expect, beforeEach, vi } from 'vitest'

const { state } = vi.hoisted(() => ({
  state: {
    settings: {
      enabled: true,
      binding: { conversationId: '42', characterId: '7', provider: 'deepseek', model: '' },
      reply: { fallbackText: '稍后再试', timeoutMs: 120000 },
      hosting: { consented: true, version: '' },
    },
  },
}))

vi.mock('electron', () => ({
  app: { getPath: () => '/tmp/lianyu-wechat-bridge-test' },
  net: {},
}))

vi.mock('../wechatBridgeSettings.js', () => ({
  readWechatBridgeSettings: () => state.settings,
}))

import {
  configureWechatBridge,
  handleWechatHostMessage,
  fanoutWechatProactive,
  rememberWechatPeer,
  clearWechatPeer,
} from '../wechatBridge.js'

function makeHost() {
  return {
    sendText: vi.fn(() => true),
    getStatus: vi.fn(() => ({ running: true, loggedIn: true, state: 'running' })),
  }
}

beforeEach(() => {
  state.settings = {
    enabled: true,
    binding: { conversationId: '42', characterId: '7', provider: 'deepseek', model: '' },
    reply: { fallbackText: '稍后再试', timeoutMs: 120000 },
    hosting: { consented: true, version: '' },
  }
  clearWechatPeer()
})

describe('inbound turns', () => {
  it('relays text to the bound conversation and replies to fromUserId', async () => {
    const host = makeHost()
    const performApiRequest = vi.fn(async ({ url }) => {
      if (String(url).includes('/messages/stream')) {
        return { status: 200, data: 'data: {"content":"你好"}\n\n' }
      }
      throw new Error(`unexpected ${url}`)
    })
    configureWechatBridge({
      apiOrigin: 'https://api.lianyu.test',
      authToken: 't',
      performApiRequest,
      host,
      log: () => {},
    })
    handleWechatHostMessage({
      type: 'inbound',
      text: 'hello',
      fromUserId: 'wx-user',
      toUserId: 'bot',
      contextToken: 'ctx-1',
    })
    await vi.waitFor(() => expect(host.sendText).toHaveBeenCalled())
    expect(performApiRequest).toHaveBeenCalledWith(expect.objectContaining({
      url: 'https://api.lianyu.test/api/conversation/42/messages/stream',
      body: expect.objectContaining({ provider: 'deepseek', content: 'hello' }),
    }))
    expect(host.sendText).toHaveBeenCalledWith({
      toUserId: 'wx-user',
      contextToken: 'ctx-1',
      text: '你好',
    })
  })

  it('uploads inbound images then includes imageUrl on the turn', async () => {
    const host = makeHost()
    const performApiRequest = vi.fn(async ({ url }) => {
      if (String(url).includes('chat-image')) {
        return { status: 200, data: JSON.stringify({ code: 200, data: { imageUrl: '/api/public/files/chat-images/a.jpg' } }) }
      }
      if (String(url).includes('/messages/stream')) {
        return { status: 200, data: 'data: {"content":"看到了"}\n\n' }
      }
      throw new Error(`unexpected ${url}`)
    })
    configureWechatBridge({
      apiOrigin: 'https://api.lianyu.test',
      authToken: 't',
      performApiRequest,
      host,
      log: () => {},
    })
    handleWechatHostMessage({
      type: 'inbound',
      text: 'look',
      imageBase64: Buffer.from('fake-image').toString('base64'),
      mime: 'image/jpeg',
      fromUserId: 'wx-user',
      contextToken: 'ctx-2',
    })
    await vi.waitFor(() => expect(host.sendText).toHaveBeenCalled())
    const streamCall = performApiRequest.mock.calls.find(([req]) => String(req.url).includes('/messages/stream'))
    expect(streamCall[0].body.imageUrl).toBe('/api/public/files/chat-images/a.jpg')
  })

  it('does not call the conversation API when provider is platform', async () => {
    state.settings.binding.provider = 'platform'
    const host = makeHost()
    const performApiRequest = vi.fn()
    configureWechatBridge({
      apiOrigin: 'https://api.lianyu.test',
      authToken: 't',
      performApiRequest,
      host,
      log: () => {},
    })
    handleWechatHostMessage({
      type: 'inbound',
      text: 'hello',
      fromUserId: 'wx-user',
      contextToken: 'ctx-3',
    })
    await vi.waitFor(() => expect(host.sendText).toHaveBeenCalled())
    expect(performApiRequest).not.toHaveBeenCalled()
  })
})

describe('proactive fanout', () => {
  it('sends the latest assistant when a context token exists', async () => {
    const host = makeHost()
    const performApiRequest = vi.fn(async () => ({
      status: 200,
      data: JSON.stringify({
        code: 200,
        data: {
          records: [
            { role: 'ASSISTANT', seq: 1, content: 'old' },
            { role: 'USER', seq: 2, content: 'hi' },
            { role: 'ASSISTANT', seq: 3, content: '午安' },
          ],
        },
      }),
    }))
    configureWechatBridge({
      apiOrigin: 'https://api.lianyu.test',
      authToken: 't',
      performApiRequest,
      host,
      log: () => {},
    })
    rememberWechatPeer({ toUserId: 'wx-user', contextToken: 'ctx-live' })
    const result = await fanoutWechatProactive({ conversationId: '42', preview: 'ignored-if-latest' })
    expect(result).toEqual({ ok: true })
    expect(host.sendText).toHaveBeenCalledTimes(1)
    expect(host.sendText).toHaveBeenCalledWith({
      toUserId: 'wx-user',
      contextToken: 'ctx-live',
      text: '午安',
    })
  })

  it('skips when there is no context token', async () => {
    const host = makeHost()
    configureWechatBridge({
      apiOrigin: 'https://api.lianyu.test',
      authToken: 't',
      performApiRequest: vi.fn(),
      host,
      log: () => {},
    })
    const result = await fanoutWechatProactive({ conversationId: '42', preview: 'hi' })
    expect(result.reason).toBe('no_context_token')
    expect(host.sendText).not.toHaveBeenCalled()
  })

  it('skips enter.wav room-enter voice', async () => {
    const host = makeHost()
    const performApiRequest = vi.fn(async () => ({
      status: 200,
      data: JSON.stringify({
        code: 200,
        data: { records: [{ role: 'ASSISTANT', seq: 9, content: '回来啦', audioUrl: '/static/enter.wav' }] },
      }),
    }))
    configureWechatBridge({
      apiOrigin: 'https://api.lianyu.test',
      authToken: 't',
      performApiRequest,
      host,
      log: () => {},
    })
    rememberWechatPeer({ toUserId: 'wx-user', contextToken: 'ctx-live' })
    const result = await fanoutWechatProactive({ conversationId: '42', preview: '回来啦' })
    expect(result.reason).toBe('enter_voice')
    expect(host.sendText).not.toHaveBeenCalled()
  })

  it('does not send when conversation ids do not match', async () => {
    const host = makeHost()
    configureWechatBridge({
      apiOrigin: 'https://api.lianyu.test',
      authToken: 't',
      performApiRequest: vi.fn(),
      host,
      log: () => {},
    })
    rememberWechatPeer({ toUserId: 'wx-user', contextToken: 'ctx-live' })
    const result = await fanoutWechatProactive({ conversationId: '99' })
    expect(result.reason).toBe('conversation_mismatch')
    expect(host.sendText).not.toHaveBeenCalled()
  })
})
