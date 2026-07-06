# 应用自动更新（App Auto-Updater）设计

- 日期：2026-07-06
- 状态：已确认，待实现
- 范围：`frontend/`（Electron 桌面客户端）
- 关联：CLAUDE.md「部署与发布」、DESIGN.md 设计系统

## 1. 背景与目标

当前 LianYu 桌面客户端（Electron + electron-builder，Windows nsis 安装包）每次发版都要把 `LianYu Setup vX.X.X.exe` 手动发给用户安装。用户没有"在软件里点一下就更新"的能力，导致旧版本长期留存、修复难以触达。

目标：在「关于」页版本号旁加一个"检查更新"入口，发现新版本后**一键下载 + 静默安装 + 自动重启**，无需用户手动下载安装包。版本源用 GitHub Releases（仓库 `whitequeen306/LianYuPC`）。

非目标（YAGNI）：
- 不做启动自动检查 / 定时轮询（纯手动触发）
- 不做 stable/beta 渠道切换
- 不做强制更新（critical 标签）
- 不展示 release notes（只提示版本号）
- 不做差量/续传增强（用 electron-updater 默认能力）

## 2. 方案选型

采用 `electron-updater`（electron-builder 官方配套）+ GitHub Releases provider。理由：
1. 与现有 electron-builder 打包链天然兼容，只需加 `publish` 配置
2. `autoUpdater` 封装了检查 / 下载 / 校验 / 安装全流程，自带 sha512 完整性校验
3. Windows + nsis + 未签名包可正常工作（走静默升级，不触发 SmartScreen）
4. 与现有 `enableEmbeddedAsarIntegrityValidation: true` fuse 兼容（nsis 全量替换 exe+asar，新包自带新哈希）

## 3. 架构与模块边界

### 3.1 新增文件

| 路径 | 职责 |
|---|---|
| `frontend/electron/updater/updater.js` | 主进程封装 `autoUpdater`：`initUpdater()`、`checkForUpdates()`、`downloadUpdate()`、`installNow()`、事件转发到渲染进程 |
| `frontend/electron/updater/__tests__/updater.test.js` | 单测：mock `autoUpdater`，验证状态机、IPC、错误传播 |
| `frontend/src/composables/useAppUpdater.js` | renderer 状态机 composable：订阅 `onUpdateState`，暴露 state + actions |
| `frontend/src/components/AppUpdateButton.vue` | AboutPage 按钮 + 状态文本 + 进度条 + 失败兜底链接 |
| `frontend/src/composables/__tests__/useAppUpdater.test.js` | composable 单测 |

### 3.2 修改文件

| 路径 | 改动 |
|---|---|
| `frontend/package.json` | 加 `electron-updater` 依赖；`build.publish` 加 `{ provider: 'github', owner: 'whitequeen306', repo: 'LianYuPC' }` |
| `frontend/electron/main.js` | `app.whenReady` 后调 `initUpdater()`；updater 模块内部注册 `updater:check/download/install` 的 `ipcMain.handle`，并经 `mainWindow.webContents.send('updater:state', payload)` 推送状态 |
| `frontend/electron/preload.js` | 暴露 `checkForUpdates()` / `downloadUpdate()` / `installNow()` / `onUpdateState(cb)`（返回 unsubscribe） |
| `frontend/src/pages/AboutPage.vue` | 版本号行尾插入 `<AppUpdateButton />`；`v-if="isElectron"` 守卫 |
| `frontend/scripts/electron-pack.mjs` | esbuild `external` 数组加 `'electron-updater'`（否则 esbuild bundle 原生模块失败）；`electron-builder` 命令按 `process.env.GH_TOKEN` 是否存在切 `--publish always` / `--publish never` |
| `frontend/scripts/electron-release.mjs` | 启动前校验 `GH_TOKEN`，缺失则报错并给出配置指引 |

### 3.3 边界与隔离

- `updater.js` 是唯一与 `autoUpdater` 直接交互的地方；renderer 与主进程之间只走 IPC，不直接 require electron-updater
- composable 只负责状态机与 IPC 调用，不掺 UI；`AppUpdateButton.vue` 只负责渲染，不做业务判断
- 发布脚本只管"打包 + 上传"，不掺运行时逻辑

## 4. 数据流与状态机

### 4.1 renderer composable 状态

```
idle ──check──▶ checking ──┬── no-update（toast 3s 后回 idle）
                           ├── update-available（弹对话框）
                           └── error

update-available ──download──▶ downloading ──┬── verifying ── ready ──install──▶ installing（应用退出）
                                             └── error

任意非终态 ──error──▶ error（含"重试" + "前往 GitHub 手动下载"）
```

### 4.2 状态 payload 结构

主进程推送到 renderer 的 `updater:state` 事件 payload：

```ts
type UpdaterStatePayload = {
  state: 'idle' | 'checking' | 'no-update' | 'update-available'
       | 'downloading' | 'verifying' | 'ready' | 'installing' | 'error'
  info?: {
    version?: string          // 新版本号，update-available 起
    percent?: number          // 0-100，downloading 期间
    transferred?: number      // bytes
    total?: number            // bytes
    bytesPerSecond?: number
    errorMessage?: string     // error 状态
  }
}
```

### 4.3 事件映射（autoUpdater → 自定义状态）

| autoUpdater 事件 | 推送状态 |
|---|---|
| `checking-for-update` | `checking` |
| `update-available` | `update-available`（带 version） |
| `update-not-available` | `no-update` |
| `download-progress` | `downloading`（带 percent/transferred/total/bps） |
| `update-downloaded` | `ready` |
| `error` | `error`（带 errorMessage） |

### 4.4 IPC 通道

| 通道 | 方向 | 用途 |
|---|---|---|
| `updater:check` | renderer→main（invoke） | 触发 `autoUpdater.checkForUpdates()` |
| `updater:download` | renderer→main（invoke） | 触发 `autoUpdater.downloadUpdate()` |
| `updater:install` | renderer→main（invoke） | 触发 `autoUpdater.quitAndInstall()`（应用即将退出） |
| `updater:state` | main→renderer（send） | 推送状态 payload |

## 5. UI 设计（AboutPage）

### 5.1 布局

`AboutPage.vue`「应用信息」卡片的版本号行，从：
```
版本    v0.2.255
```
改为：
```
版本    v0.2.255  [检查更新]
```

`<AppUpdateButton />` 通过 `v-if="isElectron"` 守卫，Web 环境整块不渲染。

### 5.2 按钮状态文本

| 状态 | 文本 | 样式 |
|---|---|---|
| idle | 检查更新 | 次 button |
| checking | 检查中… | loading，禁用 |
| no-update | 已是最新 | toast 3s 后回 idle |
| update-available | 弹对话框（不在按钮上反映） | — |
| downloading | 下载中 45% | 主 button + 进度条 |
| verifying | 校验中… | loading |
| ready | 安装并重启 | 主 button 高亮 + pink glow |
| error | 更新失败·重试 | error 色 + 兜底链接 |

### 5.3 更新对话框（update-available）

```
┌──────────────────────────────────┐
│  发现新版本 v0.2.260             │
│                                  │
│  建议立即更新以获得最新修复。     │
│  更新将下载安装包并自动重启应用。 │
│                                  │
│         [下次再说]  [立即更新]    │
└──────────────────────────────────┘
```

### 5.4 失败兜底

error 状态下，按钮下方显示一行小字链接："前往 GitHub 手动下载"，`shell.openExternal('https://github.com/whitequeen306/LianYuPC/releases/latest')`。

### 5.5 设计系统遵循

- 按钮 `border-radius: 25px`（pill），主按钮 `box-shadow: pink glow`
- 卡片 `backdrop-filter: blur()`（glass），不用扁平阴影
- 过渡 `cubic-bezier(0.23, 1, 0.32, 1)`，时长 0.2~0.28s
- 颜色全部走 CSS 变量（`--ly-accent` 等），不硬编码 hex
- 暗色/亮色双套变量都适配

## 6. 发布流程

### 6.1 一次性配置

1. GitHub PAT（Personal Access Token），权限选 `repo`（classic token）或对 `LianYuPC` 仓库的 `Contents: read/write`（fine-grained）
2. 本地设环境变量 `GH_TOKEN=<token>`（不入仓，不入 `.env`）

### 6.2 发版步骤

1. 改代码 → `git commit` → `git push origin main`
2. `cd frontend` → `npm run electron:release`（或 `:minor` / `:major`）
3. 脚本自动：
   - `npm version <bump> --no-git-tag-version`（改 package.json version）
   - `node scripts/electron-pack.mjs`（vite build + esbuild bundle + electron-builder）
   - 检测到 `GH_TOKEN` → `electron-builder --win --publish always`
   - electron-builder 产出 `LianYu Setup vX.X.X.exe` + `latest.yml`，上传到 `whitequeen306/LianYuPC` 的 Releases（tag=`vX.X.X`）
4. 用户端旧版本点"检查更新" → 拉 `latest.yml` → 比对版本号 → 下载 → 安装

### 6.3 本地测试构建（不上传）

不设 `GH_TOKEN` 时，`electron-pack.mjs` 自动用 `--publish never`，只产出本地安装包到 `release/vX.X.X/`，不触碰 Releases。

## 7. 错误处理

| 场景 | 处理 |
|---|---|
| 网络/超时/无法连接 GitHub | `error` 状态，文案"网络问题或 GitHub 不可达，可前往 Releases 手动下载" + 兜底链接 |
| 下载校验失败（sha512 不匹配） | electron-updater 自动 `error` 事件，走 error 状态 + 兜底 |
| 下载中断 | electron-updater 内置 retry；仍失败走 error 状态 |
| dev 模式（无 app.asar / 无 latest.yml） | 按钮禁用，tooltip"开发模式不可用"。主进程用 `!app.isPackaged` 判断，dev 下 `checkForUpdates` 直接返回 `{ state: 'error', errorMessage: 'dev-mode' }`，不调用 autoUpdater |
| 非 Electron 环境 | 按钮不渲染 |
| `GH_TOKEN` 缺失（发版时） | `electron-release.mjs` 报错退出，指引配置 |
| 用户点"下次再说" | 关闭对话框，回 idle，不强制 |

## 8. 测试策略

### 8.1 单元测试（Vitest）

**`updater.test.js`**（主进程）：
- mock `autoUpdater` 模块，验证 `initUpdater()` 后各事件触发时，`mainWindow.webContents.send` 收到正确状态 payload
- 验证 `updater:check/download/install` 三个 IPC handler 正确调用 autoUpdater 对应方法
- 验证 `error` 事件的 errorMessage 透传
- 验证 dev 模式下 `checkForUpdates` 返回 `{ state: 'error', reason: 'dev-mode' }` 而非调用 autoUpdater

**`useAppUpdater.test.js`**（renderer）：
- mock `getElectronAPI().onUpdateState`，验证状态机转换
- 验证各 action 正确调用对应 IPC invoke
- 验证 unsubscribe 正确清理 listener

### 8.2 手动验收

1. 发一个 patch 版本（bump version → push → release）到 GitHub Releases
2. 旧版本打开「关于」页，点"检查更新"，验证：发现新版本 → 弹对话框 → 立即更新 → 进度条 → 安装并重启 → 重启后版本号已更新
3. 断网测试：点"检查更新" → 验证 error 状态 + 兜底链接
4. dev 模式：`npm run electron:dev` → 验证按钮禁用 + tooltip

## 9. 安全与签名

- 当前 `signAndEditExecutable: false`，未签名。electron-updater 在 Windows 上对未签名包可正常工作（nsis 静默升级，不弹 SmartScreen）
- `enableEmbeddedAsarIntegrityValidation: true` fuse 兼容：nsis 全量替换 exe + asar，新包自带新哈希，无需改 fuse
- `latest.yml` 走 HTTPS，内含 sha512 完整性校验，防中间人篡改
- 首次手动安装未签名包会有 SmartScreen 警告（Windows 对未签名 exe 的通用行为，与 updater 无关）
- `GH_TOKEN` 仅用于发版时上传产物，不打包进客户端，不入仓

## 10. 不在本次范围

- 启动自检 / 定时轮询（可后续按需加，架构已留 `initUpdater()` 钩子）
- stable/beta 渠道
- 强制更新（critical 标签）
- release notes 展示
- 代码签名（需购买证书，单独议题）
- macOS / Linux 支持（当前仅 Windows nsis）

## 11. 验收标准

- [ ] AboutPage 版本号旁有"检查更新"按钮，Web 环境不渲染
- [ ] 点按钮能检查 GitHub Releases 最新版本
- [ ] 有新版 → 弹对话框 → 立即更新 → 下载进度条 → 安装并重启 → 重启后版本号更新
- [ ] 已是最新 → toast 提示
- [ ] 断网/失败 → error 状态 + 兜底链接
- [ ] dev 模式按钮禁用
- [ ] `npm run electron:release` 配 `GH_TOKEN` 能自动上传 Releases
- [ ] 不配 `GH_TOKEN` 时只产本地包，不误传
- [ ] 单测全绿
- [ ] UI 遵守 DESIGN.md（pill 按钮、glass 卡片、cubic-bezier 缓动、双套主题变量）
