# channel-gateway · QQ 云端托管网关（W1 PoC）

一台 Windows 云主机上托管多个 QQ 号：**一套协议端 shell，多个独立 workdir**，每个号扫码登录后通过 OneBot 11 收消息，W1 阶段只做「收到什么回什么」，用来证明「云端常驻 → QQ 收发」这条链路成立。

替代的是已下线的桌面端 B 方案（[`docs/qq-bridge-zh.md`](../docs/qq-bridge-zh.md)）：那条路要每个用户自己装 QQNT + NapCat + 开机保持运行，本仓库已于 2026-11-14 把桌面端 QQ 桥接整块删除。

> ⚠️ **协议端现状（2026-11-14）**：NapCat 上游已关停——仓库被清空（README 只剩 `## Ending`）、releases 为空、文档站 `napneko.github.io` 变 Site not found、`v4.18.7` 资产 404。同时 NapCat 的 `NapCatWinBootMain.exe`/`qqnt.json` **必须与 QQ 构建号成对**，QQ 自动升级后旧 shell 会被 QQ 自身反篡改校验杀掉（用户侧表现为「文件已损坏，请重新安装QQ」）。因此本 PoC 目前**跑不到扫码**，`src/launch.js` 的启动链保留但引擎待换：
> - **Lagrange.Milky**（`Lagrange.Core` 的 `noirhare/milky-refactor` 分支，C#，无需 QQ 客户端）—— **已在本机编译通过、能连 QQ 服务器、能进二维码登录阶段**，只差 Signer Token（白名单制）。逐步手册见 [`docs/qq-milky-runbook-zh.md`](../docs/qq-milky-runbook-zh.md)；
> - **LLBot**（原 LLOneBot，`LuckyLilliaDev/LuckyLilliaBot`）—— 现为厂商授权门槛产品，`--headless` 需先从 `auth.luckylillia.com` 取 Auth Token，且是 GPL-2.0；
> - 其余 fork（如 `dgcrxs/NapCatQQ`）来历不明，不建议做运行时依赖。

## 记忆通道：换入口不丢记忆（已实测，2026-11-14）

第一版 PoC 的回显（`[key] 原文`）只证明链路通，不证明「换到 QQ 还能接着聊」。真正决定这一点的是：**记忆挂在 `(userId, characterId)` 上，不挂在 QQ 号上。** 外部身份只干一件事——换出这两个 id：

```text
openid/QQ号 ──bindings.json──▶ (userId, characterId) ──云端──▶ conversationId ──▶ 记忆库
```

对应两支新模块：

| 模块 | 作用 |
|---|---|
| `src/binding.js` | 外部身份 → `(userId, characterId, conversationId, token)` 的落盘映射；`upsert` 只并字段，换号不会把角色关联弄丢 |
| `src/lianyu.js` | 云端对话 API 最小客户端：`ensureConversation`（按 `characterId` 复用 SINGLE 会话，找不到才建）、`sendText`（`POST /api/conversation/{id}/messages`）、`listMessages` |
| `scripts/simulate-qq.mjs` | 扮演「QQ 那一侧」：只拿一条 openid 绑定，问一句话，打印角色回复 |

实测（同一账号 421，云端生产库）：

| 场景 | 结果 |
|---|---|
| App 侧说「下周三上午去医院拔智齿」 | 记忆落库：`FACT「用户下周三上午要去医院拔智齿」(importance 0.85)` + `EMOTION「用户对拔智齿感到害怕」`，`sourceMsgIds=[147256]` |
| QQ 侧（另一进程、只知道 openid）问「我下周三是有什么事来着？」 | 「拔智齿。」→ 会话按 `characterId` 复用，不因入口不同而分叉 |
| **把这条会话的消息全删空**（`DELETE /api/conversation/953/messages`，剩 0 条）再问同一句 | 「下周三上午，你要去医院拔智齿。」→ 上下文里一个字都没有，答出来只能来自**记忆库** |
| 全新角色「阿凛」（无此记忆）问同一句 | 「（翻了下手机备忘，没找到线索）」→ 没有串到别的角色 |

> 顺带一个观察：这两条记忆的 `lastRecalledAt` / `recallCount` 在我清空会话后的那次召回里没有变化（仍停在 App 侧那两轮）。如果「记忆强度衰减」依赖这个计数器，值得单独查一下它们是否只在某条路径上回写。

跑法（自备一个 `lianyu-token` 与角色 id）：

```powershell
cd channel-gateway
$env:LIANYU_API_ORIGIN   = "https://downloadlianyu.asia"
$env:LIANYU_PROVIDER     = "dashscope"   # 或 platform
$env:LIANYU_MODEL        = "qwen-plus"
$env:LIANYU_INSECURE_TLS = "1"           # 云端自签证书；正式版走 /internal + HMAC
$env:DATA_ROOT           = ".\\.data\\qq-gateway"
node scripts/simulate-qq.mjs --from OPENID-1 --user 421 --character 1002 --token <token> --text "我下周三是有什么事来着？"
```

> 安全边界：PoC 的 `bindings.json` 明文存用户 token（`local/` 与 `.data/` 均已 gitignore）。正式版必须换成服务端票据——见 `docs/qq-milky-runbook-zh.md` 的 W3 项（`/internal/**` 目前不在 SaToken 拦截范围，只靠内网隔离）。

## 为什么不是「把桌面端 napcatHost.js 改成多实例」

- 原 `frontend/electron/napcatRuntime/napcatHost.js` 的状态全是模块级单例（`proc` / `onStatus` / `bridgeStarter` / `configInfo`），一个进程只认一个号；
- 原 `napcatConfig.js` 固定写 `getNapCatInstallRoot()/config`，不接受 profile 参数；
- 原 `napcatRelease.js` 顶部 `import { app } from 'electron'`，而既有前端测试用 `vi.mock('electron')` 注入 `app.getPath`，改成惰性 require 会连带打红前端 400+ 用例。

所以云端池另写，并把真正通用的三支**平移进本目录**（桌面端已删除，不再有第二份）：

| 模块 | 位置 | 用途 | 原在 |
|---|---|---|---|
| `createNapCatProcess` | `src/supervisor.js` | 子进程 + 崩溃退避重启 + `taskkill /T` | `frontend/electron/napcatRuntime/napcatProcess.js` |
| `createNapCatClient` | `src/onebotClient.js` | OneBot WS（重连 / echo 配对 / selfId 探测） | `frontend/electron/qqBridge/napCatClient.js` |
| `extractMessageContent` / `resolveSender` / `resolveReplyTarget` | `src/onebotExtract.js` | 消息段解析 | `frontend/electron/qqBridge/messageExtractor.js` |

## 一机多号怎么隔离

NapCat 打包产物里的路径包装器优先读 `NAPCAT_WORKDIR`：

```js
process.env.NAPCAT_WORKDIR ? n = process.env.NAPCAT_WORKDIR : n = this.binaryPath
this.logsPath = join(n, 'logs'); this.configPath = join(n, 'config'); this.cachePath = join(n, 'cache')
```

于是 shell 只有一份（~29MB），每个号靠 `NAPCAT_WORKDIR` 拿到独立的 `config / logs / cache / plugins`，再靠 `--user-data-dir` 拿到独立的 QQ 登录态。这套 env 拼装对任何「QQNT hook 型」协议端都成立（NapCat 系、LLBot 系），换引擎时是唯一需要重看的文件（`src/launch.js`）。

## 前置

1. **Windows 云主机**（PoC 阶段不碰 Linux 容器；容器化见 W5）。
2. **QQNT**：装好 QQ 后确认 `.../Tencent/QQNT/QQ.exe` 存在。
3. **协议端 shell**：解压到 `<DATA_ROOT>/napcat-shell`，目录内必须有
   `NapCatWinBootMain.exe`、`NapCatWinBootHook.dll`、`napcat.mjs`、`qqnt.json`。
   原桌面端用户的 `%APPDATA%/lianyu-pc/napcat` 里通常已有一份（构建号可能对不上当前 QQ）。
4. **Node 20+**，`cd channel-gateway && npm install`（只依赖 `ws`）。

## 跑起来

```powershell
cd channel-gateway
$env:DATA_ROOT = "D:\qq-gateway"
$env:QQ_EXE    = "C:\Program Files\Tencent\QQNT\QQ.exe"
$env:GATEWAY_PORT = "18080"
node src/server.js
```

浏览器打开 `http://127.0.0.1:18080/`：

1. 「新增并启动」→ 填 `key`（`[a-zA-Z0-9_-]{1,32}`，建议用 `u1001-char7` 这种可读形式）；
2. 行内出现 `waiting_login` 后点「二维码」，手机 QQ 扫码并确认登录；
3. 状态变 `online` 且「QQ」列显示 uin 即成功；此时用另一个号私聊它，会收到 `[key] 原文` 的回显。

### 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `DATA_ROOT` | `<repo>/local/qq-gateway` | 账号数据根（gitignored） |
| `NAPCAT_SHELL` | `<DATA_ROOT>/napcat-shell` | 共享 shell |
| `QQ_EXE` | 无 | **必填**，QQNT 的 QQ.exe 绝对路径 |
| `GATEWAY_HOST` / `GATEWAY_PORT` | `127.0.0.1` / `18080` | 控制台监听 |
| `GATEWAY_TOKEN` | 空 | 非空则要求 `x-gateway-token` 头；对公网必须设 |
| `MAX_ACCOUNTS` | `20` | 账号数上限 |
| `WS_PORT_BASE` / `WEBUI_PORT_BASE` | `30000` / `31000` | 端口分配起点 |
| `AUTO_START` | 空 | `all` 或逗号分隔的 key，启动时自动拉起 |

### 目录结构

```
<DATA_ROOT>/
  napcat-shell/                  # 共享，只读
  accounts/<key>/
    account.json                 # 端口/令牌/备注（0600）
    config/{onebot11.json,onebot11_<uin>.json,webui.json,napcat.json}
    loadNapCat.js                # 每号一份，file:// 指向共享 shell
    qq-profile/                  # QQ 登录态（登出/换号＝删它）
    cache/qrcode.png             # 协议端自己落的二维码
    logs/
```

## 验收清单（W1 的五条）

1. **`NAPCAT_WORKDIR` 生效**：启动后 `<DATA_ROOT>/accounts/<key>/logs/` 里出现协议端自己的日志文件。若日志跑到了 `napcat-shell/logs/`，说明该版本不认这个变量，退回「每号一份 shell 拷贝」方案。
2. **独立配置**：`accounts/<key>/config/onebot11_<uin>.json` 在首登后出现，且 `port`/`token` 等于 `account.json` 里本号的值。
3. **扫码链路**：`waiting_login` 时 `cache/qrcode.png` 有内容，控制台能弹图；扫码后状态转 `online`。
4. **OneBot 收发**：`online` 后另一个号私聊 → 网关回 `[key] 原文`；日志出现 `[echo]`。
5. **两个号互不串**：A 号私聊只回 A，改 A 的配置不影响 B；`taskkill` A 的 QQ 进程后 A 掉线而 B 保持在线。

> 当前 1~5 里只有「进程起得来 + 控制台/HTTP 正常」被验证过（19 单测 + 13 项冒烟，冒烟用 `cmd.exe` 冒充 launcher）；**3~5 需要真实协议端能注入 QQ 才能验**，被上面的协议端停服阻断。

## 已知限制（W2 起处理）

- 只做回显，**没有接 `/api/conversation/{id}/messages/stream`**、没有记忆/人设/会话落库；
- 扫码用 NapCat WebUI API + `cache/qrcode.png`，属于协议端内部接口，版本升级可能变，需要在 `src/napcatWebui.js` 一处适配（换引擎时这一支同样要重写）；
- 登录态（`passkey.json` / `qq-profile/`）是**账号凭据**，PoC 明文落盘，生产必须 AES + KMS；
- 控制台默认只监听回环；开放出去必须配 `GATEWAY_TOKEN`，且后续要换 HS256/HMAC 签名（`/internal/**` 当前不在 SaToken 拦截范围）；
- 一台机器同出口 IP 挂几十个号是**批量异常特征**，PoC 只用于验证可行性，容量与 IP 策略见里程碑 W4；
- 单机 20 个 QQ 实例的内存/句柄压力未压测（按 300~500MB/实例估算，32G 约 40~50 个）。

## 自检

```powershell
cd channel-gateway
node --test "test/*.test.js"          # 19 个用例（配置/端口/配置生成/WebUI 哈希）
node ..\local\_gw_smoke.mjs           # 冒烟：用 cmd.exe 冒充 launcher，跑通 HTTP + 进程 + 日志
```

冒烟脚本在仓库根的 `local/`（gitignored），不进仓库。
