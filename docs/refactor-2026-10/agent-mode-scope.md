# AGENT 模式到底替换了什么（代码追踪）

> **历史文档（2026-10-18 快照）**：OFF/SHADOW 已于 2026-10-19 退役，
> 当前 Java 已无模型调用与 prompt 组装；本文保留当时的追踪结论用于对照。
> 最新状态见 [`off-retirement-plan.md`](./off-retirement-plan.md)。

> 结论先行：`LIANYU_AGENT_MODE=AGENT` **只替换「模型调用」这一步**。
> 提示词组装、记忆检索、关系/情绪注入、落库、通知、多气泡切分**全部仍在 Java**。

这份追踪是 P6 的前提：不知道「AGENT 模式还剩什么在 Java」，就无法判断哪些类能退役。

## 单聊一次消息在 AGENT 模式下的实际执行

`ChatTurnFacade.invokeStream`（`lianyu-service/.../graph/ChatTurnFacade.java:73`）：

```
73  invokeStream(command, callback)
74      OverAllState state = runAssemble(command);        ← Java 图仍在跑
        └─ chatTurnGraph.invoke(...)                     ← ChatTurnGraph
             ├─ ASSEMBLE_PROMPT   （人设 + 记忆检索 + 关系/情绪注入）
             └─ ASSEMBLE_MESSAGES （历史取窗 + 工具上下文）
82      turn = new ChatTurnState(state)
83      characterRef / MESSAGE_DTOS                       ← 从图状态取
86      InvokeModelNode.buildRequest(...)                 ← 组装 AiChatRequest
87      hasImage ?
88        → agentImagePort.streamIfActive(...)            ← 【Python】识图+生成
99        → visionChatService.chatImageStream(...)        ← 回退 Java
    else
        → agentStreamPort.streamIfActive(...)            ← 【Python】流式生成
        → aiChatService.chatStream(...)                   ← 回退 Java
```

### 仍在 Java 执行的步骤

| 步骤 | 组件 | 说明 |
|---|---|---|
| 提示词组装 | `ChatTurnPromptAssembler` | 人设、场景、群聊附加段 |
| **记忆检索** | `MemoryRetriever` + `MemoryCacheService` + `MemoryVectorStore` | 检索结果注入 system prompt |
| 关系/情绪注入 | `RelationshipService` 等（在 assemble 节点内） | |
| 历史取窗 | `ASSEMBLE_MESSAGES` 节点 | 含图片消息占位文案 |
| vault 解析 | `ApiKeyVaultService` | 解密 API Key 后传给 Python |
| 落库 + 通知 | `ConversationService` | 消息、token 统计、DIARY/推送 |
| 多气泡切分 | `AssistantReplySplitter`（`beforeStreamComplete`） | pieces 由 Java 发 |
| 收尾 | `SseChatStreamHelper` | `[DONE]` 与错误帧 |

### 真正由 Python 执行的

| 步骤 | Python 侧 |
|---|---|
| 文本流式生成 | `stream_chat.py` + `ModelGateway`（含熔断/隔舱/语言门） |
| 图片识图 + 生成 | `image_chat.py`（两段式） |
| 工具循环 | `tool_loop.py`（显式 3 轮上限） |

## 这意味着 P6 的哪些判断要修正

| 原计划 | 修正 |
|---|---|
| P6-d：退役 `MemoryRetriever` / `MemoryVectorStore` / `MemoryCacheService` | ❌ **前提不成立**：它们在 AGENT 模式下仍在用（prompt 注入走 Java） |
| 退役 `ChatTurnPromptAssembler` | ❌ 同上 |
| 退役 `AiChatService.chatStream` / `VisionChatService` | 🟡 仅当 OFF 模式移除后；它们现在是回退路径 |

**真正的 P6-d 前置条件**：把「提示词组装 + 记忆注入」也迁到 Python。
那是一次独立的大改动（涉及 `ChatTurnPromptAssembler` 的 8 个 prompt 段落、
关系/情绪数据读取），不在当前已完成范围内。

## 由此得出的 P6 可行边界（截至本轮）

| 阶段 | 可删 | 前置 |
|---|---|---|
| P6-a | ✅ 已删 `MemoryOutboxRelay` | Python relay 独占投影 |
| P6-b | `MemoryMilvusSyncService` | Python 已有 `reconcile`；等 OFF 退役 |
| P6-c | `MemoryExtractionService` / `MemoryLlmExtractor` / `MemoryExtractionHeuristics` / `MemoryOutboxService` | OFF 退役（它们仍在 OFF 路径上） |
| P6-d | `MemoryRetriever` / `ChatTurnPromptAssembler` 等 | **提示词组装迁 Python**（未开始） |
| P6-e | `MemoryConsumer` | ❌ 不可迁（凭据解密依赖主密钥，见上一节） |

## 对下一步的建议

在「提示词组装迁 Python」之前，继续删 Java 记忆类都会破坏 AGENT 模式本身
（不只是 OFF）。所以优先级应该是：

1. SHADOW 跑数据 → 切 AGENT（已备好漂移率指标）
2. **提示词组装迁 Python**（新的 P5.5 阶段，规模接近已完成的 P4）
3. 之后 P6-b / P6-c / P6-d 才有意义

这份判断修正了总纲 §6 里对 P6 的乐观估计：P6 不是「删代码」，而是
「先把 Java 还在做的那一半也迁走，再删」。
