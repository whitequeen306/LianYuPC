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

## 七、2026-10-21「双击没反应」复盘（未签名安装包撞 Windows 拦截）

### 现象

用户删掉本机安装后重装 0.3.1，报「双击完全没反应，窗口一直不出来」，
并截到 Windows 的 **Open File - Security Warning**：

```
The publisher could not be verified. Are you sure you want to run this software?
Name: C:\...\frontend\release\v0.3.1\YuNian-Setup-0.3.1.exe
Publisher: Unknown Publisher
```

### 现场取证（这台机器上实测）

| 观察 | 值 |
|---|---|
| 卡住的进程 | `YuNian-Setup-0.3.1` 存活 184s、WS 432MB、CPU 22s、**线程 33** |
| 顶层窗口 | **一个都没有**（连隐藏窗口都没有） |
| `PresentationFramework` | **未加载** —— 根本没走到 WPF 初始化 |
| 自解压目录 `%TEMP%\.net\YuNian-Setup-0.3.1\*` | 三次运行各一个子目录，每个 5 个文件 / 7.8MB，最后一次在 13:54，之后不再增长 |
| Mark of the Web | `dir /r` 查过：仓库目录、`frontend`、`release`、`v0.3.1` **全都没有** `Zone.Identifier` |
| Smart App Control | `VerifiedAndReputablePolicyState = 0`（关闭） |
| SRP / AppLocker | `Safer\CodeIdentifiers` 只有 `authenticodeenabled=0`，无任何路径规则；无 SrpV2 |
| Defender | 实时保护开启，`Policies\Attachments\ScanWithAntiVirus = 3` |

### A/B 对照（同一份字节，只换放置位置）

| 用例 | 输入 | 结果 |
|---|---|---|
| A | 拷到 `%TEMP%\ab-temp-copy\` 再运行 | **exit 0，2.8s**，111 文件 / 505.4MB |
| B | 直接运行 `frontend\release\v0.3.1\` 里那个 | `Start-Process` 抛 **`The operation was canceled by the user.`**，卡了 **405s** |

两份 SHA256 完全相同（`19C150CD3FC72B2D…`）。

**结论：改目录后行为不同，而触发的是 Windows 的「未知发布者」安全警告；
警告不点掉，进程根本不会被创建，所以表现成「双击没反应」。**

### 老实说：路径差异没归因成功

「同一份字节换个目录就不弹警告」这一点，我排查了 MotW、Smart App Control、
SRP/AppLocker、Defender 排除项，**都没有解释**。所以这里只记事实，不编原因。
对真机用户影响有限（他们从浏览器下载到 `Downloads`，本来就会带 MotW 并弹更吓人的
SmartScreen），但对**我们自己**排查很致命：症状会被误判成"安装器坏了"。

### 真正的根因：我们把安装器换掉了（上一轮结论写错了，此处纠正）

上一轮我写的是「只能给 exe 签名，换 NSIS/msi 一样被拦」——**这句是错的**。
用户一句「之前安装都没有这个弹窗啊」逼出了对照实验，结论是：

**0.2.366 / 0.2.367 根本不是这个安装器。**

| 版本 | 产物 | 大小 | 类型 | 双击结果 |
|---|---|---|---|---|
| 0.2.359–0.2.362 | `LianYu Setup x.y.z.exe` | 166.1 MB | electron-builder NSIS | 直接出向导，**无警告** |
| 0.2.364 / 0.2.365 | `LianYu-Setup-x.y.z.exe` | 282.3 MB | 自制 WPF 单文件 | 未知发布者 |
| **0.2.366 / 0.2.367** | `YuNian-Setup-x.y.z.exe` | **165.9 MB** | **electron-builder NSIS** | **直接出向导，无警告** |
| 0.2.368 / 0.3.1 | `YuNian-Setup-x.y.z.exe` | 282.1 MB | 自制 WPF 单文件 | 未知发布者 |
| **0.3.2** | `YuNian-Setup-0.3.2.exe` | **165.9 MB** | **electron-builder NSIS** | **直接出向导，无警告** |

实测对照（同一台机器、同一目录）：

```
0.2.367 (165.9MB) -> 进程立刻出现，窗口标题 "YuNian 安装"，全程无安全警告
0.3.1   (282.1MB) -> 弹 "Open File - Security Warning / Unknown Publisher"，
                     不点掉进程根本不会创建 → 表现为「双击没反应」
```

所以 Windows 拦的不是「未签名」，而是**一个 282MB 的陌生单文件 exe**；而 NSIS 安装器
在 Windows 眼里是"正经安装程序"，走正常流程。用户以前装的一直是 NSIS 那一版。

### 修复：0.3.2 回到 NSIS

打包流程里 `prepare-branded-release.mjs` 做的事就是**用 282MB 的 WPF 包覆盖掉
electron-builder 打好的 NSIS 包**。所以 0.3.2 只要走到 `electron-pack.mjs` 就停：

```powershell
# 0.3.2 起：不要跑 prepare-branded-release.mjs
cd frontend
$env:LIANYU_SKIP_LAUNCHER_SMOKE = "1"   # 无交互桌面时 electron 起不来，见下
node scripts/electron-pack.mjs
# 产物 release/v0.3.2/: exe 165.9MB + blockmap + latest.yml（electron-builder 自己生成）
```

代价与收益：失去自定义安装界面（换成标准向导），换来 282MB → 166MB、
双击即用、不再撞「未知发布者」。

### 品牌化该怎么做（别再回到自制 exe）

electron-builder 的 NSIS 本身支持品牌化：`installerIcon` / `installerSidebar` /
`installerHeader` / `license` / `installer.nsh` 自定义页面。现在的
`build/installer.nsh` + `nsis.installerIcon` 已经在了 —— **要更漂亮就往 NSIS 里加，
不要在它外面再套一层自制安装器。**

### 代码签名仍然值得做（但不再是这个问题的解）

签名解决的是"发布者未知"的信任问题，不是这次的"窗口不出来"。有证书当然更好，
建议顺序：先回到 NSIS（已做），再择机买证书。

### 附带发现：旧安装器留下的目录会让 NSIS 装不上

用户随后用 0.2.367 的 NSIS 包安装时报：

```
不能打开要写入的文件:
"C:\Users\hp\AppData\Local\Programs\YuNian\uninstallerIcon.ico"
[Abort] [Retry] [Ignore]
```

当时**旧版 YuNian.exe 还在运行**（4 个进程），NSIS 把它遇到的第一个写不进去的文件
报了出来。处理办法：安装前先彻底退出予念（托盘也要退），再运行安装包。

> 这条没有 100% 归因（也可能是 Defender 实时扫描占用了文件），
> 但"先关应用再安装"是可复现、无副作用的做法。

### 本次顺手修掉的两个真缺陷

1. **无界面模式把版本号写成了 `silent-probe`**
   `App.RunSilentInstall` 以前 `new InstallerEngine("silent-probe")`，于是
   「应用和功能」里显示的版本就是字符串 `silent-probe`（真机上已出现）。
   现在 `InstallerEngine.GetInstallerVersion()` 是公开静态方法，GUI 与无界面共用。
   **已验证**：`--install-silent` 后注册表 `DisplayVersion = 0.3.1`（不再是 `silent-probe`）。
2. **GUI 模式完全不记日志**
   以前只有 `--install-silent` 写日志，用户说"装不上"时只能靠枚举进程去猜。
   现在新增 `InstallerLog`，两种模式都写
   `%TEMP%\lianyu-installer-YYYYMMDD-HHmmss.log`（含 base / exe / version / payload /
   temp / 位数 / mode / target），失败时弹框直接把日志路径给用户。
   日志头部实测：

   ```
   [16:41:51.948] === LianYu installer ===
   [16:41:51.948] base        = C:\Users\hp\AppData\Local\Temp\ver-fix-publish\
   [16:41:51.949] version     = 0.3.1
   [16:41:51.953] payload ok  = True
   [16:41:51.955] mode        = silent
   [16:41:51.955] target dir  = C:\Users\hp\AppData\Local\Temp\ver-fix-target
   ```

### 用户当时是怎么装回去的

安装包拷到 `%TEMP%` 再跑无界面安装（2.5s、exit 0、111 文件），
然后补桌面/开始菜单快捷方式，双击快捷方式 1.1s 出窗口。**这条路径可以写进客服话术。**

## 八、2026-10-21 追加：NSIS 也会被 SmartScreen 拦（第七节结论再修正）

第七节我写的「换回 NSIS 就不弹了」——**这句话也不成立**。

### 事实

同一天、同一台机器、同为 electron-builder NSIS、同为 166MB 未签名：

| 构建 | sha256 前缀 | 启动结果 |
|---|---|---|
| 0.3.2（发布版） | — | 正常出向导 |
| 0.3.3 第一次构建（带欢迎页） | — | **SmartScreen：「Windows 已保护你的电脑 / 阻止了无法识别的应用启动」** |
| 0.3.3 重建（回退欢迎页，新 hash） | `DBFF1BA6B7D67296` | 正常出向导，无 SmartScreen、无未知发布者对话框 |

第三次的验证方式：启动后枚举可见顶层窗口 + 查 `Smartscreen.exe` 进程，
两者都不存在，而 `YuNian-Setup-0.3.3` 进程带着「YuNian 安装」窗口在跑。

### 结论

**SmartScreen 的判定是按文件哈希的声誉，不是按安装器类型、也不是按"改了什么"。**
所以：

- 回退界面配置 **不能** 可靠地让 SmartScreen 消失；这次通过是换了 hash 后碰巧过。
- 关掉欢迎页只是「少改动」，不是「解决问题」。
- 唯一可靠解仍是 **代码签名**。在这之前，任何一次发版都可能被拦，
  应对办法只有：点「更多信息 → 仍要运行」，并在下载页写清楚。

### 我上一轮验证方式的两个漏洞（都已修）

1. **只查了一种提示**：探针只找 `#32770 "Open File - Security Warning"`，
   完全看不见 SmartScreen 那套全屏蓝框，于是得出了「没有安全警告」的错结论。
   现在 `scripts/_gate_installer_prompt.ps1` 同时检查：
   - `Smartscreen.exe` 进程是否带窗口（SmartScreen 的可靠信号）
   - 传统 `#32770` 未知发布者对话框
   - 安装器自己的窗口是否出现
   三种结论分别是 `BLOCKED` / `DIALOG` / `PASS`。
2. **把偶发当规律**：一次 PASS 不能推出"已解决"。发布前跑这个 gate，
   它只回答"这个 hash 此刻能不能启动"。

### 另一个坑：品牌位图被 gitignore 吞掉

`frontend/build/*.bmp` 原本在 `.gitignore` 里（历史上是生成物）。加了
`installerSidebar.bmp` / `installerHeader.bmp` 之后，如果不同时加例外，
换台机器构建时 electron-builder 会**静默回退到 `nsis3-metro.bmp`**，
品牌标识凭空消失且不报错。已在 `.gitignore` 里加 `!` 例外把这两人工产物纳入仓库。
