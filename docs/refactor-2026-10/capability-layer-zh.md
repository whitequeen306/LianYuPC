# 桌面能力层（自研）：替换 AgentAssistant MCP

> 2026-10。本文是「模型能力层重构」的落地记录：**外借的 AgentAssistant MCP 引擎已整体移除**，
> 电脑操控改由予念自己的 `desk_*` 原子能力承接。

## 一、为什么换掉

旧链路是「Electron spawn 一个 Python 引擎 → 引擎自己跑完整 agent 循环 → 只对外暴露
一个粗粒度工具 `computer_task(instruction)`」。代价：

| 问题 | 具体表现 |
|---|---|
| 黑盒 | 一次调用内部跑完多步，中间只有自由文本进度；模型无法逐步纠错 |
| 阻塞 | 后端 `call-timeout-ms: 630000`，审计 A5 点名；一次卡死拖住整轮 SSE |
| 脆弱 | 200MB 引擎包分发、sha256、解压、崩溃自动重启、WindowsApps Python stub、access violation 文案 |
| 双大脑 | 引擎自带 prompt/记忆/工具循环，与予念人设、记忆、安全策略两套，行为不一致 |
| 覆盖面错配 | UIA 全树遍历慢且对 Chromium/自绘界面失效，而它承担的正是「语义导航」这种最不擅长的活 |

## 二、目标架构

```
渲染端 Vue3 ──IPC──▶ Electron 主进程 capabilityHost ──HTTP──▶ Java ──STOMP──▶ 渲染端
                          │(感知/输入/policy/审计)              │(会话/路由/审计落库)
                          └──▶ PowerShell winActor              └──HTTP──▶ Python backend-agent
                                                                         (模型循环/预算/schema/文案)
```

一句话职责：

- **Python**：唯一会思考的地方（模型调用、prompt、工具 schema、预算、错误码 → 行动建议）；
- **Java**：唯一的用户级路由与记账（STOMP 会话、心跳、审计表、图片校验、结果消毒）；
- **Electron**：唯一能动手的地方（截图、`SendInput`、窗口、文件、剪贴板），且**策略判定是纯函数**。

红线未变：`JavaAiLogicRatchetTest` + `scripts/check-java-ai-free.ps1 -Mode strict` 仍为 `0/0/0`；
SSE 帧契约（`content/replace/pieces/error/[DONE]/keep-alive`）**一行未改**。

## 三、一次动作的完整链路

1. 模型返回 `tool_calls: desk_click{element_id:7, evidence:"user_request"}`；
2. Python `capability/budget.py` 查步数/视觉帧/墙钟 → `desktop_channel.py` 签名下发；
3. Java `InternalDesktopExecController`（`/internal/agent/desktop/exec`，HMAC + 时间窗 + 重放缓存）→ `AgentBridgeService.exec`；
4. STOMP `/user/queue/agent-tools` 到客户端；渲染端 `agentBridge` store 转 `capability:exec`；
5. `capabilityHost`：幂等去重 → 总开关 → `policy.decide`（前台进程/升级/来源护栏）→ 必要时确认 → 执行；
6. 结果 `POST /api/agent-bridge/result`（含 `images`、`policy`）→ Java 消毒 + 审计写入 → `future.complete`；
7. Python 把结果包成 tool 消息回灌，下一轮流式请求继续。

进度走旁路 `/user/queue/agent-tool-progress`，渲染成角色进度气泡。

## 四、能力清单（P0，11 项）

| 能力 | 等级 | 说明 |
|---|---|---|
| `desk_screen_read` | R0 | 截图 + 元素清单（JPEG q55，1024 宽） |
| `desk_read_elements` | R0 | 只取 UIA 元素编号清单（不上传图片，隐私模式） |
| `desk_window_list` / `desk_window_focus` | R0 / R1 | 列窗口 / 切前台 |
| `desk_app_launch` | R2 | 白名单 → 开始菜单 → App Paths 解析，`shell.openPath` |
| `desk_click` | R2 | **优先 `element_id`**（点矩形中心），无清单才用 x/y |
| `desk_key` / `desk_type` / `desk_scroll` | R2 / R2 / R1 | VK 白名单；>30 字走剪贴板并恢复 |
| `desk_system_control` | R1 | 音量/静音/媒体键/勿扰（不碰界面） |
| `desk_file_reveal` / `desk_file_open` | R1 / R3 | 定位 / 打开（拒绝 exe/bat/ps1 等可执行扩展名） |
| `desk_clipboard_get` / `desk_clipboard_set` | R2 / R1 | 读需确认（隐私） |

## 五、安全模型

### 5.0 两道开关（「言出法随」必须两把钥匙）

| 层级 | 位置 | 默认 | 作用 |
|---|---|---|---|
| 机器级 | 关于页「电脑能力」（`capability-settings.json.enabled`） | 关 | 客户端是否拉起 actor 并把 `desk_*` 注册到云端 |
| 角色级 | 角色聊天设置「允许 TA 操控我的电脑」（`character.settings.desktopEnabled`） | **关** | 这个角色能不能拿到 `desk_*` 工具 |

两者是「与」：`toolFlags.computer = 桥在线 && 角色 desktopEnabled`。
老角色没有这个键 → 一律按关处理，升级不会让任何角色突然获得操控电脑的权限。
排查「为什么 TA 不动手」：后端 debug 日志有一行
`ChatTurn toolFlags: scene=… desktopEnabled=… flags={… computer=…}`。

1. **四级危险**：R0 只读 / R1 可逆 / R2 有副作用 / R3 不可逆，R3 每次必确认（免确认开关不可跳过）；
2. **上下文升级**：往终端里 `type/key` → R3；点击文案命中「删除/支付/发送…」→ R3；
3. **来源护栏**：每次调用必填 `evidence`，`R3 + screen_content` 在 Python 与 Java 各拦一次；
4. **敏感前台**（密码管理器/银行/登录界面）：不截图、不注入，直接拒；
5. **确认 fail-safe**：45s 超时或无窗口可问 = 拒绝；弹窗显示人话描述（`describeAction`）；
6. **审计**：`agent_capability_audit`（Flyway V55）记 step/danger/evidence/confirmed/耗时，
   参数只存 `sha256` 前 16 位；写库失败只 WARN，绝不阻断动作。

## 六、预算与出口

- 单任务：步数 ≤6 / 视觉帧 ≤4 / 墙钟 ≤90s / 同工具连续失败 ≤2；
- 单步：能力自带 3–9s 超时，Java 侧 `step-timeout-ms: 20000` 兜底；
- Esc：立刻杀 actor（下次动作自动重建）+ 收起指示条；
- 幂等：`stepId = {session}#{n}`，Java/Electron 双侧 60s 去重 —— **网络重试不会产生第二次点击**。

## 七、这次删掉了什么

| 删除 | 说明 |
|---|---|
| `frontend/electron/mcp/`（mcpHost / mcpClient / engineRelease / engineDownloader / mcpSettings） | 子进程、JSON-RPC、200MB 分发、崩溃重启、25s 盲等 |
| `scripts/_upload_agent_engine.py`、`release_assets.json#agentEngine`、nginx 的 4 条 AgentEngine/agent-latest 路由 | 发版链路不再分发引擎 |
| `ClientBridgeToolCallback`、`ToolManager.resolveToolCallbacks`、`AgentBridgeService.dispatch` | Spring AI ToolCallback 装配（schema 归 Python） |
| `EngineCredentialsService` + `/api/agent-bridge/engine-credentials` | 只在 spawn 引擎时用 |
| `mcp-settings.json`（v1） | 迁移为 `capability-settings.json`（v2），旧文件打 `_migrated` 留档 |
| `application.yml` 的 `call-timeout-ms: 630000` / `session-wait-ms` | 改为 `step-timeout-ms: 20000` |
| AboutPage 的 MCP/引擎面板、4 语 `about.mcp*` 文案 | 换成「电脑能力」单开关 |

## 八、验证与已知缺口

已绿：`pytest`（1273）、`mvn test`（BUILD SUCCESS）、`vitest`、`vite build`、
`check-java-ai-free -Mode strict`（0/0/0）、`check_release_asset_contract.py`。
actor 在真机验证：`foreground`/`windows` 正确；UIA 收割冷 759ms / **热 14ms**。

已知缺口（有意留）：

1. **未做真机端到端**：需要跑起 backend + 客户端并打开开关，才能验收「打开记事本」这类场景；
2. `desk_ocr` / `desk_now_playing`（WinRT）与流式 usage 归因未做，理由见 P3 记录；
3. 确认弹窗目前只有人话描述，**没有目标区域局部放大截图**；
4. 音量绝对值是 ±2% 精度（VK 步进），不是 CoreAudio 精确设定。
