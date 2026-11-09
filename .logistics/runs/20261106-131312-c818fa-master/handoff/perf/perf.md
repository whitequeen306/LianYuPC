# 性能审查报告

> 专家：performance-analyst（perf） · 模式：project（全量审查，非 diff）
> 工作区：`C:/Users/hp/Desktop/LianYu-PC` · 运行：`20261106-131312-c818fa-master`
> 覆盖：1108 个文件 / 140294 行 / 12 个审查单元 / 144 个核心文件
> 结论：8 条发现（high 1 / medium 4 / low 3），最值得先修的是「模型网关每次调用新建 httpx 客户端」。

## 一、范围与覆盖

审查对象是整个项目，按 `scope.json` 的 12 个单元逐单元推进：仓库内没有 `rg`，先用 python 做跨文件模式扫描（新建 HTTP 客户端、`Thread.sleep`/`setInterval`、mapper 调用、缓存写入、循环内 IO），再对每个单元按「核心文件优先 + 按体积取前 32 个」做结构摘要，关键文件逐行精读并回读调用方确认真实触发路径。

| 单元 | 范围 | 状态 | 读过文件 | 说明 |
|---|---|---|---|---|
| u01 | backend/lianyu-service (1/3) | reviewed | 32/120 | 精读 GroupChatService / ConversationService / ProactiveChatScheduler / ProactiveMessageService / CharacterDiaryService，产出 runtime 1 条 |
| u02 | backend/lianyu-service (2/3) | reviewed | 32/120 | 精读 MomentsCommentService / MomentsService / VoiceCallDuplexSession / SentenceSplitter / ChatTurnFacade，产出 data-access 1 条 |
| u03 | frontend/src (1/2) | sampled | 32/120 | 结构扫描 + 精读 MemoryBrainMap.vue / AppDock.vue / useVoiceRecorder.js / memoryBrainModel.js，未发现单独可报热点 |
| u04 | scripts (1/2) | sampled | 32/120 | 脚本与生成器，不在运行时热路径 |
| u05 | backend/lianyu-dao | reviewed | 32/110 | 12 个 core 迁移 + 全部 mapper 逐条核对索引与 SQL 形状，产出 data-access 1 条 |
| u06 | frontend/src (2/2) | reviewed | 32/103 | 精读 ChatPage.vue / main.js / settings.js / LauncherPage.vue / QuickChatPage.vue，配合 dist 产物实测，产出 frontend 2 条 + memory-startup 1 条 |
| u07 | backend-agent | reviewed | 32/102 | 精读 gateway.py / chat_turn.py / tts.py / stream_chat.py / data_readers.py / http_client.py 并做连接计数实测，产出 hot-path 2 条 |
| u08 | backend/lianyu-web | reviewed | 32/71 | 精读 AgentRuntimeClient（单例 WebClient + SsrfPinningClientFactory + 显式超时）/ ChatTurnAdapter / AgentSseParser / VoiceDuplexHandler，出站复用正常 |
| u09 | frontend/electron | sampled | 32/68 | 结构扫描 + 精读 desktopObserver.js / qqBridgeCoordinator.js / updater.js 片段，产出 memory-startup 1 条 |
| u10 | 其他模块（(root)、.github、admin 等） | sampled | 32/67 | 结构扫描（vite.config.js、electron-pack.mjs、nginx.conf、docker-compose.yml、根 pom） |
| u11 | backend 小模块合集 | sampled | 32/59 | 多为测试代码，不构成生产性能面 |
| u12 | 其他模块（lianyu-app / common / 安装器等） | sampled | 32/48 | WPF 安装器 + 工具类结构扫描，无热路径 |

抽样口径：`reviewed` = 读了该单元关键文件并做了针对性检查；`sampled` = 因体量抽样，但已做模式扫描与结构摘要。每个单元列出的 32 个文件路径见 `handoff/perf/perf.json` 的 `units`。

测试基线（逐条）：

| 命令 | 状态 | 说明 |
|---|---|---|
| `npm --prefix admin test` | not-run | `admin/` 下没有 `node_modules`，只读审查未执行安装 |
| `mvn -q -f backend/pom.xml test` | passed | 5 个模块 surefire 报告聚合 531 用例，0 failure / 0 error |
| `pytest -q backend-agent` | passed | 1157 passed in 9.54s |
| `npm --prefix frontend test` | passed | 75 个测试文件 / 459 用例全绿（`-- --run` 串行执行） |

## 二、发现（按严重度排序）

- [high] [u07] [plan] PERF-01 — 每回合和模型服务商重新握手一次，首字延迟被固定拉长
详细问题：模型网关每次调用都新建 httpx 客户端，每个回合多付一次 TCP/TLS 握手
位置：backend-agent/app/gateway.py:296 · 证据：`if self._client is not None: client_cm = _null_context(self._client) else: client_cm = new_client()`；流式分支 gateway.py:427 同样 `async with new_client() as client:`；`get_gateway()`（gateway.py:489-490）永远 `return ModelGateway()`，构造参数从未注入。本地 ThreadingHTTPServer 统计 accept：3 次 `get_gateway().chat_messages(...)` 新增 3 条 TCP；改用 `ModelGateway(client=shared_client("perf-repro"))` 后 3 次只新增 1 条。仓库自述（http_client.py:47-54）：「境内到阿里云 300-400ms…冷首包 2690ms vs 热 314ms」「每句一次 POST，重新握手 150-300ms」。
影响：每个对话回合（含工具循环多轮、标题生成、主动消息）首 token 前固定多付一次握手，境内 150-300ms；高峰期短连接堆 TIME_WAIT、连接数与 TLS CPU 上涨 · 修复：gateway 构造器默认 `shared_client("model-gateway")`，427 行改为复用 `self._client`，补「3 次调用只 1 条 TCP」的计数测试（steps 见 JSON）。

- [medium] [u07] [plan] PERF-02 — 记忆搜索与语音合成同样每次重连，一次「回忆」最多握手三次
详细问题：记忆检索与 TTS 合成逐次 `new_client()`：一次记忆工具调用最多 3 次握手，一句语音 2 次
位置：backend-agent/app/memory/vector_cache.py:95 · 证据：vector_cache.py:95 `await embed([query])` → embedding.py:47-49 在 client 为 None 时 `async with new_client() as own`；memory_search_tool.py:98 再 embed 一次、:139 `rank_by_vector(...)` 内部（vector_cache.py:95）又 embed 一次；语音侧 tts.py:183（合成请求）与 tts.py:211（音频下载）各 `async with new_client() as client:`。对照 tts.py:298 的流式合成已用 `shared_client("dashscope-tts")`。
影响：每次记忆检索多付 2-3 次握手（约 300-900ms），每句非流式语音多付 2 次（约 300-600ms）；语音通话逐句合成延迟叠加 · 修复：embed / rerank / TTS 统一走 `shared_client`，并把同一 query 的重复 embed 合并为一次。

- [medium] [u05] [plan] PERF-03 — 后台每半分钟把全站单聊会话翻一遍并按子查询排序
详细问题：主动聊天扫描每 30s 全表扫 conversation，并用相关子查询表达式排序（无法走索引）
位置：backend/lianyu-dao/src/main/java/com/lianyu/dao/mapper/ConversationMapper.java:25 · 证据：`WHERE c.mode = 'SINGLE' AND c.character_id IS NOT NULL ORDER BY COALESCE((SELECT MAX(m.created_at) FROM message m WHERE m.conversation_id = c.id), c.created_at) DESC LIMIT #{limit}`，无 user_id 过滤，排序键是相关子查询、LIMIT 无法下推；调用者 ProactiveChatScheduler.java:68 位于 `@Scheduled(fixedDelayString="${lianyu.chat.proactive.scan-interval-ms:30000}")`（:62-64），scan-limit 默认 40（:59-60）。静态成本：C 行全扫 × 每行 1 次 message 索引探测 + C log C filesort，C = 全站 SINGLE 会话数。
影响：每 30s 一次 O(C log C) 查询，C 随注册用户线性增长；十万级会话时单次数百毫秒并长期占一个 DB 连接，挤占 message 读写 · 修复：物化 `conversation.last_message_at` + 复合索引，按 `last_message_at` 排序并加活跃用户过滤，EXPLAIN 确认无 filesort。

- [medium] [u01] [plan] PERF-04 — 群聊气泡之间的「停顿」是把线程挂起来做的
详细问题：群聊气泡节奏用 Thread.sleep 占住 group-chat 线程池线程
位置：backend/lianyu-service/src/main/java/com/lianyu/service/conversation/GroupChatService.java:588 · 证据：GroupChatService.java:586-593 与 :721-731 在群聊执行器线程里 `Thread.sleep(bubbleGapMs)`，默认 `lianyu.group.bubble-gap-ms:500`（:92-96）；执行器 GroupChatExecutorConfig.java:12-24 core 8 / max 20 / queue 300；一轮群聊对每个成员一个 CompletableFuture（:395-399），maxGroupMembers=4（:90-91）。一轮 4 成员 × 每成员 b 条 ≈ 4×(b-1)×500ms 纯等待。
影响：并发 K 个群聊瞬时占用约 4K 线程做纯等待，超过 core=8 即排队，队列 300 满后按拒绝策略丢弃，表现为「群里半天不冒泡」；DB 插入与广播被串在 sleep 之间 · 修复：先批量落库并给每条气泡带 `revealAt` 时间戳，前端按时间排期；必须服务端控速时改用 `ScheduledExecutorService.schedule`。

- [medium] [u06] [plan] PERF-05 — 每个窗口都要先加载整套 UI 组件库和 400KB 样式
详细问题：全量引入 Element Plus：首屏 408KB 阻塞 CSS + 1.14MB 公共 chunk，实际仅用 49 种组件
位置：frontend/src/main.js:3 · 证据：main.js:3-5 全量 `import ElementPlus from 'element-plus'` + `element-plus/dist/index.css` + dark css-vars 并 `app.use(ElementPlus, ...)`；vite.config.js:91-96 manualChunks 只拆 vue/pinia/vue-router/vue-i18n；vite.config.js:71 `cssCodeSplit: !isElectronPack`（打包构建合并为单 CSS）。dist 实测：main-DTgPoq__.css 408,299 B（`.el-` 5777 处），index.html 同步 `<link rel="stylesheet">`；launcherBootstrap-CEXfa8Qx.js 1,144,809 B 被 45 个 chunk 引用，入口 main-DEJM46Qu.js 仅 32,779 B；assets JS ≈2.0MB / CSS 677KB；src 实际用到 49 种 el-* 组件（el-button 209 次、el-icon 166、el-form-item 121）。
影响：每个窗口首帧前解析 408KB CSS 并求值 1.14MB 公共块（file:// 加载，成本在解析/求值），三个常驻窗口各付一次，启动更慢、内存更高 · 修复：`unplugin-vue-components + ElementPlusResolver` 按需引入，组件库拆独立 async chunk，目标首屏 CSS < 150KB。

- [low] [u02] [plan] PERF-06 — 为显示「几条评论」把评论正文整行读回来
详细问题：动态评论数用 selectList 拉全部评论行在内存里计数
位置：backend/lianyu-service/src/main/java/com/lianyu/service/moments/MomentsCommentService.java:190 · 证据：MomentsCommentService.java:186-197 `momentsCommentMapper.selectList(...in(MomentsComment::getPostId, postIds))` 后 `rows.forEach(r -> map.merge(r.getPostId(), 1, Integer::sum))`，只为计数却取回 content 等全部列。
影响：一页 N 条动态、合计 M 行评论时传输与反序列化是 O(M) 而非 O(N)，M 可达数百行文本 · 修复：mapper 增加 `GROUP BY post_id` 的 `countByPostIds`，只回传 post_id 与 count。

- [low] [u06] [plan] PERF-07 — 聊天窗消息只增不减，每来一条就整表重建
详细问题：ChatPage 消息无限累积 + 每次变化整表 spread 重建，且列表无虚拟滚动
位置：frontend/src/pages/ChatPage.vue:650 · 证据：:650-708 `messageTimeline` computed 对每条消息 `{ ...msg }` 重建并二次分组；:908-949 `loadOlderMessages` 每次前插 50 条无上限；:1524-1545 `syncStreamingAssistantBubbles` filter + spread 重建整表；:1182-1231 每 `NORMAL_POLL_MS=10000`（:589）拉 `MESSAGE_PAGE_SIZE=50`（:484）；模板 :73 `v-for="item in messageTimeline"` 无虚拟滚动。单次 O(n) 分配 + 全列表 patch，n 可无限增长。
影响：长会话（n 上千）时任意轮询或流式更新触发 O(n) 分配与整表 diff，输入卡顿、滚动掉帧；内存随会话时长单调增长 · 修复：timeline 按 id 增量维护、限制内存窗口（如 500 条）、列表虚拟化、隐藏/流式中跳过轮询。

- [low] [u09] [plan] PERF-08 — 桌宠与快捷聊小窗各自加载整份应用壳
详细问题：桌宠 / 快捷聊窗口入口仅 706B / 1.2KB，却各自加载 1.14MB 公共应用壳
位置：frontend/electron/main.js:618 · 证据：main.js:610-637 `loadRoute` 对 `/launcher` 载 launcher.html、`/quick/*` 载 quick.html；dist/launcher.html 入口 launcher-BE_O1lcz.js 仅 706 B、quick.html 入口 quick-DUTbY0TJ.js 仅 1,224 B，但两者都 import 同一个 launcherBootstrap-CEXfa8Qx.js（1,144,809 B）与 vue-vendor-Ba5glg1p.js（194,735 B），该公共块内含 element-plus / ElMessage 符号。
影响：常驻桌宠与快捷聊窗口各持一份完整应用壳（解析后堆占用更高），每次开小窗都要走一遍 V8 解析/求值，桌宠长期驻留不释放 · 修复：为 launcher/quick 建独立入口只共享最小 runtime，把 Element Plus 从公共 chunk 移出，用 `app.getAppMetrics()` 对比私有内存。

## 三、已查未发现问题（避免误以为漏查）

- 消息与会话数据访问：message 表 `uk_conv_seq(conversation_id, seq)` + `idx_conv_created` + V46 `idx_msg_conv_role_seq` 齐备；会话列表 last-message 走 `MessageMapper.selectLatestByConversationIds` 的批量 JOIN 而非 N+1；`getRecentMessages` 带 LIMIT；动态流、角色最近活动均批量取数且进 Redis 缓存。
- Java 出站客户端：`AgentRuntimeClient` 在构造时建单个 `WebClient`（SsrfPinningClientFactory + 显式超时），无每请求新建；`AgentToolCatalog` 失败降级有日志。
- backend-agent 阻塞面：无 `time.sleep` / `requests` / 同步 redis / pymysql / subprocess，出站统一 `httpx.AsyncClient` 且带超时。
- 前端流式与动画：`assistantStreamDrain.js` 只在流结束返回整段文本，不逐 chunk 触碰响应式状态；`MemoryBrainMap.vue` 的 rAF 主循环在 `document.hidden` / `onDeactivated` 时停，pulses/sparks/floaters 有上限；QqBridge 日志轮询主进程截断 500 行。
- 扫描到的其余 `synchronized`（VoiceCallDuplexSession / VoiceDictationSession / AgentToolCatalog / WebPushService / SquareLikeService / VoiceDuplexHandler）未发现跨网络调用的长临界区。

## 四、未验证与抽样说明

- u03 / u04 / u09 / u10 / u11 / u12 为 **sampled**：做了跨文件模式扫描与 32 个文件的结构摘要，部分文件只看了签名与关键行，不能声称「全量已读」。
- 未做真机性能剖析：Electron 首帧耗时、DB 慢查询日志、JVM 堆快照均未采集；PERF-05/07/08 的收益以「目标阈值」写在 `fix.tests` 里，需要改造后复测。
- PERF-03 的全表扫描规模 C 取自 schema 推断（无生产数据），量级结论依赖实际会话数。
- 未开启桌面感知/QQ 桥的实机链路，`desktopObserver` 截图与推送相关开销仅按代码推断，已放入 `deferred`。

## 五、建议修复顺序

1. **PERF-01（模型网关连接复用）**：改动最小、每次对话都受益，先做；用连接计数与首包 p95 复测。
2. **PERF-02（embedding/TTS 客户端复用）**：与 PERF-01 同一范式，随其一并改，顺手合并重复 embed。
3. **PERF-03（conversation 物化 last_message_at）**：需要迁移与回填，安排一次窗口期，先加索引再改查询。
4. **PERF-04（群聊去 sleep）**：涉及前后端协议（revealAt），与群聊体验改造一起排期。
5. **PERF-05 / PERF-08（Element Plus 按需 + 小窗独立入口）**：同属打包改造，一次做完并对比 chunk 体积与窗口内存。
6. **PERF-07（聊天列表增量/虚拟化）**、**PERF-06（评论数 GROUP BY）**：收益明确但优先级低，随列表体验优化一起做。

跨专家转交项：

- 服务端 `lianyu-service/src/main/resources/pet-voice-samples` 内 32MB WAV 随 jar 打包（镜像与部署时长）→ maintainability / 构建优化。
- 群聊线程池队列满后的拒绝处理是否静默丢消息 → reliability（本次只从容量角度报告）。
- `WebPushService` 每次推送新建 `CloseableHttpClient`、`SquareLikeService` 每 30s 全量重算 → 已记入 `deferred`，留给后续 perf 复测或 reliability 评估。
