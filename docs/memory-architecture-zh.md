# 予念记忆系统 v2 · 恋人型长期记忆架构

> **状态：已暂停（2026-11-06）**。长期记忆不再单独推进，已合并进
> [`context-memory-architecture-zh.md`](./context-memory-architecture-zh.md)（上下文与记忆统一设计）。
> 本文保留作为记忆侧的详细论证与数据设计参考。
>
> 原状态：设计稿（待评审）｜ 影响面：`backend-agent/app/memory/`、`backend/lianyu-service/.../memory/`、`memory_meta` 表、`MemoryPage` 星图
> 结论先行：**不要再堆 RAG 组件，先补「结构 / 归属 / 出口」三件事**，再用混合检索替换现在这条「重要性 top20 + LIKE」的取数。

---

## 0. 一页结论

现在的链路不是「基础 RAG」，而是**画像槽位 + 重要性前 20 条 + 关键词 LIKE 5 条**：

- 注入 prompt 的记忆块完全不走向量检索（`backend-agent/app/data_readers.py:201 load_profile_block`）；
- 向量库只在模型主动调用 `memory_search` 工具时才被用到（`backend-agent/app/tools/memory_search_tool.py:89`）；
- 去重只有 `source_hash` 全等，改口、同义、近义全靠叠加；
- 没有有效期、没有失效、没有衰减、没有 pin/编辑，删除是物理删（`MemoryQueryService.java:113`）；
- `importance` 是 LLM 自打分，长期集中在 0.7~0.9，导致 top20 实际近似随机。

v2 的六条主决策：

| # | 决策 | 一句话理由 |
|---|---|---|
| 1 | **四层记忆**：core（设定） / episode（事件） / fact（事实） / reflection（反思） | 恋人感来自"她记得那件事"，不是"她有一个向量库" |
| 2 | **写入按 episode 批处理**，不再每轮抽 | 每轮抽 = 成本高 + 上下文碎 + 摘要漂移 |
| 3 | **事实带双时间轴**（event_time / valid_from-valid_to），失效不删 | 情侣会改口；"你以前不是说不吃辣吗"需要旧版本 |
| 4 | **检索 = 向量 + MySQL ngram 全文 + 结构化补位，RRF 融合** | 中文短句 dense 不可靠；MySQL 已有，不引 ES、不升 Milvus |
| 5 | **注入按"记忆口吻"分四段渲染**，带日期、带不确定就说不记得的规则 | 事实清单会让角色变成客服 |
| 6 | **用户出口**：记忆页可改 / 可 pin / 可纠正 / 可回放 | 抽取一定会错；没有出口的错误不可逆 |

不引入：Neo4j、HippoRAG 式 PPR、A-Mem 式自动建链演化、MemGPT 式模型自主换页（延迟与成本都不划算，见 §11）。

---

## 1. 现状：哪里"基础"，拿代码说话

| 环节 | 现在怎么做 | 证据 | 问题 |
|---|---|---|---|
| 提取 | 每轮结束后 Java 发 `MemorySummaryTask`，Python `extract.py` 规则 + LLM 抽 3 条，`importance < 0.5` 丢 | `backend-agent/app/memory/extract.py:1-30` | 单轮 30 条消息窗口，无 episode 概念；抽取 prompt 不区分"谁说的" |
| 写入 | `source_hash` 全等去重；命中则按摘要相同/不同分别合并或覆盖 | `backend-agent/app/memory/writer.py:82-140` | 没有语义去重；改口会写成两条；覆盖会丢旧值 |
| 存储 | `memory_meta` 一张表：summary / type / importance / source_msg_ids / vec_id / created_at | `V1__init_schema.sql:58`、`V31`、`V33` | 无 layer / 无有效期 / 无 status / 无 hash / 无 pin |
| 检索（注入） | 画像槽位（每 slot 取最新，LIMIT 80）+ `importance >= 0.5` 按重要性取 20 + LIKE 命中 5 | `backend-agent/app/data_readers.py:127-176` | 与当前这句话无关；顺序由 importance 决定，而 importance 未校准 |
| 检索（工具） | `memory_search` 才走 Milvus dense + rerank，失败降级 LIKE | `memory_search_tool.py:78-130` | 只有模型想起来调工具才有语义检索 |
| ⚠️ 语义检索实际恒空 | `milvus_store.search(character_id, user_id, ...)` 的调用写成了 `search(user_id, character_id, ...)`，过滤条件变成 `character_id == 用户id && user_id == 角色id` | `backend-agent/app/tools/memory_search_tool.py:100` vs `backend-agent/app/milvus_store.py:109` | 这条路径一直静默降级到 LIKE；基线评测前必须先修（一行） |
| 输出 | 按 FACT/EMOTION/RELATION/RITUAL 四组渲染成清单 | `data_readers.py:191-215` | 是"资料卡"不是"回忆"；无日期；无出处；易被当成用户刚说的话 |
| 遗忘 | 无 | — | 记忆只增不减，星图会越来越挤 |
| 用户出口 | 列表 / 详情 / 删除（物理删） | `MemoryPage.vue`、`MemoryQueryService.java:113` | 不能改、不能 pin、不能纠正，删了不可回滚 |

一个关键量：`memory_search_candidate_count = 20`（`backend-agent/app/config.py:72`），`memory_like_boost_limit = 5`（同文件 :73）。也就是说，角色每轮能"看到"的旧记忆最多 25 条，且其中 20 条与当前话题无关。

---

## 2. 恋人型 Agent 到底需要什么

从"用户能感知到什么"倒推（同类产品调研结论，见 §12）：

| 用户感知 | 需要的机制 | 我们缺的 |
|---|---|---|
| "她记得我说过的那件事，而且是她先提起的" | 事件级抽取 + 绝对时间 + 未完成承诺（open loop）+ 主动触发 | 无 episode、无 event_time、无 open loop |
| "我纠正一次，她就永远改对" | 用户编辑优先级最高 + 覆盖式版本化 + 单条 is_current | 无编辑、无版本 |
| "她不会把 A 的事算到 B 头上" | `(user_id, character_id)` 双键过滤 + 共享层白名单 | 已有双键，但没有共享层概念 |
| "她有自己的记忆" | 角色第一人称日记 / 关系里程碑 / 情绪记忆 | 有 `character_diary`、`relationship_milestone`，但没接进记忆块 |
| "忘得像人，但我说了算" | 强度衰减 + pin 免衰减 + 召回可解释 | 无衰减、无 pin、无解释 |

反面教材（调研里被反复提到的三个坑）：

1. **把生成当记忆**：没有结构化槽位，模型就用"编"来填空（Paradot 报错生日）。
2. **截断悬崖**：按固定窗口从旧到新截断，用户体感是"突然全忘"（Character.AI 的 Prompt Poet 固定截断点，为了缓存命中）。
3. **用户无法自救**：没有可见可编辑的记忆层，任何抽取错误都不可逆（Chai）。

---

## 3. 目标架构：四层 + 五段

```
                         ┌──────────────────────────────┐
   对话流 ──episode切分──▶│ 提取 Extract（Python / LLM）  │
                         └──────────────┬───────────────┘
                                        │ 候选 + 归属 + 时间 + 重要度
                         ┌──────────────▼───────────────┐
                         │ 写入 Write（Python 白名单）   │
                         │ hash → 双阈值 → 版本化 → outbox│
                         └──────┬───────────────┬───────┘
                                │               │
                    ┌───────────▼──────┐  ┌─────▼─────────────┐
                    │ MySQL 真相源      │  │ Milvus v3 可重建索引│
                    │ memory_meta(+列)  │  │ dense 1024 + 标量   │
                    │ memory_episode    │  └───────────────────┘
                    │ memory_revision   │
                    └───────────┬──────┘
                                │
      ┌─────────────────────────▼──────────────────────────┐
      │ 检索 Retrieve（Python）                             │
      │ core 常驻 ─┐                                        │
      │ 结构化补位 ─┼─▶ 预算打包 ─▶ 输出 Inject（prompt）     │
      │ 混合召回 ──┘   （dense + ngram-全文 + RRF + MMR）    │
      └────────────────────────────────────────────────────┘
```

四层记忆（`memory_meta.layer`）：

| layer | 内容 | 谁写 | 衰减 | 注入 |
|---|---|---|---|---|
| `core` | 称呼、雷点、仪式、关系设定、用户手动 pin | 规则 / 用户 | 不衰减 | 常驻 |
| `episode` | "11-03 深夜她加班到两点说不想当大人了" | episode 抽取 | 慢（τ=30d） | 按相关度 |
| `fact` | 姓名、职业、忌口、偏好、承诺 | 规则 + LLM | 按类型（τ=90d） | 按相关度 |
| `reflection` | "她每次加班后都会自责" / 角色日记 | 夜间整合 | 极慢 | 按相关度，少量 |

---

## 4. 五段设计

### 4.1 提取 Extract（Python）

**触发**：不再每轮抽。由 Java 在会话空闲 / 关闭 / 满 40 条时发 `EpisodeClosed` 事件（复用现有 `MemorySummaryTask` 通道即可，改 payload），Python 消费。

**切分规则**（`episode.py` 新增）：

- 同一 `conversation_id` 内，相邻消息间隔 > 30 分钟 → 切；
- 累计 > 40 条消息或 > 6k 字符 → 切；
- 话题漂移（相邻 6 条消息 embedding 余弦 < 0.45）→ 切（可选，第一版先不做）。

**一次 episode 产出**（结构化 JSON，temperature 0.1）：

```json
{
  "summary": "她加班到两点，说今天不想当大人了；我陪她聊到睡着。",
  "quote": "今天不想当大人了",
  "mood": "低落",
  "salience": 0.78,
  "memories": [
    {
      "layer": "episode",
      "type": "EMOTION",
      "subject": "user",
      "predicate": "加班到深夜",
      "object": "凌晨两点",
      "summary": "11-03 她加班到凌晨两点，情绪很低落。",
      "quote": "今天不想当大人了",
      "eventTime": "2026-11-03T02:10:00+08:00",
      "assertedBy": "user",
      "importance": 0.72,
      "arousal": 0.8,
      "valence": -0.6,
      "openLoop": null
    },
    {
      "layer": "fact",
      "type": "FACT",
      "subject": "user",
      "predicate": "不喜欢",
      "object": "香菜",
      "summary": "她不吃香菜。",
      "eventTime": null,
      "assertedBy": "user",
      "importance": 0.66,
      "openLoop": null
    }
  ]
}
```

三条硬规则写进 prompt（`backend-agent/app/prompts/memory_extract.md`，**只能放 Python**）：

1. **归属**：`subject` 必须是 `user` / `character` / `third_party`；角色自己扮演时说的话不算用户事实（这是"摘要漂移"的真正根因）。
2. **时间**：相对时间必须换算成绝对时间 + `+08:00`；换算不了就写 `null`，不许猜。
3. **重要度锚点**：0.9 = 姓名/生日/创伤/重大承诺；0.75 = 强烈情绪/关系事件；0.6 = 稳定偏好；0.45 = 一般事件；≤0.4 不进库。给 3 个例子，避免全部打 0.7。

**规则通道保留**：`extract_heuristics`（姓名/爱好/忌口/偏好/禁忌/仪式）继续跑，命中即 `created_by='rule'`、`importance` 下限 0.85、`layer='core'`。规则永远优先于 LLM。

**成本闸门**：先过 `salience_gate`（规则命中 / 含情绪词 / 含时间词 / 与现有 core 槽位相似度 > 0.85）——不通过就只存 episode 摘要，不调抽取 LLM。目标：把 LLM 调用压到 episode 的 40% 以下。

### 4.2 写入 Write（Python，白名单表）

沿用 `backend-agent/app/write.py` 的"极窄白名单"模式，新增允许表：`memory_episode`、`memory_revision`。

```
候选 ──▶ ① content_hash 全等（存在 → 合并来源，结束）
      ──▶ ② 同 (character,user,subject,predicate) 下取 top5 邻居，算余弦
              ≥0.92 → NOOP（合并来源 + 提升 strength）
              0.75~0.92 → 灰区，调一次 LLM 判 ADD/UPDATE/SUPERSEDE（≤500 tok）
              <0.75 → ADD
      ──▶ ③ 事务：写 memory_meta + memory_revision + memory_outbox(UPSERT)
```

- **版本化**：UPDATE 不覆盖旧值，而是旧行 `status='superseded'`, `valid_to=新行.valid_from`, `superseded_by=新 id`；新行 `revision=旧+1`。检索恒带 `status='active'`。
- **用户编辑优先**：`created_by='user'` 的行不可被 AI 覆盖，只能由用户再改。
- **幂等**：outbox 用 `memory_id` 做 Milvus 主键 + `upsert`；重放不会产生双份。
- **对账**：`embedding_state`（0 待写 / 1 已写 / 2 失败）夜间比对 MySQL vs Milvus 的 id 集合，**不要用 count**。

为什么保留 Python 写库：写入决策（ADD/UPDATE）是模型判断，搬到 Java 就等于把 AI 逻辑搬回 Java，违反仓库棘轮；现有白名单已经把爆炸半径钉死。**建议同步修订 AGENTS.md §2 的"Python 不写 MySQL"为"仅经 `app/write.py` 白名单写记忆域四表"**，否则文档与代码会继续漂移。

### 4.3 存储 Store

**MySQL 是唯一真相源**；Milvus 只是可重建索引；Redis 只做热缓存。

新增/升级表（`V57__memory_v2.sql`，ASCII-only）：

```sql
ALTER TABLE memory_meta
  ADD COLUMN layer VARCHAR(16) NOT NULL DEFAULT 'fact' AFTER memory_type,
  ADD COLUMN subject VARCHAR(64) DEFAULT NULL,
  ADD COLUMN predicate VARCHAR(64) DEFAULT NULL,
  ADD COLUMN event_time DATETIME(3) DEFAULT NULL,
  ADD COLUMN valid_from DATETIME(3) DEFAULT NULL,
  ADD COLUMN valid_to DATETIME(3) DEFAULT NULL,
  ADD COLUMN strength DECIMAL(4,2) NOT NULL DEFAULT 1.00,
  ADD COLUMN arousal DECIMAL(3,2) DEFAULT NULL,
  ADD COLUMN valence DECIMAL(3,2) DEFAULT NULL,
  ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'active',
  ADD COLUMN superseded_by BIGINT DEFAULT NULL,
  ADD COLUMN source_episode_id BIGINT DEFAULT NULL,
  ADD COLUMN content_hash CHAR(64) DEFAULT NULL,
  ADD COLUMN embedding_state TINYINT NOT NULL DEFAULT 2,
  ADD COLUMN pinned TINYINT(1) NOT NULL DEFAULT 0,
  ADD COLUMN created_by VARCHAR(16) NOT NULL DEFAULT 'ai',
  ADD COLUMN revision INT NOT NULL DEFAULT 1,
  ADD COLUMN quote TEXT DEFAULT NULL,
  ADD COLUMN updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3);

CREATE UNIQUE INDEX uk_memory_content ON memory_meta (character_id, user_id, content_hash);
CREATE INDEX idx_memory_recall ON memory_meta (character_id, user_id, status, layer, importance);
CREATE INDEX idx_memory_time ON memory_meta (character_id, user_id, event_time);
ALTER TABLE memory_meta ADD FULLTEXT INDEX ft_memory_summary (summary) WITH PARSER ngram;
```

```sql
CREATE TABLE memory_episode (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  character_id BIGINT NOT NULL,
  conversation_id BIGINT DEFAULT NULL,
  started_at DATETIME(3) NOT NULL,
  ended_at DATETIME(3) NOT NULL,
  message_count INT NOT NULL DEFAULT 0,
  first_message_id BIGINT DEFAULT NULL,
  last_message_id BIGINT DEFAULT NULL,
  topic VARCHAR(128) DEFAULT NULL,
  mood VARCHAR(32) DEFAULT NULL,
  summary TEXT DEFAULT NULL,
  quote TEXT DEFAULT NULL,
  salience DECIMAL(3,2) NOT NULL DEFAULT 0.50,
  state TINYINT NOT NULL DEFAULT 0,          -- 0 pending 1 done 2 skipped 3 failed
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_episode_recall (character_id, user_id, ended_at),
  KEY idx_episode_state (state, ended_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE memory_revision (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  memory_id BIGINT NOT NULL,
  revision INT NOT NULL,
  op VARCHAR(16) NOT NULL,                   -- add/update/supersede/merge/pin/delete
  actor VARCHAR(16) NOT NULL,                -- ai/rule/user/job
  before_json JSON DEFAULT NULL,
  after_json JSON DEFAULT NULL,
  reason VARCHAR(255) DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_memory_revision (memory_id, revision),
  KEY idx_revision_memory (memory_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

**Milvus**：新建 `memory_vectors_v3`（不升级集群，v2.4.15 足够）：

```
id INT64 (PK, = memory_id) | character_id INT64 | user_id INT64
summary VARCHAR(1024) | memory_type VARCHAR(16) | layer VARCHAR(16)
status INT8 | event_time INT64 | importance FLOAT | vector FLOAT_VECTOR(1024)
index: HNSW(M=16, efConstruction=200, metric=COSINE)
```

partition key = `character_id`（64 分区）；查询恒带 `character_id == ? && user_id == ? && status == 1`。

**为什么不升 Milvus 2.5 用内置 BM25**：我们现在是 v2.4.15（`docker-compose.yml:307`），升级有停机与索引重建风险；而中文稀疏那一路用 **MySQL ngram 全文索引**就够（数据量万级、MySQL 8.4 已在 `docker-compose.yml:250`），还天然与真相源一致。等真到十万级再谈 Milvus 2.5。

> ngram 的两个坑，P1 上线前必须验证：① `ngram_token_size` 默认 2，单字查询（"雪"）召回为空，查询串短于 2 字时直接走 dense；② 迁移文件 `V57` 里 `WITH PARSER ngram` 必须与生产 MySQL 编译一致（官方镜像自带），本地 MariaDB 会失败。

### 4.4 检索 Retrieve（Python）

五路并行，全部带 `(character_id, user_id, status='active', valid window)`：

| 路 | 来源 | 量 | 用途 |
|---|---|---|---|
| core | MySQL `layer='core' OR pinned=1` | ≤12 | 常驻，不参与排序 |
| 结构化 | `relationship_state` / `memory_meta(open_loop)` / `character_state` | ≤6 | 关系阶段、承诺、此刻情绪 |
| dense | Milvus v3 | top 40 | 语义近邻 |
| sparse | MySQL `MATCH(summary) AGAINST(? IN NATURAL LANGUAGE MODE)` | top 40 | 人名/地名/礼物名等精确词 |
| 近因 | 最近 3 个 episode | ≤3 | "刚刚说过" |

融合与裁剪：

```python
fused = rrf([dense, sparse], k=60)[:24]        # score = Σ 1/(60+rank)
if rerank_enabled:
    fused = await rerank(query, fused, timeout=0.25)   # 超时回退 RRF 序
picked = mmr(fused, lambda_=0.72)[:8]          # 去"回声室"：同义记忆只留一条
picked = dedupe_by_slot(picked)                # (subject,predicate) 只留最新
```

**查询构造**：`query = 最后一条用户消息 + 最近 4 轮里的实体词`；识别到时间词（"上周三""我生日""去年冬天"）时，额外加 `event_time` 区间过滤，并把绝对时间写进渲染文本。

**延迟预算**：embed 60ms + Milvus 20ms + MySQL 全文 15ms + rerank ≤250ms + 组装 10ms ≈ **p95 350ms**（rerank 关闭时 ≈110ms）。

### 4.5 输出 Inject（Python）

不再按 `[用户画像]` 这种数据库分组渲染，改成"回忆的口吻"，并显式给规则：

```
=== 你记得的事（这些是你亲身经历的，不是用户刚发来的资料） ===
【关于她】
- 她叫阿岚，26 岁，杭州插画师，怕冷。（2026-08-14｜重要）
- 她不吃香菜，但喜欢番茄牛腩。（2026-09-02）
【我们之间】
- 认识 214 天，现在是"依赖期"。10-12 闹过一次，她说"别走"。（关系）
- 还欠着一件事：她说入冬想去看第一场雪。（未完成）
【最近的片段】
- 11-03 深夜，她加班到两点，说"今天不想当大人了"。（情绪 0.72）
【此刻】
- 她有点低落（0.6），今天还没怎么说话。

引用规则：
1. 只在自然的时候提起，不要逐条复述，不要像汇报数据库。
2. 日期不确定就不要说具体日期；宁可说"我记得有一次"。
3. 用户纠正过的，以最新那条为准；旧的当作"记错了"。
4. 这里没有的事就是真的不记得，直接说记不清，不要编。
```

预算：core 260 字、关系/此刻 180 字、检索片段 520 字、留白 140 字，总计 ≈1100 字（≈700 token）。**记忆块的字符预算写进配置**（`memory_block_budget_chars`），超了按"重要度 × 相关度"截断，不按时间截断（避免截断悬崖）。

**主动提起**：`proactive_context.py` 增加一路输入——`open_loop` 到期/临近（"说好入冬看雪"）、里程碑周年、情绪低谷 + 48h 未联系。这是"她记得"最强的体感来源，成本极低。

---

## 5. 数据流：一次对话的完整时序

```
用户发消息
  └─ Java: 存 message，取事实（时间/开关/关系/情绪）
      └─ Python: assemble_prompt
          ├─ core（常驻，Redis 缓存 10min）
          ├─ recall（dense + ngram + RRF + MMR）
          ├─ 结构化（关系/情绪/open loop）
          └─ render_memory_block → system prompt
      └─ 模型 → SSE 流式回复
  └─ Java: 会话空闲 30min / 满 40 条 → 发 EpisodeClosed（含 message 区间）
      └─ Python: episode 抽取（salience gate → LLM → 候选）
          └─ write.py 事务：memory_meta + memory_revision + outbox
              └─ relay：embedding → Milvus v3 upsert（幂等）
  └─ 每日 03:00：整合 job（聚类 cosine>0.88 → 合并 reflection；衰减 strength；冷热分层）
```

---

## 6. 接口与分层

| 层 | 做什么 | 不做什么 |
|---|---|---|
| Java | `GET/PATCH/DELETE /memory`、pin、timeline、revisions；用户编辑与删除；outbox 状态机 | 不拼 prompt、不调模型、不直连 Milvus |
| Python | 抽取、判定、整合、embedding、检索、注入、Milvus 读写 | 不写白名单外的表、不定义 API 契约 |
| Electron | 无（记忆不出本机之外，除模型调用） | — |

API 变更（**只增不改**，保证星图不炸）：

- `GET /memory` 增加 `layer / pinned / eventTime / quote / heat / status`（可选字段）；
- `PATCH /memory/{id}`：`summary / importance / pinned / status`（写 `memory_revision`，`actor='user'`）；
- `POST /memory/{id}/pin`、`DELETE /memory/{id}`（改软删：`status='deleted'`，7 天后清空 `summary/quote`，向量立即删）；
- `GET /memory/timeline?characterId=`、`GET /memory/{id}/revisions`。

前端星图（`frontend/src/components/memory/`）：`memoryBrainModel.js` 的 `normalizeMemory` 增加 `layer/pinned/heat/quote` 读取；新增"pin 环""热度光晕""时间轴回放时按 eventTime 而不是 createdAt"；详情抽屉增加编辑与 pin。**字段全部可选，旧数据不受影响。**

---

## 7. 迁移与回填

1. `V57` 加列 + 索引（列加用 INSTANT；FULLTEXT 建索引会锁写，放低峰，或先 `pt-online-schema-change`）。
2. 回填 SQL（一次事务，分批 5k）：
   ```sql
   UPDATE memory_meta
   SET layer = CASE WHEN summary LIKE '【长期记忆/%' THEN 'core'
                    WHEN memory_type IN ('RELATION','RITUAL') THEN 'fact'
                    ELSE 'episode' END,
       status = 'active',
       valid_from = created_at,
       content_hash = SHA2(CONCAT(character_id, '|', user_id, '|', TRIM(summary)), 256),
       embedding_state = IF(milvus_vec_id IS NULL, 0, 1)
   WHERE content_hash IS NULL;
   ```
3. 新建 `memory_vectors_v3` → 批量 re-embed（Python，限速 20 QPS，失败重试）→ 双写 3 天 → 切读 → 删 v2。
4. 回滚开关：`memory.v2.retrieval` / `memory.v2.write` / `memory.v2.inject` 三个 flag，任一关掉即回旧路径。

---

## 8. 评测

**离线**（`backend-agent/tests/memory_eval/`，200 条，人工标 `gold_memory_id`）：

| 指标 | 目标 |
|---|---|
| recall@5 / recall@10 | ≥0.80 / ≥0.90 |
| MRR | ≥0.70 |
| 引用正确率（judge 判"回答里的记忆是否真在库中"） | ≥0.95 |
| 编造率（日期/姓名/事件与库冲突） | ≤2% |
| 主动提起命中率（该提的时候提了） | ≥0.6 |

**线上**：记忆块 token 占比、empty-recall 率、检索 p95、重复记忆率、用户删除/编辑率（高 = 抽取质量差）。

**对照实验**：旧路径 vs 新路径各跑同一批 200 题，报告 per-category（单跳/多跳/时间/情绪），不只报总分。

---

## 9. 成本与延迟

| 项 | 量 | 备注 |
|---|---|---|
| 抽取 | 1 次/episode，约 2k in + 500 out | 按 30 episode/天 ≈ 60k in / 15k out |
| 灰区判定 | ≤30% 的候选，1 次 ≈500 tok | 双阈值砍掉约 70% |
| 整合 | 每晚 1 次/角色，约 1k tok | 1k 条记忆约 30~60 次调用 |
| 检索 | embed 1 次/轮 + rerank 20 条 | rerank 走 GPU 才划算（CPU 16 核 20 条要 3~6s，不可上线，故默认关） |

按现用模型单价折算，单用户日成本约 **¥0.1~0.4**（不含 rerank）；rerank 上线后按 GPU 固定成本摊。

---

## 10. 路线图（每期可独立上线）

| 期 | 内容 | 工期 | 验收 |
|---|---|---|---|
| **P0** | V57 迁移、回填、评测集骨架、三个 flag、基线数字；顺手修 `milvus_store.search` 的参数顺序 | 3~4 天 | `recall@5` 基线记录在案；旧路径不受影响 |
| **P1** | 检索重写：core + 混合召回 + RRF + MMR + 新注入模板（读路径换血，写入不动） | 1 周 | 离线 recall +15pt；线上 p95 < 400ms；flag 可回滚 |
| **P2** | 抽取/写入重写：episode 切分、结构化抽取、双阈值去重、版本化、outbox 幂等 | 1.5~2 周 | 影子写 3 天零冲突；重复率下降 50% |
| **P3** | 整合与衰减 job、pin/编辑 API、星图详情与时间轴、召回解释 | 1 周 | 用户可改可 pin；夜间 job 稳定 |
| **P4** | GPU rerank、跨角色共享画像（默认关）、闭包表（仅多跳需求出现时） | backlog | — |

---

## 11. 不做清单（以及为什么）

| 不做 | 原因 |
|---|---|
| Neo4j / 图数据库 | 5 万事实量级 MySQL 两跳 5~15ms，多一套集群 +1~2GB 不划算；出现"她哥哥的工作"这类多跳需求再上闭包表 |
| HippoRAG 式 PPR | 我们的多跳最多 2 跳，PPR 的收益抵不上建图成本 |
| A-Mem 式自动建链/演化 | 每次写入 O(k) 次 LLM，链接爆炸，且会改事实（只允许改 tags 的变体可后续考虑） |
| MemGPT 式模型自主换页 | 每轮多次 LLM + 工具循环，延迟扛不住；core memory 块的思想保留 |
| 每轮 LLM 抽取 | 成本 ×N、上下文碎、误判高；改 episode 批处理 |
| LLM 决定 DELETE | Mem0 自己已把文档改成 ADD-only，说明生产误判率高；我们只做 supersede，不物理删 |
| 跨角色全局记忆（默认） | 串味是恋人产品最致命 bug；只保留显式白名单的"用户画像"共享层 |

---

## 12. 调研依据（摘）

**学界**（详见各论文）：

- Zep/Graphiti 双时间轴：`valid_at/invalid_at` + `created_at/expired_at`，失效不删；LongMemEval 71.2% vs full-context 60.2%，延迟 2.58s vs 28.9s，上下文 1.6k vs 115k token（[arXiv 2501.13956](https://arxiv.org/html/2501.13956v1)）。
- Mem0：抽取 → 与 top-s 旧记忆比对 → ADD/UPDATE/DELETE/NOOP；LoCoMo judge 66.88（full-context 72.90），但 token −90%，p95 −91%（[arXiv 2504.19413](https://arxiv.org/html/2504.19413v1)）。其 2026 文档已改为 ADD-only（[docs.mem0.ai](https://docs.mem0.ai/core-concepts/memory-operations)），我们据此不做 DELETE 判定。
- Generative Agents：`score = α·recency + α·importance + α·relevance`，recency = 0.995^Δh（[arXiv 2304.03442](https://arxiv.org/abs/2304.03442)）。
- MemoryBank：`R = e^(−Δt/S)`，S 随召回增强（[arXiv 2305.10250](https://arxiv.org/abs/2305.10250)）。
- MemGPT/Letta core memory 块常驻 + 自编辑（[arXiv 2310.08560](https://arxiv.org/abs/2310.08560)）——只借"常驻块"，不借"自主换页"。
- 评测：LoCoMo（[arXiv 2402.17753](https://arxiv.org/abs/2402.17753)）、LongMemEval（[arXiv 2410.10813](https://arxiv.org/abs/2410.10813)）。中文情感陪伴无公开基准，必须自建。

**工程**：

- Milvus partition key 与多租户：开 isolation 后 filter 只能单值 `==`（[milvus.io/docs/use-partition-key.md](https://milvus.io/docs/use-partition-key.md)）。我们恒按单角色查，不受影响。
- 中文 BM25 的 analyzer 坑：默认 analyzer 对中文分词极差，召回≈0；因此我们用 MySQL ngram 而不是 Milvus 内置 BM25（也避免升级 2.5）。
- 重排：bge-reranker-v2-m3（MIT）GPU≈12ms/pair；CPU 16 核 150~300ms/pair，20 条 3~6s，不能上线上。
- 去重双阈值：cosine ≥0.92 NOOP / <0.75 ADD / 灰区 LLM，可砍约 70% 判定调用。
- 许可证：Mem0 / Graphiti / Letta / Cognee / Memobase 均 Apache-2.0，可商用；bge 系列 MIT。

**同类产品**：

- Character.AI：Story Memory + 自动 Facts + **pin 免压缩**（[Prompt Poet](https://blog.character.ai/introducing-prompt-poet/)）。
- Replika：记忆清单可读可删；ChatGPT：记忆可看可改可删 + 两个独立开关。
- Kindroid：Learned Context 可编辑、journal 关键词触发、**召回可解释 + Deprioritize**。
- Nomi：短/中/长期 + Identity Core + Mind Map（唯一把记忆结构可视化的）。
- 反面：Paradot 编生日、Chai 无记忆管理器、Character.AI 固定截断点导致"突然全忘"。

---

## 13. 风险登记

| 风险 | 影响 | 缓解 |
|---|---|---|
| 迁移期双写不一致 | 记忆重复/丢失 | outbox + `embedding_state` 对账；双写 3 天；flag 回滚 |
| FULLTEXT 索引锁写 | 上线窗口卡顿 | 低峰执行 / pt-osc；先建索引再加列（分批） |
| 抽取 prompt 改动导致质量回退 | 记忆变差 | 200 题评测集 + 影子对比；prompt 版本号入库 |
| 记忆注入变长挤占人设 | 角色"变味" | 硬预算 1100 字 + 超限按相关度截断；A/B 看人设评分 |
| 用户删除后残留 | 隐私 | 软删 7 天后清空正文；向量立即删；revision 只留 id 不留正文 |
| 跨角色串味 | 产品级事故 | 所有查询强制 `(user_id, character_id)`；共享层白名单默认空 |
