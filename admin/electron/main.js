import { app, BrowserWindow, ipcMain } from 'electron'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
const root = path.dirname(fileURLToPath(import.meta.url))
let win
function createWindow() {
  win = new BrowserWindow({ width: 1380, height: 860, minWidth: 1080, minHeight: 680, frame: false, backgroundColor: '#0e1218', title: 'LianYu Admin', webPreferences: { preload: path.join(root, 'preload.cjs'), contextIsolation: true, nodeIntegration: false } })
  if (process.env.VITE_DEV_SERVER_URL) win.loadURL(process.env.VITE_DEV_SERVER_URL)
  else win.loadFile(path.join(root, '../dist/index.html'))
}
app.whenReady().then(() => { ipcMain.on('window:minimize', () => win?.minimize()); ipcMain.on('window:toggle-maximize', () => win?.isMaximized() ? win.unmaximize() : win.maximize()); ipcMain.on('window:close', () => win?.close()); createWindow() })
app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit() })
