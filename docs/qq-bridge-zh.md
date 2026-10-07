# YuNian ↔ QQ 桥接（桌面端 B 方案 · NapCat）

让予念桌面客户端通过 [NapCat](https://github.com/NapNeko/NapCatQQ) 接入 QQ：QQ 私聊 / 群 @ 消息经 NapCat 到达桌面端桥接器，桥接器以**用户自己的登录态**调用云端流式对话 API，把角色回复逐段发回 QQ。

> **当前实现：桌面端 B 方案（唯一实现）。** 早期方案 A——后端 Spring 模块 `lianyu-qq-bridge`（进程内直调 `ConversationService`、服务器侧配置绑定）——已于 2026-10 从仓库移除。QQ 桥接的全部逻辑现位于 `frontend/electron/`，随桌面客户端分发。

---

## 0. 一句话架构

```
QQ 客户端 ──(QQ 协议)──> NapCat（本机正向 WS）──(OneBot 11)──> qqBridge（Electron 主进程）
                                                                    │
                                                                    │ POST /api/conversation/{id}/messages/stream
                                                                    │ （lianyu-token 鉴权 + 证书 pin，复用用户登录态）
                                                                    ▼
                                                          云端 ChatTurn Graph → SSE 流
                                                                    │
                                                                    │ 解析 {"pieces":[...]}（多段气泡分段）
                                                                    ▼
                                                NapCat send_private_msg / send_group_msg 逐段回发 QQ
```

与 A 形态的本质区别：桥接器**走标准 HTTP 鉴权链路**（用户自己的 token、配额、记忆、关系全部归属用户真实账号），不复用运营方共享 key，也不在后端新开绕过鉴权的进程内通道。

---

## 1. 依赖与外部组件

| 组件 | 说明 | 风险 |
|------|------|------|
| **NapCat** | QQ 协议端，跑在用户本机（桌面端可全自动托管，见 §4）。 | 自定义 `NOASSERTION` 许可：禁止修改 / 再分发 / 商用。我们**只运行它 + 使用标准 OneBot 11 协议**，不引入其代码、不分发其二进制（托管模式按用户指令从官方 Release 下载到本地 userData，不进安装包）。 |
| **OneBot 11** | NapCat 实现的即时通讯协议标准（消息事件 / API 调用 / 消息段）。 | 公开标准。 |
| **Node `ws`** | 桌面端桥接器的 WS 客户端（`frontend/electron/qqBridge/napCatClient.js`）。 | 仅连本地/内网 NapCat。 |

> ⚠️ **QQ 封号风险（务必先读）**：NapCat 是第三方协议客户端，**违反 QQ 用户协议**，可能导致该 QQ 账号被腾讯风控、限制登录甚至**永久封号**。请**务必使用一次性小号**，不要用主号。此风险无法规避，由用户自负。

---

## 2. 模块结构

```
frontend/electron/
├── qqBridge/                       # 桥接器（OneBot ↔ 云端 API）
│   ├── qqBridge.js                 #   编排器：收消息 → 调云端 → 回发；含图片双向、会话隔离、404 自恢复
│   ├── napCatClient.js             #   OneBot WS 客户端：重连 / echo 配对 / selfId 探测与踢线检测
│   ├── messageExtractor.js         #   消息段解析：抽文本/图片、解析发送者与回发目标
│   ├── qqBridgeSettings.js         #   配置读写（userData/qq-bridge-settings.json）+ 放行判定纯函数
│   └── qqBridgeCoordinator.js      #   编排协调：状态广播 / 告警 / 自动启动 / 自动绑定 / 扫码登录窗
└── napcatRuntime/                  # NapCat 自管托管（hosting.mode=auto）
    ├── napcatRelease.js            #   发行解析（GitHub + pinned 锚点回退）、安装完整性、QQ 安装目录探测
    ├── napcatDownloader.js         #   多源下载 + sha256 校验 + .part 断点续传（可中断）
    ├── napcatConfig.js             #   幂等写 onebot11.json / webui.json
    ├── napcatProcess.js            #   子进程拉起 / 崩溃重启（maxRestarts=5）/ 停止
    └── napcatHost.js               #   托管编排：解析发行 → 下载 → 配置 → 拉起 → 回调 bridgeStarter
```

渲染端入口：QQ 桥接页 `#/app/qq-bridge`（`frontend/src/pages/QqBridgePage.vue` + `stores/qqBridge.js`），经 IPC（`desktop:*-qq-bridge-*` / `desktop:qq-host-*`）与主进程交互。

---

## 3. 消息处理流程

`qqBridge.js` 的 `handleOneBotMessage`：

1. **热读配置**：每条消息重读 `qq-bridge-settings.json`——binding / reply 改动即时生效，无需重启桥接；napcat 连接参数改动需重启；`characterId` 变更会清空会话隔离映射。
2. **放行判定**：`allowMode='allowlist'`（默认）**默认拒绝**——私聊须命中 `allowUsers`、群聊须命中 `allowGroups`，空表 = 全拒；`allowMode='open'` 不限（UI 明示配额 / 注入风险）。
3. **会话隔离**：按 QQ 用户路由到独立会话，绝不串台：
   - 私聊：`sessionMap['private:<qq>']` 命中则复用；未命中懒建一个复用绑定角色的 `SINGLE` 会话（per-key inflight 锁防并发重复建）。
   - 群聊：`binding.conversationId` 优先；否则 `sessionMap['group:<gid>']` 懒建共享会话。
   - 绑定角色：`binding.characterId` 直用；否则反查绑定会话详情拿 `characterId`，缓存并持久化。
4. **抽取内容**：文本段拼接、`@机器人` 剥离、`@他人` 保留为 `@<qq>`、首张图片保留（`data.url` / `data.file` 双来源）；无正文时退回 `raw_message`。
5. **入向图片**：NapCat 给的 url 多为本地/受限地址，云端拉不到——先在本地下载字节（仅 http(s)/`base64://`，≤5MB），multipart 上传 `/api/conversation/chat-image` 换取公网 `imageUrl` 再随消息发出；失败退回原 url。
6. **跑回合**：`POST /api/conversation/{id}/messages/stream`（SSE，`timeoutMs` 默认 120s）。出口走 `performApiRequest`：证书 pin + 出站 host 白名单 + token 统一注入（自填的 `lianyu-token` header 会被剔除防伪造）。
7. **解析分段**：流内 `{"pieces":[...]}`（后端 `beforeStreamComplete` 推送，[DONE] 前）优先；缺失时回退 `replace`（规范化全文）或拼接的 `delta`；`{"error":...}` → 兜底文案。逐段回发还原 App 内多气泡体验：条间 `segmentDelayMs`（默认 500ms）+ `segmentJitterMs`（默认 ≤800ms 随机抖动，降低风控误判）；`appendCharacterSuffix` 开启时每段尾缀「（本次回复由虚拟角色：<角色名>回复）」。
8. **出向图片**：AI 回复的图片不在 SSE 流里——流后短轮询 `GET /messages?limit=50`（最多 3 次 × 500ms）取最新 assistant 记录的 `imageUrl`，对象 key 拼 `/api/public/files/<key>`，经 `performApiRequest(binary)` 下载（自签证书必须走 net+pin）转 base64，用 OneBot `image` 段回发（≤10MB）。
9. **自恢复**：云端 404（会话不存在）→ 失效该发送者的会话缓存 → 重新解析 / 重建会话 → 重试一次。
10. **异常兜底**：网络异常 / 超时 / 云端错误信封 → 发一次 `reply.fallbackText`（默认「（服务暂时不可用，稍后再试）」，留空则静默）。

> 已知边界：同一会话内没有串行锁，同一 QQ 用户快速连发多条消息时，两轮回复可能交错到达（后端按消息逐条处理，顺序不保证）。

### 主动消息推送（云端 → QQ）

角色主动消息（调度器主动发言、冷开场、城市跟进等）生成后会同步推送到 QQ 私聊，不再只躺在 App 里：

```
云端 persistProactiveReply 落库 + STOMP /user/queue/notifications（type=PROACTIVE_MESSAGE）
  → 渲染端 notifications.js notifyProactiveDesktopIfNeeded
  → IPC desktop:notify-proactive-message
  → main.js handleProactiveMessageNotification → qqBridge.js fanoutQqProactive
```

- **目标解析**：在 `sessionMap` 里反查 `private:<qq>` 值等于通知里 `conversationId` 的条目——只有「QQ 用户聊出来的会话」会推送；纯 App 会话不推（没有 QQ 目标可发）。群共享会话（`group:` 键）不参与反查，服务端本就只为 SINGLE 会话生成主动消息，天然不推群。
- **取全文**：通知只带 ≤100 字 preview，桥接按会话拉 `GET /messages?limit=20` 取最后一条 USER 之后的全部 ASSISTANT 分段（多段全发，还原 App 内多气泡）；空 content 的语音类记录跳过；拉取失败回退用 preview 单段。
- **发送节奏**：与回复链路一致——段间 `segmentDelayMs + jitter`、按 `appendCharacterSuffix` 带角色尾缀。
- **去重**：主进程 15 秒签名窗口（conversationId+characterId+preview）——QuickChat 与主窗口同时在线时两个 STOMP 连接会各发一次 IPC，第二份在 QQ 扇出内被丢弃（不影响微信桥/桌宠的既有逻辑）。
- **边界**：桥接未运行或 WS 断开时跳过、不补发（消息仍留在 App 内，与微信桥行为一致）；桌宠问候语（`/api/desktop/observe`）不落库、不走通知链，不在推送范围。

---

## 4. NapCat 自管托管（`hosting.mode='auto'`）

用户在设置页同意（`hosting.consented`）后，桌面端全自动完成 NapCat 的下载与运行；`mode='manual'` 则由用户自行安装运行，桥接器仅连其 WS。

**启动链**（`napcatHost.js`）：

1. **发行解析**：GitHub API 取最新 Release（受限网络回退到仓内 pinned 锚点 tag）；仅当最新版严格新于已装版本才提示升级（不自动升级，已装继续跑）。
2. **下载解压**：未安装或安装不完整时，按候选源顺序（直连 → 镜像）下载 Shell 整包，sha256 校验，`.part` 断点续传；换源即清残片整包重下。可随时中断（stop/重装 abort）。
3. **幂等配置**：正向 WS `127.0.0.1:3001`（token 定死 `lianyupc`）、WebUI `127.0.0.1:6099`；同时设 `NAPCAT_WEBUI_SECRET_KEY` 环境变量——NapCat 官方机制会在自身 init 时把 WebUI token 强制更新为此值，比预写配置文件可靠。
4. **改写 `loadNapCat.js`**：使 QQNT 启动时 import 本地 `napcat.mjs`（与官方 launcher.bat 等价；部分构建布局不同，不改写会 404）。
5. **拉起进程**：`NapCatWinBootMain.exe <QQ.exe路径> <hook.dll> --user-data-dir=<bot 专属 profile>`——机器人 QQ 跑在独立 profile，与用户日常 QQ（两个 Electron 单实例）共存，无需关闭日常 QQ。就绪判定：stdout 解析出 WebUI，或 8s 兜底（直接以已知 token 进入 running）。
6. **拉起桥接**：以写好的 `ws://127.0.0.1:<wsPort>` + token 回调 `bridgeStarter` → `startQqBridge`。

**踢线自愈**：QQ 被挤下线 / 掉线事件 → 清 selfId、重启 5s 快速补探；确认被踢且 hosting=auto → 自动停 NapCat（等 2s）→ 重新走启动链（下次走扫码）。

**停止清理**（`stopNapCatHost`）：杀进程树 → 补杀孤儿 QQ.exe（按命令行中的 bot profile 路径精准匹配，不误伤日常 QQ）→ 删除 bot profile（清登录态，下次启动走扫码）→ 清 `hosting.qqUserId`。

---

## 5. 配置项（`userData/qq-bridge-settings.json`）

桌面端每个用户各自保存一份，字段命名与已移除的后端 A 形态保持一致。UI 在 QQ 桥接页维护，一般无需手编。

| 块 | 字段 | 默认 | 说明 |
|----|------|------|------|
| — | `enabled` | `false` | 桥接总开关 |
| `napcat` | `wsUrl` / `accessToken` | `ws://127.0.0.1:3001` / 空 | NapCat 正向 WS 地址与 token（auto 托管时自动写入） |
| | `connectTimeoutMs` / `reconnectBaseMs` / `reconnectMaxMs` | 10000 / 600 / 6000 | 连接超时与重连退避 |
| `binding` | `conversationId` | 空 | 云端绑定会话（QQ 桥接页可自动选/建） |
| | `characterId` / `characterName` | 空 | 绑定角色缓存（懒建会话复用；首条消息自动反查） |
| | `provider` / `model` / `visionModel` | `platform` / 空 / 空 | 文本 / 识图模型；空 = 平台默认 |
| | `allowMode` | `allowlist` | `allowlist` 默认拒绝；`open` 全放行 |
| | `allowUsers` / `allowGroups` | `[]` / `[]` | QQ 号 / 群号白名单（字符串数组） |
| | `sessionMap` | `{}` | 会话隔离映射（`private:<qq>` / `group:<gid>` → conversationId，自动维护） |
| `reply` | `fallbackText` | 见 §3 | 云端异常兜底文案 |
| | `timeoutMs` | 120000 | 云端流式调用超时 |
| | `segmentDelayMs` / `segmentJitterMs` | 500 / 800 | 分段条间延迟与随机抖动（0 = 不延迟） |
| | `appendCharacterSuffix` | `true` | 尾缀「本次回复由虚拟角色…回复」开关 |
| `hosting` | `mode` | `manual` | `auto` = 桌面端自管 NapCat；`manual` = 用户自装 |
| | `consented` | `false` | 是否同意自管托管（首次需确认，未同意不下载/拉起） |
| | `webuiPort` / `wsPort` | 6099 / 3001 | 托管端口（token 自动写入并回填） |
| | `napcatVersion` | 空 | 已装版本（升级检测用） |

---

## 6. 状态、告警与日志

- **状态推送**：`desktop:qq-bridge-status`（桥接 WS：connected / ready（拿到 selfId）/ disconnected / stopped，含 kicked 标记）、`desktop:qq-host-status` / `desktop:qq-host-download`（托管：resolving-release / downloading / writing-config / launching / running / error）。
- **系统告警**（通知 + 页面横幅）：QQ 掉线、桥接断线、已连接但 15s 未检测到登录态（提醒扫码）、NapCat 重启失败。
- **自动启动**（应用启动时，`main.js`）：manual 模式且 `enabled` + 已绑定会话 + 有登录态 → 直接拉起桥接；auto 模式且已同意 → 拉起托管（成功后经 bridgeStarter 连带拉起桥接）。
- **首次绑定**：绑定会话缺失时 `ensureBridgeBinding` 自动选一个 SINGLE 会话（按 characterId 过滤）或建新会话；无白名单条目时放行模式默认置 `open`（首次体验即插即用，建议随后收紧）。
- **扫码登录**：QQ 桥接页打开 NapCat WebUI 登录窗——沙箱 `BrowserWindow`，导航锁死本机 NapCat URL，外链走系统浏览器。
- **日志**：主进程日志带 `[qqBridge]` / `[napcatHost]` 标签；`desktop:qq-bridge-get-logs` 返回按标签过滤的最近 500 行，用于排查「发消息收到兜底文案」类问题（记录耗时 / 状态码 / 内容长度，不含 token 与正文）。

---

## 7. 安全要点

- **走标准鉴权链**：所有云端调用带用户自己的 `lianyu-token`，经 `performApiRequest`（Electron net + 证书 pin + 出站 host 白名单）；不新增任何后端端点，不在 Sa-Token `notMatch` 加路径。
- **入向图片 SSRF 防护**：私网 / 链路本地 / 云元数据 host 一律拒绝（127.0.0.1 放行仅因 NapCat 本地 file server 必须可达）；重定向手动逐跳复检，堵「公网 URL 302 到 127.0.0.1/169.254」的跳板绕过；`file://` 与裸本地路径已移除（曾可被构造读取本机任意文件）。
- **托管边界**：下载仅从 NapCat 官方 Release（含 sha256 校验）；登录窗导航锁死本机 NapCat；NapCat 二进制**不进**予念安装包（许可禁止再分发）。

---

## 8. 故障排查

| 现象 | 排查 |
|------|------|
| 桥接状态一直「连接中 / 已断开」 | NapCat 未运行或 WS 端口 / token 不一致：manual 模式检查 NapCat 网络配置（正向 WS、array 上报格式）；auto 模式看 `desktop:qq-bridge-get-logs` 里 `[napcatHost]` 段。 |
| 连上但「未检测到登录态」 | 打开 QQ 桥接页扫码登录；小号被风控则换号。 |
| 发消息收到兜底文案 | `desktop:qq-bridge-get-logs` 看 `[qqBridge]`：`cloud reject`（鉴权/限流/404）还是 `message relay failed`（超时/网络）；404 会自动重建会话重试一次。 |
| 回复只有一段 | AI 确实只回了一段，或 SSE 未携带 pieces（已自动回退 replace/delta 拼接）。 |
| 图片不回传 | 入向：看 `upload input image failed`（源地址被 SSRF 拦截或超 5MB）；出向：AI 回复本身无图，或落库轮询 3 次内未见 imageUrl。 |
| 桌面 QQ 与机器人冲突 | 正常共存（独立 profile）；若机器人 QQ.exe 残留，停用托管会按 profile 精准清理。 |

---

## 9. 参考

- [OneBot 11 标准](https://github.com/botuniverse/onebot-11)
- [NapCat 文档](https://napneko.github.io/)
- [安全耦合地图](./security-coupling-map-zh.md)——QQ 桥接已回归标准 HTTP 鉴权链路，原「进程内扩展模块」边界规则（§9）仅作为历史参考。
