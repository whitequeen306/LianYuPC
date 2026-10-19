# OFF 退役执行手册（当前状态）

> 目标：Java 零 AI 逻辑。基线由 `JavaAiLogicRatchetTest` 守护（只允许下降）。
> **2026-10-19：OFF/SHADOW 已退役，基线归零。**

## 当前基线（2026-10-19）

| 项 | 数量 | 变化 |
|---|---|---|
| prompt 文本 | 0 | 10 → 0 |
| 模型调用 | 0 | 25 → 0 |
| 已知 AI 类 | 0 | 8 → 0 |

`pwsh scripts/check-java-ai-free.ps1 -Mode strict` 通过。

## 已完成

| 步骤 | 说明 |
|---|---|
| 摘要生成删 Java LLM | `ea3c70c` |
| 记忆抽取删 Java LLM | `977ba1a` |
| 阻塞端点 gateway bug | `7812e5a` |
| LB SSE 配置 | `0e1eaea` |
| SSE 解析丢帧 + GatewayResult 字段 | `77abbfc` |
| 移除 SHADOW 影子模式 | 删除 `PromptShadow*` / `AgentShadowStats*` / 影子测试；prompt 比对改由 Python golden 基线负责 |
| prompt 组装只走 agent | `AssemblePromptNode` / `ChatTurnFacade.assembleSystemPrompt` 只调 `PromptAssemblyPort`，失败直接报错；删除 `ChatTurnPromptAssembler` / `CharacterPromptBuilder` / `rules/hooks/*` |
| 记忆/关系/情绪读路径退役 | 删除 `MemoryRetriever`、`MemorySearchTool`、`RelationshipContextAssembler`；删除 `CharacterStateService.buildEmotionBlock` 与关系 `buildPromptContext`；写入路径保留 |
| 工具提示文案退役 | 删除 `ToolManager.buildToolsPromptHint`，只保留 `promptHintFlags` |
| Java 模型调用清零 | 删除 `ChatModelFactory`、`VisionChatService`、语言门/多模态解析；`AiChatService` 仅保留角色生成/抽取门面；所有调用点改用 agent 端口 |
| 角色生成/抽取迁 Python | 新增 `/agent/character/generate`、`/agent/character/import`；prompt 与质量标准在 `backend-agent/app/character_generation.py` |
| OFF/SHADOW 枚举退役 | 删除 `AgentRuntimeProperties.Mode`、`NoopAgentRuntimePort`、`LIANYU_AGENT_MODE`；运行时只有 AGENT 一条路径 |
| 棘轮基线归零 | `0/0/0`，已知 AI 类清单清空 |

## 关键教训（删除期踩到的 bug）

1. `gateway` 未定义 → 阻塞调用全静默失败
2. LB 的 SSE 配置（server 级 proxy_buffering 不生效）
3. **SSE 解析丢帧**：Spring 剥掉 `data:` 前缀，解析器只认带前缀 → 所有回复变空
4. `GatewayResult.text` / `provider` 字段不存在 → 摘要端点 500
5. **`afterBase` 场景映射错误**：GROUP 被当成 `full` 会多注入时间/城市/自然风格 → 已按 `ChatTurnScene` 的 include 标志逐项映射
6. **`timeFact` 为空时 Python 直接跳过时间锚点** → 已改为「显式传入优先，缺省由 Python 读当前时间」
7. **群聊附加段未随 agent 组装传递** → `PromptAssemblyRequest` 增加 `groupMaxPieces/groupOtherCharacters/groupMention`，Python 侧用 `render_group_chat_rules` 拼接
8. **`extraSystemSuffix=null` → Python 422**：Moments/Diary/Proactive 传 null，Pydantic 要求 string
   → 适配层统一 `null → ""`
9. **主动发言漏传凭据**：`proactiveTurnResult` 只传 provider/model，Python 报 `no_text_credentials`
   → 补 `textBaseUrl/textApiKey/textModel`（vault 解密仍在 Java）

共同点：**都是「未被调用的代码路径」**。删除前必须保证被删路径有真实流量覆盖，
否则删完才发现坏，且坏在更底层。

## 当前 Java/Python 边界

| 职责 | 归属 |
|---|---|
| 模型调用、prompt 文案与组装、语言门、工具循环 | Python `backend-agent` |
| vault 解析/解密、Sa-Token、事务/落库、MQ、通知、多气泡切分、SSE 收尾 | Java |
| 记忆/关系/情绪/摘要**读取**（prompt 数据） | Python 只读 MySQL/Redis |
| 记忆写入 / 关系事件 / 情绪状态 / 摘要写入 | Java（事务内） |
| 桌面桥接工具（`computer_task`）的实时会话 | Java，向 Python 传 `toolFlags` |

## 验收

- ✅ `mvn test` 全绿
- ✅ `pytest` 全绿（1024）
- ✅ `vitest` 全绿（450）
- ✅ `powershell scripts/check-java-ai-free.ps1 -Mode strict` 通过
- ✅ 真实对话 40 条（两轮 × 20 条，经 Java 完整链路）
- ✅ SSE 流式（内容帧 + `pieces` + `[DONE]`）
- ✅ 主动发言冷启动（2 条，含 Python 读取的真实天气块）
- ✅ 角色生成 / 角色导入（Java 解密 vault → Python 模型 → Java 解析）
- ✅ 日志无 `JAVA_AI_PATH`、无 422、无 5xx

## 真实链路验证方法（2026-10-19）

```powershell
# 重建并启动
docker compose build backend agent
docker compose up -d backend agent agent2
# 观察启动自检
docker logs lianyu-backend | Select-String "all ports wired"
```

验证覆盖：

| 链路 | 结果 |
|---|---|
| 注册 → 创建 vault（真实 DashScope Key）→ 创建角色 → 创建会话 | ✅ |
| 阻塞对话 20 条 × 2 轮 | 40/40 非空，无错误 |
| `POST /api/conversation/{id}/messages/stream` | 内容帧 + `pieces` + `[DONE]` |
| 空会话打开 → 主动发言 | 冷启动 2 条（含上海天气事实） |
| `POST /api/character/generate` / `/import/analyze` | 200，字段完整 |
| `POST /agent/text/chat` 直连 20 条 | 20/20 非空，usage 正常 |

> 真实对话中观察到部分回复是括号动作描写而非台词，这是模型风格/角色规则问题，
> 不是空回复或链路故障；测试断言的是「非空且无 error」。
