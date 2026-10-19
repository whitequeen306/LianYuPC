## 对你想法的评估

**两天保底**：同意，比"6 条必写"克制——日记不该每天都有，但绝不该让用户聊两天还看不到一篇。
**角色即兴写日记工具**：想法很好，而且**正好嵌进现有工具体系**——`get_current_time` / `memory_search` 等就是 Spring AI `@Tool` 组件，由 `ToolManager` 注册 + 系统提示挂载。加一个 `write_diary` 工具：模型聊天中觉得今天聊得有趣就调用，工具**只负责入队**（复用现有 RabbitMQ 后台管道），日记正文在后台异步生成、生成后照常推 `DIARY_NEW` 通知——不阻塞聊天回合。

需要三个护栏（都写进方案）：
1. **即兴日记每天上限 1 篇**（`impromptu-max-per-day`）——防模型每轮都调；
2. **防套娃**：日记生成本身的 AI 请求加 `disableChatTools` 标志，`ToolManager` 见标志即不挂任何工具——否则"写日记时又调写日记工具"会递归；
3. **工具侧预检**：调用时先查用户 vault 和当日即兴配额，不满足就返回"别承诺写日记"的提示词，模型不会白承诺。

**两个语义决定（写明，供你否决）**：
- 即兴与定时的"每天一篇"互斥**各自独立**（即兴了今天定时照样可以写，反之亦然）——符合你说的"不计入互斥"；
- **两天保底按"任何来源"的最后一篇算**——昨天刚即兴写过，今晚就不再强制定时篇（否则即兴+强制叠加太频繁）。即兴不影响"今天是否已有定时日记"的判断。

## 新定时算法（22:00 + 23:30 各扫一次，幂等）

候选 = **今天聊过天（用户消息 ≥1）的 SINGLE 会话**，按今日用户消息数降序（替换现在"按创建时间取 30 个"的缺陷）。每角色查最后一篇日记日期（任何来源）：

| 条件 | 行为 |
|---|---|
| 距最后一篇 **≥2 天**（或从未写过）且今天聊过 | **保底必写**（概率 1.0） |
| 2 天内写过、今天 1–2 条用户消息 | 30% 概率（`probability-light`） |
| 2 天内写过、今天 ≥3 条 | 50% 概率（`probability-moderate`） |

上限：全局 `max-per-run: 20` + 单用户每轮 `max-per-user-per-run: 2`；保底候选优先拿名额。若当天没聊，保底顺延到下一个聊天日（日记必须基于当天聊天，不写无米之炊）。

## 改动清单

**1. DDL**（只新增）：`V52__character_diary_source.sql`——`character_diary` 加 `source VARCHAR(16) NOT NULL DEFAULT 'SCHEDULED'`（SCHEDULED=定时算法 / IMPROMPTU=角色即兴）；实体 `CharacterDiary` 加字段。

**2. 定时链路重写**：
- `ConversationMapper` 新增 `selectSingleConversationsActiveSince(since, limit)`（JOIN message 按今日用户消息数聚合，仿仓内 `@Select` text-block 风格，返回新 DTO 行）；
- `CharacterDiaryMapper` 新增按 characterIds 批量查最后日记日期；
- `CharacterDiaryService`：`generateDiaries()` 按上表重写（档位概率抽纯静态函数便于单测）；`tryGenerateDiary(userId, characterId, source)`——定时互斥只数 SCHEDULED、即兴互斥只数 IMPROMPTU、新增"今天零消息不写"硬门槛、**素材改为今天消息优先（≤40 条）+ 不足 10 条回填历史**、指令词要求引用今天聊过的具体细节；第二个 `@Scheduled` 入口（23:30）；`aiReq.setDisableChatTools(true)`；
- `AiBackgroundTask` 加 `source` 字段 + 新工厂 `characterDiaryImpromptu`（消费侧 null 视为 SCHEDULED）；
- `application.yml` `lianyu.diary` 块换新参数并注释算法。

**3. 即兴工具**：
- 新增 `WriteDiaryTool`（`@Tool(name="write_diary")`，仿 `RecentActivityTool`）：预检 vault + 即兴当日配额 → 入队 MQ → 返回"决定写日记"的提示词；`ToolManager` 注册 + prompt hint，开关 `lianyu.tools.write-diary.enabled`；
- `AiChatRequest` 加 `disableChatTools` 字段，`ToolManager.resolveToolCallbacks` 见标志返回空列表。

**4. 测试**：新增 `CharacterDiaryServiceTest`（保底/档位边界、双上限、两路互斥独立、今天零消息门槛、素材今天优先+回填、禁工具标志）+ `WriteDiaryToolTest`（配额拒绝/vault 拒绝/入队成功）；更新 `AiBackgroundTaskTest`。**无前端改动**（日记页后续可加"即兴"角标，本次不做）。

## 验证

1. `mvn -pl lianyu-service -am test` 全绿；
2. 本地把 `min-days-between-diaries` 调 0、概率调 1 冒烟：聊天 → 22:00 触发 → 日记含当天对话细节；聊天中诱导角色调 `write_diary` → 后台异步落库 + 通知；
3. 云端部署后观察两晚：`character_diary` 有 source 分布、两天内活跃角色必有日记。