# 正确性审查报告

审查对象：`C:/Users/hp/Desktop/LianYu-PC`（project 模式，非 diff），HEAD `c652e14`。

## 范围与覆盖

1108 个文件 / 140294 行，按 scope 切成 12 个审查单元；逐个单元实际读过文件（清单见 `correctness.json` 的 `units[].files`）。

| 单元 | 范围 | 文件数 | 状态 | 说明 |
| --- | --- | --- | --- | --- |
| u01 | lianyu-service 1/3 | 32 | reviewed | 逐文件读 agent 端口层与 conversation/ai/relationship 调用方；重点核对 SSE 收尾、seq 分配、事务边界 |
| u02 | lianyu-service 2/3 | 30 | reviewed | 逐个读 12 个 DTO + relationship/memory/moments/notification 服务；重点核对里程碑、状态读改写、outbox 入队 |
| u03 | frontend/src 1/2 | 34 | reviewed | api 层 18 个文件全读（含 httpCore 401 刷新与 SSE 流），components/composables 抽样读关键实现 |
| u04 | scripts 1/2 | 30 | sampled | 按脚本用途分类抽样：发布/更新资产、Flyway 修复、诊断脚本各读代表文件，未逐行读全部一次性脚本 |
| u05 | lianyu-dao | 63 | reviewed | 23 个 entity + 23 个 mapper 字段级通读，V1/V10-V19/V31/V46/V54/V56/V57 迁移逐条读 |
| u06 | frontend/src 2/2 | 54 | sampled | 页面体量大（ChatPage 2266 行等），读入口/路由/状态管理与工具函数，页面按结构抽样并配合 ops_grep 做跨文件模式扫描 |
| u07 | backend-agent | 32 | reviewed | 回合编排、工具循环、网关、记忆写入/relay、只读数据访问契约逐文件读 |
| u08 | lianyu-web | 32 | reviewed | 14 个 controller + 16 个 agent 适配器 + 全局异常/限流组件读完，核对 SSE 帧与适配器降级契约 |
| u09 | frontend/electron | 34 | reviewed | 更新器、QQ/微信桥、NapCat 运行时、主进程 IPC 代理逐文件读或结构级读 |
| u10 | 根/.github/admin/security 等 | 31 | sampled | admin 前端、security/ai/storage 模块、网关配置与 compose 抽样读；admin 页面为内联单文件实现 |
| u11 | 小模块与测试合集 | 31 | sampled | 逐个读 30 个测试文件，按用例名判断覆盖意图与缺口，未逐行读断言实现 |
| u12 | lianyu-app/common/installer 等 | 37 | sampled | common 工具类、两处 pom、安装器 C# 工程与 Pet 脚本抽样读 |

测试命令执行情况（详见 JSON `tests`）：`npm --prefix admin test` 通过、`mvn -q -f backend/pom.xml test` 通过（123 个报告 / 531 用例 / 0 失败）、`npm --prefix frontend test` 通过（75 文件 / 459 用例）、`pytest -q backend-agent` **未能收集**（环境缺 `respx` 模块，非产品缺陷，按只读约束未安装依赖）。

## 发现

- [high] [u01] [direct] C01 — 建广场角色、落语音通话记录、写日记这三件事中间任何一步失败，前面已经写进去的数据都不会撤回，会留下角色没有会话、通话只有半段、日记没有提醒这样的半成品。
  详细问题：@Transactional 标在 protected 同类直调方法上，广场角色/语音通话/日记三处多写没有事务
  位置：backend/lianyu-service/src/main/java/com/lianyu/service/square/CharacterSquareService.java:235 · 证据：CharacterSquareService.java:235-236 是 `@Transactional` + `protected CharacterResponse persistSquareCharacter(...)`，调用点在同文件 :231 `return persistSquareCharacter(...)`（同类自调用，不经过 Spring 代理）；方法体内依次 characterMapper.insert(:252) → squareAddCountService.incrementAndInvalidate(:261) → conversationService.create(:268)。同款写法另有两处：VoiceCallService.java:186-187（调用点 :167，方法体 :188/:198 两次 nextSeq 后插两条 message）、CharacterDiaryService.java:446-447（调用点 :378，:458 diaryMapper.insert、:465 notifyDiaryNew）。Spring 代理默认只增强 public 方法，且自调用根本不进入代理，两重原因使注解不生效；ProactiveMessageService.java:53 的类注释已把该机制写明，并在 :238 用 `self.persistProactiveReply(...)` 修复过同类问题。
  影响：三条多写流程在第二步/第三步失败时留下不一致数据：广场角色已建但会话未建（用户点进去没有对话）、语音通话只存了用户句、日记已落库但没有通知；且因为这些方法标了 @Transactional，排查者会误以为已受事务保护。 · 修复：让事务真的生效：把多写方法改成 public 并通过注入的自身代理或独立 bean 调用，或把事务边界上移到无外部调用的一方。（文件：backend/lianyu-service/src/main/java/com/lianyu/service/square/CharacterSquareService.java、backend/lianyu-service/src/main/java/com/lianyu/service/conversation/VoiceCallService.java、backend/lianyu-service/src/main/java/com/lianyu/service/character/CharacterDiaryService.java、backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ProactiveMessageService.java；验证：mvn -q -f backend/pom.xml test；新增 CharacterSquareTransactionTest（失败注入，断言无孤儿角色））

- [medium] [u07] [direct] C02 — 当这一轮携带的工具名一个都没注册上时，模型只会收到用户和角色说过的句子，角色设定、记忆、情绪提示全部消失，回复会跳出角色却没有任何报错。
  详细问题：无工具分支丢掉 system prompt：模型只收到折叠后的对话文本
  位置：backend-agent/app/tool_loop.py:128 · 证据：tool_loop.py:126-143 `specs = resolve_tools(tool_names)`，`if not specs:` 分支调用 `gateway.chat_sync(system=None, user_text=_flatten(messages))`；`_flatten`(:205-214) 明确跳过 role == 'system'，只把历史拼成「用户：/你：」文本。对照 app/graphs/proactive_turn.py:139-158 的无工具路径：它把 system 单独取出（`system = msg['content']`）再传入 chat_sync。resolve_tools 对未注册的工具名静默忽略；chat_turn.py:53-58 的 CHAT_TOOL_NAMES 目前 4 个名字都能在注册表命中（time_tool.py:87 / weather_tool.py:85 / memory_search_tool.py:175 / recent_activity_tool.py:188），所以现在是潜在缺陷：注册表改名（2026-10-25 删除 desk_* 就是此类漂移）或调用方传入未注册名（app/main.py:238 `tool_names=body.tools or None` 直接透传）都会走进该分支。
  影响：走到该分支时人设、长期记忆、关系/情绪上下文全部丢失，模型以「无系统提示」的通用助手口吻回复，用户看到角色不像角色；由于没有任何异常或日志，线上表现为偶发「失忆」，很难归因。 · 修复：无工具分支也把 system 单独传给 chat_sync，与 proactive_turn 的无工具路径共用同一段取 system 的逻辑，并补一条单测锁定。（文件：backend-agent/app/tool_loop.py、backend-agent/app/graphs/proactive_turn.py；验证：pytest -q backend-agent/tests/test_tool_loop_system_prompt.py（新增）；pytest -q backend-agent（既有用例不得回退））

- [medium] [u01] [plan] C03 — 消息编号靠 Redis 里的一个计数器发号，这个计数器一旦被清空，新消息就从 1 开始编号，和历史消息撞号，用户会发不出消息。
  详细问题：消息 seq 只存在 Redis，计数器清零后与 uk_conv_seq 撞唯一键导致发消息 500
  位置：backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java:485 · 证据：ConversationService.java:483-487 `increment(SEQ_KEY_PREFIX + conversationId, safeCount)`，取不到时回落 `safeCount`；全仓只有 4 处 seq key 操作（:226/:243 删除、:485 increment、GroupChatService:1320、VoiceCallService:722），没有任何从数据库回填的逻辑。数据库侧 V1__init_schema.sql 的 message 表带 `UNIQUE KEY uk_conv_seq (conversation_id, seq)`。docker-compose.yml:271-286 的 redis 虽配了 appendonly 与 volume，但没有 maxmemory-policy，运维 FLUSHALL、重建卷、切换实例都会让计数器归零。
  影响：计数器丢失后，该会话的每条新消息都会算出一个与历史冲突的 seq，插入抛 DuplicateKeyException：私聊、群聊、语音通话三条发送路径同时 500，用户侧表现为发送失败或「对方没有回复」，直到计数器重新追平历史最大 seq。 · 修复：让 seq 的唯一真相回到数据库：用数据库原子分配（conversation 行上的 seq 列或 SELECT MAX(seq) 初始化），Redis 只做缓存加速。（文件：backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java、backend/lianyu-service/src/main/java/com/lianyu/service/conversation/GroupChatService.java、backend/lianyu-service/src/main/java/com/lianyu/service/conversation/VoiceCallService.java、backend/lianyu-dao/src/main/resources/db/migration/；验证：mvn -q -f backend/pom.xml test；新增 MessageSeqAllocatorTest（Redis 清空场景））

- [medium] [u07] [plan] C04 — 一次对话抽出的几条记忆本该一起成功或一起放弃，实际是逐条写入；中途出错时前半段已经写进库，后端还会用 Java 再写一遍，结果同一段聊天可能变成好几条记忆。
  详细问题：记忆写入声明整批单事务，实际逐条提交；失败后 Java 再抽一遍造成重复记忆
  位置：backend-agent/app/memory/writer.py:121 · 证据：writer.py:121-122 docstring「批量写入；整批在同一事务内，任一失败全回滚」，实际 :178(_insert_new)、:196(_update_meta)、:212(_replace_and_enqueue) 每条记忆各自 `async with transaction()`。app/main.py:951-957 捕获异常后返回 500 `detail='memory write failed'`；Java 侧 AgentMemoryAdapter.java:99-105 收到 null 或 error 就 return false，MemoryWriter.java:89-95 于是走自己的抽取 + upsertTypedMemory。source_hash 含 summary（writer.py compute_source_ids_hash），Python 与 Java 的摘要文本不同就视为两条新记忆。
  影响：一次失败的批量写入会把已提交的前几条留在库里，Java 兜底再写一遍时因为摘要不同而生成新行：用户在记忆页看到同一段对话的重复条目，记忆数与检索结果都失真，还多占向量。 · 修复：二选一：要么真正实现整批单事务（写失败整体回滚，Java 兜底只会补写一次），要么改成幂等写入并在 500 时告知 Java 不可重写。（文件：backend-agent/app/memory/writer.py、backend-agent/app/main.py、backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentMemoryAdapter.java、backend/lianyu-service/src/main/java/com/lianyu/service/memory/MemoryWriter.java；验证：pytest -q backend-agent；mvn -q -f backend/pom.xml test）

- [medium] [u07] [plan] C05 — 文档和部署脚本都说模型运行时只读数据库，但代码里它直接往记忆表写数据；如果按文档收紧权限，记忆功能会整体写不进去，而现在两边共用的却是同一个全权账号。
  详细问题：Python 侧直接写 MySQL，与「只读账号 / Python 不写库」的既定边界冲突
  位置：backend-agent/app/write.py:29 · 证据：app/write.py:29 `_ALLOWED_INSERTS = frozenset({'memory_meta','memory_outbox'})`，memory/writer.py 与 memory/relay.py 都通过 transaction() 复用 db.py 的连接池写这两张表。而 app/db.py 的注释要求「生产请给 agent 配只读 MySQL 账号（GRANT SELECT）」、pyproject.toml:22 写「只读数据访问（总纲 D7）：MySQL 只读查询 + Redis 缓存读取」、docker-compose.yml:111 同样注释，但 compose 里 agent 与 agent2 用的仍是 `${MYSQL_USER:-lianyu}`（Java 的全权业务账号）；AGENTS.md §2 的红线写的是「Python ❌ 写 MySQL（只读）」。
  影响：两条路都不可接受：按文档收紧为只读账号，记忆抽取写入与 outbox relay 会全部失败，而且失败被 Java 兜底掩盖成静默降级；维持现状则模型运行时长期持有业务库写权限，「只读」的边界与文档全部失真。 · 修复：先定口径再改实现：要么承认 Python 拥有记忆表的受限写权限并按此配置独立账号，要么把写入收回 Java，Python 只返回抽取结果。（文件：backend-agent/app/write.py、backend-agent/app/db.py、backend-agent/pyproject.toml、docker-compose.yml、AGENTS.md；验证：pytest -q backend-agent；docker compose config（核对账号注入））

- [medium] [u07] [plan] C06 — 记忆写进库后要靠后台任务转成可检索的向量；任务中途进程挂掉或反复失败后，这条记忆就永远停在那里，用户再也搜不到它，也没有任何提示。
  详细问题：outbox 的 RUNNING/DEAD 行没有自动回收，崩溃留下的记忆永久不再投影
  位置：backend-agent/app/memory/relay.py:207 · 证据：relay.py:41-48 `_claim` 用 CAS 把 PENDING 改成 RUNNING；:207-222 `requeue_stuck()` 才会把 DEAD 与卡住 ≥10 分钟的 RUNNING 重置回 PENDING，但全仓只有 app/main.py:967-971 的 POST /agent/memory/relay/requeue 调用它。启动路径 main.py:33-57 的 lifespan 只 `create_task(_relay_loop())`，而 _relay_loop(:54-66) 仅循环调用 drain_once。reconcile()(:178-205，用于补 milvus_vec_id 为空的记忆)同样只出现在 POST /agent/memory/reconcile(main.py:974)。
  影响：进程被 kill / 重启后，留在 RUNNING 的记忆永远不会被再次领取；embedding 连续失败到达 attempts 上限后转 DEAD，也不再重试。两者都让这条记忆永久不可检索，而界面上看不出任何异常。 · 修复：把恢复动作接进后台循环：启动时先 requeue_stuck，并按较低频率周期性执行 requeue_stuck 与 reconcile。（文件：backend-agent/app/main.py、backend-agent/app/memory/relay.py；验证：pytest -q backend-agent；新增 tests/test_memory_relay_requeue.py（崩溃恢复用例））

- [medium] [u02] [direct] C07 — 用户只是说了一句带道歉词的话、但当时并没有处于受伤状态时，系统仍会记下「第一次和好」这个里程碑，用户会在关系里看到一段没发生过的和好。
  详细问题：修复事件被跳过时仍记录「第一次和好」里程碑
  位置：backend/lianyu-service/src/main/java/com/lianyu/service/relationship/RelationshipStateService.java:306 · 证据：RelationshipStateService.java:232-240：`injuryOpen` 在循环外算一次，REPAIR_ATTEMPT/REPAIR_SUCCESS 在 `!injuryOpen` 时 `continue`，既不计分也不 persistEvent；但 :133 仍把原始 `events` 列表交给 recordMilestones，:290-307 的循环对 `case REPAIR_ATTEMPT, REPAIR_SUCCESS -> recordMilestone(..., FIRST_REPAIR, ...)` 没有任何「该事件是否真的生效」的判断。
  影响：phase 仍在 TESTING、信任分没有变化的用户会收到 FIRST_REPAIR 里程碑，关系进度与真实互动不一致（V57 只修正了 trust_score，没有修里程碑），后续按里程碑解锁的内心情景会提前出现。 · 修复：只对真正生效的事件发里程碑：让 applyEvents 返回被采纳的事件列表（或带 applied 标记），recordMilestones 只看这份列表。（文件：backend/lianyu-service/src/main/java/com/lianyu/service/relationship/RelationshipStateService.java、backend/lianyu-service/src/test/java/com/lianyu/service/relationship/RelationshipStateServiceTest.java；验证：mvn -q -f backend/pom.xml test）

- [medium] [u03] [direct] C08 — 登录凭证过期后本该自动续期，但只要续期请求碰上网络抖动或后端 5xx，客户端就清掉登录态把用户踢回首页，正在输入的内容也一起消失。
  详细问题：刷新 token 时网络瞬断即清空登录态并跳回首页
  位置：frontend/src/api/httpCore.js:45 · 证据：httpCore.js:38-53 singleFlightRefresh 中 `catch { return false }`(:45-46) 把任何异常都当作刷新失败，包括 refreshAuthToken 的网络错误与 5xx；:69-72 `const ok = await singleFlightRefresh(); if (!ok) return rejectSessionExpired()`；rejectSessionExpired(:25-33) 会 clearTokenStorage + 清 store + `window.location.hash='#/'`。只有 refresh 返回 401（token 真的失效）才应该走这条路径。
  影响：弱网或后端抖动时用户被强制登出、未发送的输入丢失；重试刷新本可以恢复的场景变成了重新登录，也是「偶尔掉登录」投诉的最可能来源。 · 修复：区分「凭证失效」与「刷新请求失败」：只有 401 才登出，网络错误/5xx 保留会话并把错误抛回原请求。（文件：frontend/src/api/httpCore.js、frontend/src/api/auth.js、frontend/src/api/__tests__/sessionRefresh.test.js；验证：npm --prefix frontend test）

- [medium] [u03] [plan] C09 — 记忆页一次只拿前 50 条且没有翻页，记忆攒多了以后，老记忆在界面上消失，用户会以为它们被删掉了。
  详细问题：记忆页固定只取第一页 50 条，更早的记忆在界面上永久不可见
  位置：frontend/src/api/memory.js:3 · 证据：frontend/src/api/memory.js:3-5 `listMemories(characterId)` 只传 characterId；frontend/src/pages/MemoryPage.vue:305 `listMemories(filterCharId.value || undefined)` 一次性取回、没有分页或「加载更多」；后端 MemoryController.java:29-35 提供 page/size 且默认 size=50，MemoryQueryService.java:39-50 用 `LIMIT ... OFFSET ...` 分页。前端从不传这两个参数，等于永远固定在第 1 页。
  影响：记忆超过 50 条的用户在记忆页看不到更早的记忆，也无法通过界面删除或查看来源消息，与「记忆管理」的产品承诺不符；同时后端分页能力事实上没有被使用。 · 修复：前端接上后端分页：滚动加载或分页器 + 总数展示；若产品不打算做分页，则后端按聚合视图返回全量摘要并明确上限。（文件：frontend/src/api/memory.js、frontend/src/pages/MemoryPage.vue、backend/lianyu-web/src/main/java/com/lianyu/web/controller/MemoryController.java、backend/lianyu-service/src/main/java/com/lianyu/service/memory/MemoryQueryService.java；验证：npm --prefix frontend test；mvn -q -f backend/pom.xml test）

- [medium] [u02] [plan] C10 — 同一个人同时从两个入口（比如桌面和 QQ 桥）跟同一个角色说话时，两次互动各自算完分数再整行写回，后写的会盖掉先写的，那一次互动的变化就丢了。
  详细问题：关系状态是无锁的读-改-写，并发回合与定时衰减会互相覆盖
  位置：backend/lianyu-service/src/main/java/com/lianyu/service/relationship/RelationshipStateService.java:107 · 证据：recordUserTurn(:107) 没有 @Transactional：loadOrCreateState → fromUserTurn → applyEvents → applyPresence → `relationshipStateMapper.updateById(state)`(:132)，整行覆盖；定时衰减 decayAll 也是 select 后逐行 `updateById`(:185)。同用户的并发入口是真实存在的：主聊天（ConversationService.sendMessageStream）与 QQ/微信桥转发都会落到 recordUserTurn。
  影响：并发时后写者覆盖前写者，那一轮互动的信任/亲密/安全增量丢失；衰减任务用旧快照回写时，还可能把刚发生的互动（lastInteractionAt、streak）一起抹掉，表现为「聊了但关系没变」。 · 修复：把关系更新变成有原子性的过程：加乐观锁（version）或行锁，并让衰减不要在读到旧快照后整行覆盖。（文件：backend/lianyu-service/src/main/java/com/lianyu/service/relationship/RelationshipStateService.java、backend/lianyu-dao/src/main/java/com/lianyu/dao/entity/RelationshipState.java；验证：mvn -q -f backend/pom.xml test）

- [low] [u01] [direct] C11 — 代码里有一个「客户端中途断开就把已生成内容存下来」的判断，但它依赖的错误类型永远不会出现，等于这段判断从没生效过；将来如果把上游超时改成 IOException，会把半句话当成完整回复存进历史。
  详细问题：isClientStreamDisconnect 只认 IOException，实际错误恒为 BusinessException 或 null
  位置：backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java:427 · 证据：ConversationService.java:427-429 `return error instanceof java.io.IOException;`；onComplete 的调用点全仓只有三处：SseChatStreamHelper.java:63 传 null（正常完成）、:83 传 `new BusinessException(ErrorCode.AI_PROVIDER_ERROR, ...)`（错误收尾）、AgentStreamBridge.java:138 传 null（转发中断）。因此该判断恒为 false，onComplete 里的「客户端断开」语义从未被识别。
  影响：分支是死代码，误导维护者以为客户端断开已被单独处理；一旦错误传播改成 IOException（例如把上游读超时包装成 IOException 抛出），半截回复会被当完整回复落库并跳过错误提示。 · 修复：用明确的语义标记替代异常类型判断：在 onComplete 的入参里带一个 abortReason（client_disconnect / upstream_error / done）。（文件：backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java、backend/lianyu-service/src/main/java/com/lianyu/service/ai/SseChatStreamHelper.java、backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentStreamBridge.java、backend/lianyu-service/src/main/java/com/lianyu/service/agent/AgentStreamCallback.java；验证：mvn -q -f backend/pom.xml test）

- [low] [u06] [direct] C12 — 读取回复时如果服务端最后一段没有以换行收尾，这段文字会被丢掉；解析到错误帧后连接也没有被主动关闭，会一直挂着等超时。
  详细问题：流式读取结束时不处理残留缓冲，出错时也不取消底层连接
  位置：frontend/src/utils/assistantStreamDrain.js:29 · 证据：assistantStreamDrain.js:26-35 按 '\n' 切分后 `buffer = parts.pop() || ''`，循环 `done` 退出时不再 feed 残留 buffer，直接 `return acc.result()`(:40)；:36-38 `finally { reader.releaseLock() }` 没有 `reader.cancel()`，acc.feed 遇到 error 帧抛异常时底层流不会被取消。
  影响：服务端最后一帧缺换行（截断、代理改写）时丢最后一段回复；错误帧抛异常后连接悬挂到超时，异常频繁时累积未关闭的流。 · 修复：循环结束后 flush 残留 buffer；异常路径上 cancel 读取器，保证连接被关闭。（文件：frontend/src/utils/assistantStreamDrain.js、frontend/shared/sseParser.js；验证：npm --prefix frontend test）

未发现问题的区域：SSE 帧契约（Java `SseChatStreamHelper`、`AgentSseParser`、Python `stream_chat`、前端 `shared/sseParser.js` 四处口径一致，`[DONE]`/keep-alive/`replace`+`pieces` 均已核对）；分页游标（`getMessages` 先反转再取 `nextBeforeSeq`，语义正确）；Java↔Python 的 outbox 常量（`UPSERT/DELETE/PENDING/RUNNING/DONE/DEAD` 两侧完全一致）；`OutboundUrlValidator` 与 Electron 出口白名单（host:port 精确匹配）。

## 未验证与抽样说明

- u04、u06、u10、u11、u12 状态为 `sampled`：分别因一次性运维脚本多、页面文件巨大（ChatPage 2266 行）、admin 内联单文件实现、测试文件以用例名判断意图、安装器 C# 工程体量大，只做了结构级阅读与跨文件模式扫描（环境中没有 `rg`，用的是等效的文件搜索工具），不代表逐行通读。
- `backend-agent` 的 48 个测试模块因缺 `respx` 未运行，Python 侧结论均来自代码审读与调用链追踪，未做运行时复现。
- V57 只回写 `trust_score`，线上是否存在「里程碑已发但信任分已被修正」的存量数据需要查生产库，本轮无库访问权限。

## 建议修复顺序

1. **C01**（事务不生效）——影响面最广，三处多写会直接留下不一致业务数据，改动集中在三个 service，风险低。
2. **C02**（丢 system prompt）——静默降级最难排查，先补 helper 与单测，防止注册表再次漂移。
3. **C03**（seq 只存 Redis）——需要一次迁移，但与上线节奏解耦，可排在发布窗口前。
4. **C04 / C05 / C06**（记忆链路）——三件事同源（Python 写库 + 逐条事务 + 无自动恢复），建议一起定口径后一次改完。
5. **C07 / C10**（关系状态机）——同属 relationship 包，可合并成一个改动 + 一个测试类。
6. **C08 / C09 / C12**（前端）——独立于后端，可并行。
7. **C11**（死分支）——顺手清理，建议与 C01 同一批提交。

## 跨专家转交

- 交给 **reliability**：outbox 的 DEAD/RUNNING 只能手工恢复（C06）、adapter 失败一律 `return null` 由 Java 兜底的静默降级；`AgentMemoryAdapter` 收到 500 后重写的兜底路径也属失败可见性范畴。
- 交给 **security**：`scripts/_prod_verify.py` 内嵌明文账号口令、`scripts/_check_yml.py` 把本地 `application.yml` 直传生产、`_repair_flyway_*.py` 硬编码生产 IP 与 root 账号并直接删 `flyway_schema_history` 行；这些是凭证与运维面问题，未计入本次正确性发现。
- 交给 **maintainability**：`frontend/electron/updater/__tests__/updater.test.js:465` 的用例名写「调用 shell.openPath 启动安装包并退出」，而断言恰恰是 `shellOpenPath` 与 `quitAndInstall` 都没被调用——测试名与行为相反，容易让人误判安装器行为；`admin/src/pages/ManagementPage.vue` 里 `configs.users.columns` 定义后立刻被同文件下一行覆盖（死代码）、`admin/src/App.vue` 首页写死「API 在线 / 最新客户端 v0.2.363 / 待处理 12」等演示数据、`tool_loop.py:153` 的 `nudged` 变量只有置位没有使用。
