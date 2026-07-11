import { contextBridge, ipcRenderer } from 'electron'

contextBridge.exposeInMainWorld('electronAPI', {
  isElectron: true,
  getWindowKind: () => ipcRenderer.invoke('desktop:get-window-kind'),
  openMainWindow: (hash) => ipcRenderer.invoke('desktop:open-main', hash || ''),
  openQuickChat: (conversationId) => {
    ipcRenderer.send('desktop:open-quick-chat', conversationId)
  },
  toggleCharacterPicker: () => ipcRenderer.invoke('desktop:toggle-picker'),
  touchPickerInteraction: () => ipcRenderer.invoke('desktop:picker-interaction'),
  closePicker: () => ipcRenderer.invoke('desktop:close-picker'),
  closeQuickChat: () => {
    ipcRenderer.send('desktop:close-quick-chat')
    return ipcRenderer.invoke('desktop:close-quick-chat')
  },
  hideLauncher: () => ipcRenderer.invoke('desktop:hide-launcher'),
  showLauncher: () => ipcRenderer.invoke('desktop:show-launcher'),
  quitApp: () => ipcRenderer.invoke('desktop:quit'),
  getDesktopSettings: () => ipcRenderer.invoke('desktop:get-settings'),
  setDesktopSettings: (partial) => ipcRenderer.invoke('desktop:set-settings', partial),
  ackCloseHint: () => ipcRenderer.invoke('desktop:ack-close-hint'),
  onCloseHint: (callback) => {
    const handler = () => callback()
    ipcRenderer.on('desktop:close-hint', handler)
    return () => ipcRenderer.removeListener('desktop:close-hint', handler)
  },
  saveLauncherPosition: (x, y) => ipcRenderer.invoke('desktop:save-launcher-position', { x, y }),
  moveLauncherByDelta: (dx, dy) => ipcRenderer.invoke('desktop:move-launcher-by-delta', { dx, dy }),
  beginLauncherDrag: () => ipcRenderer.send('desktop:launcher-drag-start'),
  moveLauncherDrag: (dx, dy) => ipcRenderer.send('desktop:launcher-drag-move', { dx, dy }),
  endLauncherDrag: () => ipcRenderer.send('desktop:launcher-drag-end'),
  setLauncherScreenPosition: (x, y) => ipcRenderer.invoke('desktop:set-launcher-screen-position', { x, y }),
  setLauncherDragging: (dragging) => ipcRenderer.invoke('desktop:set-launcher-dragging', dragging),
  clampLauncherPosition: () => ipcRenderer.invoke('desktop:clamp-launcher-position'),
  setLauncherMousePassthrough: (ignore) => ipcRenderer.invoke('desktop:set-launcher-mouse-passthrough', ignore),
  notifyProactiveMessage: (payload) => ipcRenderer.invoke('desktop:notify-proactive-message', payload),
  getCaptionBarHeight: () => ipcRenderer.invoke('desktop:get-caption-height'),
  getCaptionMetrics: () => ipcRenderer.invoke('desktop:get-caption-metrics'),
  setTitleBarAppearance: (payload) => ipcRenderer.invoke('desktop:set-title-bar-appearance', payload),
  saveAppearance: (mode) => ipcRenderer.invoke('desktop:save-appearance', mode),
  requestChromeSync: () => ipcRenderer.send('desktop:sync-chrome'),
  onCaptionMetrics: (callback) => {
    const handler = (_event, metrics) => callback(metrics)
    ipcRenderer.on('desktop:caption-metrics', handler)
    return () => ipcRenderer.removeListener('desktop:caption-metrics', handler)
  },
  onLauncherNewMessage: (callback) => {
    const handler = (_event, payload) => callback(payload)
    ipcRenderer.on('desktop:launcher-new-message', handler)
    return () => ipcRenderer.removeListener('desktop:launcher-new-message', handler)
  },
  onLauncherPetChanged: (callback) => {
    const handler = (_event, petId) => callback(petId)
    ipcRenderer.on('desktop:launcher-pet-changed', handler)
    return () => ipcRenderer.removeListener('desktop:launcher-pet-changed', handler)
  },
  onLauncherInteractionReset: (callback) => {
    const handler = () => callback()
    ipcRenderer.on('desktop:launcher-interaction-reset', handler)
    return () => ipcRenderer.removeListener('desktop:launcher-interaction-reset', handler)
  },
  setLoginState: (loggedIn) => ipcRenderer.invoke('desktop:set-login-state', loggedIn),
  getAuthSession: () => ipcRenderer.invoke('auth:get-session'),
  setAuthSession: (session) => ipcRenderer.invoke('auth:set-session', session),
  clearAuthSession: () => ipcRenderer.invoke('auth:clear-session'),
  updateAuthToken: (token) => ipcRenderer.invoke('auth:update-token', token),
  bootstrapAuthToken: () => ipcRenderer.invoke('auth:bootstrap-token'),
  getRuntimeConfig: () => ipcRenderer.invoke('runtime:get-config'),
  apiRequest: (payload) => ipcRenderer.invoke('api:request', payload),
  isLauncherVisible: () => ipcRenderer.invoke('desktop:is-launcher-visible'),
  startDesktopObserver: (config) => ipcRenderer.invoke('desktop:start-observer', config),
  stopDesktopObserver: () => ipcRenderer.invoke('desktop:stop-observer'),
  onLauncherGreeting: (callback) => {
    const handler = (_event, payload) => callback(payload)
    ipcRenderer.on('desktop:launcher-greeting', handler)
    return () => ipcRenderer.removeListener('desktop:launcher-greeting', handler)
  },
  onRestartObserver: (callback) => {
    const handler = () => callback()
    ipcRenderer.on('desktop:restart-observer', handler)
    return () => ipcRenderer.removeListener('desktop:restart-observer', handler)
  },
  // #10：捕获期间主进程通知桌宠显示不可隐藏的捕获指示
  onObserveCapturing: (callback) => {
    const handler = (_event, capturing) => callback(capturing)
    ipcRenderer.on('desktop:observe-capturing', handler)
    return () => ipcRenderer.removeListener('desktop:observe-capturing', handler)
  },
  onLauncherShown: (callback) => {
    const handler = () => callback()
    ipcRenderer.on('desktop:launcher-shown', handler)
    return () => ipcRenderer.removeListener('desktop:launcher-shown', handler)
  },
  onPickerToggle: (callback) => {
    const handler = (_event, payload) => callback(payload)
    ipcRenderer.on('desktop:picker-toggle', handler)
    return () => ipcRenderer.removeListener('desktop:picker-toggle', handler)
  },
  onPickerShown: (callback) => {
    const handler = () => callback()
    ipcRenderer.on('desktop:picker-shown', handler)
    return () => ipcRenderer.removeListener('desktop:picker-shown', handler)
  },
  onLauncherHidden: (callback) => {
    const handler = () => callback()
    ipcRenderer.on('desktop:launcher-hidden', handler)
    return () => ipcRenderer.removeListener('desktop:launcher-hidden', handler)
  },
  onAuthSessionUpdated: (callback) => {
    const handler = (_event, session) => callback(session)
    ipcRenderer.on('desktop:auth-session-updated', handler)
    return () => ipcRenderer.removeListener('desktop:auth-session-updated', handler)
  },
  notifyQuickChatReady: () => ipcRenderer.send('desktop:quick-chat-ready'),
  getQqBridgeSettings: () => ipcRenderer.invoke('desktop:get-qq-bridge-settings'),
  setQqBridgeSettings: (partial) => ipcRenderer.invoke('desktop:set-qq-bridge-settings', partial),
  startQqBridge: (override) => ipcRenderer.invoke('desktop:start-qq-bridge', override),
  stopQqBridge: () => ipcRenderer.invoke('desktop:stop-qq-bridge'),
  getQqBridgeStatus: () => ipcRenderer.invoke('desktop:get-qq-bridge-status'),
  resolveQqBridgeConversation: (characterId) => ipcRenderer.invoke('desktop:qq-bridge-resolve-conversation', characterId),
  getQqBridgeLogs: () => ipcRenderer.invoke('desktop:qq-bridge-get-logs'),
  onQqBridgeStatus: (callback) => {
    const handler = (_event, payload) => callback(payload)
    ipcRenderer.on('desktop:qq-bridge-status', handler)
    return () => ipcRenderer.removeListener('desktop:qq-bridge-status', handler)
  },
  onQqBridgeAlert: (callback) => {
    const handler = (_event, payload) => callback(payload)
    ipcRenderer.on('desktop:qq-bridge-alert', handler)
    return () => ipcRenderer.removeListener('desktop:qq-bridge-alert', handler)
  },
  startQqHost: (override) => ipcRenderer.invoke('desktop:start-qq-host', override),
  stopQqHost: () => ipcRenderer.invoke('desktop:stop-qq-host'),
  reinstallQqHost: () => ipcRenderer.invoke('desktop:reinstall-qq-host'),
  getQqHostStatus: () => ipcRenderer.invoke('desktop:get-qq-host-status'),
  onQqHostStatus: (callback) => {
    const handler = (_event, payload) => callback(payload)
    ipcRenderer.on('desktop:qq-host-status', handler)
    return () => ipcRenderer.removeListener('desktop:qq-host-status', handler)
  },
  onQqHostDownload: (callback) => {
    const handler = (_event, payload) => callback(payload)
    ipcRenderer.on('desktop:qq-host-download', handler)
    return () => ipcRenderer.removeListener('desktop:qq-host-download', handler)
  },
  openQqLoginWindow: () => ipcRenderer.invoke('desktop:open-qq-login-window'),
  openImageViewer: (payload) => ipcRenderer.invoke('desktop:open-image-viewer', payload),
  rendererLog: (level, tag, msg) => ipcRenderer.send('desktop:renderer-log', { level, tag, msg }),
  exportLogs: () => ipcRenderer.invoke('desktop:export-logs'),
  getGlobalLogs: (maxLines) => ipcRenderer.invoke('desktop:get-global-logs', maxLines),
  openLogFolder: () => ipcRenderer.invoke('desktop:open-log-folder'),
  // ── 应用自动更新（updater.js 主进程封装） ──
  checkForUpdates: () => ipcRenderer.invoke('updater:check'),
  downloadUpdate: () => ipcRenderer.invoke('updater:download'),
  installNow: () => ipcRenderer.invoke('updater:install'),
  openInstallerFolder: () => ipcRenderer.invoke('updater:openInstallerFolder'),
  onUpdateState: (callback) => {
    const handler = (_event, payload) => callback(payload)
    ipcRenderer.on('updater:state', handler)
    return () => ipcRenderer.removeListener('updater:state', handler)
  },
})
