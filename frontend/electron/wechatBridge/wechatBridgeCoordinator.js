/**
 * WeChat ClawBot coordinator — download/start/stop + character binding.
 */
import {
  WECHAT_CHANNEL_MANIFEST_PATH,
  parseWechatChannelLatestYml,
  resolveWechatChannelAssetUrl,
  getWechatChannelStatus,
} from './wechatChannelRelease.js'
import { ensureWechatChannelRuntime, wipeWechatChannelInstall } from './wechatChannelDownloader.js'
import { readWechatBridgeSettings, writeWechatBridgeSettings } from './wechatBridgeSettings.js'
import { createWechatHost } from './wechatHost.js'
import { configureWechatBridge, handleWechatHostMessage, fanoutWechatProactive } from './wechatBridge.js'
import { HOST_MSG } from './wechatProtocol.js'

export function createWechatBridgeCoordinator(deps) {
  function broadcast(channel, payload) {
    for (const win of deps.getWindows()) {
      if (win && !win.isDestroyed()) {
        try {
          win.webContents.send(channel, payload)
        } catch {
          /* ignore */
        }
      }
    }
  }

  function pushStatus(extra = {}) {
    const hostStatus = host.getStatus()
    const installed = getWechatChannelStatus()
    broadcast('desktop:wechat-host-status', {
      ...hostStatus,
      installed: installed.installed,
      version: installed.version,
      ...extra,
      ts: Date.now(),
    })
  }

  const host = createWechatHost({
    log: (msg) => deps.log?.(msg),
    onMessage: (msg) => {
      if (msg.type === HOST_MSG.INBOUND) handleWechatHostMessage(msg)
      pushStatus()
    },
  })

  async function ensureBinding({ apiOrigin, authToken, characterId }) {
    const unwrap = (res, path) => {
      if (!res || res.status < 200 || res.status >= 300) throw new Error(`api:${path} HTTP ${res?.status}`)
      const body = JSON.parse(res.data || '{}')
      if (typeof body.code === 'number') {
        if (body.code !== 200) throw new Error(`api:${path} code ${body.code}`)
        return body.data
      }
      return body
    }
    const apiGet = async (path) => unwrap(await deps.performApiRequest({
      method: 'GET', url: `${apiOrigin}${path}`, timeoutMs: 15000, apiOrigin, authToken,
    }), path)
    const apiPost = async (path, payload) => unwrap(await deps.performApiRequest({
      method: 'POST',
      url: `${apiOrigin}${path}`,
      headers: { 'Content-Type': 'application/json' },
      body: payload,
      timeoutMs: 30000,
      apiOrigin,
      authToken,
    }), path)

    try {
      const list = await apiGet('/api/conversation')
      if (Array.isArray(list) && list.length) {
        const single = characterId
          ? list.find((c) => c?.mode === 'SINGLE' && String(c?.characterId) === String(characterId))
          : (list.find((c) => c?.mode === 'SINGLE') || list[0])
        if (single?.id) {
          return {
            conversationId: String(single.id),
            characterId: characterId ? String(characterId) : (single.characterId ? String(single.characterId) : ''),
          }
        }
      }
    } catch (e) {
      deps.log?.(`[wechatBridge] list conversations failed: ${e?.message || e}`)
    }
    try {
      const chars = await apiGet('/api/character')
      const pick = characterId
        ? (Array.isArray(chars) ? chars.find((c) => String(c?.id) === String(characterId)) : null)
        : (Array.isArray(chars) && chars.length ? chars[0] : null)
      if (!pick?.id) return null
      const created = await apiPost('/api/conversation', { characterId: String(pick.id), mode: 'SINGLE' })
      if (!created?.id) return null
      return { conversationId: String(created.id), characterId: String(pick.id) }
    } catch (e) {
      deps.log?.(`[wechatBridge] auto-create conversation failed: ${e?.message || e}`)
    }
    return null
  }

  async function installFromCloud(onProgress) {
    const secrets = deps.getRuntimeSecrets?.() || {}
    const apiOrigin = secrets.apiOrigin || deps.resolveApiOrigin?.() || ''
    if (!apiOrigin) throw new Error('no api origin')
    const ymlUrl = `${apiOrigin}${WECHAT_CHANNEL_MANIFEST_PATH}`
    const ymlResp = await deps.performApiRequest({
      method: 'GET',
      url: ymlUrl,
      apiOrigin,
      timeoutMs: 15000,
    })
    if (ymlResp.status !== 200) throw new Error(`wechat-channel-latest.yml HTTP ${ymlResp.status}`)
    const manifest = parseWechatChannelLatestYml(typeof ymlResp.data === 'string' ? ymlResp.data : '')
    if (!manifest) throw new Error('wechat-channel-latest.yml parse failed')
    const downloadUrl = resolveWechatChannelAssetUrl(manifest.url, ymlUrl, secrets.updateOrigin || '')
    if (!deps.isAllowedEgressUrl?.(downloadUrl, apiOrigin, secrets.updateOrigin || '')) {
      throw new Error('download url not allowed')
    }
    return ensureWechatChannelRuntime({
      manifest,
      downloadUrl,
      onProgress: (p) => {
        onProgress?.(p)
        broadcast('desktop:wechat-host-download', { ...p, ts: Date.now() })
      },
    })
  }

  async function startHost() {
    const authToken = await deps.resolveDesktopAuthToken()
    if (!authToken) return { ok: false, reason: 'not_logged_in' }
    const settings = readWechatBridgeSettings()
    if (!settings.hosting?.consented) return { ok: false, reason: 'not_consented' }
    if (!settings.binding?.characterId && !settings.binding?.conversationId) {
      return { ok: false, reason: 'no_character' }
    }
    const provider = (settings.binding?.provider || '').trim()
    if (!provider || provider.toLowerCase() === 'platform') {
      return { ok: false, reason: 'no_provider' }
    }
    try {
      await installFromCloud()
    } catch (e) {
      deps.log?.(`[wechatHost] install failed: ${e?.message || e}`)
      if (!getWechatChannelStatus().installed) {
        return { ok: false, reason: 'install_failed' }
      }
    }
    const apiOrigin = deps.resolveApiOrigin()
    const bound = await ensureBinding({
      apiOrigin,
      authToken,
      characterId: settings.binding?.characterId,
    })
    if (bound?.conversationId) {
      writeWechatBridgeSettings({
        enabled: true,
        binding: { ...settings.binding, conversationId: bound.conversationId, characterId: bound.characterId || settings.binding.characterId },
      })
    }
    configureWechatBridge({
      apiOrigin,
      authToken,
      performApiRequest: deps.performApiRequest,
      host,
      log: (msg) => deps.log?.(msg),
    })
    try {
      await host.start()
    } catch (e) {
      deps.log?.(`[wechatHost] start failed: ${e?.message || e}`)
      return { ok: false, reason: 'start_failed' }
    }
    if (!host.getStatus().loggedIn) host.requestLogin()
    writeWechatBridgeSettings({ enabled: true })
    pushStatus()
    return { ok: true, status: host.getStatus() }
  }

  async function autoStartIfNeeded() {
    const settings = readWechatBridgeSettings()
    if (!settings.enabled || !settings.hosting?.consented) return
    if (!settings.binding?.characterId && !settings.binding?.conversationId) return
    const provider = (settings.binding?.provider || '').trim()
    if (!provider || provider.toLowerCase() === 'platform') return
    try {
      await startHost()
    } catch (e) {
      deps.log?.(`[wechatHost] auto-start failed: ${e?.message || e}`)
    }
  }

  async function stopHost({ persist = true } = {}) {
    await host.stop()
    if (persist) writeWechatBridgeSettings({ enabled: false })
    pushStatus()
    return { ok: true, status: host.getStatus() }
  }

  async function reinstallHost() {
    await host.stop()
    wipeWechatChannelInstall()
    return startHost()
  }

  function requestLogin() {
    const ok = host.requestLogin()
    pushStatus()
    return { ok }
  }

  return {
    host,
    startHost,
    stopHost,
    reinstallHost,
    requestLogin,
    autoStartIfNeeded,
    ensureBinding,
    fanoutWechatProactive,
    pushStatus,
    getStatus: () => ({
      ...host.getStatus(),
      ...getWechatChannelStatus(),
    }),
  }
}
