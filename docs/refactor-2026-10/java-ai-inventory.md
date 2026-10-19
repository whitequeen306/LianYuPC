# Java AI 逻辑残留清单（滚动更新）

由 `scripts/check-java-ai-free.ps1` 生成/核对。目标：Java 零 AI 逻辑
（仅保留加解密、桌面桥接会话、事务/落库三条 seam）。

## 当前基线（2026-10-19，OFF 退役后）

| 项 | 数量 |
|---|---|
| prompt 文本痕迹 | 0 处 |
| 模型调用点 | 0 处 |
| 已知 AI 类 | 0 个 |

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

## 保留的三条 seam

| 文件 | 职责 |
|---|---|
| `ApiKeyVaultService.java` / `JasyptUtil.java` | 凭据加解密 |
| `AgentBridgeService.java` / `AgentBridgeController.java` | 桌面桥接长连接会话 |
| 事务/落库（各 Service/Mapper） | MySQL/Redis/MQ 写入 |

## 防回退

- `JavaAiLogicRatchetTest` 基线 `0/0/0`
- `scripts/check-java-ai-free.ps1 -Mode strict` 作为 CI gate
- 以后若新增 AI 逻辑类，把类名加回脚本的 `$knownAiClasses`，strict 会立刻失败
