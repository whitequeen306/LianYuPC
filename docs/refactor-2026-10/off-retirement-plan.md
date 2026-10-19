# OFF 模式退役路线（Python 单一维护）

> 目标：Java 只保留 CRUD / 社交 / 管理 / **加解密 / 桌面桥接会话 / 事务落库**，
> AI 逻辑 100% 在 Python。本文记录「还差什么」「按什么顺序做」「怎么验收和回滚」。

## 一、状态总览（滚动更新）

| 能力 | 归属 | 状态 |
|---|---|---|
| prompt 组装（全部块） | Python | ✅ 真实流量 9 段逐字节一致 |
| 工具提示文案 | Python | ✅ Java 只传 flags |
| 单聊流式模型调用 | Python | ✅ AGENT 模式 |
| 阻塞模型调用（13 处） | Python | ✅ 已分流（单测覆盖，群聊/语音待真实流量验证） |
| 被动消息模型调用 | Python | ✅ |
| 记忆抽取/写入/投影/检索 | Python | ✅（Java 转发 + 回退） |
| 会话摘要生成（合并/压缩） | Python | ✅ |
| **agent 高可用** | — | ✅ **双实例 + nginx LB（本轮完成）** |
| Java 侧 AI 代码 | Java | ❌ 10 处 prompt 文本 / 27 模型调用 / 9 个 AI 类（全部是 OFF 回退） |

## 二、残留为什么不能立刻删

`mvn test` 里的 `JavaAiLogicRatchetTest` 用「棘轮」守住基线（只允许变少）：

    prompt 文本 ≤ 10 处 | 模型调用 ≤ 27 处 | 已知 AI 类 ≤ 9 个

逐个核实的归属：

| 类 | AGENT 模式是否使用 | 何时可删 |
|---|---|---|
| `ChatTurnPromptAssembler` / `CharacterPromptBuilder` | 否（OFF 组装） | OFF 退役 |
| `MemoryRetriever` / `RelationshipStateService` / `CharacterStateService` | 否（Python 自读） | OFF 退役 |
| `SessionSummaryService` / `SessionSummaryMerger` | 仅回退 | OFF 退役 |
| `MemoryLlmExtractor` | 仅回退 | OFF 退役 |
| `ToolManager.buildToolsPromptHint` | 否（仅 OFF 组装调用） | OFF 退役 |
| `AiChatService.chat*` / `ChatModelFactory` | 仅回退 | OFF 退役 |

**结论：它们就是 OFF 模式的定义，删了等于删掉回退。**

## 三、删除 OFF 的判定条件

1. `JavaAiLogicRatchetTest` 基线可下调 → 前提是下面几条都成立
2. AGENT 模式下连续 7 天 `prompt` 通道 `diff=0`
3. 双实例 agent + LB 已上线（✅ 已完成）
4. 极限演练：停 agent → 停 agent2 → 双实例全挂时行为已知、可接受

## 四、删 OFF 的执行顺序

1. `LIANYU_AGENT_MODE` 默认值改 `AGENT`
2. 观察一个版本周期（保留 SHADOW 能力做只读比对）
3. 集中删除（一次性，按依赖顺序）：
   - `CharacterPromptBuilder` + `ChatTurnPromptAssembler` + `PromptsRuleEngine` + 各 RuleHook
   - `MemoryRetriever` / `RelationshipStateService` / `CharacterStateService`（仅读路径）
   - `SessionSummaryMerger` 本地实现
   - `MemoryLlmExtractor`
   - `ToolManager.buildToolsPromptHint`
   - `AiChatService` 内的模型调用分支 + `ChatModelFactory`
4. 下调 `JavaAiLogicRatchetTest` 基线到 0/0/0
5. 删除 `OFF` 枚举与相关配置、`NoopAgentRuntimePort`

## 五、保留的永久 seam

| seam | 原因 |
|---|---|
| 加解密（`ApiKeyVaultService` / `JasyptUtil`） | 主密钥在 Java，Python 拿明文 key 调模型 |
| 桌面桥接（`AgentBridgeService`） | 进程内 WebSocket 长连接 + 90s 心跳 |
| 事务/落库 | Java 是业务写入权威（记忆域已单写者 Python） |

## 六、已知风险

- 群聊/语音的**编排**（谁说话、说几条）仍在 Java：那是产品规则，不是 AI 文本，保留
- 阻塞调用接管尚未经过真实群聊/语音流量验证（STOMP WebSocket 入口难测）
- LB 为被动健康检查，切换窗口 ≤10s
- `agent2` 关闭 relay（单写者语义），若要多实例并行需引入 leader 选举