# 正确性审查报告 · 予念（YuNian）PC 端

**一句话结论**：全项目 12 个审查单元跑完了正确性维度，共确认 10 个缺陷（1 高 / 5 中 / 4 低），其中唯一的高危项是管理端发布解析接口对「注定为空」的产物列调用 `Map.of`，任何已发布版本都会 500；其余集中在记忆向量投影的认领与死信状态机、消息 seq 的 Redis/MySQL 双源、关系状态的读改写并发，以及图片解码阶段的资源上限失效。

## 1. 范围与本次口径

| 项 | 值 |
|---|---|
| 工作区 | `C:/Users/hp/Desktop/LianYu-PC` |
| Git | 分支 `main`，HEAD `c652e14`（fix(relationship): 修正被刷高的历史信任分（V57）） |
| 范围类型 | **全项目**（不是 diff、不是最近一次提交） |
| 规模 | 1100 个文件 / 135978 行 / 12 个审查单元 |
| 语言构成 | Java 450 · JavaScript 252 · Python 220 · SQL 60 · Vue 57 |
| 本次专家 | correctness（1 位） |
| 未运行 | perf（可选专家，本次未选）、security / reliability / maintainability（本次未选） |

审查者只读代码，没有修改源码、没有提交。所有结论都能在给出的 `file:line` 上复现。

## 2. 单元 × 专家覆盖矩阵

| 单元 | 内容 | correctness | 读过文件数 | 说明 |
|---|---|---|---|---|
| u01 | backend/lianyu-service (1/3) | reviewed | 5 | relationship 子系统逐行读（状态机/衰减/阶段推导） |
| u02 | backend/lianyu-service (2/3) | reviewed | 6 | 会话、群聊、语音、摘要、切分器 |
| u03 | frontend/src (1/2) | sampled | 3 | 按模式扫描后抽查 httpCore / assistantStreamDrain / 共用 sseParser；页面级 .vue 未逐个读 |
| u04 | scripts (1/2) | sampled | 2 | 资产命名与契约脚本核对；一次性 `_cloud_*` 脚本按用途抽样 |
| u05 | backend/lianyu-dao | reviewed | 8 | 迁移逐条对照实体字段；确认实体无 `@Version` |
| u06 | backend-agent | reviewed | 6 | memory writer/relay、write 白名单、relay 循环、stream_chat |
| u07 | frontend/src (2/2) | sampled | 2 | 关系展示分档与流消费方；大体积页面未逐行读 |
| u08 | backend/lianyu-web | reviewed | 3 | AgentStreamBridge / AgentSseParser / agent 包清单 |
| u09 | frontend/electron | sampled | 3 | updater、apiProxy、preload 通道清单；main.js 未逐行读 |
| u10 | 其他模块（root/.github/admin 等） | sampled | 3 | docker-compose、AGENTS 口径、发布命名源；admin 静态资源抽样 |
| u11 | backend 小模块合集 | reviewed | 5 | lianyu-admin 发布域为主 |
| u12 | 其他（app/common/storage 等） | reviewed | 7 | lianyu-common 14 个文件清单 + 关键 7 个实现 |

**没有 skipped，没有 missing。** 7 个单元 reviewed、5 个单元 sampled —— sampled 不等于全量已读，具体抽样范围见上表；未读到的部分在 §6 列出。

## 3. 按模块的发现

### 3.1 backend/lianyu-admin（发布域）— 1 高

**COR-A93ED · 高 · 公开更新解析接口对「永远为空」的产物列调用 `Map.of`，任何已发布版本都会 NPE 500**
`backend/lianyu-admin/src/main/java/com/lianyu/admin/release/ReleaseResolverService.java:21`

```java
if (inRollout(...)) return Map.of("version", row.get("version"), "url", row.get("package_url"),
        "sha512", row.get("sha512"), "size", row.get("package_size"),
        "mandatory", row.get("mandatory"), "notes", row.get("notes"));
```

`Map.of` 对 null 值直接抛 `NullPointerException`。子代理的原始证据只到「`notes` 可空」，主编核对时把触发面补全了：全仓 grep `package_url`，只有 `V51__admin_management_schema.sql:84-88` 的 DDL 和这一行 SELECT —— **没有任何 INSERT/UPDATE 写 `package_url` / `sha512` / `package_size`**，`ReleaseUploadService.java:29-33` 的 `createUpload` 只返回 MinIO 预签名 staging 地址，不回写版本行。也就是说 published/rollout 行的 `url`、`sha512`、`size` 恒为 NULL，`notes` 又在 `ReleaseController.java:20` 未传时显式写 null。

可达路径：建版本（不填 notes）→ `transition` ready→published（`ReleaseService.java:41-49` 的 `allowed()` 只校验状态机，完全不校验产物）→ `GET /api/public/admin-release?channel=stable` → 第一条候选记录就 NPE → 500，且 NPE 无 message，日志里只有栈。

影响：这个公开接口对任何已发布版本 100% 失败，管理端发布通道拿不到可解析的版本元数据。仓库内没有调用方（grep `admin-release` 只命中 controller 自己），所以后果是「发布通道整条不可用」，不是线上客户端立刻崩溃。

修复摘要：`Map.of` 换成容忍 null 的 `LinkedHashMap`；`ready→published` 前校验产物三列；补上「上传完成回写 `app_release`」这条缺失的写入路径；补集成测试锁住「无 notes + 无产物」的解析结果。

### 3.2 backend-agent（记忆投影）— 2 中 + 1 低

**COR-9E0EF · 中 · `memory_outbox` 认领没有租约，进程中途退出后行永久停在 RUNNING**
`backend-agent/app/memory/relay.py:41`

`_claim` 只做 `UPDATE memory_outbox SET status='RUNNING' WHERE id=? AND status='PENDING'`，不写 `claimed_at`；`drain_once`（:118-121）只取 `status='PENDING'`；自动回收不存在 —— `main.py:54-65` 的 relay 循环只调 `drain_once`，`requeue_stuck` 只挂在手工端点 `POST /agent/memory/relay/requeue`（`main.py:967-971`）。V54 的 `updated_at ON UPDATE CURRENT_TIMESTAMP` 有值，但没有任何代码按它判超时。

影响：UPSERT 要跑 embedding + Milvus insert，正是滚动更新/OOM/超时最容易打断的一段。已认领行永久 RUNNING，既不会被取到也不会转 DEAD，`memory_meta.milvus_vec_id` 保持 NULL —— 「记住了但永远想不起来」，且无自愈路径。

修复摘要：认领写租约时间戳；drain 条件放宽为「PENDING 到期 ∪ RUNNING 超租约」，回收时先 CAS 回 PENDING 再认领，并计入 attempts 上限。

**COR-98AFD · 中 · `requeue_stuck` 不重置 `attempts`，死信重放下一次失败就回 DEAD**
`backend-agent/app/memory/relay.py:207`

重排只 `SET status='PENDING'` 与 `SET next_attempt_at=NOW()`，不碰 `attempts`/`last_error`；而 `_retry_or_dead`（:163-172）是 `attempts = row.attempts + 1; if attempts >= cap: DEAD`。DEAD 行的 attempts 必然已经 ≥ cap，所以重排后第一次失败立刻又变 DEAD —— 运维修好 Milvus 后调 requeue，一条都修不回来，还刷一轮 error。这条也抵消了上一条的人工兜底。

修复摘要（可直接改）：对 DEAD 行 `SET status='PENDING', attempts=0, last_error=NULL, next_attempt_at=NOW()`；RUNNING 行按「超时回收」单独定口径。

**COR-D7877 · 低 · Python 写库白名单被 writer 的裸 UPDATE 绕过**
`backend-agent/app/memory/writer.py:193`

`app/write.py:44-52` 的 `_ALLOWED_UPDATES` 只放行 `update memory_meta set milvus_vec_id = %s where id = %s` 和三类 `update memory_outbox ...` 前缀，校验函数 `_assert_allowed` 只在 `execute_write` / `insert_returning_id` 里调用；而 `transaction()`（:70-82）把裸 aiomysql 游标直接 yield 出去，`writer.py:193-204` 与 `:211-220` 就用它跑了改 `memory_meta` 内容列的 UPDATE。

影响：数据本身没错（这两条 UPDATE 是业务需要的），但「唯一写库入口 + 表级白名单」这个架构承诺不成立 —— 后续按白名单收窄 DB 账号权限或做静态审查都会与运行行为冲突，新加一条越界 UPDATE 也不会有任何拦截。

修复摘要：二选一钉死口径 —— 把这些 UPDATE 登记进白名单并统一走 `execute_write`，或删掉白名单、在文档里写清「agent 可写 memory_meta / memory_outbox 的指定列」。

### 3.3 backend/lianyu-service（消息序号 / 关系状态 / 摘要）— 3 中 + 2 低

**COR-5B124 · 中 · 消息 seq 只靠 Redis 计数器，无 DB 回填，计数丢失后撞 `uk_conv_seq`**
`backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java:483`

```java
Long lastSeq = redisTemplate.opsForValue().increment(SEQ_KEY_PREFIX + conversationId, safeCount);
return lastSeq != null ? lastSeq : safeCount;
```

全仓 `msg_seq:*` 只有 increment 和 delete（`:226` 删会话、`:243` 清空消息、`CharacterService.java:298` 删角色），没有任何从 `message` 表 `max(seq)` 回填的逻辑；`MessageMapper` 里的 `MAX(seq)` 只用于统计。`V1__init_schema.sql:54` 有 `UNIQUE KEY uk_conv_seq (conversation_id, seq)`。同款实现在 `GroupChatService.java:104`、`VoiceCallService.java:62`，`"msg_seq:"` 前缀共 4 处常量。

影响：seq 的权威在 Redis、持久化在 MySQL，两者无对齐机制。redis 卷重建 / 换实例 / FLUSHDB / 运维清键之后，新消息从 1 开始撞唯一键 → 该会话此后所有发送 500，只有「清空聊天记录」能恢复；群聊与语音通话各复制了一份同样的风险。

修复摘要：取号前用一次条件初始化保证计数器不小于 `max(seq)`（Lua 或 SETNX+INCR），撞键时回填重试一次，并把三份实现收敛成一个 SeqAllocator。

**COR-CB2EE · 中 · 关系状态是无乐观锁的读改写，并发回合丢更新**
`backend/lianyu-service/src/main/java/com/lianyu/service/relationship/RelationshipStateService.java:132`

`recordUserTurn`（:107-134）是 `loadOrCreateState → applyEvents`（循环内逐条 `persistEvent` 写 `relationship_event`）`→ updateById(state)`，方法上没有 `@Transactional`；`recordAssistantTurn`（:153-165）和 `decayAll`（:174-188）同样以 `updateById` 收尾。实体 `RelationshipState.java:12-34` 没有 version 字段，项目也没启用 `OptimisticLockerInnerInterceptor`。

主编更正：子代理写的「整行覆盖」不准确 —— MyBatis-Plus `updateById` 默认只更新非空字段。但快照里所有非空分数与时间戳都会以旧值写回，**丢更新的结论不变**。

影响：同一 (user_id, character_id) 上的并发写（连发两条、用户回合与助手回合交错、衰减任务撞实时回合）会后写者抹掉前者的分数与时间戳；而事件已按各自批次落库 → 「事件日志里两次受伤，状态里 `last_injury_at` 只剩一个」，`RelationshipPhaseResolver` 依赖的分数与事件历史开始分叉，事件与状态也没有事务保证同时成功。

修复摘要：加 `version` 列 + `@Version` 并对读改写做有界重试，或改增量 SQL（`SET trust_score = trust_score + ?`）；同时把写事件与更新状态收进同一事务。

**COR-C74EC · 低 · 衰减轮转队列会被「未满一天」的活跃行长期占住**
`backend/lianyu-service/src/main/java/com/lianyu/service/relationship/RelationshipStateService.java:176`

`decayAll` 按 `last_decay_at` 升序取 `LIMIT 500`（`DECAY_BATCH_LIMIT=500`），`applyDecay` 在 `days < 1` 时直接 `return false` 且不写 `lastDecayAt`；`V56` 只新增列、不回填，MySQL 升序 NULL 排最前。

影响：「结算过的行排到队尾」这个轮转前提只在返回 true 时成立。最近 24 小时活跃过的行每轮都被扫到、返回 false、`lastDecayAt` 仍为 NULL，继续占前排；这类行达到 500 条时整批空转，其余沉默用户当天一分不掉。影响是衰减被推迟（不是永久饿死），所以定 low —— 但它让注释里「按 last_decay_at 轮转修好了 936 行只有 502 行轮到」的修复只对非活跃行生效。

修复摘要：把「扫描推进」与「是否真正衰减」解耦，`days < 1` 时也写 `last_decay_at`（或独立的 check 时间），或在取数时排除最近 24 小时已检查过的行。

**COR-0802C · 低 · 会话摘要的 Redis 读改写没有并发保护，`lastSummarizedSeq` 可能回退**
`backend/lianyu-service/src/main/java/com/lianyu/service/conversation/SessionSummaryService.java:88`

`@Async maybeMergeAsync` → `maybeMerge`：`loadState → loadPendingMessages → merger.merge`（模型调用，秒级）`→ saveState`，全程无锁、无 CAS、无版本比较。

影响：同一会话两个回合并发时，后写者可能拿更旧的 pending 覆盖更靠后的 lastSeq → 回退 → 同一批消息被再次合并（模型调用浪费、摘要重复膨胀），用户可见表现为摘要出现重复段落。需要秒级并发窗口，定 low / 置信度 medium。

修复摘要：落库前比较写（新 lastSeq 不大于现存值就放弃），并用 Redis SETNX 串行化同一 conversationId 的合并。

### 3.4 backend/lianyu-common（上传）— 1 中

**COR-5CA37 · 中 · 图片像素上限在解码之后才校验，8MB 内的大尺寸 PNG 解码即 OOM**
`backend/lianyu-common/src/main/java/com/lianyu/common/util/ImageUploadValidator.java:44`

```java
BufferedImage image = decodeImage(raw);            // ImageIO.read(...)
...
if (width > MAX_DIMENSION || height > MAX_DIMENSION) { throw new BusinessException(...); }
```

`MAX_BYTES=8MB` 只限制字节数，`MAX_DIMENSION=8192` 在解码之后才生效。一个 8MB 以内的 PNG 可以声明 20000×20000（4 亿像素，ARGB 约 1.6GB），`ImageIO.read` 先按声明尺寸分配像素缓冲 → 抛 `OutOfMemoryError` 而不是 `BusinessException`，`GlobalExceptionHandler` 接不住 → 500，并瞬时吃掉共享堆影响同进程其他请求。调用方：`FileStorageService.java:60` 与 `:92`（头像/图片上传走同一条重编码路径）。

修复摘要：解码前从文件头读尺寸（PNG IHDR / JPEG SOFn / GIF LSD / WebP VP8X）并做像素上限校验；解码改用 `ImageReader` + 子采样，杜绝对声明尺寸的全量分配。

### 3.5 frontend/shared（流解析）— 1 低

**COR-CF259 · 低 · SSE 折叠器与整流解析器对「同帧 content+replace」的优先级相反**
`frontend/shared/sseParser.js:93`

`createSseAccumulator.feed` 先 `fullContent += content` 再 `fullContent = replace`（增量被丢弃）；同文件 `parseSseStream`（:66-71）顺序相反；文件头（:17）却写「四字段同帧出现时全部生效」。Java 还有第三种语义：`AgentSseParser.parseJson`（`backend/lianyu-web/.../AgentSseParser.java:79-93`）每帧只取一个字段，优先级 content > replace > error。

主编补充（限缩了这条的可达性）：当前生产者不触发 —— `stream_chat.py:42-50` 的 `sse_content`/`sse_replace`/`sse_error` 各发单字段，`ConversationService.java:363-372` 的 `beforeStreamComplete` 同帧只发 `replace` + `pieces`（不含 content）。所以没有用户可见错误，定 low / 置信度 medium：风险是这条隐式契约没有测试锁定，一旦某帧同时带 `replace` 与 `content`，渲染端丢增量、桥接端把增量拼在整段替换之前，两条消费路径给出不同文本。

修复摘要：抽出单一 `applyEvent(state, applied)` 供两个消费端共用，把优先级写进文件头，并用一条单测同时锁两种帧形态。

## 4. 跨单元的系统性问题

1. **「状态机只校验状态，不校验前置事实」**：`ReleaseService.allowed()` 允许无产物的版本直达 published（3.1），`SessionSummaryService` 允许无锁的读改写（3.3 低）、`memory_outbox` 允许认领后无限期持有（3.2）—— 三处都把「当前状态合法」当成了「前置条件满足」。
2. **同一事实有两个来源且没有对齐机制**：消息 seq 的权威在 Redis、落库在 MySQL（COR-5B124）；关系分数在 `relationship_state`、事实在 `relationship_event`（COR-CB2EE）；两条都在「第二个来源丢数据/被并发写」时才暴露。
3. **防护写在错误的阶段**：像素上限写在解码之后（COR-5CA37）、白名单写在 `execute_write` 而不是游标上（COR-D7877）、租约写在文档里而不是表上（COR-9E0EF）—— 都是「检查点不在真正分配资源/写入的位置」。
4. **同一份逻辑复制多份**：`msg_seq:` 有 4 处常量 3 份实现；SSE 折叠有 3 套优先级（COR-CF259）。修一处不修另一处就会重新长回来。

## 5. 建议修复顺序

**第一批（先做，成本低、收益明确）**
1. COR-A93ED：`Map.of` → 容忍 null + `ready→published` 校验产物（同一提交内做，否则只修 NPE 会掩盖「url 永远为空」）。
2. COR-98AFD：requeue 重置 attempts / last_error（单条 SQL，标了 `direct`）。
3. COR-9E0EF：认领加租约 + drain 回收超租约行（与上一条同文件，合并成一个提交）。

**第二批（数据面）**
4. COR-5B124：seq 分配器收敛 + DB 回填 + 撞键重试（三处调用点一起改）。
5. COR-CB2EE：乐观锁或增量 UPDATE + 事务边界。
6. COR-0802C：摘要合并的比较写 + 会话级锁（与 5 同属并发写，可同一批次排期）。

**第三批（边界与契约）**
7. COR-5CA37：解码前置尺寸校验（安全侧还会补限额策略，见 §7）。
8. COR-D7877：写库白名单口径二选一（改代码或改文档，别停在两者之间）。
9. COR-CF259 / COR-C74EC：解析器优先级统一 + 衰减队列推进；两者都建议配一条能失败的测试再修。

同文件合并建议：`relay.py`（9E0EF + 98AFD 一次改完）、`RelationshipStateService.java`（CB2EE + C74EC）、`sseParser.js` 单文件。

## 6. 覆盖缺口与未验证项

- **sampled 的 5 个单元不是全量已读**：u03/u07（frontend/src 页面级 `.vue` 未逐个读，只覆盖数据通道与工具层）、u04（`scripts` 一次性脚本按用途抽样）、u09（`electron/main.js` 未逐行读）、u10（`.github`、admin 静态资源仅抽样）。这些单元里的问题可能没被看见。
- **未验证的怀疑**（无法从静态代码断定，没有计入 findings）：`docker-compose.yml` 的 Redis 持久化配置在真实部署下是否足以避免 COR-5B124 的丢键场景；线上 `relationship_state` 实际行数是否会让 COR-C74EC 的 500 条批次真的被活跃行占满。
- **本次只跑 correctness**：security / reliability / maintainability 三个维度没有跑，不代表这些维度干净。已把 4 条跨维度线索放进 deferred（§7）。
- **性能维度未运行**：需要时执行 `/logi-perf`。不要把「没检查」当成「没问题」。

## 7. 跨专家 deferred（转交项）

| 来源 | 转交 | 文件 | 原因 |
|---|---|---|---|
| correctness | security | `backend/lianyu-common/.../ImageUploadValidator.java:28-60` | 像素上限的「限额取多大、是否要账号级配额/解压炸弹防护」是安全策略，correctness 只报了解码顺序（COR-5CA37） |
| correctness | reliability | `backend/lianyu-web/.../AgentStreamBridge.java:97-141` | finish / fail / abort 三种收尾都汇入 `callback.onComplete(content, error)`，上游只能靠 error 是否为 null 区分三态；是否可观测属可靠性 |
| correctness | maintainability | `backend/lianyu-dao/.../V55__agent_capability_audit.sql:15` | 能力层删除后该表全仓无读写引用（只有 CREATE TABLE），疑似死表 |
| correctness | maintainability | `backend/lianyu-service/.../SessionSummaryService.java:74,41` | `loadState` 从不返回 null，两处 `if (state == null)` 是死分支 |

## 8. 主编对子代理结论的修正

1. **COR-A93ED 升级了证据强度**：从「没填 notes 会 NPE」补到「`package_url`/`sha512`/`package_size` 全仓没有写入路径，任何 published 行必 NPE」，并据此把 `kind` 从 `direct` 改为 `plan`（只把 `Map.of` 换成 `LinkedHashMap` 会让接口返回空 url，把真问题藏起来）。
2. **COR-CB2EE 修正机制描述**：MyBatis-Plus `updateById` 只更新非空字段，不是严格整行覆盖；丢更新的结论成立，措辞已改。
3. **COR-CF259 限缩可达性**：确认当前生产者不会在同一帧发 `content` + `replace`，因此没有用户可见错误，保持 low 并在报告里写明触发条件。
4. 其余 7 条（COR-9E0EF / 98AFD / 5B124 / C74EC / D7877 / 5CA37 / 0802C）证据逐条复核通过，未改动结论。

> 本报告不含健康分、评分或门禁结论。单模块、非默认专家组合，未更新 latest。
