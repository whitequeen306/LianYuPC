# Prompt 迁移清单（Java → Python）

> 2026-10-26 盘点。背景：仓库红线是「prompt 只许在 `backend-agent/app/prompts/`」，
> 但大量台词指令仍硬编码在 Java 字符串里；守门脚本
> （`scripts/check-java-ai-free.ps1`）只认「模型调用点/ChatClient/已知 AI 类」，
> **认不出裸文案**，所以这类代码一直在长。

## 已完成

| 场景 | 原位置 | 现状 |
|---|---|---|
| 写日记 instruction（四语） | `CharacterDiaryService` 里的四语 switch | ✅ 已迁：`app/prompts/diary_instruction_*.md` + `POST /agent/prompt/scene-instruction` + `SceneInstructionPort` |
| A 批次（主动发言 / 破冰 / 城市变更，6 段） | `ProactiveMessageService` 内联字面量 | ✅ 已迁：`proactive_task.md` / `cold_open_task.md` / `cold_open_followup.md` / `city_change_task.md` / `city_change_context.md`，走 `POST /agent/prompt/render` + `SceneInstructionPort.render`；Java 侧零兜底文案 |
| A6 主动开口真实环境块 | `ProactiveRealWorldContextService.buildBlock` | ✅ **无需搬文案**：b161fa7（OFF 退役）已在 `app/proactive_context.py` 重建同一段；Java 那份从此没人调用（死代码）。2026-10-26 删除该服务 + 3 处陈旧 mock，并在 `tests/test_scene_prompt_extras.py` 用工整字面量锁死两分支字节 |

A6 的特殊之处值得记一笔：盘点时它被登记成「待迁」，实际是**同一段文案在两侧各存了一份**，
Java 那份已经在 OFF 退役时失去调用方。所以这一步的正确动作不是「再迁一遍」，而是
「逐字比对 → 确认 Python 已是唯一实现 → 删掉死代码」。比对方式：按 JLS 文本块语义把
Java 字面量还原（开分隔符后的换行不算内容 / 去公共缩进 / 行尾 `\` 续行不产生换行），
两条分支（有天气 257 字符、无天气 266 字符）与 Python 输出**完全相等**。
留下来的风险点只有一处：Python 对天气实况做了 `.strip()`，Java 是原样拼接——
实况由工具生成、本就无首尾空白，故不影响，但将来若换天气源要留意。

## 待迁（B/C 共 18 段，2 个文件）

### A. 主动发言 / 破冰 / 城市变更

全部完成，见上表。`PromptAssemblyRequest` 之外 Java 不再持有任何主动发言文案。

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

1. **前导空行必须逐站点量**，别按印象抄：
   - `appendCityChangeContext`：`basePrompt + "\n\n=== …"` → **2 个**（已迁，A5）
   - `buildProactiveTaskSystemSuffix` / 破冰两块：文本块源码里有 3 个原始换行，其中
     开分隔符后那个换行是**定界符不是内容** → 内容前导 **2 个**（已迁，A1–A3）
   - `ProactiveRealWorldContextService`：`sb.append("\n\n=== …")` → **2 个**（见上文 A6）
   - Python 侧 `render_prompt()` 默认 `leading_newlines=2` 与上述一致，但**默认值不等于事实**：
     端点/端口始终显式传，以后接新站点继续逐个量。
   （本清单初稿曾把 A1 记成「1 个」，实测是 2 —— 记错就会让模型看到的分隔结构变样，
   所以每条都配了「渲染结果 == 原字面量」的快照测试，而不是靠人眼。）
2. **占位符风格不同**：Java 用 `String.format("%d" / "%s")`，Python 用 `str.format("{max_pieces}")`。
   `render_prompt` 会**校验缺变量并 fail-fast**，这是好事，但要求 Java 侧把参数名对齐
   （`max_pieces` / `previous_city` / `new_city` / `addressing`）。

## 落地顺序

1. ~~**A 批次**~~：✅ 完成。通用端点 `POST /agent/prompt/render` + `SceneInstructionPort.render`
   已上线，6 段全部接线，A6 为「删死代码」。
2. **B 批次**：新建四语文件；判题 prompt 单独一个 key。
3. **C 批次**：新建 11 段。
4. **棘轮规则**：A 已清空，现在可以让规则以「0 未登记违规」落地——把
   `CharacterSquareCatalog*`（角色资料数据）、日志/通知文案列入 allowlist，
   并给 B/C 那 18 段登记「待迁」，这样新长出来的裸文案会立刻红。

## 附：棘轮规则草稿（A 已完成，可启用）

- 排除：`CharacterSquareCatalog*`（人设资料）、`log.*`/`notify*`/`BusinessException` 所在行
- 命中条件：字符串（含文本块）长度 ≥ 60 且含 ≥ 2 个 prompt 特征词
  （你是 / 你正 / 请你 / 必须 / 不要 / 输出 / 格式 / JSON / 任务 / 场景 / 扮演 / 语气 / 字数 / 只输出 / 禁止）
- 基线：A 完成后应为 0；B/C 未迁部分登记在 allowlist 里，带「待迁」注释
