# 后勤主编材料 · 20261106-131312-c818fa-master

> 这是给主会话模型（主编）的候选材料，不是最终报告。
> 去重、定级、取舍、模块组织由主编决定；机器只负责收集与校验。

## 运行
- 根目录：`C:/Users/hp/Desktop/LianYu-PC`
- 范围：1108 个文件 / 12 个审查单元 / 140294 行
- 测试命令（4 个模块）：
  - `npm --prefix admin test`（package.json:admin）
  - `mvn -q -f backend/pom.xml test`（maven:backend）
  - `pytest -q backend-agent`（pytest:backend-agent）
  - `npm --prefix frontend test`（package.json:frontend）

## 覆盖
- 预期单元 / 专家：correctness、security、reliability、maintainability、perf
- 已收到：correctness、security、reliability、maintainability、perf
- 缺失：（无）
- 无效：（无）
- 失败：（无）
- partial：（无）

## 单元覆盖矩阵

| 单元 | 标签 | correctness | security | reliability | maintainability | perf |
| --- | --- | --- | --- | --- | --- | --- |
| u01 | backend/lianyu-service (1/3) | reviewed(32) | reviewed(36) | reviewed(34) | reviewed(36) | reviewed(32) |
| u02 | backend/lianyu-service (2/3) | reviewed(30) | reviewed(34) | sampled(34) | reviewed(36) | reviewed(32) |
| u03 | frontend/src (1/2) | reviewed(34) | reviewed(30) | reviewed(34) | sampled(36) | sampled(32) |
| u04 | scripts (1/2) | sampled(30) | sampled(30) | sampled(34) | reviewed(36) | sampled(32) |
| u05 | backend/lianyu-dao | reviewed(63) | reviewed(31) | sampled(34) | reviewed(36) | reviewed(32) |
| u06 | frontend/src (2/2) | sampled(54) | reviewed(30) | sampled(34) | sampled(36) | reviewed(32) |
| u07 | backend-agent | reviewed(32) | reviewed(30) | reviewed(34) | reviewed(36) | reviewed(32) |
| u08 | backend/lianyu-web | reviewed(32) | reviewed(30) | reviewed(34) | sampled(36) | reviewed(32) |
| u09 | frontend/electron | reviewed(34) | sampled(31) | sampled(34) | reviewed(36) | sampled(32) |
| u10 | 其他模块（(root)、.github、admin 等） | sampled(31) | sampled(31) | sampled(34) | reviewed(36) | sampled(32) |
| u11 | backend（小模块合集 59 文件） | sampled(31) | reviewed(39) | sampled(34) | sampled(36) | sampled(32) |
| u12 | 其他模块（backend/lianyu-app、backend/lianyu-common、backend 等） | sampled(37) | sampled(30) | sampled(34) | sampled(36) | sampled(32) |

## 专家摘要
### correctness · ok
- 摘要：对 1108 个文件 / 12 个审查单元做了正确性审查，覆盖 logic、state、concurrency、data-integrity、contracts、architecture、edge-cases 七个面，共 12 条发现（1 high / 9 medium / 2 low）。最严重的一条是 @Transactional 标在 protected 同类直调方法上导致广场角色、语音通话、日记三处多写没有事务；其余集中在记忆写入链路（逐条事务 + Java 兜底重复写、outbox 无自动恢复、Python 写库与只读口径冲突）、消息 seq 只存 Redis、关系状态机（未生效的修复也发里程碑、无锁读改写）与前端会话状态（刷新瞬断即登出）。
- 专家报告：`C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261106-131312-c818fa-master/handoff/correctness/correctness.md`（15958 字符）
- sections：logic=2 · state=2 · concurrency=2 · data-integrity=3 · contracts=1 · architecture=1 · edge-cases=1
- 测试证据：
  - `npm --prefix admin test`：passed（exit 0；admin 端（vite + vitest）用例全绿。）
  - `mvn -q -f backend/pom.xml test`：passed（exit 0；按 backend/**/target/surefire-reports 汇总 123 个报告 / 531 用例 / failures=0 / errors=0 / skipped=0。）
  - `pytest -q backend-agent`：failed（collection error：ModuleNotFoundError: No module named 'respx'（48 个测试模块无法收集）。属本次环境缺依赖，不是产品缺陷；按只读约束未安装依赖，故 backend-agent 的行为只能靠代码审读与既有用例文件交叉验证。）
  - `npm --prefix frontend test`：passed（以 CI=1 运行，exit 0；75 个测试文件 / 459 个用例全绿（7.03s）。）
- notes：只读审查，未修改任何源码。审查为 project 模式：u01/u02/u03/u05/u07/u08/u09 单元内关键文件逐文件读过；u04/u06/u10/u11/u12 为抽样（大文件与一次性运维脚本较多），抽样方式与已读文件已在 units.files 列全。backend-agent 的 pytest 未能跑起来（缺 respx），JSON 的 tests 里如实登记为 failed。
- 发现数：12
- 转交其他专家：3 条
  - → 未指定 · backend-agent 运行时行为未能实跑：pytest 因缺 respx 无法收集用例，本轮对 Python 侧的结论全部来自代码审读与调用链追踪，未做运行时复现。
  - → 未指定 · 关系里程碑与 V57 的数据修复是否一致：V57 只回写 trust_score，未回写 phase 与 relationship_milestone；线上是否存在「高分里程碑 + 30 分信任」的存量行需要查生产库，本轮只有代码证据。
  - → 未指定 · 管理台首页展示的是硬编码数据：admin/src/App.vue 内联了「API 在线 / 运行正常 / 最新客户端 v0.2.363 / 待处理 12」等固定文案，未接 /api/admin/v1/overview；是否属产品已知占位待确认，故未列入正式发现。

### security · ok
- 摘要：整仓 12 个审查单元逐单元过了一遍安全面：最严重的是 Python agent 的 /agent/** 在网关侧完全没有鉴权（可匿名读取任意用户的记忆与关系），其次是管理台登录锁定失效且无限流、生产口令与平台 API Key 密文进了仓库、NapCat 桥接令牌被写死成常量。认证/授权主体链路（Sa-Token、归属双条件查询、上传重编码、Electron 沙箱与更新器签名）加固基本到位。
- 专家报告：`C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261106-131312-c818fa-master/handoff/security/security.md`（23783 字符）
- sections：auth=5 · authz=0 · injection=0 · browser=1 · uploads=1 · secrets=[REDACTED:secret] · crypto=1 · exposure=2 · business-logic=0 · supply-chain=0
- 测试证据：
  - `npm --prefix admin test`：failed（admin/node_modules 未安装：`> vitest --passWithNoTests` → `'vitest' 不是内部或外部命令`（exit 1），属环境未安装依赖，非用例断言失败。）
  - `mvn -q -f backend/pom.xml test`：passed（exit 0，仅 WARN 级 fallback 日志，无失败用例。）
  - `pytest -q backend-agent`：failed（collection 阶段 48 errors，根因 `ModuleNotFoundError: No module named 'app'`（仓库根调用时模块路径不在 sys.path）；改在 backend-agent 目录 `python -m pytest -q` 仍 26 errors，根因 `ModuleNotFoundError: No module named 'aiomysql'`（依赖未安装）。属环境缺依赖，未验证到用例级结果。）
  - `npm --prefix frontend test`：passed（Test Files 75 passed / Tests 459 passed，exit 0。）
- notes：只读审查：未修改任何源码。运行目录下临时脚本（_sec_units.py/_sec_read.py/_sec_scan.py/_sec_emit.py）与测试日志（_test_*.log）在交付后删除。安全耦合面的部署假设：外网只能经 api-gateway 进入（docker-compose 中 backend/mysql/redis 均绑 127.0.0.1），因此 agent 直连风险按「网关已暴露 /api/agent/」判定；若生产网关未启用该 location，F1 的严重度可降为 high。未做动态验证（无运行中的实例），所有结论来自源码与配置的静态路径。
- 发现数：12
- 转交其他专家：4 条
  - → reliability · backend-agent 的 pytest 在仓库根无法收集（ModuleNotFoundError: app / aiomysql），CI 门禁依赖未落地，建议由可靠性专家确认测试环境链路。
  - → reliability · admin 前端未安装依赖导致 `npm --prefix admin test` 无法执行，建议纳入 CI 安装步骤。
  - → correctness · /ws/voice 首帧认证与 VoiceDuplexHandler 的状态机在断线重连、超时关闭上的边界行为未做黑盒验证。
  - → maintainability · deploy/api-gateway/nginx.conf 中 X-Forwarded-For 写法在 15 处重复，改口径时容易漏改。

### reliability · ok
- 摘要：覆盖 12 个单元、每单元 34 个文件（共 408 个文件路径，含全部 144 个 core），逐单元做了跨文件模式扫描并全文读了 SSE 转发链路、Python 网关韧性层、前端流式发送与门禁配置。发现 10 条可靠性问题：最严重的是断流时把半截回复当完整回复落库（失败报成功）；其次是客户端取消被计入上游熔断（已用脚本复现，5 次断连即可 OPEN）、流式发送失败重试无幂等键导致重复入库与重复计费。另有零超时客户端分支、后台任务未知类型静默 ack、客户端断开后不取消上游、后台失败只落 debug，以及三项测试门禁问题（前端门禁并行 flaky、admin 不在 CI、断流/重试路径无回归测试）。Java 全量测试与 backend-agent 测试通过；前端门禁在机器满载时红、空闲时绿，说明门禁本身不稳定；admin 测试因未安装依赖未能执行。
- 专家报告：`C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261106-131312-c818fa-master/handoff/reliability/reliability.md`（10753 字符）
- sections：error-handling=2 · resilience=4 · observability=1 · tests=3
- 测试证据：
  - `npm --prefix admin test`：not-run（admin/node_modules 未安装，直接报 `'vitest' 不是内部或外部命令`；按只读约束未安装依赖。脚本本身为 `vitest --passWithNoTests`，仓库内仅有 admin/src/__tests__/menu.test.js 一个 8 行用例，且 CI 无 admin job。）
  - `mvn -q -f backend/pom.xml test`：passed（BUILD SUCCESS，退出码 0；backend/*/target/surefire-reports 共 123 份报告，lianyu-service 汇总 Tests 365 / Failures 0 / Errors 0 / Skipped 0。）
  - `pytest -q backend-agent`：passed（系统 python3.11 缺 respx，直接跑有 48 个收集错误（test_voice_tts.py:12 import respx）；改用仓库内解释器 backend-agent/.venv/Scripts/python.exe -m pytest -q backend-agent → 1157 passed in 15.32s。建议门禁文档写明必须用 venv 解释器。）
  - `npm --prefix frontend test`：failed（同一 commit 两次结果不同：机器满载时 Test Files 5 failed | 70 passed (75)、Tests 8 failed | 451 passed (459)（wechatChannelRelease.test.js 两条断言/ENOENT，bootstrap、bootstrapRestoreRoute、launcherBootstrap 三条 5000ms 超时）；空闲时 75 files / 459 tests 全绿。属并行执行导致的 flaky，见 tests 章节发现。）
- notes：所有单元均实际读取 34 个文件（u01/u02 等大单元为抽样，见 units.notes）；u04/u05/u10/u11/u12 与运行时链路相关性弱，按抽样处理并在报告「未验证/抽样说明」中声明。未修改任何源码，临时扫描脚本已删除。
- 发现数：10
- 转交其他专家：3 条
  - → 未指定 · scripts/_check_yml.py 等运维脚本硬编码生产 IP 与 root 账号，建议交给安全专家评估。
  - → 未指定 · SsrfPinningClientFactory 的 IP 固定与 DNS 重绑定防护属安全域，本次只报超时缺失。
  - → 未指定 · backend-agent 的 prompt 文案与工具预算属正确性/可维护性域，未纳入本次结论。

### maintainability · ok
- 摘要：按 simplify / coupling / cleanup 三个 section 审完 12 个单元（1108 文件）：12 个单元全部检查，6 个 reviewed、6 个 sampled，无 skipped。共 11 条发现（simplify 3、coupling 2、cleanup 6），severity 上限为 medium（可维护性不 inflate）。最值得先做的是三件事：角色广场名册的两份权威（M-07，Java 2429 行常量与 Flyway 种子并存且无交叉校验）、admin 后台的空壳现状（M-03，表单无 save、窗口键失效、概览写死）、81 个一次性脚本入库且 72 个零引用（M-02）。零 AI 逻辑门禁是显式豁免而非失效，未据此报问题；前端与 Java 的测试结果已逐条记录。
- 专家报告：`C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261106-131312-c818fa-master/handoff/maintainability/maintainability.md`（17502 字符）
- sections：simplify=3 · coupling=2 · cleanup=6
- 测试证据：
  - `npm --prefix admin test`：failed（EXIT=1：`> lianyu-admin@0.2.363 test` / `> vitest --passWithNoTests` 后报 `'vitest' 不是内部或外部命令`。原因是 admin/ 下没有 node_modules（frontend/node_modules 存在），admin/src/__tests__/menu.test.js 因此未被执行；补 npm --prefix admin install 后应可运行。）
  - `mvn -q -f backend/pom.xml test`：passed（EXIT=0，`Tests run: 123, Failures: 0, Errors: 0, Skipped: 0` + BUILD SUCCESS，含 JavaAiLogicRatchetTest(8) 与 LegacyCommunityControllerTest(1)。）
  - `pytest -q backend-agent`：failed（EXIT=2，48 errors during collection，首因 `ModuleNotFoundError: No module named 'app'`（仓库根运行无 sys.path 注入）；cd backend-agent 后仍有 26 errors，根因是环境缺依赖：`No module named 'aiomysql'`(15 次)、`No module named 'respx'`(11 次)。属环境/测试基建问题，非源码断言失败，已转交 deferred。）
  - `npm --prefix frontend test`：failed（EXIT=1，默认并行：`Test Files 4 failed | 71 passed (75)`、`Tests 4 failed | 455 passed (459)`，4 个失败全部是 `Error: Test timed out in 5000ms`（src/auth/__tests__/bootstrap.test.js:29、bootstrapRestoreRoute.test.js:37、launcherBootstrap.test.js:19 及 updater 相关 1 个）。按 AGENTS.md §4 追加 `-- --no-file-parallelism` 后 `Test Files 75 passed (75)`、`Tests 459 passed (459)`。脚本口径漂移见 M-05。）
- notes：只读审查：未修改任何源码，未提交，只写本 handoff 目录。忽略 .logistics/ 与 docs/logistics/ 下由本工具产生的文件。范围外未报：.specstory/（35M 但 git ls-files 为 0，已被忽略）、顶层 ship-*.log（.gitignore:113 已忽略）、纯格式/命名/import 顺序。抽样边界见 units[].notes：1108 个文件全部打开过，其中 u03/u06/u08/u11/u12 未逐行通读，记 sampled。
- 发现数：11
- 转交其他专家：3 条
  - → correctness · backend/lianyu-web/src/test/java/com/lianyu/web/agent/JavaAiLogicRatchetTest.java:211 · Java 侧角色广场 2429 行人设 prompt 文本是否违反「Java 零 AI 逻辑」：JavaAiLogicRatchetTest.java:211-212 显式把 CharacterSquareCatalog 列入 BARE_PROMPT_SKIP_FILES（「数据搬家不在本棘轮范围」），属已登记的架构豁免；是否搬去 Python 是架构决策，不在可维护性判定范围。
  - → 未指定 · backend-agent/pyproject.toml · pytest -q backend-agent 在仓库根收集失败、缺 aiomysql/respx：属测试基建（无 conftest.py 注入 sys.path、依赖未装），不是重复/死代码口径内的问题。
  - → reliability · admin/electron/main.js · admin/electron/main.js 用 process.env.VITE_DEV_SERVER_URL 判 dev，electron:dev 未注入该变量：仅静态观察，未实际运行 admin 的 dev 模式，证据不足以单列 finding。

### perf · ok
- 摘要：按 12 个单元全量覆盖（每单元读 32 个文件、核心文件全覆盖），审查 1108 文件 / 140294 行。共 8 条发现：hot-path 2 条（模型网关与 embedding/TTS 逐次新建 httpx 客户端，实测 3 次调用 3 条 TCP；仓库自述握手 150-300ms）、data-access 2 条（主动聊天每 30s 全表扫 conversation 且按相关子查询排序；动态评论数用拉全行在内存计数）、runtime 1 条（群聊气泡用 Thread.sleep 占住 core=8 的线程池）、frontend 2 条（全量 Element Plus：408KB 阻塞 CSS + 1.14MB 公共 chunk；ChatPage 消息无限累积且整表重建）、memory-startup 1 条（桌宠/快捷聊窗口为 706B/1.2KB 入口加载整份应用壳）。最值得先修的是模型网关连接复用：改动小、每次对话都能省下一次握手。前端与群聊两条属于结构性改造，需排期。已实测核对的干净项：message 表索引齐全、会话列表 last-message 批量 JOIN、Java 侧 WebClient 单例复用、流式收包不逐 token 触发响应式更新。
- 专家报告：`C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261106-131312-c818fa-master/handoff/perf/perf.md`（10170 字符）
- sections：hot-path=2 · data-access=2 · runtime=1 · frontend=2 · memory-startup=1
- 测试证据：
  - `npm --prefix admin test`：not-run（admin/ 下没有 node_modules（仅有 package.json 的 vitest 脚本），只读审查不执行 npm install，故未跑。）
  - `mvn -q -f backend/pom.xml test`：passed（5 个模块 surefire 报告聚合 531 个用例，0 failure / 0 error / 0 skipped（lianyu-admin、lianyu-ai、lianyu-security、lianyu-service、lianyu-web）。）
  - `pytest -q backend-agent`：passed（backend-agent/.venv 下 1157 passed in 9.54s。）
  - `npm --prefix frontend test`：passed（以 `npm --prefix frontend test -- --run` 执行（避免 watch 模式）：75 个测试文件 / 459 个用例全绿，5.88s。）
- notes：方法：仓库无 rg，用 python 做跨文件模式扫描（http 客户端新建、Thread.sleep/setInterval、mapper 调用、缓存写入、循环内 IO），再对每单元按体积 + core 取 32 个文件做结构摘要，关键文件逐行精读（gateway.py、tts.py、memory_search_tool.py、ConversationMapper.java、ProactiveChatScheduler.java、MomentsCommentService.java、GroupChatService.java、GroupChatExecutorConfig.java、ChatPage.vue、main.js、vite.config.js、electron/main.js、AgentRuntimeClient.java）。证据分级：F1/F2 为 A（本地连接计数实测）、F3/F4/F5/F7 为 C（给出 C 行全扫、M 行评论、4K 线程、O(n) 重排的公式与规模）、F6/F8 为 B（dist 产物与 chunk 引用图实测）。抽样说明：u03/u04/u09/u10/u11/u12 为 sampled，只做了结构扫描与少量精读，结论不能视为全量已读。未做真机性能剖析（Electron 首帧、DB 慢查询日志），相关阈值以目标形式写在 fix.tests 中。
- 发现数：8
- 转交其他专家：5 条
  - → 未指定 · SquareLikeService 每 30s 在 synchronized 内全量拉 Redis ZSET 重算点赞数：单次数据量小（模板数级），影响被锁粒度限制；未测量到可复现的排队。
  - → 未指定 · WebPushService 每次推送 new CloseableHttpClient：推送频率低且为一次性对象，属冷路径抖动，未达 low 门槛。
  - → 未指定 · desktopObserver 每 30s 截图后去重（先截图再判重）：默认关闭/可配置，且有空闲暂停；缺少真实开启时的 CPU 占用数据，留待 reliability/perf 后续测量。
  - → 未指定 · VoiceCallDuplexSession 每个 SSE delta 做 llmEmitBuf.toString() 拼接：常量小、单次通话上限有限，未达到 low。
  - → 未指定 · 服务端 resources 内 32MB pet-voice-samples 音频随 jar 打包：影响镜像体积与部署时长，非运行时内存；属构建/部署优化，转交 maintainability。

## 候选 findings
### COR-E9A11 · [高] [u02] — 建广场角色、落语音通话记录、写日记这三件事中间任何一步失败，前面已经写进去的数据都不会撤回，会留下角色没有会话、通话只有半段、日记没有提醒这样的半成品。
- 简述：建广场角色、落语音通话记录、写日记这三件事中间任何一步失败，前面已经写进去的数据都不会撤回，会留下角色没有会话、通话只有半段、日记没有提醒这样的半成品。
- 详细问题：@Transactional 标在 protected 同类直调方法上，广场角色/语音通话/日记三处多写没有事务
- 专家 / section：correctness / data-integrity
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/square/CharacterSquareService.java:235`
- 置信度：medium · kind：direct
- 证据：CharacterSquareService.java:235-236 是 `@Transactional` + `protected CharacterResponse persistSquareCharacter(...)`，调用点在同文件 :231 `return persistSquareCharacter(...)`（同类自调用，不经过 Spring 代理）；方法体内依次 characterMapper.insert(:252) → squareAddCountService.incrementAndInvalidate(:261) → conversationService.create(:268)。同款写法另有两处：VoiceCallService.java:186-187（调用点 :167，方法体 :188/:198 两次 nextSeq 后插两条 message）、CharacterDiaryService.java:446-447（调用点 :378，:458 diaryMapper.insert、:465 notifyDiaryNew）。Spring 代理默认只增强 public 方法，且自调用根本不进入代理，两重原因使注解不生效；ProactiveMessageService.java:53 的类注释已把该机制写明，并在 :238 用 `self.persistProactiveReply(...)` 修复过同类问题。
- 影响：三条多写流程在第二步/第三步失败时留下不一致数据：广场角色已建但会话未建（用户点进去没有对话）、语音通话只存了用户句、日记已落库但没有通知；且因为这些方法标了 @Transactional，排查者会误以为已受事务保护。
- 修复摘要：让事务真的生效：把多写方法改成 public 并通过注入的自身代理或独立 bean 调用，或把事务边界上移到无外部调用的一方。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/square/CharacterSquareService.java、backend/lianyu-service/src/main/java/com/lianyu/service/conversation/VoiceCallService.java、backend/lianyu-service/src/main/java/com/lianyu/service/character/CharacterDiaryService.java、backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ProactiveMessageService.java
- 建议测试：mvn -q -f backend/pom.xml test；新增 CharacterSquareTransactionTest（失败注入，断言无孤儿角色）

### COR-07994 · [中] [u07] — 当这一轮携带的工具名一个都没注册上时，模型只会收到用户和角色说过的句子，角色设定、记忆、情绪提示全部消失，回复会跳出角色却没有任何报错。
- 简述：当这一轮携带的工具名一个都没注册上时，模型只会收到用户和角色说过的句子，角色设定、记忆、情绪提示全部消失，回复会跳出角色却没有任何报错。
- 详细问题：无工具分支丢掉 system prompt：模型只收到折叠后的对话文本
- 专家 / section：correctness / logic
- 位置：`backend-agent/app/tool_loop.py:128`
- 置信度：medium · kind：direct
- 证据：tool_loop.py:126-143 `specs = resolve_tools(tool_names)`，`if not specs:` 分支调用 `gateway.chat_sync(system=None, user_text=_flatten(messages))`；`_flatten`(:205-214) 明确跳过 role == 'system'，只把历史拼成「用户：/你：」文本。对照 app/graphs/proactive_turn.py:139-158 的无工具路径：它把 system 单独取出（`system = msg['content']`）再传入 chat_sync。resolve_tools 对未注册的工具名静默忽略；chat_turn.py:53-58 的 CHAT_TOOL_NAMES 目前 4 个名字都能在注册表命中（time_tool.py:87 / weather_tool.py:85 / memory_search_tool.py:175 / recent_activity_tool.py:188），所以现在是潜在缺陷：注册表改名（2026-10-25 删除 desk_* 就是此类漂移）或调用方传入未注册名（app/main.py:238 `tool_names=body.tools or None` 直接透传）都会走进该分支。
- 影响：走到该分支时人设、长期记忆、关系/情绪上下文全部丢失，模型以「无系统提示」的通用助手口吻回复，用户看到角色不像角色；由于没有任何异常或日志，线上表现为偶发「失忆」，很难归因。
- 修复摘要：无工具分支也把 system 单独传给 chat_sync，与 proactive_turn 的无工具路径共用同一段取 system 的逻辑，并补一条单测锁定。
- 修复文件：backend-agent/app/tool_loop.py、backend-agent/app/graphs/proactive_turn.py
- 建议测试：pytest -q backend-agent/tests/test_tool_loop_system_prompt.py（新增）；pytest -q backend-agent（既有用例不得回退）

### COR-820D8 · [中] [u01] — 消息编号靠 Redis 里的一个计数器发号，这个计数器一旦被清空，新消息就从 1 开始编号，和历史消息撞号，用户会发不出消息。
- 简述：消息编号靠 Redis 里的一个计数器发号，这个计数器一旦被清空，新消息就从 1 开始编号，和历史消息撞号，用户会发不出消息。
- 详细问题：消息 seq 只存在 Redis，计数器清零后与 uk_conv_seq 撞唯一键导致发消息 500
- 专家 / section：correctness / data-integrity
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java:485`
- 置信度：medium · kind：plan
- 证据：ConversationService.java:483-487 `increment(SEQ_KEY_PREFIX + conversationId, safeCount)`，取不到时回落 `safeCount`；全仓只有 4 处 seq key 操作（:226/:243 删除、:485 increment、GroupChatService:1320、VoiceCallService:722），没有任何从数据库回填的逻辑。数据库侧 V1__init_schema.sql 的 message 表带 `UNIQUE KEY uk_conv_seq (conversation_id, seq)`。docker-compose.yml:271-286 的 redis 虽配了 appendonly 与 volume，但没有 maxmemory-policy，运维 FLUSHALL、重建卷、切换实例都会让计数器归零。
- 影响：计数器丢失后，该会话的每条新消息都会算出一个与历史冲突的 seq，插入抛 DuplicateKeyException：私聊、群聊、语音通话三条发送路径同时 500，用户侧表现为发送失败或「对方没有回复」，直到计数器重新追平历史最大 seq。
- 修复摘要：让 seq 的唯一真相回到数据库：用数据库原子分配（conversation 行上的 seq 列或 SELECT MAX(seq) 初始化），Redis 只做缓存加速。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java、backend/lianyu-service/src/main/java/com/lianyu/service/conversation/GroupChatService.java、backend/lianyu-service/src/main/java/com/lianyu/service/conversation/VoiceCallService.java、backend/lianyu-dao/src/main/resources/db/migration/
- 建议测试：mvn -q -f backend/pom.xml test；新增 MessageSeqAllocatorTest（Redis 清空场景）

### COR-CF695 · [中] [u07] — 一次对话抽出的几条记忆本该一起成功或一起放弃，实际是逐条写入；中途出错时前半段已经写进库，后端还会用 Java 再写一遍，结果同一段聊天可能变成好几条记忆。
- 简述：一次对话抽出的几条记忆本该一起成功或一起放弃，实际是逐条写入；中途出错时前半段已经写进库，后端还会用 Java 再写一遍，结果同一段聊天可能变成好几条记忆。
- 详细问题：记忆写入声明整批单事务，实际逐条提交；失败后 Java 再抽一遍造成重复记忆
- 专家 / section：correctness / data-integrity
- 位置：`backend-agent/app/memory/writer.py:121`
- 置信度：medium · kind：plan
- 证据：writer.py:121-122 docstring「批量写入；整批在同一事务内，任一失败全回滚」，实际 :178(_insert_new)、:196(_update_meta)、:212(_replace_and_enqueue) 每条记忆各自 `async with transaction()`。app/main.py:951-957 捕获异常后返回 500 `detail='memory write failed'`；Java 侧 AgentMemoryAdapter.java:99-105 收到 null 或 error 就 return false，MemoryWriter.java:89-95 于是走自己的抽取 + upsertTypedMemory。source_hash 含 summary（writer.py compute_source_ids_hash），Python 与 Java 的摘要文本不同就视为两条新记忆。
- 影响：一次失败的批量写入会把已提交的前几条留在库里，Java 兜底再写一遍时因为摘要不同而生成新行：用户在记忆页看到同一段对话的重复条目，记忆数与检索结果都失真，还多占向量。
- 修复摘要：二选一：要么真正实现整批单事务（写失败整体回滚，Java 兜底只会补写一次），要么改成幂等写入并在 500 时告知 Java 不可重写。
- 修复文件：backend-agent/app/memory/writer.py、backend-agent/app/main.py、backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentMemoryAdapter.java、backend/lianyu-service/src/main/java/com/lianyu/service/memory/MemoryWriter.java
- 建议测试：pytest -q backend-agent；mvn -q -f backend/pom.xml test

### COR-34387 · [中] [u07] — 文档和部署脚本都说模型运行时只读数据库，但代码里它直接往记忆表写数据；如果按文档收紧权限，记忆功能会整体写不进去，而现在两边共用的却是同一个全权账号。
- 简述：文档和部署脚本都说模型运行时只读数据库，但代码里它直接往记忆表写数据；如果按文档收紧权限，记忆功能会整体写不进去，而现在两边共用的却是同一个全权账号。
- 详细问题：Python 侧直接写 MySQL，与「只读账号 / Python 不写库」的既定边界冲突
- 专家 / section：correctness / architecture
- 位置：`backend-agent/app/write.py:29`
- 置信度：medium · kind：plan
- 证据：app/write.py:29 `_ALLOWED_INSERTS = frozenset({'memory_meta','memory_outbox'})`，memory/writer.py 与 memory/relay.py 都通过 transaction() 复用 db.py 的连接池写这两张表。而 app/db.py 的注释要求「生产请给 agent 配只读 MySQL 账号（GRANT SELECT）」、pyproject.toml:22 写「只读数据访问（总纲 D7）：MySQL 只读查询 + Redis 缓存读取」、docker-compose.yml:111 同样注释，但 compose 里 agent 与 agent2 用的仍是 `${MYSQL_USER:-lianyu}`（Java 的全权业务账号）；AGENTS.md §2 的红线写的是「Python ❌ 写 MySQL（只读）」。
- 影响：两条路都不可接受：按文档收紧为只读账号，记忆抽取写入与 outbox relay 会全部失败，而且失败被 Java 兜底掩盖成静默降级；维持现状则模型运行时长期持有业务库写权限，「只读」的边界与文档全部失真。
- 修复摘要：先定口径再改实现：要么承认 Python 拥有记忆表的受限写权限并按此配置独立账号，要么把写入收回 Java，Python 只返回抽取结果。
- 修复文件：backend-agent/app/write.py、backend-agent/app/db.py、backend-agent/pyproject.toml、docker-compose.yml、AGENTS.md
- 建议测试：pytest -q backend-agent；docker compose config（核对账号注入）

### COR-0147C · [中] [u07] — 记忆写进库后要靠后台任务转成可检索的向量；任务中途进程挂掉或反复失败后，这条记忆就永远停在那里，用户再也搜不到它，也没有任何提示。
- 简述：记忆写进库后要靠后台任务转成可检索的向量；任务中途进程挂掉或反复失败后，这条记忆就永远停在那里，用户再也搜不到它，也没有任何提示。
- 详细问题：outbox 的 RUNNING/DEAD 行没有自动回收，崩溃留下的记忆永久不再投影
- 专家 / section：correctness / concurrency
- 位置：`backend-agent/app/memory/relay.py:207`
- 置信度：medium · kind：plan
- 证据：relay.py:41-48 `_claim` 用 CAS 把 PENDING 改成 RUNNING；:207-222 `requeue_stuck()` 才会把 DEAD 与卡住 ≥10 分钟的 RUNNING 重置回 PENDING，但全仓只有 app/main.py:967-971 的 POST /agent/memory/relay/requeue 调用它。启动路径 main.py:33-57 的 lifespan 只 `create_task(_relay_loop())`，而 _relay_loop(:54-66) 仅循环调用 drain_once。reconcile()(:178-205，用于补 milvus_vec_id 为空的记忆)同样只出现在 POST /agent/memory/reconcile(main.py:974)。
- 影响：进程被 kill / 重启后，留在 RUNNING 的记忆永远不会被再次领取；embedding 连续失败到达 attempts 上限后转 DEAD，也不再重试。两者都让这条记忆永久不可检索，而界面上看不出任何异常。
- 修复摘要：把恢复动作接进后台循环：启动时先 requeue_stuck，并按较低频率周期性执行 requeue_stuck 与 reconcile。
- 修复文件：backend-agent/app/main.py、backend-agent/app/memory/relay.py
- 建议测试：pytest -q backend-agent；新增 tests/test_memory_relay_requeue.py（崩溃恢复用例）

### COR-7AAFA · [中] [u02] — 用户只是说了一句带道歉词的话、但当时并没有处于受伤状态时，系统仍会记下「第一次和好」这个里程碑，用户会在关系里看到一段没发生过的和好。
- 简述：用户只是说了一句带道歉词的话、但当时并没有处于受伤状态时，系统仍会记下「第一次和好」这个里程碑，用户会在关系里看到一段没发生过的和好。
- 详细问题：修复事件被跳过时仍记录「第一次和好」里程碑
- 专家 / section：correctness / state
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/relationship/RelationshipStateService.java:306`
- 置信度：medium · kind：direct
- 证据：RelationshipStateService.java:232-240：`injuryOpen` 在循环外算一次，REPAIR_ATTEMPT/REPAIR_SUCCESS 在 `!injuryOpen` 时 `continue`，既不计分也不 persistEvent；但 :133 仍把原始 `events` 列表交给 recordMilestones，:290-307 的循环对 `case REPAIR_ATTEMPT, REPAIR_SUCCESS -> recordMilestone(..., FIRST_REPAIR, ...)` 没有任何「该事件是否真的生效」的判断。
- 影响：phase 仍在 TESTING、信任分没有变化的用户会收到 FIRST_REPAIR 里程碑，关系进度与真实互动不一致（V57 只修正了 trust_score，没有修里程碑），后续按里程碑解锁的内心情景会提前出现。
- 修复摘要：只对真正生效的事件发里程碑：让 applyEvents 返回被采纳的事件列表（或带 applied 标记），recordMilestones 只看这份列表。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/relationship/RelationshipStateService.java、backend/lianyu-service/src/test/java/com/lianyu/service/relationship/RelationshipStateServiceTest.java
- 建议测试：mvn -q -f backend/pom.xml test

### COR-C63BD · [中] [u03] — 登录凭证过期后本该自动续期，但只要续期请求碰上网络抖动或后端 5xx，客户端就清掉登录态把用户踢回首页，正在输入的内容也一起消失。
- 简述：登录凭证过期后本该自动续期，但只要续期请求碰上网络抖动或后端 5xx，客户端就清掉登录态把用户踢回首页，正在输入的内容也一起消失。
- 详细问题：刷新 token 时网络瞬断即清空登录态并跳回首页
- 专家 / section：correctness / state
- 位置：`frontend/src/api/httpCore.js:45`
- 置信度：medium · kind：direct
- 证据：httpCore.js:38-53 singleFlightRefresh 中 `catch { return false }`(:45-46) 把任何异常都当作刷新失败，包括 refreshAuthToken 的网络错误与 5xx；:69-72 `const ok = await singleFlightRefresh(); if (!ok) return rejectSessionExpired()`；rejectSessionExpired(:25-33) 会 clearTokenStorage + 清 store + `window.location.hash='#/'`。只有 refresh 返回 401（token 真的失效）才应该走这条路径。
- 影响：弱网或后端抖动时用户被强制登出、未发送的输入丢失；重试刷新本可以恢复的场景变成了重新登录，也是「偶尔掉登录」投诉的最可能来源。
- 修复摘要：区分「凭证失效」与「刷新请求失败」：只有 401 才登出，网络错误/5xx 保留会话并把错误抛回原请求。
- 修复文件：frontend/src/api/httpCore.js、frontend/src/api/auth.js、frontend/src/api/__tests__/sessionRefresh.test.js
- 建议测试：npm --prefix frontend test

### COR-138AC · [中] [u03] — 记忆页一次只拿前 50 条且没有翻页，记忆攒多了以后，老记忆在界面上消失，用户会以为它们被删掉了。
- 简述：记忆页一次只拿前 50 条且没有翻页，记忆攒多了以后，老记忆在界面上消失，用户会以为它们被删掉了。
- 详细问题：记忆页固定只取第一页 50 条，更早的记忆在界面上永久不可见
- 专家 / section：correctness / contracts
- 位置：`frontend/src/api/memory.js:3`
- 置信度：medium · kind：plan
- 证据：frontend/src/api/memory.js:3-5 `listMemories(characterId)` 只传 characterId；frontend/src/pages/MemoryPage.vue:305 `listMemories(filterCharId.value || undefined)` 一次性取回、没有分页或「加载更多」；后端 MemoryController.java:29-35 提供 page/size 且默认 size=50，MemoryQueryService.java:39-50 用 `LIMIT ... OFFSET ...` 分页。前端从不传这两个参数，等于永远固定在第 1 页。
- 影响：记忆超过 50 条的用户在记忆页看不到更早的记忆，也无法通过界面删除或查看来源消息，与「记忆管理」的产品承诺不符；同时后端分页能力事实上没有被使用。
- 修复摘要：前端接上后端分页：滚动加载或分页器 + 总数展示；若产品不打算做分页，则后端按聚合视图返回全量摘要并明确上限。
- 修复文件：frontend/src/api/memory.js、frontend/src/pages/MemoryPage.vue、backend/lianyu-web/src/main/java/com/lianyu/web/controller/MemoryController.java、backend/lianyu-service/src/main/java/com/lianyu/service/memory/MemoryQueryService.java
- 建议测试：npm --prefix frontend test；mvn -q -f backend/pom.xml test

### COR-369B6 · [中] [u02] — 同一个人同时从两个入口（比如桌面和 QQ 桥）跟同一个角色说话时，两次互动各自算完分数再整行写回，后写的会盖掉先写的，那一次互动的变化就丢了。
- 简述：同一个人同时从两个入口（比如桌面和 QQ 桥）跟同一个角色说话时，两次互动各自算完分数再整行写回，后写的会盖掉先写的，那一次互动的变化就丢了。
- 详细问题：关系状态是无锁的读-改-写，并发回合与定时衰减会互相覆盖
- 专家 / section：correctness / concurrency
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/relationship/RelationshipStateService.java:107`
- 置信度：medium · kind：plan
- 证据：recordUserTurn(:107) 没有 @Transactional：loadOrCreateState → fromUserTurn → applyEvents → applyPresence → `relationshipStateMapper.updateById(state)`(:132)，整行覆盖；定时衰减 decayAll 也是 select 后逐行 `updateById`(:185)。同用户的并发入口是真实存在的：主聊天（ConversationService.sendMessageStream）与 QQ/微信桥转发都会落到 recordUserTurn。
- 影响：并发时后写者覆盖前写者，那一轮互动的信任/亲密/安全增量丢失；衰减任务用旧快照回写时，还可能把刚发生的互动（lastInteractionAt、streak）一起抹掉，表现为「聊了但关系没变」。
- 修复摘要：把关系更新变成有原子性的过程：加乐观锁（version）或行锁，并让衰减不要在读到旧快照后整行覆盖。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/relationship/RelationshipStateService.java、backend/lianyu-dao/src/main/java/com/lianyu/dao/entity/RelationshipState.java
- 建议测试：mvn -q -f backend/pom.xml test

### COR-45168 · [低] [u01] — 代码里有一个「客户端中途断开就把已生成内容存下来」的判断，但它依赖的错误类型永远不会出现，等于这段判断从没生效过；将来如果把上游超时改成 IOException，会把半句话当成完整回复存进历史。
- 简述：代码里有一个「客户端中途断开就把已生成内容存下来」的判断，但它依赖的错误类型永远不会出现，等于这段判断从没生效过；将来如果把上游超时改成 IOException，会把半句话当成完整回复存进历史。
- 详细问题：isClientStreamDisconnect 只认 IOException，实际错误恒为 BusinessException 或 null
- 专家 / section：correctness / logic
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java:427`
- 置信度：medium · kind：direct
- 证据：ConversationService.java:427-429 `return error instanceof java.io.IOException;`；onComplete 的调用点全仓只有三处：SseChatStreamHelper.java:63 传 null（正常完成）、:83 传 `new BusinessException(ErrorCode.AI_PROVIDER_ERROR, ...)`（错误收尾）、AgentStreamBridge.java:138 传 null（转发中断）。因此该判断恒为 false，onComplete 里的「客户端断开」语义从未被识别。
- 影响：分支是死代码，误导维护者以为客户端断开已被单独处理；一旦错误传播改成 IOException（例如把上游读超时包装成 IOException 抛出），半截回复会被当完整回复落库并跳过错误提示。
- 修复摘要：用明确的语义标记替代异常类型判断：在 onComplete 的入参里带一个 abortReason（client_disconnect / upstream_error / done）。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java、backend/lianyu-service/src/main/java/com/lianyu/service/ai/SseChatStreamHelper.java、backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentStreamBridge.java、backend/lianyu-service/src/main/java/com/lianyu/service/agent/AgentStreamCallback.java
- 建议测试：mvn -q -f backend/pom.xml test

### COR-F1F6B · [低] [u06] — 读取回复时如果服务端最后一段没有以换行收尾，这段文字会被丢掉；解析到错误帧后连接也没有被主动关闭，会一直挂着等超时。
- 简述：读取回复时如果服务端最后一段没有以换行收尾，这段文字会被丢掉；解析到错误帧后连接也没有被主动关闭，会一直挂着等超时。
- 详细问题：流式读取结束时不处理残留缓冲，出错时也不取消底层连接
- 专家 / section：correctness / edge-cases
- 位置：`frontend/src/utils/assistantStreamDrain.js:29`
- 置信度：medium · kind：direct
- 证据：assistantStreamDrain.js:26-35 按 '\n' 切分后 `buffer = parts.pop() || ''`，循环 `done` 退出时不再 feed 残留 buffer，直接 `return acc.result()`(:40)；:36-38 `finally { reader.releaseLock() }` 没有 `reader.cancel()`，acc.feed 遇到 error 帧抛异常时底层流不会被取消。
- 影响：服务端最后一帧缺换行（截断、代理改写）时丢最后一段回复；错误帧抛异常后连接悬挂到超时，异常频繁时累积未关闭的流。
- 修复摘要：循环结束后 flush 残留 buffer；异常路径上 cancel 读取器，保证连接被关闭。
- 修复文件：frontend/src/utils/assistantStreamDrain.js、frontend/shared/sseParser.js
- 建议测试：npm --prefix frontend test

### SEC-D46E9 · [严重] [u07] — 任何人不用登录就能调用后端模型服务的内部接口，冒充任意用户拿到别人的聊天记忆和关系数据。
- 简述：任何人不用登录就能调用后端模型服务的内部接口，冒充任意用户拿到别人的聊天记忆和关系数据。
- 详细问题：Python agent 的 /agent/** 端点在公网网关上完全没有鉴权，X-User-Id 校验可被省略
- 专家 / section：security / auth
- 位置：`backend-agent/app/main.py:143`
- 置信度：high · kind：plan
- 证据：deploy/api-gateway/nginx.conf:252-284 `location /api/agent/` 直接 `set $agent_upstream http://agent:8001;` + `rewrite ^/api(/agent/.*)$ $1 break;` + `proxy_pass $agent_upstream;`，不经过 Java/Sa-Token；backend-agent/app/main.py:143-151 `user_id_header = request.headers.get("X-User-Id"); if user_id_header and str(body.user_id) != user_id_header: raise HTTPException(403)` —— 不带该头即整段校验跳过；同文件 :657-704 的 prompt_assemble 用同一模式。数据面 backend-agent/app/data_readers.py:26-51 `WHERE user_id=%s AND character_id=%s`，只按调用方给的两个 id 取数。
- 影响：匿名攻击者对 https://<gateway>/api/agent/prompt/assemble POST {"userId":<受害者 id>,"characterId":<角色 id>,"useInternalData":true,"scene":"SINGLE"} 即可拿到含受害者记忆、关系状态、角色状态的 systemPrompt；同族 /agent/chat-stream、/agent/memory/write、/agent/tts/synthesize、/agent/desktop-observe 等 20+ 端点同样敞开，可枚举 userId 批量窃取隐私、向他人记忆写入污染数据、并烧掉平台模型额度。
- 修复摘要：在网关与 agent 之间补一层服务间认证，并让 Python 侧只信任来自 Java 的身份声明。
- 修复文件：deploy/api-gateway/nginx.conf、backend-agent/app/main.py、backend-agent/app/data_readers.py、backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentRuntimeClient.java
- 建议测试：pytest -q backend-agent（新增 test_agent_auth.py：缺签名/过期签名/userId 不符均拒绝）；mvn -q -f backend/pom.xml test（AgentRuntimeClient 注入签名的单测）

### SEC-B61C6 · [高] [u11] — 运维后台的登录失败锁定其实是失效的，而且登录接口没有任何限流，管理员口令可以被无限次尝试。
- 简述：运维后台的登录失败锁定其实是失效的，而且登录接口没有任何限流，管理员口令可以被无限次尝试。
- 详细问题：管理员登录的 locked_until 用 instanceof java.sql.Timestamp 判断，MySQL 8 返回 LocalDateTime 导致锁定永不生效
- 专家 / section：security / auth
- 位置：`backend/lianyu-admin/src/main/java/com/lianyu/admin/identity/AdminAuthService.java:36`
- 置信度：high · kind：plan
- 证据：AdminAuthService.java:35-39 `Object lockedUntil = row.get("locked_until"); if (lockedUntil instanceof java.sql.Timestamp t && t.toInstant().isAfter(Instant.now())) throw new IllegalStateException("登录暂时锁定");`；:38 失败计数 `failed_attempts=failed_attempts+1, locked_until=CASE WHEN failed_attempts+1>=5 THEN DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 15 MINUTE)`；backend/pom.xml:41 mysql-connector 8.4.0 对 DATETIME(3) 映射为 java.time.LocalDateTime，instanceof 恒 false；AdminAuthController.java:13 只有 `@PostMapping("/login")`，backend/lianyu-admin 全模块无任何 RateLimiter。
- 影响：攻击者可以对 /api/admin/v1/auth/login 无限次撞库：5 次失败后的 15 分钟锁定形同虚设，且没有 IP/账号维度限流，一旦口令命中即拿到 8 小时运维令牌（可读用户列表、发公告、管理发版）。
- 修复摘要：修正锁定时间类型判断，并给管理员登录加账号 + IP 双维度限流。
- 修复文件：backend/lianyu-admin/src/main/java/com/lianyu/admin/identity/AdminAuthService.java、backend/lianyu-admin/src/main/java/com/lianyu/admin/identity/AdminAuthController.java、backend/lianyu-service/src/main/java/com/lianyu/service/auth/AuthRateLimiter.java
- 建议测试：mvn -q -f backend/pom.xml test（AdminAuthService 锁定用例 + 限流用例）

### SEC-A3AB2 · [高] [u04] — 生产环境的真实账号口令被写在仓库里的运维脚本里，任何能看代码的人都能直接登录生产后端。
- 简述：生产环境的真实账号口令被写在仓库里的运维脚本里，任何能看代码的人都能直接登录生产后端。
- 详细问题：scripts/_prod_verify.py 硬编码生产账号与口令，e2e 脚本另带默认口令并关闭 TLS 校验
- 专家 / section：security / secrets
- 位置：`scripts/_prod_verify.py:22`
- 置信度：high · kind：plan
- 证据：scripts/_prod_verify.py:20-23 `BASE = "http://127.0.0.1:8080"` / 脚本里把 `USERNAME`、`PASSWORD` 直接写成生产账号与口令字面量（值已隐去），`ENV_FILE = "/opt/lianyu/.env"`，文件头注释说明该脚本在服务器上跑并走完整 Java 链路（登录 + 6 条阻塞对话 + SSE）；scripts/e2e/lianyu_e2e.py:23-27 默认 `BASE_URL=https://156.233.228.18`、`PASSWORD` 直接写入口令字面量（值已隐去），`SSL_CTX.check_hostname = False` / `SSL_CTX.verify_mode = ssl.CERT_NONE`。
- 影响：拿到仓库读权限的人（含外包、镜像、fork、泄露的备份）可直接用该口令登录生产账号，读写该账号的会话与记忆；口令与服务器的 root@156.233.228.18、DEPLOY_SSH_PASSWORD 同处一套运维资产，横向移动风险高。
- 修复摘要：立即轮换该账号口令，脚本改为从 .env/密钥管理读取，并清理 git 历史中的明文。
- 修复文件：scripts/_prod_verify.py、scripts/e2e/lianyu_e2e.py、.env.example
- 建议测试：python -m py_compile scripts/_prod_verify.py scripts/e2e/lianyu_e2e.py；gitleaks detect --no-git -v（本地跑通基线为空）

### SEC-4E2CB · [高] [u09] — 本机 QQ 桥接的登录令牌是个写死的常量，用户电脑上任何一个网页都能用它冒充用户给自己的 QQ 好友发消息。
- 简述：本机 QQ 桥接的登录令牌是个写死的常量，用户电脑上任何一个网页都能用它冒充用户给自己的 QQ 好友发消息。
- 详细问题：NapCat OneBot/WebUI 令牌被固定为常量 <固定常量>，回环端口对本机任意进程与浏览器页面敞开
- 专家 / section：security / auth
- 位置：`frontend/electron/napcatRuntime/napcatHost.js:139`
- 置信度：medium · kind：plan
- 证据：napcatRuntime/napcatHost.js:135-140 注释「token 定死为 <固定常量>（用户指定）……不再随机生成、不读 hosting 残留旧 token」，代码里 `wsToken` 与 `webuiToken` 两个变量都赋成同一个硬编码常量（值已隐去），随后 writeQqBridgeSettings 回写 hosting.webuiToken/wsToken 并作为启动参数传给 NapCat；:200-210 fallback 直接把 WebUI 地址（把访问令牌放进 query 参数）拼出来，`buildWsUrl` 再把 access_token 拼进 ws URL；默认 wsPort 3001、webuiPort 6099 仅监听回环。
- 影响：回环端口对「本机任意进程」与「用户浏览的任意网页」都可达：页面可用已知 token 连 ws://127.0.0.1:3001 调 OneBot 接口读取会话列表、以用户身份发送消息/图片，造成账号冒用与隐私泄露；WebUI 6099 同样可被直接登录。
- 修复摘要：把桥接令牌改回每台机器随机生成并持久化，同时收紧回环端口的调用方。
- 修复文件：frontend/electron/napcatRuntime/napcatHost.js、frontend/electron/qqBridge/qqBridgeSettings.js、frontend/electron/qqBridge/napCatClient.js、frontend/src/pages/QqBridgePage.vue
- 建议测试：npm --prefix frontend test（napcatHost/napCatClient 用例：token 随机化与持久化）

### SEC-92E01 · [中] [u08] — 限流用的客户端 IP 取自可被伪造的请求头第一段，攻击者每次换一个假 IP 就能绕过注册和登录的频率限制。
- 简述：限流用的客户端 IP 取自可被伪造的请求头第一段，攻击者每次换一个假 IP 就能绕过注册和登录的频率限制。
- 详细问题：ClientIpResolver 取 X-Forwarded-For 首个值，而 nginx 用 $proxy_add_x_forwarded_for 保留客户端自带前缀
- 专家 / section：security / auth
- 位置：`backend/lianyu-web/src/main/java/com/lianyu/web/util/ClientIpResolver.java:24`
- 置信度：high · kind：direct
- 证据：ClientIpResolver.java:24-29 `if (trustForwardedFor) { String forwarded = request.getHeader("X-Forwarded-For"); ... int comma = forwarded.indexOf(','); return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim(); }`；deploy/api-gateway/nginx.conf:52（及同文件 15 处）`proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;` 会把客户端自带的 XFF 原样前置；backend/lianyu-app/src/main/resources/application.yml:170-172 默认 `lianyu.security.trust-forwarded-for: ${LIANYU_TRUST_FORWARDED_FOR:true}`；调用点 AuthController.java:66/78 用该 IP 做 checkLoginOrRegister 与 `checkRateLimit("rate:register:ip:", ip, 3, Duration.ofDays(1))`。
- 影响：攻击者每次请求换一个伪造的首段 IP，就能无限次尝试注册（3 次/日/IP）与登录（30 次/分/IP），把短信/验证码与账号枚举成本压到接近零；配合已存在的验证码弱点可放大为批量注册与撞库。
- 修复摘要：只信任最后一跳代理写入的地址：nginx 用 $remote_addr 覆盖 XFF，后端取最右侧可信值。
- 修复文件：backend/lianyu-web/src/main/java/com/lianyu/web/util/ClientIpResolver.java、deploy/api-gateway/nginx.conf、backend/lianyu-app/src/main/resources/application.yml
- 建议测试：mvn -q -f backend/pom.xml test（ClientIpResolver 新增伪造 XFF 用例）

### SEC-F6304 · [中] [u01] — 用户改密码之后，之前登录过的设备并不会被踢下线，旧凭证还能继续用一个月。
- 简述：用户改密码之后，之前登录过的设备并不会被踢下线，旧凭证还能继续用一个月。
- 详细问题：changePassword 只更新 password_hash，不吊销任何既有会话；sa-token 有效期 30 天且不因空闲失效
- 专家 / section：security / auth
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/auth/impl/AuthServiceImpl.java:154`
- 置信度：high · kind：direct
- 证据：AuthServiceImpl.java:154-162 `user.setPasswordHash(passwordEncoder.encode(request.getNewPassword())); userMapper.updateById(user);` 之后只有日志，没有 StpUtil.logout/kickout/会话清理；backend/lianyu-app/src/main/resources/application.yml:133-141 sa-token `timeout: 2592000`（30 天）、`active-timeout: -1`、`is-concurrent: true`。
- 影响：账号在他人设备上留存的 token 在改密后仍有效 30 天：用户发现异常后改密码并不能止损，被盗 token（例如日志泄漏、旧客户端明文降级）可继续读写全部会话与记忆。
- 修复摘要：改密成功后立即吊销该用户的全部会话，并要求重新登录。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/auth/impl/AuthServiceImpl.java、backend/lianyu-web/src/main/java/com/lianyu/web/controller/AuthController.java
- 建议测试：mvn -q -f backend/pom.xml test（AuthServiceImplChangePassword 用例：旧 token 失效）

### SEC-312D4 · [中] [u05] — 平台自带的 API Key 以密文形式被提交进数据库迁移文件，密文和主密钥都在同一套运维资产里，等于密钥已经外泄。
- 简述：平台自带的 API Key 以密文形式被提交进数据库迁移文件，密文和主密钥都在同一套运维资产里，等于密钥已经外泄。
- 详细问题：V22 迁移把 10 条平台 API Key 密文写进仓库，V49 只删行不删历史
- 专家 / section：security / secrets
- 位置：`backend/lianyu-dao/src/main/resources/db/migration/V22__team_default_vault_pool_encrypted.sql:5`
- 置信度：high · kind：plan
- 证据：V22__team_default_vault_pool_encrypted.sql:5-14 共 10 条 `UPDATE api_key_vault SET 写入 `api_key_encrypted`（<base64 密文占位>）、`key_version`='v1'、`enabled`=1、 base_url='https://clove.dpdns.org', model_default='deepseek-v4-flash', remark='default-pool-N' ...`；V49__drop_default_vault_pool.sql:3 只 `DELETE FROM api_key_vault WHERE vault_scope='DEFAULT' AND provider='platform'`；解密钥来自服务器 .env 的 LIANYU_MASTER_KEY（backend/lianyu-security/.../JasyptUtil.java:20-59）。
- 影响：任何拿到仓库（含历史、镜像、备份）并可访问部署 .env 的人都能还原出这 10 个平台 Key，用平台额度调用模型；即使行已被删除，密文仍永久留在 git 历史里，属于「已外泄密钥」。
- 修复摘要：轮换这 10 个平台 Key，改为运行时注入，并从历史中清除密文。
- 修复文件：backend/lianyu-dao/src/main/resources/db/migration/V22__team_default_vault_pool_encrypted.sql、backend/lianyu-dao/src/main/resources/db/migration/V49__drop_default_vault_pool.sql、scripts/_cloud_deploy_pull.py
- 建议测试：mvn -q -f backend/pom.xml test（Flyway 迁移在新库上跑通，且 vault 表为空）

### SEC-811CF · [中] [u02] — 登录用户可以让服务器向任意地址发起请求，包括内网和云厂商的元数据地址，从而探测内网或拿到实例凭据。
- 简述：登录用户可以让服务器向任意地址发起请求，包括内网和云厂商的元数据地址，从而探测内网或拿到实例凭据。
- 详细问题：Web Push 订阅端点未经任何校验就由服务端 POST，可指向内网/元数据地址
- 专家 / section：security / uploads
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/notification/WebPushService.java:95`
- 置信度：medium · kind：direct
- 证据：WebPushService.java:95-103 `Notification notification = new Notification(sub.getEndpoint(), sub.getP256dh(), sub.getAuth(), json); HttpPost post = service.preparePost(notification, Encoding.AESGCM); HttpResponse response = httpClient.execute(post);`，`buildHttpClient` 只设 RequestConfig，全类无 OutboundUrlValidator/主机白名单；端点来源 backend/lianyu-web/.../controller/NotificationController.java:69-74 `POST /api/notifications/push/subscribe` → NotificationService.upsertPushSubscription(userId, request)，DTO PushSubscriptionRequest 只 `@NotBlank endpoint/p256dh/auth`。
- 影响：任意登录用户提交 endpoint=http://169.254.169.254/... 或 http://127.0.0.1:8080/... 后触发发送，服务端即向该地址发起带 VAPID 头的 POST；可用于内网端口探测、打内部服务（盲 SSRF），并可能读取云元数据服务响应中的实例信息。
- 修复摘要：订阅时用 OutboundUrlValidator 校验并固定解析结果，发送时只连白名单主机。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/notification/WebPushService.java、backend/lianyu-web/src/main/java/com/lianyu/web/controller/NotificationController.java、backend/lianyu-common/src/main/java/com/lianyu/common/util/OutboundUrlValidator.java
- 建议测试：mvn -q -f backend/pom.xml test（NotificationServiceTest 新增非法 endpoint 用例）

### SEC-03649 · [中] [u10] — 一旦服务器漏配主密钥，用户的 API Key 会被明文存进数据库，而且只在日志里留一句警告，没人会发现。
- 简述：一旦服务器漏配主密钥，用户的 API Key 会被明文存进数据库，而且只在日志里留一句警告，没人会发现。
- 详细问题：JasyptUtil 在缺少 LIANYU_MASTER_KEY 时静默降级为返回明文，加密列里存的是明文
- 专家 / section：security / crypto
- 位置：`backend/lianyu-security/src/main/java/com/lianyu/security/util/JasyptUtil.java:61`
- 置信度：high · kind：direct
- 证据：JasyptUtil.java:20-27 构造器在 `LIANYU_MASTER_KEY` 缺失时只 `log.warn(...)` 后返回（encryptors 为空）；:61-73 encrypt() 在 `currentVersion == null || encryptors.isEmpty()` 或取不到 encryptor 时 `log.warn("No Jasypt encryptor available, returning plaintext"); return plaintext;`；:75-80 decrypt() 同样原样返回。调用方 ApiKeyVaultService 把结果直接写进 api_key_vault.api_key_encrypted（列注释仍写 encrypted）。
- 影响：部署漏配主密钥（新环境、容器未挂 .env、轮换失败）时，所有用户与平台 API Key 明文落库，备份/binlog/只读副本里都是明文；补救密钥后系统也无法区分历史明文行，需要人工逐行迁移，等于长期静默泄漏。
- 修复摘要：改为 fail-closed：缺少主密钥时启动失败或写入报错，绝不返回明文。
- 修复文件：backend/lianyu-security/src/main/java/com/lianyu/security/util/JasyptUtil.java、backend/lianyu-security/src/main/java/com/lianyu/security/handler/EncryptedStringTypeHandler.java、backend/lianyu-service/src/main/java/com/lianyu/service/ai/ApiKeyVaultService.java
- 建议测试：mvn -q -f backend/pom.xml test（JasyptUtil 缺密钥用例）

### SEC-F9B0F · [中] [u10] — 运维后台把管理员登录凭证明文存在浏览器存储里，任何能在页面里执行脚本的东西都能把它读走。
- 简述：运维后台把管理员登录凭证明文存在浏览器存储里，任何能在页面里执行脚本的东西都能把它读走。
- 详细问题：admin 管理台把 8 小时有效的管理员令牌明文存入 localStorage
- 专家 / section：security / browser
- 位置：`admin/src/stores/session.js:2`
- 置信度：high · kind：plan
- 证据：admin/src/stores/session.js:2 把登录令牌放进 `token` 字段并持久化到 localStorage 的 `lianyu-admin-token`；admin/src/api.js:2-4 `baseURL = import.meta.env.VITE_ADMIN_API_ORIGIN || 'https://downloadlianyu.asia'` 并在拦截器里用该 token 塞 `lianyu-token` 头；admin/electron/main.js:5-7 窗口 contextIsolation/ nodeIntegration 已关闭但未设 sandbox，页面无 CSP；令牌有效期 8 小时（AdminAuthService.java:48）。
- 影响：管理台任意 XSS、被投毒的第三方依赖或恶意 DevTools 扩展都能直接读出该令牌，之后 8 小时内以超管身份调用 /api/admin/v1/**（用户管理、发版、公告）；相比会话 cookie 方案，localStorage 无 HttpOnly 保护。
- 修复摘要：改为内存持有 + 短时刷新，或退回 HttpOnly Cookie，至少不再持久化到 localStorage。
- 修复文件：admin/src/stores/session.js、admin/src/api.js、admin/electron/main.js、backend/lianyu-admin/src/main/java/com/lianyu/admin/identity/AdminAuthService.java
- 建议测试：npm --prefix admin test（session store 用例：刷新后需重新认证，localStorage 无 token）

### SEC-A692C · [低] [u08] — 公开文件下载接口直接照 URL 取存储对象，没有限定目录，任何对象只要猜到名字就能匿名下载。
- 简述：公开文件下载接口直接照 URL 取存储对象，没有限定目录，任何对象只要猜到名字就能匿名下载。
- 详细问题：/api/public/files/** 按原始 URI 截取对象键，缺少前缀白名单
- 专家 / section：security / exposure
- 位置：`backend/lianyu-web/src/main/java/com/lianyu/web/controller/PublicFileController.java:41`
- 置信度：high · kind：direct
- 证据：PublicFileController.java:31-79 `GET /api/public/files/**` 匿名可访问（SaTokenConfig notMatch "/api/public/**"），extractObjectKey 用 `request.getRequestURI().indexOf(PUBLIC_FILE_PREFIX)` 之后整段作为对象键，未限定 avatars/ updates/ chat-voice/ 等前缀；仅第 41 行 `isDangerousPublicContentType`（text/html、xhtml、svg、javascript）返回 404；响应带 nosniff 与 `Access-Control-Allow-Origin: *`。对象键来自 FileStorageService.java:32-38（avatars/<uuid>.png、chat-images/…、chat-voice/<petId>/…、custom-voices/<uid>/…）。
- 影响：一旦对象键可预测（用户 id 组成的 custom-voices 前缀、时间戳命名的音频），他人可绕过业务接口直接下载语音/图片；也把 MinIO 中任何未来新增的桶暴露在匿名读面上，缺少纵深防御。
- 修复摘要：给公开文件接口加对象键前缀白名单，并校验键格式。
- 修复文件：backend/lianyu-web/src/main/java/com/lianyu/web/controller/PublicFileController.java、backend/lianyu-service/src/main/java/com/lianyu/service/storage/FileStorageService.java
- 建议测试：mvn -q -f backend/pom.xml test（PublicFileController 前缀白名单用例：非法前缀 404）

### SEC-D60A4 · [低] [u08] — 语音通话的 WebSocket 还接受把登录凭证放在网址里，凭证会因此被写进服务器访问日志。
- 简述：语音通话的 WebSocket 还接受把登录凭证放在网址里，凭证会因此被写进服务器访问日志。
- 详细问题：/ws/voice 保留 query 传 token 的回退分支，30 天有效令牌会落入 nginx access log
- 专家 / section：security / exposure
- 位置：`backend/lianyu-web/src/main/java/com/lianyu/web/config/VoiceWebSocketConfig.java:42`
- 置信度：high · kind：direct
- 证据：VoiceWebSocketConfig.java:39-49 握手时先取 `String token`，只要非空就 `return bindToken(token, attributes)` 认定身份；注释自认「query 传 token 仅作旧客户端兼容回退（会进 nginx access log，将在客户端全面切换后移除）」；:65-79 bindToken 直接 `StpUtil.getLoginIdByToken(token)` 认定身份；客户端 frontend/src/composables/useVoiceDuplex.js:173-207 已改为首帧 `{type:'auth',token}` 且注释明确「URL 不再携带 token」，说明该回退分支已无调用方；令牌有效期 30 天（application.yml:133-141）。
- 影响：只要有人（旧客户端、脚本、攻击者构造）用 query 参数携带令牌这种 30 天有效的登录凭证就会以明文写进 nginx access log、代理日志与链路追踪，读日志的运维/外包即可复用该 token 长期冒用账号。
- 修复摘要：删除 query token 回退分支，只允许首帧认证。
- 修复文件：backend/lianyu-web/src/main/java/com/lianyu/web/config/VoiceWebSocketConfig.java、frontend/src/composables/useVoiceDuplex.js
- 建议测试：mvn -q -f backend/pom.xml test（voice WS 握手用例：带 query token 不再认证）

### REL-ED960 · [严重] [u08] — 聊天中途断网或关窗时，屏幕上那半句话会被当成完整回复保存下来，之后聊天记录、关系状态和记忆摘要里都留着一条被截断的回复，用户和运维都看不出发生过失败。
- 简述：聊天中途断网或关窗时，屏幕上那半句话会被当成完整回复保存下来，之后聊天记录、关系状态和记忆摘要里都留着一条被截断的回复，用户和运维都看不出发生过失败。
- 详细问题：SSE 断流时把半截回复当完整回复落库
- 专家 / section：reliability / error-handling
- 位置：`backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentStreamBridge.java:126`
- 置信度：high · kind：plan
- 证据：Relay.accept() 在 emitter.send 抛 IOException 时先 log.info 再 abort()（:74-78、:83-87）；abort()（:126-140）先 emitter.complete()，随后 `callback.onComplete(partial, null)`——error 明确传 null。上层 ConversationService.onComplete（:377-407）只在 `error != null && !isClientStreamDisconnect(error)` 时跳过落库，error=null 会直接执行 saveAssistantReplies + relationshipStateService.recordAssistantTurn + memoryWriter.enqueueSummary + notificationService.notifyAssistantMessage。另一侧 isClientStreamDisconnect 只认 `error instanceof java.io.IOException`（:427-429），而 SseChatStreamHelper.finishSseError 传的是 BusinessException（SseChatStreamHelper.java:82-83），所以「生成失败」永远匹配不上断流判断，两条分支的语义正好接反。复现方式：mock SseEmitter 的 send 抛 IOException 后喂一帧 CONTENT，Relay 会调用 callback.onComplete("半句", null)，ConversationService 随即落库。
- 影响：用户关窗、切网或代理断流时，已经显示出来的半截回复被当作完整回复写入 message 表，并写进关系状态、记忆摘要与推送通知；用户重新进入会话看到的是被截断的「完整」回复，服务端只有一条 log.info，没有错误帧、没有指标。这是把失败报成成功，属于静默的数据污染，且会让后续记忆/关系基于残缺文本继续生成。
- 修复摘要：把「客户端断开」和「生成完成」区分开：断开时不得以 error=null 调用 onComplete，截断内容要么不落库，要么带明确的不完整标记。
- 修复文件：backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentStreamBridge.java、backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java、backend/lianyu-service/src/main/java/com/lianyu/service/ai/SseChatStreamHelper.java、backend/lianyu-web/src/test/java/com/lianyu/web/agent/AgentStreamBridgeTest.java
- 建议测试：（无）

### REL-E04DB · [高] [u06] — 网络一抖动，同一条消息会被发两次：聊天记录里出现两条一样的用户消息，模型被调用两遍，用户为一次输入付两份钱。
- 简述：网络一抖动，同一条消息会被发两次：聊天记录里出现两条一样的用户消息，模型被调用两遍，用户为一次输入付两份钱。
- 详细问题：流式发送失败后自动重试没有幂等键，重复入库并重复调用模型
- 专家 / section：reliability / resilience
- 位置：`frontend/src/pages/ChatPage.vue:1437`
- 置信度：high · kind：plan
- 证据：ChatPage.handleSend 在 `isNetworkError(err)` 时 sleep(800) 后直接重放同一个 POST（:1437-1446，attemptStream 内部只是再次 sendMessageStream(sendConvId, streamPayload, { signal })）。sendMessageStream（frontend/src/api/conversation.js:67-83）只带 provider/model/content/imageUrl；SendMessageRequest（backend/lianyu-service/src/main/java/com/lianyu/service/dto/SendMessageRequest.java:10-33）没有任何 clientMessageId 字段；服务端 ConversationService.sendMessageStream 在流建立前就 `messageMapper.insert(turn.userMsg())`（:328-330）并开始调用模型，因此第一次请求即使客户端没读到响应，用户消息也已经落库、模型已经开始生成。
- 影响：弱网/代理超时（axios 层 60s，fetch 无超时）会稳定触发重试：同一句话出现两条用户消息、两次模型调用（双份 token 费用与配额），并且第一次请求的服务端流不会取消（见「客户端断开后不取消上游」），两条回复可能并发写入同一会话，顺序与关系状态都被污染。
- 修复摘要：给发送链路加端到端幂等键：客户端生成 clientMessageId，重试复用；服务端以（conversation_id, client_message_id）唯一约束去重，冲突时返回已有结果而不是再跑一轮模型。
- 修复文件：frontend/src/pages/ChatPage.vue、frontend/src/api/conversation.js、backend/lianyu-service/src/main/java/com/lianyu/service/dto/SendMessageRequest.java、backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java
- 建议测试：（无）

### REL-DAF08 · [高] [u07] — 用户自己点停止或关窗口这种正常操作，会被系统当成「模型供应商故障」记账；连续五次就能让所有人在该模型上被拒绝服务，提示「对方模型暂时繁忙」。
- 简述：用户自己点停止或关窗口这种正常操作，会被系统当成「模型供应商故障」记账；连续五次就能让所有人在该模型上被拒绝服务，提示「对方模型暂时繁忙」。
- 详细问题：客户端取消/断连被计入上游失败，5 次即可把共享熔断打到 OPEN
- 专家 / section：reliability / resilience
- 位置：`backend-agent/app/gateway.py:279`
- 置信度：high · kind：direct
- 证据：stream_text 的 `except BaseException as exc: await breaker.on_error(exc); raise`（:279-281）会把生成器关闭时的 GeneratorExit 与 asyncio.CancelledError 一并记成上游失败；resilience.py 的 UpstreamBreaker.on_error（:132-152）只把 GatewayBusinessError 当成功，其余一律 append(False)，_maybe_open（:156-166）在 20 样本窗口失败率 ≥50% 即 OPEN（半开状态任一次失败立即重开）。实测（backend-agent/.venv/Scripts/python.exe，子类记录 on_error 入参）：消费者读一帧后 agen.aclose() 连续 5 次 → 每次记录 GeneratorExit，第 5 次后 state=open，日志 `breaker probe OPEN (failure_rate=100%, samples=5)`；agen.athrow(asyncio.CancelledError()) 同样被记录为失败。
- 影响：用户点停止、切会话、关窗口都会消耗该 (provider|base_url) 共享熔断器的失败预算；5 次断连即可让所有用户在该模型上被快速失败、收到「对方模型暂时繁忙」，而真实上游健康。熔断器因此既误伤正常流量，也在真故障时失去区分度。
- 修复摘要：不要把生成器关闭/任务取消当作上游故障：只捕获真正的请求异常，取消与关闭直接向上抛且不记账。
- 修复文件：backend-agent/app/gateway.py、backend-agent/app/resilience.py、backend-agent/tests/test_gateway.py
- 建议测试：（无）

### REL-6EA52 · [中] [u10] — 用户把模型服务配成本机地址后，取模型列表用的客户端没有任何超时；对端接了连接却不回数据时，请求会一直挂着，同文件注释说这曾在线上造成整站 HTTP 卡死。
- 简述：用户把模型服务配成本机地址后，取模型列表用的客户端没有任何超时；对端接了连接却不回数据时，请求会一直挂着，同文件注释说这曾在线上造成整站 HTTP 卡死。
- 详细问题：受信本地端点走零超时 WebClient/RestClient 分支
- 专家 / section：reliability / resilience
- 位置：`backend/lianyu-ai/src/main/java/com/lianyu/ai/SsrfPinningClientFactory.java:86`
- 置信度：high · kind：direct
- 证据：restClientBuilder(ValidatedEndpoint) 在 `if (!endpoint.isPinningRequired()) return RestClient.builder();`（:86-88）、webClientBuilder(ValidatedEndpoint) 在 :103-105 同样 `return WebClient.builder();`，两者都没加 connect/read 超时。而同类 javadoc（:59-63）写着「reactor-netty 默认无任何读超时——对端挂起会让调用线程在 Mono.block() 里永久 park（线上已发生全场 HTTP 卡死）」。ValidatedEndpoint.isPinningRequired() = !pinnedIps.isEmpty()（backend/lianyu-common/src/main/java/com/lianyu/common/util/OutboundUrlValidator.java:119-121），Ollama 本机 11434 端点被单独放行且不固定 IP（同文件 :58、:151-157），因此 ModelCatalogService.fetchOllamaModels 的 :215-217 正好命中该分支；ModelCatalogService.fetchOpenAiCompatibleModels 的 :184-188 也没有把该调用包在 try 里。
- 影响：自建/本机 Ollama 或受信兼容端点配置后，模型列表拉取使用无超时客户端；对端半开连接（TCP 已建立但不回字节）时线程长期被占用，按该文件自述的现网事故，这类挂起会拖垮整体 HTTP 线程池，表现为全站请求排队。
- 修复摘要：两个 pinning builder 的非固定分支复用同一套超时配置，杜绝零超时客户端。
- 修复文件：backend/lianyu-ai/src/main/java/com/lianyu/ai/SsrfPinningClientFactory.java、backend/lianyu-service/src/main/java/com/lianyu/service/ai/ModelCatalogService.java、backend/lianyu-ai/src/test/java/com/lianyu/ai/SsrfPinningClientFactoryTest.java
- 建议测试：（无）

### REL-6497C · [中] [u01] — 后台任务如果类型名对不上（比如升级期间新旧版本混跑），消息会被直接确认掉，既不重试也不进死信队列，用户的记忆摘要、日记这类内容就永久缺失，只留一行 warn。
- 简述：后台任务如果类型名对不上（比如升级期间新旧版本混跑），消息会被直接确认掉，既不重试也不进死信队列，用户的记忆摘要、日记这类内容就永久缺失，只留一行 warn。
- 详细问题：后台 AI 任务遇到未知类型只打 warn 就 ack，任务静默消失
- 专家 / section：reliability / error-handling
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/ai/background/AiBackgroundConsumer.java:94`
- 置信度：high · kind：plan
- 证据：dispatch 的 `default -> log.warn("AI background unknown type: {}", task.type())`（:94）之后方法正常返回，RabbitMQ 视为消费成功并 ack，消息既不重投也不进 DLQ；同文件 isAgentTransient（:102-116）通过匹配中文消息文本（"prompt 组装失败"/"消息发送失败"/"上游超时…"）判断是否重试，上游文案一改重试策略就静默失效。
- 影响：发布期间新旧版本混跑、或任务类型改名后，旧消息被直接丢弃：记忆摘要、角色日记、朋友圈等后台生成永久缺失且不可补；运维在日志里只看到一行 warn，没有计数或告警；文案驱动的重试判定还会把可重试故障悄悄变成不可重试。
- 修复摘要：未知类型不能静默 ack：要么进 DLQ 供人工/后续版本重放，要么按可重试异常退回队列；瞬断判定改为异常类型而不是中文文案。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/ai/background/AiBackgroundConsumer.java、backend/lianyu-service/src/test/java/com/lianyu/service/ai/background/AiBackgroundConsumerTest.java
- 建议测试：（无）

### REL-DE7E0 · [中] [u08] — 用户关窗口后，服务端仍在替这轮对话调用模型，最长三十分钟，费用照付；结果没人接收，既不保存也不报错。
- 简述：用户关窗口后，服务端仍在替这轮对话调用模型，最长三十分钟，费用照付；结果没人接收，既不保存也不报错。
- 详细问题：客户端断开后不取消上游模型流，SSE 30 分钟超时也无回调
- 专家 / section：reliability / resilience
- 位置：`backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentStreamAdapter.java:92`
- 置信度：high · kind：plan
- 证据：`client.streamChat(...).subscribe(relay::accept, error -> { log.warn(...); relay.fail("消息发送失败，请稍后再试"); }, relay::finish)`（:92-98）：relay::accept 返回 boolean（AgentStreamBridge.accept 在发送失败时 return false，:74-78、:83-87），作为 Consumer 被丢弃；返回的 Disposable 没有保存；emitter 上没有注册 onCompletion/onError/onTimeout。SSE_TIMEOUT_MS = 1_800_000L（:25），超时后 SseEmitter 自行 complete，但没有任何代码取消上游 Flux。
- 影响：一次关窗/断网之后，Java→Python→模型供应商的流继续跑（最长 30 分钟或被上游读超时打断），token 照常计费；由于没有消费者，结果既不落库也不报错，只在日志里留一行——既烧钱又不可观测，还会占用 bulkhead/连接池名额，放大高峰期排队。
- 修复摘要：把 SseEmitter 的生命周期接到上游订阅上：断开/超时即 dispose，避免空转计费。
- 修复文件：backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentStreamAdapter.java、backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentStreamBridge.java、backend/lianyu-web/src/test/java/com/lianyu/web/agent/AgentStreamAdapterTest.java
- 建议测试：（无）

### REL-1D45F · [中] [u01] — 角色突然不主动找人聊天、不写日记、不总结会话时，服务端日志里一行都看不到，只能靠猜是没触发还是每次都失败。
- 简述：角色突然不主动找人聊天、不写日记、不总结会话时，服务端日志里一行都看不到，只能靠猜是没触发还是每次都失败。
- 详细问题：主动消息/群聊/摘要等后台 AI 失败只落 debug，线上静默停摆不可见
- 专家 / section：reliability / observability
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ProactiveChatScheduler.java:173`
- 置信度：high · kind：plan
- 证据：ProactiveChatScheduler 三处失败分支只 `log.debug("Proactive chat skipped ...")`（:173-174、:214-215、:264-265）；GroupChatService 主动群聊失败 `log.debug("Proactive group chat skipped: ...")`（:288-289）；SessionSummaryService 读取失败 `log.debug`（:114-115）；MomentsScheduler tick 失败 `log.debug("Moments tick skipped: ...")`（:144-145）；MomentsCommentService 角色评论失败 `log.debug`（:158-161）；MemoryCacheService 读写失败也只 debug（:70-72、:85-86）。生产默认 INFO 级别，这些行不会进日志。
- 影响：模型/记忆/网关整体不可用时，用户感知是「角色不再主动找我、不写日记、不总结」，而服务端日志与指标完全安静，无法区分「没有触发」与「每次都失败」，也无法在故障扩散前发现；排障只能靠临时调级别或加日志。
- 修复摘要：后台 AI 链路的失败要能被看见：加带 reason 标签的计数器，并采样 warn，细节仍留 debug。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ProactiveChatScheduler.java、backend/lianyu-service/src/main/java/com/lianyu/service/conversation/GroupChatService.java、backend/lianyu-service/src/main/java/com/lianyu/service/conversation/SessionSummaryService.java、backend/lianyu-service/src/main/java/com/lianyu/service/moments/MomentsScheduler.java
- 建议测试：（无）

### REL-DD8A3 · [中] [u10] — 前端测试门禁在同一份代码上跑两次结果不同：忙的时候 8 个用例失败，闲的时候全绿，真正的回归容易被当成偶发忽略。
- 简述：前端测试门禁在同一份代码上跑两次结果不同：忙的时候 8 个用例失败，闲的时候全绿，真正的回归容易被当成偶发忽略。
- 详细问题：前端门禁默认并行且未按 AGENTS.md 串行，同一 commit 两次结果不同
- 专家 / section：reliability / tests
- 位置：`frontend/package.json:18`
- 置信度：high · kind：direct
- 证据：`"test": "vitest --passWithNoTests"`（frontend/package.json:18）没有 --no-file-parallelism；.github/workflows/ci.yml:39 的 frontend job 也是 `npx vitest run`；AGENTS.md §4 明确要求 `npx vitest run --no-file-parallelism` 并注明 updater 并发用例是已知 flake。实测同一 commit：机器满载时 `Test Files 5 failed | 70 passed (75)`、`Tests 8 failed | 451 passed (459)`（wechatChannelRelease.test.js：`findWechatChannelLaunch(root)?.hostPath` 为 undefined、`ENOENT ...wechat-channel\v0.1.0\payload\node.exe`；bootstrap / bootstrapRestoreRoute / launcherBootstrap 三条均 `Test timed out in 5000ms`）；机器空闲时 `Test Files 75 passed (75)`、`Tests 459 passed (459)`。
- 影响：门禁结果取决于机器负载：真回归会被当成 flake 重跑掉，假红又逼着人反复重跑；本地脚本、CI 与文档三处口径不一致，等于没有统一门禁，提交前的「全绿」不能作为可靠性证据。
- 修复摘要：统一门禁口径：脚本与 CI 都按 AGENTS.md 串行执行，并给慢用例显式超时。
- 修复文件：frontend/package.json、.github/workflows/ci.yml、frontend/src/auth/__tests__/bootstrap.test.js、frontend/src/auth/__tests__/launcherBootstrap.test.js
- 建议测试：（无）

### REL-23428 · [中] [u10] — 后台管理端的测试从来没在 CI 跑过，连依赖都没装，测试脚本还允许「一个用例都没有」直接通过。
- 简述：后台管理端的测试从来没在 CI 跑过，连依赖都没装，测试脚本还允许「一个用例都没有」直接通过。
- 详细问题：admin 模块测试不在 CI 内且脚本带 --passWithNoTests
- 专家 / section：reliability / tests
- 位置：`admin/package.json:9`
- 置信度：high · kind：plan
- 证据：admin/package.json:9 `"test": "vitest --passWithNoTests"`；仓库里只有 admin/src/__tests__/menu.test.js（8 行，只断言菜单顺序）；.github/workflows/ci.yml 只有 backend / frontend / deps-audit 三个 job（:9-55），没有 admin；本次实测 `npm --prefix admin test` 报 `'vitest' 不是内部或外部命令`（admin/node_modules 不存在）。
- 影响：管理端（登录、发布、用户处置）没有任何自动化回归保护，测试写坏或依赖装错时 CI 永远绿；--passWithNoTests 还会在测试被误删时静默通过，把「测了」变成假象。
- 修复摘要：把 admin 纳入门禁并去掉空跑许可，至少覆盖登录/发布/用户处置的失败分支。
- 修复文件：admin/package.json、.github/workflows/ci.yml、admin/src/__tests__/menu.test.js、AGENTS.md
- 建议测试：（无）

### REL-E91A4 · [中] [u08] — 这轮最严重的两个问题——断流时保存半截回复、重试导致重复发送——都落在完全没有测试的代码路径上，修完也没有护栏。
- 简述：这轮最严重的两个问题——断流时保存半截回复、重试导致重复发送——都落在完全没有测试的代码路径上，修完也没有护栏。
- 详细问题：SSE 断流/取消与发送重试路径无任何回归测试
- 专家 / section：reliability / tests
- 位置：`backend/lianyu-web/src/test/java/com/lianyu/web/agent/AgentStreamBridgeTest.java:25`
- 置信度：high · kind：plan
- 证据：AgentStreamBridgeTest 只覆盖正常 finish 与错误帧（:178、:191 两处 newRelay 用例）；在 backend/**/src/test 中检索 AgentStreamBridge|AgentStreamAdapter|isClientStreamDisconnect|abort() 仅命中 11 行，没有任何 abort 用例；前端也没有覆盖 ChatPage.handleSend:1437-1446 的「网络错误后重放同一请求」分支；backend-agent 侧 1157 个用例中同样没有「客户端取消不应计入熔断」的断言（见 resilience 发现的复现脚本）。
- 影响：报告中的 critical/high 缺陷（半截回复落库、重复发送、取消误记熔断）都位于无测试路径，修复后没有回归护栏，重构或改动异常类型时极易复发；同时也说明现有 459+1157+365 个用例的「全绿」并不能覆盖失败传播链。
- 修复摘要：为三条失败路径补最小回归用例：断流不落库、重试幂等、取消不记账。
- 修复文件：backend/lianyu-web/src/test/java/com/lianyu/web/agent/AgentStreamBridgeTest.java、frontend/src/pages/__tests__/chatSendRetry.test.js、backend-agent/tests/test_gateway.py
- 建议测试：（无）

### MNT-B3F89 · [中] [u04] — 仓库 scripts/ 下堆了 81 个写一次就完的排障脚本（约 7600 行），绝大多数没有任何文档或流水线引用，其中 21 个还落在 .gitignore 已声明为一次性产物的目录里；看目录无法判断哪个还能跑、哪个早就失效。
- 简述：仓库 scripts/ 下堆了 81 个写一次就完的排障脚本（约 7600 行），绝大多数没有任何文档或流水线引用，其中 21 个还落在 .gitignore 已声明为一次性产物的目录里；看目录无法判断哪个还能跑、哪个早就失效。
- 详细问题：81 个一次性排查脚本已入库，其中 72 个在全仓库无任何引用
- 专家 / section：maintainability / cleanup
- 位置：`scripts/_cloud_verify.py:1`
- 置信度：high · kind：plan
- 证据：证据：git ls-files 'scripts/_*' = 60 个（6173 行）、git ls-files 'scripts/diag/*' = 21 个（1461 行），合计 81 个一次性脚本入库。对 81 个文件名做全仓引用扫描（docs/、AGENTS.md、.github/、frontend/ 一起扫）：72 个零引用；仅 9 个被文档或流程引用（_cloud_deploy_pull.py、_cloud_deploy_layered.py、_upload_update_assets.py、_upload_wechat_channel.py、_prod_verify.py、_gate_installer_prompt.ps1、diag/README.md、diag/_diag_pet_tts_logs.py、diag/_gen_pet_fixed_voices.py）。近似重复族：_cloud_verify.py / _cloud_verify_deploy.py / _cloud_verify_logs.py / _cloud_verify_post.py / _prod_verify.py / _run_prod_verify.py 六个都在做「部署后自检」；_repair_flyway_v31.py、_repair_flyway_v34.py、ops/_repair_flyway_v30_mark_applied.py、ops/_repair_flyway_v34_mark_applied.py 是同一类 Flyway 补账脚本。.gitignore:106-107 已把 scripts/diag/ 与 artifacts/ 标为一次性产物，AGENTS.md §3.1 也要求 `_` 前缀脚本不进仓库，但 21 个 diag 文件仍是 tracked。
- 影响：scripts/ 既放门禁与发版入口（release_assets.json、check-java-ai-free.ps1、check_release_asset_contract.py），又放历史垃圾，改动时无法靠目录判断哪些是必跑门禁；六个近似「部署自检」并存，没人知道该跑哪个，部署口径会分叉。
- 修复摘要：把 scripts/ 收敛成「门禁 + 发版 + 运维常驻」三类，一次性脚本出仓或归档成台账，重复族合并为带子命令的单一脚本。
- 修复文件：scripts/、.gitignore、AGENTS.md
- 建议测试：powershell -File scripts/check-java-ai-free.ps1 -Mode strict；python scripts/check_release_asset_contract.py；python scripts/_cloud_deploy_pull.py --help

### MNT-D683B · [中] [u10] — 后台管理页看起来能用，实际「新建版本」表单点保存毫无反应，窗口最小化/最大化/关闭按钮不生效，首页「最新客户端 v0.2.363 / 待处理 12 项」是写死在模板里的假数据；靠后台判断线上现状会判断错。
- 简述：后台管理页看起来能用，实际「新建版本」表单点保存毫无反应，窗口最小化/最大化/关闭按钮不生效，首页「最新客户端 v0.2.363 / 待处理 12 项」是写死在模板里的假数据；靠后台判断线上现状会判断错。
- 详细问题：admin 后台是空壳：表单无提交函数、标题栏三键取不到 window、概览数据写死
- 专家 / section：maintainability / cleanup
- 位置：`admin/src/pages/ManagementPage.vue:15`
- 置信度：high · kind：plan
- 证据：证据：admin/src/pages/ManagementPage.vue:15 表单 `<form class="dialog-panel" @submit.prevent="save">`，但该文件脚本段（第 1-13 行）没有定义 save；同文件 import 的 createRelease 无使用点。admin/src/App.vue:14 标题栏 `<button @click="window.adminWindow?.minimize()">−</button>`（另有 toggleMaximize / close 两键）：模板里 window 不在 setup 返回的作用域，解析到组件上下文而非全局 window，三个按钮都不会触发 IPC（preload.cjs 暴露的是 window.adminWindow）。admin/src/App.vue:14 概览写死 `<strong>v0.2.363</strong>`、`<strong>12</strong>` 与三条硬编码活动行；admin/src/api.js:6 定义 getOverview() 但全仓无调用。admin/package.json 的 8 个依赖里，echarts、@tanstack/vue-table、vue-router、@element-plus/icons-vue 在 admin/src 与 admin/electron 全仓 0 命中。admin 侧文件极小：ManagementPage.vue 15 行、DataTable.vue 5 行、stores/session.js 2 行、api.js 9 行。
- 影响：后台是运维判断「线上什么版本、有什么待办」的入口，写死数据会给出错误现状；表单静默失败会让人以为草稿已保存。任何一次后台验收都要先在这些假象上耗时间。
- 修复摘要：先把「看起来能用其实不通」的入口收掉（禁用或标注未实现），再决定后台是继续做还是下线；概览改为读真实数据。
- 修复文件：admin/src/App.vue、admin/src/pages/ManagementPage.vue、admin/src/api.js、admin/src/__tests__/menu.test.js
- 建议测试：npm --prefix admin install && npm --prefix admin test；手工：后台点最小化/最大化/关闭，点新建版本保存

### MNT-35DF8 · [中] [u04] — 有一份从 Java 抄过来的文案基线，来源里的 5 个 Java 文件已删，但生成脚本还在；照报错提示去「重新生成」会把这 45 条悄悄丢掉，测试还不会红。
- 简述：有一份从 Java 抄过来的文案基线，来源里的 5 个 Java 文件已删，但生成脚本还在；照报错提示去「重新生成」会把这 45 条悄悄丢掉，测试还不会红。
- 详细问题：已冻结的 Java prompt 片段基线，配套生成器仍会静默把 56 条截成 11 条
- 专家 / section：maintainability / cleanup
- 位置：`scripts/extract-java-prompt-fragments.py:118`
- 置信度：high · kind：direct
- 证据：证据：backend-agent/app/prompts/fragments.json 共 56 个 block，来自 8 个文件；其中 ChatTurnPromptAssembler.java(8)、CharacterPromptBuilder.java(1)、ReplyBehaviorRuleHook.java(24)、OutputLanguageRuleHook.java(4)、GroupChatRuleHook.java(8) 已不存在（45/56 block 的来源已删）。scripts/extract-java-prompt-fragments.py:118-121 对缺失来源只打印 `skip (missing)` 后 continue，:122-127 直接 OUT.write_text 覆盖，跑一次只剩 11 条且无告警。backend-agent/app/prompt_fragments.py:29-32 的报错文案仍教人 `run: uv run python scripts/extract-java-prompt-fragments.py`；同文件 docstring:12 仍称测试会校验「重新生成与仓库 JSON 一致」，而 backend-agent/tests/test_prompt_fragments.py:38-42 已改成「Java prompt 源码已删除，fragments.json 是冻结基线」，不再校验来源。消费方 backend-agent/app/prompt_assembly.py 仍按 (java 文件名, 行号) 取文本：_TIME_PREFIX=("ChatTurnPromptAssembler.java",161)、_CITY_PREFIX=("ChatTurnPromptAssembler.java",183)、_NATURAL_STYLE_LINES 用 OutputLanguageService.java:162-197，且被 app/system_prompt.py:181/187/189/197 真实调用。
- 影响：维护者按报错指引重新生成基线，会得到缺 45 条的文件，测试不红，线上时间前缀/城市前缀/自然口语风格文案静默退化成空，只在特定语言或场景的回复里才看得出来。
- 修复摘要：承认这份 JSON 是冻结资产：让生成器不可再运行、改掉报错文案与 docstring 的「重新生成」指引，并补一条基线键位断言。
- 修复文件：scripts/extract-java-prompt-fragments.py、backend-agent/app/prompt_fragments.py、backend-agent/tests/test_prompt_fragments.py
- 建议测试：cd backend-agent && python -m pytest tests/test_prompt_fragments.py -q

### MNT-BEB26 · [中] [u07] — 「把 zh_HK 这类写法统一成标准语言代码」的规则被抄了 5 遍，其中一份不认香港繁体；同一个 zh-hk 用户，在语言闸门里被当简体中文，在提示词渲染里又当繁体中文。
- 简述：「把 zh_HK 这类写法统一成标准语言代码」的规则被抄了 5 遍，其中一份不认香港繁体；同一个 zh-hk 用户，在语言闸门里被当简体中文，在提示词渲染里又当繁体中文。
- 详细问题：语言代码归一化在 Python 里重复 5 份，zh-hk 在两条路径上归一结果不同
- 专家 / section：maintainability / simplify
- 位置：`backend-agent/app/language_gate.py:38`
- 置信度：high · kind：plan
- 证据：证据：五份实现 —— backend-agent/app/language_gate.py:38-50 from_code（接受 zh-tw / zh_tw / zhtw / zh-hant，不含 zh-hk）、backend-agent/app/prompt_assembly.py:111-119 normalize_language（先 replace('_','-')，接受 zh-tw / zhtw / zh-hant / zh-hk）、backend-agent/app/reply_rules.py:44-50、backend-agent/app/rule_templates.py:34-42、backend-agent/app/system_prompt.py:51-59 三处 _lang_key（replace('_','-') 后接受 zh-hk）。Java 侧同一概念还有一份：backend/lianyu-common/src/main/java/com/lianyu/common/i18n/OutputLanguage.java:27 `case "zh-tw", "zh_hant", "zh-hant", "zh_hk", "zh-hk" -> ZH_TW;`。可观察差异：输入 zh_hk 时 language_gate 归一为 zh（简体），prompt_assembly 归一为 zh-TW。
- 影响：语言是横切 prompt 组装、规则模板、语言闸门、Java 输出语言的概念，新增一个别名要改 5+1 处；已经出现 zh-hk 的口径分歧，用户会看到闸门判定与提示词语言不一致的回复。
- 修复摘要：抽一个 backend-agent/app/language_codes.py 作为唯一归一化入口（ALIASES 表 + normalize），5 处改为调用它，Java 侧用同一张表对齐。
- 修复文件：backend-agent/app/language_gate.py、backend-agent/app/prompt_assembly.py、backend-agent/app/reply_rules.py、backend-agent/app/rule_templates.py、backend-agent/app/system_prompt.py
- 建议测试：cd backend-agent && python -m pytest tests -q -k lang；mvn -q -f backend/pom.xml test -Dtest=OutputLanguage*

### MNT-817F0 · [中] [u02] — 角色广场每个角色的人设文案，既写在一批 Java 常量类里，又写在数据库迁移里；以哪份为准没人说得清，两边的角色清单也没有任何测试比对。
- 简述：角色广场每个角色的人设文案，既写在一批 Java 常量类里，又写在数据库迁移里；以哪份为准没人说得清，两边的角色清单也没有任何测试比对。
- 详细问题：角色广场名册有两份权威：Java 常量类 2429 行与 Flyway 种子并存，且无一致性校验
- 专家 / section：maintainability / coupling
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/square/CharacterSquareService.java:222`
- 置信度：high · kind：plan
- 证据：证据：Java 侧 8 个类共 2429 行 —— CharacterSquareCatalog.java(462)、CatalogGenshin.java(590)、CatalogDal.java(421)、CatalogBlueArchive.java(369)、CatalogReZero.java(326)、CatalogDanganronpa.java(227)、CatalogHonkai.java(61)、CatalogShizhong.java(61)，54 个 slug、162 个 `private static String promptXxx()` 返回四语种人设文本（例：CharacterSquareCatalog.java:201-207 promptZhGanyu()）。运行时优先用 Java 常量：CharacterSquareService.java:222 `String resolvedPrompt = promptPack != null ? promptPack.prompt() : template.getPromptTemplate();`、:378 `.promptTemplate(pack.prompt())`。同一批 slug 又由 Flyway 写入 character_square_template（V10/V11/V12/V13/V14/V17/V18/V19/V25/V26/V27/V29/V36/V37/V45）。测试只断言数量与四语种非空（CharacterSquareCatalogTest.java:17-56，slug>=51、locale pack 非空），不与 DB 行交叉校验。
- 影响：新增或改名一个广场角色要同时改 Java 常量、Flyway 迁移、头像文件三处，漏一处不会红；DB 的 prompt_template 实际被 Java 常量覆盖，改 DB 文案不生效——这层关系只写在 CharacterSquareService.java:222 一行里。
- 修复摘要：先定「人设文案的 owner」，再让另一侧退化为只读或删除，并加一条 Java slug 集合与 DB 行集合的比对测试。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/square/CharacterSquareService.java、backend/lianyu-service/src/main/java/com/lianyu/service/square/CharacterSquareCatalog.java、backend/lianyu-service/src/test/java/com/lianyu/service/square/CharacterSquareCatalogTest.java、backend/lianyu-dao/src/main/resources/db/migration/V18__character_square_genshin.sql
- 建议测试：mvn -q -f backend/pom.xml test -Dtest=CharacterSquareCatalogTest

### MNT-CE01E · [中] [u05] — 角色广场名册不是一次写好的，而是靠 15 个迁移脚本一批批插入、再用 4 个补丁修正写错的条目（包括把重复的角色合并掉）；加一个角色就要再加一个迁移。
- 简述：角色广场名册不是一次写好的，而是靠 15 个迁移脚本一批批插入、再用 4 个补丁修正写错的条目（包括把重复的角色合并掉）；加一个角色就要再加一个迁移。
- 详细问题：一个角色广场表的种子数据散在 19 个迁移脚本里，其中 4 个是事后纠错补丁
- 专家 / section：maintainability / simplify
- 位置：`backend/lianyu-dao/src/main/resources/db/migration/V39__merge_eriri_into_erii_uesugi.sql:1`
- 置信度：high · kind：plan
- 证据：证据：backend/lianyu-dao/src/main/resources/db/migration/ 共 V1–V57；涉及 character_square_template 的有 19 个迁移，其中 15 个含 INSERT（V10 6 行、V12 1、V13 9、V14 1、V15 1、V17 1、V18 9、V19 1、V25 4、V26 4、V27 7、V29 7、V36 2、V37 1、V45 1，合计 55 次插入），另外 V11 补 slug、V16 精简标签、V38 纠正 eriri 身份、V39 合并重复上杉绘梨衣（eriri → erii_uesugi）并删除一行。纠错型迁移原话：V12「替换为带头像的 7 人 roster」、V15「修正已存在的 linzihan 行」、V39「下线误加的 eriri，并把 VC/计数归到前者」。V30__version_gap_placeholder.sql 只有 2 行占位。
- 影响：想回答「广场现在有哪些角色、某人设是什么」必须读完这 19 个 SQL 并叠加 V39 的删除合并；每次改名/纠错都要再写一个迁移，版本号只增不减，新环境初始化要靠按顺序执行全部 55 次插入与 4 个补丁才能到达正确状态。
- 修复摘要：把名册收敛成一份可读的种子文件 + 一个幂等同步，后续增改只改种子文件；历史迁移保持不动。
- 修复文件：backend/lianyu-dao/src/main/resources/db/migration/、backend/lianyu-dao/src/main/resources/db/migration/V39__merge_eriri_into_erii_uesugi.sql
- 建议测试：mvn -q -f backend/pom.xml test；空库 flyway migrate 后比对 character_square_template 行数

### MNT-90230 · [中] [u10] — 产品当前客户端版本是 0.3.15，后台管理端还写着 0.2.363，后台首页又把 v0.2.363 写死成「最新客户端」；运维照后台看会以为线上停在很久以前的版本。
- 简述：产品当前客户端版本是 0.3.15，后台管理端还写着 0.2.363，后台首页又把 v0.2.363 写死成「最新客户端」；运维照后台看会以为线上停在很久以前的版本。
- 详细问题：客户端版本号出现三处口径：frontend 0.3.15、admin 0.2.363、后台界面写死 v0.2.363
- 专家 / section：maintainability / coupling
- 位置：`admin/src/App.vue:14`
- 置信度：high · kind：plan
- 证据：证据：frontend/package.json:3 `"version": "0.3.15"`（AGENTS.md §3.1 指定的版本号唯一事实来源）；admin/package.json:3 `"version": "0.2.363"`；admin/src/App.vue:14 概览 `<strong>v0.2.363</strong><span class="muted">stable · 已发布</span>` 与活动行 `客户端版本发布 v0.2.363`。frontend/src 内已无 0.2.363 字样（仅 updater 测试用 0.2.368/0.2.367 样例），说明这个数字是从旧客户端版本抄进后台模板后无人更新。
- 影响：后台是「该发什么版本、线上是什么版本」的唯一界面提示，写死旧版本会误导发版判断；两个 package.json 各自 bump 会让发版脚本与 admin 打包版本持续漂移。
- 修复摘要：后台版本改为读 admin 自身版本或后台 API，「最新客户端」取真实发布数据；把「admin 只读版本」写进单一事实来源清单。
- 修复文件：admin/src/App.vue、admin/src/api.js、admin/package.json、frontend/package.json
- 建议测试：npm --prefix admin install && npm --prefix admin test

### MNT-46938 · [低] [u09] — 桌面客户端的预加载白名单里留着 8 个界面从未调用过的窗口操作接口，主进程里对应的处理函数也还在；改桌宠拖拽时容易改到已经失效的那一份。
- 简述：桌面客户端的预加载白名单里留着 8 个界面从未调用过的窗口操作接口，主进程里对应的处理函数也还在；改桌宠拖拽时容易改到已经失效的那一份。
- 详细问题：preload 暴露 8 个零调用 IPC 通道，主进程仍保留对应 handler
- 专家 / section：maintainability / cleanup
- 位置：`frontend/electron/preload.js:28`
- 置信度：high · kind：direct
- 证据：证据（CONFIRMED_DEAD）：frontend/electron/preload.js:11 touchPickerInteraction、:28 saveLauncherPosition、:29 moveLauncherByDelta、:33 setLauncherScreenPosition、:34 setLauncherDragging、:35 clampLauncherPosition、:101 onPickerShown、:171 getGlobalLogs。对 8 个标识符在 frontend/src（含唯一入口 src/entries/launcher-main.js:launcher.html:22、src/entries/quick-main.js:quick.html:25）grep 结果为 0 命中（排除 node_modules 与 dist 构建产物）。main.js 仍注册 desktop:picker-interaction:2106、desktop:get-global-logs:2610、desktop:save-launcher-position:2709、desktop:move-launcher-by-delta:2717、desktop:set-launcher-screen-position:2772、desktop:set-launcher-dragging:2784、desktop:clamp-launcher-position:2794。
- 影响：桌宠/选择器的位置逻辑现在有「界面已不调用的 IPC」与「实际在用的内部函数」两套并存；按 preload 名单推断能力边界会得出错误结论。preload.js 93 个方法 vs main.js 69 个 handler，两份清单只能人工比对，死通道越多越难核。
- 修复摘要：确认真机不依赖后，成组删除 preload 的 8 个暴露项与 main.js 的 7 个 handler，保留仍被内部调用的位置计算函数为模块私有。
- 修复文件：frontend/electron/preload.js、frontend/electron/main.js
- 建议测试：node --check frontend/electron/preload.js && node --check frontend/electron/main.js；npm --prefix frontend test -- --no-file-parallelism；手工冒烟：桌宠拖拽 / 快捷聊天 / 选择器

### MNT-B2E26 · [低] [u10] — 仓库文档要求前端测试串行执行，但 package.json 的 test 脚本没带串行参数；照这个脚本跑会有 4 个用例超时失败，让人误以为改坏了东西。
- 简述：仓库文档要求前端测试串行执行，但 package.json 的 test 脚本没带串行参数；照这个脚本跑会有 4 个用例超时失败，让人误以为改坏了东西。
- 详细问题：frontend 的 npm test 没按 AGENTS.md 串行跑，默认并行会 4 个用例超时
- 专家 / section：maintainability / cleanup
- 位置：`frontend/package.json:18`
- 置信度：high · kind：direct
- 证据：证据：frontend/package.json:18 `"test": "vitest --passWithNoTests"`（无 run、无 --no-file-parallelism），与 AGENTS.md §4「cd frontend && npx vitest run --no-file-parallelism」不一致。实测 npm --prefix frontend test：`Test Files 4 failed | 71 passed (75)`、`Tests 4 failed | 455 passed (459)`，4 个失败全是 `Error: Test timed out in 5000ms`（src/auth/__tests__/bootstrap.test.js:29、bootstrapRestoreRoute.test.js:37、launcherBootstrap.test.js:19 与 updater 相关 1 个）；追加 `-- --no-file-parallelism` 后 `Test Files 75 passed (75)`、`Tests 459 passed (459)`。
- 影响：本地/CI 只要按 package.json 跑就 4 红，与文档口径不一致；后来者会花时间排查这 4 个 flake，或误改断言迁就并行。
- 修复摘要：把 test 脚本改成与门禁一致的串行 run，并让文档只引用这一条命令。
- 修复文件：frontend/package.json、AGENTS.md
- 建议测试：npm --prefix frontend test

### MNT-86BE4 · [低] [u09] — 桌面客户端主进程只有一个 3000 多行的装配文件，窗口、托盘、快捷聊天、选择器、日志、桥接全在里面；任何一个小改动都要在整文件里找位置。
- 简述：桌面客户端主进程只有一个 3000 多行的装配文件，窗口、托盘、快捷聊天、选择器、日志、桥接全在里面；任何一个小改动都要在整文件里找位置。
- 详细问题：electron/main.js 3077 行塞满窗口、托盘、IPC 与桥接装配，69 个 ipcMain.handle 同处一文件
- 专家 / section：maintainability / simplify
- 位置：`frontend/electron/main.js:2106`
- 置信度：medium · kind：plan
- 证据：证据：frontend/electron/main.js 共 3077 行，含 69 处 `ipcMain.handle(`；同目录已有拆分先例：frontend/electron/updater/updater.js 独立承接 updater:* 通道（:449 ipcMain.handle('updater:check')、:486 'updater:probe'）、desktopSettings.js 承接设置默认值、napcatRuntime/ 与 qqBridge/ 各自成目录。preload.js 仅 187 行却是 93 个方法的白名单，与 main.js 的 69 个 handler 需要人工对齐。
- 影响：主进程是崩溃面最大的部分，3077 行让「哪段代码负责哪个窗口」只能靠搜索；新增 IPC 时容易漏改 preload 白名单（93 对 69 已经对不齐），review 只能肉眼比对。
- 修复摘要：按窗口/领域把 IPC 注册拆到 electron/ipc/*.js，main.js 只留装配与生命周期，通道名与行为不变。
- 修复文件：frontend/electron/main.js、frontend/electron/preload.js、frontend/electron/updater/updater.js
- 建议测试：npm --prefix frontend test -- --no-file-parallelism；手工冒烟：托盘 / 桌宠 / 快捷聊天 / 选择器 / 桥接

### MNT-7031C · [低] [u10] — 前端 package.json 声明了一个 Electron 渲染端插件，但构建配置用的是另一个插件，这个依赖从来没被 import 过，每次安装都在装、锁文件里也占位。
- 简述：前端 package.json 声明了一个 Electron 渲染端插件，但构建配置用的是另一个插件，这个依赖从来没被 import 过，每次安装都在装、锁文件里也占位。
- 详细问题：frontend 依赖 vite-plugin-electron-renderer 全仓库没有任何 import
- 专家 / section：maintainability / cleanup
- 位置：`frontend/package.json:44`
- 置信度：high · kind：plan
- 证据：证据：frontend/package.json:44 `"vite-plugin-electron-renderer": "^1.0.0"`（devDependencies）；全仓 grep 该 specifier 只命中 frontend/package.json:44 与 frontend/package-lock.json:34/7608/7611/7618，无任何 import/require。frontend/vite.config.js 用的是同一套插件的另一个入口 `vite-plugin-electron/simple`。
- 影响：依赖清单失去「装了就一定在用」的可信度，升级与安全审计时多一个需要单独判断去留的条目。
- 修复摘要：从 devDependencies 删除该依赖并刷新锁文件，跑一遍构建与测试确认无影响。
- 修复文件：frontend/package.json、frontend/package-lock.json
- 建议测试：cd frontend && npx vite build；npm --prefix frontend test -- --no-file-parallelism

### PRF-AF9AC · [高] [u07] — 每次和模型服务商对话都要重新建立一条加密连接，握手期间用户只能干等，首字延迟被固定拉长一两百毫秒。
- 简述：每次和模型服务商对话都要重新建立一条加密连接，握手期间用户只能干等，首字延迟被固定拉长一两百毫秒。
- 详细问题：模型网关每次调用都新建 httpx 客户端，每个回合多付一次 TCP/TLS 握手
- 专家 / section：perf / hot-path
- 位置：`backend-agent/app/gateway.py:296`
- 置信度：high · kind：plan
- 证据：gateway.py:296-299 `if self._client is not None: client_cm = _null_context(self._client) else: client_cm = new_client()`；流式分支 gateway.py:427 也是 `async with new_client() as client:`；而 get_gateway()（gateway.py:489-490）永远 `return ModelGateway()`，构造参数 client 从未被注入，因此所有调用都走新连接。实测（本地 ThreadingHTTPServer 统计 accept）：连续 3 次 `get_gateway().chat_messages(...)` 新增 3 条 TCP 连接；同样 3 次调用改用 `ModelGateway(client=shared_client("perf-repro"))` 只新增 1 条。仓库自述成本见 http_client.py:47-54：「境内到阿里云 300-400ms…实测冷首包 2690ms vs 热 314ms」「每句一次 POST，重新握手 150-300ms」。调用点：chat_turn.py:261/346、main.py:162/227/329/368/747/794/848/868/926。
- 影响：每个对话回合（含工具循环多轮、会话标题、主动消息、桌面感知）在首 token 前固定多付 1 次 TCP+TLS 握手，境内 150-300ms、跨云更差；高峰期大量短连接还会在服务端堆 TIME_WAIT，并放大连接数与 TLS CPU。用户体感是「点了没反应」的那一下。
- 修复摘要：让网关默认复用进程级 httpx.AsyncClient（仓库已有 shared_client），只在需要独立生命周期时才新建。
- 修复文件：backend-agent/app/gateway.py、backend-agent/app/http_client.py、backend-agent/app/chat_turn.py
- 建议测试：连接计数探针：3 次 chat_messages 只新增 1 条 TCP（当前 3 条）；pytest -q backend-agent/tests/test_resilience.py backend-agent/tests/test_stream_tool_fallback.py

### PRF-4CB43 · [中] [u07] — 记忆搜索和语音合成也要每次重新连线：一次「回忆」最多连三四次，一句话的语音要连两次，所以回得慢、出声也慢。
- 简述：记忆搜索和语音合成也要每次重新连线：一次「回忆」最多连三四次，一句话的语音要连两次，所以回得慢、出声也慢。
- 详细问题：记忆检索与 TTS 合成同样逐次 new_client()：一次记忆工具调用最多 3 次握手，一句语音 2 次
- 专家 / section：perf / hot-path
- 位置：`backend-agent/app/memory/vector_cache.py:95`
- 置信度：high · kind：plan
- 证据：vector_cache.py:95 `query_vector = await embed([query])` → embedding.py:47-49 在 client 为 None 时 `async with new_client() as own: response = await own.post(...)`；memory_search_tool.py:98 又 `await embed([query.strip()])`，:139 `rank_by_vector(...)` 内部再次 embed（vector_cache.py:95），一次记忆检索最多 3 条新连接。语音侧 tts.py:183（合成请求）与 tts.py:211（音频下载）各 `async with new_client() as client:`，一句非流式语音 2 条新连接；对照 tts.py:298 的流式合成已改用 `shared_client("dashscope-tts")`，说明修复范式已在仓库内。
- 影响：每次记忆检索多付 2-3 次握手（约 300-900ms），每次非流式语音多付 2 次（约 300-600ms）；语音通话逐句合成时延迟逐句叠加，表现为「说完要顿一下才出声」。
- 修复摘要：embedding / rerank / TTS 统一改用 shared_client，把 new_client() 收敛为一次性工具函数的兜底。
- 修复文件：backend-agent/app/memory/embedding.py、backend-agent/app/memory/vector_cache.py、backend-agent/app/tools/memory_search_tool.py、backend-agent/app/tts.py、backend-agent/app/http_client.py
- 建议测试：pytest -q backend-agent/tests/test_memory_embedding.py backend-agent/tests/test_voice_tts.py；new_client 调用计数：记忆检索 0 次、单句 TTS 0 次（当前分别最多 3 次 / 2 次）

### PRF-C7F17 · [中] [u05] — 后台程序每半分钟把全站所有单聊会话翻一遍，还要给每个会话单独查一次最后一条消息来排序；用户越多这一步越慢，会拖慢数据库的整体读写。
- 简述：后台程序每半分钟把全站所有单聊会话翻一遍，还要给每个会话单独查一次最后一条消息来排序；用户越多这一步越慢，会拖慢数据库的整体读写。
- 详细问题：主动聊天扫描每 30s 全表扫 conversation，并用相关子查询表达式排序（无法走索引）
- 专家 / section：perf / data-access
- 位置：`backend/lianyu-dao/src/main/java/com/lianyu/dao/mapper/ConversationMapper.java:25`
- 置信度：high · kind：plan
- 证据：ConversationMapper.java:20-31：`WHERE c.mode = 'SINGLE' AND c.character_id IS NOT NULL ORDER BY COALESCE((SELECT MAX(m.created_at) FROM message m WHERE m.conversation_id = c.id), c.created_at) DESC LIMIT #{limit}` —— 没有 user_id 过滤；排序键是相关子查询表达式，MySQL 无法用索引完成排序，LIMIT 不能下推。调用者 ProactiveChatScheduler.java:68 在 `@Scheduled(fixedDelayString="${lianyu.chat.proactive.scan-interval-ms:30000}")`（:62-64）里执行，scan-limit 默认 40（:59-60）。静态成本上界：C 行 conversation 全扫 × 每行 1 次 message 索引探测 + C log C 的 filesort，C = 全站 SINGLE 会话数（与本次 tick 的活跃用户无关）。
- 影响：每 30 秒一次 O(C log C) 查询，C 随注册用户数线性增长；十万级会话时单次扫描达数百毫秒并长期占用一个 DB 连接，期间 message 表的读写被挤，影响所有在线用户的发消息延迟。
- 修复摘要：把「最后消息时间」物化成 conversation.last_message_at 并加复合索引，让扫描按索引顺序取前 N 行，同时按活跃用户收窄范围。
- 修复文件：backend/lianyu-dao/src/main/java/com/lianyu/dao/mapper/ConversationMapper.java、backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ProactiveChatScheduler.java、backend/lianyu-dao/src/main/resources/db/migration/
- 建议测试：EXPLAIN 对比：改造后无 filesort、扫描行数从 C 降到 limit 量级；mvn -q -f backend/pom.xml test -Dtest=ProactiveChatSchedulerRelationshipTest

### PRF-AD911 · [低] [u02] — 为了显示每条动态有几个评论，后端把评论正文整行都读出来再自己数；评论多的动态会让这个列表接口白白搬运大量文字。
- 简述：为了显示每条动态有几个评论，后端把评论正文整行都读出来再自己数；评论多的动态会让这个列表接口白白搬运大量文字。
- 详细问题：动态评论数用 selectList 拉全部评论行在内存里计数
- 专家 / section：perf / data-access
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/moments/MomentsCommentService.java:190`
- 置信度：high · kind：plan
- 证据：MomentsCommentService.java:186-197：`List<MomentsComment> rows = momentsCommentMapper.selectList(Wrappers.<MomentsComment>lambdaQuery().in(MomentsComment::getPostId, postIds))`，随后 `rows.forEach(r -> map.merge(r.getPostId(), 1, Integer::sum))` —— 只为得到一个计数，却把 content 等全部列取回并反序列化。
- 影响：一页 N 条动态、评论合计 M 行时，传输与反序列化是 O(M) 而非 O(N)；M 可为数百行文本，直接抬高动态列表接口的延迟与堆分配。
- 修复摘要：改成数据库侧 GROUP BY 计数，只回传 post_id 与 count。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/moments/MomentsCommentService.java、backend/lianyu-dao/src/main/java/com/lianyu/dao/mapper/MomentsCommentMapper.java
- 建议测试：单测：计数结果与旧实现逐条一致（含 0 值补齐）；接口返回行数 M → N，动态列表 p95 目标不高于改造前

### PRF-B7910 · [中] [u01] — 群聊里角色一条条冒泡之间的停顿，是靠把后台线程挂起来实现的；群里人一多，线程被白占着，别人的消息就得排队。
- 简述：群聊里角色一条条冒泡之间的停顿，是靠把后台线程挂起来实现的；群里人一多，线程被白占着，别人的消息就得排队。
- 详细问题：群聊气泡节奏用 Thread.sleep 占住 group-chat 线程池线程
- 专家 / section：perf / runtime
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/conversation/GroupChatService.java:588`
- 置信度：high · kind：plan
- 证据：GroupChatService.java:586-593 与 :721-731 在群聊执行器线程里 `Thread.sleep(bubbleGapMs)`；默认值 `lianyu.group.bubble-gap-ms:500`（:92-96）。执行器 GroupChatExecutorConfig.java:12-24：core 8 / max 20 / queue 300；一轮群聊对每个成员起一个 CompletableFuture（GroupChatService.java:395-399），maxGroupMembers=4（:90-91）。阻塞量估算：一轮 4 成员 × 每成员 b 条气泡 ≈ 4×(b-1)×500ms 的纯等待线程时间，分散在 4 个线程上。
- 影响：并发 K 个群聊时瞬时占用约 4K 个线程做纯等待，超过 core=8 即开始排队、超过 max=20 且队列 300 满后按拒绝策略丢弃，表现为「群里半天不冒泡、消息丢失」；同时 DB 插入与 WebSocket 广播被串在 sleep 之间，单轮群聊尾延迟随气泡数线性增长。
- 修复摘要：把「节奏」从阻塞线程改为定时投递或前端按时间戳排期，服务端一次落库并带上展示时间。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/conversation/GroupChatService.java、backend/lianyu-service/src/main/java/com/lianyu/service/config/GroupChatExecutorConfig.java、frontend/src/pages/GroupChatPage.vue
- 建议测试：压测脚本：100 并发群聊，统计 group-chat 线程活跃数与拒绝次数；mvn -q -f backend/pom.xml test -Dtest=GroupChat*

### PRF-EB0C7 · [中] [u06] — 每个窗口打开时都要先加载一整套 UI 组件库和 400KB 样式，而实际只用到其中一半；窗口越开越多、启动越慢，老机器上能感到明显迟滞。
- 简述：每个窗口打开时都要先加载一整套 UI 组件库和 400KB 样式，而实际只用到其中一半；窗口越开越多、启动越慢，老机器上能感到明显迟滞。
- 详细问题：全量引入 Element Plus：首屏 408KB 阻塞 CSS + 1.14MB 公共 chunk，实际仅用 49 种组件
- 专家 / section：perf / frontend
- 位置：`frontend/src/main.js:3`
- 置信度：high · kind：plan
- 证据：main.js:3-5 全量 `import ElementPlus from 'element-plus'` + `import 'element-plus/dist/index.css'` + dark css-vars，并在 createApp 后 `app.use(ElementPlus, ...)` 全量注册；vite.config.js:91-96 manualChunks 只拆 vue/pinia/vue-router/vue-i18n（注释明确「勿把 axios 与 element-plus 拆成跨 chunk 引用」）；vite.config.js:71 `cssCodeSplit: !isElectronPack`，打包构建下合并为单一 CSS。dist 实测：main-DTgPoq__.css = 408,299 B（`.el-` 选择器 5777 处、`.el-button` 281 处），index.html 以 `<link rel="stylesheet">` 同步加载；最大公共块 launcherBootstrap-CEXfa8Qx.js = 1,144,809 B，45 个 chunk 引用它，入口 main-DEJM46Qu.js 仅 32,779 B；assets JS 合计 ≈2.0MB、CSS 677KB。src 实际使用 49 种 el-* 组件（el-button 209 次、el-icon 166、el-form-item 121、el-input 45），图标已按需 import。
- 影响：每个窗口首帧前都要解析 408KB CSS 并求值 1.14MB 公共 chunk（Electron 本地 file:// 加载，成本主要是解析/求值而非网络）；三个常驻窗口各付一次，启动更慢、常驻内存更高，低配机上表现为打开聊天窗明显卡顿。
- 修复摘要：按需引入 Element Plus（unplugin-vue-components + ElementPlusResolver），并把组件库拆成独立 async chunk 供主窗按需加载。
- 修复文件：frontend/src/main.js、frontend/vite.config.js、frontend/package.json
- 建议测试：npm --prefix frontend run build 后对比 main-*.css 与最大 chunk 字节数（目标各降 ≥ 50%）；node frontend/scripts/smoke-main-startup.mjs 记录首帧耗时

### PRF-87846 · [低] [u06] — 聊天窗里的消息只增不减，每来一条新消息（或每 10 秒一次轮询）都要把整张消息表重新拼一遍；聊得越久、往上翻得越多，界面越卡。
- 简述：聊天窗里的消息只增不减，每来一条新消息（或每 10 秒一次轮询）都要把整张消息表重新拼一遍；聊得越久、往上翻得越多，界面越卡。
- 详细问题：ChatPage 消息无限累积 + 每次变化整表 spread 重建，且列表无虚拟滚动
- 专家 / section：perf / frontend
- 位置：`frontend/src/pages/ChatPage.vue:650`
- 置信度：high · kind：plan
- 证据：ChatPage.vue:650-708 messageTimeline computed 对每条消息 `{ ...msg }` 重建数组并二次遍历分组；:908-949 loadOlderMessages 每次前插 50 条且无上限；:1524-1545 syncStreamingAssistantBubbles 用 filter + spread 重建整表；:1182-1231 每 NORMAL_POLL_MS=10000（:589）拉 MESSAGE_PAGE_SIZE=50（:484）后 mergePolledMessages；模板 :73 `v-for="item in messageTimeline"` 无虚拟滚动。单次成本 O(n) 对象分配 + 全列表 patch，n = 已加载消息数（可无限增长）。
- 影响：长会话（n 上千）时任意一次轮询或流式气泡更新都触发 O(n) 分配与整表 diff，出现输入卡顿与滚动掉帧；内存随会话时长单调增长，长时间挂着的窗口会越来越吃内存。
- 修复摘要：timeline 改为按 id 增量维护、限制内存消息窗口、列表虚拟化，并在窗口隐藏/流式中跳过轮询。
- 修复文件：frontend/src/pages/ChatPage.vue
- 建议测试：Performance 录制 1000 条消息下轮询帧耗时（目标单次 < 16ms）；npm --prefix frontend test -- --run

### PRF-81F87 · [低] [u09] — 桌宠和快捷聊天这两个常驻小窗口，自身代码只有一两千字节，却把整个应用连同 UI 组件库都加载进内存，三个窗口各存一份。
- 简述：桌宠和快捷聊天这两个常驻小窗口，自身代码只有一两千字节，却把整个应用连同 UI 组件库都加载进内存，三个窗口各存一份。
- 详细问题：桌宠 / 快捷聊窗口入口仅 706B / 1.2KB，却各自加载 1.14MB 公共应用壳
- 专家 / section：perf / memory-startup
- 位置：`frontend/electron/main.js:618`
- 置信度：high · kind：plan
- 证据：electron/main.js:610-637 loadRoute 对 /launcher 载 launcher.html、/quick/* 载 quick.html；实测 dist/launcher.html 的入口 launcher-BE_O1lcz.js 仅 706 B、quick.html 的 quick-DUTbY0TJ.js 仅 1,224 B，但两者都 import 同一个 launcherBootstrap-CEXfa8Qx.js（1,144,809 B）与 vue-vendor-Ba5glg1p.js（194,735 B）；该公共块内含 element-plus / ElMessage 等符号，说明小窗也为整库付了加载成本。
- 影响：常驻的桌宠窗口与快捷聊窗口各持有一份完整应用壳（≈1.3MB 压缩源码，解析后堆占用更高），启动每个小窗都要走一遍 V8 解析/求值；桌宠长期开着，这部分内存常驻不释放，低配机更明显。
- 修复摘要：让 launcher / quick 入口不依赖主应用壳：只共享最小 runtime，把 Element Plus 从公共 chunk 中移出。
- 修复文件：frontend/electron/main.js、frontend/src/main.js、frontend/launcher.html、frontend/quick.html、frontend/vite.config.js
- 建议测试：node frontend/scripts/smoke-launcher.mjs 通过；app.getAppMetrics() 记录桌宠窗口私有内存与首帧时间（改造前 vs 改造后）

## 转交 deferred
- correctness → 未指定 · backend-agent 运行时行为未能实跑：pytest 因缺 respx 无法收集用例，本轮对 Python 侧的结论全部来自代码审读与调用链追踪，未做运行时复现。
- correctness → 未指定 · 关系里程碑与 V57 的数据修复是否一致：V57 只回写 trust_score，未回写 phase 与 relationship_milestone；线上是否存在「高分里程碑 + 30 分信任」的存量行需要查生产库，本轮只有代码证据。
- correctness → 未指定 · 管理台首页展示的是硬编码数据：admin/src/App.vue 内联了「API 在线 / 运行正常 / 最新客户端 v0.2.363 / 待处理 12」等固定文案，未接 /api/admin/v1/overview；是否属产品已知占位待确认，故未列入正式发现。
- security → reliability · backend-agent 的 pytest 在仓库根无法收集（ModuleNotFoundError: app / aiomysql），CI 门禁依赖未落地，建议由可靠性专家确认测试环境链路。
- security → reliability · admin 前端未安装依赖导致 `npm --prefix admin test` 无法执行，建议纳入 CI 安装步骤。
- security → correctness · /ws/voice 首帧认证与 VoiceDuplexHandler 的状态机在断线重连、超时关闭上的边界行为未做黑盒验证。
- security → maintainability · deploy/api-gateway/nginx.conf 中 X-Forwarded-For 写法在 15 处重复，改口径时容易漏改。
- reliability → 未指定 · scripts/_check_yml.py 等运维脚本硬编码生产 IP 与 root 账号，建议交给安全专家评估。
- reliability → 未指定 · SsrfPinningClientFactory 的 IP 固定与 DNS 重绑定防护属安全域，本次只报超时缺失。
- reliability → 未指定 · backend-agent 的 prompt 文案与工具预算属正确性/可维护性域，未纳入本次结论。
- maintainability → correctness · backend/lianyu-web/src/test/java/com/lianyu/web/agent/JavaAiLogicRatchetTest.java:211 · Java 侧角色广场 2429 行人设 prompt 文本是否违反「Java 零 AI 逻辑」：JavaAiLogicRatchetTest.java:211-212 显式把 CharacterSquareCatalog 列入 BARE_PROMPT_SKIP_FILES（「数据搬家不在本棘轮范围」），属已登记的架构豁免；是否搬去 Python 是架构决策，不在可维护性判定范围。
- maintainability → 未指定 · backend-agent/pyproject.toml · pytest -q backend-agent 在仓库根收集失败、缺 aiomysql/respx：属测试基建（无 conftest.py 注入 sys.path、依赖未装），不是重复/死代码口径内的问题。
- maintainability → reliability · admin/electron/main.js · admin/electron/main.js 用 process.env.VITE_DEV_SERVER_URL 判 dev，electron:dev 未注入该变量：仅静态观察，未实际运行 admin 的 dev 模式，证据不足以单列 finding。
- perf → 未指定 · SquareLikeService 每 30s 在 synchronized 内全量拉 Redis ZSET 重算点赞数：单次数据量小（模板数级），影响被锁粒度限制；未测量到可复现的排队。
- perf → 未指定 · WebPushService 每次推送 new CloseableHttpClient：推送频率低且为一次性对象，属冷路径抖动，未达 low 门槛。
- perf → 未指定 · desktopObserver 每 30s 截图后去重（先截图再判重）：默认关闭/可配置，且有空闲暂停；缺少真实开启时的 CPU 占用数据，留待 reliability/perf 后续测量。
- perf → 未指定 · VoiceCallDuplexSession 每个 SSE delta 做 llmEmitBuf.toString() 拼接：常量小、单次通话上限有限，未达到 low。
- perf → 未指定 · 服务端 resources 内 32MB pet-voice-samples 音频随 jar 打包：影响镜像体积与部署时长，非运行时内存；属构建/部署优化，转交 maintainability。

> 这些不是当前专家的 finding，但必须写入 verdict.deferred，不能静默丢弃。

## 机器提示（仅供参考）
- 没有检测到明显的跨专家重复候选。

## 交给主编
1. 读本文件与原始 handoff；不要只依赖机器提示。
2. 合并重复项，保留最可信的证据；被合并的 ID 记入 verdict.dropped。
3. 写 `verdict.json`（schemaVersion 2、findings、coverage、dropped），再写 `report.md`。
4. `latest` 由 `verdict.mjs` 在默认五专家完整且 contract 通过后自动发布；不要手动复制。
5. 工作区根目录：`C:\Users\hp\Desktop\LianYu-PC`；运行目录：`C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261106-131312-c818fa-master`。
