# 客户端发版手册（Windows 本地出包）

> 2026-10-21 整理。上一次真实出包是 **0.2.367（2026-10-07）**，之后 13 天没发过包，
> 期间踩了三个纯环境坑，全部记在这里，下次直接照着走。

## 一、发版链路（谁管什么）

```
frontend/package.json  version  ← 唯一版本源
        │
        ├─ node scripts/electron-pack.mjs          → NSIS 基础包（约 166MB）
        │      vite build → esbuild 主进程 → packRuntimeSecrets
        │      → **启动冒烟** → electron-builder
        │
        ├─ node scripts/prepare-branded-release.mjs → 品牌离线安装包（约 282MB）+ latest.yml
        │      installer/build-offline-installer.ps1（需要 .NET 10 SDK）
        │      → buildBlockMap 算 size + sha512 写进 latest.yml
        │
        └─ python scripts/_upload_update_assets.py  → 传服务器 MinIO updates/
               ★ 这一步才会换掉 latest.yml，用户立刻能看到新版本
```

更新检查读的是 **我们自己服务器** 的 `/api/public/files/updates/latest.yml`，
不是 GitHub。GitHub Releases 只是留档/镜像。

## 二、三个环境坑

### 1. `ELECTRON_RUN_AS_NODE=1` 会让打包冒烟必然失败

现象：`smoke-launcher: FAILED`，栈里是
`TypeError: Cannot read properties of undefined (reading 'setAppUserModelId')`。

原因：该环境变量让 `electron.exe` 以纯 Node 身份启动，`require('electron')` 拿不到 `app`。

处理：跑打包前 `Remove-Item Env:ELECTRON_RUN_AS_NODE`。
（注意这是环境级变量，可能被别的工具设进当前会话。）

### 2. Electron 二进制常常没装上

现象：`smoke-launcher: electron binary not found — run npm ci`，
或 `node node_modules/electron/install.js` 报 `TypeError: fetch failed`。

原因：pnpm 会跳过 postinstall（`ERR_PNPM_IGNORED_BUILDS`），二进制没下载；
且本机直连 `github.com` 拉 release 资产不通。

处理：

```powershell
cd frontend
$env:ELECTRON_MIRROR = "https://npmmirror.com/mirrors/electron/"
node node_modules/electron/install.js      # 约 223MB
```

### 3. 品牌离线安装包需要 .NET 10 SDK

现象：`dotnet SDK not found. Install .NET 10 SDK to build the offline installer.`

处理（装到用户目录，`build-offline-installer.ps1` 会自己找到 `%USERPROFILE%\.dotnet\dotnet.exe`）：

```powershell
curl.exe -s -L -o "$env:TEMP\dotnet-install.ps1" "https://dot.net/v1/dotnet-install.ps1"
powershell -NoProfile -ExecutionPolicy Bypass -File "$env:TEMP\dotnet-install.ps1" `
  -Channel 10.0 -InstallDir "$env:USERPROFILE\.dotnet"
```

### 无交互桌面时跳过冒烟

如果打包机没有可用桌面（CI / 远程 agent），`electron.exe` 连 `--version` 都起不来
（Windows 退出码 `0x80000003` = STATUS_BREAKPOINT）。此时：

```powershell
$env:LIANYU_SKIP_LAUNCHER_SMOKE = "1"   # 显式跳过，会打印醒目警告
```

**跳过就意味着这个包没有被启动验证过**（2026-10-21 的 0.3.1 就是这样打的）。

### 4. ⚠️ `Failed to create directory [...\.net\...] Error code: 5` —— 先换目录再怀疑安装包

现象：双击安装包，SmartScreen 点「仍要运行」，然后**什么都不发生**（没有安装界面）。
控制台能抓到的只有：

```
Failure processing application bundle.
Failed to create directory [C:\Users\hp\AppData\Local\Temp\.net\YuNian-Setup-x.y.z\]
for extracting bundled files. Error code: 5      ← 0x5 = ACCESS_DENIED
```

**这是 .NET 单文件包在解包时写不进 `%TEMP%\.net`**，几乎总是「安装包被放在受限目录里启动」。

排查时我先误判成「`EnableCompressionInSingleFile` 的锅」——因为**工作区里**那个压缩版失败、
而 `%TEMP%` 里的不压缩版成功。做了对照才发现是**路径**在捣鬼，不是压缩：

| 启动位置 | 压缩版 282MB | 不压缩版 353MB |
|---|---|---|
| 仓库工作区内 `frontend\release\...` | ❌ Error code 5 | ❌ Error code 5 |
| `%TEMP%` 等普通目录 | ✅ 正常弹窗 | ✅ 正常弹窗 |

结论：
- **安装包本身没问题**，换到桌面 / 下载目录 / `%TEMP%` 再双击即可。
- 判断方法：看 `%TEMP%\.net\<包名>\` 有没有被创建出来；手工能建、但安装包建不出来 → 就是启动位置的限制。
- 不要在受限目录（尤其是带沙箱 ACL 的工作区）里验证安装包。

### 5. 点「立即安装」没反应 / 变「重试安装」—— 解包环节卡死（已修）

现象：安装界面正常，点安装后要么一直转，要么按钮变「重试安装」。

根因在 `InstallerEngine.ExtractPayloadAsync`：它把 **218MB 的内嵌资源流直接交给了 `ZipArchive`**。
单文件包里的内嵌资源在随机读上极慢，实测卡在第一个条目（`locales\af.pak` 只写了 12KB，
进程 10 分钟只有 1.1 秒 CPU，看着像死锁）。

修法：**先把资源流顺序复制成一个临时 .zip 文件，再 `ZipFile.OpenRead(temp)`**。
修完同一台机器上全新安装约 **2 秒**完成（111 个文件 / 505.4 MB）。

```csharp
// 反例（会卡死）：using var archive = new ZipArchive(payload, ZipArchiveMode.Read);
var payloadZip = Path.Combine(Path.GetTempPath(), $"lianyu-payload-{Guid.NewGuid():N}.zip");
await using (var file = File.Create(payloadZip)) await payload.CopyToAsync(file);
using var archive = ZipFile.OpenRead(payloadZip);   // 落盘后顺序读，内存恒定
```

顺带发现并修掉一个**更隐蔽的坑**：`build-offline-installer.ps1` 用 `& $dotnet publish` 调用发布，
MSBuild 失败时不会抛异常，脚本会把**上一次的旧产物**复制出去 —— 产物 sha512 一模一样、
看着一切正常，实际发的是旧包（2026-10-21 就这样白跑了一轮）。
现在脚本会在 publish 后检查 `$LASTEXITCODE` 并先删旧产物，失败即中止。

## 三、标准步骤

```powershell
cd frontend
Remove-Item Env:ELECTRON_RUN_AS_NODE -ErrorAction SilentlyContinue

# 1. 版本号（唯一来源）
npm version patch --no-git-tag-version

# 2. 基础包 + 冒烟
$env:LIANYU_SKIP_LAUNCHER_SMOKE = "1"   # 仅在没有桌面时
node scripts/electron-pack.mjs

# 3. 品牌离线安装包 + latest.yml
node scripts/prepare-branded-release.mjs

# 4. 核对（不要跳过）
Get-Content release\v<版本>\latest.yml
#   ↑ 独立算一遍文件的 sha512(base64)，必须和 yml 里的一致

# 5. 发布（不可逆：latest.yml 一换，所有用户下次进软件就会看到更新提示）
$env:GH_TOKEN = <token>          # 仓库根 .env 里的 LIANYU_GITHUB_TOKEN 就是
gh release create v<版本> --draft --title "v<版本>" --notes "..."
gh release upload v<版本> release\v<版本>\YuNian-Setup-<版本>.exe `
                            release\v<版本>\YuNian-Setup-<版本>.exe.blockmap `
                            release\v<版本>\latest.yml --clobber
gh release edit v<版本> --draft=false
python ../scripts/_upload_update_assets.py --version <版本>   # ★ 真正的开关
```

`npm run electron:release` 把 1→5 串起来了，但它会自己 `npm version patch`
（再 bump 一次），手动出包时不要用它。

## 四、发布前必须确认的事

1. **sha512 自校验**：`latest.yml` 里的 sha512 必须等于文件的真实 sha512(base64)。
   更新器校验不一致会删包报错，用户永远装不上。
2. **包内确实有新代码**：解 `resources/app.asar` 检查关键字符串（本次检查了
   `稍后再装` / `probeForUpdates` / `updater:probe` / 以及 `push-catchup` 已消失）。
3. **能启动**：双击新包装一次。没桌面环境时至少让一个人在本机跑一遍再发。
4. 安装包文件名必须是 `YuNian-Setup-<semver>.exe` —— 更新器用正则校验，
   不匹配会拒绝安装（防 cmd 注入）。

## 五、这次（0.3.1）的实测结果

0.2.368 作废（版本号跳过，直接进 0.3.1：社区下线 + 编排/语音重构之后的第一个客户端版本）。

| 项 | 值 |
|---|---|
| 基础包 | `release/v0.3.1/` 166 MB（electron-builder NSIS，可用） |
| 品牌离线包 | 295,799,756 字节（282 MB），压缩版 |
| sha512 | `J22Kp0UtYuoPyyKyaTQdkTfaActpqVOXPlGMWCpxLj3zT71a2i7FBwgfHAjJHsHL3ZoISf+FOWvfaa8XaxB8tA==` |
| 包内版本 | `resources/app.asar` 里 `package.json` version = **0.3.1**（更新器比对的就是它） |
| 全新安装 | ✅ 退出码 0，111 个文件 / 505.4 MB，`YuNian.exe` 就位，耗时约 2 秒 |
| 覆盖升级 | ✅ 退出码 0，装进已有目录，112 个文件（原有额外文件保留） |
| 装完启动 | ✅ `YuNian.exe` 启动成功（窗口句柄创建，132 MB 工作集） |
| 包内新代码 | `main-*.js` 含「稍后再装/前去更新」、`useAppUpdater-*.js` 含 `probeForUpdates`、`preload.cjs` 含 `probeForUpdates`、`main-bundle.cjs` 含 `updater:probe`，全包无 `push-catchup` |
| 发布状态 | ✅ **已发布 2026-10-21 06:09 UTC**（GitHub Release v0.3.1 + MinIO `updates/`），线上 `latest.yml` = 0.3.1 |

### 发布后核对（这四条都要过）

```powershell
curl.exe -sk https://downloadlianyu.asia/api/public/files/updates/latest.yml     # version 要等于新版本
curl.exe -skI https://downloadlianyu.asia/api/public/files/updates/YuNian-Setup-<版本>.exe   # 200 + Content-Length
# 在服务器上算一遍「对外提供的那个文件」的 sha512，必须和 latest.yml 一致（客户端就是这么校验的）
ssh root@<host> "curl -sk https://127.0.0.1/api/public/files/updates/YuNian-Setup-<版本>.exe | openssl dgst -sha512 -binary | openssl base64 -A"
# MinIO 里新旧版本都在（旧版本别急着删，升级中的用户还要下）
docker exec lianyu-minio sh -lc 'mc ls local/lianyu/updates'
```

### 安装器内置了无界面安装（排查用）

```powershell
# 0 = 成功，1 = 失败，2 = 卡住超时；日志写到 %TEMP%\lianyu-installer-<时间>.log
.\YuNian-Setup-0.3.1.exe --install-silent --dir "$env:TEMP\test-install"
Get-Content (Get-ChildItem $env:TEMP\lianyu-installer-*.log | Sort-Object LastWriteTime -Desc | Select -First 1)
```

它的意义不只是排查：**安装器终于可以在没有人点鼠标的情况下被验证**。
以前只能靠人肉双击，坏了也说不清卡在哪一步。

## 六、下次发版前值得先修的两件事

1. **`frontend/dist` 与 `src` 不同源**：dist 未纳入 git，仓库里那份是 2026-10-07 的产物，
   和当前源码不一致，排查线上问题时极容易被它带偏（这次就被带偏过一次）。
2. **没有 `vite build` 门禁**：`useActiveChatContext.js` 末尾多出的一个 `}` 让 HEAD
   根本构建不过，却存在了三个月没被发现。建议 CI 里加一条 `vite build`。
