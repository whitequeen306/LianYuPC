# 性能审查报告

## 一、范围与覆盖

- 审查对象：`C:/Users/hp/Desktop/LianYu-PC` 全项目（非 diff、非单次提交）。
- 规模：1675 个受跟踪文件，其中 1108 个进入审查范围（12 个审查单元 / 139994 行），567 个被 scope 省略。
- 审查面：热路径、复杂度、数据库访问、运行时/阻塞、前端渲染与包体、内存与启动。
- 实测手段：`frontend/dist` 产物体积与 chunk 依赖图（与源码同批构建）、`mvn` 全量测试、前端 vitest 全量测试、跨模块 `for` 循环 + 数据访问调用扫描（Java 全量 15 处命中，逐条判读）、Python 侧阻塞调用扫描（`time.sleep` / `requests` / `subprocess` 在 `backend-agent/app/**/*.py` 为 0 命中）。

### 单元覆盖状态

| 单元 | 范围 | 状态 | 读过的文件数 | 理由 |
| --- | --- | --- | --- | --- |
| u01 | backend/lianyu-service (1/3) | reviewed | 32 | 逐文件读 `GroupChatService`、`ConversationService`、`ProactiveChatScheduler`、`CharacterStateService`、`EmotionDecayScheduler`、`RelationshipStateService`、`GroupChatExecutorConfig` 等热点，并直读核心文件全文 |
| u02 | backend/lianyu-service (2/3) | sampled | 32 | 以 DTO / 广场目录为主，按摘要抽样 + 全量模式扫描，未逐文件读全文 |
| u03 | frontend/src (1/2) | sampled | 32 | 抽查 `api/*`、`composables/*`、`components/memory/*`，重点看请求层与渲染循环 |
| u04 | scripts (1/2) | sampled | 32 | 运维/发版脚本，非用户热路径；按摘要抽样，确认无运行时常驻逻辑 |
| u05 | backend/lianyu-dao | reviewed | 32 | 直读 `MessageMapper`、`ConversationMapper`、`SquareCommentMapper` 与 V1/V23/V46 等迁移，核对索引与 SQL 形态 |
| u06 | frontend/src (2/2) | reviewed | 32 | 直读 `main.js`、`vite.config.js`、`ChatPage.vue`、`QuickChatPage.vue`、`useChatScroll`、`assistantStreamDrain` 与 dist 产物 |
| u07 | backend-agent | reviewed | 32 | 直读 `config.py`、`db.py`、`memory/vector_cache.py`、`memory/embedding.py`、`tools/memory_search_tool.py`、`main.py` |
| u08 | backend/lianyu-web | sampled | 32 | Controller 层以分页与限流为主，抽样核对 11 个 Controller 与 agent 适配层 |
| u09 | frontend/electron | sampled | 32 | 主进程/桥接/更新器抽样，核对定时器与窗口预热链路 |
| u10 | 其他模块（root/.github/admin 等） | sampled | 32 | 配置与构建脚本，性能影响仅在构建期 |
| u11 | backend（小模块合集） | sampled | 32 | 以测试类为主，无运行时热路径，抽样确认断言与基线 |
| u12 | 其他模块（app/common/installer 等） | sampled | 32 | 公共类型处理器、安装器与容器配置，抽样核对 |

> 说明：`reviewed` 表示读了该单元的关键文件并做了针对性检查；`sampled` 表示体量大、做了模式扫描与抽样。本次没有任何单元被 `skipped`。

## 二、发现（按严重度排序）

- [high] [u01] [plan] P01 — 8 个并发群聊回合会把线程池占满，所有回复永久停住。
  详细问题：群聊编排任务与内层生成共用同一个 8 线程池，外层 join 阻塞导致自饥饿死锁
  位置：backend/lianyu-service/src/main/java/com/lianyu/service/conversation/GroupChatService.java:370 · 证据：`CompletableFuture.runAsync(..., taskExecutor)` 占 1 个线程；第 394-399 行 `supplyAsync(..., taskExecutor)` 把成员生成提交到同一个池；第 401-407 行在该池线程里 `anyOf(...).join()` 阻塞等待。`GroupChatExecutorConfig.java:12-23` 配置 core=8 / max=20 / queue=300，未设拒绝策略；队列 300 远未填满，ThreadPoolExecutor 不会扩容，第 9 个内层任务永远排队。
  影响：群聊功能在 8 个并发回合后整体挂死且无超时兜底，只能重启进程；4-7 个并发时回复被串行化、多角色顺序错乱，SSE 请求长期不收尾。 · 修复：拆成编排池与生成池（或改 `allOf(...).whenComplete` 非阻塞聚合），并给每回合加整体超时；补 8 并发回归用例。

- [high] [u06] [plan] P07 — 桌宠和快捷聊天窗口为几个按钮解析 1.1MB 脚本，打开明显变慢。
  详细问题：element-plus 全量注册把 1118 KB 的 launcherBootstrap chunk 拖进桌宠/快捷聊入口
  位置：frontend/src/main.js:3 · 证据：实测 dist 中 `launcherBootstrap-CEXfa8Qx.js` 1118.0 KB raw / 360.9 KB gzip（内含 element-plus 标识 10 次、ElMessage 5、el-button 3、el-dialog 1），`main-DTgPoq__.css` 398.7 KB / 54.3 KB gzip 为全量样式；`launcher-BE_O1lcz.js` 自身仅 706 字节、`quick-DUTbY0TJ.js` 仅 1224 字节，但首行都 import 该 chunk。根因是 main.js:3-4 全量 `import ElementPlus` + `element-plus/dist/index.css` 与 :69 `app.use(ElementPlus)`，vite.config.js 未做按需拆分。
  影响：桌宠/快捷聊冷启动需解析 1.1MB JS + 398KB CSS，而它们只用少数组件；Electron 在启动后 4s/10s/12s 预热这两个窗口（electron/main.js:1496-1513），三窗口并发解压同一大 chunk，低配机首屏可感知卡顿、常驻桌宠多占几十 MB 渲染内存。 · 修复：改 unplugin-vue-components 按需引入并移除全量 CSS，把 el-* 收进 app-only chunk，断言 launcher/quick 首屏 gzip <150KB。

- [medium] [u05] [plan] P02 — 每 30 秒为所有单聊会话算一次最后消息时间，会话越多越慢。
  详细问题：主动搭话扫描每 30 秒执行一次带相关子查询的 ORDER BY，代价随会话总数线性增长
  位置：backend/lianyu-dao/src/main/java/com/lianyu/dao/mapper/ConversationMapper.java:20 · 证据：SQL 在 `ORDER BY COALESCE((SELECT MAX(m.created_at) FROM message m WHERE m.conversation_id = c.id), c.created_at) DESC` 后 `LIMIT #{limit}`；调用方 ProactiveChatScheduler.java:62-68 为 `fixedDelay` 30000ms、scan-limit 40。相关子查询依赖外表列，无法用索引消除排序，需为每条单聊会话求一次 MAX 再整体 filesort。
  影响：单次成本 ≈ 单聊会话数 次索引下探 + 同规模排序，每天固定 2880 次；5 万会话量级即 5 万次子查询 + 5 万行排序，持续占用 CPU 与 buffer pool，表现为接口周期性抖动。 · 修复：conversation 增加 `last_message_at` + `(mode, last_message_at)` 索引并在写消息时维护，或改派生表 JOIN 聚合。

- [medium] [u01] [plan] P03 — 情绪衰减任务每次全表读入再逐行回写，跑得越久占连接越久。
  详细问题：decayAllEmotions 无 LIMIT 全表扫描并在同一事务内逐行 updateById
  位置：backend/lianyu-service/src/main/java/com/lianyu/service/character/CharacterStateService.java:179 · 证据：第 182-188 行 `selectList(...ne(currentEmotion,"平静")...)` 无 LIMIT；第 190-220 行 `for (...) { ... stateMapper.updateById(state); }` 在同一 `@Transactional` / 同一 Hikari 连接内逐行整行回写，循环结束才提交。V23__character_state.sql 只有 `uk_char_user` 与 `idx_user`，`current_emotion` / `emotion_updated_at` 无索引。同仓 RelationshipStateService.java:43/172-190 已有 `DECAY_BATCH_LIMIT=500` 的分页范式。
  影响：单次耗时随行数线性增长，超过 15 分钟间隔后任务堆叠；长事务占住连接并持行锁，阻塞在线聊天写 character_state；N 次单行 UPDATE 放大 redo/binlog。 · 修复：改 500 行有界批次 + 只更新真正变化的行 + 批量 UPDATE，并给 `emotion_updated_at` 加索引。

- [medium] [u05] [plan] P04 — 每天两次统计当日聊天量要扫整张消息表。
  详细问题：message 表无 created_at 单独索引，日记候选查询退化为全表扫描
  位置：backend/lianyu-dao/src/main/java/com/lianyu/dao/mapper/ConversationMapper.java:37 · 证据：查询含 `JOIN message m ... AND m.created_at >= #{since} GROUP BY c.id ...`；调用方 CharacterDiaryService.java:164-168，调度为 :135 `0 0 22 * * *` 与 :141 `0 30 23 * * *`。message 现有索引 `uk_conv_seq`、`idx_conv_created (conversation_id, created_at)`、`idx_msg_conv_role_seq` 均以 conversation_id 前导，全库迁移无 created_at 单列索引。
  影响：`m.created_at >= since` 无法单独走索引，只能全表扫 message（最大表、content 为 MEDIUMTEXT）；每天两次把冷数据扫入 buffer pool，冲掉热聊天缓存页，随后在线聊天 IO 命中率下降。 · 修复：补 `INDEX idx_msg_created (created_at)`（或复用 P02 的 `conversation.last_message_at` 过滤），EXPLAIN 断言 range 扫描。

- [medium] [u01] [plan] P05 — 打开会话列表时把每条最后消息的完整正文都取回来，只为显示 120 字。
  详细问题：会话列表批量取 MEDIUMTEXT 全文，仅用于截断到 120 字符的预览
  位置：backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java:489 · 证据：list() 第 166-169 行取最多 200 个会话；第 489-517 行批量调 `selectLatestByConversationIds` / `selectLatestAssistantByConversationIds`；MessageMapper.java:16-67 两条 SQL 都 `SELECT ... m.content`（MEDIUMTEXT 整列）；第 522-544 行 `formatConversationPreviewSnippet` 最终只保留 120 字符。
  影响：单次列表最多取回 200 条完整正文，长回复场景可达数 MB，MySQL 需读溢出页、JDBC 逐行反序列化，侧边栏打开变慢且内存抖动。 · 修复：SQL 改 `SUBSTRING(m.content,1,200)`，需要全文的分支改按会话懒加载。

- [medium] [u06] [plan] P08 — 聊天记录越长，打开会话和收新消息越卡。
  详细问题：ChatPage 无虚拟滚动，messageTimeline 全量重建 + 每次变更全量 sort
  位置：frontend/src/pages/ChatPage.vue:650 · 证据：`messageTimeline` computed（650-720）在 messages 任何变更时全量重建，逐条执行 `expandAssistantForDisplay`（640-648，含 stripInnerThoughts 正则）；`mergePolledMessages`（965-1041）发现变化即 `sortMessagesInTimelineOrder()` 全量排序（961-963）；模板第 73 行 `<template v-for="item in messageTimeline" :key="item._key">` 直接渲染全部条目，无窗口化。
  影响：n 条消息时每次增量都是 O(n) 重建 + O(n log n) 排序 + O(n) VDOM diff 与布局，数千条后滚动/发送掉帧，流式结束回填也触发一次全量重算。 · 修复：引入虚拟滚动、按消息缓存派生项、按 seq 二分插入代替全量排序；3000 条数据下断言单条新增 <16ms。

- [medium] [u06] [plan] P09 — 聊天窗口每 10 秒重下最近 50 条消息，没新消息也照旧。
  详细问题：会话轮询固定拉取最近 50 条全文且无增量游标，隐藏窗口也不停止
  位置：frontend/src/pages/ChatPage.vue:1203 · 证据：`getMessages(convId, { limit: MESSAGE_PAGE_SIZE }, { silent: true })` 只带 limit、无 afterSeq/游标；`startConversationPolling()`（1182-1187）为 10s setInterval，只在 onUnmounted 清理（801-808），全文件无 visibilitychange 处理；开场另有 600/1500/3500ms 三次 burst（1150-1155）与 2s×90s 的 fastPoll（1157-1173）；QuickChatPage.vue:363-372 同样 10s 轮询。
  影响：单窗口每分钟约 300 条正文的下载与解析（叠加 P05 的全文取回），后台窗口继续轮询，多窗口并存时请求量与主进程解析开销成倍上升，是弱网延迟与耗电主因。 · 修复：后端支持 afterSeq 增量，前端隐藏时暂停/降频并在恢复可见时补拉一次。

- [low] [u07] [plan] P06 — 记忆检索逐条向 Redis 取向量，50 条候选 50 次往返。
  详细问题：vector_cache 逐条串行 Redis GET/SET，N 次往返叠加在对话回合内
  位置：backend-agent/app/memory/vector_cache.py:59 · 证据：`for i, text in enumerate(texts): vector = await cache_get_json(_text_key(text))` 每条一次 GET，缺失项再 `for ...: await cache_put_json(...)` 逐条 SET；`rank_by_vector`（77-109）先 `embed([query])` 再取向量，cosine（31-44）为纯 Python 点积 O(1024)×N；N 上限 `memory_vector_max_candidates=50`（config.py），db.py 只有单键读写、无 MGET/pipeline。调用方 `tools/memory_search_tool.py` 的 search_semantic。
  影响：每次语义检索在对话回合内串行叠加最多 50 次 RTT（跨可用区 1-2ms 则 50-100ms），未命中再叠 50 次 SET，直接推迟首字时间。 · 修复：db.py 增加 cache_mget/cache_mset，向量读写各合并为一次往返，cosine 改 numpy 批量。

- [low] [u06] [plan] P10 — 往上翻聊天记录时消息数组只增不减，越翻越占内存。
  详细问题：loadOlderMessages 每页 50 条前插且无上限，messages 数组无限增长
  位置：frontend/src/pages/ChatPage.vue:908 · 证据：`loadOlderMessages`（908-949）以 50 条/页 unshift 进 `messages.value`，无总条数上限也无旧页回收；该数组同时驱动 `messageTimeline` 全量重建（650-720）与 `sortMessagesInTimelineOrder` 全量排序（961-963）。
  影响：长时间连续上翻后可达数千条，与 P08 叠加导致内存与帧率持续恶化，桌面端常驻窗口不会自动释放。 · 修复：设定保留上限（如 500 条）+ 按 beforeSeq 回取，与虚拟滚动共用同一窗口。

### 已查未发现问题的区域

- data-access：`ConversationService.getMessages`（431-457）已 `Math.min(200, ...)` 且取 `LIMIT safeLimit+1`；`MomentsService.listFeed`（99-148）限 50 并批量取角色/用户；`MomentsCommentService`（48）限 500；`NotificationService`（229-232）限 100；`SquareCommentMapper`（15-35）用 MySQL 8 窗口函数取每组前 N；`exportChatTranscript.fetchAllConversationMessages`（85-123）有页数上限 + beforeSeq 分页 + 去重排序。Java 全量 `for` 循环内数据访问扫描仅 15 处命中，除本报告所列外均为冷路径（删角色循环 delete）或纯 DTO 转换。
- runtime：`backend-agent/app/**/*.py` 无 `time.sleep` / 同步 `requests` / `subprocess` 命中（全 async）；`main.py:32-65` lifespan 只启动 relay 循环；Tomcat 50 / Hikari 20 / scheduling 4 的配置与当前并发量匹配。
- frontend：`api/httpCore.js`（148 行）有单飞刷新与 60s 超时，无重复刷新风暴；`useChatScroll` 为 passive 监听；`assistantStreamDrain.js` 先缓存再一次性回填，不存在逐 token 重算时间线。
- memory-startup：`embed()`（`memory/embedding.py:27-73`）走远程 HTTP，不加载本地模型；`PetVoiceRegistry.@PostConstruct`（67-84）只读一个小 classpath JSON；Electron 主进程无同步大文件读取阻塞启动。

## 三、未验证与抽样说明

1. **未做真机/生产压测**：P01、P02、P03、P04 的成本基于代码语义与索引现状推导（Explorer C 级：静态界 + 明确公式），未连生产库跑 EXPLAIN 或压测；建议按各条 `tests` 字段补齐实测。
2. **前端体积为实测、帧率为推断**：dist 体积与 chunk import 关系是实测数据；P08/P09 的帧率与耗电影响未在真机 profile，属按消息数推导。
3. **u02/u03/u04/u08/u09/u10/u11/u12 为 sampled**：这些单元做了全量模式扫描与抽样阅读，不能视作逐文件全文审查；若要全量结论需补读。
4. **测试环境有两处未跑通**：见下节，`pytest` 与 `admin` 的失败均来自环境缺依赖，不代表用例失败。

## 四、测试执行

| 命令 | 结果 | 说明 |
| --- | --- | --- |
| `npm --prefix admin test` | failed | `'vitest' 不是内部或外部命令`，`admin/node_modules` 不存在，用例未真正执行（环境未装依赖） |
| `mvn -q -f backend/pom.xml test` | passed | exit 0，输出仅预期 WARN，末尾 AgentWiringHealthCheck 显示各端口已 wired |
| `pytest -q backend-agent` | failed | 仓库根执行 48 个 collection error（`ModuleNotFoundError: No module named 'app'`）；切到 backend-agent 目录仍 26 个 error，首个根因 `app/db.py:20: import aiomysql` → 环境缺 `aiomysql` |
| `npm --prefix frontend test` | passed | `Test Files 76 passed (76)`、`Tests 460 passed (460)`，vitest 5.05s |

## 五、建议修复顺序

1. **P01（先止血）**：群聊死锁会让功能整体不可用且只能重启，改动集中在两个类，风险可控。
2. **P02 + P04 + P03**：三条数据库侧问题共享「加索引 / 分页 / 冗余列」的手法，建议合成一次迁移 + 一次调度改造，按 P02 → P04 → P03 顺序做（P02 的 `last_message_at` 可直接解掉 P04 的表扫描）。
3. **P07 + P09**：前端包体与轮询是用户可感知的两大成本，P07 改构建配置、P09 改协议，可并行。
4. **P08 + P10**：虚拟滚动与数组上限必须一起做，否则单独裁剪数组会破坏滚动位置。
5. **P05 + P06**：低风险收尾项，随相应接口/模块的下一次改动顺带完成。

## 六、跨专家转交项

- **correctness**：P01 除了性能问题，也是并发正确性问题（回合无超时、无降级路径），建议一并确认群聊回合的失败语义与 SSE 收尾行为；`CharacterStateService.decayAllEmotions` 在同一事务内逐行写，若中途异常是否整体回滚也值得核对。
- **reliability**：P02/P03/P04 的定时任务均无耗时监控与超时告警，P01 的线程池无拒绝策略；建议补任务耗时埋点、队列深度指标与线程池饱和告警。
- **security**：本次未审，但 P05 涉及把消息正文整列取回，若后续改为 `SUBSTRING` 需确认不影响任何做敏感过滤的逻辑。
- **maintainability**：`RelationshipStateService` 已有正确的分页衰减范式，`CharacterStateService` 未对齐，属可复用的收敛点。
