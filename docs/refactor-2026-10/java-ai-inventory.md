# Java AI 逻辑残留基线（2026-10-19）

由 `scripts/check-java-ai-free.ps1` 生成。目标：Java 零 AI 逻辑
（仅保留加解密、桌面桥接会话、事务/落库三条 seam）。

## 当前基线

| 项 | 数量 |
|---|---|
| prompt 文本痕迹 | 10 处 |
| 模型调用点 | 27 处 |
| 已知 AI 类 | 9 个 |

### prompt 文本痕迹（10）

| 文件 | 处数 |
|---|---|
| CharacterStateService.java | 4 |
| OutputLanguageService.java | 2 |
| ToolManager.java | 1 |
| ReplyBehaviorRuleHook.java | 1 |
| CharacterPromptBuilder.java | 1 |
| UserInputSanitizer.java | 1 |

### 模型调用点（27）

| 文件 | 处数 | 说明 |
|---|---|---|
| ChatModelFactory.java | 5 | 模型客户端构造（最终应删除） |
| AiChatService.java | 4 | 模型调用主入口（AGENT 模式已分流） |
| ProactiveMessageService.java | 3 | 先走 agent，失败回退 |
| VoiceCallService.java | 2 | 阻塞调用已可走 agent |
| AiController.java | 2 | 直连接口（已关闭） |
| VisionChatService.java | 2 | 图片链路（agent 有适配器） |
| 其余 8 个文件 | 各 1 | 群聊 judge / 日记 / 摘要 / 动态 / 记忆抽取 |

### 已知 AI 类（9）

`CharacterPromptBuilder`、`ChatTurnPromptAssembler`、`MemoryRetriever`、
`RelationshipStateService`、`CharacterStateService`、`SessionSummaryService`、
`SessionSummaryMerger`、`MemoryLlmExtractor`、`ToolManager`

## 门槛定义

- `-Mode gate`：prompt 文本 + 模型调用必须为 0（OFF 退役前后都适用）
- `-Mode strict`：连「已知 AI 类」也必须为 0（OFF 退役完成后启用）

## 备注

- `ToolManager.buildToolsPromptHint` 目前仍存在，但**运行时已不再被调用**
  （文案改由 Python 查表生成），仅作为 golden 生成源保留；迁移收尾时删除。
- 模型调用点里有相当一部分是「AGENT 模式分流 + 回退」，属于过渡态；
  删除 OFF 后才能归零。