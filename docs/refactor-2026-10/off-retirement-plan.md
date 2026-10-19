# OFF 模式退役路线（Python 单一维护）

> 目标：Java 只保留 CRUD / 社交 / 管理 / 凭据解密 / 桌面桥接，**AI 逻辑 100% 在 Python**。
> 本文记录「还差什么」「按什么顺序做」「每个阶段怎么验收和回滚」。

## 一、当前仍由 Java 执行的 AI 路径（实测 + 代码定位）

| 路径 | 位置 | 现状 |
|---|---|---|
| 单聊文本模型调用 | `ChatTurnFacade` → `AgentStreamAdapter` | ✅ Python（AGENT 模式） |
| 单聊 prompt 组装 | `AssemblePromptNode` → `PromptAssemblyPort` | ✅ Python |
| 图片两段式 | `AgentImageAdapter` | ✅ Python |
| 记忆抽取/写入/去重/投影/检索 | `AgentMemoryAdapter` + Python relay | ✅ Python |
| 桌面感知 / 主动发言 | `DesktopObserveOrchestrator` / proactive | ✅ Python |
| **群聊模型调用** | `GroupChatService:1017` `aiChatService.chatBlocking` | ❌ Java |
| **语音通话回合** | `VoiceCallService` | ❌ Java |
| **阻塞调用统一入口** | `AiChatService.chatBlocking/chatStream` | ❌ Java |
| **记忆 MQ 消费者** | `MemoryConsumer`（19 行） | ❌ Java（Jasypt 主密钥） |
| **工具提示文本** | `ToolManager.buildToolsPromptHint` | ❌ Java（依赖桌面桥接内存态） |
| **OFF 模式的组装/取数** | `ChatTurnPromptAssembler` / `MemoryRetriever` / `CharacterPromptBuilder` / `RelationshipStateService` / `CharacterStateService` / `SessionSummaryService` | ❌ Java（回退路径所需） |

## 二、新增遥测：JAVA_AI_PATH

`AiChatService.chatStream` / `chatBlocking` 在 AGENT 模式下会打印：

```
JAVA_AI_PATH=chatStream (AGENT 模式下仍由 Java 调用模型)
JAVA_AI_PATH=chatBlocking (AGENT 模式下仍由 Java 调用模型)
```

用途：**能枚举才能删除**。跑一轮群聊/语音后，日志里出现哪些路径，就是下一阶段要迁的清单。

实测：单聊回复走 `ChatTurn stream served by agent runtime`，无 `JAVA_AI_PATH` → 单聊已完全由 Python 承担。

## 三、阶段与验收

### P6-2 群聊模型调用迁 Python（下一步）
- 需要 `AgentTextPort` 支持**多轮 messages**（当前只接受 system + 单条 user）
- 保留群聊的「谁说话/说几条」编排在 Java（那是产品逻辑），只把**模型调用**交出去
- 验收：群聊 3 人场景下 `JAVA_AI_PATH=chatBlocking` 消失，回复内容与 Java 版本抽样一致
- 回滚：模式切 SHADOW（Java 继续出话），比对记录

### P6-3 语音通话回合
- 实时性要求最高，优先做「一次性 blocking 回合」，流式后续再议
- 验收：一轮通话 5 个回合无超时，`JAVA_AI_PATH` 消失
- 回滚：同上

### P6-4 记忆 MQ 消费者
- 阻塞点：Jasypt 主密钥只在 Java
- 方案 A（推荐）：Java 暴露**内网**凭据代理接口（`/internal/vault/plain`），Python 拿明文 key 后自行调用模型；解密逻辑仍只有 Java 一份
- 方案 B：把主密钥交给 Python（安全面扩大，不推荐）
- 验收：停掉 Java 消费者后，新记忆仍能在 30s 内完成抽取与投影

### P6-5 工具提示
- 阻塞点：`hasComputerTask` 依赖 Java 进程内的桥接会话（内存态，90s TTL）
- 方案：Java 只传 **flags + 工具名列表**，文案模板放 Python（Java 删除文案拼接）
- 验收：SINGLE 场景工具段逐字节一致（当前已通过「Java 传整段」实现一致，此步是把文案所有权转移）

### P6-6 删除 OFF 模式
前置条件（全部满足才可执行）：
1. P6-2 / P6-3 / P6-4 / P6-5 完成
2. AGENT 模式下连续 7 天 `prompt` 通道 `diff=0`
3. agent 宕机回退演练通过（已有）
4. 双实例部署验证（消除单点）

执行顺序：
1. `LIANYU_AGENT_MODE` 默认值改为 `AGENT`
2. 保留 SHADOW 模式一个版本周期（只读比对，不出话）
3. 删除 Java AI 类：`ChatTurnPromptAssembler`、`CharacterPromptBuilder`、`MemoryRetriever`、`RelationshipStateService`、`CharacterStateService`、`SessionSummaryService`、`ToolManager` 文案部分
4. 删除 `OFF` 分支与相关配置

## 四、保留的永久 seam（不算重复实现）

| seam | 原因 |
|---|---|
| 凭据解密 | 主密钥在 Java，Jasypt 生态 |
| 桌面桥接会话 | 进程内内存态 + WebSocket 长连接 |
| 落库/事务 | Java 是写入权威（记忆域除外，已单写者 Python） |

## 五、风险

- 群聊/语音迁移属于**用户可见**变更，必须先 SHADOW 跑出 diff=0 再切
- 删除 OFF 模式后，agent 故障时不再有「完整 Java 兜底」，只有「快速失败 + 重试」；
  因此双实例部署是硬前提
