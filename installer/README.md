# LianYu 独立离线安装器

安装器使用 WPF 实现，界面资源来自现有恋语设计 tokens 和 `frontend/public/landing/mahiru.jpg`。

## 构建

先生成 Electron 的 `win-unpacked`：

```powershell
cd frontend
npm run electron:build
```

再构建单文件离线安装器：

```powershell
cd installer
.\build-offline-installer.ps1 -Version 0.2.363
```

最终文件位于 `frontend/release/v0.2.363/LianYu-Setup-0.2.363.exe`。

安装器支持当前用户/所有用户、安装目录、快捷方式、开机启动和安装完成后启动。payload 完整内嵌，安装过程不需要网络。
