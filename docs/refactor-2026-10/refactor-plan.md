# 予念重构总纲

> 本文是予念（YuNian）PC 端架构重构的**唯一规划文档**。取代并被以下两份历史文档的内容吸收：
> `docs/refactor-plan-coupling.md`（2026-09 耦合审查，19 项已归档 13 项）、
> `docs/refactor-2026-10/rewrite-options.md`（语言级重写方案对比，决策已定稿）。
>
> 配套证据台账：[`audit-findings.md`](./audit-findings.md)（每条结论带 `path:line`，不在本文重复）。
>
> **维护规则**：每完成一批回写 §5；决策变了改 §4 并记日期；路线变了改 §6。文档与代码冲突时以代码为准，并当场改本文。

---

## 1. 我们要解决什么问题

三个层面的问题，**只有第一个是语言层面的**：

| 层 | 问题 | 证据 |
|---|---|---|
| **技术栈** | Spring AI Alibaba Graph 官方停更约 7 个月；agent 框架生态已换代（LangGraph 成事实标准，checkpoint/interrupt/条件边是它的原生能力） | 依赖版本 `backend/pom.xml:36` |
| **架构** | 三处「隐形第二实现」：Prompt 组装有两条路径（Graph 内 + Facade 重载后门，5 处走后者）、SSE 解析有三份且语义已分叉、模型网关没有接口（`VisionChatService` 直调 `AiChatService` 8 个 public 方法） | 见台账 A1/A2/B1/E1 |
| **工程** | 曾经零 CI、22 个「自证式」测试、零组件测试；行为保真只能靠手测 | 台账 E8/E9、已建 `.github/workflows/ci.yml` |

**结论**：这不是一坨需要推倒的代码（19 项历史重构已完成 13 项、事务红线有 6 处自觉注释、Electron 安全基线是教科书级），而是**分层壳子建好了、边界全是靠约定维持的**。重构目标是「把约定变成类型 / 让每件事只有一个实现」。

---

## 2. 当前架构（as-is）

### 2.1 规模

| 区块 | 文件 | 行数 | 备注 |
|---|---|---|---|
| 后端 Java | 465 | 38,333 | 9 个 Maven 模块；37 张表；52 个 Flyway 迁移 |
| 前端 `src` | 215 | 35,312 | Vue 3.5 + Electron 42；22 条路由 |
| Electron 主进程 | 41 | 11,278 | `main.js` 单文件 3,319 行 |
| **backend-agent（新）** | 17 | 968 | FastAPI + LangGraph；8 个 prompt 资源；6 个测试文件 / 37 用例 |
| 管理端 `admin/` | 19 | — | 独立 Electron + Element Plus |

### 2.2 运行时拓扑

```
桌面客户端 Electron                ┌──────────── api-gateway (Nginx TLS) ────────────┐
 main / quick / launcher  ────────►│ :443  /api/**  → backend:8080                 │
                                   │       /ws      → backend:8080                 │
                                   │       /api/agent/** → agent:8001  ← 新        │
                                   │ :4443 更新资产 → minio:9000                   │
                                   └───────────────────────────────────────────────┘
                                                    │
        ┌───────────────┬───────────────┬───────────┴────┬──────────────┐
     backend         agent(新)          asr            mysql/redis    milvus/minio
   Spring Boot      FastAPI                          rabbitmq        （有状态）
```

`agent` 仅内网（无对外端口映射），经 `api-gateway` 分流；`ship-release.ps1 -BackendOnly`
重建清单已含 agent，发版语义未变。

### 2.3 后端模块依赖（DAG，无环）

```
lianyu-app → lianyu-web → lianyu-service → lianyu-ai / lianyu-dao / lianyu-security / lianyu-storage
lianyu-app → lianyu-admin（只依赖 dao/security/storage/ai）
```
`lianyu-service` 承载 83% 代码量（19 个包 / 233 文件），是复杂度的全部所在。
`lianyu-ai` 名不符实——不含任何 AI 调用，只是状态键契约 + 出站客户端工厂。

### 2.4 一轮单聊的真实链路

```
ChatPage → fetch SSE → ConversationController → ConversationService.sendMessageStream
  → ChatTurnFacade.invokeStream → CompiledGraph("chatTurn")
      assemblePrompt  ：人设/记忆/关系/摘要/时间/城市/晚安/群聊/口语风格 共 20 层
      assembleMessages：32 条窗口 + 图片历史改写
      invokeModel     ：AiChatService 或 VisionChatService → 上游 LLM（Resilience4j 按上游熔断）
  → 拆气泡 / 清洗 / 内心独白过滤 / seq 落库 / 关系+情绪更新 / 摘要入 MQ
  → SSE 逐字回吐
```

Graph 只有 3 个节点、直线、无分支——**它承载的是「组装」，不是「决策」**。

---

## 3. 目标架构（to-be）

### 3.1 总策略：Strangler（分层蚕食，不推倒）

```
第 1 层  Agent 编排（产品灵魂，全部结构债所在）→ 迁 Python + LangGraph
第 2 层  CRUD / 社区 / 管理端 / 文件代理（68% 代码，与 agent 无关）→ 暂留 Java，按域逐个迁或永久保留
```

理由与备选方案对比见 §4 D1/D2。

### 3.2 Python 侧目标分层

```
backend-agent/app/
├── config.py            pydantic-settings，全部配置单一绑定（禁止散落 @Value 式读取）
├── http_client.py       出站唯一构造入口，显式超时（connect15/read60/write30）
├── gateway.py           ModelGateway —— 模型调用唯一出口
├── resilience.py        ← 待建：按上游熔断 / 并发隔舱 / 瞬断重试
├── language_gate.py     ← 待建：输出语言校验 + 有界重生成
├── prompts/             prompt 资源（现已 8 个；loader 带占位符 fail-fast）
├── graphs/              LangGraph 状态图（现已 ProactiveTurn；后续 SingleTurn/GroupTurn）
├── tools/               ← 待建：工具循环（有界 recursion_limit）
├── memory/              ← 待建（阶段 P5）：检索/抽取/outbox 双写
└── vision.py / desktop_observe.py   视觉两段式 + 桌面感知
```

### 3.3 接缝设计（已落地）

| 接缝 | 实现 | 约束 |
|---|---|---|
| `AgentRuntimePort` | service 层定义接口，web 层 `DesktopObserveOrchestrator` 实现，无 web 时回落 `NoopAgentRuntimePort` | 依赖方向不变：service 不依赖 web |
| 三模式开关 | `LIANYU_AGENT_MODE=OFF\|SHADOW\|AGENT`（默认 OFF） | 任何 agent 失败**静默回退 Java 原链路**，绝不阻塞用户 |
| 职责切分 | Java：vault 解析、人设组装、历史取窗、落库、通知；Python：装配 + 模型调用 | **Python 不碰 MySQL/Redis**（阶段 P5 前），事务红线天然不破 |

### 3.4 三模式语义

| 模式 | 行为 | 用途 |
|---|---|---|
| `OFF` | 纯 Java，与历史版本逐字节一致 | 默认；出问题立刻退回 |
| `SHADOW` | Java 出结果给用户 + Python 同请求影子跑，记 diff | 观察期，统计漂移率 |
| `AGENT` | Python 出结果；为空/失败回退 Java | 切流 |

---

## 4. 决策记录

**D1 · 不做全量重写，选 Strangler**（2026-10）
68% 后端代码（`square`/`character`/`community`/`moments`/`admin`）的问题是分层问题不是语言问题，换语言不解决；全量重写要在无 E2E 测试的前提下同时保真 37 张表、128 端点、20 层 prompt 顺序、13 条行为资产 —— 单人高概率烂尾。

**D2 · agent 层选 Python + LangGraph，不选 Rust**（2026-10）
LangGraph 的 checkpoint / interrupt / `recursion_limit` / 条件边，正好是审计 A3/A4 的直接解药；Rust 生态（Rig 等）仍在 0.x，缺这些原语。**Rust 的主场是 AgentEngine / 桌宠常驻进程**（内存小、单 exe），留待后续评估，不是本层。
「热更新/运行时拼装」的真正红利是 **prompt 资源化 + 配置外置**，与语言无关，已在 P1 落地。

**D3 · 接缝用端口接口，不让 service 依赖 web**（2026-10）
沿用项目已有 `VoiceEventSink` 先例：service 定义 `AgentRuntimePort`，web 实现。

**D4 · prompt 资源化 + 快照测试作为行为保真手段**（2026-10）
8 个 Java 内联任务块搬成 `app/prompts/*.md`，逐字移植；测试断言与 Java 原文逐字相等。
**改任一侧必须同步另一侧**，否则快照测试红。

**D5 · 用影子流量替代缺失的 E2E 测试**（2026-10）
在零集成测试的现状下，`SHADOW` 模式的双跑 diff 是唯一可量化的保真依据。LLM 输出天然非确定，diff 只记 WARN 供统计，不当作事故。

**D6 · 不把朋友圈/日记接到 Python，直到网关对等层就绪**（2026-10 修正，**替换原计划的 2b**）
原计划阶段 2b 要把朋友圈发帖/评论 + 日记也接入 Python 图。实际评估后否决：
Python 侧目前**没有** Resilience4j 等价物（按上游熔断/隔舱）、**没有**语言门、**没有**工具循环。
此时接线会让 `AGENT` 模式相对 Java 路径**降级**（丢工具、丢语言门、丢熔断）。
→ 改为先建 `resilience.py` + `language_gate.py` + 工具循环（P1/P2），再接线（P3）。

**D7 · Python 在 P5 前不直连数据库**（2026-10）
避免两套数据访问栈并行；也让「事务红线」在迁移期自动成立。记忆子系统的 outbox 化放到 P5。

**D8 · 社区功能整站下线，动态接替其导航主位**（2026-10-17，产品变更）
产品收敛为「私域陪伴」，公开社区与定位冲突。执行口径：
- 后端删干净：`service/community` 整包、Controller、7 个 DTO、3 entity、3 mapper、4 测试、
  2 个 MQ 队列、3 个通知方法、`communityPushEnabled` 设置项、`uploadCommunityImage`、
  `moderateCommunityContent`、`/users/{id}/community-posts` 端点
- 前端删干净：`CommunityPage`、`api/community`、`communityShareDraft`、`CommunityPostCard`、
  分享到社区整套（选消息/截图/上传/跳转）、社区动态列表、`COMMUNITY_*` 通知处理、四语种文案
- 导航：`AppDock` 右主位 community → **moments**（动态从滚轮菜单提到主位）；`AppSidebar` 同理
- 保留并移位：`CharacterShowcase`（角色橱窗）、`ProfilePostList`（通用列表容器）→ `components/`
- 数据库：`V53__drop_community.sql` 删三张表并清理 `COMMUNITY_*` 站内通知
  - **一次性不可逆操作**，迁移文件头已写备份命令；上线前应先导出
- 「分享聊天截图」随社区一并移除（它是社区投稿能力）；如日后需要，可基于
  `moments` 增加图片投稿后重做，无需恢复社区

**D9 · `write_diary` 暂不在 Python 侧实现**（2026-10-17）
写日记要入 RabbitMQ（`ai.background`），属**写路径**；Python 侧要保持「只读」（D7）。
两条路都不好：复制 Java 的 MQ 消息契约（`AiBackgroundTask` 跨语言对齐，格式一漂就静默失败）、
或新增 Java 内部端点 + 共享密钥 + nginx 封堵（引入新的攻击面）。
→ 决定：AGENT 模式下主动发言链路**不声明** `write_diary`（Java 侧 tools 清单里已排除），
模型自然不会调用；即兴日记能力继续由 Java 链路承担。待 P5（记忆写路径 outbox 化）
时一并解决：届时要么给 agent 一个受控写通道，要么把日记触发留在 Java。

---

## 5. 已完成（进度台账）

| 批次 | 内容 | commit |
|---|---|---|
| **P0 止血** | prod profile 真正生效（`${SPRING_PROFILES_ACTIVE:dev}` + compose 注入）；语音 WS token 改首帧认证；事务红线三修（MinIO 出事务 / `StpUtil.login` 出事务 / 通知 `afterCommit`）；admin 异常 400/403/401 分流；`@Async` 专属池；`SupportGrant.redeem` 补审计；前端 `syncToken` 未定义修复；CI 建档 | `b6047dd` |
| **P1 agent 骨架** | `backend-agent/`（FastAPI）、`ModelGateway` 唯一出口、出站超时红线、桌面感知两段式（VL JSON → 问候） | `26da01f` |
| **P1 Java 适配** | 三模式编排器、`/api/agent/` nginx 分流、compose `agent` 服务 | `eba202e` |
| **P1 SSE 单源** | 3 份解析器 → `frontend/shared/sseParser.js`（修正 wechatBridge 不跳心跳行的分叉） | `2f6be26` |
| **P1 容器实测** | Dockerfile 换官方镜像（ghcr 不可达）、nginx rewrite 去 `/api` 前缀（404 根因） | `18d1091` |
| **P2a 主动发言族** | prompt 资源化 8 文件 + LangGraph `ProactiveTurn` 图 + `AgentRuntimePort` 依赖倒置 + **4 条链路接入**（定时主动/冷启动/破冰跟进/城市变更） | `410165f`、`eedbe3b` |
| 收尾 | compose 模式开关、文档回写 | `4de4d0f`、`876abac` |
| **总纲定稿** | 新总纲取代 `refactor-plan-coupling.md` 与 `rewrite-options.md`；顺带修正 7 处悬空文档引用 | `093b7a1` |
| **社区下线** | 后端整包 + 队列 + 通知 + 设置项 + 端点；前端页面/api/组件/分享流/通知/i18n 全删；动态接替 dock 主位；`V53` 删三表 | `74552b2` |
| **P1 网关对等层** | `resilience.py`（按上游熔断/隔舱/瞬断重试）+ `language_gate.py`（4 语种检测、有界重生成、永不强制英文）；语言门做成图内有界环 | `a2d4cfb` |
| **P2 工具循环** | `tools/`（time/weather，声明式能力清单）+ `tool_loop.py`（显式 3 轮上限，A4 解药）+ `/agent/capabilities` 能力端点 | `3d44876` |
| **A 批 数据访问** | `db.py`（SELECT 守卫的只读层）+ `memory_search`（Java 降级分支语义）+ `get_my_recent_life`（7 天窗口/4 语种/缓存）；characterId+tools 贯穿端口 | `2e1c832` |
| **P3 准备** | 三模式行为测试 10 个（OFF/SHADOW/AGENT 分派 + AGENT 空结果必回退 Java + 桌面感知三模式）；`scripts/agent-shadow-report.ps1` 漂移率统计 | `fb8fbf6` |
| **P4a 流式端点** | Python `/agent/chat-stream`（SSE 契约逐字节对齐、不发 pieces、语言门发 replace）+ `gateway.stream_text`（首字前瞬断重试）+ 8 个契约测试；修 wire 字段名与 usage 透传 | 见提交 |
| **P4b 消费端** | Java `AgentSseParser`（纯函数，13 用例）+ `AgentStreamBridge`（增量中继）+ `AgentStreamPort` 接线 `ChatTurnFacade` | `c4a4231`、`3af7755` |
| **P5 读路径** | `memory/embedding.py`（向量化 + rerank，base_url 可配置）+ `memory/vector_cache.py`（Redis 缓存向量 + 进程内余弦，Milvus 缺失时的真实语义召回）+ 三级召回 | `d35c459` |
| **审计 C3 修复** | Java `RerankerService` 重排地址改为可配置（原为硬编码 DashScope + 用户 vault key，非 DashScope 用户必然 401 后静默降级成原序） | `2ffec9a` |
| **记忆 outbox** | `memory_outbox` 表 + relay（幂等/指数退避/死信）+ 写入改事务内入队；真实 Milvus 端到端验证 | `9586f9c` |
| **Python 接管记忆写** | 白名单写层（只允许 INSERT 两张表）+ writer（source_hash 对齐 Java）+ `/agent/memory/write`；跨语言契约 bug（PREFERENCE 不在 Java 枚举）修复 | `e721a05` |
| **3 条非 Graph 链路下沉** | 通用文本端口 `AgentTextPort` + `/agent/text/complete`（支持 history）：日记、朋友圈发帖、朋友圈评论全部接通，任何 null/异常回退 Java | `1d681b4`、`f948b65` |

**容器级实测结论**：`lianyu-agent` healthy；backend 日志确认 `profile "prod"` 激活；
gateway TLS → `/api/agent/` → `{"status":"UP"}`；proactive-turn 无凭据 → `no_text_credentials`、user mismatch → 403。

**基线验证**：`mvn -B test` BUILD SUCCESS（service 274 + web 10 + 全模块 0 失败）；
`vitest` 76 文件 453 用例；`pytest` 37 用例。

---

## 6. 路线图

排序原则：**先补对等能力，再接链路**；每步独立可上线、可回滚。

### 主线（agent 迁移）

| 阶段 | 内容 | 退出条件 |
|---|---|---|
| **P1 · 网关对等层**（当前） | `resilience.py`（按 provider\|baseUrl 隔离熔断、前台/后台隔舱、瞬断重试）+ `language_gate.py`（4 语种检测 + 最多 2 次重生成，逐字移植 Java） | 单测覆盖：5xx 重试、熔断打开/半开、语言不符重生成、业务错不计失败 |
| **P2 · 工具循环** | `tools/` + 图内有界 `recursion_limit`；先做只读工具（time/weather），依赖库的工具等 P5 | 工具轮次超限被截断；与 Java `ToolManager` 行为对照 |
| **P3 · 灰度切流** | 主动发言族 4 条：`OFF → SHADOW`（观察 diff）→ `AGENT`；**同时**把朋友圈/日记接进来（此时才允许，见 D6） | 漂移率可解释；回退演练通过 |
| **P4 · 单聊流式** | Python `/agent/chat-stream` + Java `AgentStreamBridge`（增量中继）+ `AgentStreamPort` 接线到 `ChatTurnFacade` | ✅ 代码完成：AGENT 模式纯文本流走 Python，图片仍走 Java，任何异常/null 回落 Java；**待真实链路验证**（需本地 vault 解密后实跑） |
| **P5 · 记忆子系统** | 读路径（检索/rerank）先迁；写路径改 outbox（MySQL 为准，Milvus 异步投影），修台账 C1/C2 | 双写失败可自愈；命中率可观测 |
| **P6 · Java 逐域退役** | square → community → moments → admin；或永久保留（按届时成本决定） | java 容器下线或明确保留理由 |

### 并行轨道（不阻塞主线，按需插入）

| 轨道 | 内容 | 来源 |
|---|---|---|
| 前端结构 | `ChatPage.vue` 2,209 行拆 5 个 composable；18/22 页面穿透 api 层收敛到 store | 台账 E5/E6 |
| Electron | `main.js` 3,319 行拆 `app/windows/ipc/security/tray`；建 `shared/channels.js` 通道常量表（77 handle + 24 push 现全靠字符串） | 台账 E3/E4 |
| i18n | 8 个页面零 `t()` 调用（LandingPage 1,366 行 0 个） | 台账 E10 |
| 死代码 | `zh-TW.js`(517) / `AppSidebar.vue`(208) / `api/index.js` / `stores/layout.js` / `_run-release.ps1` / `vitest.e2e.config.js` | 台账 E7 |
| 测试真实性 | 改写或删除 22 个自证式测试 + 8 个源码文本断言；引入 jsdom 组件测试 | 台账 E8/E9 |
| 基建 | 版本 5 个真相源收敛；52 个脚本硬编码服务器 IP 抽 `scripts/_lib/ssh.py`；可观测性最小闭环（Prometheus + 结构化日志） | 台账 F1/F2/F4 |

### 从旧计划继承的未完成项

| 项 | 内容 | 处置 |
|---|---|---|
| 原 8 | Electron `main.js` IPC 拆分 | 并入「Electron」轨道 |
| 原 9 | `ChatPage.vue` 拆 composables | 并入「前端结构」轨道 |
| 原 14 | landing/auth ~72 处硬编码 hex 令牌化 + 8 页面补 i18n | 并入「i18n」轨道 |
| 原 16 | `GroupChatService`（1,207 行）拆分 | 等 P4 单聊落定后一起做（群聊与单聊共用编排） |
| 原 17 | 桌宠四点注册收敛 | HOLD，下一个新桌宠立项时做 |
| 原 18 | 脆弱脚本（跨项目 icon 路径 / 硬编码版本 / blockmap 空格补丁） | 并入「基建」轨道 |
| 原 19 | nginx 按文件名正则白名单 → 目录级白名单 | 与更新链改造合并评估 |

---

## 7. 验收与灰度（Runbook）

### 7.1 三套件（每次提交前）

```powershell
cd backend;       mvn -B test                 # 期望 BUILD SUCCESS
cd backend-agent; uv run pytest               # 期望 37+ passed
cd frontend;      npx vitest run              # 期望 76 files / 453+ tests
```

### 7.2 本地容器验证

```powershell
docker compose up -d --build agent backend    # 只重建变更侧
docker ps --filter name=lianyu --format "{{.Names}} {{.Status}}"   # agent 应 healthy
docker logs lianyu-backend | Select-String "profile is active"     # 应为 prod
```

### 7.3 灰度切换

```powershell
# .env
LIANYU_AGENT_MODE=SHADOW      # 观察期
LIANYU_AGENT_MODE=AGENT       # 切流
LIANYU_AGENT_MODE=OFF         # 回滚（一步到位）
```

观察期看两类日志统计漂移率：
- `Shadow observe[userId]: match|DIFF`
- `Proactive shadow[userId][scene]: match|DIFF`

一键统计（自动读 backend 容器日志、算漂移率、打印 DIFF 样例）：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/agent-shadow-report.ps1
# 自定义窗口与样例数
powershell -ExecutionPolicy Bypass -File scripts/agent-shadow-report.ps1 -Tail 20000 -Samples 10
```

> 判读口径：漂移率 = DIFF /（match + DIFF），**不含 both-null**。
> 30% 以下视为可接受（LLM 输出天然非确定），70% 以上应排查（多半是凭据/模型/提示词不一致）。
> 注意：DIFF 只比较长度与空值，不看语义——定位具体差异需同时看 `docker logs lianyu-agent`。

### 7.4 发版

唯一入口不变：`.\local\ship-release.ps1 -BackendOnly`（agent 已并入重建清单）/
`-ElectronOnly`（前端/Electron 改动）/ 无参数（两侧都改）。

---

## 8. 红线（不因重构而松动）

1. **事务红线**：慢调用（LLM/HTTP/ASR/TTS/MinIO/Milvus）不进 `@Transactional`。
   Python 侧等价物：SQLAlchemy async session 内不得 await 外部服务。
2. **出站超时红线**：所有出站客户端显式带超时，禁止零超时（2026-08-07 全站 499/504 事故教训）。
   Python：只许用 `app/http_client.new_client()`。
3. **发版红线**：`local/ship-release.ps1` 唯一入口，参数语义不变。
4. **设计红线**：`DESIGN.md` 令牌唯一真相源（不许新色 / 不许 `border-radius:0` / 不许 linear 缓动 / 深度靠玻璃）。
5. **数据红线**：Flyway 只增不改，V30 占位禁复用；37 张表结构在 P6 前不动。
6. **客户端红线**：Electron 安全基线（contextIsolation / 证书 pin / CSP / fuses / `assertTrustedSender`）是资产不是债。

---

## 9. 已知缺口与风险

| # | 缺口 | 影响 | 处置 |
|---|---|---|---|
| 1 | Python 工具集仍缺两个 | `write_diary`（需 RabbitMQ 入队）、`computer_task`（需 STOMP 桌宠桥）尚未实现；其余 4 个已就绪 | `/agent/capabilities` 已诚实上报 `missingTools`；依赖这两个工具的链路**不得**路由到 Python |
| 1b | `memory_search` 精度低于 Java | Python 走 MySQL 降级分支（重要性排序 + LIKE 提升），没有 Milvus 向量召回与 rerank | 语义与 Java fallback 一致；Milvus 接入留 P5，届时只换 `search_semantic` 内部实现 |
| 2 | ~~Python 无语言门~~ | 已补齐（P1）：4 语种检测 + 最多 2 次重生成 + 永不强制英文，图内有界环 | ✅ `a2d4cfb` |
| 2b | ~~Python 不能读数据~~ | 已补齐（A 批）：只读 MySQL/Redis + SELECT 守卫 | ✅ `2e1c832` |
| 3 | Python 无数据库访问 | 记忆/关系/情绪仍在 Java 组装 persona | 有意为之（D7），P5 再议 |
| 4 | 影子 diff 只是「长度对比」 | 漂移率统计粗糙，不能定位语义差异 | P3 前加可选的文本相似度指标 |
| 5 | 本机宿主 443 可能被加速器占用 | 宿主机 curl 打不到 gateway（容器内不受影响） | 已在 §7.2 用容器内验证规避 |
| 6 | CI 刚建，从未在 GitHub 上跑过 | 首次 push 后可能因环境差异失败 | 推上去后确认绿；`continue-on-error` 的 OSV 步骤允许失败 |
| 7 | 历史审计曾有误报 | 例：P0-2 曾误判「SupportGrantController 无授权」，实际授权在 Service 层完整 | 台账已更正；后续结论一律先复读代码再动手 |

---

## 10. 术语

| 术语 | 含义 |
|---|---|
| Strangler | 绞杀者模式：新实现在旧系统旁并行生长，逐块替换，旧系统逐步萎缩 |
| SHADOW | 影子模式：Java 出结果给用户，Python 同请求跑一遍只记日志 |
| 行为保真 | 迁移后用户可感知行为（措辞、节奏、语言、参数）不变 |
| 行为资产 | 重写时必须原样保留的 13 条（见台账末节，如 20 层 prompt 顺序、主动节律参数） |

---

## 11. 历史归档（pre-2026-10，已完成部分）

以下为 2026-09 耦合审查 19 项中已完成者，仅作追溯（原文件已并入本文）：

| 项 | 结果 | commit |
|---|---|---|
| 1 更新资产命名契约 | 新增 `scripts/release_assets.json` 单一真相源 + 校验脚本 | `82d2af1` |
| 2 ASR base-url 漂移 | yml 默认值改 `http://asr:8080` | `82d2af1` |
| 3 nginx `/ops` 硬编码 | `OPS_DASHBOARD_UPSTREAM` 经 compose 注入 | `82d2af1` |
| 4 `AiChatService` 拆分 | 2059 → 968 行，拆出 6 个协作组件 | `6d8962d` |
| 5 `ConversationService` 瘦身 | 1468 → 814 行；顺带修好 `persistX` 事务失效 | `2c4d0d0` |
| 6 admin 依赖清理 | 删冗余 dao 依赖（`SsrfPinningClientFactory` 不下沉，理由已记） | `0a59c76` |
| 7 重复逻辑下沉 | `TextUtils.trimToNull` 下沉 common | `0a59c76` |
| 11 越层 Controller 下沉 | Memory/CharacterState 查询下沉 service | `9a33fcd` |
| 12 事务内发通知 | 改 `afterCommit` | `9a33fcd` |
| 13 Vault 事务加固 | 加红线注释 | `9a33fcd` |
| 15 文档配置同步 | installer `-Version` 改必填 | `a804738` |
