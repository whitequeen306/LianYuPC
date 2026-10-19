# P6 · Java 域退役清单（可判定版）

> **2026-10-19 更新**：OFF/SHADOW 已退役，本文「OFF 模式依赖」一列已失效。
> prompt 组装、记忆/关系/情绪读路径、模型调用均已迁 Python；
> 当前基线见 [`java-ai-inventory.md`](./java-ai-inventory.md)。
> 记忆写入/MQ/事务仍按本文结论保留在 Java。

> 目的：把「哪些 Java 代码可以在 Python 稳定后删除」变成可核对的清单，
> 而不是凭印象删。每一条都标注 **OFF 模式是否还依赖它** —— 只要还依赖，
> 现在就删会让 `LIANYU_AGENT_MODE=OFF` 直接坏掉。
>
> 数据采集时间：2026-10-18；基于仓库实际引用（排除测试与自身）。

## 判定口径

一个 Java 组件可以退役，必须同时满足：

1. Python 侧有等价实现且已验证（真 Key / 容器实测）
2. **OFF 模式不再依赖它**，或 OFF 模式本身已按计划移除
3. 它不是对外 API 的实现（HTTP 端点删掉会破坏客户端契约）

## 一、记忆域（13 个类）

| 类 | 职责 | OFF 模式依赖 | Python 等价 | 可退役？ |
|---|---|---|---|---|
| `MemoryWriter` | 抽取结果落库 + 去重 | ✅ 依赖（8 处引用） | `memory/writer.py` | ❌ 需 OFF 先退 |
| `MemoryExtractionService` | 规则+LLM 抽取编排 | ✅ 依赖 | `memory/extract.py` | ❌ 同上 |
| `MemoryLlmExtractor` | LLM 抽取（prompt） | ✅ 依赖 | `memory/extract.py` | ❌ 同上 |
| `MemoryExtractionHeuristics` | 规则抽取 | ✅ 依赖 | `memory/extract.py` | ❌ 同上 |
| `MemoryOutboxService` | outbox 入队 | ✅ 依赖（MemoryWriter 4 处） | `write.py` + `writer.py` | ❌ 同上 |
| `MemoryConsumer` | MQ 消费者（`@RabbitListener`） | ✅ 依赖（消息入口） | ❌ 无（Python 未接 MQ） | ❌ **Python 尚缺** |
| `MemoryQueryService` | 查询/删除（`MemoryController` 5 处） | ✅ 对外 API | 部分（只读检索） | ❌ 对外端点 |
| `MemoryRetriever` | 检索编排（8 处引用，含 prompt 注入） | ✅ 依赖 | `memory_search_tool.py` | ❌ 依赖面广 |
| `MemoryVectorStore` | Milvus 读写 | ✅ 依赖 | `milvus_store.py` | ❌ 同上 |
| `MemoryCacheService` | Redis 记忆缓存 | ✅ 依赖 | 部分（`cache_get/put_json`） | ❌ 同上 |
| `MemoryMilvusSyncService` | 启动全量 resync（手工修复） | ✅ 依赖 | ❌ 无（Python 只有增量 relay） | ❌ **Python 尚缺** |
| `CachedMemoryRow` / `ExtractedMemory` | DTO | ✅ 依赖 | — | ❌ 随上游一起退 |

**结论：记忆域现在一个类都不能删。** Python 的 relay 只做增量投影，
没有「全量对账」能力；MQ 入口也还在 Java。这两块不补齐，删任何一类都会留下盲区。

## 二、Agent 接缝（11 个类，web 层）

| 类 | 职责 | 说明 |
|---|---|---|
| `AgentRuntimeClient` | 所有对 Python 的 HTTP 出口 | 核心，不可退役 |
| `AgentStreamBridge` / `AgentSseParser` | SSE 消费与转发 | 核心 |
| `DesktopObserveOrchestrator` | 三模式分派（桌面感知 + 主动发言） | 核心 |
| `AgentStreamAdapter` / `AgentTextAdapter` / `AgentMemoryAdapter` / `AgentImageAdapter` | 各链路端口实现 | 核心 |
| `AgentWiringHealthCheck` | 启动自检 | 核心 |
| `AgentRuntimeProperties` | 模式与地址配置 | 核心 |

这些是**新架构的一部分**，不是债务。P6 的目标是删「被 Python 替代的旧路径」，
不是删接缝。

## 三、真正可退役的顺序（有前置条件）

| 阶段 | 可删内容 | 前置条件 |
|---|---|---|
| **P6-a** | ✅ 已删：`MemoryOutboxRelay` + 测试 | Python relay 独占投影（已完成） |
| **P6-b** | ✅ 已删：`MemoryMilvusSyncService` + `milvus.resync-on-startup` 配置 | Python `POST /agent/memory/reconcile` 等价（容器实测修复 2 条） |
| **P6-c** | `MemoryExtractionService` / `MemoryLlmExtractor` / `MemoryExtractionHeuristics` / `MemoryOutboxService` | OFF 模式退役 + MQ 入口迁 Python |
| **P6-d** | `MemoryWriter` / `MemoryVectorStore` / `MemoryCacheService` / `MemoryRetriever` | 需先让 `ChatTurnGraph` 的 prompt 注入改读 Python 产出的缓存 |
| **P6-e** | `MemoryConsumer`（MQ） | ❌ **不可执行（本轮结论修正）**，见下方「MQ 为何不该迁」 |

## 四、为什么现在不继续删

`LIANYU_AGENT_MODE=OFF` 仍是默认值，也是唯一「不依赖任何新组件」的路径。
删掉回退路径等于取消灰度能力 —— 这与整个 strangler 策略的前提冲突。

**正确的解锁顺序**：
1. `SHADOW` 在真实流量上跑出漂移率指标（现在只有日志）
2. 切 `AGENT` 稳定运行一段时间
3. 移除 `OFF` 默认与回退分支
4. 然后本清单的 P6-b..P6-e 才能逐项执行

## 四点五、MQ 为何不该迁到 Python（本轮实测结论）

原计划是「Python 接 RabbitMQ，然后退役 `MemoryConsumer`」。读代码后否掉了：

```java
@RabbitListener(queues = "memory.summary.queue")
public void onMemorySummaryTask(MemoryWriter.MemorySummaryTask task) { ... }
// MemorySummaryTask 只带 (conversationId, characterId, userId, provider, model)
```

**消息里没有凭据**。抽取需要调用户自己的模型，而 API Key 存在
`api_key_vault.api_key_encrypted`，由 Java 用 Jasypt（`LIANYU_MASTER_KEY`）解密 ——
Python 侧没有任何解密实现（全仓 grep 无 jasypt/decrypt）。

三条替代路都更差：

| 方案 | 问题 |
|---|---|
| Python 只做规则抽取 | 丢掉 LLM 抽取的事实，质量实质下降 |
| Python 实现 Jasypt 解密 | 主密钥要进 agent 容器，扩大泄露面；跨语言复刻加密易错 |
| Python 调 Java 内部接口取明文 Key | 新增高价值攻击面（一个内网接口能吐所有用户的 Key） |

**结论：`MemoryConsumer` 保留在 Java 是正确的边界** —— 凭据解密本就该由持有主密钥的
那一侧负责。记忆的「抽取+写入」已经由 Python 执行（Java 消费者在 AGENT 模式下
只是转发，见 `AgentMemoryAdapter`）。所以 P6 不把这一条算作待办，而是
**划为「长期保留」**。

## 四点六、P5.6 之后的前置条件变化（2026-10-18）

P5.6 把 prompt 的**组装与数据读取都搬到了 Python**，因此 P6-d 的前置条件发生实质变化：

| 组件 | 之前 | 现在 |
|---|---|---|
| 提示词组装 | Java 独占 | Python 已等价（三场景逐字节一致） |
| 记忆块数据 | Java 读 | Python 读 `memory_meta` |
| 关系块数据 | Java 读 | Python 读 `relationship_state` / `relationship_event` |
| 情绪块数据 | Java 读 | Python 读 `character_state` |
| 会话摘要 | Java 读 | Python 读 Redis `session:summary:*`（写入仍归 Java） |

**仍未解除的前置**：
1. **工具提示**：`toolManager.buildToolsPromptHint(userId)` 依赖角色的工具配置，
   Python 未读取 —— 目前由 Java 计算后传入
2. **真实流量验证**：parity 用的是固定 fixture（user 7 / character 999999），
   只证明「相同数据下等价」，不证明「线上数据下等价」。差异可能来自
   Milvus 近似检索、缓存时序、并发读写
3. **OFF 模式**：`ChatTurnGraph` 在 OFF/SHADOW 下仍使用 Java 组装，
   删任何 Java 记忆/关系/情绪类都会破坏 OFF 路径

## 五、进度与剩下的事

已完成：
1. ✅ **Python 全量对账**（`POST /agent/memory/reconcile`）—— 补齐了
   `MemoryMilvusSyncService` 的等价能力，容器实测修复 2 条缺失向量的历史记忆。
   P6-b 的前置条件已满足（等 OFF 退役后再删）。
2. ✅ **SHADOW 漂移率指标**（`AgentShadowStats`）—— 每 5 分钟打一次
   `Shadow drift[channel]: match=… diff=… both-null=… drift=…%`。
   这是从 SHADOW 走向 AGENT 的依据，此前只有逐条日志。

剩下（都需要真实流量，不是代码问题）：
- 在 SHADOW 下跑出漂移率数据 → 决定是否切 AGENT
- AGENT 稳定后移除 OFF 默认值 → 才能执行 P6-b..P6-d
