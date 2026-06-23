import { contextBridge, ipcRenderer } from 'electron'

contextBridge.exposeInMainWorld('electronAPI', {
  isElectron: true,
  getWindowKind: () => ipcRenderer.invoke('desktop:get-window-kind'),
  openMainWindow: (hash) => ipcRenderer.invoke('desktop:open-main', hash || ''),
  openQuickChat: (conversationId) => ipcRenderer.invoke('desktop:open-quick-chat', conversationId),
  toggleCharacterPicker: () => ipcRenderer.invoke('desktop:toggle-picker'),
  closePicker: () => ipcRenderer.invoke('desktop:close-picker'),
  closeQuickChat: () => ipcRenderer.invoke('desktop:close-quick-chat'),
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
})
