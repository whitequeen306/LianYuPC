# 后勤审查报告 · 20261104-161638-80f129

## 1. 结论与覆盖范围

**结论：V57 这条迁移的判定规则站不住——它假设「trust 高 + intimacy 低」在正常计分下不可能出现，但信任分从不衰减、亲密分会衰减到地板 20，所以长期不活跃的合法用户天然就是 92/20、100/32 这种形状；照原样执行会把这类用户（以及「角色反复安抚」的用户）的信任永久砍到 50，且没有 before-image 可以回滚。建议先不要执行，先把命中的 3 行按事件账本逐行核实。**

| 项 | 内容 |
|---|---|
| 运行方式 | `/logi-correctness`（单模块） |
| 已跑模块 | correctness（1/1，status=ok；logic / state / concurrency / data-integrity / contracts / architecture / edge-cases 全部已检查） |
| 未跑模块 | security、maintainability、reliability（本次未选择）；perf 为可选专家，本次未选择 |
| 范围 | 1 个文件，+18/-0 行；分支 `main`，HEAD `c652e14` |
| 改动文件 | `backend/lianyu-dao/src/main/resources/db/migration/V57__relationship_trust_anomaly_repair.sql`（新增） |
| 覆盖完整性 | complete（无缺失/无效/失败 handoff） |

保留下来的 3 条发现（1 条与原 `COR-7B96F` 同根因合并，见第 2 节说明）：

| ID | 严重度 | 类型 | 标题 |
|---|---|---|---|
| COR-B8580 | high | plan | 谓词把合法状态当刷分证据，合法用户会被永久降到 50 |
| COR-919AD | medium | plan | 覆盖式 UPDATE 没有 before-image，改错不可恢复、无法定位 |
| COR-D71AB | low | plan | 计分口径硬编码进迁移 SQL，成为第二事实来源 |

## 2. 按模块组织的发现

### 模块：correctness（`lianyu-dao` / Flyway 迁移）

#### COR-B8580 · high · `backend/lianyu-dao/src/main/resources/db/migration/V57__relationship_trust_anomaly_repair.sql:15`

**标题**：V57 的谓词把「信任高 + 亲密低」当刷分证据，但该状态在正常计分下合法可达（信任从不衰减、亲密会衰减到地板 20），会把合法用户永久降到 50。

**证据摘要**（均已在源码中逐条复核）：

- 迁移只做了 `UPDATE relationship_state SET trust_score = 50 WHERE trust_score >= 90 AND intimacy_score <= 40;`，注释 5-6 行的理由是「100/32、100/35、92/20 在当前计分下不可能出现，因为每个带亲密的动作也会动信任」。
- 信任分只增不减：写入点只有 `RelationshipStateService.java:252`（`state.setTrustScore(snapshot.trustScore())`）与 `:440`（`created.setTrustScore(40)`）。
- 时间衰减完全不碰 trust：`RelationshipStateService.java:208-219` 只改 anticipation / intimacy / security，亲密有地板 `INTIMACY_FLOOR = 20`（`:40`），`MAX_DECAY_DAYS = 30`（`:37`），`INTIMACY_GRACE_HOURS = 72`。
- 触发路径：trust=98 / intimacy=95 的活跃用户 → 连续 90 天无交互 → intimacy 衰减到 20、trust 仍是 98 → 命中谓词，被改成 50。
- 「亲密事件一定带信任」也不成立：`:532-534` 的 `USER_WARM_RESPONSE` 是 +3 亲密 / +0 信任，`:554-557` 的 `SPECIAL_NICKNAME_CREATED` / `RITUAL_ESTABLISHED` 同样只加亲密。
- 反向路径无上限：`:536-539` 的 `ASSISTANT_VULNERABLE_REPLY` 是 +2 信任 / +0 亲密，每条含安抚词的助手消息都会产生一次（`:149` 用 `RelationshipHeuristics.isComfortingReply` 判定 → `:155` 构造事件）；从默认 40 起约 25 次安抚回复即可越过 90。

**影响**：两类合法用户被判成刷分并永久降档，且降档会立刻出现在前端关系面板与 Python prompt 的信任事实（`CharacterStateQueryService.java:184`、`backend-agent/app/data_readers.py:36`）。规模要公道说：提交自述「现象：3 行」说明线上命中面是 3 行，不是全量用户；但这 3 行的原值（100/100/92）会被永久抹掉，当中可能正包含上面两类用户——所以结论不是「影响面小、可以放过」，而是「执行前必须把这 3 行按事件账本逐行判一次」。同一谓词还有漏判面：`>= 90` 不对应任何常量（代码里的阈值只有 `RelationshipPhaseResolver.java:18-21` 的 45/55/65/70），被同一旧 bug 污染的 trust 80-89 行、或 intimacy 41-55 行一行都修不到，而 Flyway 不会重跑。

**修复摘要**：改用 `relationship_event` 事件账本反推「若没有 REPAIR_ATTEMPT，trust 应为多少」，只回滚可解释的那部分增量；判定搬到 `RelationshipStateService`，迁移只负责触发。步骤与测试见 verdict 的 `fix`（5 步：先取证 → 判定入 Java → 迁移改形 → 不可解释行只打标记 → 补回归测试）。

**合并说明**：原候选 `COR-7B96F`（90/40 阈值不对应任何计分不变量、80-89 行漏修）与本条同一根因，已并入本条 `impact`，记入 `verdict.dropped`，不重复计数。

#### COR-919AD · medium · `backend/lianyu-dao/src/main/resources/db/migration/V57__relationship_trust_anomaly_repair.sql:16`

**标题**：覆盖式 UPDATE 没有保留 before-image：改错无法恢复，也无法事后按用户定位被改过的行。

**证据摘要**：整个迁移只有一个写语句，没有备份表也没有被改行清单；`flyway_schema_history` 只记版本、校验和与执行时间，不记受影响行。提交说明的验证手段是「部署后复查 trust 分布」，但执行后 `trust=50` 与自然产生的 50 无法区分，复查看不出改对还是改错。

**影响**：在上一条已证明谓词会误伤的前提下，这次改写不可逆——原 `trust=100/100/92` 被永久抹掉，客服也无法按用户回答「谁被改过、改前多少」。

**修复摘要**：先 `INSERT INTO` 备份表（`run_id/user_id/character_id/old_trust/old_intimacy/changed_at`），再按主键 join 更新；加幂等键；提供从备份表还原的回滚脚本。若 V57 已在某环境执行，改为新增 V58。

#### COR-D71AB · low · `backend/lianyu-dao/src/main/resources/db/migration/V57__relationship_trust_anomaly_repair.sql:18`

**标题**：计分口径被硬编码进迁移 SQL，成为第二事实来源（90/40/50 与代码里的 45/65 及事件权重无任何关联）。

**证据摘要**：SQL 里三个只在本文件有意义的数字（`SET trust_score = 50`、`>= 90`、`<= 40`），而真实口径在 `RelationshipStateService.java:517-571`（applyEvent 权重）、`:30-43`（`PRESENCE_TRUST=1`、`INTIMACY_FLOOR=20`、`SECURITY_BASELINE=40`）、`RelationshipPhaseResolver.java:18-21`（45/55/65/70）；两者没有任何注释或常量关联，relationship 包里也不存在 90 这个常量。

**影响**：下次调整信任权重时（例如给安抚回复加封顶），这段 SQL 的语义会悄悄失效，而 Flyway 已执行、没人会回来改它；同类问题只能再拍一个数字、再写一个迁移。

**修复摘要**：把「异常判定 + 修正值」收敛为 `RelationshipStateService` / `RelationshipPhaseResolver` 里的唯一实现（引用既有常量），迁移只触发，不带魔数。

### 判为干净的面（与 handoff 一致，主编复核后同意）

- **state**：受影响行（trust>=90、intimacy<=40）改前按 `RelationshipPhaseResolver.deriveFromScores` 只会落到 FAMILIAR（intimacy<55 进不了 DEPENDENT/STABLE_INTIMATE，trust>=45 已是 FAMILIAR）；改后 trust=50 仍 >= `FAMILIAR_TRUST=45`，phase 不变。`updated_at` 由 ON UPDATE 刷新，relationship 代码只消费 `last_decay_at` / `last_interaction_at` / `created_at`，无副作用。
- **concurrency**：`docker-compose.yml` 中 backend 单容器（无 replicas），Flyway 在对外服务前完成，无可证的并发写入窗口。
- **contracts**：表结构未变，Python 侧 `data_readers.py:36` 只读 `trust_score` 数值，字段名与语义未改。
- **logic**：SQL 本身是合法的单语句、幂等重跑安全；问题在「判定规则不成立」，已归入 data-integrity / edge-cases（现合并）而非控制流。

## 3. 建议修复顺序

1. **先止血，不要先改 SQL**（COR-B8580）：把当前命中的 3 行（线上）导出，用 `relationship_event` 核对每行 trust 是否真能被 `REPAIR_ATTEMPT` 解释。这一步只读，不动数据。
2. **再决定迁移形态**（COR-919AD 与 COR-B8580 同文件，建议一张单一起做）：改成「先落 before-image → 按事件账本判定 → 只改可解释行」，两个问题一次解决；V57 未执行则原地重写，已执行则新增 V58（不要改已执行文件的 checksum）。
3. **最后收敛口径**（COR-D71AB）：把判定与修正值搬进 `RelationshipStateService` / `RelationshipPhaseResolver`，迁移退化为触发点。属于重构性质，可与第 2 步同一批次，但不阻塞止血。
4. **单独批次**：perf（是否需要索引 / 分批）与 reliability（启动期失败的可见性）转交对应专家，不与本次修复混在一个提交里。

## 4. 覆盖缺口 / 失败专家

- 无失败、无无效 handoff，`coverage.complete = true`。
- **未覆盖**：security、maintainability、reliability 本次未运行；perf 为可选专家、本次未选择。因此本报告不代表「这个迁移整体没问题」——第 5 节的 deferred 正是这些未跑模块的职责。
- 单模块运行：按规则**未更新** `docs/logistics/latest.md` 与 `.logistics/latest.json`。
- 过程备注：本次 handoff 的两条 `deferred` 用的是 `reason` 字段而非 `summary`，collect/bundle 因此给出「缺少非空 summary，已忽略」的警告（`packet.md` 的转交段为空）。主编已按原文内容重建这两条 deferred 写入 `verdict.json`，没有静默丢弃。

## 5. 跨专家 deferred（不因不在本次模块内而省略）

| # | 来源 → 目标 | 文件:行 | 摘要 |
|---|---|---|---|
| 1 | correctness → perf | `backend/lianyu-dao/src/main/resources/db/migration/V57__relationship_trust_anomaly_repair.sql:15-18` | `UPDATE` 以 `trust_score` / `intimacy_score` 为条件且这两列无索引，Flyway 启动期全表扫描 `relationship_state`（线上约 936 行）。是否需要索引/分批由 perf 判断。 |
| 2 | correctness → reliability | 同上 | 迁移在启动期执行，语法错误或锁等待失败会让后端起不来；脚本无命中行数日志与诊断输出，失败不可见。 |

**跨 run 复核（重要）**：上一轮安全审查（run `20261104-161342-a90607`）曾把「V57 未重算 phase，可能出现 trust=50 但 phase=STABLE 的不一致」转交给 correctness。本次逐条算过 `RelationshipPhaseResolver.deriveFromScores`：命中行改前 intimacy<55、trust>=45 → FAMILIAR，改后 trust=50 仍 >=45 → 仍是 FAMILIAR，**不存在可证的 phase 漂移**，该转交项可以关闭；`RelationshipStateService.java:485` 直读 phase 的写法本身仍值得留意，但不构成本次迁移的缺陷。

---

*本报告只陈述问题与修复方案，不含健康分、评分、基线或门禁结论。*
