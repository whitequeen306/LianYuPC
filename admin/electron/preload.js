import { contextBridge, ipcRenderer } from 'electron'
contextBridge.exposeInMainWorld('adminWindow', { minimize: () => ipcRenderer.send('window:minimize'), toggleMaximize: () => ipcRenderer.send('window:toggle-maximize'), close: () => ipcRenderer.send('window:close') })
