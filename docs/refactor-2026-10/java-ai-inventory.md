# Java AI 逻辑残留清单（滚动更新）

由 `scripts/check-java-ai-free.ps1` 生成/核对。目标：Java 零 AI 逻辑
（仅保留加解密、桌面桥接会话、事务/落库三条 seam）。

## 当前基线

| 项 | 数量 |
|---|---|
| prompt 文本痕迹 | 10 处 |
| 模型调用点 | 27 处 |
| 已知 AI 类 | 9 个 |

## 关键结论：这些残留几乎全部是「OFF 路径必需」

逐个核实后的归属：

| 残留 | 运行时是否被 AGENT 模式使用 | 何时可删 |
|---|---|---|
| `ToolManager.buildToolsPromptHint` | ❌ 仅 `CharacterPromptBuilder`（OFF 组装用） | OFF 退役 |
| `ChatTurnPromptAssembler` / `CharacterPromptBuilder` | ❌ 仅 OFF/SHADOW | OFF 退役（SHADOW 也依赖 Java 组装作基准） |
| `MemoryRetriever` / `RelationshipStateService` / `CharacterStateService` | ❌ AGENT 模式由 Python 自读 | OFF 退役 |
| `SessionSummaryService` / `SessionSummaryMerger` | ❌ AGENT 模式摘要仍由 Java 写 Redis，但 Python 读 | 需先把「摘要写入」迁 Python |
| `MemoryLlmExtractor` | ❌ `MemoryWriter` 已优先走 `AgentMemoryPort` | OFF 退役 |
| `AiChatService.chat*` | 🟡 AGENT 模式已分流（流式 + 阻塞），仅作回退 | OFF 退役 |
| `ChatModelFactory`（5 处） | 🟡 仅回退路径构造模型 | OFF 退役 |
| `VisionChatService` 模型调用 | 🟡 图片链路 AGENT 有适配器，Java 保留回退 | OFF 退役 |

结论：**在 OFF 模式存在的期间，这些代码都不能删**——它们就是 OFF 的定义。

## 唯一还没被 Python 接管的执行路径

| 路径 | 状态 |
|---|---|
| 会话摘要写入（Redis `session:summary:*`） | ❌ Java 写、Python 读 |
| 记忆 MQ 消费（触发抽取） | 🟡 Java 消费→转发 Python（AGENT 模式）；Java 抽取器仅 OFF 用 |
| 群聊 / 语音的「生成逻辑」 | 🟡 模型调用已可走 Python，编排仍在 Java（属于产品逻辑，不是 AI 文本） |

## 因此 OFF 退役的判定条件（收敛后）

1. `-Mode strict` 为 0（Java 无 prompt 文本、无模型调用、无 AI 类）
2. 会话摘要写入迁到 Python
3. AGENT 模式下连续 7 天 `prompt` 通道 diff=0
4. 双实例部署（agent 单点消除）

## 怎么走到第 1 条

删除 OFF 模式 → 立刻减少 27 个模型调用点中的回退部分、9 个 AI 类中的 7 个。
这是一次**集中删除**，前提是第 2-4 条已满足。