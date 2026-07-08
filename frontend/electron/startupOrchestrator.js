export function schedulePostWindowStartup({
  mainWindow,
  patchDesktopRequestOrigin,
  applyLaunchAtLogin,
  initUpdater,
  ensureTray,
  scheduleAuxWindowPrewarm,
}) {
  if (!mainWindow || mainWindow.isDestroyed?.()) return

  const run = () => {
    setTimeout(() => {
      if (!mainWindow || mainWindow.isDestroyed?.()) return
      patchDesktopRequestOrigin?.()
      applyLaunchAtLogin?.()
      initUpdater?.(mainWindow)
      ensureTray?.()
      scheduleAuxWindowPrewarm?.()
    }, 0)
  }

  if (mainWindow.webContents?.isLoading?.()) {
    mainWindow.webContents.once('did-finish-load', run)
  } else {
    run()
  }
}
