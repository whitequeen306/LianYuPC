# 上下文与长期记忆架构改革（v1.0）

> 状态：方案稿，待评审。本文是这次改革的**唯一总纲**，合并了三路外部调研
> （RP 类产品 / 学术机制 / 生产实践）与一次代码审计。
> 记忆侧的详细论证仍见 [`memory-architecture-zh.md`](./memory-architecture-zh.md)（已标暂停，作为附件）。
>
> 核心判断：**上下文与长期记忆不能分开做**。上下文是记忆的工作集，记忆是上下文的持久层；
> 两者之间缺的不是算法，而是一个共同的对象——**场景卡（SceneCard）**。
>
> 一句话：**把"每轮重新猜我们在聊什么"，换成"每轮读一张场景卡"；把"importance 前 20 条"，
> 换成"用场景卡去召回几条原话"。**

---

## 0. 一页结论

1. 现状不是「基础 RAG」，而是**固定槽位模板 + 最近 32 条原文 + importance top20 + 600 字滚动摘要**。
   真正缺的不是向量库，是「当前在聊什么」这个对象。
2. 引入 **SceneCard**：一份 ≤220 token 的结构化场景状态（话题 / 情绪 / 未兑现的约定 / 可回cue的梗）。
   它同时是：**注入上下文的实体**、**检索的 query**、**抽取的边界**、**承诺的账本**。
3. 上下文改成 **9 个槽位 + token 预算 + 写死的淘汰序**；**静态头在前**（吃满 prompt cache），
   **导演指令在最后**（贴近生成端，压人设漂移）。
4. 记忆改成 **core / fact / episode / reflection 四层**；**场景关闭时才抽取**，不再每条回复抽一次；
   写入用双阈值（cos ≥0.92 NOOP / <0.75 ADD / 灰区 LLM）+ supersede 版本化。
5. 检索用场景卡构造 query，五路召回 → RRF 融合 → MMR 去重 → **与最近窗口去重**（现在同一句话会出现在
   摘要、记忆、原文三处）。中文稀疏路用 MySQL ngram，不升 Milvus 2.5。
6. 分四期落地：**P0 只改顺序/预算/埋点（可回滚）**；P1 场景卡；P2 记忆 v3；P3 整合与前端出口。
7. 遗忘不是删除：每条记忆一个 `strength` 随时间衰减、被提及时回升，分 `active/faded/archived/dormant`
   四档，可复活、可 pin；**硬删只在用户手里**（§7.6）。

### 0.1 改什么、不改什么

| 维度 | 现在 | 改成 | 依据 | 期 |
|---|---|---|---|---|
| 上下文骨架 | 人设→记忆块→规则→秒级时间，每轮变 | tools → 静态头（product/persona/core）→ 动态尾（relation/scene/recall/archive/director） | 三家缓存文档 + Manus | P0 |
| 场景 | 无 | SceneCard（Redis 6h，≤220 token） | RP 类产品的槽位共识 + 产品需求 | P1 |
| 抽取 | 每条回复后抽 3 条消息级记忆 | 场景关闭时按整段场景抽 episode/fact | Zep / Mem0 / 我们的审计 | P1–P2 |
| 存储 | 单表 + 4 类型 | 四层 + 双时间轴 + supersede + 原话 | Zep 双时间轴 / MemoryBank 衰减 | P2 |
| 检索 | importance top20 + LIKE 5 | 五路（core/fact/episode/reflection/recent）+ RRF + MMR | Generative Agents / Mem0 | P2 |
| 遗忘 | 没有机制（只增不减，靠排序取前 20） | `strength` 双时间尺度衰减 + 四档 + 复活 + 主动浮现 + `muted`/`pin` | 人类遗忘曲线 / MemoryBank / Kindroid | P2 |
| 注入 | 摘要式条目，无原话 | 每条带 `[日期] 她说「原话」` | Fidelity Before Structure | P2 |
| 缓存 | 无断点，system 每轮变 | 静态前缀 ≥1200 token，前缀稳定性单测 | Anthropic 实测 −90% 成本 | P0 |
| 预算 | 固定 32 条，无 token 上限 | 9 槽预算 + 写死淘汰序 + 估算校准 | 无官方配比，自测 | P0–P1 |
| 工具结果 | 原样回灌 | 入口截断 + 旧结果按块清理 + 成对 | Anthropic context editing | P0 |
| 评测 | 无 | 200 题中文 + 三档消融 + 线上 9 指标 | LoCoMo / LongMemEval 的题型 | P1 起 |
| 前端 | 星图只读列表 | 星图接 layer/pin/`muted`/时间轴，可改可删 | Replika/Kindroid 的可控性 | P3 |

**明确不改**：Java 不拼 prompt、不查记忆（零 AI 棘轮）；SSE 帧契约不动；Milvus 不升 2.5；
不引 Neo4j；不动 `backend-agent/app/write.py` 这个记忆域唯一写入口（改为在 AGENTS.md 登记例外）。

---

## 1. 现状：一轮请求到底拼了什么

### 1.1 拼装顺序（SINGLE 场景）

```
Java 取最近 32 条消息（含本轮用户消息）
  → Python 组装 system prompt：
       模板/人设
       === 关于用户的记忆（最新权威信息） ===   ← 记忆块 + 关系块 + 会话摘要
       称呼规则 → 工具提示 → 回复规则 → 输出语言规则 → 情绪块 → 安全边界
       当前真实时间（精确到秒）→ 城市 → 晚安 → 自然风格
  → messages = [system] + 最近 32 条 history（当前用户消息包 <user_message trusted="false">）
```

依据：`backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java:264`；
`backend/lianyu-app/src/main/resources/application.yml:204`（`context-window: 32`）；
`backend-agent/app/prompt_pipeline.py:118`；`backend-agent/app/system_prompt.py:87-128`、`:186-192`；
`backend-agent/app/chat_turn.py:177-219`。

### 1.2 记忆块：三段拼接，且**不是向量检索**

`backend-agent/app/data_readers.py:225 load_memory_context()` 依次拼：

| 段 | 取数 SQL | 依据 |
|---|---|---|
| 记忆画像 | `summary LIKE '【长期记忆/%' ORDER BY created_at DESC LIMIT 80`（每 slot 只留最新） | `data_readers.py:129-146` |
| 基础候选 | `importance >= 0.5 ORDER BY importance DESC, created_at DESC LIMIT 20` | `data_readers.py:105-113` |
| 关键词补位 | 用户话里首个 2–12 字 token，`summary LIKE %kw%`，`LIMIT 5` | `data_readers.py:149-171` |
| 关系块 | `relationship_state` 一行 + 最近 5 条 `relationship_event` | `data_readers.py:26-66`、`relationship_context.py:67-121` |
| 会话摘要 | Redis `session:summary:{conversationId}` 的 `text` | `data_readers.py:269-286` |

渲染成 `[用户画像] / [近期情绪线索] / [关系事件] / [专属仪式]` 四组（`data_readers.py:174-198`）。
**向量库只在模型主动调 `memory_search` 工具时才被用到**；主链路一次 embedding 都不做。

### 1.3 会话摘要：一条只增不减的自由文本

`SessionSummaryService`（`backend/lianyu-service/.../conversation/SessionSummaryService.java`）：

- `rawWindow = 32`：只把「已经掉出最近 32 条」的消息纳入摘要（`loadPendingMessages`）；
- 触发：pending ≥ `slideBatchMin = 6`，或最老 pending 超过 `staleMinutes = 30` 分钟；
- 生成：调 Python `/agent/session-summary`，`target 600 / soft 900 / hard 1200` 字符；
- 存储：Redis `session:summary:{id}`，TTL 72h；
- 超长裁剪：按段丢弃（先丢 `【用户状态】`，再丢 `【正在聊的话题】`）。

参数见 `SessionSummaryProperties.java:12-22`；只有 `SINGLE` 会话做摘要（`SessionSummaryService.java:59`）。

### 1.4 写入：每条回复后抽一次，粒度是"消息"不是"事件"

- Java 每条助手回复后调 `/agent/memory/extract-write`（`backend/lianyu-web/.../AgentMemoryAdapter.java:95`）；
- Python `memory/extract.py`：读最近 30 条、每条截 160 字；`importance < 0.5` 丢弃；每轮最多 3 条；
  类型只有 `FACT | EMOTION | RELATION | RITUAL`；
- 写 `memory_meta` + `memory_outbox`（同事务），relay 异步入 Milvus（`memory/relay.py`）。

### 1.5 预算与规模：没有 token 预算

- system prompt 实测（`build_system_prompt`，空记忆）：**1750 字**；加示例记忆 + 情绪块：**2063 字**。
- 历史固定 32 条，**没有 token 上限**；单条用户消息上限 8000 字（`message_assembly.py:18`）。
- 全链路没有按 token 的裁剪、淘汰或预算；`message.tokens` 只用于计费。
- 没有 `cache_control` / 显式缓存断点；时间锚**精确到秒**（`tools/time_tool.py:63`）且位于 system 末尾。
- 工具结果原样回灌（`tool_loop.py:72-104`），没有长度上限。

### 1.6 一句话概括

问题不是上下文太长（实测约 3–5k token），而是**没有"当前在聊什么"的对象，也没有"该塞什么"的决策**。

---

## 2. 六个结构性问题

1. **没有场景对象**：模型每轮都要从 32 条原文里重新推断"我们在聊什么、有什么没兑现的约定"。
   用户问"你还记得我昨天说的吗"时，系统唯一能做的是塞 20 条记忆赌运气。
2. **检索与当下无关**：top20 按 `importance` 排序，而 `importance` 由 LLM 自打分、长期挤在 0.7–0.9，
   排序近似随机；画像槽位永远全量注入。
3. **同一事实多处重复且无仲裁**：摘要说"她最近加班很累"、记忆里一条 EMOTION、最近 32 条里还有原话；
   三份文本可能互相矛盾，prompt 只声明"记忆最新"，没有冲突裁决。
4. **摘要是会漂的黑盒**：600 字自由文本，每 6 条消息重写一次；没有 schema、没有校验、没有版本，
   漂移不可检测；"答应周末去看猫"这种**未兑现的约定**没有结构化位置。更糟的是它还在替原文"记忆"——
   受控消融里把原文换成结构化摘要，LoCoMo 掉 15.9 点、LongMemEval-S 掉 22.0 点（§6.6）。
5. **顺序同时伤害缓存与权威性**：动态记忆块紧跟人设（第 2 槽），使前 1.7k 字静态前缀每轮失效；
   而真正该压住人设漂移的"导演指令"没有末尾槽位。
6. **没有预算与淘汰序**：长消息、工具结果、群聊都能把窗口顶爆；产品上也没有回答"先丢什么"。

---

## 3. 外部证据

### 3.1 角色扮演类产品：槽位骨架是共识

来源：SillyTavern 官方文档（worldinfo / prompt-manager / context-template / authors-note / summarize）、
AI Dungeon 官方帮助（plot-components / plot-essentials / authors-note / memory-system / story-cards）、
NovelAI 官方文档（lorebook / advancedsettings）、Character Card V2/V3 规范。

| 结论 | 证据 |
|---|---|
| **槽位顺序高度一致**：开头是「你是谁/世界是什么」，结尾是「这一句该怎么写」，中间是可按预算裁剪的对话历史 | ST Prompt Manager 固定列表：Main Prompt → World Info (before/after) → Persona → Character Description/Personality/Scenario → Chat Examples → **Chat History** → **Post-History Instructions**（[docs.sillytavern.app/usage/prompts/prompt-manager](https://docs.sillytavern.app/usage/prompts/prompt-manager/)） |
| **末尾指令比开头指令权威** | ST 明确 PHI「AI usually gives them a higher priority than the main prompt」；AID 称 Author's Note 是「the strongest component for telling the AI how it should generate a response」 |
| **人设会漂**：对话变长后改 Main Prompt 效果有限，模型当它是"很久以前说的话" | ST 官方文档原话（authors-note 页） |
| **世界设定靠确定性关键词，不靠向量** | ST World Info 的 `Key` / `Optional Filter` / `constant` / `Insertion Order`；AID Story Cards 子串匹配；NAI `Activation Keys` 支持 `&` 联合 |
| **长聊不忘 =「滚动摘要 + 最近窗口」**，向量只是最后手段 | AID：每 6 条 action 一条 Memory、每 15 条重写 Story Summary、**最近 6 条永不摘要**；ST Summarize：按消息数/词数双阈值触发；AID Memory Bank「只有在整段 adventure 塞不进上下文时才启用」 |
| **预算与淘汰序必须写死** | ST：`Context %` / `Budget` + 淘汰优先序（constant 先入、order 大者先入、直接命中优先于递归命中）；AID：Story Cards 是「最先被踢出上下文」的元素 |
| **随机性（probability / inclusion group / 无限递归）是负债** | ST 文档承认向量"不可预测"；AID 专门做 Context Viewer 让用户自查；NAI 承认递归"不保证内容进上下文" |

**对我们的三条硬约束**：① 中文不能照搬「整词匹配」（ST 官方警告对中日文有害）；② 顺序写死，不给用户拖拽；
③ 递归最多 1 层，概率激活一律不要。

### 3.2 学术与机制：上下文为什么会坏

| 结论 | 证据 |
|---|---|
| **长上下文不是免费的**：相关信息在首尾时准确率最高，落在中间显著下降（U 形：首位 73.4% → 中部 50.5–51.8% → 末位 63.7%） | Liu et al., *Lost in the Middle*（[arXiv:2307.03172](https://arxiv.org/abs/2307.03172)） |
| **长度本身就是变量**：固定任务难度、只改长度，18 个模型一致退化；同一批 LongMemEval 题，focused ≈300 token 全面优于 full ≈113k token | Chroma《Context Rot》(2025-07)（[research.trychroma.com/context-rot](https://research.trychroma.com/context-rot)） |
| **厂商宣称的 128K/200K 普遍缩水**：32K 上只有一半模型能维持「≥ Llama-2-7B@4K 的 85.6%」（RULER）；13 个长上下文模型中 11 个在 32K 跌破自身短上下文基线的一半（NoLiMa） | RULER（[arXiv:2404.06654](https://arxiv.org/abs/2404.06654)）、NoLiMa（[arXiv:2502.05167](https://arxiv.org/abs/2502.05167)） |
| **长上下文 vs RAG 无普适赢家**：字面匹配任务 RAG 强，非字面问法长上下文强（PassKey 改写后 4.58% vs 69.32%） | EMNLP 2024 industry（[arXiv:2407.16833](https://arxiv.org/abs/2407.16833)） |
| **结构化摘要不能替代原文**：固定检索/重排/判分管线只换存储表示，原文 chunk 比结构化摘要高 15.9 点（LoCoMo 43.9 vs 28.0）、22.0 点（LongMemEval-S 67.4 vs 45.4）；抽取式管线甚至低于 naive RAG | *Fidelity Before Structure*（[arXiv:2601.00821](https://arxiv.org/abs/2601.00821)，**单作者 preprint，需谨慎**） |
| **分层记忆主要买的是延迟**：Mem0 在 LOCOMO 上全量上下文 26,031 tok / p95 17.1s / judge 72.90，Mem0 1,764 tok / 1.44s / 66.88；LangMem 只 127 tok 却要 60s → 省 token ≠ 省延迟 | Mem0（[arXiv:2504.19413](https://arxiv.org/abs/2504.19413)） |
| **记忆必须分层/分页**：主上下文 + 可换入换出的外部记忆，用函数调用显式搬运 | MemGPT（[arXiv:2310.08560](https://arxiv.org/abs/2310.08560)） |
| **写入要有判定，不是无脑追加**：ADD / UPDATE / DELETE / NOOP，且更新优于堆叠 | Mem0（[arXiv:2504.19413](https://arxiv.org/abs/2504.19413)）；A-Mem 的 Zettelkasten 式笔记与动态重写（[arXiv:2502.12110](https://arxiv.org/abs/2502.12110)） |
| **时间是结构，不是文本**：双时间轴（事件时间 vs 记录时间）才能回答"她当时说过什么、后来改口了吗" | Zep / Graphiti（[arXiv:2501.13956](https://arxiv.org/abs/2501.13956)）；MemoryBank 的遗忘曲线 `R = e^(-Δt/S)`（[arXiv:2305.10250](https://arxiv.org/abs/2305.10250)） |
| **检索要多信号融合**：相关性 + 新鲜度 + 重要性，单靠 cosine 不够 | Generative Agents：`α·recency + α·importance + α·relevance`，recency = 0.995^小时（[arXiv:2304.03442](https://arxiv.org/abs/2304.03442)）；HippoRAG 的图上的 PPR 扩散（[arXiv:2405.14831](https://arxiv.org/abs/2405.14831)、[2502.14802](https://arxiv.org/abs/2502.14802)） |
| **分层 + 热度迁移**：STM / MTM / LPM 之间按访问热度晋升与淘汰 | MemoryOS（[arXiv:2506.06326](https://arxiv.org/abs/2506.06326)） |
| **评测必须问"跨会话"**：单轮 QA 看不出记忆好坏 | LoCoMo（[arXiv:2402.17753](https://arxiv.org/abs/2402.17753)）、LongMemEval（[arXiv:2410.10813](https://arxiv.org/abs/2410.10813)）、MemBench（[arXiv:2506.21605](https://arxiv.org/abs/2506.21605)） |

**四个直接含义**：

1. 目标不是"把窗口塞满"，而是"用最少的 token 命中此刻需要的那几条"——这正好要求一个场景对象来定义"此刻"。
2. 时间必须是一等字段（`event_time` / `valid_from` / `valid_to`），而不是摘要里的一句"最近"。
3. 写入必须带判定与版本（NOOP / ADD / SUPERSEDE），否则记忆库会变成互相矛盾的堆栈。
4. **原文是无损基座，结构是叠加层**：摘要只能做导航（"这里有件事"），召回必须回原文取 quote；
   任何"用摘要替换 transcript"的做法都要按上表的数据预期掉 15–22 点。

> 说明：中文情感陪伴场景没有公开基准（LoCoMo 是英文、LongMemEval 偏助理任务），
> 所以 §11 的 200 题必须自建，不能引用论文数字当验收。

### 3.3 生产实践：别人怎么压、怎么缓存

来源：Claude Code 官方文档（context-window / memory / prompt-caching / costs / how-claude-code-works）、
OpenAI 官方文档（prompt-caching / conversation-state / compaction / agents-python sessions / memory FAQ）、
Anthropic context-editing、LangGraph/LangChain 官方文档、Cursor rules、Aider repo map、Manus 工程博客。

| 结论 | 证据 |
|---|---|
| **成本第一指标是前缀缓存命中率**，不是总 token | Manus：agent 的 input:output ≈ **100:1**，"KV-cache hit rate is the single most important metric"（[manus.im/blog](https://manus.im/blog/Context-Engineering-for-AI-Agents-Lessons-from-Building-Manus)） |
| **反模式：静态 system 后紧跟"时间戳 + 用户消息"，断点打在这块 → 永不命中** | Manus 原话 "putting a timestamp at the top of the system prompt is a common mistake"；Anthropic/OpenAI 文档均强调缓存是**前缀匹配**，前缀一变整段失效 |
| **摘要要作为"对话项"注入，不要塞 system 头部** | Claude Code 把 CLAUDE.md 以 user message 形式投递在 system 之后（"not as part of the system prompt itself"），plan/skill 同样作为消息追加（[code.claude.com/docs/en/memory](https://code.claude.com/docs/en/memory)） |
| **压缩在轮次之间/空闲时做，绝不阻塞流式** | OpenAI 官方：自动压缩会让 stream 拖尾数秒，低延迟场景应自己 `run_compaction({"force": true})`（[openai.github.io](https://openai.github.io/openai-agents-python/sessions/)） |
| **压缩要防抖**：连压几次仍超限就停并报错，不无限循环 | Claude Code 官方："after a few attempts it stops and reports an error"（[how-claude-code-works](https://code.claude.com/docs/en/how-claude-code-works#when-context-fills-up)） |
| **压缩后重注入可枚举清单**，别指望摘要记得住 | Claude Code "what survives compaction"：最近读改文件 ≤**5** 个、单文件 >**5000 token** 只留路径、skill body 每 **5000** / 总 **25000** token（[context-window](https://code.claude.com/docs/en/context-window)） |
| **工具输出是最该回收的垃圾**，且"清够多才值得这次缓存失效" | Anthropic context-editing：`trigger 30000 / keep tool_uses 3 / clear_at_least 5000 / exclude_tools`；LangChain 警告删消息必须保持 `tool_call` / `tool_result` 成对 |
| **服务端保存上下文不等于省钱** | OpenAI：即使用 `previous_response_id`，链上全部历史 input token 仍计费（[conversation-state](https://platform.openai.com/docs/guides/conversation-state)） |
| **摘要从原文重算，不做"摘要的摘要"**；原文是唯一事实源 | Claude Code 压缩后从磁盘重读；ChatGPT 的 memory summary 明确只是"高层视图、不保证完整" |
| **没有官方 token 预算比例**（三家都只给单项硬上限） | 只有硬锚点：CLAUDE.md ≤200 行、Aider repo map 默认 1k token、LangChain `trim_tokens_to_summarize=4000`、OpenAI 最小可缓存 1024 visible token |

**四条直接约束本设计**：

1. 我们的 system prompt 现在是「人设 → **每轮都变的记忆块** → 规则 → **秒级时间**」——正好踩中官方点名的反模式。
   槽 1–3 必须逐字节稳定，秒级时间挪到末尾（§5.1、§5.4）。
2. 摘要必须是**带版本与源区间的派生物**（`summary_id` + `from_turn/to_turn`），注入在**静态前缀之后**
   （system 尾部或独立上下文块均可），关键是不得进入静态头。
3. 工具结果入口截断 + 旧结果按块清理（`clear_at_least`），且保持调用/结果成对——这条独立于记忆重构，P0 就该做。
4. 压缩只发生在轮间（场景关闭时正好是天然压缩点），流式回复期间不做任何裁剪。

> 注：我们的静态头（product + persona + core）约 940 token，**低于 OpenAI 的最小可缓存 1024 token**，
> 也低于 Anthropic 部分模型的 2048 档。P0 需要把静态头做到 ≥1200 token（补角色示例即可），
> 否则缓存根本不会生效。

### 3.4 对槽位设计的校准

- 学界/业界都没有"人设占多少"的权威配比；§5.1 的比例是工程判断，必须用 §11 的评测回验。
- 唯一可外部对标的硬约束是**缓存最小块**与**压缩重注入清单**，两者都已写进 P0/P1 验收。

---

## 4. 统一模型：场景卡是接缝

### 4.1 一张图

```
用户消息 ──► [规则] 更新场景卡 ──► 检索(query=场景卡) ──► 预算组装 ──► 模型 ──► 回复
                    ▲                                              │
                    └──────── 承诺/约定写回 openLoops ◄────────────┘
                    │
             场景关闭（话题转移/闲置/轮数到）
                    │
                    ▼
             Episode 抽取 ──► 记忆库(core / fact / episode / reflection)
```

### 4.2 三个对象

| 对象 | 生命周期 | 存放 | 作用 |
|---|---|---|---|
| **SceneCard** | 一次连续对话（分钟–小时） | Redis `scene:{conversationId}`，TTL 6h 滑动 | 注入 + 检索 query + 抽取边界 + 承诺账本 |
| **Episode** | 一次场景关闭后永久 | MySQL `memory_episode` + Milvus | 可召回的事件，**带原话**与时间 |
| **Memory** | 长期 | MySQL `memory_meta`（四层）+ Milvus | core（设定）/ fact（双时间轴）/ reflection（日记/模式） |

### 4.3 闭环怎么闭

1. **注入**：场景卡渲染成 ≤220 token 的「此刻」块，替代现在 600 字摘要的位置。
2. **检索**：query = 场景卡.topic + openLoops + 最后一条用户消息；召回结果与最近窗口去重后注入。
3. **写回**：模型回复里出现"我答应/明天/周末"→ 规则写入 `openLoops`；用户确认兑现 → 关闭并写 fact。
4. **抽取**：场景关闭时，用**整段场景**（不是单条消息）抽 episode/fact，质量远高于每轮抽 3 条。
5. **回流**：夜间整合把重复 episode 合并、fact 衰减、生成 reflection（已有 `character_diary` 可复用）。

### 4.4 SceneCard 结构（Redis，渲染 ≤220 token）

```json
{
  "version": 7,
  "updatedAt": "2026-11-06T12:40:00+08:00",
  "turnFrom": 41,
  "turnTo": 47,
  "topic": "她加班到很晚，想被哄",
  "mood": { "user": "低落", "character": "心疼", "intensity": 0.6 },
  "beats": [{ "t": "她说不想当大人了", "at": 3 }, { "t": "问她晚饭吃了没", "at": 6 }],
  "openLoops": [{ "what": "周末陪她去看猫", "due": "2026-11-08", "status": "open", "quote": "……" }],
  "callbacks": ["团子（她养的猫）"],
  "avoid": ["别提她妈"]
}
```

- 上限：`beats ≤4`（每条 ≤18 字）、`openLoops ≤3`、`callbacks ≤3`、`avoid ≤3`；超了丢最旧，宁可少。
- 更新：每条用户消息后走**规则**（关键词/日期/情绪词/承诺句式）更新 `mood` 与 `openLoops`；
  每 6 轮或话题切换时用一次小模型刷新 `topic/beats`（输出 JSON，校验失败保留旧卡）。
- 渲染示例（注入槽 5）：

```
此刻：她加班到很晚，想被哄；她刚说"不想当大人了"。
你说过周末陪她看猫（11-08 前，未兑现）。可以自然提到：团子。别提她妈。
```

- 关闭时整卡序列化进 `memory_episode.scene_json`，作为抽取输入与回滚依据。

---

## 5. 上下文预算与槽位

### 5.1 九个槽位（顺序写死）

| # | 槽位 | 内容 | 预算 | 变化 | 淘汰 |
|---|---|---|---|---|---|
| 1 | `product` | 产品规则：安全、不得自称 AI、语言 | 180 | 静态 | 永不 |
| 2 | `persona` | 角色人设 + 说话风格 + 少量示例 | 500 | 角色级静态 | 永不 |
| 3 | `core` | 姓名/称呼/雷点/仪式/世界设定 constant | 260 | 低频 | 先截条目，保姓名/雷点 |
| 4 | `relation` | 阶段 + 相处天数 + 最近 2 条事件 | 120 | 低频 | 丢事件 |
| 5 | `scene` | 场景卡（此刻） | 220 | 每轮 | 丢 callbacks → 丢 beats |
| 6 | `recall` | 召回 4–6 条，带 `[11-03] "原话"` | 400 | 每轮 | 6→4→2 |
| 7 | `archive` | 更早场景的一行摘要（最多 6 行） | 300 | 每场景 | 丢最旧 |
| 8 | `recent` | 原文最近 N 轮 | 1400 | 每轮 | 从最旧丢，保最后 4 条 |
| 9 | `director` | 本轮导演指令（≤2 句） | 80 | 每轮 | 永不 |

合计约 **3.4k token**（不含工具 schema 与输出）。空出来的窗口留给模型思考与长回复，而不是塞满。

### 5.2 淘汰序（写死，加断言测试）

```
callbacks → beats → 老 archive 行 → recall 从 6 条减到 2 条 → recent 从最旧丢（保最后 4 条）
永不淘汰：product / persona / core 的姓名与雷点 / director
```

### 5.3 token 估算与校准

- `estimate_tokens(text)`：CJK 按 1 字 ≈ 0.66 token，ASCII 按 4 字符 ≈ 1 token，标点按 0.5；
- 每次模型响应读 `usage.prompt_tokens`，与估算值对比，滚动校准系数（按 provider+model 分别存）；
- 预算判断用「估算 × 1.1」保守值，避免低估导致 400。

### 5.4 缓存布局（省钱的第一杠杆）

- 请求顺序固定为 **tools → system（槽 1–3）→ messages（槽 4–9 的内容不塞 system）**；
- 槽 1–3 逐字节稳定 → 放在最前，形成可缓存前缀（角色维度），且**补到 ≥1200 token**（低于 1024 时 OpenAI 不缓存）；
- 槽 4–9 全部放在前缀之后；时间锚**只到分钟**且放在 `director` 之前；
- 目标：同一角色连续对话时，**前缀命中率 ≥80%**（记录 `cached_tokens` / `prompt_cache_hit_tokens`）；
- 任何"每轮都变"的内容（随机 ID、秒级时间、动态排序、每轮重写的摘要）一律不得进入槽 1–3；
- 单测：同一会话连跑两轮，断言槽 1–3 拼接结果的 sha256 不变（`backend-agent/tests/test_prefix_stability.py`）。
- 收益量级（Anthropic 官方实测）：命中缓存后成本 −90%、延迟 −85%；「多轮 + 长 system prompt」约 10s → 2.5s。
  换算到 prefill 速度（≈8.7k tok/s 未缓存），2s 预算只够 ~17k 未缓存 token，命中后 100k 仍 2.4s——这就是为什么
  「每轮都变的 system」是最贵的写法。

### 5.5 组装形状（provider 无关，最稳）

```python
system = static_head(product, persona, core)        # 逐字节稳定，≥1200 token
       + dynamic_tail(relation, scene, recall, archive, director)

messages = history(recent, ≤1400 token) + [current_user_message]
```

- **不塞"假的 user 消息"**：部分 provider 不接受对话中途的 system，也会打乱角色交替；
  动态内容全部放 system 尾部，缓存效果一样（前缀在第一个字节差异处截断）。
- `dynamic_tail` 内部顺序固定：关系 → 场景 → 召回 → 归档 → **导演（system 的最后 2 行）**。
- 关键约束（安全、不得自称 AI、称呼、雷点）在 `product`（头）与 `director`（尾）**各出现一次**：
  Lost in the Middle 的 U 型 + 因果注意力都说明首尾最稳，中间最容易被忽略。
- 时间戳只到分钟，放在导演之前；任何随机 ID / 随机排序不得出现在 `static_head`。

---

## 6. 读取：用场景卡当 query

### 6.1 查询构造

```
q_text = scene.topic ×0.8 + openLoops.what ×0.9 + last_user_message ×1.0
```

时间过滤：`event_time` 在最近 180 天内优先；`status != 'archived'`；`character_id` 必须匹配（core 层额外允许全局）。

### 6.2 五路召回

| 路 | 来源 | 条数 | 目的 |
|---|---|---|---|
| A core | `memory_meta.layer='core'` | 全量（≤12） | 设定/雷点/称呼，永不漏 |
| B fact | `subject/predicate` 命中 query 实体 | 5 | "她忌口"这类硬事实 |
| C episode | 向量 + MySQL ngram 全文（RRF） | 8 | 具体发生过的事 |
| D reflection | `character_diary` / reflection | 3 | 模式与关系变化 |
| E recent | 最近 7 天 episode | 4 | 兜底近因 |

融合：`RRF(k=60)` → 按 `0.50*rel + 0.25*s_eff + 0.15*importance + 0.10*recency` 重排（见 §7.6.10）
→ **MMR(λ=0.7)** 去回声；`s_eff < 0.15` 的条目不进候选（强 cue 除外）。

### 6.3 与最近窗口去重

- episode 的 `source_msg_ids` 只要与 `recent` 槽重叠 → 不注入（模型已看到原文）；
- 与场景卡 `beats` 语义重复（cos ≥0.9）→ 不注入；
- 同一 `content_hash` 只出现一次；
- **冷却**：同一条记忆 3 天内已注入过 → 除非用户本轮直接提到它，否则不再注入（防"祥林嫂"）。

### 6.4 注入格式

```
=== 你记得的事（按时间，最近的在前） ===
[11-03·确定] 她说「不想当大人了」，那天加班到两点。（她主动说的）
[10-20·确定] 她养了一只猫，叫团子。
[10-12·确定] 你答应周末陪她去看猫 —— 还没兑现。
[09-28·模糊] 她好像提过想去看海，具体哪片海记不清了。
```

日期 + 原话 + 归属（她说/你说的），比现在的 `[用户画像] - ...` 更像"回忆"，也更容易被模型引用。

**置信标记是契约的一部分**：`确定` = `active`；`模糊` = `faded`/`archived`（渲染时自动加"好像/似乎"）。
system 里配一条硬规则：模糊条目不许说成确定、不许补细节；一条都没命中时，
默认台词是"我有点想不起来了，你提醒我一下"——**禁止编造**（§7.6.7）。

### 6.5 降级

向量不可用 → 只用 B/C 的 SQL 分支（`LIKE` + ngram）；LLM 抽取失败 → 只写原文 episode 不写 fact；
任何一路超时 80ms 直接跳过，不阻塞主链路。

### 6.6 召回必须回原文取原话（不是取摘要）

这是 *Fidelity Before Structure*（[arXiv:2601.00821](https://arxiv.org/abs/2601.00821)）给的硬约束：
同一套检索管线，只把存储从"原文 chunk"换成"结构化摘要"，LoCoMo 掉 **15.9** 点、LongMemEval-S 掉 **22.0** 点。
所以本设计的取数顺序是：

```
召回命中 → 取 memory_episode.quote / message 原文片段（≤120 字，按句边界裁）
        → 摘要只用于「挑选」与「排序」，不进入注入文本
```

- 注入的每一行都必须带 `quote`；没有 `quote` 的 fact 只能出现在 core 层（设定类），不能当"回忆"讲。
- `archive` 槽（更早场景的一行摘要）只作**导航**：它告诉模型"更早还有这些事"，不承载事实细节。
- 评测要单列一档"结构 vs 原文"的消融，防止我们悄悄用摘要替换原文（§11）。

---

## 7. 写入：场景关闭才抽取

### 7.1 触发条件（满足其一）

- 闲置 ≥45 分钟；
- 话题转移（连续 3 条用户消息与 `scene.topic` 的 cos <0.35，且新话题持续 3 轮）；
- 场景轮数 ≥60；
- 用户手动"换个话题/结束"。

### 7.2 抽取 schema（一次调用，输出 ≤600 token）

```json
{
  "episodes": [{"title": "...", "quote": "原话", "when": "2026-11-06", "who": "user",
                "valence": -0.6, "arousal": 0.7, "open_loop": null}],
  "facts": [{"subject": "user", "predicate": "likes", "object": "热可可",
             "confidence": 0.8, "quote": "...", "asserted_by": "user"}],
  "relationship": {"delta": "trust+1", "reason": "她第一次说了家里的事"}
}
```

`quote` 必填：没有原话的记忆不允许落库（这是"她真的记得"的来源，也是评测的证据）。

抽取调用的边界：输入 = 关闭时的场景卡 + 该场景全部消息（>6000 token 时按轮抽稀，保留用户消息优先）；
输出必须过 JSON schema 校验；失败重试 1 次，仍失败则只落一条 `raw episode`（原文 + 时间），不写 fact。

### 7.3 去重与 supersede

- `content_hash` 全等 → NOOP；
- 与同 `(character, subject, predicate)` 的现有 fact 比 cos：
  - ≥0.92 → NOOP；<0.75 → ADD；灰区 → 一次 LLM 判定（ADD / UPDATE / SUPERSEDE / NOOP）；
    当前实现（PR-21）灰区按 ADD、不调模型——误合并比多记一条更难收拾；
- 更新不物理删除：旧行 `valid_to = now()`、`status='superseded'`、`superseded_by = 新 id`；
- `importance` 用锚点示例校准（0.9 姓名/创伤、0.75 强情绪、0.6 偏好、0.4 闲聊）。

### 7.4 openLoops 生命周期

`open`（说出约定）→ `reminded`（被提起）→ `done`（兑现）/ `expired`（过期 7 天）。
每条 openLoop 同时是一条 `fact(predicate='promise', status='open')`，两边通过 `fact_id` 关联。

### 7.5 夜间整合与衰减（拆成两段）

- **03:00 Java 侧**：`MemoryDecayService` 结算 `strength` 并分档（纯算术，无模型；见 §7.6.3/§7.6.9），
  顺带把 `dormant` 的向量下沉、`openLoop` 过期的标 `expired`。
- **03:30 Python 侧**：合并重复 episode（cos ≥0.9）、生成 reflection（单幕印象已在场景结算时产出；
  跨天周记走 `app/memory/consolidate.py`，`POST /agent/memory/consolidate` 由 cron 触发），
  对账 `embedding_state`。衰减不在这里算——两边各算一遍必然会漂。

放在夜间是刻意的：sleep-time compute（[arXiv:2504.13171](https://arxiv.org/abs/2504.13171)）报告同精度下
可省约 5x 在线算力；反过来，把整合放进回复链路会直接抬高 TTFT。

### 7.6 遗忘：降权，不是删除

#### 7.6.1 先说实话：现在没有遗忘机制

- `backend-agent/` 全库 grep `decay|遗忘|forget|fade` = **0 命中**；Java 只有 `EmotionDecayScheduler`
  与 `RelationshipDecayScheduler`，记忆一个都没有。
- 现在被误认为"遗忘"的其实是三件事：写入时 `importance < 0.5` 丢弃（**没记住**）、
  召回只取 importance 前 20（**检索不到**）、用户手动删除（**失忆手术**）。
- 结果就是两个极端：要么记得太死（三年前一句闲话还拿出来说），要么突然全忘——这正是
  Character.AI 被用户骂"固定截断点导致突然全忘"的同款问题。

**结论：遗忘要设计成"可及性下降"，不是"行消失"。**

#### 7.6.2 四条原则

1. **遗忘作用于可及性，不作用于存在性**：行还在、原话还在，只是越来越难想起来。
2. **细节先忘，结论后留**（gist survives detail）：`episode` 的原话先淡，`reflection` 里
   "你们那阵子在闹别扭"留得久；这也是人记得"那次很生气"却忘了"具体说了什么"的原因。
3. **遗忘可逆、表达有梯度**：记得 / 有点模糊 / 想不起来，三档台词必须不同；没有梯度就只剩"全知"和"失忆"。
4. **情绪只调可及性，不调保真度**：吵架那晚会被反复想起，但细节并不因此更准——
   闪光灯记忆的实验结论是"信心更高、一致性并不更好"（Talarico & Rubin 2003）；
   所以 `arousal/valence` 只进 `tau_days`，绝不进"这条更可信"的判断。

#### 7.6.3 强度模型

每条记忆存两个数：`strength`（上一次结算后的值）与 `tau_days`（该条的半衰参数），
读取时**惰性求值**：

```
s_eff = clamp(strength * (0.7·e^(−Δdays/τ_fast) + 0.3·e^(−Δdays/τ_slow)), 0, 1)
τ_fast = tau_days / 6     τ_slow = tau_days × 2.5     // Δdays = now − last_decay_at
```

**为什么是两条指数**：单指数拟合不了人类的遗忘曲线——Murre & Dros 2015（PLOS ONE 10(7):e0120644）
用 1885 年原始数据重拟合，幂律/对数式明显优于单指数，实测 20 分钟≈57%、1 小时≈47%、1 天≈30%、
1 周≈25%：早期掉得很快，之后有一条很长很长的尾巴。两条指数叠加是对这条曲线的工程近似，
既保留"先陡后慢"，又能被 Java 和 Python 用同一行公式算出来。

| 层 | `tau_days` | 说明 |
|---|---|---|
| `core` / `pinned` | 0（表示 ∞） | 设定与用户 pin 的，不衰减；只能被用户改 |
| `fact`（身份/偏好/忌口） | 365 | 说错会立刻穿帮，宁可慢 |
| `relation`（关系结论） | 730 | "她怕黑"这类，比事件活得久 |
| `episode` | `30 × (0.7 + 1.2·arousal) × (1 + 0.6·\|valence\|)` | 吵架（−0.8, 0.9）≈79 天；普通闲聊（0, 0.1）≈25 天 |
| `reflection` | 180 | 已经是结论，降得慢 |

- 写入时就把 `tau_days` 算好存下来（episode 用 valence/arousal，其余按层）。
  `tau_days = 0` 表示"不衰减"，读取时直接 `s_eff = 1`；SQL 里用 `tau_days > 0` 过滤，避免除零。
- `boost`：被注入一次 `+0.05`（同一条每天最多一次）；用户主动提到 `+0.15`；纪念日回访 `+0.1`。
  boost 发生时**立刻结算**：`strength = s_eff + boost`、`last_decay_at = now`。
- **不做每日全表更新**：指数衰减可组合，`s_eff` 在读取时算一次即可（每条 ≤2 次 `exp`）；
  夜间只处理"跨档"的行（见 7.6.9），避免 10 万行表每天扫一遍。
- 单日降级上限 30 条/角色，按 `s_eff` 升序——防止"一夜失忆"。
- 写入时不预扣强度：写的时候不知道它以后会不会被想起，衰减只由"时间和使用"决定。

#### 7.6.4 四档与行为

| 档 | `s_eff` | 存储 | 检索 | 台词 |
|---|---|---|---|---|
| `active` | ≥0.60 | 全量 | 常规五路 | 确定口吻："你上次说…" |
| `faded` | 0.30–0.60 | 全量 | 相关度高才进 recall，注入 gist | "我记得你好像提过…" |
| `archived` | 0.15–0.30 | 全量，向量保留但打 `fade_level=archived` | 默认查询过滤掉；只有强 cue 才带上这一档 | "让我想想……是不是…" |
| `dormant` | <0.15 且 90 天未召回 | 只留 MySQL 行 + quote，`embedding_state='dropped'` 并删向量 | 只走 ngram/关键词；不参与常规召回 | 不主动提；被问就说"太久啦，你提醒我一下" |

- `dormant` **不是删除**：记忆页仍能搜到，用户点一下即可复活。
- 记忆页把 `fade_level` 显示成「清晰 / 有点模糊 / 沉底」三档，并提供四个动作：pin（永不衰减）、
  "让她想起来"（触发一次强 cue 复活）、"别再提"（`muted=1`）、删除（真删，二次确认）。
- **`muted` 与删除不同**：置 `muted=1` 后这条仍可被检索、用户直接问起时也答得出，
  但**永不主动浮现**、不进日常注入——对应 ChatGPT 的 "Don't mention this again"：只减引用，不删源。
- **硬删（purge）只有三种**：用户主动删除；账号注销；`dormant` 且 24 个月零命中（可配置）。
- `superseded` 的旧事实不衰减、不召回、保留审计（它已经不是"记忆"，是"历史版本"）。

#### 7.6.5 复活（tip-of-the-tongue）

- 触发：`cos ≥ 0.85` 或实体精确命中（名字/日期/地点），且该条处于 `archived`/`dormant`。
- 动作：`strength = max(strength, 0.55)`、`status='active'`、`revival_count++`，并在本轮**显式演出来**：
  "啊——我想起来了，你那次……"。这一下比任何召回指标都更像人。
- 强 cue 但确实没命中：也要演——"我有点印象，但记不清了，你再说说？"（**禁止编造**，见 7.6.7）。
- **再巩固窗口**：一次召回后的 24 小时内，如果用户补充/纠正了细节，允许**就地改写**这条
  （`revision+1`，旧版本进 `memory_episode` 留审计），而不是另写一条新的；窗口之外只能 `supersede`。
  依据是 Nader 等 2000（Nature 406:722）：记忆在召回后的一段时间里是可塑的，这段时间里改写最像人。

#### 7.6.6 主动浮现（不是随机推送）

- 三类触发：`event_time` 周年（认识 100 天、第一次吵架一年）；`openLoop` 到期（"你答应过…"）；
  `s_eff` 在中段（0.35–0.55）且 30 天未被提及。
- 每角色每周 ≤1 次，且必须与当前场景卡 `topic` 弱相关（cos ≥0.2），否则就是硬找话题。
- 目的：让遗忘**看起来像人**——不是均匀掉线，而是"突然想起"。

#### 7.6.7 不许编造（遗忘的阴暗面）

- `faded`/`archived` 的记忆只能以**不确定语气**注入，注入行带 `confidence` 标记；prompt 明确
  "不确定的事用'好像/是不是'，不许补细节"。
- 用户纠正 → 立刻 `supersede` + `strength=1` + `correction_count++`；同一条被纠正两次 →
  降为 `uncertain` 并停止主动提。
- 这条比"记得多"重要：**忘记可以原谅，编造不行**。

#### 7.6.8 与关系/情绪的耦合

- 关系阶段 `INJURED`/`REPAIRING`：负性 `episode` 的 `τ_eff × 0.7`（淡得快一点），但**不删**；
  回到 `STABLE_INTIMATE` 后恢复 `× 1.0`。这是"恋人和好"，不是"篡改记忆"。
- 高唤醒正性记忆（`arousal ≥ 0.7` 且 `valence ≥ 0.5`）`τ × 1.3`。
- 已有 `EmotionDecayScheduler` 是 60 分钟 tick；记忆衰减**必须按天**，否则分数抖动、复现不了。

#### 7.6.9 工程落点（贴着现有约定）

**读时求值（Python，零写）**：召回候选 ≤200 条，逐条算
`s_eff = strength * (0.7·e^(−Δ/τ_fast) + 0.3·e^(−Δ/τ_slow))`，按 §7.6.4 选档与措辞。
这里不需要 Java 参与，也不需要写库。

**夜间结算（Java，只碰"要跨档"的行）**：

```sql
UPDATE memory_meta
   SET strength = strength * (
         0.7 * EXP(-DATEDIFF(NOW(), last_decay_at) / (tau_days / 6))
       + 0.3 * EXP(-DATEDIFF(NOW(), last_decay_at) / (tau_days * 2.5))),
       last_decay_at = NOW(),
       fade_level = CASE ... END
 WHERE status = 'active' AND pinned = 0 AND tau_days > 0
   AND last_decay_at < NOW() - INTERVAL 7 DAY
   AND strength * (
         0.7 * EXP(-DATEDIFF(NOW(), last_decay_at) / (tau_days / 6))
       + 0.3 * EXP(-DATEDIFF(NOW(), last_decay_at) / (tau_days * 2.5))) < 0.30
 ORDER BY last_decay_at ASC
 LIMIT 2000;
```

（`CASE` 段按 §7.6.4 的阈值映射 `fade_level`，此处从简；真实迁移里这段逻辑放 Service，SQL 只做筛选。）

- 与 `RelationshipStateService.applyDecay()` 同构，但**不做每日全表**：只捞"已经掉到 0.30 以下"的行，
  10 万行表一天也就几百条；`ORDER BY last_decay_at ASC` 轮转，**不要按 `created_at`**——
  关系衰减踩过：线上 936 行只有 502 行轮到过（`RelationshipStateService.java:172-190` 注释）。
- `dormant` 落档时同时删向量、`embedding_state='dropped'`；`archived` 只改 `fade_level`。
- 单日降级上限 30 条/角色；`MAX_DECAY_DAYS=30` 只用于**结算时的 Δ 封顶**，避免久未上线一次性跌到底。
- 测试要断言"两批覆盖不同 id"（防轮转饿死），以及"跑两次结果一致"（幂等）。

**写时（boost）**：注入/被提到时立刻 `strength = s_eff + boost`、`last_decay_at = now`——
一次主键 UPDATE，不进批处理。

**配置**：`lianyu.memory.decay.enabled=true`、`cron=0 30 3 * * *`、`batch-limit=2000`、`daily-demote-cap=30`。

#### 7.6.10 检索打分修正

```
score = 0.50*rel + 0.25*s_eff + 0.15*importance + 0.10*recency
```

`s_eff < 0.15` 不进候选（除强 cue）。理由：`s_eff` 已经把"时间"和"被用过"编码进去了，
再单列 recency 就是重复计权——Generative Agents 的 recency 与 MemoryBank 的 `R` 本来就是同一个东西的两种写法。

#### 7.6.11 指标

- **误忘率**：用户问"你还记得"时，目标条 `s_eff < 0.3` 的比例（目标 <10%）
- **过度记忆率**：同一事实 7 天内被主动提两次以上（目标 <2%）
- **复活成功率**：强 cue 后正确复述（目标 ≥70%）
- **编造率**：`faded`/`archived` 记忆被说成确定事实的比例（目标 0）
- **分布健康度**：active/faded/archived/dormant ≈ 55/25/15/5；某一档 >70% 说明 τ 或上限配错了

#### 7.6.12 为什么不用 TTL 直接删

TTL 会让"三年前她说过的话"在某天**突然消失**，用户感知是"角色换人了"；而不删只降权，
最坏的结果是"想不起来"，这恰恰是可以演、也可以被原谅的。**删除是产品动作，遗忘是体验动作**。
Zep/Graphiti 也是这个路子：矛盾事实把旧边的 `t_invalid` 置为当前时间，**只失效、不删除**。

#### 7.6.13 每个取舍背后的证据

| 设计选择 | 证据 | 出处 |
|---|---|---|
| 不删、只降可及性 | 旧事实 `t_invalid` 失效而非删除 | Zep/Graphiti, arXiv:2501.13956 |
| 两条指数近似"先陡后长尾" | 20min≈57%、1d≈30%、1w≈25%，幂律优于单指数 | Murre & Dros 2015, PLOS ONE 10(7):e0120644 |
| 被想起才变强（boost） | 提取练习一周后 61% vs 40%；MemoryBank `S += 1` | Roediger & Karpicke 2006；arXiv:2305.10250 |
| 细节先忘、结论后留 | gist 比 verbatim 活得久 | Brainerd & Reyna 2015, Dev Rev 38:1 |
| 情绪只调可及性、不调保真 | 闪光灯记忆信心高但一致性并不更好 | Talarico & Rubin 2003, Psych Sci 14(5):455 |
| 遗忘是为了"变概括" | 遗忘有适应性价值，不只是容量问题 | Richards & Frankland 2017, Neuron 94(6):1071 |
| 夜间整合 | 睡眠期的离线巩固 | Rasch & Born 2013, Physiol Rev 93(2):681 |
| 召回要配额、防"祥林嫂" | 提取诱发遗忘：想起 A 会抑制 B | Anderson/Bjork 1994；Cuc/Hirst 2007 |
| 召回后可改写（24h 窗口） | 再巩固窗口内记忆可塑 | Nader et al. 2000, Nature 406:722 |
| 用户主权：pin / 别再提 / 删 | ChatGPT "Don't mention this again"；Character.AI 长按 pin 免整理 | OpenAI Memory FAQ；Character.AI Memory 博客 |

产品侧的对照很清楚：**Kindroid 是唯一在官方文档里承认"1 个月前的事比 1 天前更模糊"的产品**，
ChatGPT / Character.AI / Replika 都只有"选择性 + 用户主权"、没有时间维度。也就是说，
"有据可依地变模糊"这件事没有现成产品可以照抄，τ 和阈值只能我们自己标定（见 §11 的 A/B）。

**没找到的**：不遗忘 / 均匀衰减 / 分层衰减三者的定量消融（FadeMem 只做了组件级消融，
MemoryBank 自认简化且无对照）；"因记错被投诉"的一手报道。这些只能靠我们自己的 200 题 + 线上指标补。

---

## 8. 数据与接口

### 8.1 Redis

| key | 值 | TTL |
|---|---|---|
| `scene:{conversationId}` | SceneCard JSON + `version` | 6h 滑动 |
| `scene:hist:{conversationId}` | 最近 3 个版本的卡（回滚用） | 6h |
| `ctx:stats:{conversationId}` | 最近 50 轮的槽位 token / 命中 / 丢词 | 24h |

保留 `session:summary:*` 直到 P1 验收，作为回滚路径。

### 8.2 MySQL（V58）

在既有 `memory_meta` 上加：`layer`、`subject`、`predicate`、`event_time`、`valid_from/valid_to`、
`status`、`superseded_by`、`source_episode_id`、`content_hash`、`embedding_state`、`pinned`、`muted`、`quote`、
`revision`、`strength`、`fade_level`、`last_decay_at`、`last_recalled_at`、`recall_count`、`revival_count`、
`archived_at`；新增 `memory_episode`（场景快照 + 原话 + 自己的 `strength`）；
`memory_meta` 建 `FULLTEXT ... WITH PARSER ngram`。
`memory_vectors_v3`：HNSW、COSINE、标量 `layer/status/event_time`（partition key 暂未启用——单集合按 character_id/user_id 过滤已够，等量级上来再切 partition）。

### 8.2.1 DDL 草案（示意；正式迁移见 `V58__memory_layers_and_episodes.sql`）

> V57 已被关系信任修复占用，正式迁移顺延为 V58；落地版去掉了 `AFTER` 子句、统一用
> `DATETIME`（与既有表一致），并补了 `tau_days / last_injected_at / archived_at` 与
> `idx_mem_decay`。

```sql
-- V58：四层 + 双时间轴 + 原话 + 去重哈希
ALTER TABLE memory_meta
  ADD COLUMN layer             VARCHAR(16)  NOT NULL DEFAULT 'fact' AFTER memory_type,
  ADD COLUMN subject           VARCHAR(64)  NULL,
  ADD COLUMN predicate         VARCHAR(64)  NULL,
  ADD COLUMN event_time        DATETIME(3)  NULL,
  ADD COLUMN valid_from        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  ADD COLUMN valid_to          DATETIME(3)  NULL,
  ADD COLUMN status            VARCHAR(16)  NOT NULL DEFAULT 'active',
  ADD COLUMN superseded_by     BIGINT       NULL,
  ADD COLUMN source_episode_id BIGINT       NULL,
  ADD COLUMN content_hash      CHAR(64)     NULL,
  ADD COLUMN embedding_state   VARCHAR(16)  NOT NULL DEFAULT 'pending',
  ADD COLUMN pinned            TINYINT(1)   NOT NULL DEFAULT 0,
  ADD COLUMN muted             TINYINT(1)   NOT NULL DEFAULT 0,
  ADD COLUMN quote             VARCHAR(512) NULL,
  ADD COLUMN revision          INT          NOT NULL DEFAULT 1,
  ADD COLUMN strength          DECIMAL(4,3) NOT NULL DEFAULT 1.000,
  ADD COLUMN tau_days          DECIMAL(6,1) NOT NULL DEFAULT 30.0,
  ADD COLUMN fade_level        VARCHAR(16)  NOT NULL DEFAULT 'active',
  ADD COLUMN last_decay_at     DATETIME(3)  NULL,
  ADD COLUMN last_recalled_at  DATETIME(3)  NULL,
  ADD COLUMN last_injected_at  DATETIME(3)  NULL,
  ADD COLUMN recall_count      INT          NOT NULL DEFAULT 0,
  ADD COLUMN revival_count     INT          NOT NULL DEFAULT 0,
  ADD COLUMN archived_at       DATETIME(3)  NULL,
  ADD KEY idx_mem_layer (character_id, user_id, layer, status),
  ADD KEY idx_mem_decay (status, last_decay_at),
  ADD KEY idx_mem_valid (character_id, user_id, valid_to),
  ADD UNIQUE KEY uk_mem_hash (character_id, user_id, content_hash),
  ADD FULLTEXT KEY ft_mem_summary (summary) WITH PARSER ngram;

CREATE TABLE memory_episode (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  character_id    BIGINT NOT NULL,
  user_id         BIGINT NOT NULL,
  conversation_id BIGINT NULL,
  scene_json      JSON NOT NULL,
  summary         VARCHAR(512) NOT NULL,
  quote           VARCHAR(512) NOT NULL,
  event_time      DATETIME(3) NOT NULL,
  valence         DECIMAL(3,2) NULL,
  arousal         DECIMAL(3,2) NULL,
  open_loop       TINYINT(1) NOT NULL DEFAULT 0,
  muted           TINYINT(1) NOT NULL DEFAULT 0,
  source_msg_ids  JSON NULL,
  content_hash    CHAR(64) NOT NULL,
  embedding_state VARCHAR(16) NOT NULL DEFAULT 'pending',
  status          VARCHAR(16) NOT NULL DEFAULT 'active',
  strength        DECIMAL(4,3) NOT NULL DEFAULT 1.000,
  tau_days        DECIMAL(6,1) NOT NULL DEFAULT 30.0,
  fade_level      VARCHAR(16) NOT NULL DEFAULT 'active',
  last_decay_at   DATETIME(3) NULL,
  last_injected_at DATETIME(3) NULL,
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_ep_time (character_id, user_id, event_time),
  KEY idx_ep_decay (fade_level, last_decay_at),
  UNIQUE KEY uk_ep_hash (character_id, user_id, content_hash),
  FULLTEXT KEY ft_ep_summary (summary) WITH PARSER ngram
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

注意：`WITH PARSER ngram` 依赖 `ngram_token_size`（默认 2，生产别改）；迁移文件保持 ASCII-only
（AGENTS.md §3.3），中文注释只能出现在本文。

### 8.2.2 Redis 写入时序

`scene:{id}` 用 `SET ... EX 21600` 滑动刷新；关闭时先 `scene:hist` 快照，再删 key；
Redis 不可用时降级为"从最近 12 条消息临时重建卡"，不阻塞回复。

### 8.3 Python 新模块

```
app/context/
  tokens.py      # estimate_tokens + 校准
  budget.py      # 槽位定义、淘汰序、断言
  scene_card.py  # 读/写/规则更新/LLM 刷新/关闭
  assembler.py   # 产 ContextPlan → system prompt + messages
app/memory/
  retrieve.py    # 五路召回 + RRF + MMR + 去重（按 strength 选档与措辞）
  extract_scene.py  # 场景关闭抽取
```

衰减公式只有一份（§7.6.3）：Python 读取时求值，Java 只在"跨档"时物化，两边不各算一套。

`prompt_pipeline.assemble_turn_prompt` 改为调用 `assembler.assemble(...)`；
`system_prompt.build_system_prompt` 拆成 `static_head()` + `dynamic_tail()`（保留旧函数作为 golden 基线）。

### 8.4 Java 改动

- `ConversationService`：聊天历史改为 `getRecentMessages(conversationId, 80)`（只是多给原始行，
  Python 按 token 裁），其它调用点保持 32；
- `clearMessages` → 同时删 `scene:{conversationId}`；
- 新增 `SceneCardPort`（可选）：Java 不需要理解卡内容，只负责失效与 TTL；
- 新增 `MemoryDecayScheduler` + `MemoryDecayService`：每日 03:30 结算 `strength/fade_level`，
  按 `last_decay_at ASC` 取批（`LIMIT 500`），单日降级上限 30；与 `RelationshipStateService.applyDecay`
  同构，**物化进库**供 Python 只读；
- `SessionSummaryService` 在 P1 之后由开关降级为 fallback。

---

## 9. 时序

**普通一轮**（目标 p95 ≤ 350ms，不含模型）：
`读卡(Redis, 1ms) → 规则更新卡 → 构造 query → 五路召回(并行, ≤80ms) → 预算裁剪 → 组装 → 调用模型`

**场景关闭**（异步，回复后）：
`快照卡 → LLM 抽取(≤600 tok 输出) → 去重/supersede → 写 episode/fact → 入 outbox → 清卡`

**夜间**：`03:00 Java 结算 strength/fade_level → 03:30 Python 合并 → reflection → 对账 → 重建索引`

### 9.5 成本与延迟的真实账（别夸大）

我们现在的上下文并不长：system ≈1.2k token + 32 条约 1.5–2.5k token ≈ **每轮 3–4k**。
所以这次改革的收益不是"省一大笔钱"，而是三件更实的事：

1. **缓存口径**：静态头 1.2k 命中后按 0.1x 计费，现状是每轮全 miss。按 Anthropic 实测（成本 −90%、
   多轮长 system 延迟 −75%），我们绝对量小，TTFT 大约省 0.2–0.4s；真正省钱的是**未来长会话**。
2. **质量**：召回从"importance 随机 top20"变成"场景相关的 4–6 条原话"——这是用户问"你还记得吗"时
   唯一能感知的差别。
3. **可运维**：有预算、有淘汰序、有埋点；长会话不会突然 400，也不会像 `memory_search` 那样静默降级。

| 指标 | 现在 | 目标 |
|---|---|---|
| 输入 token/轮 | 3–4k，长会话线性涨、无上限 | 3.4k ±10%，硬上限 |
| 静态前缀命中 | 0（每轮变） | ≥80% |
| 预填充 | 每轮全量重算 | 静态头命中，约省 0.2–0.4s |
| 召回条数 | 20 + 5，与场景无关 | 4–6，场景相关且带原话 |
| 超限保护 | 无 | 预算裁剪 + 写死淘汰序 |

---

## 10. 路线图

| 期 | 内容 | 工期 | 验收 |
|---|---|---|---|
| **P0** | 槽位重排（tools → 静态头 → 会话级 → 易变尾）、静态头补到 ≥1200 token、时间到分钟、token 估算与预算骨架、`gateway` 缓存字段打点、前缀稳定性单测、工具结果入口截断、修 `memory_search` 参数倒置 bug | 3–4 天 | 同一对话 `cached_tokens` 可见且 ≥50%；槽 1–3 的 sha256 两轮不变；prompt 不再随秒变化；`pytest` 全绿 |
| **P1** | SceneCard（规则 + 每 6 轮 LLM 刷新）、替换摘要注入、场景关闭归档 | 1 周 | 200 题评测中"跨轮指代/约定"类 ≥0.75；摘要漂移抽检 20 例无事实错误 |
| **P2** | 记忆 v3（四层 + episode/fact + 双阈值 + supersede）、`strength` 衰减结算（Java）、五路召回 + RRF/MMR + 去重 | 1.5–2 周 | recall@5 ≥0.8（200 题）；重复注入率 <5%；影子双写 diff <2% |
| **P3** | 夜间整合、pin/改/删/时间轴 UI（接星图）、缓存命中率与成本看板 | 1 周 | 用户可改一条记忆并在下一轮生效；日成本 ≤¥0.4 |

每期独立开关（`context.scene_card.enabled` / `memory.v3.enabled`），默认关，按角色灰度。

### 10.1 落地顺序（PR 拆分）

按"先修 bug、再腾位置、后换内容"排。每个 PR 独立可回滚，前后不夹带业务提交。

| # | 内容 | 主要文件 | 门禁 |
|---|---|---|---|
| PR-1 | 修 `memory_search` 参数倒置（P0 bug） | `backend-agent/app/tools/memory_search_tool.py:100`、新增 `tests/test_memory_search_tool.py` | 单测断言 filter 是 `character_id == 角色 && user_id == 用户`；日志能看到 milvus 命中 |
| PR-2 | 前缀分层 + 时间到分钟 + 前缀稳定性测试 | `system_prompt.py`、`prompt_pipeline.py`、`tests/test_prefix_stability.py` | 两轮 system 的槽 1–3 sha256 相同 |
| PR-3 | token 估算 + 预算骨架 + gateway 打点 | `app/context/{tokens,budget}.py`、`gateway.py` | 预算断言 + `ctx:stats` 落一行 |
| PR-4 | 工具结果入口截断 + 旧结果成对清理 | `app/tool_loop.py`、`app/context/assembler.py` | tool_call/result 成对；超限不 400 |
| PR-5 | SceneCard（读写/规则更新/6 轮刷新/关闭） | `app/context/scene_card.py`、`ConversationService`（只加删 key） | 卡 schema 校验；Redis 挂时能重建 |
| PR-6 | 场景关闭抽取 + `memory_episode`（V58 上半） | `app/memory/extract_scene.py`、`V58__memory_layers_and_episodes.sql` | 一次场景只抽一次；quote 必填 |
| PR-7 | 四层 + 双阈值 + supersede（V58 下半） | `app/memory/writer.py`、`relay.py` | cos 边界三例；旧版本 `valid_to` 有值、不物理删 |
| PR-8 | 五路召回 + RRF/MMR + 去重 + 影子 | `app/memory/retrieve.py`、`milvus_store.py` | recall@5；与 recent 去重率；影子 diff 日志 |
| PR-9 | **衰减结算**（`strength`/`fade_level`/`dormant`）+ 复活 | `MemoryDecayScheduler`、`MemoryDecayService`、`MemoryDecayServiceTest` | τ 表四层各一例；`MAX_DECAY_DAYS` 封顶；批轮转（不重复扫同一批）；单日降级 ≤30；pinned 永不降；跑两次结果一致 |
| PR-10 | 夜间整合 + reflection + 对账 | `app/memory/consolidate.py`（新）、定时任务 | 幂等；一天跑两次结果一致 |
| PR-11 | 前端星图接 layer/pin/`muted`/时间轴/`strength` | `MemoryPage.vue`、`memoryBrainModel.js`、`memory.js` | 四语 key 齐；星图/列表都不崩；`strength` 能映射成热度 |

PR-1 可以今天就合；PR-2～PR-4 是"腾位置"，不改任何记忆语义；PR-5 之后才开始动内容。

### 10.2 实施进度（2026-11-06）

| 计划 | 状态 | 落点 |
|---|---|---|
| PR-1 修参数倒置 | ✅ `c20b253` | `memory_search_tool.py` 调 `search(character_id, user_id, …)`；测试捕获实参 |
| PR-2 前缀分层 + 时间到分钟 | ✅ `8d210f8` | 静态头（人设/工具/回复/输出）在前，记忆/情绪/安全在后；`HH:mm`；parity 测试改为结构断言 |
| PR-3 token 预算 + 缓存打点 | ✅ `3423dc7` | `app/context/{tokens,budget}.py`；`GatewayResult` 带 cache hit/miss；`chat_turn` 只测不裁 |
| PR-4 工具结果卫生 | ✅ `3a605d7` | `clip_tool_output`（头 60% + 尾 30%）、`drop_orphan_tool_messages`；流式回灌同样截断 |
| PR-5 场景卡 | ✅ `3f2df18` | 规则更新 + 6 轮/漂移刷新 + Redis 6h；`clearMessages` 删 `scene:{id}` |
| PR-6 场景关闭抽取 + episode 表 | ✅ `8414f80` | `extract_scene.py` / `scene_close.py`；Java 侧透传 `conversationId` |
| PR-6.5 存储底座（V58） | ✅ `024fa09` | `memory_episode` + `memory_meta` 23 列 + ngram 全文；episode 幂等写入 |
| PR-7 召回纯函数 | ✅ `448faca` | `effective_strength` / 双路 RRF / MMR / 冷却 / 置信标记 |
| PR-8 v3 召回接线 | ✅ `d7acbe0` | `recall.py`：`MATCH…AGAINST` + core + 强度 + 冷却；开关 `memory_recall_v3_enabled` 默认关 |
| PR-8.5 episode 进召回 | ✅ `1f65670` | `_EPISODE_SQL`（`ep-` 前缀 id）作为第三路；`共同经历` 分组 |
| PR-9 衰减结算 | ✅ `a1825f7` | `MemoryDecayService/Scheduler`：四档、双时间尺度、每日降档配额、按 `last_decay_at` 轮转 |
| PR-11 前端接衰减/pin | ✅ `d261e79` `030fff3` `0921a75` | 列表返回 `memoryType/fadeLevel/strength/quote`；星图按强度压暗；抽屉显示原话/档位/钉住 |
| PR-12 槽位 supersede | ✅ `42c568f` | 同一槽位再次写入时旧行 `status='superseded'` + `valid_to` 关闭 + `superseded_by` 指向新行；不物理删 |
| PR-15 注入打点 | ✅ 本次 | 注入后写 `last_injected_at`/`recall_count`；core/pinned 不受 3 天冷却约束 |
| PR-14 静音（muted） | ✅ 本次 | `PATCH /memory/{id}/mute`；候选 SQL 已排除 `muted=1`；抽屉可切换"别再提/恢复提起" |
| PR-13 Milvus 向量路 | ✅ `search_hits` + `_vector_rows` | Milvus 只回 `memory_id`+分数，回 MySQL 取权威行；开关 `memory_vector_recall_enabled` 默认关 |
| PR-16 reflection 层 | ✅ `2dddae6` | 场景关闭那次调用多问一句 `impression`；写 `layer='reflection'`、tau 180d、7 天去重；不额外花钱 |
| PR-17 周记（跨天整合） | ✅ `81a9d64` | `app/memory/consolidate.py` + `POST /agent/memory/consolidate`；`MemoryConsolidateScheduler` 周一 04:10 触发（默认关）；同角色同周只一条；抽取式、不调模型 |
| PR-18 召回评测脚手架 | ✅ 本次 | `app/memory/evaluation.py`：JSONL 题集 → recall@k / MRR / 未命中清单；同一套题可对比开 v3 前后 |
| PR-19 历史按 token 裁剪 | ✅ 本次 | `app/context/history.py` + `chat_turn._trim_to_budget`：只为 recent 槽服务，开关 `history_trim_enabled` 默认关 |
| PR-21 语义去重（双阈值） | ✅ 本次 | `app/memory/dedupe.py`：cos≥0.92 NOOP / <0.75 ADD / 灰区默认 ADD（不调模型）；开关 `memory_dedupe_similarity_enabled` 默认关 |
| PR-20 上下文日统计 | ✅ 本次 | `app/context/stats.py` 写 Redis `ctx:stats:YYYYMMDD`；`GET /agent/context/stats` 读 `turns / est_tokens_per_turn / cache_hit_rate / trimmed_turns` |
| PR-30 共同经历可见 | ✅ 本次 | `GET /memory/episodes` + 记忆页第三个视图「经历」：显示原话、发生时间、没接住的话。事实层可以合并、原话不行，所以单列一层给人看 |
| PR-29 episode 也进冷却 | ✅ 本次 | V59 给 `memory_episode` 加 `last_injected_at`；`mark_injected` 按 `ep-` 前缀回写这张表，`in_cooldown` 对原话同样生效——否则同一段生动原话会每轮重复出现 |
| PR-28 记忆体检端点 | ✅ 本次 | `GET /agent/memory/health`：开关快照 + active/faded/superseded 条数 + episode 数 + outbox 积压 + 最后一次衰减/写入时间；无 episode、DEAD 积压、pending>50 会给 warning。开开关前先看它 |
| PR-27 召回块整行裁剪 | ✅ 本次 | `render_rows_limited` 按行卡 400 token 并返回真正进块的 id；只有这些行打 `last_injected_at`，被截掉的尾巴不会被误判成"看过"而进冷却 |
| PR-26 灰区模型判定 | ✅ 本次 | 0.75–0.92 灰区可选多问一次（`memory_dedupe_llm_review_enabled`，默认开）：同一件 → NOOP，不同 → ADD，调用失败仍按 ADD |
| PR-25 评测题集生成 | ✅ 本次 | `app/memory/eval_gen.py`：从真实记忆反推自然提问 → JSONL；生成后仍需人工过一遍同义撞车 |
| PR-24 工具与注入共用召回 | ✅ 本次 | `memory_search` 在 `memory_recall_v3_enabled` 时先走 v3，v3 空/异常才落回 Milvus，避免「注入说记得、工具说找不到」 |
| PR-23 段落装配器 | ✅ 本次 | `app/context/assembler.py`：先按槽截断、再按 `DROP_ORDER` 整段丢；v3 召回块已硬顶到 recall 槽 400 token |
| PR-22 影子召回 | ✅ 本次 | `app/memory/shadow.py`：v3 未开时也跑一遍，只记 `overlap / missing`，不改注入内容；开关 `memory_shadow_enabled` 默认关 |
| PR-32 记忆页层标签 | ✅ `2437fcb` | 列表卡片与抽屉显示「核心设定/事实/经历/印象」，四语齐；未知 layer 不渲染 |
| PR-33 向量集合 v3 双写 | ✅ 本次 | 新集合多带 layer/status/event_time；开启后 relay 双写、召回只捞 `status == "active"`。v2 仍是主键来源，v3 可随时重建；建集合用 `python -m scripts.init_milvus_v3` |
| PR-34 v3 历史回填 | ✅ 本次 | `scripts/backfill_milvus_v3.py`：按 `memory_id in [...]` 查 v3 跳过已有，再批量 embed 写入；`--dry-run` 只看数量。双写只覆盖开关之后的记忆，历史不回填会出现「她突然忘了很多」 |
| PR-35 当前问句不再记成「没接住」 | ✅ 本次 | 组装时 `track_questions=False`：那句问话正是她马上要答的，记进 openLoops 会渲染出「还没接的话：你刚问的这句」。真正没接住的话由场景结算时的 impression/episode 产出 |
| PR-36 沉底记忆被召回时复活 | ✅ 本次 | `mark_injected` 对 `fade_level=archived` 的行抬到 `strength≥0.55 / active`、`revival_count+1`；只抬强度与档位，不动摘要，写语句在 `app/write.py` 白名单里 |
| PR-37 主动浮现（周频控 + 贴题门槛） | ✅ | `POST /agent/memory/resurface`：本科目每周最多一次；只挑强度 0.35–0.55 或周年（同月同日且 ≥300 天）；给了话题就要求余弦 ≥0.2，否则这周不提。Redis 挂了宁可不说 |
| PR-38 记忆块总预算 | ✅ | `memory_context_max_tokens`（默认 1100）在拼 system 前兜底截断；画像 80 行 + 召回 5 条 + 场景卡的极端情况不再挤掉最近对话 |
| PR-39 星图显示钉住 / 静音 | ✅ | 钉住加暖金细环；静音压到 35% 亮度、半径 ×0.78——留在图上，但不抢注意力 |
| PR-40 主动浮现按场景话题过滤 | ✅ | PROACTIVE 组装时读场景卡 topic 传给 `suggest`，余弦 <0.2 就不提；拿不到卡则不设门槛 |
| PR-41 评测对比 | ✅ | `evaluation.compare/render_comparison` + CLI `--json/--compare`：同一题集两次跑，给出 recall/MRR 差与逐题「救回/弄丢」 |
| PR-42 记忆可解释（被想起次数） | ✅ | 列表/详情返回 `recallCount` + `lastRecalledAt`，抽屉标「被想起过 3 次 · 最近 11-06」；召回打点本来就在写这两个字段 |
| PR-43 同一句原话不重复记 | ✅ | `insert_episode` 先查 7 天内同 quote（hash 带 event_time，重试差一秒就会各写一行）；查重失败仍写，宁可多一条也不丢这一幕 |
| PR-31 召回超时预算 | ✅ 本次 | 召回超过 `memory_recall_timeout_seconds`（默认 80ms）就退回旧路径；`mark_injected` 超过 300ms 直接放弃，下一轮再补——记忆可以晚一轮，回复不能卡住 |

**仍未做**：周记换成生成式（现在是抽取拼接；cron 已就绪但默认关）、
生产库的 V58/V59 迁移与 Milvus v3 回填（本地 8.4/2.4.15 已演练通过，见 §10.4）、
`memory_recall_v3_enabled` 打开前的 200 题评测与 3 天影子数据。

**开关**：`scene_card_enabled`（默认开）、`memory_recall_v3_enabled`（默认关，等迁移 + 评测）、
`memory_vector_recall_enabled`（默认关，等 Milvus 里有向量且集合升级）、
`memory_consolidate_enabled`（默认开，关掉后端点直接返回 `skipped`）、
`history_trim_enabled`（默认关；打开后 recent 槽超预算会丢最旧的消息，system 与当前轮不动）、
`memory_dedupe_similarity_enabled`（默认关；打开后写入前多一次 embedding，cos≥0.92 直接不写）、
`memory_shadow_enabled`（默认关；打开后每轮多跑一次 v3 召回，只为打覆盖率日志）、
`memory_dedupe_llm_review_enabled`（默认开；灰区多问一次模型，详见 §7.3）、
`memory_resurface_enabled`（默认开；主动开口时允许提一句快要忘记的事，每角色每周最多一次）。
周记另有 Java 侧调度开关 `lianyu.memory.consolidate.enabled`（**默认关**，线上显式开启后每周一 04:10 调一次；
cron 可用 `lianyu.memory.consolidate.cron` 覆盖）。
回滚：关开关即可回到「画像槽位 + importance top20 + LIKE 5」，新表/新列不影响旧路径。

### 10.3 切换手册（按顺序，每步都看体检）

`GET /agent/memory/health` 是每一步的判据；不要"全开再看"。

| 步 | 动作 | 通过条件 | 失败怎么办 |
|---|---|---|---|
| 1 | 跑 Flyway：`V58__memory_layers_and_episodes.sql`、`V59__memory_episode_injected.sql` | `health.timestamps.last_decay_at` 非 null；`counters.memories_active` 与迁移前一致 | 回滚迁移前先停写入（`MemoryWriter` 的 extract-write 开关） |
| 2 | 校验全文索引：`SELECT id FROM memory_meta WHERE MATCH(summary) AGAINST ('加班') LIMIT 1` | 有返回；若报 `unknown parser ngram`，先确认 MySQL `ngram_token_size` | 索引没建成时 v3 的 ngram 一路为空，但不会报错——必须显式验一次 |
| 3 | `memory_shadow_enabled=true` | 日志 `memory shadow overlap=…` 连续 3 天 ≥0.60 | <0.4 说明 v3 召回与旧路径分歧大，先看 `missing` 里是哪些 id |
| 4 | `memory_recall_v3_enabled=true`（关 shadow） | 体检 `warnings` 为空；`episodes_active>0` 或确实还没有场景 | 注入变空 → 立刻关回；`_load_v3_block` 失败会退回旧路径，但"空"不会 |
| 5 | 建 v3 集合：`python -m scripts.init_milvus_v3` → `memory_vectors_v3_enabled=true` → 回填 `python -m scripts.backfill_milvus_v3` | 回填输出 `failed=0`；`skipped` 是重复跑的行 | 集合没建时双写只 warning（v2 照常），但这时开向量路只会命中新记忆，看起来像「她忘了很多」 |
| 6 | `memory_vector_recall_enabled=true` | `memory_search` 日志出现 `via v3`，且 `outbox_dead=0` | 向量库为空时它只是少一路，不会更差；DEAD 多说明 relay 卡住，先修 relay |
| 7 | `memory_dedupe_similarity_enabled=true`、`memory_dedupe_llm_review_enabled=true`，观察 `skipped` 占比与误合并 | `skipped` 占比 <15%；人工抽查无"两件事并成一件" | 误合并不可逆（只写了新行，旧行没合并），关开关即可止损 |
| 8 | Java `lianyu.memory.consolidate.enabled=true` | 下周一 04:10 后 `last_reflection_at` 前进，且没有重复周记 | 重复说明 `content_hash` 没带周标签，检查 `digest_hash` |
| 9 | `history_trim_enabled=true` | `GET /agent/context/stats` 的 `trimmed_turns` 与 `est_tokens_per_turn` 同时下降 | 若"当前轮被截"报告，立即关；`trim_history` 保底 6 条且保 system |

两条硬约束：**第 3 步之前不要开第 4 步**（没有影子数据就没有分歧证据）；**第 1、5 步之前不要开第 6 步**
（向量路要读 v2 集合并回 `memory_meta` 取权威行，迁移没跑时回表会全部失败）。

### 10.4 真机演练记录（2026-11-06，MySQL 8.4 + Milvus 2.4.15）

本地 docker 起了 mysql:8.4 与 milvusdb/milvus:v2.4.15（standalone，embedded etcd），
在克隆库 `lianyu_rehearsal` 上把迁移与召回各跑了一遍。

| 项 | 结果 |
|---|---|
| V56/V57/V58/V59 依次执行 | 4/4 exit 0；`memory_meta` 34 列、`memory_episode` 18 列 |
| 全文索引 | `FULLTEXT KEY ft_mem_summary (summary) WITH PARSER ngram`，`ngram_token_size=2`；两行数据时 `MATCH('加班')` 得 0.271857 |
| 真库 `recall(3,7,"加班")` | 修了三处：候选必须 `rel > 0`；lane 归一再乘「这一路最强分」折扣；episode 单行 IDF=0 时用 `LIKE` 兜底 0.05 |
| Milvus v3 建集合 | `python -m scripts.init_milvus_v3` → `memory_vectors_v3`（1024 维、HNSW COSINE） |
| v3 写入/检索 | 插入 active + superseded 两行；`search_hits_v3` 只返回 active，换角色过滤返回空（租户隔离成立） |
| backfill dry-run | `{'scanned': 2, 'would_write': 2, 'written': 0, ...}`（dry-run 不连 Milvus、不调 embedding） |
| 真实回填（text-embedding-v3） | 2 条记忆写入 v3，`failed=0`；查询「加班」cos=1.0、「工作到深夜很疲惫」0.847 |
| 向量路开关对比 | 关：改写句（「工作到深夜，身心俱疲」）召回 **0 条**；开：正确召回「今天加班到很晚」；无关句（「今天吃了火锅」）两档都为 0 条 |

embedding 接通后又补了两道精度闸：

- **相似度下限** `memory_vector_min_similarity=0.55`：text-embedding-v3 对无关句也给 0.40–0.52
  （「今天吃了火锅」对两条记忆是 0.46/0.52），不设下限会把不相干的记忆召回来。
- **实词二元组闸**：MySQL 的 ngram 没有停用词，短句里「今天」会把「今天加班到很晚」拉进来；
  Python 侧在候选出库后要求命中至少一个非停用词二元组，避免「今天吃了火锅」召回加班记忆。

两个只在真机上才暴露的坑：

1. **刚写入的向量搜不到**。`insert` 后立刻 `search` 返回空，`query` 却能看到；`flush` 之后立刻可见。
   已给 `relay` 的写批末尾加 `milvus_store.flush_v3()`（并给 v3 客户端加 `consistency_level="Strong"`）。
   不刷的话，表现为「她刚记住的事，下一轮问她说不记得」。
2. **aiomysql 连 MySQL 8 要 cryptography**。报
   `'cryptography' package is required for sha256_password or caching_sha2_password auth methods`，
   `pyproject.toml` 已加 `cryptography>=42`——生产 venv 构建同样需要，否则整套召回在启动时就连不上库。

---

## 11. 评测与指标

**离线 200 题**（中文，自建）：单跳事实 / 多跳 / 时间 / 更新 / 指代 / 约定 / 拒答，各 ~28 题。
指标：recall@5、answer accuracy（LLM judge + 人工抽检 20%）、temporal accuracy、update accuracy、
abstention accuracy。

**跑法**：`python -m app.memory.evaluation backend-agent/eval/memory_cases.jsonl --k 5 --json before.json`，开关前后各跑一次，
再用 `--compare before.json` 看「救回几条、弄丢几条」——整体 recall 持平也可能是换掉两条，逐题对比才知道该不该开。
`backend-agent/app/memory/evaluation.py` 读 JSONL 题集，直接打 `recall()` 算 recall@k / MRR，
并列出未命中题与它实际召回的 top-k；模板见 `backend-agent/eval/memory_cases.sample.jsonl`。

```bash
cd backend-agent && ./.venv/Scripts/python.exe -m app.memory.evaluation eval/memory_cases.sample.jsonl 5
```

它测的是**当前开关下**的链路：`memory_recall_v3_enabled=false` 时测旧路径，开 v3 后同一套题可以直接对比。

**三档必跑消融**（对应 §3.2 / §6.6 的证据）：

1. **结构 vs 原文**：只给结构化摘要 / 只给原文片段 / 两者并存——三者都要跑，防止"结构悄悄替换原文"。
2. **长度**：focused（≈300 token 命中片段）vs full（全量最近 N 条）——验证"少而准 > 多而全"。
3. **前缀稳定性**：命中率低于 50% 时打印两轮 system 的 diff，定位是哪个字段在抖。

**摘要漂移率**：从原文抽原子事实，与摘要里的原子事实对齐，
`漂移率 = (新增 + 丢失 + 篡改) / 原文事实数`，目标 <5%/千轮。

**约束保持率**：人设/安全/称呼三类约束各 20 个探针，检模型是否违反（借鉴 VeriFY 的探针构造方式）。

**线上**：`context_tokens_p50/p95`、`cache_hit_rate`、`slot_drop_total`、`recall_used_rate`（判官：
回复是否引用了召回内容）、`contradiction_rate`、`callback_success`（用户回"你还记得"）、
`forgot_complaints`、`p95_latency`、`cost_per_turn`。
其中 `turns / est_tokens_per_turn / cache_hit_rate / trimmed_turns` 已由
`app/context/stats.py` 按天累计，`GET /agent/context/stats` 可直接读；其余待埋点。

**遗忘相关**（§7.6.11）：`forget_false_negative`（问"还记得"时 `s_eff<0.3`）、`over_memory_rate`
（同一事实 7 天被主动提 ≥2 次）、`revival_success`、`fabrication_rate`（目标 0）、
四档分布（健康区间 55/25/15/5）。

---

## 12. 不做清单

- 不引 Neo4j / 图数据库（双时间轴用 MySQL 列足够）；
- 不做 HippoRAG 式 PPR、不做 A-Mem 自动建链（收益未验证，复杂度高）；
- **不用摘要替换原文**（§6.6：受控消融里掉 15.9 / 22.0 点）；摘要只做导航；
- 不做在线 LLMLingua 剪枝（要自建 encoder，中文情感文本删改风险高）；
- 不做 MemGPT 式 function-call 换页（每次多一轮推理，2s 内做不到）；
- 不让 LLM 决定 DELETE（Mem0 自己已改 ADD-only）；
- **不做 TTL 到期删除**（§7.6.12：用户感知是"角色换人"）；也不在查询时实时算衰减
  （同一轮不同召回会看到不同分数，且 Java/Python 会各算一遍）；
- 不做"按价值排序批量删旧记忆"（这是数据清理，不是遗忘；清理只在 `dormant` 24 个月后做）；
- 不做概率激活 / 无限递归 / 用户可拖拽 prompt 顺序；
- 不做跨角色默认共享记忆（串味是事故级）；
- 不把 long context 当解药（成本与注意力衰减都不划算）。

---

## 13. 风险与回滚

| 风险 | 处理 |
|---|---|
| 场景卡漂移/胡说 | 每版存 hist，规则校验（topic 非空、beats ≤4、openLoops 必须有 quote）；失败保留旧卡 |
| LLM 刷新拖慢首 token | 刷新在回复后异步做，下一轮生效；超时 1.5s 放弃 |
| 缓存前缀被 core 更新打断 | core 变更走"版本号 + 重建"，一天最多几次 |
| 末尾 system 消息被某些 provider 拒绝 | 降级为 system 尾部追加；开关控制 |
| 迁移期双写不一致 | 影子模式 diff 7 天，再切读 |
| **衰减把重要记忆误降** | `pinned` 永不降；单日降级上限 30；`forget_false_negative` 超 10% 就整体调慢 τ；可一键回填 `strength=1` |
| **批处理永远扫不到某些行** | 按 `last_decay_at ASC` 轮转（不是 `created_at`），并在测试里断言"两批覆盖不同 id" |
| **复活时编造细节** | `faded/archived` 只允许不确定语气；`fabrication_rate` 目标 0；同一条被纠正两次即停止主动提 |
| **衰减后角色"变冷淡"** | 先只影响召回措辞、不影响关系/情绪数值；上线前用 20 个长会话回放对比 |
| 回滚 | 四个开关（`context.scene_card` / `memory.v3` / `memory.decay` / `context.budget`）+ 旧 `session:summary` 保留 30 天 |

---

## 14. 决策记录

**已定（2026-11-06）**

1. **场景卡刷新用不用小模型** → **用**，但只在检测到话题漂移时触发（每 6 轮兜底一次，≈¥0.0002/次）。
2. **`recent` 槽预算** → **默认 1.4k token，按最近 20 条平均长度自适应 1.0–1.8k**：
   `avg > 120 字` 压到 1.2k（长段落里一轮就占满），`avg < 40 字` 放到 1.6k（多给几轮），硬上限 1.8k。
   固定窗口的问题是"短句浪费、长句挤爆"，自适应比调一个魔法数更稳。
3. **旧 `session:summary` 是否退役** → **双跑 30 天**，摘要漂移率 <5% 且"跨轮指代/约定"类 ≥0.75 后退役；
   退役只关写入，读路径保留 30 天可回滚。

**待定**

4. **记忆的"改/删/pin"给不给用户**：产品上必须有（Replika/Kindroid 都有），但涉及 UI 与权限，排在 P3。
5. **群聊/多角色是否共用场景卡**：建议一角色一卡（conversation 维度已有 characterId），群聊按"轮到的角色"切卡。
6. **AGENTS.md 的写库例外**：`backend-agent/app/write.py` 已经是记忆域唯一写入口，建议在 AGENTS.md 里
   明确登记（而不是把写入搬回 Java，那会触发 Java 零 AI 棘轮）。
7. **遗忘策略的两个口味问题** → 已定：负性记忆在 `INJURED`/`REPAIRING` 阶段 `τ×0.7`、**不删**，
   回到 `STABLE_INTIMATE` 再恢复；`dormant` 硬删保留期取 **24 个月**（配置项，可调）。见 §7.6.8 / §7.6.4。
8. **`s_eff` 用单指数还是双时间尺度** → 用**两条指数叠加**（0.7·τ/6 + 0.3·2.5τ），
   因为单指数拟合不了"先陡后长尾"（§7.6.3 有实测数字）；参数先按表里的值跑，P2 用 200 题标定。
9. **"别再提"要不要做** → **要**，`muted=1` 只减引用、不删源（ChatGPT 同款），与 pin、删除一起进 P3 的记忆页。

---

## 15. 参考

- SillyTavern：worldinfo / prompt-manager / context-template / authors-note / summarize 官方文档
- AI Dungeon：plot-components / plot-essentials / authors-note / memory-system / story-cards 官方帮助
- NovelAI：lorebook / advancedsettings 官方文档
- Character Card V2 / V3 规范
- 学术与机制：Context Rot（Chroma 2025-07）、Lost in the Middle（2307.03172）、RULER（2404.06654）、
  NoLiMa（2502.05167）、长上下文 vs RAG（2407.16833）、Fidelity Before Structure（2601.00821, preprint）、
  Mem0（2504.19413）、MemGPT（2310.08560）、MemoryOS（2506.06326）、Generative Agents（2304.03442）、
  sleep-time compute（2504.13171）
- 生产实践：Claude Code（context-window / memory / prompt-caching / costs）、OpenAI（prompt-caching /
  conversation-state / compaction / agents sessions）、Anthropic context editing、LangGraph persistence、
  Cursor rules、Aider repo map、Manus context engineering
- 遗忘机制：Murre & Dros 2015（PLOS ONE 10(7):e0120644，遗忘曲线重拟合）、Cepeda et al. 2006
  （Psych Bull 132(3):354，间隔效应）、Roediger & Karpicke 2006（Psych Sci 17(3):249，提取练习）、
  Anderson/Bjork 1994（JEP:LMC 20(5):1063，提取诱发遗忘）、Nader et al. 2000（Nature 406:722，再巩固）、
  Brainerd & Reyna 2015（Dev Rev 38:1，gist vs verbatim）、Talarico & Rubin 2003（Psych Sci 14(5):455，
  闪光灯记忆）、Richards & Frankland 2017（Neuron 94(6):1071，遗忘的适应性价值）、Rasch & Born 2013
  （Physiol Rev 93(2):681，睡眠巩固）、Cuc/Hirst 2007（Psych Sci 18(8):727，社会性记忆抑制）、
  MemoryBank（2305.10250，`R=e^(−Δt/S)`）、Zep/Graphiti（2501.13956，四时间戳与边失效）、
  FadeMem（2601.18642，双层衰减；仅组件级消融）
- 产品记忆机制：Kindroid（memory / learned context / journal 官方文档）、ChatGPT Memory FAQ、
  Character.AI Memory 博客（Story Memory / pin / Facts）、Replika Help（memory 分层）
- 本地调研原文（`local/` 已 gitignore）：`local/research/context-management-2023-2026.md`（学术）、
  `local/research/context-engineering-survey-2026-11.md`（生产）
- 长期记忆部分见 [`memory-architecture-zh.md`](./memory-architecture-zh.md) 的参考列表（MemGPT / Mem0 /
  Zep / A-Mem / HippoRAG / MemoryBank / Generative Agents / MemoryOS / LoCoMo / LongMemEval）

### 10.5 召回质量实测（2026-11-06，演练库 26 题）

| 版本 | recall@5 | MRR | 负例 | 隔离 |
|---|---|---|---|---|
| 旧路径（画像槽位 + importance top20 + LIKE） | 7/24 | 0.112 | 0/2 | PASS |
| v3 + 向量，但 core 混在 recall 里 | 11/24 | 0.147 | 0/2 | PASS |
| core 独立槽 + 整行预算 + episode 原话 | 18/24 | 0.681 | 2/2 | PASS |

三处改动对应三个真实故障：

1. **core 记忆不能参与 recall 排名**。core/pinned 每轮都该出现，但它们一旦混进候选，就把 5 个位置占满（实测 501/517/533/527/504），真正相关的记忆一条进不来。现在 `core_rows()` 单独取、单独渲染，占 `core` 槽 260 token。
2. **跨路要按名次融合，不能各自归一**。`MATCH` 分数与 cosine 不是一个量纲，各自除以本路最大值会让每路第一名都变成 1.0，事实永远挤满前五。改成 RRF（k=60）后，原话与事实按"被几路同时认可"排序。
3. **原话也要进向量**。episode 只有 ngram 时，「猫生病那晚」对不上「团子吐了一夜」，8 道原话题全灭。现在场景结算时把原话投影进 v3（`memory_id = EPISODE_ID_OFFSET + episode_id`），回填脚本一并补历史。

默认开关（2026-11-06 起）：`memory_recall_v3_enabled=true`、`memory_vector_recall_enabled=true`、`memory_dedupe_similarity_enabled=true`。旧路径只在 v3 抛错/超时（80ms 预算）时兜底，不再作为主路径。没有配 `embedding_api_key` 时向量路直接返回空，不会去等 HTTP 超时。召回超时预算从 80ms 提到 500ms（实测：冷启动 1088ms、热路径 176–260ms、整块 208–251ms），并在启动时预热 embedding 与 Milvus 连接；超过 500ms 的慢请求仍落回旧路径，宁可少召回也不拖住回复。

**上线后（2026-11-06 夜，生产 15,528 条记忆）**：服务器到 DashScope 的 embedding 实测
0.6–1.5s/次，整条召回被它拖过 500ms，日志每轮都是 `timed out ... falling back`——表面上
开着 v3，实际仍在走旧路径。改成两件事后恢复：

1. **向量路单独限时**（`memory_vector_timeout_seconds`）：超时只丢向量，ngram /
   episode / core 照常出块，不再整体回退旧路径；整条召回预算 0.65s → 0.45s。
   生产实测外网 embedding 0.6–1.5s，所以这个预算目前基本只决定"白等多久"，已压到
   0.18s；等本地 embedding 落地（~20ms）它才会变成收益。
2. **三条路并发**（候选 SQL / episode SQL / 向量）：串行的 SQL 两跳 + 向量预算会叠成
   544ms，并发后热路径约 300ms。

同期回填也改成批量投影（`insert_v3_batch`，一次 RPC 写 50 条）：逐条 delete+insert 在
生产跑了一小时只到 8.7k，批量后 8.7k → 15,528 条不到十分钟。代价是外网 embedding 慢时
向量路在热路径基本拿不到分——这是"本地 embedding 未落地"的已知限制；要真正用上语义召回，
下一步应把 embedding 换成本地小模型（如 bge-small-zh），或改成轮次外异步预取。

### 10.6 验证后的收尾（2026-11-06）

大验证组（见 `docs/memory-verification-2026-11-06.md`）暴露的问题已按三轮修完：
时区（Python 连接池对齐 +08）、截断顺序（先按预算再拼场景）、判重阈值（0.97 + 灰区 review）、
写库白名单（整句匹配）、归档过滤、复活（接口 + 按钮）、衰减封顶（30 天/次）、
v3 投影失败重试、冷启动建连不阻塞、dormant 清向量、superseded 徽标、体检并发化。
仍未做的只有历史时间戳清洗与 `reconcile` 的 v3 存在性检查。

### 10.7 旧时间戳与向量库对账（2026-11-06）

- 旧行（Python 用 UTC 写入）用 `python -m scripts.audit_clock_skew` 审计、`--apply` 修正；
  判据是「记忆晚于来源消息」，可重复执行。
- 向量库被重建后，`vec_id` 这一列不可信：`POST /agent/memory/reconcile?deep=true`
  会问 Milvus 本身并补投影。
