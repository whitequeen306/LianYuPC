# Prompt 迁移清单（Java → Python）

> 2026-10-26 盘点。背景：仓库红线是「prompt 只许在 `backend-agent/app/prompts/`」，
> 但大量台词指令仍硬编码在 Java 字符串里；守门脚本
> （`scripts/check-java-ai-free.ps1`）只认「模型调用点/ChatClient/已知 AI 类」，
> **认不出裸文案**，所以这类代码一直在长。

## 已完成

| 场景 | 原位置 | 现状 |
|---|---|---|
| 写日记 instruction（四语） | `CharacterDiaryService` 里的四语 switch | ✅ 已迁：`app/prompts/diary_instruction_*.md` + `POST /agent/prompt/scene-instruction` + `SceneInstructionPort` |

## 待迁（25 段，5 个文件）

### A. 主动发言 / 破冰 / 城市变更（Python 文案已存在，属接线）

| # | 位置 | 方法 | 对应 Python 资源 | 现状 |
|---|---|---|---|---|
| A1 | `ProactiveMessageService:88` | `buildProactiveTaskSystemSuffix` | `proactive_task.md`（`{max_pieces}`） | 文件已在，未接线 |
| A2 | `ProactiveMessageService:312` | `sendColdOpenFirstLine` | `cold_open_task.md`（`{max_pieces}`） | 文件已在，未接线 |
| A3 | `ProactiveMessageService:600` | `sendColdOpenFollowUpIfStillSilent` | `cold_open_followup.md`（无占位） | 文件已在，未接线 |
| A4 | `ProactiveMessageService:680` | `sendCityChangeFollowUp` | `city_change_task.md`（`{previous_city}{new_city}{addressing}`） | 文件已在，未接线 |
| A5 | `ProactiveMessageService:71` | `appendCityChangeContext` | **待新建** `city_change_context.md` | Java 内联拼接 |
| A6 | `ProactiveRealWorldContextService:40` | `buildBlock` | **待新建** `real_world_context.md` | Java 内联拼接 |

### B. 群聊（需新建四语文案）

| # | 位置 | 方法 | 说明 |
|---|---|---|---|
| B1 | `GroupChatService:244/249/254/260` | `sendProactiveGroupMessage` | 同一任务块的 **ja / en / zh-TW / zh** 四份 |
| B2 | `GroupChatService:280/284` | 同上 | 标题行 + 禁止条款补丁 |
| B3 | `GroupChatService:989` | `decideMentionByJudgeModel` | 「群聊@提及裁决器」判题 prompt（非对话场景，需独立小端点） |

### C. 朋友圈（需新建文案，量大）

| # | 位置 | 方法 | 说明 |
|---|---|---|---|
| C1 | `MomentsService:371` | `generateMoodMoment` | 发帖·日常生活 |
| C2 | `MomentsService:389` | `generateReflectionMoment` | 发帖·对话余韵 |
| C3 | `MomentsCommentOrchestrator:355/362` | `executePeerComment` | 评论用户 / 评论另一位角色 |
| C4 | `MomentsCommentOrchestrator:457/463` | `runAuthorReply` | 回用户评论 / 回角色评论 |
| C5 | `MomentsCommentOrchestrator:761-776` | `buildMomentsSceneGuard` | 场景护栏，四语 |

## ⚠️ 迁移前必须量准的两件事（否则模型看到的话会悄悄变）

1. **前导空行不一致**，逐个站点量：
   - `appendCityChangeContext`：`basePrompt + "\n\n=== …"` → **2 个**
   - `buildProactiveTaskSystemSuffix`：文本块 `"""` 后紧跟一个空行 → **1 个**
   - Python 侧 `render_prompt()` 默认 `leading_newlines=2`
   → 端点/端口必须能指定前导空行数，不能统一按 2 处理。
2. **占位符风格不同**：Java 用 `String.format("%d" / "%s")`，Python 用 `str.format("{max_pieces}")`。
   `render_prompt` 会**校验缺变量并 fail-fast**，这是好事，但要求 Java 侧把参数名对齐
   （`max_pieces` / `previous_city` / `new_city` / `addressing`）。

## 落地顺序（建议）

1. **A 批次**：加通用端点 `POST /agent/prompt/render`（`name` + `variables` + `leading_newlines`），
   端口扩一个 `render(...)`，然后逐站点接线，每站点配一条「渲染结果 == 原 Java 字面量」的对比测试。
2. **B 批次**：新建四语文件；判题 prompt 单独一个 key。
3. **C 批次**：新建 11 段。
4. **最后**才加棘轮规则：把 `CharacterSquareCatalog*`（角色资料数据）、日志/通知文案列入 allowlist，
   并给 B/C 未迁部分登记「待迁」清单，使规则以「0 未登记违规」落地。

## 附：棘轮规则草稿（待 A 完成后启用）

- 排除：`CharacterSquareCatalog*`（人设资料）、`log.*`/`notify*`/`BusinessException` 所在行
- 命中条件：字符串（含文本块）长度 ≥ 60 且含 ≥ 2 个 prompt 特征词
  （你是 / 你正 / 请你 / 必须 / 不要 / 输出 / 格式 / JSON / 任务 / 场景 / 扮演 / 语气 / 字数 / 只输出 / 禁止）
- 基线：A 完成后应为 0；B/C 未迁部分登记在 allowlist 里，带「待迁」注释
