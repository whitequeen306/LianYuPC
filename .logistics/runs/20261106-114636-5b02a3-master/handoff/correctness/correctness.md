# 正确性审查报告

任务：DSH Logistics 全项目正确性（correctness）审查，只读源码，不改动、不提交。
审查对象：`C:/Users/hp/Desktop/LianYu-PC`，分支 `main`，HEAD `c652e14`（工作区有未提交改动）。
运行目录：`.logistics/runs/20261106-114636-5b02a3-master`。

## 1. 范围与覆盖

- 1108 个文件 / 12 个审查单元 / 139994 行；全部按 `scope.json` 的单元清单逐单元过了一遍。
- 方法：核心文件逐行精读；单元内其余文件先用索引脚本通读（抽取类名、方法名、`@Transactional`/`@Scheduled`/`@Version` 等标记），再用 `rg`（本机无 `rg`，用等价 Rust 正则扫描）做跨文件模式核对。
- 4 条门禁命令全部实际执行并通过（见第 4 节）。

| 单元 | 范围 | 状态 | 读过文件 | 说明 |
|---|---|---|---|---|
| u01 | backend/lianyu-service (1/3) | reviewed | 36 | agent 端口与结果对象、ConversationService、AssistantReplySplitter 精读 |
| u02 | backend/lianyu-service (2/3) | reviewed | 36 | dto/graph/memory/relationship：MemoryWriter、记忆抽取、关系状态机精读 |
| u03 | frontend/src (1/2) | sampled | 36 | api 层（httpCore/auth/conversation/electronAdapter）全读，其余抽样 |
| u04 | scripts (1/2) | sampled | 36 | 运维脚本按 deploy/upload/diag 分组扫描，精读上传与保留策略 |
| u05 | backend/lianyu-dao | reviewed | 36 | 57 个 Flyway 迁移逐个核对版本与 DDL 语义 |
| u06 | frontend/src (2/2) | sampled | 36 | ChatPage/QuickChatPage 精读，其余页面按 store/router/i18n 索引扫描 |
| u07 | backend-agent | reviewed | 36 | gateway/stream_chat/tool_loop/relay/main 精读，并做复现实验 |
| u08 | backend/lianyu-web | sampled | 36 | AgentStreamBridge/SSE 解析/WebSocket 鉴权/Controller 精读 |
| u09 | frontend/electron | sampled | 36 | updater 解析与版本比较精读，napcat/qqBridge 抽样 |
| u10 | 其他模块（root/.github/admin） | sampled | 36 | 体量小，全量索引；security/ai/storage 按类职责核对 |
| u11 | backend 小模块合集 | sampled | 36 | admin 支撑/发布授权 + 11 个 service 测试的断言意图 |
| u12 | 其他模块（app/common/installer） | sampled | 36 | 公共工具类按类核对，`shared/sseParser.js` 与其真实测试对读 |

结论：没有 skipped 单元，整体状态 `ok`；`sampled` 的单元只做了抽样与模式扫描，不能当作全量逐行已读（见第 5 节）。

## 2. 发现（按严重度）

- [high] [u07] [direct] C-001 — 流式回合收到 `[DONE]` 时提前 return，模型要求调用的工具被整段丢弃，工具永不执行。
  详细问题：流式回合收到 [DONE] 时提前 return，聚合好的 tool_calls 被丢弃，工具永不执行
  位置：backend-agent/app/gateway.py:313 · 证据：`if data == "[DONE]": return` 直接退出 `_stream_once`，而把 `pending` 写入 `tool_sink` 的收尾代码在 `async with` 之后（gateway.py:348-352）；用 httpx MockTransport 复现，流以 `data: [DONE]` 结尾时 `tool_sink == []`，去掉 `[DONE]` 后同一个流能拿到 `get_time` 调用；`stream_chat.py:109` 因此不执行工具，若该轮已有文本，`stream_chat.py:145` 的兜底也不会触发。
  影响：查时间/天气/记忆检索等工具在流式通道上全部失效，角色只“说要做”却没有动作，且无错误提示、无日志 · 修复：把该分支的 `return` 改为 `break`，让循环正常结束后执行 348-352 行的 tool_sink 落盘；补一条以 `[DONE]` 结尾的 MockTransport 用例断言 sink 非空。

- [high] [u06] [plan] C-007 — 发送失败后客户端原样重发同一回合，服务端无幂等键，同一句话落两条、角色回两遍。
  详细问题：网络失败后原样重发同一回合，用户消息与角色回复各落两份
  位置：frontend/src/pages/ChatPage.vue:1439 · 证据：`isNetworkError(err)` 命中后 `await sleep(800)` 再 `attemptStream()`，而它就是再 POST `/conversation/{id}/messages/stream`；`isNetworkError`（frontend/src/composables/useStreamAbort.js:8-15）把任何 `TypeError`/`failed to fetch`/`load failed` 都算网络错误，包含请求已达服务端、响应阶段断连的情况；请求体只有 provider/model/content/imageUrl，无客户端消息 ID，服务端每次调用都新插一条用户消息并重新生成。
  影响：一次网络抖动就产生重复气泡、双倍模型计费，群聊/连续会话的 seq 与气泡顺序错乱 · 修复：请求体加 `clientMessageId` 并在服务端按 (userId, conversationId, clientMessageId) 幂等去重，或把自动重试限制在“确实未送达”的场景。

- [high] [u02] [plan] C-002 — 同一条用户消息命中的多条记忆共用 sourceHash，后写的把先写的覆盖掉，只有最后一条留下。
  详细问题：同一条用户消息命中的多条记忆共用 sourceHash，后写的覆盖先写的
  位置：backend/lianyu-service/src/main/java/com/lianyu/service/memory/MemoryWriter.java:199 · 证据：`resolveSourceHash`→`computeSourceIdsHash`（:266-281）只对 source_msg_ids 求 sha256，而 `processSummary` 每次传 `List.of(memory.sourceMsgId())`（:105-108）；`MemoryExtractionHeuristics.extractAll` 对**同一条消息**可产出多条（专属称呼 :101、脆弱情绪 :105、仪式感 :108、昵称 :111、画像事实 :57-77），它们的 hash 完全相同；第二条命中 `findExistingMemory`(:200) 后走 UPDATED 分支（:238-252）覆盖 summary 并清空 vec_id。
  影响：用户一句话里的多个事实只记住最后一个（“我刚说过它又忘了”），每次覆盖还触发一次向量删除+重建，检索出现空洞、嵌入成本翻倍 · 修复：把记忆类型与归一化摘要纳入哈希（如 sha256(ids|type|summary)），配一条 Flyway 回填 + 清理迁移，并补“一条消息命中三类记忆”的用例。

- [medium] [u02] [plan] C-003 — 关系状态整行读改写且没有版本控制，30 分钟一次的衰减任务会覆盖用户互动带来的增量。
  详细问题：关系状态整行读改写且无版本控制，衰减任务会覆盖互动增量
  位置：backend/lianyu-service/src/main/java/com/lianyu/service/relationship/RelationshipStateService.java:132 · 证据：`recordUserTurn`（:116-133）、`recordAssistantTurn`(:163-165)、`decayAll`(:183-187) 都是 `loadOrCreateState` → 内存累加 → `updateById(state)` 整行回写；`RelationshipState` 实体没有 `@Version`，表里也没有版本列；`RelationshipDecayScheduler`(:25-37) 每 30 分钟跑一次，与请求线程天然并发。
  影响：trust/intimacy 增量被静默吞掉（用户感知“关系不推进”），`lastInteractionAt` 回退让 `isNewPresenceDay` 重复判定新的一天，连续天数与里程碑偏移 · 修复：加 version 列做条件更新（冲突重算），并让衰减只更新衰减相关列，不回写 lastInteractionAt/streak。

- [medium] [u05] [plan] C-004 — V34 去重删掉重复会话，但那些会话里的消息既不迁移也不清理，历史记录从界面消失。
  详细问题：V34 去重删掉重复会话，但会话下的消息既不迁移也不清理
  位置：backend/lianyu-dao/src/main/resources/db/migration/V34__conversation_single_unique.sql:2 · 证据：迁移开头 `DELETE c1 FROM conversation c1 INNER JOIN conversation c2 ... c1.id > c2.id` 只删会话行，`message` 表（V1__init.sql:45-56）只有普通列 `conversation_id` 与 `uk_conv_seq`，无外键，消息既没被级联删除也没改挂到保留会话；V35–V57 没有任何迁移触碰 message（全量 grep 无命中）。
  影响：受影响用户的历史聊天永久读不到（数据还在库里），message 表留下永久孤儿行，按会话统计/清理/备份失真 · 修复：补一条修复迁移，把孤儿消息改挂到保留会话并做 seq 偏移，无法改挂的先归档再删，末尾断言孤儿行为 0。

- [medium] [u07] [plan] C-006 — memory_outbox 认领后进程被杀会永久停在 RUNNING，没有人回收，那条记忆再也搜不到或删不掉。
  详细问题：memory_outbox 认领后进程崩溃会永久停在 RUNNING，无人回收
  位置：backend-agent/app/memory/relay.py:41 · 证据：认领是 `UPDATE ... SET status='RUNNING' WHERE id=? AND status='PENDING'`（:41-48），`drain_once` 只捞 PENDING（:111-119）；`requeue_stuck()`（:207-225）docstring 写明“运维用”，唯一调用点是 main.py:966-972 的手工接口，常驻 `_relay_loop`（main.py:52-65）只调 `drain_once`，`reconcile` 同样只由运维接口触发。
  影响：滚动重启/OOM 后该行永久 RUNNING——UPSERT 场景记忆没有向量、检索不到；DELETE 场景向量残留，用户删掉的记忆仍会被角色说出来，且无告警 · 修复：给认领加租约（lease_expires_at），在常驻循环里定期回收超时 RUNNING 行，并对回收数量打点。

- [low] [u02] [direct] C-005 — 新建记忆被重复写入 outbox，每条触发两次向量重建，中间有向量缺失窗口。
  详细问题：新建记忆被重复写入 outbox，每条记忆触发两次向量重建
  位置：backend/lianyu-service/src/main/java/com/lianyu/service/memory/MemoryWriter.java:120 · 证据：`upsertTypedMemory` 在 CREATED 分支已 `enqueueUpsert`（:223），`processSummary` 又对 `saved.getMilvusVecId()` 为空的行再入队一次（:117-126），而新建行 vec_id 必然为空；relay 的 `project_upsert`（relay.py:86-110）先删旧向量再插入，所以第二条会重复嵌入并删除重建。
  影响：嵌入与 Milvus 写入翻倍，两次 UPSERT 之间刚写入的记忆检索不到，第二条失败时 vec_id 指向已删除的向量 · 修复：删掉 `processSummary` 里的重复入队，或改为先查 outbox 是否已有该 memory_id 的待处理行再入队。

### 已查未发现问题的区域

- contracts（0 条）：SSE 帧契约在 Java `AgentStreamBridge`、Python `stream_chat.py`、前端 `frontend/shared/sseParser.js` 三处一致（content/replace/pieces/error/[DONE]/心跳）；工具名正则 `^[A-Za-z0-9_-]{1,64}$` 在注册表与路由两侧一致；DTO 校验长度均未超过列宽（如 `UpdateGroupTitleRequest` `@Size(64)` vs `VARCHAR(256)`）。
- architecture（0 条）：Java 侧没有 prompt 文案与模型调用（`AiController`/`ChatTurnFacade` 只取事实并转交 Python），Python 不直接写 MySQL（记忆变更统一经 `memory_outbox` 由 relay 投影），Electron 不调模型；`AssistantReplySplitter` 与前端 `assistantReplySplit.js` 的分段规则目前逐条一致。
- edge-cases（0 条）：分页 size/page 有上下界钳制（`MemoryQueryService` 1..200、`getMessages` 1..200），SemVer/文件名/SHA-512 校验完整，57 个迁移版本号无重复。
- state（除 C-006 外）：群聊回合 turnId 换代能让旧回合停止吐泡，`saveAssistantRepliesLimited` 的 seq 块保留与回滚一致。
- concurrency（除 C-003 外）：群聊 `anyOf().join()` 的异常路径被逐成员 catch 兜住，支持授权码兑换用的是条件 UPDATE（`redeemed_at IS NULL AND revoked_at IS NULL AND expires_at > NOW()`），不会双兑。

## 3. 未验证 / 抽样说明

- u03、u04、u06、u08、u09、u10、u11、u12 是 `sampled`：单元内全部文件都被索引脚本通读过（类/方法/注解层面），但只有核心文件与个别可疑点是逐行读的，页面级 UI 交互、Electron 桥接分支、运维脚本的失败路径没有逐条推演。
- C-007 的重复落库是按代码路径判定的，没有在真实网络抖动下打点验证触发频率（confidence 记为 medium）。
- C-006 的影响面取决于部署时进程被杀的概率，未统计线上 RUNNING 残留行数（confidence 记为 medium）。
- 未报告但已记录的观察：V10–V12 迁移里带中文字面量，违反 AGENTS.md 的 ASCII-only 约定，但 JDBC 连接串已设 `useUnicode=true&characterEncoding=UTF-8`，运行时风险低，只在此备注。

## 4. 测试门禁

| 命令 | 结果 | 备注 |
|---|---|---|
| `npm --prefix admin test` | passed | vitest run 退出码 0 |
| `mvn -q -f backend/pom.xml test` | passed | 后端各模块 BUILD SUCCESS；输出里的 WARN/ERROR 来自负路径断言用例 |
| `pytest -q backend-agent` | passed | 退出码 0 |
| `npm --prefix frontend test` | passed | vitest run 退出码 0 |

门禁全绿不等于覆盖充分：C-001 与 C-007 都落在现有测试没覆盖的路径上（前者用假 gateway 直接往 `tool_sink` 塞数据，后者没有任何重试用例）。

## 5. 建议修复顺序

1. 先修 C-001（一行改动 + 一条用例），当天可完成，直接恢复所有流式工具能力。
2. 再修 C-002（记忆去重键 + 回填迁移），这是用户可感知的数据丢失；与 C-005 一起改，顺手去掉重复入队。
3. C-007 需要前后端一起改（幂等键），排在同一次迭代；改动前先把自动重试降级为“提示用户重试”也能止血。
4. C-003、C-006 属稳定性欠账，建议合并成一次“状态一致性”小迭代（版本列 + 租约回收）。
5. C-004 是对历史数据的修复，需要先确认线上 V34 是否已执行、影响用户数多少，再单独发一次数据迁移。

## 6. 跨专家转交项

- 安全（security）：`SupportGrantService.issue` 与 `redeem` 共用同一个权限 `support.conversation.read`，即出码人自己就能核销，双人复核的设计意图落空——属授权设计问题，交安全专家判断。
- 可靠性（reliability）：C-006 同时是“失败不可见”问题（卡住无告警、无指标），修复时可一并补监控；另 `relay.requeue_stuck()`、`reconcile()` 两个运维接口没有鉴权前提说明，交可靠性/安全专家确认暴露面。
- 性能（perf）：`GroupChatService` 在生成循环里 `Thread.sleep(bubbleGapMs)` 占住执行线程，以及记忆重复投影带来的额外延迟，交性能专家评估是否值得优化。
