# 后勤审查报告 · 20261104-161342-a90607

## 1. 结论与覆盖范围

**结论：本次改动（V57 关系信任分修复迁移）在安全面上无问题——没有新增任何可信边界、没有攻击者可控输入进入 SQL、没有密钥/加密/上传/浏览器面；0 条安全发现，但附带 2 条跨专家风险（数据一致性、启动期锁表）需要后续处理。**

| 项 | 内容 |
|---|---|
| 运行方式 | `/logi-security`（单模块） |
| 已跑模块 | security（1/1，status=ok，9 个 section 全部已检查） |
| 未跑模块 | correctness、maintainability、reliability（本次未选择；perf 为可选专家，本次未选择） |
| 范围 | 1 个文件，+18/-0 行；分支 `main`，HEAD `c652e14` |
| 改动文件 | `backend/lianyu-dao/src/main/resources/db/migration/V57__relationship_trust_anomaly_repair.sql`（新增） |
| 覆盖完整性 | complete（无缺失/无效/失败 handoff） |

本次唯一改动是一条 18 行的静态数据修复迁移：

```sql
UPDATE relationship_state
SET trust_score = 50
WHERE trust_score >= 90
  AND intimacy_score <= 40;
```

威胁模型判断：该文件不新增入口（无 HTTP 路由、无请求参数、无动态拼接），执行者是启动期 Flyway（应用自身 DB 账号），用户侧输入无法影响这条语句；WHERE 条件只涉及 `trust_score` / `intimacy_score`，不按 `user_id` 过滤属有意的全表一致性修复，不构成越权读写他人数据。

## 2. 按模块组织的发现

### security（安全）· 0 条

无保留发现。九个小节逐一检查的结论（`sections` 计数与 findings 逐项一致，均为 0 = 已查且干净，非跳过）：

| section | 条数 | 说明 |
|---|---|---|
| auth | 0 | 迁移不触碰密码存储、会话签发/轮换、JWT/OAuth |
| authz | 0 | 无新路由；全表 UPDATE 由部署账号执行，非对象级授权路径 |
| injection | 0 | 无字符串拼接 SQL、无外部内容进入语句；注释为静态文本 |
| browser | 0 | 无 CSP/CORS/postMessage/cookie 相关代码 |
| uploads | 0 | 无文件接收、无出站请求、无 SSRF 面 |
| secrets | 0 | 迁移文件与注释不含口令、令牌、连接串 |
| crypto | 0 | 无加解密、无随机数、无签名逻辑 |
| exposure | 0 | 无响应体/日志/错误信息变更；不返回任何数据 |
| supply-chain | 0 | 未新增依赖、未改构建链、未改 lockfile |

**为什么是 0 而不是漏查**：本次 diff 全文即该 SQL 文件（`git show --stat HEAD` 确认仅此一个文件、18 行新增），上述九个关注面在其中没有可检查的代码载体；不是"没看"，而是"看了且无适用攻击面"。

## 3. 建议修复顺序

security 无待修项。若要继续推进本次改动带来的风险，建议顺序如下（均为跨专家，不属于 security）：

1. **先补 phase 一致性（correctness，优先）**：`V57` 只重置 `trust_score`，未重算 `phase`。这是"迁移后立刻存在的错误状态"，用户可见（关系进度、台词口径），且不修就会一直带着不一致快照跑。修复方式二选一：在同一条迁移里按 `RelationshipPhaseResolver` 的阈值同步 `phase`，或让读取侧（`RelationshipStateService.java:485`）从分数派生而不是直读库列。
2. **再评估启动期锁表（reliability）**：同一条 SQL 无 `LIMIT` 分批，行数大时在 Flyway 启动阶段长时间持锁。建议加执行前备份说明 + 分批（按主键区间循环）或改为运维手工执行的修复脚本。
3. **单独开单清理遗留口令（security，范围外）**：见第 5 节第 3 条。

同文件合并建议：第 1、2 条都落在同一个迁移文件上，可以由同一张单承载（先解决 phase，再决定是否分批），避免两次改同一个 V57（已发布的 Flyway 迁移被修改会导致 checksum 变化，需配合 `repair`）。

## 4. 覆盖缺口 / 失败专家

- 无失败、无无效 handoff，`coverage.complete = true`。
- **未覆盖**：correctness、maintainability、reliability、perf 本次未运行。因此本报告**不能**被读作"这个迁移整体没问题"——第 3 节列出的两条跨专家风险正是这些未跑模块的职责范围，本次只是通过 security 专家转交，未做深度验证。
- 单模块运行：按规则**未更新** `docs/logistics/latest.md` 与 `.logistics/latest.json`。

## 5. 跨专家 deferred（不因不在本次模块内而省略）

| # | 来源 → 目标 | 文件:行 | 摘要 |
|---|---|---|---|
| 1 | security → correctness | `backend/lianyu-dao/src/main/resources/db/migration/V57__relationship_trust_anomaly_repair.sql:15-18` | 只改 `trust_score` 未重算 `phase`；`RelationshipStateService.java:485` 的 `toSnapshot()` 用 `.phase(parsePhase(state.getPhase()))` 直读库列，而 `phase` 只在 `RelationshipStateService.java:223` / `:256` 由 `RelationshipPhaseResolver.derive(...)` 随事件写入 → 迁移后出现 `trust_score=50` 但 `phase` 仍为 `STABLE` 的不一致快照。 |
| 2 | security → reliability | `backend/lianyu-dao/src/main/resources/db/migration/V57__relationship_trust_anomaly_repair.sql:15-18` | 无 `LIMIT` 分批的全表 `UPDATE`，在应用启动期由 Flyway 执行；`relationship_state` 行数大时长时间持锁、拖慢启动，迁移注释缺执行前备份/回滚说明。 |
| 3 | security → security（范围外，另开单） | `backend/scripts/repair-flyway-v10.sql:2`、`backend/scripts/repair-flyway-v14-checksum.sql:8` | 用法注释里写着 `mysql -uroot -p<明文口令>`（口令片段已随初始提交 `97865cd` 入库），违反 `AGENTS.md` §3.3「密钥只来自 `.env`/vault，不写进仓库」。本次是**既有遗留**，不在 diff 内，故未计入 findings；建议清理注释、轮换口令，并检查该口令是否复用于其他环境。 |

---

*本报告只陈述问题与修复方案，不含健康分、评分、基线或门禁结论。*
