# Java AI 逻辑残留清单（滚动更新）

由 `scripts/check-java-ai-free.ps1` 生成/核对。目标：Java 零 AI 逻辑
（仅保留加解密、事务/落库两条 seam —— 桌面桥接会话已于 2026-10-25 随能力层删除）。

## 当前基线（2026-10-20，语音迁移第 1、2 步之后）

| 项 | 数量 |
|---|---|
| prompt 文本痕迹 | 0 处 |
| 模型调用点 | 0 处 |
| 已知 AI 类 | 0 个 |
| 已迁 Python 的客户端复活 | 0 个 |

`pwsh scripts/check-java-ai-free.ps1 -Mode strict` 通过。

## 清理结果

| 残留 | 处理 |
|---|---|
| `ToolManager.buildToolsPromptHint` | ✅ 删除；保留 `promptHintFlags` |
| `ChatTurnPromptAssembler` / `CharacterPromptBuilder` | ✅ 删除；prompt 组装只走 Python |
| `rules/hooks/*` | ✅ 删除；规则模板冻结在 `backend-agent/app/prompts/*.json` |
| `MemoryRetriever` / `MemorySearchTool` | ✅ 删除；Python 只读 MySQL/Redis |
| `RelationshipContextAssembler` / 关系 `buildPromptContext` / `CharacterStateService.buildEmotionBlock` | ✅ 删除读路径；写入仍在 Java |
| `ChatModelFactory` | ✅ 删除 |
| `VisionChatService` + 多模态解析/语言门 | ✅ 删除；识图/生成走 Python `/agent/chat-image` |
| `AiChatService.chat*` | ✅ 删除；对话走 `AgentStreamPort` / `AgentBlockingPort` |
| 角色生成/抽取的 Java 模型调用 | ✅ 迁 Python `/agent/character/*`；Java 只做清洗 + JSON 归一化 |
| `AgentRuntimeProperties.Mode` / `NoopAgentRuntimePort` | ✅ 删除；运行时只有 AGENT |
| Spring AI Alibaba Graph 编排（`ChatTurnGraphConfig` + 3 节点 + `ChatTurnMessageAssembler` + 图状态类，约 1014 行） | ✅ 删除；编排改由 Python LangGraph 负责，`spring-ai-alibaba-graph-core` 依赖一并移除。详见 [langgraph-orchestration.md](langgraph-orchestration.md) |
| `EmbeddingService` / `RerankerService` / `MemoryVectorStore.insert+search` / `AiConfig.Embedding+Reranker` | ✅ 删除；向量化与重排已在 Python（pymilvus）。`MemoryVectorStore` 只留 `delete()`（纯 IO，无模型调用） |
| `DashScopeTtsService` / `DashScopeVoiceEnrollmentClient` | ✅ 删除（-449 行）；音色注册与非实时 TTS 走 `VoiceEnrollmentPort` / `TtsPort`。`DashScopeCloudEndpointValidator.enrollUrl/synthUrl` 一并删除 —— 出站 URL 只在 Python 拼。详见 [tts-migration-plan.md](tts-migration-plan.md) |
| 桌面能力层 Java 侧：`AgentBridgeService` / `CapabilityAuditService` / `AgentToolArguments` / `AgentBridgeController` / `InternalDesktopExecController` / `AgentInternalSigner` / 桥专用 DTO + 3 个测试（11 个文件，-1539 行） | ✅ 删除（2026-10-25）。模型侧 `desk_*` 工具已下线、Electron 能力层并行删除，这条链路已无调用方；`toolFlags.computer` 保留键名但恒为 `false`。Flyway `V55__agent_capability_audit.sql` 与 `agent_capability_audit` 表**保留**（已上线，只停写） |

## 保留的两条 seam

| 文件 | 职责 |
|---|---|
| `ApiKeyVaultService.java` / `JasyptUtil.java` | 凭据加解密 |
| 事务/落库（各 Service/Mapper） | MySQL/Redis/MQ 写入 |

## 防回退

- `JavaAiLogicRatchetTest` 基线 `0/0/0`，并新增两项：
  - `MODEL_CALLS` 增加 4 条**出站路径**模式（多模态生成 / 音色定制 / 重排 / embeddings）——
    补上的正是「只数 prompt 与 chat 调用、数不到另一套 HTTP 自己发请求」这个盲区
  - `migratedAiClientsStayDeleted`：已迁走的客户端文件名不许在 Java 复活
- `scripts/check-java-ai-free.ps1 -Mode strict` 作为 CI gate（同步上述两类规则）
- 两项检查都做过**非空转验证**：造探针文件 → 立刻失败；删掉 → 通过

### 已知盲区（检查器不覆盖）

1. **群聊指令文本**仍留在 Java，且不会被现有模式命中：

- `GroupChatService:243-266` 群聊主动发言指令（zh/en/ja/zh-TW 四套）
- `GroupChatService:280-287` `extraSystemSuffix` 外壳与 `禁止假装…` 约束
- `GroupChatService:268` `（系统触发主动发言，用户未发送新消息。）`
- `GroupChatService:237` `formatOtherCharacterHistoryLine` 的中文格式

2. **实时 TTS**（`DashScopeTtsRealtimeService` / `VoiceCallDuplexSession` /
   `VoiceDuplexHandler`，约 1232 行）仍在 Java，走的是 `wss://` 自有协议，
   现有模式（都针对 HTTP 路径）扫不到。

因此 `0/0/0` 只代表「已登记的残留清零」，不等于「Java 里没有任何 AI 代码」。
群聊补法见 [langgraph-orchestration.md](langgraph-orchestration.md) 第五节；
实时 TTS 见 [tts-migration-plan.md](tts-migration-plan.md) 第六节。
