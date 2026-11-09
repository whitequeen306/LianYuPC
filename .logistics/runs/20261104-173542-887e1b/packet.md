# 后勤主编材料 · 20261104-173542-887e1b

> 这是给主会话模型（主编）的候选材料，不是最终报告。
> 去重、定级、取舍、模块组织由主编决定；机器只负责收集与校验。

## 运行
- 根目录：`C:/Users/hp/Desktop/LianYu-PC`
- 范围：1100 个文件 / 12 个审查单元 / 135978 行
- 测试命令：（未检测到）

## 覆盖
- 预期单元 / 专家：correctness
- 已收到：correctness
- 缺失：（无）
- 无效：（无）
- 失败：（无）
- partial：（无）
- 可选未选：perf

## 单元覆盖矩阵

| 单元 | 标签 | correctness | perf |
| --- | --- | --- | --- |
| u01 | backend/lianyu-service (1/3) | reviewed | missing |
| u02 | backend/lianyu-service (2/3) | reviewed | missing |
| u03 | frontend/src (1/2) | sampled | missing |
| u04 | scripts (1/2) | sampled | missing |
| u05 | backend/lianyu-dao | reviewed | missing |
| u06 | backend-agent | reviewed | missing |
| u07 | frontend/src (2/2) | sampled | missing |
| u08 | backend/lianyu-web | reviewed | missing |
| u09 | frontend/electron | sampled | missing |
| u10 | 其他模块（(root)、.github、admin 等） | sampled | missing |
| u11 | backend（小模块合集 59 文件） | reviewed | missing |
| u12 | 其他模块（backend/lianyu-app、backend/lianyu-common、backend 等） | reviewed | missing |

## 专家摘要
### correctness · ok
- 摘要：全项目 12 个审查单元逐一检查（7 个 reviewed、5 个 sampled，无 skipped），聚焦逻辑、状态、并发、数据完整性、接口契约、架构边界与边界条件。共 10 条发现：1 条 high（公开更新解析接口对可空列调用 Map.of，必现 500）、6 条 medium（记忆 outbox 认领无租约、消息序号只存 Redis、关系状态整行读改写无乐观锁、死信重排不重置 attempts、图片尺寸校验晚于解码、衰减队列被活跃行阻塞）、3 条 low（SSE 折叠器同帧字段顺序、Python 写库白名单被绕过、会话摘要无锁读改写）。另核查并确认为正确、未报告的点：SSE 帧契约（content/replace/pieces/error/[DONE]/keep-alive）、V57 trust 修正谓词、客户端 compareVersions/isValidInstallerVersion、OutboundUrlValidator 的 SSRF 阻断、PcmFrameAssembler 的样本对齐、SentenceSplitter 的括号/标记切分、Message 分页游标、UserInputSanitizer 的 XML 包裹与策略匹配、OutputLanguage 归一。
- sections：logic=1 · state=1 · concurrency=1 · data-integrity=3 · contracts=2 · architecture=1 · edge-cases=1
- notes：覆盖说明：12 个单元全部检查（7 reviewed / 5 sampled / 0 skipped），无漏单元。已按 7 个 section 逐一检查并在 sections 给出条数，计数与 findings 逐条一致。已检查且判定为干净的方面：（1）SSE 帧契约 content/replace/pieces/error/[DONE]/: keep-alive 在 Python stream_chat、Java AgentSseParser/AgentStreamBridge、前端解析器三处一致，[DONE] 由 Java 侧自行产出、Python 的 [DONE] 不转发是刻意设计；（2）V57 一次性修正谓词 trust>=90 AND intimacy<=40 与注释「60~80 保留」一致；（3）客户端 updater 的 compareVersions/isValidInstallerVersion 与 latest.yml 解析正确，check 与 probe 共用 fetchLatestInfo；（4）OutboundUrlValidator 的 scheme/host/DNS 三重校验与固定 IP 语义无绕过（ollama 放行仍需 host 为 localhost/127.0.0.1）；（5）PcmFrameAssembler 的样本对齐与 flush 丢半字节、SentenceSplitter 的括号内不切/marker 归一/join 抑制逻辑自洽；（6）ConversationService.getMessages 的 beforeSeq 游标（多取一条、reverse 后取首条作 nextBeforeSeq）正确；（7）UserInputSanitizer 的 XML 包裹只转义 & < > 对纯文本节点足够，策略正则命中即抛为产品策略不是缺陷；（8）OutputLanguage.fromCode 的四语归一正确；（9）衰减/阶段阈值在 Java 侧为单一事实来源，前端 75/50/25 是展示分档而非阶段阈值，未判定为漂移。工具环境：本机无 rg，跨文件扫描改用 ops_grep；ops_read_files 显示反斜杠时会加倍，转义相关结论均用 python repr 复核（RelationshipHeuristics 的 \s+、stream_chat 的 [DONE]、_upload_update_assets 的资产正则均已排除假阳性）。
- 发现数：10

### perf · not-selected
- 可选专家，本次未选择（不是缺失）。

## 候选 findings
### COR-A93ED · [高] 公开更新解析接口对可空列调用 Map.of，未填 notes 的版本必现 NPE 500
- 专家 / section：correctness / contracts
- 位置：`backend/lianyu-admin/src/main/java/com/lianyu/admin/release/ReleaseResolverService.java:21`
- 置信度：high · kind：direct
- 证据：if (inRollout(((Number) row.get("id")).longValue(), subject)) return Map.of("version", row.get("version"), "url", row.get("package_url"), "sha512", row.get("sha512"), "size", row.get("package_size"), "mandatory", row.get("mandatory"), "notes", row.get("notes"));
- 影响：java.util.Map.of 不允许 null 值，而 app_release 的 package_url/sha512/package_size/notes 全部可空（V51__admin_management_schema.sql:85-90），ReleaseController.create 在 body 未带 notes 时明确传 null。更关键的是 ReleaseService.transition 的 allowed() 允许 draft→uploading→validating→ready→published 全程不校验是否已上传产物，因此「建版本、不填 notes、直接流转到 published」是可达路径。此时 GET /api/public/admin-release 返回 500，客户端与管理端都拿不到渠道内版本，且异常信息里没有可读原因（NPE 无 message）。
- 修复摘要：把 Map.of 换成可容纳 null 的 LinkedHashMap，并对 null 值做显式归一（字符串字段空串或 null 语义二选一，但不要抛异常）；同时在 transition 里禁止无 package_url/sha512 的版本进入 published。
- 修复文件：backend/lianyu-admin/src/main/java/com/lianyu/admin/release/ReleaseResolverService.java、backend/lianyu-admin/src/main/java/com/lianyu/admin/release/ReleaseService.java、backend/lianyu-dao/src/main/resources/db/migration/V51__admin_management_schema.sql
- 建议测试：新增 ReleaseResolverServiceTest：插入 notes=NULL 的 published 行，断言 resolve 返回 version 且 notes 为空串、不抛 NPE。；新增 ReleaseServiceTest：draft→...→ready 后未上传产物直接 transition(published) 应被拒绝。

### COR-9E0EF · [中] memory_outbox 认领没有租约，进程中途退出后行永久停在 RUNNING、记忆永不投影
- 专家 / section：correctness / data-integrity
- 位置：`backend-agent/app/memory/relay.py:41`
- 置信度：high · kind：plan
- 证据：_claim: "UPDATE memory_outbox SET status = %s WHERE id = %s AND status = %s"（置 RUNNING）；drain_once 取数: "WHERE status = %s AND next_attempt_at <= NOW() ORDER BY id ASC LIMIT %s"（只取 PENDING）；回收只有运维端点 main.py:969-971 requeue_stuck，而 _relay_loop（main.py:54-65）每 5s 只调 drain_once。
- 影响：UPSERT 过程中要跑 embedding + Milvus insert，是整条链路里最容易被重启/超时打断的一段。容器滚动更新或 OOM 杀掉进程后，已认领的行永远停在 RUNNING：不会被 drain_once 取到（只查 PENDING），也不会被判 DEAD，对应 memory_meta.milvus_vec_id 保持为空。用户表现为「这段对话记住了但永远想不起来」，且没有自愈路径——只有人工调用 /agent/memory/requeue-stuck 才会恢复。
- 修复摘要：把认领改成带租约的原子更新：置 RUNNING 的同时写 claimed_at（或复用 updated_at），drain 的取数条件放宽为 PENDING 到期 ∪ RUNNING 超租约，并按尝试次数计入重试。
- 修复文件：backend-agent/app/memory/relay.py、backend-agent/app/config.py、backend/lianyu-dao/src/main/resources/db/migration
- 建议测试：pytest: test_relay_reclaims_expired_running_row；pytest: test_relay_does_not_reclaim_fresh_running_row

### COR-98AFD · [中] requeue_stuck 不重置 attempts，被重排的死信在下一次失败时立刻回到 DEAD
- 专家 / section：correctness / logic
- 位置：`backend-agent/app/memory/relay.py:207`
- 置信度：high · kind：direct
- 证据：requeue_stuck: "SELECT id FROM memory_outbox WHERE status IN (%s, %s) AND updated_at <= DATE_SUB(NOW(), INTERVAL 10 MINUTE)" → 循环内只执行 "UPDATE memory_outbox SET status = %s WHERE id = %s" 与 "UPDATE memory_outbox SET next_attempt_at = NOW() WHERE id = %s"；_retry_or_dead: attempts = int(row.get("attempts") or 0) + 1; if attempts >= cap: await _mark(outbox_id, STATUS_DEAD, ...)。
- 影响：DEAD 行的 attempts 已经 >= relay_max_attempts，重排后第一次处理失败就以 attempts+1 命中 cap 再次置 DEAD，并保留原来的 last_error。也就是说「把死信重新排队」这个动作对 DEAD 行是无效的：运维修好 Milvus 后重放，仍然一条都修不回来，还会在日志里刷一轮 error。
- 修复摘要：重排时把 attempts 归零（并清空 last_error），让 DEAD 行重新拥有完整的重试预算；RUNNING 与 DEAD 两种来源的重排语义分开处理。
- 修复文件：backend-agent/app/memory/relay.py、backend-agent/app/main.py
- 建议测试：pytest: test_requeue_dead_resets_attempts；pytest: test_requeue_keeps_running_attempts

### COR-5B124 · [中] 消息 seq 只靠 Redis 计数器，无 DB 回填，计数丢失后撞 uk_conv_seq 使会话无法继续
- 专家 / section：correctness / data-integrity
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java:483`
- 置信度：high · kind：plan
- 证据：private long reserveSeqBlock(Long conversationId, int count) { Long lastSeq = redisTemplate.opsForValue().increment(SEQ_KEY_PREFIX + conversationId, safeCount); return lastSeq != null ? lastSeq : safeCount; }；全仓只有 delete 该键（ConversationService:226 删除会话、:243 清空消息、CharacterService:298 删角色），没有任何从 message 表 max(seq) 回填的逻辑；message 表有 UNIQUE KEY uk_conv_seq (conversation_id, seq)（V1__init_schema.sql:54）。
- 影响：seq 的权威来源是 Redis，而持久化来源是 MySQL，两者之间没有对齐机制。redis 卷被重建、换库、FLUSHDB，或运维对 msg_seq:* 做过清理之后，新消息从 seq=1 开始，与表里已有的历史消息撞唯一键 → messageMapper.insert 抛 DuplicateKeyException，该会话此后所有发送 500，只有「清空聊天记录」（会连带删消息与键）才能恢复。同一模式在 GroupChatService:1320 与 VoiceCallService:722 各有一份实现。
- 修复摘要：让 seq 以数据库为准：发送前用一次条件 upsert 确保计数器键存在且不小于 max(seq)，或直接改为在 DB 侧用 SELECT ... FOR UPDATE 取号；捕获 DuplicateKeyException 时回填键并重试一次。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java、backend/lianyu-service/src/main/java/com/lianyu/service/conversation/GroupChatService.java、backend/lianyu-service/src/main/java/com/lianyu/service/conversation/VoiceCallService.java
- 建议测试：ConversationSeqTest: redis 键缺失但有历史消息时应取到 max(seq)+1；ConversationSeqTest: 插入撞唯一键时应自动重试一次并成功

### COR-CB2EE · [中] 关系状态整行读改写且无乐观锁，事件表与分数快照会分叉
- 专家 / section：correctness / data-integrity
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/relationship/RelationshipStateService.java:132`
- 置信度：medium · kind：plan
- 证据：recordUserTurn(...) { RelationshipState state = loadOrCreateState(...); ... applyEvents(state, events, now); applyPresence(state, now); relationshipStateMapper.updateById(state); recordMilestones(...); }，applyEvents 在循环内逐条 persistEvent(event)（INSERT relationship_event）后统一 updateById；方法上无 @Transactional，RelationshipState 实体无 @Version 字段（MyBatisPlusConfig 只做 strictInsertFill/strictUpdateFill）。recordAssistantTurn 与衰减任务同样走 updateById(state) 整行覆盖。
- 影响：updateById 会把 state 的全部列（四个分数 + phase + last_injury_at/last_repair_at/last_interaction_at/streak）按内存快照整行覆盖。同一 (user_id, character_id) 上的并发写入——用户连发两条、recordUserTurn 与 recordAssistantTurn 交错、或衰减任务与实时回合撞在同一行——后写者会用旧快照抹掉前者的分数与时间戳；而 relationship_event 已经按各自批次落库，结果是「事件日志里有两处受伤，状态里的 last_injury_at 却只剩一个」，阶段推导也随之漂移。
- 修复摘要：给 relationship_state 引入乐观锁（@Version + version 列），并对 recordUserTurn/recordAssistantTurn/applyDecay 的读改写加重试；同时把「插事件 + 更新状态」放进同一事务，避免事件与快照半写。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/relationship/RelationshipStateService.java、backend/lianyu-dao/src/main/java/com/lianyu/dao/entity/RelationshipState.java、backend/lianyu-dao/src/main/java/com/lianyu/dao/config/MyBatisPlusConfig.java、backend/lianyu-dao/src/main/resources/db/migration
- 建议测试：RelationshipStateConcurrencyTest: 两线程并发 recordUserTurn 后分数与事件数一致；RelationshipStateConcurrencyTest: 过期版本写入应重试而不是静默覆盖

### COR-C74EC · [低] 衰减轮转队列会被「未满一天」的活跃行长期占住，其余行结算被推迟
- 专家 / section：correctness / state
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/relationship/RelationshipStateService.java:176`
- 置信度：medium · kind：plan
- 证据：decayAll: relationshipStateMapper.selectList(new LambdaQueryWrapper<RelationshipState>().isNotNull(RelationshipState::getLastInteractionAt).orderByAsc(RelationshipState::getLastDecayAt).last("LIMIT " + DECAY_BATCH_LIMIT))；applyDecay: long days = ChronoUnit.DAYS.between(anchor, now); if (days < 1) { return false; } —— 这条路径不写 lastDecayAt，行留在队首。V56 只回填 last_interaction_at，不回填 last_decay_at（升级后全表 NULL，MySQL 升序 NULL 排最前）。
- 影响：「结算过的行排到队尾」这个轮转前提只在 applyDecay 返回 true 时成立。最近 24 小时内活跃过的行每隔 30 分钟被重新扫到一次、返回 false、lastDecayAt 仍是 NULL，继续占据 LIMIT 批的前排；如果这类行达到 DECAY_BATCH_LIMIT，整批就是空转，其余沉默用户当天不会掉任何分数。影响是「衰减被推迟最多一天」而不是永久饿死，因此定为 low，但它让「按 last_decay_at 轮转修好了 936 行只有 502 行轮到」的修复只对非活跃行生效。
- 修复摘要：把「扫描推进」与「是否真正衰减」解耦：扫描过但不足一天的行也要写下 last_decay_at（或一个 last_decay_check_at），保证队列一定推进；或在取数时排除最近 24 小时内已检查过的行。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/relationship/RelationshipStateService.java、backend/lianyu-dao/src/main/resources/db/migration
- 建议测试：RelationshipDecayTest: 活跃行与沉默行混合时沉默行仍被结算；RelationshipDecayTest: 全表 last_decay_at 为 NULL 时首轮应结算最老的 500 行

### COR-CF259 · [低] SSE 折叠器同一帧内先累加 content 再用 replace 覆盖，增量丢失且与整流解析器语义相反
- 专家 / section：correctness / contracts
- 位置：`frontend/shared/sseParser.js:93`
- 置信度：medium · kind：plan
- 证据：createSseAccumulator.feed: if (applied.content) { fullContent += applied.content } 之后 if (typeof applied.replace === 'string') { fullContent = applied.replace }；同一文件的 parseSseStream 顺序相反：if (typeof applied.replace === 'string') out.replace = applied.replace; if (typeof applied.content === 'string') out.content += applied.content;；文件头注释声明「pieces/replace/content/error 四字段同帧出现时全部生效（与 Java 写入顺序一致）」。
- 影响：注释承诺四字段同帧生效，但两个消费端对同帧的解释相反：渲染端（createSseAccumulator）会丢掉该帧的 content 增量，主进程整流端（parseSseStream → qqBridge/wechatBridge）会把 content 拼到 replace 之前，再自行决定谁优先。一旦 Java 侧某次改动把 replace 与 content 写进同一帧（例如语言门重写后紧接一个 token），渲染端气泡会比真实回复少一段，桥接端则可能整段重复。当前线上帧是分开写的，所以定为 low，但这是无测试锁定的隐式契约。
- 修复摘要：先把语义钉死（建议：replace 是「整段重置」，同帧 content 视为重置后的追加），再让两处实现共用同一段折叠逻辑，并用一条单测同时锁定两种帧形态。
- 修复文件：frontend/shared/sseParser.js、frontend/src/utils/assistantStreamDrain.js、frontend/electron/qqBridge/qqBridge.js
- 建议测试：frontend vitest: sseParser 同帧 replace+content 的折叠结果与整流结果一致；frontend vitest: 仅 replace 时 fullContent 被整体替换

### COR-D7877 · [低] Python 写库白名单被 writer 的 UPDATE 绕过，架构约束实际不成立
- 专家 / section：correctness / architecture
- 位置：`backend-agent/app/memory/writer.py:193`
- 置信度：high · kind：plan
- 证据：_update_meta: await cur.execute("UPDATE memory_meta SET memory_type = %s, importance = %s, source_msg_ids = %s, source_hash = %s WHERE id = %s", ...)；_replace_and_enqueue: await cur.execute("UPDATE memory_meta SET summary = %s, memory_type = %s, importance = %s, source_msg_ids = %s, source_hash = %s, milvus_vec_id = NULL WHERE id = %s", ...)。而 app/write.py:44-52 的 _ALLOWED_UPDATES 只放行 update memory_meta set milvus_vec_id = %s where id = %s 与三类 update memory_outbox 前缀，_assert_allowed 仅在 execute_write 内调用。
- 影响：write.py 的文档把这层描述为「Python 侧唯一的写库入口 + 表级白名单 + UPDATE/DELETE 一律拒绝」，但 writer 直接用事务游标执行 UPDATE memory_meta 的内容改写，白名单完全没有参与。后果不是数据错，而是「爆炸半径」这个架构承诺失效：后续任何按白名单做审查或改权限（例如把 DB 账号收窄到只能改 milvus_vec_id）的动作都会与代码实际行为冲突。
- 修复摘要：二选一定口径：要么把 memory_meta 的这几类 UPDATE 正式登记进 write.py 白名单并统一走 execute_write，要么撤销白名单、明确写「Python 可写 memory_meta 与 memory_outbox」，不要让文档与实现相反。
- 修复文件：backend-agent/app/write.py、backend-agent/app/memory/writer.py
- 建议测试：pytest: test_write_guard_blocks_unknown_update；pytest: test_write_guard_allows_memory_meta_upsert_shapes

### COR-5CA37 · [中] 图片尺寸上限在解码之后才校验，超大像素图在解码阶段就 OOM
- 专家 / section：correctness / edge-cases
- 位置：`backend/lianyu-common/src/main/java/com/lianyu/common/util/ImageUploadValidator.java:44`
- 置信度：high · kind：plan
- 证据：BufferedImage image = decodeImage(raw); if (image == null) { throw ... } int width = image.getWidth(); int height = image.getHeight(); if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION) { throw new BusinessException(ErrorCode.BAD_REQUEST, "图片尺寸无效或超过限制"); }；decodeImage 内部是 ImageIO.read(ByteArrayInputStream)。
- 影响：MAX_BYTES=8MB 只管字节数，一个 8MB 以内的 PNG 可以声明 20000×20000（4 亿像素，ARGB 约 1.6GB）。ImageIO.read 会先按声明尺寸分配像素缓冲，于是 OOM 发生在第 50 行的尺寸校验之前——抛出的是 OutOfMemoryError 而不是 BusinessException，GlobalExceptionHandler 接不住，请求 500，并且会瞬时吃掉共享堆影响同进程的其他请求（上传、头像、朋友圈共用这条路径）。MAX_DIMENSION=8192 这个常量因此不构成任何防护。
- 修复摘要：解码前先从文件头读尺寸（PNG IHDR / JPEG SOFn / GIF LSD / WebP VP8X）并校验像素数与总像素上限，再解码；解码也放到有上限的缓冲策略里（缩略解码或用 ImageReader 读取指定尺寸）。
- 修复文件：backend/lianyu-common/src/main/java/com/lianyu/common/util/ImageUploadValidator.java
- 建议测试：ImageUploadValidatorTest: 声明 20000x20000 的 PNG 在解码前被拒绝；ImageUploadValidatorTest: 8192x8192 边界值可通过、8193 被拒绝

### COR-0802C · [低] 会话摘要的 Redis 读改写没有并发保护，lastSummarizedSeq 可能回退
- 专家 / section：correctness / concurrency
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/conversation/SessionSummaryService.java:88`
- 置信度：medium · kind：plan
- 证据：@Async public void maybeMergeAsync(Long conversationId, String provider, String model) { maybeMerge(conversationId, provider, model); }；maybeMerge 中 SessionSummaryState state = loadState(conversationId) → List<Message> pending = loadPendingMessages(conversationId, state.getLastSummarizedSeq(), windowOldestSeq) → saveState(conversationId, next)，整段无锁、无 CAS、无版本比较；state 只存 text/lastSeq/updatedAt。
- 影响：摘要合并是「读 lastSeq → 取 pending → 调模型（秒级）→ 写回 lastSeq」的长事务，且由 @Async 触发，同一会话两个回合并发时后写者可能用更旧的 pending 集合覆盖更靠后的 lastSeq：导致 lastSummarizedSeq 回退，同一批消息被再次合并进摘要（模型调用浪费、摘要文本重复膨胀）。用户可见表现是摘要里出现重复段落。
- 修复摘要：落库前做一次比较写（仅当新 lastSeq 大于等于现存值时写入），并用会话级 Redis 锁或 SETNX 把同一 conversationId 的合并串行化。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/conversation/SessionSummaryService.java
- 建议测试：SessionSummaryServiceTest: 并发合并时 lastSummarizedSeq 不回退；SessionSummaryServiceTest: 抢锁失败时不调用 merger

## 转交 deferred
- 没有跨专家转交项。

## 机器提示（仅供参考）
- 没有检测到明显的跨专家重复候选。

## 交给主编
1. 读本文件与原始 handoff；不要只依赖机器提示。
2. 合并重复项，保留最可信的证据；被合并的 ID 记入 verdict.dropped。
3. 写 `verdict.json`（schemaVersion 2、findings、coverage、dropped），再写 `report.md`。
4. 只有完整覆盖默认四模块时，才更新 `docs/logistics/latest.md` 与 `.logistics/latest.json`。
5. 工作区根目录：`C:\Users\hp\Desktop\LianYu-PC`；运行目录：`C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261104-173542-887e1b`。
