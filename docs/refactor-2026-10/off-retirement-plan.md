# OFF 退役执行手册（当前状态）

> 目标：Java 零 AI 逻辑。基线由 `JavaAiLogicRatchetTest` 守护（只允许下降）。

## 当前基线（2026-10-19）

| 项 | 数量 | 变化 |
|---|---|---|
| prompt 文本 | 10 | — |
| 模型调用 | 25 | 27 → 25 |
| 已知 AI 类 | 8 | 9 → 8 |

## 已完成

| 步骤 | 提交 |
|---|---|
| 摘要生成删 Java LLM（269→90 行） | `ea3c70c` |
| 记忆抽取删 Java LLM（整类删除） | `977ba1a` |
| 阻塞端点 gateway bug | `7812e5a` |
| LB SSE 配置 | `0e1eaea` |
| SSE 解析丢帧 + GatewayResult 字段 | `77abbfc` |

## 关键教训（删除期踩到的 4 个 bug）

1. `gateway` 未定义 → 阻塞调用全静默失败
2. LB 的 SSE 配置（server 级 proxy_buffering 不生效）
3. **SSE 解析丢帧**：Spring 剥掉 `data:` 前缀，解析器只认带前缀 → 所有回复变空
4. `GatewayResult.text` / `provider` 字段不存在 → 摘要端点 500

共同点：**都是「未被调用的代码路径」**。删除前必须保证被删路径有真实流量覆盖，
否则删完才发现坏，且坏在更底层。

## 剩余删除（一次性大改，需按依赖顺序）

前置：**放弃 SHADOW 模式**（它依赖 Java 组装作为比对基准）。

1. `AssemblePromptNode` 去掉 Java 回退，只调 `PromptAssemblyPort`，失败直接报错
2. 删除 `ChatTurnPromptAssembler`、`CharacterPromptBuilder`、`PromptsRuleEngine`
   及 `rules/hooks/*`、`LanguageGateService`（若仅被它们使用）
3. 删除 `MemoryRetriever`、`RelationshipStateService`、`CharacterStateService` 的读路径
   （注意：`character_state` 的写入仍需 Java）
4. 删除 `ToolManager.buildToolsPromptHint`（`promptHintFlags` 保留）
5. 删除 `AiChatService` 的模型调用分支、`ChatModelFactory`、`VisionChatService` 的模型部分
6. 删除 `OFF` / `SHADOW` 枚举与 `NoopAgentRuntimePort`
7. 棘轮基线 → 0/0/0

## 验收

- `mvn test` + `pytest` + `vitest` 全绿
- `powershell scripts/check-java-ai-free.ps1 -Mode strict` 通过
- 真实对话 20 条：回复有内容、无 `JAVA_AI_PATH` 日志