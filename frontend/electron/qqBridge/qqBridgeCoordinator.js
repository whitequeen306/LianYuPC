export function createQqBridgeCoordinator(deps) {
  let prevBridgeState = ''
  let notLoggedInTimer = null
  let lastDisconnectNotifyTs = 0
  let lastKickedTs = 0
  let napcatRestarting = false
  let qqLoginWindow = null

  function clearNotLoggedInTimer() {
    if (notLoggedInTimer) {
      clearTimeout(notLoggedInTimer)
      notLoggedInTimer = null
    }
  }

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

  function showQqBridgeNotification(title, body) {
    if (!deps.Notification?.isSupported?.()) return
    const notification = new deps.Notification({ title, body, silent: false })
    notification.on?.('click', () => deps.showMainWindow?.('#/app/qq-bridge'))
    notification.show?.()
  }

  function sendQqBridgeAlert(type, message) {
    const titleMap = {
      kicked: 'QQ 已掉线',
      disconnected: 'QQ 桥接掉线',
      not_logged_in: 'QQ 未登录',
      restart_failed: 'NapCat 重启失败',
    }
    broadcast('desktop:qq-bridge-alert', { type, message, ts: Date.now() })
    showQqBridgeNotification(titleMap[type] || 'QQ 桥接提醒', message)
  }

  function pushQqHostStatus(status) {
    broadcast('desktop:qq-host-status', { ...status, ts: Date.now() })
  }

  function pushQqHostDownload(progress) {
    broadcast('desktop:qq-host-download', { ...progress, ts: Date.now() })
  }

  async function ensureBridgeBinding({ apiOrigin, authToken, characterId }) {
    const unwrap = (res, path) => {
      if (!res || res.status < 200 || res.status >= 300) throw new Error(`api:${path} HTTP ${res?.status}`)
      const body = JSON.parse(res.data || '{}')
      if (typeof body.code === 'number') {
        if (body.code !== 200) throw new Error(`api:${path} code ${body.code}`)
        return body.data
      }
      return body
    }
    const apiGet = async (path) => unwrap(await deps.performApiRequest({ method: 'GET', url: `${apiOrigin}${path}`, timeoutMs: 15000, apiOrigin, authToken }), path)
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
      deps.log?.(`[ensureBridgeBinding] list conversations failed: ${e?.message || e}`)
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
      deps.log?.(`[ensureBridgeBinding] auto-create conversation failed: ${e?.message || e}`)
    }
    return null
  }

  function makeNapCatBridgeStarter() {
    return async ({ wsUrl, accessToken }) => {
      try {
        const authToken = await deps.resolveDesktopAuthToken()
        if (!authToken) return
        let settings = deps.readQqBridgeSettings()
        if (!settings.binding?.conversationId && !settings.binding?.characterId) {
          const result = await ensureBridgeBinding({ apiOrigin: deps.resolveApiOrigin(), authToken, characterId: settings.binding?.characterId })
          if (!result?.conversationId) return
          const prevBinding = settings.binding || {}
          const hasAllowEntries = (prevBinding.allowUsers || []).length > 0 || (prevBinding.allowGroups || []).length > 0
          deps.writeQqBridgeSettings({
            binding: {
              ...prevBinding,
              conversationId: result.conversationId,
              ...(hasAllowEntries ? {} : { allowMode: 'open' }),
            },
          })
          settings = deps.readQqBridgeSettings()
        }
        deps.startQqBridge({
          apiOrigin: deps.resolveApiOrigin(),
          authToken,
          settings: {
            ...settings,
            enabled: true,
            napcat: { ...settings.napcat, wsUrl, accessToken },
          },
          onStatus: (status) => pushQqBridgeStatus(status),
        })
      } catch (e) {
        deps.log?.('[napcatHost] bridge starter failed:', e?.message || e)
      }
    }
  }

  async function restartNapCatForReconnect() {
    if (napcatRestarting) return
    napcatRestarting = true
    try {
      const settings = deps.readQqBridgeSettings()
      if (settings.hosting?.mode !== 'auto') {
        sendQqBridgeAlert('restart_failed', 'QQ 已掉线，手动模式下需自行重启 NapCat 并扫码登录')
        return
      }
      await deps.stopNapCatHost()
      await new Promise((resolve) => setTimeout(resolve, 2000))
      const ok = await deps.startNapCatHost({
        settings,
        onStatus: (status) => pushQqHostStatus(status),
        onDownload: (progress) => pushQqHostDownload(progress),
        bridgeStarter: makeNapCatBridgeStarter(),
      })
      if (!ok) {
        sendQqBridgeAlert('restart_failed', 'NapCat 重启失败，请打开 QQ 桥接页面扫码登录')
      }
    } catch {
      sendQqBridgeAlert('restart_failed', 'NapCat 重启失败，请打开 QQ 桥接页面扫码登录')
    } finally {
      napcatRestarting = false
    }
  }

  function pushQqBridgeStatus(status) {
    broadcast('desktop:qq-bridge-status', { ...status, ts: Date.now() })
    const state = status?.state || ''
    if (state === 'ready') {
      clearNotLoggedInTimer()
    } else if (status?.kicked && state === 'connected') {
      lastKickedTs = Date.now()
      clearNotLoggedInTimer()
      sendQqBridgeAlert('kicked', 'QQ 已掉线（被踢下线/另一终端登录），正在重启 NapCat 尝试重新登录…')
      void restartNapCatForReconnect()
    } else if (state === 'connected' && prevBridgeState !== 'connected') {
      clearNotLoggedInTimer()
      notLoggedInTimer = setTimeout(() => {
        sendQqBridgeAlert('not_logged_in', 'QQ 桥接已连接但未检测到登录态，请打开 QQ 桥接页面扫码登录')
      }, 15000)
    } else if (state === 'disconnected') {
      const now = Date.now()
      if (now - lastKickedTs > 60000 && now - lastDisconnectNotifyTs > 30000 && (prevBridgeState === 'ready' || prevBridgeState === 'connected')) {
        lastDisconnectNotifyTs = now
        sendQqBridgeAlert('disconnected', 'QQ 连接已断开，正在尝试重连…')
      }
      clearNotLoggedInTimer()
    } else if (state === 'stopped') {
      clearNotLoggedInTimer()
    }
    prevBridgeState = state
  }

  async function autoStartQqBridgeIfNeeded() {
    try {
      const settings = deps.readQqBridgeSettings()
      if (settings.hosting?.mode === 'auto') return
      if (!settings.enabled || !settings.binding?.conversationId) return
      const authToken = await deps.resolveDesktopAuthToken()
      if (!authToken) return
      deps.startQqBridge({
        apiOrigin: deps.resolveApiOrigin(),
        authToken,
        settings,
        onStatus: (status) => pushQqBridgeStatus(status),
      })
    } catch {
      /* ignore */
    }
  }

  async function autoStartNapCatHostIfNeeded() {
    try {
      const settings = deps.readQqBridgeSettings()
      if (settings.hosting?.mode !== 'auto' || !settings.hosting?.consented) return
      await deps.startNapCatHost({
        settings,
        onStatus: (status) => pushQqHostStatus(status),
        onDownload: (progress) => pushQqHostDownload(progress),
        bridgeStarter: makeNapCatBridgeStarter(),
      })
    } catch {
      /* ignore */
    }
  }

  function isLocalNapCatUrl(url, port) {
    try {
      const u = new URL(url)
      return (u.hostname === '127.0.0.1' || u.hostname === 'localhost') && u.port === String(port)
    } catch {
      return false
    }
  }

  function openQqLoginWindow() {
    const settings = deps.readQqBridgeSettings()
    if (!settings.hosting?.consented) return { ok: false, reason: 'not_consented' }
    const status = deps.getNapCatHostStatus()
    const webui = status?.webui
    const port = webui?.port || settings.hosting?.webuiPort || 6099
    const token = webui?.token || settings.hosting?.webuiToken || ''
    if (!token) return { ok: false, reason: 'not_running' }
    const url = webui?.url || `http://127.0.0.1:${port}/webui?token=${token}`

    if (qqLoginWindow && !qqLoginWindow.isDestroyed()) {
      qqLoginWindow.show()
      qqLoginWindow.focus()
      try {
        qqLoginWindow.loadURL(url)
      } catch {
        /* ignore */
      }
      return { ok: true, reused: true }
    }

    const win = new deps.BrowserWindow({
      width: 520,
      height: 720,
      minWidth: 360,
      minHeight: 480,
      title: 'QQ 登录 · NapCat',
      icon: deps.resolveDistPath('icon.ico'),
      backgroundColor: '#ffffff',
      autoHideMenuBar: true,
      show: false,
      webPreferences: {
        contextIsolation: true,
        sandbox: true,
        nodeIntegration: false,
        partition: 'persist:napcat-webui',
      },
    })
    qqLoginWindow = win
    win.setMenuBarVisibility(false)
    win.once('ready-to-show', () => {
      if (!win.isDestroyed()) win.show()
    })
    win.webContents.on('will-navigate', (event, navUrl) => {
      if (!isLocalNapCatUrl(navUrl, port)) event.preventDefault()
    })
    win.webContents.setWindowOpenHandler(({ url: openUrl }) => {
      if (isLocalNapCatUrl(openUrl, port)) return { action: 'allow' }
      if (deps.isAllowedExternalUrl(openUrl)) deps.shell.openExternal(openUrl)
      return { action: 'deny' }
    })
    win.on('closed', () => {
      qqLoginWindow = null
    })
    void win.loadURL(url)
    return { ok: true }
  }

  function getQqBridgeLogs() {
    try {
      const content = deps.logger.getLogContent(50000)
      const all = content ? content.split(/\r?\n/).filter(Boolean) : []
      const bridgeRe = /\[(qqBridge|napcatHost|resolve-conversation|ensureBridgeBinding)\]/
      const lines = all.filter((line) => bridgeRe.test(line))
      return { ok: true, lines: lines.slice(-500) }
    } catch (e) {
      return { ok: false, reason: 'read_failed', error: e?.message || String(e) }
    }
  }

  function dispose() {
    clearNotLoggedInTimer()
    if (qqLoginWindow && !qqLoginWindow.isDestroyed()) {
      try {
        qqLoginWindow.destroy()
      } catch {
        /* ignore */
      }
    }
    qqLoginWindow = null
  }

  return {
    pushQqBridgeStatus,
    pushQqHostStatus,
    pushQqHostDownload,
    ensureBridgeBinding,
    makeNapCatBridgeStarter,
    autoStartQqBridgeIfNeeded,
    autoStartNapCatHostIfNeeded,
    openQqLoginWindow,
    getQqBridgeLogs,
    dispose,
  }
}
