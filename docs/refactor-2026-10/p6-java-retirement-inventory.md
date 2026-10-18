# P6 · Java 域退役清单（可判定版）

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
| **P6-b** | `MemoryMilvusSyncService` 的自动 resync | Python 侧补「全量对账」命令 |
| **P6-c** | `MemoryExtractionService` / `MemoryLlmExtractor` / `MemoryExtractionHeuristics` / `MemoryOutboxService` | OFF 模式退役 + MQ 入口迁 Python |
| **P6-d** | `MemoryWriter` / `MemoryVectorStore` / `MemoryCacheService` / `MemoryRetriever` | 需先让 `ChatTurnGraph` 的 prompt 注入改读 Python 产出的缓存 |
| **P6-e** | `MemoryConsumer`（MQ） | Python 接 RabbitMQ（当前只有 HTTP 触发） |

## 四、为什么现在不继续删

`LIANYU_AGENT_MODE=OFF` 仍是默认值，也是唯一「不依赖任何新组件」的路径。
删掉回退路径等于取消灰度能力 —— 这与整个 strangler 策略的前提冲突。

**正确的解锁顺序**：
1. `SHADOW` 在真实流量上跑出漂移率指标（现在只有日志）
2. 切 `AGENT` 稳定运行一段时间
3. 移除 `OFF` 默认与回退分支
4. 然后本清单的 P6-b..P6-e 才能逐项执行

## 五、当前可直接做、且不破坏 OFF 的两件事

1. **给 Python 补全量对账**（`/agent/memory/reconcile`）：扫描 `memory_meta` 中
   `milvus_vec_id` 为空或指向不存在向量的行并重新投影 —— 这才是
   `MemoryMilvusSyncService` 的等价能力，补齐后 P6-b 可执行。
2. **Python 接 MQ**：把 `MemoryConsumer` 的入口迁过来，补齐后 P6-e 可执行。

两件事都不需要动 OFF 路径，属于纯增量。
