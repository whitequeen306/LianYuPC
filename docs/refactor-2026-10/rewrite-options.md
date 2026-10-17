# 语言级重写方案对比(2026-10)

> 背景:SAA(Spring AI Alibaba Graph)官方停更 ~7 个月;agent 框架生态已换代(LangGraph 成事实标准、Rust 侧 Rig/Cordis 兴起)。
> 本文只回答一个问题:**后端 + agent 编排层,换成什么**。
> 硬约束:发版仍走 `local/ship-release.ps1`;37 张 MySQL 表不动;`api_key_vault` Jasypt 密文必须能继续解密;前端 Electron 不在本次范围。

---

## 候选方案

| | ① FastAPI + LangGraph(Python) | ② Rust(Rig / 自研编排) | ③ 分层 Strangler(推荐基线) |
|---|---|---|---|
| 生态成熟度 | ★★★★★ LangGraph 文档/社区/示例最厚,Rig 也只是刚起量 | ★★ Rust LLM 栈年轻:Rig 0.x、swarms.rs 早早期;async SSE/WS 生态能用但坑多 | 按 EC 成熟度逐个选 |
| 与现有架构映射 | Graph→LangGraph 状态图几乎 1:1 节点/边/状态键 | 概念要重译:LangGraph 的 checkpoint/interrupt 在 Rust 无对应物,要自建 | — |
| **热更新/运行时拼装** | ★★★ Python 动态性天然支持 prompt 即改即生效、LangGraph 支持 runtime 图拓扑 | Cordis 式 hot-reload 对**服务端常驻进程**价值有限(见下分析) | — |
| 你的存量迁移成本 | 中:Java→Python 语义近,MyBatis→SQLAlchemy 概念直译 | **极高**:31,535 行业务 Java→Rust,重写+重测+重踩一遍并发坑 | 低→中,按模块爬 |
| 运维成本 | 与现在同量级(pip/uv + docker) | 最低内存/最快冷启动;但团队(=你)要同时当 Rust 工程师 | — |
| 128 端点×46 事务×26 Redis 前缀×37 表的移植面 | 一次到位,边迁边测 | 同左,但每一步都在打两场仗(业务迁移 + 语言学习) | — |
| 招人/AI 辅助开发友好度 | ★★★★★ | ★★★(社区小,示例少) | — |

### 关于「Cordis 热更新」的一个冷静判断

Cordis 通过 DeepSeek Harness 走红的那套 **hot-reload / 运行时拼装配置**,解决的核心痛点是「**agent 开发期的迭代速度**」——改一行 prompt 不用重启、运行时重配 graph。

但请注意你项目的实际形态:予念后端是一个**常驻服务进程**,不是本地 CLI 工具。对服务端:

- prompt 改动本来就可以放 DB/配置中心,热更新靠**数据驱动**就能拿到 90% 收益(prompt 本来就该资源化,审计 A9);
- 服务端热替换代码的收益主要在开发期,而开发期热更 Python 天然就有(uvicorn --reload),Rust 要靠 hot-lib-reloader 这类 hack,Windows 支持还很糙;
- **你的前端打包链不动**,服务端热更新对用户是零感知的。

所以「热更新」不应是选 Rust 的理由;它最多是「prompt 资源化 + 配置外置」这个本来就该做的重构的红利。真正值得为 Rust 付的代价是:**常驻桌宠进程的内存/启动**——但那属于 AgentEngine/客户端侧,不在本次后端范围。

### 关于 LangGraph 的匹配度

LangGraph 概念与你的 ChatTurn Graph 几乎逐一对应:

| 现有(SAA Graph) | LangGraph |
|---|---|
| `StateGraph` + `KeyStrategy` | `StateGraph` + `TypedDict` state schema(类型安全,编译期校验键) |
| `node_async` + 节点类 | `@node` 函数 + `Command` 返回 |
| `addEdge` 线性链 | `add_edge` / `add_conditional_edges`(**你有图无分支,A3 的直接解药**) |
| 无 checkpoint | `checkpointer`(SQLite/Postgres/Redis)——**断点恢复/human-in-the-loop 白送** |
| 无 interrupt | `interrupt()`——MCP 确认弹窗(现在 main.js:607 手搓 45s 超时)可标准化 |
| 工具循环靠框架递归无上限(A4) | LangGraph 的 ReAct loop 有显式 `recursion_limit` |
| `OverAllState` 裸 Map + 34 常量键 | TypedDict 强类型 state,键拼错 = mypy 报错 |

即:**审计里 A1-A9 的大部分结构债,LangGraph 的原语就是为消灭它们设计的。**

---

## 推荐:方案③ —— 分层 Strangler,agent 层先行

不是「全量二选一」,而是把系统拆成两类,分别处理:

```
第 1 层(agent 编排)────── 立即重写,LangGraph(Python/FastAPI)
第 2 层(CRUD/社区域/管理端)── 暂留 Java,按域逐个迁,最后退役
```

### 为什么不全量重写(① 或 ②)

- **37 张表 + 128 端点 + 46 事务边界 + 26 个 Redis key 前缀 + 52 个运维脚本**,全量重写意味着 6-10 周内你同时做:业务移植、行为保真(资产清单 13 条)、双写验证、线上共存。单人 + 无 CI 的现状下,这是高概率烂尾的形状。
- 审计显示 **68% 的后端代码与 agent 无关**(square/character/community/moments/user/notification/admin...)。这些代码的问题(D5 裸 SQL、D6 双源数据)是**分层问题,不是语言问题**——换语言一分钱不解决,只是把同样的债用新语言再写一遍。
- Spring Boot 本身没有停更,停更的只是 SAA Graph。**为 32% 的代码换掉 100% 的运行时,是赔本买卖。**

### 为什么 agent 层选 Python + LangGraph 而不是 Rust

1. **Rust agent 生态还在 0.x**:Rig 声称 20+ provider 统一 API,但 checkpoint、interrupt、streaming tool-calls、subgraph 这些 LangGraph 已经打磨两年的原语,Rust 侧要么没有要么要自建。你要的核心能力(状态图+检查点+人工确认)恰恰是 Rust 最弱的部分。
2. **你的全部增值在 prompt 工程和行为节律**,不在计算密度。语音双工那点并发(个位数会话)Python asyncio 完全接得住。
3. **审计里最大的风险面是行为保真**:20 层 prompt 顺序、场景裁剪矩阵、主动节律参数。这些迁 Python 是「翻译」,迁 Rust 是「翻译+换范式」,bug 面翻倍。
4. 以后真要 Rust,从 **AgentEngine/客户端侧**(桌宠常驻进程)切入收益最大——内存小、启动快、打包单 exe,那才是 Rust 的主场。

### 目标形态

```
backend-java/          (现 backend/,暂留,逐步瘦身)
  └─ 只剩 CRUD / 社区 / 管理端 / 文件代理 —— 最后退役
backend-agent/         (新,Python 3.12 + FastAPI + LangGraph + uv)
  ├─ graph/            LangGraph 状态图(loadContext→assemblePrompt→assembleMessages
  │                    →conditional(vision? tools?)→invokeModel),checkpointer=Redis
  ├─ gateway/          ModelGateway 协议类: vault 解析→模型路由→SSE 编排→语言门→重试
  ├─ tools/            @tool: time/weather/memory_search/recent_activity/write_diary/agent_bridge
  ├─ memory/           检索/抽取/双写(outbox 模式,C1 解药)
  ├─ prompts/          资源化 prompt: prompt/{lang}/{scene}/{slot}.md(渲染+快照测试)
  └─ share/            与 Java 侧共享的契约: SSE 事件、Redis key、Flyway SQL
nginx: /api/agent/** → python:8001 ; /api/** → java:8080
```

### 迁移步骤(Strangler,每步独立可上线、可回滚)

| 阶段 | 动作 | 验收 |
|---|---|---|
| 0 | P0-1~P0-9 先修 + CI 起来(在 Java 上) | main 绿 |
| 1 | 建 backend-agent 骨架;nginx 分流 `/api/agent/**`;**先迁 1 条最简单链路:桌面感知(observeDesktop,独立极简 prompt,不落库)** | 影子对比:同一输入新旧链路输出 diff |
| 2 | 迁冷启动/城市变更/朋友圈/日记(现绕过 Graph 的 5 处,本来就该走统一图) | 行为对比测试(资产 1/2/3 的快照) |
| 3 | 迁单聊流式(SSE 契约原样保留,资产 4);Java 侧 ConversationService 只剩落库+通知 | 双写对比 + 灰度按用户切流 |
| 4 | 迁群聊/主动/语音;memory 子系统 outbox 化 | 同上 |
| 5 | Java 侧逐域退役(square→community→moments→admin) | 最终下线 java 容器 |

**关键机制:影子公司(shadow traffic)。** 阶段 1-4 期间,Java 收到请求后异步复制一份给 Python 侧执行(不回给用户),对比输出。这是没有 E2E 测试(审计 E9)前提下唯一可靠的行为保真手段。

### 为什么这个路线仍然满足「语言级大重构」

- agent 编排层(产品的灵魂,audit A/B/C 全部债务所在)**彻底离开 Java/SAA**,换成活跃生态;
- 最终态 Java 容器下线,后端 = Python(或 Python+Rust AgentEngine);
- 用户体验零中断,37 张表零迁移,发版链(ship-release.ps1 的 `-BackendOnly` 照用,只是它拉的镜像多了一个)。

---

## 如果你仍然倾向全量重写

那就选 ①(FastAPI+LangGraph),别选 ②。理由浓缩成三句:

1. Rust 生态给不了你 checkpoint/interrupt/recursion_limit,这些恰是你 A3/A4 的直接解药;
2. 31,535 行业务 Java→Rust 的移植风险远大于→Python,而行为保真(20 层 prompt 顺序)是本次重构最大的隐性风险;
3. Rust 的真正主场是 AgentEngine/客户端常驻进程,留给下一期。

全量重写时把上面迁移步骤的 3-5 压缩成一个大阶段,前置条件不变:P0 修完 + CI 绿 + 资产行为快照测试建好,再动第一行代码。
