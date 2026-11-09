# 正确性审查报告

审查对象：LianYu-PC 全项目（非 diff），HEAD `c652e14`，1100 个受审文件 / 135978 行 / 12 个审查单元。

## 范围与覆盖

| 单元 | 覆盖 | 实际读过的文件 | 状态与理由 |
| --- | --- | --- | --- |
| u01 backend/lianyu-service (1/3) | 36 文件 | 36 | reviewed（agent Port/Result 契约与 ai 包：先看接口返回值/异常约定，再抽查实现类，未发现契约漂移） |
| u02 backend/lianyu-service (2/3) | 36 文件 | 36 | reviewed（dto/graph/memory/moments 四个域：memory 与 moments 逐行读，dto 与 graph 做字段与返回约定核对） |
| u03 frontend/src (1/2) | 37 文件 | 37 | reviewed（api 层 12 个核心文件全读，页面按调用链抽查；innerThoughtFilter 复现出嵌套括号缺陷） |
| u04 scripts (1/2) | 36 文件 | 36 | sampled（12 个核心脚本全读，其余 24 个按上传/发版/探针三组抽样；未发现正确性问题） |
| u05 backend/lianyu-dao | 36 文件 | 36 | reviewed（V1～V57 迁移逐条核对约束与幂等性，实体字段与迁移列一一比对） |
| u06 backend-agent | 36 文件 | 36 | reviewed（12 个核心文件全读（gateway/stream_chat/chat_turn/tts/resilience/data_readers），另有 24 个按调用链抽查） |
| u07 frontend/src (2/2) | 36 文件 | 36 | reviewed（路由/设置/Store 与 6 个主页面：启动失败恢复、持久化与页面状态迁移） |
| u08 backend/lianyu-web | 36 文件 | 36 | reviewed（11 个 controller 全读，agent 适配层与 SSE 解析重点核对） |
| u09 frontend/electron | 39 文件 | 39 | sampled（electron 主进程 3077 行按模块抽查（更新器/IPC/桥接），核心文件全列） |
| u10 其他模块（root/.github/admin 等） | 36 文件 | 36 | sampled（admin、docker-compose、nginx、CI、部署脚本：配置与编排一致性抽查） |
| u11 backend 小模块合集 | 36 文件 | 36 | reviewed（12 个核心测试全读 + 15 个相邻测试 + 9 个 admin 主类，重点看断言是否覆盖缺陷路径） |
| u12 其他模块（app/common/installer/Pets） | 36 文件 | 36 | sampled（公共工具/安装器/脚本合集抽样，OutboundUrlValidator 与限额常量逐行核对） |

核心文件（scope.project.units[].core）全部纳入各单元 files 清单；u09/u10/u12 为 sampled，只做了核心文件精读 + 其余模块抽查，不能视为逐行全量已读。

## 门禁结果

| 命令 | 结果 | 说明 |
| --- | --- | --- |
| `npm --prefix admin test` | not-run | 实跑 exit 1：'vitest' 不是内部或外部命令；admin/node_modules 不存在，未安装依赖，未能执行任何用例 |
| `mvn -q -f backend/pom.xml test` | passed | BUILD SUCCESS，10 个模块全绿；lianyu-service 256 tests / lianyu-web 123 tests，0 failures 0 errors |
| `pytest -q backend-agent` | not-run | 仓库根运行 48 errors（ModuleNotFoundError: No module named 'app'）；在 backend-agent 内 python -m pytest -q 仍 26 errors：缺 aiomysql（15）与 respx（11），用例未执行 |
| `npm --prefix frontend test` | passed | Test Files 71 passed (71)，Tests 417 passed (417) |

## 发现（按严重度排序）

- [high] [u02] [plan] C-01 — 同一条消息抽出的多条记忆共享 source_hash，互相覆盖或来回改写
详细问题：同一条消息抽出的多条记忆共享 source_hash，互相覆盖或来回改写
位置：backend/lianyu-service/src/main/java/com/lianyu/service/memory/MemoryWriter.java:270 · 证据：MemoryWriter.java:270-275 的 resolveSourceHash 只要 sourceIds 非空就 `return computeSourceIdsHash(sourceIds)`，把 summary 参数丢掉；:277-282 的 computeSourceIdsHash 只做 `sha256(sorted ids joined by ",")`。:104-126 每条 ExtractedMemory 只带一个 sourceMsgId（List.of(memory.sourceMsgId())），所以同一条用户消息抽出的 N 条记忆 hash 完全相同。:200 的 findExistingMemory 用 `selectOne(...eq(sourceHash).last("LIMIT 1"))` 命中先插入的那行，:227-251 因 summary 不同走覆盖分支（改写 summary/memoryType/importance、setMilvusVecId(null)、enqueueDelete + enqueueUpsert）。DB 侧 V1__init_schema.sql:64,67 为 `source_hash CHAR(64)` + `UNIQUE KEY uk_source_hash (source_hash)`（全局唯一）。Python 侧同一语义的实现是 backend-agent/app/memory/writer.py:62-74 `payload = f"{joined}|{summary.strip()}" if summary else joined`，其 docstring 自称与 Java 规则一致，实际 Java 未跟上。
影响：一句“我叫小明，我喜欢猫”会让【长期记忆/姓名】与【长期记忆/爱好】落到同一行，只剩一条；重复抽取时该行在两条内容间来回改写，每轮都往 memory_outbox 追加一次删除+写入，Milvus 反复重投影。用户侧表现为角色记不住刚说过的事。 · 修复：把记忆内容纳入 source_hash 口径，让 Java 与 Python 算出同一个不可碰撞的 hash，并迁移重算历史行。

- [high] [u03] [direct] C-02 — 嵌套括号时按原串下标删除区间，误删正文并重复渲染内心独白
详细问题：嵌套括号时按原串下标删除区间，误删正文并重复渲染内心独白
位置：frontend/src/utils/innerThoughtFilter.js:171 · 证据：stripInnerThoughts(:167-177) 取 findParenthesisRanges 后按 start 倒序排序，再对**已经变短的 result** 用原串下标切片：`ranges = findParenthesisRanges(text).sort((a,b)=>b.start-a.start); for (...) result = result.slice(0,start)+result.slice(end+1)`。findParenthesisRanges(:108-126) 在遇到右括号时 pop，内层区间先入数组、外层后入，返回时只按 start 排序，重叠区间从未合并。node 实测（stripInnerThoughts(s,false)）：'（a（b）c）嗨' → ''，'（有点紧张（笑））你来了' → ''，'（笑）你好' → '你好'，'hi (a(b)c) there' → 'hi ere'。parseInnerThoughtSegments('（有点紧张（笑））你来了') 返回 [{inner:'（有点紧张（笑））'},{inner:'（笑）'},{speech:'）你来了'}]。
影响：关闭内心独白（角色设置项）时，用户看到的助手气泡被删成空白或残缺单词（如 there → ere），内容等于丢失；开启时独白重复出现并在正文里多一个“）”。嵌套括号在中文口语化台词里很常见（“（笑（无奈））”），因此不是罕见输入。 · 修复：改为一次性按区间拼装输出，并让区间集合先合并嵌套，避免在变短的字符串上复用旧下标。

- [medium] [u02] [plan] C-03 — relationship_state 读改写无版本控制，用户名回合与衰减调度并发丢更新
详细问题：relationship_state 读改写无版本控制，用户名回合与衰减调度并发丢更新
位置：backend/lianyu-service/src/main/java/com/lianyu/service/relationship/RelationshipStateService.java:185 · 证据：recordUserTurn(:107) 在 :132 用 `relationshipStateMapper.updateById(state)` 整行写回；recordAssistantTurn(:136) 在 :165 同样整行写回；decayAll(:172-190) 先 `selectList(orderByAsc(lastDecayAt).last("LIMIT 500"))`（:177-181），再在 :185 `updateById(state)` 整行写回。调度器 backend/lianyu-service/src/main/java/com/lianyu/service/relationship/RelationshipDecayScheduler.java:23-24 为 `@Scheduled(fixedDelayString = "${lianyu.relationship.decay.interval-ms:1800000}")`（默认 30 分钟）。实体 backend/lianyu-dao/src/main/java/com/lianyu/dao/entity/RelationshipState.java 无 @Version 字段，updateById 为全列覆盖。
影响：衰减任务读到旧快照后写回，会把同一时刻用户回合刚加的 trust/intimacy、streak_days、last_interaction_at 覆盖回旧值；反之用户回合写回会把已结算的 last_decay_at 抹掉，使该行下一轮重复结算或长期轮不到。结果是关系分与连续天数偶发倒退，且没有日志可查。 · 修复：给 relationship_state 引入版本号或原子增量更新，避免整行覆盖式读改写。

- [medium] [u01] [plan] C-04 — 消息 seq 只靠 Redis INCR，未按数据库 max(seq) 播种，缓存丢失后与唯一键冲突且不自愈
详细问题：消息 seq 只靠 Redis INCR，未按数据库 max(seq) 播种，缓存丢失后与唯一键冲突且不自愈
位置：backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java:483 · 证据：ConversationService.java:479-487 getNextSeq → reserveSeqBlock 直接 `Long lastSeq = redisTemplate.opsForValue().increment(SEQ_KEY_PREFIX + conversationId, safeCount)`（SEQ_KEY_PREFIX = "msg_seq:"，:72），既没有 setIfAbsent 播种，也没有按 `select max(seq)` 回填；GroupChatService.java:1314-1325、VoiceCallService.java:722 是同一份实现。只有删会话（ConversationService.java:225-228）、clearMessages（:243）、删角色会话（CharacterService.java:297-300）会 delete 这个 key。DB 侧 V1__init_schema.sql:54 有 `UNIQUE KEY uk_conv_seq (conversation_id, seq)`，而所有 messageMapper.insert 调用点都没有 DuplicateKeyException 处理（ConversationService.java:262,330,722、GroupChatService.java:355,574,709、ProactiveMessageService.java:549、VoiceCallService.java:196,206,351,370,453）。缓解事实：docker-compose.yml:271-282 的 redis 用 `--appendonly yes` + 命名卷 redis_data，未配置 maxmemory 驱逐，只有 flush/换库/卷丢失才会触发。
影响：触发后发消息会持续 500（唯一键冲突），且不会自愈——除非人工删会话或手工 SET 回正确序号。运维迁移 Redis、清理缓存、恢复备份都属于会触发的高频操作。 · 修复：让数据库成为序号真相源：Redis 只做加速，缺失时按 max(seq) 播种，并对冲突做一次重试。

- [medium] [u06] [plan] C-05 — 工具轮次之间文本直接首尾拼接，SSE 内容缺少轮次分隔
详细问题：工具轮次之间文本直接首尾拼接，SSE 内容缺少轮次分隔
位置：backend-agent/app/stream_chat.py:105 · 证据：stream_chat.py:105-107 `round_text.append(delta); collected.append(delta); yield sse_content(delta)`，第 118-132 行把 assistant 消息与 tool 结果回灌后进入下一轮继续 append；`content = "".join(collected)`（:144）同样不带分隔。两轮文本因此在客户端首尾相接，例如第一轮“我先查一下今天”+ 第二轮“北京晴，26 度”直接变成“我先查一下今天北京晴，26 度”。
影响：含工具调用的回合读起来像错句，且前端拿到的完整 content 与分段 pieces 都是黏连版本，属于面向客户端的内容契约问题；用户会认为角色说话结巴或逻辑断裂。 · 修复：在轮次边界补一个显式分隔（换行或空格），并让 pieces 与最终 content 使用同一份带分隔的文本。

- [low] [u01] [direct] C-06 — Java 内心独白剥离只做单层替换，嵌套括号残留且与前端结果不一致
详细问题：Java 内心独白剥离只做单层替换，嵌套括号残留且与前端结果不一致
位置：backend/lianyu-service/src/main/java/com/lianyu/service/ai/InnerThoughtFilter.java:28 · 证据：InnerThoughtFilter.java:11-12 只有单层模式 `FULL_WIDTH_PARENS = （[^（）]*）`、`HALF_WIDTH_PARENS = \([^()]*\)`；:24-31 的 strip 对全角、半角各跑一次 replaceAll，不循环也不按区间处理。因此“（a（b）c）”只会删掉内层“（b）”，留下“（ac）”。同一段文本前端 stripInnerThoughts 走区间整段删除（见 C-02），两端结果不同。调用点：ConversationService.java:704、GroupChatService.java:1155、VoiceCallService.java:533,588、voice/SentenceSplitter.java:217、voice/VoiceCallDuplexSession.java:491（落库展示与 TTS 朗读都会用到）。现有 InnerThoughtFilterTest 只有 5 条基础断言，无嵌套用例。
影响：关闭内心独白后，带嵌套括号的回复会以“（ac）”这种残句落库并显示，TTS 也会把括号读出来；同一段文本在客户端被整段删除、在服务端只删一层，前后端展示不一致。 · 修复：按最外层配对整体删除，而不是按单层正则替换。

已查未发现问题的区域：

- 架构边界（architecture 0 条）：`JavaAiLogicRatchetTest` 随 mvn 通过，Java 侧无 prompt 文本/模型调用点；`backend-agent` 未直连业务表写入，唯一写口 `app/write.py` 白名单为 `memory_meta` / `memory_outbox`。
- SSE 帧契约：`frontend/shared/sseParser.js`、`backend/lianyu-web/.../AgentSseParser.java`、`AgentStreamBridge.java` 对 `content/pieces/replace/error/[DONE]`、CRLF、心跳、多行 data 的处理彼此一致。
- 分页与限额：`ConversationService.getMessages` 游标分页无重复/漏页；`UserInputSanitizer` 与 `message_assembly` 的 8000 字上限一致。
- 更新器：`updater.js` 的版本比较、签名/大小校验路径正确。

## 未验证 / 抽样说明

- 每个单元实际读取 36 个文件，但除 u03/u05/u06/u07/u08/u11 的核心文件为逐行精读外，其余为“核心精读 + 调用链抽查”；u04/u09/u10/u12 明确只做抽样。
- 两条门禁未执行：`npm --prefix admin test` 缺 vitest（admin/node_modules 不存在）；`pytest -q backend-agent` 缺 aiomysql/respx。因此与这两条命令相关的模块（admin、backend-agent 大部）只能靠静态审查，不能声称“测试通过”。
- C-03（关系分并发）与 C-04（seq 播种）为静态推理结论，未在真实 MySQL/Redis 上做并发压测复现；C-01 的覆盖链路由唯一键与代码路径推出，同样未跑库。

## 建议修复顺序与转交

1. C-02（前端误删正文）—— 改动小、影响直接，先修并补单测。
2. C-01（记忆覆盖）—— 需迁移，排在前端修复之后、下次发版之前。
3. C-04（seq 播种）—— 与运维迁移/清缓存强相关，建议与部署流程一起上线。
4. C-03 / C-05 / C-06 —— 可在同一迭代内按 plan 评估后排期。

转交：`backend-agent` 的受控写口与衰减轮转饥饿交 maintainability / reliability 评估；moments 锁 TTL 与模型超时同为 90s 的隐患交 reliability；`scripts/_upload_update_assets.py` 的 `get_pty=True` 交 reliability。
