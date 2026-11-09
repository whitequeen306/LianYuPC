# 后勤主编材料 · 20261104-161638-80f129

> 这是给主会话模型（主编）的候选材料，不是最终报告。
> 去重、定级、取舍、模块组织由主编决定；机器只负责收集与校验。

## 运行
- 根目录：`C:/Users/hp/Desktop/LianYu-PC`
- 范围：1 个文件，+18/-0 行
- 测试命令：（未检测到）

## 覆盖
- 预期专家：correctness
- 已收到：correctness
- 缺失：（无）
- 无效：（无）
- 失败：（无）
- partial：（无）
- 可选未选：perf

## 专家摘要
### correctness · ok
- 摘要：本次只新增 V57__relationship_trust_anomaly_repair.sql（18 行，一条覆盖式 UPDATE：trust_score>=90 且 intimacy_score<=40 的行 trust 置为 50）。核对计分实现后发现该谓词并不能代表「被 REPAIR_ATTEMPT 刷高」：信任分从不衰减，亲密分却会随离线衰减到地板 20，且 ASSISTANT_VULNERABLE_REPLY 本身就是 +2 信任 / +0 亲密、USER_WARM_RESPONSE 是 +0 信任 / +3 亲密，所以 trust=100/intimacy=20 是完全合法可达的状态，迁移会把长期用户的信任永久砍到 50，且没有 before-image 无法恢复。阈值 90/40 也不对应任何计分不变量，会漏掉同类异常。共 4 条发现（data-integrity 2、architecture 1、edge-cases 1）。
- sections：logic=0 · state=0 · concurrency=0 · data-integrity=2 · contracts=0 · architecture=1 · edge-cases=1
- notes：已检查但判为干净的部分：state 记 0——迁移改了 trust_score 但没重算 phase，我逐条验证了受影响行（trust>=90, intimacy<=40）在改前改后 RelationshipPhaseResolver.deriveFromScores 都只会落到 FAMILIAR（intimacy<55 走不到 DEPENDENT/STABLE_INTIMATE，trust 50 仍 >= FAMILIAR_TRUST=45），INJURED/REPAIRING 由时间戳驱动与 trust 无关，因此不存在可证的 phase 漂移；also updated_at 被 ON UPDATE 自动刷新，但 relationship 相关代码只用 last_decay_at/last_interaction_at/created_at，V56 的 updated_at 回填已在 V57 之前执行，无副作用。concurrency 记 0——docker-compose.yml 中 backend 为单容器（container_name: lianyu-backend，无 replicas），Flyway 在 Web 端口对外服务前完成，未见并发写入窗口。contracts 记 0——表结构未变，Python 侧 data_readers.py 只读 trust_score 数值，字段名/语义未改，无外部 API/SSE 契约变化。logic 记 0——真正的问题不是控制流写错（SQL 本身是合法单语句、幂等重跑安全），而是判定规则不成立，已分别记在 data-integrity 与 edge-cases 里，避免重复计数。未执行任何构建/测试（只读评审），未修改源码。
- 发现数：4

### perf · not-selected
- 可选专家，本次未选择（不是缺失）。

## 候选 findings
### COR-B8580 · [高] V57 的谓词会命中合法状态：长期用户离线后信任仍高、亲密衰减到地板，被一次性重置为 50
- 专家 / section：correctness / data-integrity
- 位置：`backend/lianyu-dao/src/main/resources/db/migration/V57__relationship_trust_anomaly_repair.sql:15`
- 置信度：high · kind：plan
- 证据：迁移正文：
15 UPDATE relationship_state
16 SET trust_score = 50
17 WHERE trust_score >= 90
18   AND intimacy_score <= 40;
注释理由（第 5-6 行）："Observed rows: trust=100/intimacy=32, 100/35, 92/20 -- impossible under the current scoring, where every intimacy-bearing event also moves trust."

该理由与同仓库计分实现不符：
1) 信任分只增不减，只有事件结算与建行会写它：backend/lianyu-service/src/main/java/com/lianyu/service/relationship/RelationshipStateService.java:252 `state.setTrustScore(snapshot.trustScore());`、:440 `created.setTrustScore(40);`（全仓 setTrustScore 仅这两处生产代码）。
2) 时间衰减只改 anticipation / intimacy / security，完全不碰 trust：RelationshipStateService.java:208-220，且亲密有地板 INTIMACY_FLOOR = 20（:40 `private static final int INTIMACY_FLOOR = 20;`），衰减上限 MAX_DECAY_DAYS = 30，由 RelationshipDecayScheduler 每 1800000ms 跑 decayAll。
   触发路径：用户 trust=98 / intimacy=95 → 90 天不说话 → 衰减每次最多 -30 亲密、信任不动 → intimacy 落到 20，trust 仍是 98 → 迁移把 trust 改成 50。
3) 「亲密事件一定带信任」也不成立：RelationshipStateService.java:532-535 `case USER_WARM_RESPONSE -> { intimacy += 3 * input.weight(); anticipation += 2 * input.weight(); }`（+0 信任）、:554-557 SPECIAL_NICKNAME_CREATED / RITUAL_ESTABLISHED 只加亲密与期待；反过来 :536-540 `case ASSISTANT_VULNERABLE_REPLY -> { security += 3; trust += 2; }` 是 +2 信任 / +0 亲密，且没有次数上限（RelationshipHeuristics.java:86-88 只按安抚词表命中即计分）。45 条安抚回复即可把 trust 顶到 100 而 intimacy 不动，同样命中谓词。
- 影响：把「长期陪伴后离开一段时间」与「角色多次安抚」这两类完全合法的用户，判成刷分并把 trust 永久降到 50：前端关系面板、Python prompt 里的信任事实（backend/lianyu-service/src/main/java/com/lianyu/service/character/CharacterStateQueryService.java:184 输出 relationshipTrustScore；backend-agent/app/data_readers.py:36 读 trust_score）都会在部署瞬间掉档，而 Flyway 只跑一次、本文件无备份，改错不可恢复。提交说明里「92/20 在正常计分下不可能出现」的结论是错的，受影响行里很可能就包含这类用户。
- 修复摘要：不要用「trust 高 + intimacy 低」这种快照比例当刷分证据；改为用 relationship_event 事件账本判断信任分是谁给的，只回滚能被 REPAIR_ATTEMPT 解释的那部分。
- 修复文件：backend/lianyu-dao/src/main/resources/db/migration/V57__relationship_trust_anomaly_repair.sql、backend/lianyu-service/src/main/java/com/lianyu/service/relationship/RelationshipStateService.java、backend/lianyu-service/src/test/java/com/lianyu/service/relationship/RelationshipStateServiceTest.java
- 建议测试：RelationshipStateServiceTest：新增用例「长时间离线后 trust 保持、intimacy 衰减到 20 的行不得被数据修复命中」；手工 SQL 验证：对 (trust=98,intimacy=20,无 REPAIR_ATTEMPT 事件) 的行执行新迁移后 trust 仍为 98；手工 SQL 验证：对 (trust=100,intimacy=32,有 15 条 REPAIR_ATTEMPT 事件) 的行执行新迁移后 trust 按 4*15 的幅度回滚

### COR-7B96F · [中] 90/40 阈值不对应任何计分不变量：trust 80-89 与 intimacy 41-55 的同类异常一行都修不到
- 专家 / section：correctness / edge-cases
- 位置：`backend/lianyu-dao/src/main/resources/db/migration/V57__relationship_trust_anomaly_repair.sql:17`
- 置信度：high · kind：plan
- 证据：谓词边界：17 `WHERE trust_score >= 90`、18 `AND intimacy_score <= 40`。
代码里能对上的阈值只有 RelationshipPhaseResolver.java:18-21 的 FAMILIAR_TRUST=45 / DEPENDENT_INTIMACY=55 / STABLE_INTIMACY=70 / STABLE_TRUST=65，没有任何 90 或 40 的常量。
同一份注释（第 12-13 行）承认 trust 60-80 / intimacy 20 是合法的，说明作者无法给出上界，90 只是取了个整数；而刷分路径每次 +4、无每日上限（RelationshipStateService.java:545-548 `case REPAIR_ATTEMPT -> { trust += 4 * input.weight(); security += 3 * input.weight(); }`，被 :238-241 的 injuryOpen 门控之前是逐条累加），因此 trust=88 / intimacy=20、trust=100 / intimacy=45（亲密靠 USER_WARM_RESPONSE 长、信任靠安抚回复长）都是同一机制造成的同类脏数据，却不在谓词内。
- 影响：一次性迁移执行后，被同一条旧 bug 污染但恰好落在 80-89 或 intimacy 41-55 的行仍保持错误状态；Flyway 不会重跑，用户看到的关系档位继续偏高，且没有任何标记能让后续排查认出这批行（提交说明里的「部署后复查 trust 分布」也查不出来）。
- 修复摘要：把「哪些行需要修」写成可解释的规则（事件账本/不变量），而不是 trust>=90 这个拍出来的整数；边界必须能回答「为什么是 90 不是 89」。
- 修复文件：backend/lianyu-dao/src/main/resources/db/migration/V57__relationship_trust_anomaly_repair.sql、backend/lianyu-service/src/main/java/com/lianyu/service/relationship/RelationshipStateService.java
- 建议测试：单测：构造 trust=88/intimacy=20 且存在未受伤 REPAIR_ATTEMPT 事件的快照，断言它被识别为异常行；单测：构造 trust=50/intimacy=40 的正常行进阶用例，断言不被识别为异常行

### COR-919AD · [中] 覆盖式 UPDATE 没有保留 before-image，改错既不能恢复也无法定位受影响用户
- 专家 / section：correctness / data-integrity
- 位置：`backend/lianyu-dao/src/main/resources/db/migration/V57__relationship_trust_anomaly_repair.sql:16`
- 置信度：high · kind：plan
- 证据：整个迁移只有一个写语句：15-18 的 `UPDATE relationship_state SET trust_score = 50 WHERE ...`，没有先把待改行落进备份表，也没有留下被改行的 id/user_id 清单；Flyway 的 flyway_schema_history 只记录脚本校验和与执行时间，不记录受影响行。执行后 trust=50 与自然产生的 50 无法区分，提交说明里的验证手段「部署后复查 trust 分布」只能看到一堆 50，无法证明改对了还是改错了。
- 影响：在谓词本身被证明会误伤合法用户（见同文件 15-18 行的 finding）的前提下，这次改写不可逆：原 trust=100/98/92 被永久抹掉，产品的「关系会一直记得你」承诺在这批用户身上回退；客服/运营也无法按用户维度说明谁被改过、改前是多少。
- 修复摘要：把覆盖式 UPDATE 改成「先落 before-image 再改」，并让备份表成为事后审计与回滚的唯一依据。
- 修复文件：backend/lianyu-dao/src/main/resources/db/migration/V57__relationship_trust_anomaly_repair.sql
- 建议测试：本地 docker compose MySQL：插入 3 行命中谓词 + 2 行不命中，执行迁移后校验备份表恰好 3 行、值等于改前值；本地：重复执行同一份 SQL，校验备份表不二次插入（IF NOT EXISTS / 幂等键）

### COR-D71AB · [低] 计分口径被硬编码进 SQL，成为第二事实来源（90/50 与代码里的 45/65 和事件权重没有关联）
- 专家 / section：correctness / architecture
- 位置：`backend/lianyu-dao/src/main/resources/db/migration/V57__relationship_trust_anomaly_repair.sql:18`
- 置信度：medium · kind：plan
- 证据：SQL 里出现三个只在本文件有意义的数字：16 `SET trust_score = 50`、17 `trust_score >= 90`、18 `intimacy_score <= 40`。真实的计分口径分布在别处且是对的：RelationshipStateService.java:517-571 的 applyEvent 权重表、:30-43 的常量（PRESENCE_TRUST=1、INTIMACY_FLOOR=20、SECURITY_BASELINE=40）、RelationshipPhaseResolver.java:18-21 的阶段阈值。没有任何注释或常量把 SQL 的数字与它们对应起来。
- 影响：这是一次性数据修复，却把「什么样的分数算异常、修到多少算正常」的定义复制到了不可再执行的迁移文件里：下一次调整信任权重（例如给安抚回复加封顶）时，这段 SQL 的语义就悄悄失效，而 Flyway 已执行、没人会回来改它；后续同样的刷分问题只能再拍一个数字、再写一个迁移，规则永远没有 owner。
- 修复摘要：把「异常判定 + 修正值」收敛成一处有 owner 的规则（复用 RelationshipStateService 的权重与 RelationshipPhaseResolver 的阈值），迁移只负责触发它。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/relationship/RelationshipStateService.java、backend/lianyu-service/src/main/java/com/lianyu/service/relationship/RelationshipPhaseResolver.java、backend/lianyu-dao/src/main/resources/db/migration/V57__relationship_trust_anomaly_repair.sql
- 建议测试：RelationshipStateServiceTest：repairTrustAnomalies 对「真刷分行」返回 1 条、对「离线掉亲密行」返回 0 条；RelationshipPhaseResolver 相关单测：断言异常判定阈值与阶段阈值的关系约束

## 转交 deferred
- 没有跨专家转交项。

## 机器提示（仅供参考）
- 没有检测到明显的跨专家重复候选。

## 交给主编
1. 读本文件与原始 handoff；不要只依赖机器提示。
2. 合并重复项，保留最可信的证据；被合并的 ID 记入 verdict.dropped。
3. 写 `verdict.json`（schemaVersion 2、findings、coverage、dropped），再写 `report.md`。
4. 只有完整覆盖默认四模块时，才更新 `docs/logistics/latest.md` 与 `.logistics/latest.json`。
5. 工作区根目录：`C:\Users\hp\Desktop\LianYu-PC`；运行目录：`C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261104-161638-80f129`。
