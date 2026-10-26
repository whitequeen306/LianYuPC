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

| # | 位置 | 方法 | 说明 | 状态 |
|---|---|---|---|---|
| B1 | `GroupChatService:244/249/254/260` | `sendProactiveGroupMessage` | 同一任务块的 **ja / en / zh-TW / zh** 四份 | ✅ 已迁（2026-10-26）：`group_proactive_task_{zh,zh_tw,ja,en}.md` |
| B2 | `GroupChatService:280/284` | 同上 | 标题行 + 禁止条款补丁 | ✅ 已迁：并进同一批模板（段头/禁止条款在四语下都保持中文） |
| B3 | `GroupChatService:989` | `decideMentionByJudgeModel` | 「群聊@提及裁决器」判题 prompt（非对话场景，需独立小端点） | ⬜ 待迁 |

### C. 朋友圈（需新建文案，量大）

| # | 位置 | 方法 | 说明 |
|---|---|---|---|
| C1 | `MomentsService:371` | `generateMoodMoment` | 发帖·日常生活 |
| C2 | `MomentsService:389` | `generateReflectionMoment` | 发帖·对话余韵 |
| C3 | `MomentsCommentOrchestrator:355/362` | `executePeerComment` | 评论用户 / 评论另一位角色 |
| C4 | `MomentsCommentOrchestrator:457/463` | `runAuthorReply` | 回用户评论 / 回角色评论 |
| C5 | `MomentsCommentOrchestrator:761-776` | `buildMomentsSceneGuard` | 场景护栏，四语（英文版是单行片段，棘轮规则 5 扫不到，见下方「已知盲区」） |

### D. 盘点时漏掉、由新棘轮规则扫出来的（2026-10-26）

清单初稿是**人眼**盘出来的，所以只能盘到「我搜索时想到的词」。规则 5 上线后实测多出 5 段：

| # | 位置 | 方法 | 说明 |
|---|---|---|---|
| D1 | `CharacterRecentActivityService:164` | 生活动态块 | `=== Your recent life activities (background context, do not recap line by line) ===` |
| D2 | `SessionSummaryService:177/183` | 会话摘要块 | `=== 本次对话 Earlier 摘要 ===` + 「与最近消息冲突时以最近为准」说明 |
| D3 | `TimeTool:18` / `WeatherTool:38` / `RecentActivityTool:22` | `@ToolParam(description=...)` | Spring AI 工具描述。按红线「工具 schema 归 Python」**待评估**：要确认 Java 侧这套 `@Tool` 是否还活着，再决定整段迁走还是删掉 |

> 这不是「清单不准」，而是**人盘点必然漏**的证据：规则 5 的价值就在于它不依赖记忆。

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
2. ~~**棘轮规则**~~：✅ 已上线（2026-10-26），见下节。现在 B/C/D 的每一段都在
   `scripts/java_bare_prompt_allowlist.txt` 里挂着号，新长出来的裸文案立刻红。
3. **B 批次**：群聊 7 段，新建四语文件；判题 prompt 单独一个 key。
4. **C 批次**：朋友圈 11 段。
5. **D 批次**：3 组 5 段（先确认 `@Tool` 那条链路是否还活着）。

## 棘轮规则 5「裸 prompt 长文案」（已落地）

实现只有一份：`JavaAiLogicRatchetTest#barePromptLiteralsAreAllRegistered`（`mvn test` 守），
`scripts/check-java-ai-free.ps1` 只负责把「还欠多少」打印出来 —— 提取器（注释/字符串/文本块
状态机）写两遍必然漂，而两份结果不一致时没人知道该信哪个。

命中判定（**看结构，不看具体标题**）：

| 条件 | 为什么 |
|---|---|
| 字面量长度 ≥ 60（`\n` 先还原） | 短句做不了 prompt；太短的多是错误码 |
| 含 `=== 标题 ===` 段头 | prompt 块最稳的结构特征，业务文案/SQL/日志里不会出现 |
| 或含 ≥2 个三语特征词 | 收「写成句子才会出现」的词；`role`/`character` 这类 SQL 列名**刻意不收** |
| 或「非 SQL 文本块」 | 业务代码里除了 SQL 几乎只有 prompt 写成文本块 |

- 排除：`CharacterSquareCatalog*`（角色资料数据，属搬家不属 prompt）、`ApiKeyVaultService`/`JasyptUtil`（既有 seam）
- 登记：`scripts/java_bare_prompt_allowlist.txt`，条目指纹 = `路径::正文头 48 字`
  - 命中未登记 → 红（新文案必须搬走或登记）
  - 登记未命中 → 红（迁完了就得删行；文案改了要更新指纹）
  - 所以该文件**只会变短**，目标态是空文件
- 当前 13 条：B3 一段、C1–C4 六段、D1–D3 六段（1+6+6）
  （C5 是单行英文片段，本规则扫不到 —— 见下方盲区，别以为它被登记了）

### 已知盲区（明确记录，不假装守住了）

- **单行英文 prompt 片段**：如 `MomentsCommentOrchestrator` 的 `[Moment scene] You are ...` /
  `Do not use pet names ...`。英文特征词只收了 `You are` / `Do not` 等几个，
  因为通用英文词在 SQL 里满地都是（实测 86 个长字面量里 26 个是「英文且不含中日文」，
  绝大多数是 SQL/Mapper 语句）。代价是行内英文片段可能漏 —— 记在 C5。
- **跨行字符串拼接**（`"a" + "b" + ...`）：按单个字面量判定，拼出来的长句可能每段都不足 60 字。

