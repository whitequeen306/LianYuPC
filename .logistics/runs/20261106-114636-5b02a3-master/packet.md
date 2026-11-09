# 后勤主编材料 · 20261106-114636-5b02a3-master

> 这是给主会话模型（主编）的候选材料，不是最终报告。
> 去重、定级、取舍、模块组织由主编决定；机器只负责收集与校验。

## 运行
- 根目录：`C:/Users/hp/Desktop/LianYu-PC`
- 范围：1108 个文件 / 12 个审查单元 / 139994 行
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
| u01 | backend/lianyu-service (1/3) | reviewed(36) | sampled(32) | reviewed(41) | sampled(30) | reviewed(32) |
| u02 | backend/lianyu-service (2/3) | reviewed(36) | sampled(32) | sampled(38) | sampled(30) | sampled(32) |
| u03 | frontend/src (1/2) | sampled(36) | sampled(32) | reviewed(34) | sampled(30) | sampled(32) |
| u04 | scripts (1/2) | sampled(36) | sampled(32) | sampled(31) | sampled(30) | sampled(32) |
| u05 | backend/lianyu-dao | reviewed(36) | sampled(32) | sampled(30) | sampled(30) | reviewed(32) |
| u06 | frontend/src (2/2) | sampled(36) | sampled(32) | sampled(35) | sampled(30) | reviewed(32) |
| u07 | backend-agent | reviewed(36) | sampled(32) | reviewed(36) | sampled(30) | reviewed(32) |
| u08 | backend/lianyu-web | sampled(36) | sampled(32) | reviewed(36) | sampled(30) | sampled(32) |
| u09 | frontend/electron | sampled(36) | sampled(32) | sampled(31) | sampled(30) | sampled(32) |
| u10 | 其他模块（(root)、.github、admin 等） | sampled(36) | sampled(32) | sampled(30) | sampled(30) | sampled(32) |
| u11 | backend（小模块合集 59 文件） | sampled(36) | sampled(32) | sampled(30) | reviewed(30) | sampled(32) |
| u12 | 其他模块（backend/lianyu-app、backend/lianyu-common、backend 等） | sampled(36) | sampled(32) | sampled(31) | sampled(30) | sampled(32) |

## 专家摘要
### correctness · ok
- 摘要：全项目 12 个单元按核心文件精读 + 跨文件模式扫描完成正确性审查，7 条发现：3 条 high（流式工具调用被 [DONE] 提前 return 丢弃、前端网络重试导致同一回合重复落库、同一条消息的多条记忆互相覆盖）、3 条 medium（关系状态无版本控制丢失互动增量、V34 迁移遗留孤儿消息、memory_outbox 卡 RUNNING 无回收）、1 条 low（新建记忆重复入队）。4 条门禁测试全部通过。
- 专家报告：`C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261106-114636-5b02a3-master/handoff/correctness/correctness.md`（8465 字符）
- sections：logic=2 · state=1 · concurrency=1 · data-integrity=3 · contracts=0 · architecture=0 · edge-cases=0
- 测试证据：
  - `npm --prefix admin test`：passed（vitest run 退出码 0，菜单与接口用例全绿。）
  - `mvn -q -f backend/pom.xml test`：passed（后端各模块编译并跑完单测，BUILD SUCCESS；输出中的 WARN/ERROR 均来自负路径断言用例（如鉴权失败、网关异常），非构建失败。）
  - `pytest -q backend-agent`：passed（退出码 0，全绿；注意现有用例未覆盖 gateway 的真实 [DONE] 分支（见 C-001）。）
  - `npm --prefix frontend test`：passed（vitest run 退出码 0，全绿。）
- notes：审查方式：先按 scope.json 的 12 个单元取全量文件清单，核心文件逐个精读（含全文），其余用方法/注解索引与 rg 模式扫描；对 C-001 用 httpx.MockTransport 写了复现脚本验证 [DONE] 分支下 tool_sink 为空。未纳入报告的观察：V10-V12 迁移含中文字面量（违反 AGENTS.md ASCII-only，但 JDBC 连接 useUnicode=true&characterEncoding=UTF-8，运行时风险低）；relay 的 RUNNING 回收问题已作为 C-006 报出。
- 发现数：7

### security · ok
- 摘要：覆盖全部 12 个单元、1108 个文件（139994 行）的安全审查：Spring 鉴权链、Python Agent 运行时、Electron 客户端、部署网关与迁移脚本逐项核对并做了跨文件模式扫描。共 10 条发现：1 条 critical（Agent 接口缺少强鉴权，任意 userId 可跨用户读取记忆）、3 条 high（出站 SSRF、X-Forwarded-For 伪造绕过限流、Jasypt 缺主密钥时明文入库）、4 条 medium、2 条 low；注入、数据暴露两类已查未发现问题。
- 专家报告：`C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261106-114636-5b02a3-master/handoff/security/security.md`（12384 字符）
- sections：auth=2 · authz=1 · injection=0 · browser=1 · uploads=1 · secrets=[REDACTED:secret] · crypto=1 · exposure=0 · business-logic=1 · supply-chain=2
- 测试证据：
  - `npm --prefix admin test`：not-run（admin/node_modules 未安装，执行时报 'vitest' 不是内部或外部命令；管理台前端用例本次未运行，不影响后端与 Agent 侧的安全结论。）
  - `mvn -q -f backend/pom.xml test`：passed（在 backend/ 下执行 mvn -q test，退出码 0，surefire 123 份报告无 failure/error。）
  - `pytest -q backend-agent`：passed（仓库根直接执行会因缺 app 包路径与 aiomysql/respx 依赖产生 48 个 collection error；按仓库约定 cd backend-agent 后用 .venv/Scripts/python.exe -m pytest -q 运行，1157 passed。）
  - `npm --prefix frontend test`：passed（75 个测试文件、459 个用例全部通过。）
- notes：每个单元实际精读 32 个文件（含该单元全部 core 路径），并对其余文件按密钥、SQL 拼接、出站请求、v-html/innerHTML、上传路径、限流与鉴权注解等模式做了全量扫描；因此 units 状态统一标为 sampled，不声称逐行通读。testCommands 中 admin 因依赖缺失未运行。临时脚本与中间产物已删除，handoff 目录仅保留 security.json 与 security.md。
- 发现数：10
- 转交其他专家：2 条
  - → reliability · Agent 侧 /agent/* 无速率限制（observe_limiter 只是并发信号量），额度耗尽与雪崩风险需可靠性视角复核。
  - → correctness · X-Forwarded-For 取首段与网关 $proxy_add_x_forwarded_for 追加语义不一致，属限流口径错误，建议与部署/网关一起修正。

### reliability · ok
- 摘要：审查 12 个单元 1108 个文件（每单元实读 30-41 个文件，核心文件全覆盖）。健壮性层面最要紧的两处：Python 网关把客户端中断记成上游失败，5 次用户关窗就能打开熔断把该 provider 的所有用户打成“模型繁忙”（已用脚本复现）；Java 的 SSE 收尾只接 IOException，收尾阶段任何其它异常都会让流最长挂 30 分钟、且这一轮回复不落库。另有后台任务重试预算短于熔断窗口导致整批进死信、推送队列没有死信配置、主动消息失败只记 DEBUG、流式兜底日志没有 traceback，以及两条测试门禁失效（pytest 缺 pythonpath、admin 依赖未装且 --passWithNoTests）。共 11 条发现：high 2 / medium 4 / low 5。
- 专家报告：`C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261106-114636-5b02a3-master/handoff/reliability/reliability.md`（11756 字符）
- sections：error-handling=2 · resilience=3 · observability=3 · tests=3
- 测试证据：
  - `npm --prefix admin test`：failed（`'vitest' 不是内部或外部命令`：admin 未安装依赖（无 node_modules），命令直接失败；源码里只有 admin/src/__tests__/menu.test.js 一个 8 行用例）
  - `mvn -q -f backend/pom.xml test`：passed（exit 0 / BUILD SUCCESS；覆盖 AiBackgroundConsumer 重试与 DLQ、ConversationService 落库、AgentRuntimeClient 5xx/连接失败、AgentStreamBridge 帧中继）
  - `pytest -q backend-agent`：failed（48 个 collection error，全部 `ModuleNotFoundError: No module named 'app'`（仓库根与 backend-agent 目录下都一样）；改用 `backend-agent/.venv/Scripts/python.exe -m pytest -q` 后 1157 passed —— 说明失败原因是导入路径/解释器，不是用例本身）
  - `npm --prefix frontend test`：passed（75 个测试文件 / 459 个用例全绿（7.5s）；含 updater、qqBridge、napcat、sseParser、httpCore 等用例）
- notes：跨专家转交：1) maintainability——AiResilience/UpstreamCircuitBreakerFactory 与 resilience4j yml 全为死配置，建议删除或真正接线；2) security——/agent/memory/reconcile 与 /agent/memory/relay/requeue 两个运维接口只做内部签名校验，建议确认暴露面与鉴权；3) correctness——gateway.py 在 [DONE] 处 return 会丢弃 tool_calls（已由 correctness 报告），修 REL-01 时不要顺手改动该分支语义。未覆盖：未做压测/故障注入，并发相关结论均来自代码路径与已执行的熔断复现脚本。
- 发现数：11
- 转交其他专家：4 条
  - → correctness · backend-agent/app/memory/relay.py · memory_outbox 认领后卡在 RUNNING、requeue_stuck 无定时调用。本轮已作为 C-006 报告故不重复计数；其“失败不可见”的一面已在 REL-03/REL-06 的监控建议中覆盖。
  - → maintainability · backend/lianyu-service/src/main/java/com/lianyu/service/ai/AiResilience.java · AiResilience / UpstreamCircuitBreakerFactory 与 application.yml 的 resilience4j 配置没有任何注入点，属死代码 + 失效配置；Python 侧已有等价熔断，不构成本轮可证的可靠性损失，建议交 maintainability 处理。
  - → maintainability · frontend/src/composables/useVoiceRecorder.js · onChunk 失败被 `.catch(() => {})` 吞掉；全仓只有它自己的单测引用该 composable，生产代码未使用，证据不足以证明可达。
  - → reliability · frontend/electron · updater/qqBridge/napcat 空 catch 扫描命中 31 处，逐处核对均为单条音频/代理探测/状态同步的可降级分支，上层有状态机与日志兜底，不构成失败不可见。

### maintainability · ok
- 摘要：全项目 12 个单元、1108 个文件逐单元检查（每单元实读 30 个文件并含全部 core，另做跨文件 rg 与自建 import 图扫描）。可维护性整体尚可：Maven 模块无环、生产代码 14 行窗口重复为 0 组、backend-agent 无不可达模块、frontend 运行时依赖全部在用。主要问题集中在四类：①同一事实多处手写（健康探测 5 份、语音白名单 Java/JS 两份、消息时间 3 份）；②两处循环依赖（service.ai ↔ service.character 包互指、前端 notifications store ↔ useNotificationNavigation）；③死代码与过期实现（zh-TW 语言包 617 行未注册、4 个前端模块 791 行无引用、Electron 仍在解密已废弃的 localStorage token）；④仓库卫生（scripts/ 根目录 94 条目且违反自身 README、backend/scripts 残留硬编码个人路径的一次性迁移脚本、admin 4 个未用依赖）。共 12 条发现：simplify 3、coupling 3、cleanup 6，均为 medium 及以下（可维护性封顶 medium）。测试：Maven 与 frontend 通过；admin 与 backend-agent 因环境缺依赖未通过，详见 tests。
- 专家报告：`C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261106-114636-5b02a3-master/handoff/maintainability/maintainability.md`（11351 字符）
- sections：simplify=3 · coupling=3 · cleanup=6
- 测试证据：
  - `npm --prefix admin test`：failed（报 `'vitest' 不是内部或外部命令`：admin 未安装 node_modules（无 vitest 可执行文件），不是断言失败；命令经管道后被掩盖为 exit 0。）
  - `mvn -q -f backend/pom.xml test`：passed（重跑 `cd backend && mvn -q test` 得 exit 0，surefire 报告 123 份，覆盖 service/web/ai/admin。）
  - `pytest -q backend-agent`：failed（收集阶段失败：`ModuleNotFoundError: No module named 'respx'`，26 个用例收集错误，属环境缺 dev 依赖，未执行到断言。）
  - `npm --prefix frontend test`：passed（vitest 75 个测试文件 / 459 个用例全绿。）
- notes：证据方法：python 脚本按 scope.json 每单元选 core + 最小文件至 30 个并逐文件读首 14-30 行；另用 rg 做跨文件模式扫描（重复代码 8/14 行窗口、env var 读取点、依赖引用数），用自建脚本对 frontend/src（219 模块）与 backend-agent（app+tests）构建 import 图，并对 service 包做包级引用矩阵。未做运行时验证（未启动服务、未连数据库）；性能相关判断未纳入本轮。仓库根 ship-*.log 等未跟踪垃圾不在 scope，未计入。
- 发现数：12
- 转交其他专家：5 条
  - → perf · backend-agent/app/main.py · 单文件 1278 行、25 个路由未拆 router；已作为 M03 上报，若拆分排期靠后可作为独立重构任务跟踪。
  - → correctness · frontend/electron/main.js · 孤儿 IPC handler desktop:notify-window-changed：68 个 ipcMain.handle 与 66 个 preload invoke 全部匹配，仅此通道无暴露/无调用；删除属机械改动，价值低于已上报的 12 条。
  - → correctness · AGENTS.md · 文档与配置漂移：AGENTS.md:139 仍写“当前最新 V55”（实际 V57）；docker-compose.yml:110,157 传的 LIANYU_AGENT_SHADOW_MODE 与 :59 的 LIANYU_MEMORY_OUTBOX_ENABLED 在 backend 无任何读取。
  - → correctness · admin/src/pages/ManagementPage.vue · configs.users.columns 先定义后被整段覆盖，前一处为死赋值；admin 后台为壳子状态，等该页真正开发时一并整理。
  - → perf · frontend/electron/startupProfiler.js · electron/startupProfiler.js 11 行与 src/utils/startupProfiler.js 18 行同签名重复（时钟基准不同）；合并需统一时钟语义，暂列观察。

### perf · ok
- 摘要：覆盖 12 个审查单元共 1108 个文件（139994 行）。最严重的是群聊线程池自饥饿：编排任务与内层生成共用 8 线程池，8 个并发群聊回合即永久挂死；其次是前端 element-plus 全量注册把 1118KB（gzip 361KB）的 chunk 拖进桌宠/快捷聊窗口；另有 4 条数据库侧问题（每 30 秒相关子查询、情绪衰减全表逐行回写、message 缺 created_at 索引、会话列表取 MEDIUMTEXT 全文）与 3 条前端轮询/渲染/内存问题。共 10 条发现，均给出 plan 级修复步骤与验证方法。
- 专家报告：`C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261106-114636-5b02a3-master/handoff/perf/perf.md`（11065 字符）
- sections：hot-path=1 · data-access=5 · runtime=1 · frontend=2 · memory-startup=1
- 测试证据：
  - `npm --prefix admin test`：failed（报错 'vitest' 不是内部或外部命令，admin/node_modules 不存在，用例未真正执行（环境未安装依赖））
  - `mvn -q -f backend/pom.xml test`：passed（exit 0，输出仅预期 WARN，末尾 AgentWiringHealthCheck 显示各端口已 wired）
  - `pytest -q backend-agent`：failed（仓库根执行 48 个 collection error（ModuleNotFoundError: No module named 'app'）；切到 backend-agent 目录仍 26 error，首个根因 backend-agent/app/db.py:20 import aiomysql 缺失，属环境缺依赖）
  - `npm --prefix frontend test`：passed（Test Files 76 passed (76)、Tests 460 passed (460)，vitest 5.05s）
- notes：只读源码，未修改任何项目文件。前端包体数据来自 frontend/dist 实际产物（mtime 2026-11-06 12:06，与源码同批构建）。Java/Python 侧结论来自直读源码与全量模式扫描；u02/u03/u04/u08/u09/u10/u11/u12 为抽样审查，未逐文件全文阅读。P02/P03/P04 的数据库成本为基于索引现状与 SQL 形态的静态推导，未连生产库压测。
- 发现数：10

## 候选 findings
### COR-559B1 · [高] [u07] — 模型在流式回复里要求调用工具（查时间、天气、记忆等）时，这条请求会被丢掉，用户只看到角色「说要做」却没有动作。
- 简述：模型在流式回复里要求调用工具（查时间、天气、记忆等）时，这条请求会被丢掉，用户只看到角色「说要做」却没有动作。
- 详细问题：流式回合收到 [DONE] 时提前 return，聚合好的 tool_calls 被丢弃，工具永不执行
- 专家 / section：correctness / logic
- 位置：`backend-agent/app/gateway.py:313`
- 置信度：high · kind：direct
- 证据：`_stream_once` 在 `data == "[DONE]"` 时直接 `return`（gateway.py:312-313），而把聚合好的 `pending` 写进 `tool_sink` 的收尾代码在 `async with` 之后（gateway.py:348-352），return 会整体跳过。用 httpx.MockTransport 复现：上游流以 `data: [DONE]` 结尾时 `tool_sink == []`；同一个流去掉 `[DONE]` 后 `tool_sink == [{"id":"call_1","type":"function","function":{"name":"get_time","arguments":"{}"}}]`。`stream_chat.py:109` 因此拿到空列表、不执行工具；若该轮模型同时吐了文本，`stream_chat.py:145` 的兜底（content 为空才补）也不会触发，工具调用被彻底吞掉。现有 `backend-agent/tests/test_stream_tool_fallback.py:187-197` 是直接往 tool_sink 里塞假数据，覆盖不到真实 [DONE] 路径。
- 影响：所有走流式通道的回合，只要模型选择调用工具就不会执行：查时间/天气/记忆检索等工具形同失效，回复退化成「我这就帮你查」之类的空话，而且没有任何错误提示与日志（pending 非空但从未上报）。
- 修复摘要：把收尾的 return 改成 break，让循环退出后仍然执行 tool_sink 落盘。
- 修复文件：backend-agent/app/gateway.py、backend-agent/tests/test_stream_tool_fallback.py
- 建议测试：pytest -q backend-agent；pytest -q backend-agent/tests/test_stream_tool_fallback.py

### COR-CA186 · [高] [u02] — 用户一句话里说出的多件事（例如「以后叫我小林，我今天很崩溃」），只有最后一件会被记住，前面几件被同一条记录悄悄覆盖。
- 简述：用户一句话里说出的多件事（例如「以后叫我小林，我今天很崩溃」），只有最后一件会被记住，前面几件被同一条记录悄悄覆盖。
- 详细问题：同一条用户消息命中的多条记忆共用 sourceHash，后写的覆盖先写的
- 专家 / section：correctness / data-integrity
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/memory/MemoryWriter.java:199`
- 置信度：high · kind：plan
- 证据：`resolveSourceHash`（MemoryWriter.java:199）→ `computeSourceIdsHash`（:266-281）只用排序后的 source_msg_ids 求 sha256，而 `processSummary` 每次传 `List.of(memory.sourceMsgId())`（:105-108）。另一侧 `MemoryExtractionHeuristics.extractAll`（:20-30）会把 profile/relationship/keyword 三组结果拼在一起，`extractRelationshipMemories` 对同一条消息可同时产出「专属称呼锚点」(:101)、「脆弱情绪」(:105)、「仪式感」(:108)、「昵称」(:111) 多条，`extractProfileFacts`(:57-77) 也按 msg id 归档 → 这些记忆的 sourceHash 完全相同。第二条进入 `upsertTypedMemory` 时 `findExistingMemory`(:200) 命中第一条，summary 不同 → 走 UPDATED 分支（:238-252）覆盖 summary/type、`existing.setMilvusVecId(null)` 并 `enqueueDelete`+`enqueueUpsert`。`memory_meta` 上的 `UNIQUE KEY uk_source_hash`（V1__init.sql:67）还会让并发插入的一方进入 SKIPPED。
- 影响：记忆能力静默丢数据：一句话里的多个事实只留下最后一个，用户会反复发现「我刚说过它又忘了」；每次覆盖还清空 vec_id 并触发一次向量删除+重建，检索存在短暂空洞，嵌入成本翻倍。
- 修复摘要：让哈希标识「一条记忆」而不是「一条消息」：把记忆类型与归一化摘要一起纳入 sourceHash，并为存量行做一次回填。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/memory/MemoryWriter.java、backend/lianyu-service/src/main/java/com/lianyu/service/memory/MemoryExtractionHeuristics.java、backend/lianyu-dao/src/main/resources/db/migration
- 建议测试：mvn -q -f backend/pom.xml test；pytest -q backend-agent

### COR-05886 · [中] [u02] — 后台每半小时的关系衰减和用户聊天会同时改同一份关系数据，用户的信任/亲密度增长经常被旧快照覆盖，表现为「聊很久关系也不涨」。
- 简述：后台每半小时的关系衰减和用户聊天会同时改同一份关系数据，用户的信任/亲密度增长经常被旧快照覆盖，表现为「聊很久关系也不涨」。
- 详细问题：关系状态整行读改写且无版本控制，衰减任务会覆盖互动增量
- 专家 / section：correctness / concurrency
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/relationship/RelationshipStateService.java:132`
- 置信度：high · kind：plan
- 证据：`recordUserTurn`（RelationshipStateService.java:116-133）流程是 `loadOrCreateState` → 内存里累加事件/在场分 → `relationshipStateMapper.updateById(state)` 写回整行；`recordAssistantTurn`(:163-165) 与 `decayAll`(:183-187) 同一模式。`RelationshipState`（backend/lianyu-dao/.../entity/RelationshipState.java）是普通 `@TableName("relationship_state")`，没有 `@Version`，表结构也没有版本列（V1/V56 均无）。`RelationshipDecayScheduler`(:25-37) 每 30 分钟触发一次 `decayAll()`，与 Tomcat 请求线程天然并发。
- 影响：trust/intimacy 增量被静默吞掉（用户感知是关系不推进）；`lastInteractionAt` 被写回旧值后 `isNewPresenceDay` 会重复判定「新的一天」，DAILY_PRESENCE 连续天数被重复累加，里程碑判定随之偏移。
- 修复摘要：把关系状态改成带版本的条件更新，或让衰减只更新衰减相关列，避免整行回写覆盖并发增量。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/relationship/RelationshipStateService.java、backend/lianyu-dao/src/main/java/com/lianyu/dao/entity/RelationshipState.java
- 建议测试：mvn -q -f backend/pom.xml test

### COR-F6ADD · [中] [u05] — 修「重复会话」的那次数据库迁移直接把多余会话删了，里面的聊天记录从界面上消失，却永远留在库里。
- 简述：修「重复会话」的那次数据库迁移直接把多余会话删了，里面的聊天记录从界面上消失，却永远留在库里。
- 详细问题：V34 去重删掉重复会话，但会话下的消息既不迁移也不清理
- 专家 / section：correctness / data-integrity
- 位置：`backend/lianyu-dao/src/main/resources/db/migration/V34__conversation_single_unique.sql:2`
- 置信度：high · kind：plan
- 证据：V34 开头 `DELETE c1 FROM conversation c1 INNER JOIN conversation c2 ... AND c1.id > c2.id` 只删 conversation 行（第 1-8 行），之后才建唯一键 `uk_conversation_user_character_mode`。`message` 表（V1__init.sql:45-56）只有普通列 conversation_id 和 `UNIQUE KEY uk_conv_seq (conversation_id, seq)`，没有外键约束，所以被删会话下的消息不会被级联删除，也没有被改挂到保留会话；V35–V57 没有任何迁移触碰 message（按 `FROM message` / `UPDATE message` / `DELETE FROM message` 全量 grep 无命中）。
- 影响：受影响的用户打开角色会话只剩被保留的那条，历史消息在 UI 上永久消失（数据其实还在，只是永远读不到）；同时 message 表留下永久孤儿行，按会话统计/清理/备份都会失真。
- 修复摘要：补一条数据修复迁移：先把重复会话的消息改挂到保留会话并重排 seq，无法改挂的先归档再删除，最后校验孤儿行为 0。
- 修复文件：backend/lianyu-dao/src/main/resources/db/migration/V34__conversation_single_unique.sql、backend/lianyu-dao/src/main/resources/db/migration/V1__init.sql
- 建议测试：mvn -q -f backend/pom.xml test

### COR-B3226 · [低] [u02] — 每写一条新记忆都会排队两次，第二次会把刚建好的向量删掉再插一遍，这期间这条记忆搜不到。
- 简述：每写一条新记忆都会排队两次，第二次会把刚建好的向量删掉再插一遍，这期间这条记忆搜不到。
- 详细问题：新建记忆被重复写入 outbox，每条记忆触发两次向量重建
- 专家 / section：correctness / data-integrity
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/memory/MemoryWriter.java:120`
- 置信度：high · kind：direct
- 证据：`upsertTypedMemory` 在 CREATED 分支已经 `memoryOutboxService.enqueueUpsert(meta.getId())`（MemoryWriter.java:223）；回到 `processSummary` 又对 `saved.getMilvusVecId()` 为空的行再入队一次（MemoryWriter.java:117-126），而刚创建的行 vec_id 必然为空 → 同一个 memory_id 产生两条 UPSERT。relay 的 `project_upsert`（backend-agent/app/memory/relay.py:86-110）是先 DELETE 旧向量再 insert，因此第二条会重复嵌入并把向量删除重建。
- 影响：嵌入调用与 Milvus 写入翻倍（成本与延迟），两次 UPSERT 之间存在向量缺失窗口，刚说完的话可能检索不到；第二条执行失败时 memory_meta.vec_id 已指向被删掉的向量。
- 修复摘要：去掉 processSummary 里重复的入队，只在 outbox 中确实没有该记忆的待处理行时才补一次。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/memory/MemoryWriter.java、backend/lianyu-service/src/test/java/com/lianyu/service/memory/MemoryWriterDebounceTest.java
- 建议测试：mvn -q -f backend/pom.xml test

### COR-50DF0 · [中] [u07] — 记忆向量同步任务如果做到一半进程被重启，这条任务会一直卡在「执行中」，之后再也不会重试，那条记忆就永远搜不到或永远删不掉。
- 简述：记忆向量同步任务如果做到一半进程被重启，这条任务会一直卡在「执行中」，之后再也不会重试，那条记忆就永远搜不到或永远删不掉。
- 详细问题：memory_outbox 认领后进程崩溃会永久停在 RUNNING，无人回收
- 专家 / section：correctness / state
- 位置：`backend-agent/app/memory/relay.py:41`
- 置信度：medium · kind：plan
- 证据：relay 用 `UPDATE memory_outbox SET status = 'RUNNING' WHERE id = %s AND status = 'PENDING'` 认领（relay.py:41-48），失败走 `_retry_or_dead` 指数退避（:161-176），`drain_once` 只捞 `status='PENDING' AND next_attempt_at <= NOW()`（:111-119）。`requeue_stuck()`（:207-225）的 docstring 写明「运维用」，唯一调用点是 `backend-agent/app/main.py:966-972` 的手工接口 `/agent/memory/relay/requeue`；常驻循环 `_relay_loop`（main.py:52-65）只调 `drain_once`，`reconcile`（relay.py:178-205）也只由运维接口触发。
- 影响：进程在认领后被杀（部署滚动重启、OOM、容器驱逐）时该行永久 RUNNING：UPSERT 场景记忆没有向量、检索不到；DELETE 场景向量残留在 Milvus，用户删掉的记忆仍会被角色说出来。没有任何告警，只能靠人工调运维接口。
- 修复摘要：给认领加租约并在常驻循环里定期回收超时的 RUNNING 行，而不是依赖人工调用运维接口。
- 修复文件：backend-agent/app/memory/relay.py、backend-agent/app/main.py
- 建议测试：pytest -q backend-agent

### COR-54E29 · [高] [u06] — 发送消息遇到网络抖动时客户端会自动重发，但服务端每次都当成新消息，于是同一句话被记两遍、角色也回两遍。
- 简述：发送消息遇到网络抖动时客户端会自动重发，但服务端每次都当成新消息，于是同一句话被记两遍、角色也回两遍。
- 详细问题：网络失败后原样重发同一回合，用户消息与角色回复各落两份
- 专家 / section：correctness / logic
- 位置：`frontend/src/pages/ChatPage.vue:1439`
- 置信度：medium · kind：plan
- 证据：ChatPage.vue:1433-1441：`catch (err) { if (isNetworkError(err)) { await sleep(800); if (signal.aborted) return; streamResult = await attemptStream() } }`，而 `attemptStream` 就是再 POST 一次 `/conversation/{id}/messages/stream`（:1420-1421）。`isNetworkError`（frontend/src/composables/useStreamAbort.js:8-15）把任何 `TypeError`、`failed to fetch`、`load failed` 都算网络错误——包含请求已到达服务端、响应阶段连接被重置的情况。请求体 `streamPayload`（ChatPage.vue:1405-1410）只有 provider/model/content/imageUrl，没有客户端消息 ID；服务端 `ConversationService.sendMessageStream` 每次调用都会新插一条用户消息并重新生成回复，无幂等键。QuickChatPage.vue:304 复用同一发送/重试模式。
- 影响：一次网络抖动就会让同一条消息在历史里出现两次、角色回复两遍，模型调用与 token 双倍计费；群聊/连续会话的 seq 与气泡顺序也会错乱，用户看到「自己说了两遍」。
- 修复摘要：给发送加客户端幂等键并在服务端去重，或把自动重试限制在「请求确定未送达」的场景。
- 修复文件：frontend/src/pages/ChatPage.vue、frontend/src/api/conversation.js、backend/lianyu-web/src/main/java/com/lianyu/web/controller/ConversationController.java、backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java
- 建议测试：npm --prefix frontend test；mvn -q -f backend/pom.xml test

### SEC-EA2CB · [严重] [u07] — 任何人只要把请求里的用户编号换成别人，就能读到对方的聊天记忆和日记；这些内部接口目前没有登录校验。
- 简述：任何人只要把请求里的用户编号换成别人，就能读到对方的聊天记忆和日记；这些内部接口目前没有登录校验。
- 详细问题：Agent 运行时缺少 X-User-Id 时直接放行，body.userId 可任意指定导致跨用户读取记忆
- 专家 / section：security / authz
- 位置：`backend-agent/app/main.py:150`
- 置信度：high · kind：plan
- 证据：backend-agent/app/main.py:146-151 只做“有头才比对”：user_id_header: str | None = Header(default=None, alias="X-User-Id") → if user_id_header and str(body.user_id) != user_id_header: raise HTTPException(403)。同一写法出现在 :206(chat-stream)、:313(session-summary)、:365(text/chat)、:476、:504，缺头即跳过全部校验。deploy/api-gateway/nginx.conf:252-274 的 location /api/agent/ 没有 auth_request 也没有 internal：set $agent_upstream http://agent:8001; rewrite ^/api(/agent/.*)$ $1 break; proxy_pass $agent_upstream; ⇒ 公网可直达 8001。body.userId 会一路进入工具上下文：app/main.py:228-240 tool_context={"_user_id": body.user_id, …}；app/tool_loop.py:98-100 merged.update(tool_context) 覆盖模型参数后 spec.handler(merged)；app/tools/memory_search_tool.py:158 user_id = (arguments or {}).get("_user_id") 再执行 :52/:62 的 WHERE character_id = %s AND user_id = %s。
- 影响：攻击者无需账号即可枚举 userId 读取他人记忆摘要、日记、动态与评论，内容以模型可读的明文形式返回；还能借平台额度生成任意回复。Java 侧 Sa-Token 鉴权被整条绕过，等于把跨租户读取能力挂在公网网关路径上。
- 修复摘要：在 Agent 侧引入真正的服务间鉴权（共享密钥签名 + 时间窗 + 重放防护），并由网关默认拒绝外部直连 /api/agent/。
- 修复文件：backend-agent/app/main.py、backend-agent/app/config.py、deploy/api-gateway/nginx.conf、backend-agent/tests/test_api_contract.py、backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentRuntimeClient.java
- 建议测试：cd backend-agent && .venv/Scripts/python.exe -m pytest -q；cd backend && mvn -q test

### SEC-E06CB · [高] [u07] — 外部调用者可以指定“模型地址”，服务就会照这个地址发请求，内网服务因此可能被探测，返回内容还会被带出来。
- 简述：外部调用者可以指定“模型地址”，服务就会照这个地址发请求，内网服务因此可能被探测，返回内容还会被带出来。
- 详细问题：Agent 接受调用方自填 baseUrl/apiKey，出站请求可打到任意主机并回显响应
- 专家 / section：security / uploads
- 位置：`backend-agent/app/gateway.py:92`
- 置信度：high · kind：plan
- 证据：backend-agent/app/main.py:284-285 TextChatBody.base_url/api_key 来自请求体（session-summary :347-348、chat-stream :213 的 text_base_url/text_api_key 同理）；backend-agent/app/gateway.py:92 normalize_openai_base_url 仅做字符串归一（去尾斜杠、去 /v1、固定追加 /v1/chat/completions），随后经 app/http_client.py new_client() 用 httpx 发出请求；main.py:369 附近把模型返回的 content 直接回给调用方。AgentRuntimeClient.java 注释称“仅访问固定地址 agent:8001、无 SSRF 面”，SSRF 面其实在 Python 的这一段。
- 影响：结合 SEC-01 无需登录即可让服务向内网任意 HTTP 服务发起 POST（backend:8080、minio:9000、milvus、运维面板等），把 2xx 响应体带回；也可作为数据外带通道，把请求体里的内容送到攻击者服务器。
- 修复摘要：Python 出站对 base_url 做与 Java OutboundUrlValidator 等价的白名单校验（禁私网/回环/元数据地址，解析结果钉 IP），并只允许 vault 中登记的域名。
- 修复文件：backend-agent/app/gateway.py、backend-agent/app/http_client.py、backend-agent/app/main.py、backend/lianyu-common/src/main/java/com/lianyu/common/util/OutboundUrlValidator.java
- 建议测试：cd backend-agent && .venv/Scripts/python.exe -m pytest -q

### SEC-A3F36 · [高] [u08] — 客户端的限流按“来源 IP”算，而这个 IP 可以从请求头里随便写，攻击者每次换一个就能绕过注册和上传的次数限制。
- 简述：客户端的限流按“来源 IP”算，而这个 IP 可以从请求头里随便写，攻击者每次换一个就能绕过注册和上传的次数限制。
- 详细问题：默认信任 X-Forwarded-For 首个值，公网可伪造来源 IP 绕过按 IP 的限流
- 专家 / section：security / auth
- 位置：`backend/lianyu-web/src/main/java/com/lianyu/web/util/ClientIpResolver.java:28`
- 置信度：high · kind：plan
- 证据：ClientIpResolver.java:16 @Value("${lianyu.security.trust-forwarded-for:true}") 默认为 true；:24-28 if (trustForwardedFor) { String forwarded = request.getHeader("X-Forwarded-For"); … int comma = forwarded.indexOf(','); return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim(); } —— 取的是客户端可写的第一段。backend/lianyu-app/src/main/resources/application.yml:172 trust-forwarded-for: ${LIANYU_TRUST_FORWARDED_FOR:true}，.env.example 与 docker-compose.yml 均未设置该变量。deploy/api-gateway/nginx.conf:306 `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;` 把真实 IP 追加在客户端值之后，因此第一段永远是攻击者可控内容。使用方：AuthController.java:65/:78（登录/注册/验证码，30 次/IP/分钟、3 次/日/IP 注册额度）、ObserveController.java:57（30 次/小时/IP）、头像上传 20 次/日/IP。
- 影响：所有按 IP 的额度（注册 3 次/日、头像 20 次/日、桌面感知 30 次/小时、认证 30 次/分钟）在公网均可被任意绕过，可批量注册、刷模型额度、规避风控；按用户名维度的限额仍生效但无法挡住换号。
- 修复摘要：改为只信任网关写入的 X-Real-IP（或取 XFF 最后一段），并把 trust-forwarded-for 默认改为 false，部署环境显式打开。
- 修复文件：backend/lianyu-web/src/main/java/com/lianyu/web/util/ClientIpResolver.java、backend/lianyu-app/src/main/resources/application.yml、docker-compose.yml、.env.example
- 建议测试：cd backend && mvn -q test

### SEC-701CA · [高] [u10] — 少配一个环境变量，用户自己填的模型密钥就会以明文存进数据库，而系统只在启动日志里警告一句。
- 简述：少配一个环境变量，用户自己填的模型密钥就会以明文存进数据库，而系统只在启动日志里警告一句。
- 详细问题：未配置 LIANYU_MASTER_KEY 时 Jasypt 静默降级为明文，用户模型 API Key 直接明文入库
- 专家 / section：security / crypto
- 位置：`backend/lianyu-security/src/main/java/com/lianyu/security/util/JasyptUtil.java:63`
- 置信度：high · kind：direct
- 证据：JasyptUtil.java:23 无主密钥时 log.warn("LIANYU_MASTER_KEY not set — Jasypt encryption disabled. Set env var before production use.") 然后不建 encryptor；:61-65 public String encrypt(String plaintext) { … if (currentVersion == null || encryptors.isEmpty()) { log.warn("No Jasypt encryptor available, returning plaintext"); return plaintext; } … }（:69-70 还有第二处同样回退）；:75-78 decrypt 在 encryptors 为空时原样返回。写入方 EncryptedStringTypeHandler 与 ApiKeyVaultService 都直接调用 encrypt，因此 api_key_vault.api_key_encrypted 会存明文。
- 影响：运维漏配一个变量，全部用户自填的模型 Key 会以明文落库；数据库备份、只读账号、误导出或任意 SQL 读取都会造成 Key 泄露，而调用方看不出任何异常（只有启动时一条 warn）。
- 修复摘要：加密路径失败即关闭（fail closed）：无 encryptor 时抛异常而不是返回明文，并在启动阶段对缺失主密钥做硬校验。
- 修复文件：backend/lianyu-security/src/main/java/com/lianyu/security/util/JasyptUtil.java、backend/lianyu-service/src/main/java/com/lianyu/service/ai/ApiKeyVaultService.java、.env.example
- 建议测试：cd backend && mvn -q test

### SEC-1101A · [中] [u11] — 管理员密码一旦泄露，第二步验证码可以无限次尝试，因为只有密码输错才会计数锁定。
- 简述：管理员密码一旦泄露，第二步验证码可以无限次尝试，因为只有密码输错才会计数锁定。
- 详细问题：管理员登录 TOTP 错误不累计失败次数，6 位验证码可在线穷举
- 专家 / section：security / auth
- 位置：`backend/lianyu-admin/src/main/java/com/lianyu/admin/identity/AdminAuthService.java:43`
- 置信度：high · kind：direct
- 证据：AdminAuthService.java:37-39 仅密码不匹配分支执行 UPDATE admin_user SET failed_attempts=failed_attempts+1, locked_until=CASE WHEN failed_attempts+1>=5 THEN DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 15 MINUTE) …；:42 缺 otp 时返回 otpRequired；:43 if (otpRequired && !TotpUtil.verify(jasypt.decrypt(String.valueOf(row.get("totp_secret"))), request.otp(), System.currentTimeMillis())) throw new IllegalArgumentException("用户名或密码错误") —— 不写 failed_attempts、不锁定；:49 成功后才清零。
- 影响：攻击者拿到管理员密码（复用/撞库/钓鱼）后可对 6 位 TOTP 做在线穷举（成功概率随请求数线性上升），5 次锁定策略形同虚设；管理员后台可读用户列表、发放客服授权、发布客户端版本。
- 修复摘要：TOTP 校验失败与密码失败同等计数，达到阈值同样锁定并写审计。
- 修复文件：backend/lianyu-admin/src/main/java/com/lianyu/admin/identity/AdminAuthService.java、backend/lianyu-service/src/test/java/com/lianyu/service/RegisterRequestValidationTest.java
- 建议测试：cd backend && mvn -q test

### SEC-D6178 · [中] [u07] — 外部任何人都能反复调用识图接口，用平台自己的模型额度生成内容，花的是运营方的钱。
- 简述：外部任何人都能反复调用识图接口，用平台自己的模型额度生成内容，花的是运营方的钱。
- 详细问题：无鉴权的桌面感知接口会消耗平台自己的多模态 Key，且没有速率限制
- 专家 / section：security / business-logic
- 位置：`backend-agent/app/main.py:143`
- 置信度：high · kind：plan
- 证据：backend-agent/app/main.py:143-151 /agent/desktop-observe 无鉴权（同 SEC-01 的头比对逻辑）；app/vision.py:69-70 base_url=settings.multimodal_base_url、api_key=[REDACTED:secret] DashScope Key）；app/desktop_observe.py:86 只有调用方未带 text_base_url/text_api_key 时才回落到平台 Key；main.py:76-79 observe_limiter 是 asyncio.Semaphore（并发上限），不是速率限制。
- 影响：外部可无限次把任意图片与 windowTitle 送进平台模型，按调用量直接消耗运营方额度（成本攻击），并让平台承担内容风险；桌面截图内容也会流向模型供应商。
- 修复摘要：该端点只允许通过内部签名从 Java 转发，并加用户/IP 双维度配额与单次图片大小上限。
- 修复文件：backend-agent/app/main.py、backend-agent/app/vision.py、backend-agent/app/desktop_observe.py、backend-agent/app/config.py
- 建议测试：cd backend-agent && .venv/Scripts/python.exe -m pytest -q

### SEC-1AD35 · [中] [u05] — 十个真实的模型密钥以加密形式写进了代码库，而解密用的那把钥匙全组共用，谁拿到代码和钥匙谁就能用这些额度。
- 简述：十个真实的模型密钥以加密形式写进了代码库，而解密用的那把钥匙全组共用，谁拿到代码和钥匙谁就能用这些额度。
- 详细问题：团队默认 Key 池的十份密文随迁移入库，解密主密钥在全体开发间共享
- 专家 / section：security / secrets
- 位置：`backend/lianyu-dao/src/main/resources/db/migration/V22__team_default_vault_pool_encrypted.sql:1`
- 置信度：high · kind：plan
- 证据：V22__team_default_vault_pool_encrypted.sql:1-13 连续 10 条 UPDATE api_key_vault SET api_key_encrypted='<base64 密文>', key_version='v1', base_url='https://clove.dpdns.org', model_default='deepseek-v4-flash' WHERE id=N AND vault_scope='DEFAULT'；文件头注释写明用团队共用 LIANYU_MASTER_KEY 解密（“新同事 clone 后：填好 .env 的 LIANYU_MASTER_KEY”），本机 .env:41 即为该主密钥（.env 被 gitignore，未入库）。JasyptUtil.java:52 使用 PBEWithHMACSHA512AndAES_256 + 1000 次迭代，迭代次数偏低。
- 影响：任何同时拿到仓库与团队主密钥的人（含离职人员、泄露的 .env、CI 缓存）都能解出 10 个真实模型 Key 并直接消耗额度；主密钥一换，所有环境所有密文同时失效，只能全量重新加密与轮换。
- 修复摘要：把默认 Key 池移出仓库，改为部署时注入；密文与主密钥分离保管，并按环境使用不同主密钥。
- 修复文件：backend/lianyu-dao/src/main/resources/db/migration/V22__team_default_vault_pool_encrypted.sql、backend/lianyu-dao/src/main/resources/db/migration/V21__team_default_vault_pool.sql、backend/lianyu-security/src/main/java/com/lianyu/security/util/JasyptUtil.java、.env.example
- 建议测试：cd backend && mvn -q test

### SEC-CA474 · [中] [u09] — 客户端安装更新包前只比对一个同样来自更新服务器的校验值，等于自己给自己盖章；一旦更新服务器被控，就会静默装上任意程序。
- 简述：客户端安装更新包前只比对一个同样来自更新服务器的校验值，等于自己给自己盖章；一旦更新服务器被控，就会静默装上任意程序。
- 详细问题：自动更新只校验同源清单里的 sha512，清单签名校验默认关闭
- 专家 / section：security / supply-chain
- 位置：`frontend/electron/updater/updater.js:81`
- 置信度：high · kind：plan
- 证据：frontend/electron/updater/updater.js:81-90 async function verifyManifestOrThrow(ymlText, ymlUrl, apiOrigin) { const publicKeyBase64 = resolveManifestPublicKey(); if (!publicKeyBase64) { logger.warn('updater', 'latest.yml signature check disabled: no updateManifestPublicKey'); return } … } ⇒ 无公钥时直接跳过验签；frontend/.env.production.cloud 中 VITE_LIANYU_UPDATE_PUBKEY 被注释掉（注释：留空 = 客户端暂不强制验签）；:57 的 sha512 取自同一份 latest.yml，:333 verifyInstallerSha512 只比对两者一致性；frontend/package.json:82 signAndEditExecutable: false ⇒ 安装包无 Authenticode 签名。
- 影响：控制更新源（对象存储凭据、4443 端口服务、发布流水线）即可推送任意安装包，客户端下次检查更新即静默下载安装；TLS 证书固定只能防中间人，防不了源端被改或内鬼。
- 修复摘要：按既定三步走完成强制验签：发布 .sig、填充公钥、把缺公钥从“跳过”改为“拒绝更新”。
- 修复文件：frontend/electron/updater/updater.js、frontend/.env.production.cloud、frontend/package.json、frontend/electron/updater/__tests__/updater.test.js
- 建议测试：npm --prefix frontend test

### SEC-AFEFF · [低] [u10] — 构建流水线用的第三方组件没有锁定版本，依赖漏洞扫描报错也不会让构建失败，等于没人管。
- 简述：构建流水线用的第三方组件没有锁定版本，依赖漏洞扫描报错也不会让构建失败，等于没人管。
- 详细问题：CI 以可变 tag 引用第三方 Action，依赖漏洞扫描失败不阻断流水线
- 专家 / section：security / supply-chain
- 位置：`.github/workflows/ci.yml:48`
- 置信度：high · kind：direct
- 证据：.github/workflows/ci.yml:14/28/45 actions/checkout@v4、:15 actions/setup-java@v4、:29 actions/setup-node@v4、:48 google/osv-scanner-action/osv-scanner-action@v2.0.3 全部按 tag/分支引用；:47 continue-on-error: true 使依赖漏洞扫描失败不影响 job 结论。
- 影响：上游 action 仓库被打新 tag 或劫持时，CI 会执行攻击者代码并接触仓库令牌与发布凭据；osv-scanner 的告警被吞掉，已知漏洞依赖可以长期留在主干。
- 修复摘要：Actions 固定到 commit SHA，漏洞扫描改为阻断（或至少单独出 job 并告警到人）。
- 修复文件：.github/workflows/ci.yml
- 建议测试：不适用（CI 配置变更，需在 GitHub Actions 上验证一次运行）

### SEC-6B08B · [低] [u10] — 页面一旦被人塞进恶意脚本，这个脚本能用 eval 拼载荷，还能把读到的内容发到任意外部网站。
- 简述：页面一旦被人塞进恶意脚本，这个脚本能用 eval 拼载荷，还能把读到的内容发到任意外部网站。
- 详细问题：渲染进程 CSP 允许 unsafe-eval，且 connect-src 放开到任意 https 域名
- 专家 / section：security / browser
- 位置：`frontend/index.html:7`
- 置信度：high · kind：direct
- 证据：frontend/index.html:7 <meta http-equiv="Content-Security-Policy" content="default-src 'self'; script-src 'self' 'unsafe-eval'; … connect-src 'self' https: wss:; object-src 'none'; base-uri 'self'; frame-ancestors 'none'">；frontend/launcher.html:7 同样的 script-src 'unsafe-eval'。渲染进程内存中持有登录 token（frontend/src/utils/secureToken.js），并可通过 preload 暴露的 IPC 调用主进程能力。
- 影响：任何 DOM XSS（社区/评论渲染、第三方依赖、未来新增富文本）都会放大为可 eval 任意代码、可把页面内容与渲染进程内 token 发往任意 https 主机的完整接管。
- 修复摘要：去掉 unsafe-eval，把 connect-src 收紧到实际使用的 API/更新域名。
- 修复文件：frontend/index.html、frontend/launcher.html、frontend/electron/main.js
- 建议测试：npm --prefix frontend test

### REL-34EFB · [高] [u07] — 用户中途关掉聊天窗口，系统会把它当成模型服务故障记一笔；攒够几次，所有用户都会被提示“模型繁忙”，哪怕模型本身好好的。
- 简述：用户中途关掉聊天窗口，系统会把它当成模型服务故障记一笔；攒够几次，所有用户都会被提示“模型繁忙”，哪怕模型本身好好的。
- 详细问题：客户端中断被记成上游失败，5 次用户放弃就能打开熔断器
- 专家 / section：reliability / resilience
- 位置：`backend-agent/app/gateway.py:279`
- 置信度：high · kind：direct
- 证据：stream_text 里 `try:`（L259）包住了 `async for delta in self._stream_once(...): yielded; yield delta`（L264-268），于是消费方关闭连接产生的 GeneratorExit 与任务取消会落到 `except BaseException as exc: await breaker.on_error(exc); raise`（L279-281）。resilience.py 的 on_error（L132-149）对非 GatewayBusinessError 一律 `self._results.append(False)` + `_maybe_open()`；BreakerConfig 为 sliding_window_size=20、minimum_number_of_calls=5、failure_rate_threshold=50.0、wait_duration_seconds=30.0（L67-76）。已实跑复现（venv + PYTHONPATH=.）：连续 5 次 `try: raise GeneratorExit() except BaseException as exc: await breaker.on_error(exc)` 后 `attempt 4 permit True -> state CircuitState.OPEN`，日志 `breaker provider=platform|base=https://api.example.com OPEN (failure_rate=100%, samples=5)`，随后 `try_acquire()` 返回 False。
- 影响：一次 5 个用户手滑关窗（或前端 abort、网关超时、进程收到取消）就能把该 provider+baseUrl 的熔断器打开 30 秒；期间所有用户的所有请求都不再打上游、统一返回“对方模型暂时繁忙，请稍后再试”，日志还把原因写成上游故障，误导排查方向。
- 修复摘要：把“消费方主动放弃”从熔断样本里剔除，只对真实上游错误记失败。
- 修复文件：backend-agent/app/gateway.py、backend-agent/app/resilience.py、backend-agent/tests/test_gateway.py、backend-agent/tests/test_resilience.py
- 建议测试：pytest -q backend-agent/tests/test_gateway.py；pytest -q backend-agent/tests/test_resilience.py；新增用例：test_stream_client_abort_does_not_open_breaker

### REL-42BC8 · [高] [u01] — 回复已经生成完、准备收尾时只要冒出一点非网络异常，这条流就不会发结束标记、回复也不会存进数据库，用户界面会一直转圈。
- 简述：回复已经生成完、准备收尾时只要冒出一点非网络异常，这条流就不会发结束标记、回复也不会存进数据库，用户界面会一直转圈。
- 详细问题：finishSseSuccess 只接 IOException：收尾异常会让流不结束、回复不落库
- 专家 / section：reliability / error-handling
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/ai/SseChatStreamHelper.java:51`
- 置信度：high · kind：direct
- 证据：L51-65：`try { if (callback != null) callback.beforeStreamComplete(emitter, fullContent); emitter.send(SseEmitter.event().data("[DONE]")); emitter.complete(); } catch (IOException e) { log.warn("SSE complete failed", e); emitter.complete(); }`，`callback.onComplete(fullContent, null)` 写在 try 之外（L62-64）。调用点 `AgentStreamBridge.finish()`（AgentStreamBridge.java:113）挂在 reactor 的完成回调 `subscribe(..., relay::finish)`（ChatTurnAdapter.java:72-79）：回调里抛出的 RuntimeException 会被 Reactor 记为 dropped error，emitter 不会被 complete，`onComplete` 也不会执行。SseEmitter 超时是 `SSE_TIMEOUT_MS = 1_800_000L`（ChatTurnAdapter.java:37）；而落库、记录回合、记忆入队、通知全部只在 ConversationService.onComplete（L377-407）里发生。`emitter.send` 在已完成/已超时状态下抛的是 IllegalStateException 而不是 IOException。AgentStreamBridgeTest 的 RecordingCallback 的 beforeStreamComplete 永不抛异常，这条路径没有测试。
- 影响：收尾阶段一旦抛非 IOException（emitter 已完成/客户端刚断、回调里 DB 写入失败、序列化失败），用户拿到的是截断的回复且连接最长挂 30 分钟不收尾；刷新后这条回复消失（没落库、没触发记忆摘要与通知），服务端只留一条 Reactor onErrorDropped，几乎无法定位。
- 修复摘要：收尾与 [DONE] 发送、落库回调三段各自兜底，保证 emitter 一定被 complete、onComplete 一定被调用一次。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/ai/SseChatStreamHelper.java、backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentStreamBridge.java、backend/lianyu-web/src/test/java/com/lianyu/web/agent/AgentStreamBridgeTest.java
- 建议测试：mvn -q -f backend/pom.xml test -Dtest=AgentStreamBridgeTest；新增用例：finishSseSuccess_whenBeforeCompleteThrows_stillCompletesAndPersists

### REL-388F4 · [中] [u01] — 后台生成（朋友圈评论、角色日记、主动问候）总共只重试约 4.5 秒，而上游熔断要 30 秒才恢复，所以一次短暂抖动就把这批任务全丢进死信队列，不会自动补做。
- 简述：后台生成（朋友圈评论、角色日记、主动问候）总共只重试约 4.5 秒，而上游熔断要 30 秒才恢复，所以一次短暂抖动就把这批任务全丢进死信队列，不会自动补做。
- 详细问题：后台任务重试预算（3 次 / 约 4.5s）短于熔断窗口（30s），熔断一开就全进死信
- 专家 / section：reliability / resilience
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/ai/background/AiBackgroundConsumer.java:71`
- 置信度：high · kind：plan
- 证据：AiBackgroundConsumer：`MAX_ATTEMPTS = 3`（L32）、`retryDelayMs = 1500L`（L43）、`long delay = Math.max(1L, retryDelayMs) * attempt`（L71）→ 重试两次、共等 1.5s+3s；只有 `isAgentTransient(e)`（L102-116）为真才重试，判定方式是字符串匹配“prompt 组装失败/消息发送失败/服务暂时不可用”。上游 Python 熔断参数 wait_duration_seconds=30.0（resilience.py L67-76），application.yml L523 同样是 `wait-duration-in-open-state: 30s`。熔断打开时 Python 走 `sse_error("对方模型暂时繁忙，请稍后再试")`，Java 侧 ChatTurnFacade L103/L112 统一映射为 AI_PROVIDER_ERROR“消息发送失败，请稍后再试”，因此三次重试全部落在同一个打开窗口内，最终 `throw last` → `defaultRequeueRejected(false)`（RabbitMqConfig L119-132）→ 死信。
- 影响：上游抖动 30 秒，就会永久丢掉这段时间排队的所有 MOMENTS_*/CHARACTER_DIARY/主动问候任务：用户侧表现为角色忽然不再发朋友圈、不再主动说话，既没有补偿也没有告警，只能靠人工重放死信。
- 修复摘要：让重试窗口覆盖熔断恢复时间，并给死信一条可自动补做的路。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/ai/background/AiBackgroundConsumer.java、backend/lianyu-web/src/main/java/com/lianyu/web/config/RabbitMqConfig.java、backend-agent/app/resilience.py、backend/lianyu-service/src/test/java/com/lianyu/service/ai/background/AiBackgroundConsumerTest.java
- 建议测试：mvn -q -f backend/pom.xml test -Dtest=AiBackgroundConsumerTest；新增用例：retrySurvivesBreakerOpenWindow

### REL-49628 · [中] [u08] — 推送通知的队列没有配置死信队列，消费者也没有兜底捕获，一旦推送出错这条通知就彻底丢了，既不能重投也查不到。
- 简述：推送通知的队列没有配置死信队列，消费者也没有兜底捕获，一旦推送出错这条通知就彻底丢了，既不能重投也查不到。
- 详细问题：event.broadcast.queue 没有死信配置，消费者无 try/catch：抛错即丢通知
- 专家 / section：reliability / resilience
- 位置：`backend/lianyu-web/src/main/java/com/lianyu/web/config/RabbitMqConfig.java:84`
- 置信度：high · kind：direct
- 证据：RabbitMqConfig：`eventBroadcastQueue()`（L84-87）是 `QueueBuilder.durable(QUEUE_EVENT_BROADCAST).build()`，没有 `x-dead-letter-exchange`/`x-dead-letter-routing-key`；同文件 `memorySummaryQueue()`（L54-62）与 `aiBackgroundQueue()`（L69-77）都设了 DLX。`rabbitListenerContainerFactory`（L119-132）设置 `factory.setDefaultRequeueRejected(false)` 并注释“失败后不回队列，交给 DLQ”，而 NotificationPushConsumer 用的是默认工厂（注解只指定 queues 与 concurrency）。NotificationPushConsumer.onPushTask（L20-25）直接调用 `webPushService.sendToUser(task)`，没有任何 try/catch；`task == null || task.userId() == null` 时静默 return（消息被 ack）。
- 影响：推送消费一旦抛异常（订阅查询/DB/编码），这条通知既不在队列里、也没有死信可查，用户永远收不到，运维无法重放；空 payload 的推送则被静默吞掉，连日志都没有。
- 修复摘要：把推送队列接上死信通道，并让消费者把异常显式抛出去。
- 修复文件：backend/lianyu-web/src/main/java/com/lianyu/web/config/RabbitMqConfig.java、backend/lianyu-service/src/main/java/com/lianyu/service/notification/NotificationPushConsumer.java
- 建议测试：mvn -q -f backend/pom.xml test -Dtest=NotificationServiceTest；新增用例：pushListenerFailure_routesToDeadLetter

### REL-97AFD · [低] [u01] — 群聊里某个角色生成失败时，用户只看到它不说话，前端没有任何提示，日志里也只有一行不带调用栈的原因。
- 简述：群聊里某个角色生成失败时，用户只看到它不说话，前端没有任何提示，日志里也只有一行不带调用栈的原因。
- 详细问题：群聊单角色生成失败被吞：静默缺席 + 日志无堆栈
- 专家 / section：reliability / error-handling
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/conversation/GroupChatService.java:414`
- 置信度：high · kind：direct
- 证据：L401-419 的完成循环：`try { reply = f.join(); } catch (Exception ignored) { // handled in generateCharacterReply }`，随后 `if (reply == null || reply.content() == null || reply.content().isBlank()) continue;`；被调用的 generateCharacterReply L525-527 `catch (Exception e) { log.error("Group chat error for character {}: {}", member.getCharacterId(), e.getMessage()); return null; }` —— 只记 message、没有堆栈。
- 影响：某个角色这一轮生成失败时，它在群聊里静默缺席，前端没有任何“暂时无法回复”的提示，用户只会觉得角色不理人；排查时日志只有一行 message，定位不到是上游还是本地异常。
- 修复摘要：失败要留堆栈、要能被前端看见。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/conversation/GroupChatService.java
- 建议测试：mvn -q -f backend/pom.xml test -Dtest=GroupChatServiceTest

### REL-E2A96 · [低] [u01] — 主动问候和定时语音发送失败时只写一行 DEBUG 日志，运维按 WARN 告警的话完全看不到“角色不再主动说话”这件事。
- 简述：主动问候和定时语音发送失败时只写一行 DEBUG 日志，运维按 WARN 告警的话完全看不到“角色不再主动说话”这件事。
- 详细问题：主动消息失败只记 DEBUG：整条链路停摆时无 WARN、无指标
- 专家 / section：reliability / observability
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ProactiveChatScheduler.java:174`
- 置信度：high · kind：direct
- 证据：发送循环 L148-176：`catch (Exception e) { log.debug("Proactive chat skipped for convId={}, reason={}", item.conv().getId(), e.getMessage()); }`；`trySendWaitNudgeVoices` L215-217 与定时语音 L265 同族，都是 log.debug + e.getMessage()。tick()（L62-63）本身没有外层 try/catch，批次查询（L68-95）也不在 per-candidate 隔离内。application.yml L531 默认 `com.lianyu: DEBUG` 让这些行可见，但一旦生产日志按 INFO 收敛或按 WARN/ERROR 告警，失败就完全不可见，也没有任何计数器。
- 影响：主动问候整体失效（例如定时语音全部被策略跳过、或每轮都抛异常）时，日志里既没有 WARN 也没有指标，只能等用户反馈“角色怎么不理我了”。
- 修复摘要：把失败从 debug 提升到可见级别，并加最小计数。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ProactiveChatScheduler.java
- 建议测试：mvn -q -f backend/pom.xml test -Dtest=ProactiveChatSchedulerTest

### REL-38C23 · [中] [u07] — 聊天流中途崩掉时，服务端只打印一行错误摘要、没有调用栈，出了代码级毛病根本定位不到哪里炸的。
- 简述：聊天流中途崩掉时，服务端只打印一行错误摘要、没有调用栈，出了代码级毛病根本定位不到哪里炸的。
- 详细问题：流式端点顶层异常只打印 repr，没有 traceback
- 专家 / section：reliability / observability
- 位置：`backend-agent/app/main.py:253`
- 置信度：high · kind：direct
- 证据：L252-256：`except Exception as exc:  # noqa: BLE001 - 生成器异常必须可见，否则上游只看到空回复` → `logger.warning("chat-stream failed: user=%s frames=%d err=%r", body.user_id, frames, exc)`，没有 `exc_info=True`；`/agent/chat-turn/stream` 的 L520-522 是同一写法（`logger.warning("chat-turn stream failed: ... err=%r", ...)`）。这两处是主聊天链路唯一的兜底 catch（stream_chat.stream_chat_events 只处理 GatewayRejectedError/GatewayError，其余异常会一路冒到这里）。
- 影响：遇到代码级异常（AttributeError/KeyError/TypeError/NoneType 调用）时，日志只有一行 repr，没有文件与行号；而这正是兜底 catch 存在的唯一理由，等于把最容易出 bug 的路径变成了最难定位的路径。
- 修复摘要：兜底日志带堆栈，并区分“预期失败”和“未知异常”。
- 修复文件：backend-agent/app/main.py、backend-agent/app/stream_chat.py
- 建议测试：pytest -q backend-agent/tests/test_stream_chat.py；新增用例：test_chat_stream_unexpected_error_logs_traceback

### REL-7C13B · [低] [u01] — 关系衰减、情绪衰减、朋友圈评论补偿这三个定时任务失败时只留一行文字原因，没有调用栈也没有连续失败计数。
- 简述：关系衰减、情绪衰减、朋友圈评论补偿这三个定时任务失败时只留一行文字原因，没有调用栈也没有连续失败计数。
- 详细问题：三个定时任务失败只记 e.getMessage()：无堆栈、无连续失败告警
- 专家 / section：reliability / observability
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/character/EmotionDecayScheduler.java:34`
- 置信度：high · kind：direct
- 证据：EmotionDecayScheduler L33-35 `catch (Exception e) { log.warn("Emotion decay tick failed: {}", e.getMessage()); }`；RelationshipDecayScheduler L33-35 `catch (Exception e) { log.warn("Relationship decay tick failed: {}", e.getMessage()); }`；MomentsCommentReconciliationScheduler L59-61 `catch (Exception e) { log.warn("Moments comment reconcile failed: postId={}, reason={}", postId, e.getMessage()); }`。三处都没有堆栈、没有计数、没有指标。
- 影响：定时任务连续失败（连接池耗尽、SQL 报错、迁移缺列）时，日志里只有一堆同类文本，看不出根因，也没有“连续失败 N 次”的告警触发点；衰减/补偿长期失效只能靠用户察觉数值不变化。
- 修复摘要：定时任务失败要带堆栈，并能在连续失败时升级报警。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/character/EmotionDecayScheduler.java、backend/lianyu-service/src/main/java/com/lianyu/service/relationship/RelationshipDecayScheduler.java、backend/lianyu-service/src/main/java/com/lianyu/service/moments/MomentsCommentReconciliationScheduler.java
- 建议测试：mvn -q -f backend/pom.xml test -Dtest=RelationshipHeuristicsTest

### REL-F9370 · [中] [u07] — 按项目文档跑 Python 测试会直接报错跑不起来，只有先手工装好虚拟环境才行，所以这道门禁在新机器和 CI 上是失效的。
- 简述：按项目文档跑 Python 测试会直接报错跑不起来，只有先手工装好虚拟环境才行，所以这道门禁在新机器和 CI 上是失效的。
- 详细问题：`pytest -q backend-agent` 在干净环境 100% 失败：缺 pythonpath，只有 .venv 能跑
- 专家 / section：reliability / tests
- 位置：`backend-agent/pyproject.toml:30`
- 置信度：high · kind：direct
- 证据：Gap：AGENTS.md §4 要求 `cd backend-agent && pytest` 全绿。Current：在仓库根跑 `pytest -q backend-agent` → 48 个 collection error，全部是 `ModuleNotFoundError: No module named 'app'`（`from app.main import app`）；`cd backend-agent && pytest -q` 同样失败（全局解释器 Python 3.11）。pyproject.toml L30-32 只有 `asyncio_mode = "auto"` 与 `testpaths = ["tests"]`，没有 `pythonpath`；backend-agent 下也没有 conftest.py。只有 `backend-agent/.venv/Scripts/python.exe -m pytest -q` 能跑通（1157 passed），因为 `.venv/Lib/site-packages/_editable_impl_lianyu_agent.pth` 里写着 backend-agent 的绝对路径（即曾做过 `pip install -e .`）。Proposed：让门禁不依赖机器上残留的 .venv。Why：现在任何人都无法从干净检出复现测试结果，CI 要么红要么跳过，回归保护实际不存在。
- 影响：换机器或 CI 按文档跑测试会 100% 失败，容易被当成“代码坏了”；反过来也可能被忽略成“环境问题”，导致 Python 侧 1157 个用例长期不在门禁里跑。
- 修复摘要：让 pytest 从仓库根就能导入 app 包，并固定解释器版本。
- 修复文件：backend-agent/pyproject.toml、backend-agent/Dockerfile、AGENTS.md
- 建议测试：pytest -q backend-agent；新增冒烟：python -c "import app.main"

### REL-BC684 · [低] [u10] — 管理后台只有一个 8 行的菜单测试，而且脚本允许“一个测试都没有也算通过”，所以后台登录态和接口出错不会被任何门禁拦住。
- 简述：管理后台只有一个 8 行的菜单测试，而且脚本允许“一个测试都没有也算通过”，所以后台登录态和接口出错不会被任何门禁拦住。
- 详细问题：admin 控制台仅 1 个菜单测试，且 `--passWithNoTests` 让空套件也变绿
- 专家 / section：reliability / tests
- 位置：`admin/package.json:9`
- 置信度：high · kind：plan
- 证据：`"test": "vitest --passWithNoTests"`；`admin/src/__tests__/menu.test.js` 只有 8 行、仅断言菜单 id；`admin/src/api.js`（token 存 localStorage、adminLogin/adminLogout/getOverview）、`admin/src/stores/session.js`（单行 pinia 持久化 token）没有任何测试。实跑 `npm --prefix admin test` 直接失败：`'vitest' 不是内部或外部命令`（admin 未安装依赖），说明该命令在本地/CI 都没有真正执行过。
- 影响：`--passWithNoTests` 让“没有测试”也返回成功，管理后台的登录态过期、权限接口 401/403、列表加载失败等改动静默通过门禁；一旦线上后台登录异常，没有任何用例能先一步发现。
- 修复摘要：去掉“空套件也算过”的退路，并给后台登录/接口补最小用例。
- 修复文件：admin/package.json、admin/src/__tests__/menu.test.js、admin/src/api.js、admin/src/stores/session.js
- 建议测试：npm --prefix admin test；新增用例：session 401 清理与 api 错误分支

### REL-416DA · [低] [u11] — 记忆防抖那条用例里写死了真实等待 5.2 秒，跑得慢还容易在机器繁忙时偶发失败。
- 简述：记忆防抖那条用例里写死了真实等待 5.2 秒，跑得慢还容易在机器繁忙时偶发失败。
- 详细问题：MemoryWriterDebounceTest 硬等 5.2 秒验证补发，慢且时序脆弱
- 专家 / section：reliability / tests
- 位置：`backend/lianyu-service/src/test/java/com/lianyu/service/memory/MemoryWriterDebounceTest.java:72`
- 置信度：high · kind：direct
- 证据：`maybeReschedule_pendingFlagSendsFollowUpTask` 通过 `ReflectionTestUtils.invokeMethod(writer, "maybeReschedule", task)` 触发后 `Thread.sleep(5200);` 再断言补发只发生一次；被验证的常量是 `RESCHEDULE_DELAY_SECONDS = 5`（MemoryWriter 的重排调度器）。
- 影响：单条用例固定占用 5.2 秒，是全套件里最慢的一条；机器繁忙或 CI 抖动时 5.2s 可能不够 → 偶发红，被视为“已知 flake”后真实回归也会被忽略。
- 修复摘要：把重排延迟做成可注入值，测试用毫秒级延迟 + 轮询断言。
- 修复文件：backend/lianyu-service/src/test/java/com/lianyu/service/memory/MemoryWriterDebounceTest.java、backend/lianyu-service/src/main/java/com/lianyu/service/memory/MemoryWriter.java
- 建议测试：mvn -q -f backend/pom.xml test -Dtest=MemoryWriterDebounceTest

### MNT-9D4F4 · [中] [u08] — 后端把“Python 服务是否活着”的检查在五个类里各抄了一遍，每个都带自己的 3 秒缓存；同目录另一个类却用另一套开关判断，所以改一次刷新间隔要改五处，漏一处就会出现“有的功能能连通、有的直接跳过”。
- 简述：后端把“Python 服务是否活着”的检查在五个类里各抄了一遍，每个都带自己的 3 秒缓存；同目录另一个类却用另一套开关判断，所以改一次刷新间隔要改五处，漏一处就会出现“有的功能能连通、有的直接跳过”。
- 详细问题：Agent 健康探测在 5 个适配器里各写一份，门控策略还不一致
- 专家 / section：maintainability / simplify
- 位置：`backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentBlockingAdapter.java:126`
- 置信度：high · kind：plan
- 证据：5 个类各有一份完全相同的实现（grep 'private boolean reachable()'）: AgentBlockingAdapter.java:126, AgentStreamAdapter.java:106, AgentPromptAssemblyAdapter.java:108, AgentSessionSummaryAdapter.java:109, ChatTurnAdapter.java:204，方法体均为 `long now = System.currentTimeMillis(); if (now - healthyCheckedAt < 3_000L) return healthy; healthy = client.isHealthy(); healthyCheckedAt = now; return healthy;`，字段为 `private volatile boolean healthy; private volatile long healthyCheckedAt;`。而同包 AgentTextAdapter.java:33 的门控是 `if (!client.properties().agentActive()) { return null; }`，不看 isHealthy()。
- 影响：健康判定的 TTL 与语义没有单一所有者：调整 3 秒窗口或新增“半开”策略要改 5 处；TextAdapter 与其余 4 个适配器对“服务可用”给出不同答案，排查“文本能回、流式不回”时容易被误导。
- 修复摘要：抽一个 AgentHealthGate（持有 client 与 TTL），5 个适配器注入同一个实例，并显式对齐或注明 TextAdapter 的门控差异。
- 修复文件：backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentHealthGate.java、backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentBlockingAdapter.java、backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentStreamAdapter.java、backend/lianyu-web/src/main/java/com/lianyu/web/agent/ChatTurnAdapter.java、backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentSessionSummaryAdapter.java、backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentPromptAssemblyAdapter.java、backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentTextAdapter.java
- 建议测试：cd backend && mvn -q test

### MNT-66CE2 · [低] [u06] — 三个聊天页面各自复制了一段“把消息时间转成毫秒、再渲染成时间分隔条”的小函数，文本完全一样；哪天要改分隔条规则（比如跨天显示日期），必须记得三个文件都改。
- 简述：三个聊天页面各自复制了一段“把消息时间转成毫秒、再渲染成时间分隔条”的小函数，文本完全一样；哪天要改分隔条规则（比如跨天显示日期），必须记得三个文件都改。
- 详细问题：消息时间解析/分隔条格式化在 3 个页面各写一份
- 专家 / section：maintainability / simplify
- 位置：`frontend/src/pages/ChatPage.vue:1556`
- 置信度：high · kind：direct
- 证据：ChatPage.vue:1556-1565、GroupChatPage.vue:883-892、QuickChatPage.vue:157-166 三处的 `function parseMessageTime(msg) { const ts = msg?.createdAt || msg?._time; if (!ts) return Date.now(); const ms = new Date(ts).getTime(); return Number.isNaN(ms) ? Date.now() : ms }` 与 `function formatTimeDivider(ms) { return formatSmartTime(ms, { t, locale: locale.value }) }` 逐字相同；ChatPage.vue:1567-1569 还多一份同实现的 formatTime。
- 影响：分隔条策略（脏时间回退、跨天格式、locale 透传）被拆成三份，修改时容易只改一处，导致三个页面时间显示不一致；新增页面还会继续复制第四份。
- 修复摘要：把 parseMessageTime / formatTimeDivider 提到 frontend/src/utils/messageTimeline.js，三个页面改成 import。
- 修复文件：frontend/src/utils/messageTimeline.js、frontend/src/pages/ChatPage.vue、frontend/src/pages/GroupChatPage.vue、frontend/src/pages/QuickChatPage.vue
- 建议测试：npm --prefix frontend test

### MNT-258C6 · [中] [u07] — 模型运行时的入口文件把聊天、语音、记忆、prompt 组装等二十多个接口全塞在同一个 1200 多行的文件里，一个函数最长一百多行；只想改语音接口的人也得在这个大文件里翻找，改动容易互相踩。
- 简述：模型运行时的入口文件把聊天、语音、记忆、prompt 组装等二十多个接口全塞在同一个 1200 多行的文件里，一个函数最长一百多行；只想改语音接口的人也得在这个大文件里翻找，改动容易互相踩。
- 详细问题：backend-agent/app/main.py 1278 行、25 个端点没有拆 router
- 专家 / section：maintainability / simplify
- 位置：`backend-agent/app/main.py:1`
- 置信度：high · kind：plan
- 证据：`wc -l backend-agent/app/main.py` = 1278；文件内 16 个 import、31 个 def、25 个 `@app.post`/`@app.get` 装饰器，0 处 `APIRouter`；最长回调 async def chat_turn_stream 102 行（起始 495），chat_stream 94 行（201），text_chat 92 行（360），proactive_turn 81 行（1060）；同目录第二大模块 gateway.py 仅 504 行。
- 影响：所有端点的装配、鉴权前缀、异常映射都耦合在一个文件里：改任一接口都要重读 1278 行上下文，合并冲突集中在单文件，单测只能整体导入 app，无法按域隔离。
- 修复摘要：按域把端点拆成 APIRouter（chat / voice / memory / prompt / observe），main.py 只保留 app 装配与 include_router，路径与响应结构不变。
- 修复文件：backend-agent/app/main.py、backend-agent/app/routers/chat.py、backend-agent/app/routers/voice.py、backend-agent/app/routers/memory.py、backend-agent/app/routers/prompt.py
- 建议测试：pytest -q backend-agent

### MNT-0DED5 · [中] [u01] — 后端服务层里“AI 能力”和“角色”两个包互相引用：AI 包要用角色的导入解析器与日记服务，角色包又要用 AI 包的密钥保管、语音注册和后台任务。结果这两个包只能一起改、一起测，想单独拿走其中一个都做不到。
- 简述：后端服务层里“AI 能力”和“角色”两个包互相引用：AI 包要用角色的导入解析器与日记服务，角色包又要用 AI 包的密钥保管、语音注册和后台任务。结果这两个包只能一起改、一起测，想单独拿走其中一个都做不到。
- 详细问题：com.lianyu.service.ai 与 com.lianyu.service.character 包级互相依赖
- 专家 / section：maintainability / coupling
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/character/CharacterDiaryService.java:18`
- 置信度：high · kind：plan
- 证据：ai→character：AiChatService.java:11 `import com.lianyu.service.character.CharacterImportDraftMapper;`、:12 `CharacterImportSourceParser`；ai/background/AiBackgroundConsumer.java:5 `import com.lianyu.service.character.CharacterDiaryService;`。character→ai：CharacterDiaryService.java:18-20 `ApiKeyVaultService`、`ai.background.AiBackgroundPublisher`、`AiBackgroundTask`；CharacterImportService.java:5-6 `AiChatService`、`ApiKeyVaultService`；CharacterService.java:32 `PetVoiceRegistry`。全 service 包只有这一对互指。
- 影响：两个包（ai 20 类、character 15 类）无法独立抽取或做包级测试；任何一方的构造签名变化都会同时牵动另一边，包边界名存实亡。
- 修复摘要：把被交叉引用的能力抽成 service/port 下的接口（密钥保管、语音注册、日记后台任务、角色导入解析），两边只依赖接口。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/port/ApiKeyVaultPort.java、backend/lianyu-service/src/main/java/com/lianyu/service/ai/AiChatService.java、backend/lianyu-service/src/main/java/com/lianyu/service/character/CharacterDiaryService.java、backend/lianyu-service/src/main/java/com/lianyu/service/character/CharacterImportService.java
- 建议测试：cd backend && mvn -q test

### MNT-938BA · [中] [u06] — 前端通知模块和“点通知跳转”的组件互相 import，形成整个前端唯一一个循环依赖；通知 store 同时管长连接、提示音、弹窗和跳转，改其中任何一块都得把另一半也读一遍。
- 简述：前端通知模块和“点通知跳转”的组件互相 import，形成整个前端唯一一个循环依赖；通知 store 同时管长连接、提示音、弹窗和跳转，改其中任何一块都得把另一半也读一遍。
- 详细问题：notifications store 与 useNotificationNavigation 互相 import（全前端唯一环）
- 专家 / section：maintainability / coupling
- 位置：`frontend/src/stores/notifications.js:14`
- 置信度：high · kind：plan
- 证据：notifications.js:14 `import { navigateToNotification } from '@/composables/useNotificationNavigation'`；useNotificationNavigation.js:3 `import { useNotificationsStore } from '@/stores/notifications'`。对 frontend/src 219 个模块、476 条 import 边做 Tarjan 强连通检测，仅此一处在环上。该 store 417 行，同时负责 STOMP 连接、AudioContext 提示音、Element 弹窗、顶栏 toast 与未读计数；composable 125 行负责路由跳转与目标解析。
- 影响：环使这两个模块无法单独加载/单测（store 测试必须连 router 与跳转逻辑），也让“通知到达→选哪个页面”的决策散落在 store 与 composable 两侧，新增通知类型要同时改两处。
- 修复摘要：让 store 只发事件（或回调注入），导航决策留在 composable/router 侧，打断 import 环。
- 修复文件：frontend/src/stores/notifications.js、frontend/src/composables/useNotificationNavigation.js
- 建议测试：npm --prefix frontend test

### MNT-A43FA · [中] [u01] — “哪些角色能打语音电话”这件事被写了两份：Java 里一个常量集合、前端一个常量数组，两边注释都写着“必须和对方保持一致”。新增一个能语音的角色时漏改一边，就会出现前端显示可拨打、后端直接拒绝。
- 简述：“哪些角色能打语音电话”这件事被写了两份：Java 里一个常量集合、前端一个常量数组，两边注释都写着“必须和对方保持一致”。新增一个能语音的角色时漏改一边，就会出现前端显示可拨打、后端直接拒绝。
- 详细问题：语音可拨打角色白名单在 Java 与 JS 各手写一份，靠注释人工同步
- 专家 / section：maintainability / coupling
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/conversation/VoiceCallService.java:69`
- 置信度：high · kind：plan
- 证据：VoiceCallService.java:67 注释 `Keep in sync with frontend {@code voiceCallPets.js}.`，:69-83 `private static final Set<String> VOICE_CALL_PET_IDS = Set.of("klee","ganyu","ayaka","raiden","elysia","erii_uesugi","yae_miko","kokomi","shenhe","nahida","hu_tao","furina","noelle")`；消费点 :242-243 `VOICE_CALL_PET_IDS.contains(petId)`。前端 frontend/src/constants/voiceCallPets.js:1 注释 `must match VoiceCallService.VOICE_CALL_PET_IDS`，同文件导出同一批 13 个 id。
- 影响：同一事实两处定义且没有任何测试比对：漏改时前端仍显示“语音通话”入口，后端会走 else 分支返回不可拨打，用户看到的是“按钮在但打不通”。
- 修复摘要：改由后端作为唯一来源（提供可拨打角色列表接口，或在构建期生成前端常量），并加一致性测试兜底。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/conversation/VoiceCallService.java、frontend/src/constants/voiceCallPets.js
- 建议测试：cd backend && mvn -q test；npm --prefix frontend test

### MNT-745BE · [中] [u03] — 仓库里有一份 617 行的繁体中文语言包，但程序从来没加载过它——用户选不到繁体，代码里也把它从语言列表里去掉了；只剩一个测试还在检查它的 key 是否齐全，等于逼着后来人继续维护一份没人看得到的译文。
- 简述：仓库里有一份 617 行的繁体中文语言包，但程序从来没加载过它——用户选不到繁体，代码里也把它从语言列表里去掉了；只剩一个测试还在检查它的 key 是否齐全，等于逼着后来人继续维护一份没人看得到的译文。
- 详细问题：zh-TW 语言包未被注册（CONFIRMED_DEAD），仅测试在续命
- 专家 / section：maintainability / cleanup
- 位置：`frontend/src/i18n/index.js:12`
- 置信度：high · kind：plan
- 证据：i18n/index.js:9-16 只 `import zh/ja/en`，`messages: { zh, ja, en }`；constants/language.js:3 `export const SUPPORTED_LANGUAGES = /** @type {const} */ (['zh', 'ja', 'en'])`，normalizeLanguage 会把未知值回退到 'zh'；locales/zh-TW.js 617 行完整译文，全仓唯一 import 是 i18n/__tests__/memoryLocale.test.js:5,7（断言四语 memory.* key 完全一致）；utils/dateLocale.js:4 仍判断 'zh-TW' 但该值已不可达。搜索过程：grep -rn "zh-TW" frontend/src 仅命中 locales/zh-TW.js、该测试、dateLocale.js 与 language.js 的注释。
- 影响：617 行死译文 + 一个专门保护它的测试：按 AGENTS.md §3.2“四语齐”的要求，后续新增 key 还得往这里补，否则测试红；而真实用户永远看不到繁体界面。
- 修复摘要：先确认产品是否需要繁体中文：不需要就删除语言包并把“四语齐”改为三语；需要就把它真正接进语言列表和 UI 选项。
- 修复文件：frontend/src/i18n/locales/zh-TW.js、frontend/src/i18n/index.js、frontend/src/constants/language.js、frontend/src/i18n/__tests__/memoryLocale.test.js、frontend/src/utils/dateLocale.js
- 建议测试：npm --prefix frontend test

### MNT-B9294 · [中] [u03] — 前端有四个模块已经没人用了，合计近 800 行：一个录音 composable（只有它自己的测试在用）、一个聊天截图工具、一个反调试工具，还有一个只被复制走逻辑的语音转写 API。读代码的人会以为这些能力还在生效。
- 简述：前端有四个模块已经没人用了，合计近 800 行：一个录音 composable（只有它自己的测试在用）、一个聊天截图工具、一个反调试工具，还有一个只被复制走逻辑的语音转写 API。读代码的人会以为这些能力还在生效。
- 详细问题：4 个前端模块无任何生产引用（合计 791 行），其中一个只被自己的测试引用
- 专家 / section：maintainability / cleanup
- 位置：`frontend/src/composables/useVoiceRecorder.js:1`
- 置信度：high · kind：plan
- 证据：对 frontend/src 下 170 个 js/vue 模块（排除 __tests__，入口 main.js/App.vue）建 import 图：useVoiceRecorder.js（466 行）唯一引用为 composables/__tests__/useVoiceRecorder.test.js:2；utils/captureChatShareScreenshot.js（220 行）`export async function captureChatShareScreenshot(container, selectedIds)` 在 :128，全仓 0 调用；utils/antiDebug.js（93 行）仅被 utils/logger.js:4 的注释提到（“生产环境 console.* 被 antiDebug 吞掉”）；api/asr.js（12 行）`transcribeAudio` 0 调用，同一 multipart 逻辑被 api/voiceCall.js:1-13 复制实现。另 entries/quick-main.js、entries/launcher-main.js 是 Vite 多页入口，属正常。
- 影响：四个死模块 + 一个为死代码保绿的测试，让“录音/截图/反调试/ASR”四个能力在代码库里看起来仍然存在；新人改 ASR 时可能改到 asr.js 而线上实际走 voiceCall.js。
- 修复摘要：删除这 4 个模块与对应测试；若截图或录音功能仍计划上线，先建 issue 再从历史提交取回。
- 修复文件：frontend/src/composables/useVoiceRecorder.js、frontend/src/composables/__tests__/useVoiceRecorder.test.js、frontend/src/utils/captureChatShareScreenshot.js、frontend/src/utils/antiDebug.js、frontend/src/api/asr.js
- 建议测试：npm --prefix frontend test

### MNT-BEAD5 · [中] — 脚本目录自己写了一条规矩“根目录只放常用脚本，一次性的放 diag/ 或 ops/”，但根目录实际堆了 94 个条目、23 个云部署脚本，还有几对只差一个数字的重复脚本；想知道“部署到底该跑哪个”只能逐个点开看。
- 简述：脚本目录自己写了一条规矩“根目录只放常用脚本，一次性的放 diag/ 或 ops/”，但根目录实际堆了 94 个条目、23 个云部署脚本，还有几对只差一个数字的重复脚本；想知道“部署到底该跑哪个”只能逐个点开看。
- 详细问题：scripts/ 根目录膨胀且违反自身 README 约定，存在成对重复脚本
- 专家 / section：maintainability / cleanup
- 位置：`scripts/README.md:18`
- 置信度：high · kind：plan
- 证据：scripts/README.md:18 `新增一次性排障脚本请放入 diag/ 或 ops/，避免堆在根目录。`；实际 `ls scripts | wc -l` = 94 个条目、132 个跟踪文件，根目录 23 个 `_cloud_*.py` 共 1779 行；成对重复：scripts/diag/_diag_community_feed.py 与 _diag_community_feed2.py、scripts/diag/_fix_gateway_connect.py / _fix_gateway_network.py / _fix_gateway_order.py、scripts/gen_pet_fixed_voices.py 与 scripts/diag/_gen_pet_fixed_voices.py、scripts/_repair_flyway_v34.py 与 scripts/ops/_repair_flyway_v34_mark_applied.py。
- 影响：部署与排障入口不可枚举：94 个根目录条目里混着只用过一次的迁移脚本，`_fix_gateway_*` 三个变体哪个是最终版无从判断；新人容易跑错脚本或重复实现已有的云部署逻辑。
- 修复摘要：按 README 约定把一次性脚本移入 diag/ops（或直接删除已完成的），合并重复变体，并在 CI 里限制根目录条目数。
- 修复文件：scripts/README.md、scripts/_cloud_deploy_pull.py、scripts/diag/_diag_community_feed.py、scripts/diag/_fix_gateway_order.py、scripts/gen_pet_fixed_voices.py、scripts/_repair_flyway_v34.py
- 建议测试：python scripts/check_release_asset_contract.py

### MNT-0CAA3 · [中] [u12] — backend/scripts 里留着五个月前的五个 PowerShell 迁移脚本，路径写死在作者本机的 C:\Users\hp\... 上，其中一个的注释直接写“用来回滚另一个脚本”；同目录还有两份写死版本号的 Flyway 修复 SQL 和一个 0 字节的 vault 导出文件。别人跑这些脚本只会改错目录或改到已迁完的代码。
- 简述：backend/scripts 里留着五个月前的五个 PowerShell 迁移脚本，路径写死在作者本机的 C:\Users\hp\... 上，其中一个的注释直接写“用来回滚另一个脚本”；同目录还有两份写死版本号的 Flyway 修复 SQL 和一个 0 字节的 vault 导出文件。别人跑这些脚本只会改错目录或改到已迁完的代码。
- 详细问题：backend/scripts 残留一次性迁移脚本：硬编码个人绝对路径 + 0 字节占位文件
- 专家 / section：maintainability / cleanup
- 位置：`backend/scripts/restore-service-imports.ps1:3`
- 置信度：high · kind：plan
- 证据：5 个 ps1 硬编码 `c:\Users\hp\Desktop\LianYu-PC\...`：dedupe-java-imports.ps1:1、fix-service-cross-imports.ps1:2、repackage-service.ps1:3-4、restore-service-imports.ps1:3、strip-utf8-bom.ps1:1；restore-service-imports.ps1:1 注释 `# Restore java.* / third-party imports stripped by fix-service-cross-imports.ps1`，即这两份互为“执行/回滚”的一次性工具；repair-flyway-v10.sql 与 repair-flyway-v14-checksum.sql 内写死 `WHERE version = '14'` / `checksum = -724640786`；`_vault_export.tsv` 为 0 字节但仍 `git ls-files` 可见。全仓除 .logistics 的 scope 快照外，无任何文档或脚本引用这些文件。
- 影响：目录名 scripts 让人以为可以照跑：实际路径指向作者机器、SQL 绑定已过去的迁移版本、0 字节 tsv 看起来像凭据导出；误跑会改错文件或写入错误的 flyway_schema_history。
- 修复摘要：删除已执行完的一次性脚本与空占位文件，只保留仍在用的 _upload_backend_image.py；如需留档则移入 docs/refactor-2026-06/ 并注明执行日期与结果。
- 修复文件：backend/scripts/restore-service-imports.ps1、backend/scripts/fix-service-cross-imports.ps1、backend/scripts/repackage-service.ps1、backend/scripts/repair-flyway-v14-checksum.sql、backend/scripts/_vault_export.tsv
- 建议测试：cd backend && mvn -q test

### MNT-2AC9C · [中] [u09] — Electron 主进程还留着一套“从 localStorage 解密登录 token”的注入脚本，但渲染层早已改成只把 token 放内存、不再写那两个键；这段代码现在每次开窗都会执行一遍、必然返回空，注释却还声称“与 secureToken.js 保持一致”，会误导后面的人以为存在 localStorage 回退。
- 简述：Electron 主进程还留着一套“从 localStorage 解密登录 token”的注入脚本，但渲染层早已改成只把 token 放内存、不再写那两个键；这段代码现在每次开窗都会执行一遍、必然返回空，注释却还声称“与 secureToken.js 保持一致”，会误导后面的人以为存在 localStorage 回退。
- 详细问题：rendererTokenScript 解密的是已被移除的 localStorage token（恒返回 null）
- 专家 / section：maintainability / cleanup
- 位置：`frontend/electron/rendererTokenScript.js:[REDACTED:secret]
- 置信度：high · kind：plan
- 证据：rendererTokenScript.js:[REDACTED:secret] 注释 `在渲染进程 executeJavaScript 中解密 secureToken 存储的 _ltt` / `逻辑与 src/utils/secureToken.js 保持一致`，脚本体读 `localStorage.getItem('_ltt')` 与 `_lkt` 做 AES-GCM 解密；而 src/utils/secureToken.js:[REDACTED:secret] 注释 `Token 内存态存储（#14：移除 localStorage 加密剧场）`，:9 `token 仅驻留渲染进程内存（rawToken），不再落 localStorage`；全仓 grep `localStorage.setItem` 于 _ltt/_lkt 为 0 命中。消费点：electron/main.js:82 import，:991-1000 pullRendererAuthToken 内 `executeJavaScript(RENDERER_AUTH_TOKEN_SCRIPT, true)`，:1019 在 ensureAuthSessionFromRenderer 的循环里调用；另 main.js:963 的 RENDERER_DESKTOP_STATE_SCRIPT 也用 `localStorage.getItem('_ltt')` 判定 loggedIn。
- 影响：每次 ensureAuthSessionFromRenderer 都会对每个候选窗口执行一次必然失败的 executeJavaScript，且注释与真实实现相反：维护者会以为删掉 secureToken.js 的 localStorage 分支是 bug，或继续在 token 路径上叠加兼容代码。
- 修复摘要：删除 rendererTokenScript.js 与 pullRendererAuthToken 分支，认证恢复统一走主进程 safeStorage；loggedIn 判定改用 'lianyu-user-profile'。
- 修复文件：frontend/electron/rendererTokenScript.js、frontend/electron/main.js、frontend/src/utils/secureToken.js
- 建议测试：npm --prefix frontend test

### MNT-10A82 · [低] [u10] — 管理后台的 package.json 里装了表格库、图表库、路由和一套图标，但后台一共只有十个源文件的壳子，这四样东西一次都没被 import 过；每次装依赖都要多下一批包，也给升级维护增加无谓的负担。
- 简述：管理后台的 package.json 里装了表格库、图表库、路由和一套图标，但后台一共只有十个源文件的壳子，这四样东西一次都没被 import 过；每次装依赖都要多下一批包，也给升级维护增加无谓的负担。
- 详细问题：admin 声明了 4 个从未被引用的依赖
- 专家 / section：maintainability / cleanup
- 位置：`admin/package.json:10`
- 置信度：high · kind：plan
- 证据：admin/package.json:10 单行 dependencies 含 `@element-plus/icons-vue`、`@tanstack/vue-table`、`echarts`、`vue-router`（以及实际在用的 axios/element-plus/pinia/vue）；在 admin/src 与 admin/electron 内 grep 这四个包名命中数为 0。admin 前端总共 10 个 src 文件（App.vue 16 行、pages/ManagementPage.vue 16 行、components/DataTable.vue 6 行、menu.js 9 行、api.js 10 行、stores/session.js 3 行等）。
- 影响：多装 4 个未使用的包（含 echarts 这类体积较大的库），安装/审计/升级都要为它们付出成本；也让人误以为后台已有图表与路由能力。
- 修复摘要：从 admin/package.json 移除这四个依赖并更新 lockfile；若后台确实要做图表/路由，另开任务再接。
- 修复文件：admin/package.json、admin/package-lock.json
- 建议测试：npm --prefix admin test

### PRF-71D14 · [高] [u01] — 群聊里同时有 8 个会话在回复时，所有回复会永久停住，用户只能重启应用或等服务重启。
- 简述：群聊里同时有 8 个会话在回复时，所有回复会永久停住，用户只能重启应用或等服务重启。
- 详细问题：群聊编排任务与内层生成共用同一个 8 线程池，外层 join 阻塞导致自饥饿死锁
- 专家 / section：perf / runtime
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/conversation/GroupChatService.java:370`
- 置信度：high · kind：plan
- 证据：第 370 行 `CompletableFuture.runAsync(() -> {` 把整轮编排提交到 groupChatExecutor；第 394-399 行对每个成员 `CompletableFuture.supplyAsync(() -> generateCharacterReply(...), taskExecutor)` 又提交到同一个池；第 401-407 行 `while (!pending.isEmpty()) { CompletableFuture.anyOf(pending.toArray(EMPTY_CF_ARRAY)).join();` 在池线程里阻塞等待。池配置见 backend/lianyu-service/src/main/java/com/lianyu/service/config/GroupChatExecutorConfig.java:12-23：`@Value("${lianyu.group.executor.core-pool-size:8}")`、`max-pool-size:20`、`queue-capacity:300`，未设置拒绝策略。ThreadPoolExecutor 只有线程数 < core 或队列已满时才新建线程；队列 300 远未填满，因此 8 个并发群聊回合就能把 8 个 core 线程全部占在外层 join 上，内层生成任务永远排在队列里。
- 影响：群聊功能在 8 个并发回合后整体挂死：外层不返回、内层不执行，没有超时兜底，只能重启进程恢复；4-7 个并发时内层生成被压到剩余线程上串行执行，回复延迟成倍上升且多角色顺序错乱；被卡住的线程还占着未收尾的 SSE 请求。
- 修复摘要：把「回合编排」和「单角色生成」拆成两个线程池（或改为非阻塞聚合），禁止池线程阻塞等待同池任务。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/conversation/GroupChatService.java、backend/lianyu-service/src/main/java/com/lianyu/service/config/GroupChatExecutorConfig.java
- 建议测试：新增并发用例：同时发起 8 个群聊回合，断言 90s 内全部完成（当前实现会永久挂起）。；压测时观察 groupChatExecutor 活跃线程数不再恒等于 core，队列长度在高峰后回落。

### PRF-9FA45 · [中] [u05] — 服务端每 30 秒为所有单聊会话各算一次「最后一条消息时间」，会话越多越慢，最终拖慢聊天接口和数据库。
- 简述：服务端每 30 秒为所有单聊会话各算一次「最后一条消息时间」，会话越多越慢，最终拖慢聊天接口和数据库。
- 详细问题：主动搭话扫描每 30 秒执行一次带相关子查询的 ORDER BY，代价随会话总数线性增长
- 专家 / section：perf / data-access
- 位置：`backend/lianyu-dao/src/main/java/com/lianyu/dao/mapper/ConversationMapper.java:20`
- 置信度：medium · kind：plan
- 证据：SQL 为 `SELECT c.id, c.user_id, c.character_id, c.mode, c.title, c.created_at FROM conversation c WHERE c.mode='SINGLE' AND c.character_id IS NOT NULL ORDER BY COALESCE((SELECT MAX(m.created_at) FROM message m WHERE m.conversation_id = c.id), c.created_at) DESC LIMIT #{limit}`（第 20-31 行）。调用方 backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ProactiveChatScheduler.java:62-68：`@Scheduled(fixedDelayString = "${lianyu.chat.proactive.scan-interval-ms:30000}")`，`scan-limit:40`。ORDER BY 表达式依赖外表 c.id 的相关子查询，优化器无法用索引消除排序，必须先为每条单聊会话求一次 MAX(created_at) 并整体 filesort，最后才截断到 40 行。
- 影响：每次扫描的工作量 ≈ 单聊会话数 次索引下探 + 同等行数排序，且每天固定执行 2880 次；会话数到 5 万量级时单次已是 5 万次子查询 + 5 万行文件排序，持续占用 CPU 与 buffer pool，用户侧表现为聊天/列表接口的周期性抖动。
- 修复摘要：把「最后消息时间」变成可索引的冗余列，或改成派生表聚合后再排序，避免 ORDER BY 内出现相关子查询。
- 修复文件：backend/lianyu-dao/src/main/java/com/lianyu/dao/mapper/ConversationMapper.java、backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ProactiveChatScheduler.java、backend/lianyu-dao/src/main/resources/db/migration/
- 建议测试：EXPLAIN 断言新查询 type=range/ref 且 Extra 不含 Using filesort。；在 10 万会话数据集上压测 tick() 单次耗时，目标从秒级降到 <50ms。

### PRF-454DD · [中] [u01] — 情绪衰减定时任务每次把全表角色状态读进内存再逐行回写，角色多时一次要跑很久，期间数据库连接和写锁都被长期占着。
- 简述：情绪衰减定时任务每次把全表角色状态读进内存再逐行回写，角色多时一次要跑很久，期间数据库连接和写锁都被长期占着。
- 详细问题：decayAllEmotions 无 LIMIT 全表扫描并在同一事务内逐行 updateById
- 专家 / section：perf / data-access
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/character/CharacterStateService.java:179`
- 置信度：high · kind：plan
- 证据：第 179-221 行 `public int decayAllEmotions()` 带 @Transactional：第 182-188 行 `stateMapper.selectList(...isNotNull(currentEmotion).ne(currentEmotion,"平静").and(w -> w.isNull(emotionUpdatedAt).or().le(emotionUpdatedAt, threshold)))` 没有 LIMIT；第 190-220 行 `for (CharacterState state : candidates) { ... stateMapper.updateById(state); }` 每行一次整行 UPDATE，全部在同一个事务、同一条 Hikari 连接内，循环结束才提交。调度见 EmotionDecayScheduler.java:20-29（fixedDelay 900000ms，15 分钟）。表 V23__character_state.sql:2-15 只有 `UNIQUE KEY uk_char_user (character_id,user_id)` 与 `INDEX idx_user (user_id)`，current_emotion / emotion_updated_at 均无索引，`!= '平静'` 加范围条件只能全表扫。同仓已有分页范式：RelationshipStateService.java:43 `DECAY_BATCH_LIMIT = 500` 与 :172-190 `.last("LIMIT " + DECAY_BATCH_LIMIT)`。
- 影响：单次任务耗时随 character_state 行数线性增长，超过 15 分钟间隔后任务开始堆叠；长事务占住 20 个 Hikari 连接中的 1 个并持有行锁，在线聊天写 character_state 时被阻塞；N 次单行 UPDATE 还把 binlog 与 redo 放大 N 倍。
- 修复摘要：按主键分页取一批、只更新真正变化的行，并用批量 UPDATE 替换逐行 updateById。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/character/CharacterStateService.java、backend/lianyu-service/src/main/java/com/lianyu/service/character/EmotionDecayScheduler.java、backend/lianyu-dao/src/main/resources/db/migration/
- 建议测试：单测：给定 1200 行候选，断言每次最多处理 500 行、共 3 批收敛。；压测 10 万行 character_state，断言单批 SQL 条数从 N 降到 1，事务时长 <200ms。

### PRF-5C4A1 · [中] [u05] — 每天两次为「日记」统计当天聊天量，需要扫描整张消息表，聊天记录越多越慢。
- 简述：每天两次为「日记」统计当天聊天量，需要扫描整张消息表，聊天记录越多越慢。
- 详细问题：message 表无 created_at 单独索引，日记候选查询退化为全表扫描
- 专家 / section：perf / data-access
- 位置：`backend/lianyu-dao/src/main/java/com/lianyu/dao/mapper/ConversationMapper.java:37`
- 置信度：medium · kind：plan
- 证据：SQL（第 37-51 行）：`SELECT c.id AS conversationId, ... SUM(m.role='USER') AS userMsgCount, MAX(m.created_at) AS lastActivityAt FROM conversation c JOIN message m ON m.conversation_id = c.id WHERE c.mode='SINGLE' AND c.character_id IS NOT NULL AND m.created_at >= #{since} GROUP BY c.id, ... HAVING userMsgCount > 0 ORDER BY userMsgCount DESC, lastActivityAt DESC LIMIT #{limit}`。调用方 CharacterDiaryService.java:164-168（`selectSingleConversationsActiveSince(startOfDay, scanLimit)`），调度为 :135 `@Scheduled(cron = "0 0 22 * * *")` 与 :141 `@Scheduled(cron = "0 30 23 * * *")`。message 现有索引只有 V1 的 `uk_conv_seq (conversation_id, seq)`、`idx_conv_created (conversation_id, created_at)` 与 V46 的 `idx_msg_conv_role_seq (conversation_id, role, seq)`，均以 conversation_id 为前导列，全库 migration 中不存在 created_at 单列索引。
- 影响：`m.created_at >= since` 无法单独走索引，只能按 conversation 驱动逐条下探或直接全表扫 message（项目里最大的表，content 为 MEDIUMTEXT）；每天 2 次把冷数据扫进 buffer pool，冲掉热聊天的缓存页，随后一段时间在线聊天 IO 命中率下降。
- 修复摘要：给 message.created_at 补一个可用的索引（或改由 conversation.last_message_at 过滤），让按时间窗口的统计不再全表扫。
- 修复文件：backend/lianyu-dao/src/main/resources/db/migration/、backend/lianyu-dao/src/main/java/com/lianyu/dao/mapper/ConversationMapper.java、backend/lianyu-service/src/main/java/com/lianyu/service/character/CharacterDiaryService.java
- 建议测试：EXPLAIN 断言新查询对 message 走 range 而非 ALL。；在千万级 message 数据集上对比改动前后单次 generateDiaries 耗时。

### PRF-E4A17 · [中] [u01] — 打开会话列表时，服务端把每个会话最后一条消息的完整正文都取回来，只为显示 120 字预览，白传了大量文本。
- 简述：打开会话列表时，服务端把每个会话最后一条消息的完整正文都取回来，只为显示 120 字预览，白传了大量文本。
- 详细问题：会话列表批量取 MEDIUMTEXT 全文，仅用于截断到 120 字符的预览
- 专家 / section：perf / data-access
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java:489`
- 置信度：high · kind：plan
- 证据：list() 第 166-169 行取最多 200 个会话；第 489-517 行 buildLastMessageSnippetMap / buildLastCharacterMessageSnippetMap 批量调 MessageMapper.selectLatestByConversationIds 与 selectLatestAssistantByConversationIds；backend/lianyu-dao/src/main/java/com/lianyu/dao/mapper/MessageMapper.java:16-67 两条 SQL 都 `SELECT ... m.content ...`（MEDIUMTEXT 整列）；ConversationService.java:522-544 `formatConversationPreviewSnippet` 最终只保留 120 字符。
- 影响：一次列表请求最多取回 200 条完整正文，长回复/长文导入场景下单次可达数 MB 文本，MySQL 还需读 MEDIUMTEXT 溢出页、JDBC 逐行反序列化；用户侧表现为侧边栏会话列表打开变慢、内存抖动。
- 修复摘要：在 SQL 侧只取预览所需的长度，不要整列取回 MEDIUMTEXT。
- 修复文件：backend/lianyu-dao/src/main/java/com/lianyu/dao/mapper/MessageMapper.java、backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java
- 建议测试：单测：断言 snippet 长度 ≤200 且 120 字符预览内容与原文前 120 字符一致。；接口对比：同一账号 200 个会话下列表响应体大小与 P95 耗时。

### PRF-585C8 · [低] [u07] — 记忆检索时一条一条地向 Redis 取向量，50 条候选就是 50 次往返，时间全花在等待上。
- 简述：记忆检索时一条一条地向 Redis 取向量，50 条候选就是 50 次往返，时间全花在等待上。
- 详细问题：vector_cache 逐条串行 Redis GET/SET，N 次往返叠加在对话回合内
- 专家 / section：perf / data-access
- 位置：`backend-agent/app/memory/vector_cache.py:59`
- 置信度：high · kind：plan
- 证据：第 59-73 行 `_vectors_for(texts)`：`for i, text in enumerate(texts): vector = await cache_get_json(_text_key(text))`，每条一次 Redis GET；缺失项再 `for slot, idx in enumerate(missing_idx): await cache_put_json(...)` 逐条 SET。rank_by_vector（:77-109）先 `embed([query])` 再 `_vectors_for(texts)`，cosine（:31-44）为纯 Python 点积 O(dim=1024)×N。N 上限来自 backend-agent/app/config.py 的 `memory_vector_max_candidates=50`；backend-agent/app/db.py 只提供单键 cache_get_json/cache_put_json，没有 MGET/pipeline 封装。调用链：app/tools/memory_search_tool.py 的 search_semantic。
- 影响：每次语义记忆检索在对话回合内串行叠加最多 50 次 RTT（跨可用区 1-2ms 时约 50-100ms），缓存未命中时再叠 50 次 SET；这段延迟直接落在用户等待回复的首字时间上，而 CPU 侧几乎没有开销。
- 修复摘要：用 MGET/pipeline 把逐条读写改成一次往返，并把 cosine 计算向量化。
- 修复文件：backend-agent/app/memory/vector_cache.py、backend-agent/app/db.py
- 建议测试：单测：50 条候选时断言 Redis 命令次数为 2（1 次 MGET + 1 次 MSET），而不是 100。；基准：rank_by_vector 在 50 候选下的 P95 耗时目标 <20ms。

### PRF-9225F · [高] [u06] — 桌宠和快捷聊天窗口为了几个按钮，启动时要解析 1.1MB 的脚本（压缩后 361KB），窗口打开明显变慢、内存占用变高。
- 简述：桌宠和快捷聊天窗口为了几个按钮，启动时要解析 1.1MB 的脚本（压缩后 361KB），窗口打开明显变慢、内存占用变高。
- 详细问题：element-plus 全量注册把 1118 KB 的 launcherBootstrap chunk 拖进桌宠/快捷聊入口
- 专家 / section：perf / frontend
- 位置：`frontend/src/main.js:3`
- 置信度：high · kind：plan
- 证据：实测 frontend/dist（与源码同批构建）：`launcherBootstrap-CEXfa8Qx.js` 1118.0 KB raw / 360.9 KB gzip，文件内含 element-plus 标识 10 次、ElMessage 5、ElScrollbar 4、el-button 3、el-dialog 1；`main-DTgPoq__.css` 398.7 KB raw / 54.3 KB gzip 为 element-plus 全量样式；dist/launcher.html 引用的 `launcher-BE_O1lcz.js` 自身仅 706 字节，但第 1 行即 `import{m,p as u,q as l,g as d,t as s}from"./launcherBootstrap-CEXfa8Qx.js"`；dist/quick.html 的 `quick-DUTbY0TJ.js`（1224 字节）同样 import launcherBootstrap 等 8 个模块。根因在 frontend/src/main.js:3-4 `import ElementPlus from 'element-plus'` + `import 'element-plus/dist/index.css'` 与 :69 `app.use(ElementPlus, { size: 'default' })` 全量注册；frontend/vite.config.js:67-101 未做按需拆分（`modulePreload:false`、manualChunks 只分 vue-vendor，第 92 行注释明确说不能把 element-plus 拆成跨 chunk 引用）。
- 影响：桌宠窗口与快捷聊天窗口每次冷启动都要解析/编译 1.1MB JS 与 398KB CSS（gzip 后 415KB 需解压），而它们只用到几个 el-* 组件；Electron 在启动后 4s/10s/12s 预热这两个窗口（frontend/electron/main.js:1496-1513），三个窗口同时解压同一份大 chunk，低配机首屏可感知卡顿、常驻桌宠多吃几十 MB 渲染内存。
- 修复摘要：element-plus 改按需引入，并让 launcher/quick 入口不再依赖含全量 UI 库的共享 chunk。
- 修复文件：frontend/src/main.js、frontend/vite.config.js、frontend/src/entries/launcher-main.js、frontend/src/entries/quick-main.js
- 建议测试：构建后脚本断言：launcher.html/quick.html 的初始 import 图不含 element-plus 标识，且首屏 JS gzip <150KB。；Electron 冒烟：桌宠与快捷聊窗口从创建到 did-finish-load 的耗时对比（目标降低 40% 以上）。

### PRF-D16A1 · [中] [u06] — 聊天记录越长，打开会话和收到新消息时越卡，因为每次都要重建整条时间线并重排全部消息。
- 简述：聊天记录越长，打开会话和收到新消息时越卡，因为每次都要重建整条时间线并重排全部消息。
- 详细问题：ChatPage 无虚拟滚动，messageTimeline 全量重建 + 每次变更全量 sort
- 专家 / section：perf / frontend
- 位置：`frontend/src/pages/ChatPage.vue:650`
- 置信度：high · kind：plan
- 证据：`messageTimeline` computed（第 650-720 行）在 messages 每次变更时全量重建，对每条消息执行 `expandAssistantForDisplay`（:640-648，含 stripInnerThoughts 正则）并 push 分组标记；`mergePolledMessages`（:965-1041）发现变化后调用 `sortMessagesInTimelineOrder()` 对全量数组排序（:961-963）；模板第 73 行 `<template v-for="item in messageTimeline" :key="item._key">` 直接渲染全部条目，没有虚拟滚动或窗口化。
- 影响：n 条消息时每次增量更新都是 O(n) 重建 + O(n log n) 排序 + O(n) 的 VDOM diff 与布局；消息到几千条后（loadOlderMessages 无上限，见 P10）滚动与发送都会掉帧，流式回复结束的回填也会触发一次全量重算。
- 修复摘要：引入虚拟滚动并按 key 增量更新，避免每次变更全量重建与全量排序。
- 修复文件：frontend/src/pages/ChatPage.vue、frontend/src/composables/useChatScroll.js
- 建议测试：性能用例：注入 3000 条消息后追加 1 条，用 performance.mark 断言处理耗时 <16ms、DOM 节点数不随消息总数增长。；回归：滚动到顶触发加载更早消息后，可视位置不跳动。

### PRF-F4602 · [中] [u06] — 聊天窗口每 10 秒重新下载最近 50 条消息，即使一条新消息都没有；窗口切到后台也照旧。
- 简述：聊天窗口每 10 秒重新下载最近 50 条消息，即使一条新消息都没有；窗口切到后台也照旧。
- 详细问题：会话轮询固定拉取最近 50 条全文且无增量游标，隐藏窗口也不停止
- 专家 / section：perf / hot-path
- 位置：`frontend/src/pages/ChatPage.vue:1203`
- 置信度：high · kind：plan
- 证据：常量 MESSAGE_PAGE_SIZE=50（:484）、NORMAL_POLL_MS=10000（:589）、FAST_POLL_MS=2000（:587）；`pollCurrentConversationMessages(force)`（:1197-1231）每次 `const page = await getMessages(convId, { limit: MESSAGE_PAGE_SIZE }, { silent: true })`（:1203），请求只带 limit 没有 afterSeq/游标；`startConversationPolling()`（:1182-1187）用 10s setInterval，只在 onUnmounted 清理（:801-808），全文件无 visibilitychange 处理；`scheduleOpeningPollBurst`（:1150-1155）额外追加 600/1500/3500ms 三次；QuickChatPage.vue:363-372 同样是 10s setInterval 调 loadMessages(convId)。
- 影响：单个前台聊天窗口每分钟 6 次 × 50 条完整消息（含 MEDIUMTEXT 正文，见 P05）≈ 每分钟 300 条正文的下载与 JSON 解析；桌宠/快捷聊常驻时后台窗口也在轮询，多窗口并存时请求量与主进程解析开销成倍叠加，是弱网下消息延迟与电量消耗的主要来源。
- 修复摘要：改成增量拉取（afterSeq/游标）+ 页面不可见时暂停或降频轮询。
- 修复文件：frontend/src/pages/ChatPage.vue、frontend/src/pages/QuickChatPage.vue、frontend/src/api/conversation.js、backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java
- 建议测试：无新消息时连续 5 个轮询周期，断言响应体为 0 条且字节数 <1KB。；用例：页面隐藏后不再发起请求（mock visibilitychange），恢复可见时补拉一次。

### PRF-2EAFA · [低] [u06] — 往上翻聊天记录时消息数组只增不减，翻得越多前端内存越大、越卡。
- 简述：往上翻聊天记录时消息数组只增不减，翻得越多前端内存越大、越卡。
- 详细问题：loadOlderMessages 每页 50 条前插且无上限，messages 数组无限增长
- 专家 / section：perf / memory-startup
- 位置：`frontend/src/pages/ChatPage.vue:908`
- 置信度：high · kind：plan
- 证据：`loadOlderMessages`（第 908-949 行）以 MESSAGE_PAGE_SIZE=50 分页把更早消息 unshift 进 `messages.value`，没有总条数上限、也没有回收旧页；同一数组同时被 `messageTimeline`（:650-720）全量重建与 `sortMessagesInTimelineOrder`（:961-963）全量排序使用，切换会话时（onUnmounted/会话切换逻辑）才整体替换。
- 影响：长时间连续上翻后 messages 可达数千条，与 P08 的全量重建/排序叠加，内存与帧率持续恶化；桌面端常驻窗口不会因切页释放，属于缓慢累积的泄漏式增长。
- 修复摘要：给会话消息数组设定保留上限并配套「向上翻页」的窗口化策略。
- 修复文件：frontend/src/pages/ChatPage.vue、frontend/src/composables/useChatScroll.js
- 建议测试：用例：连续加载 20 页后断言 messages.length ≤ 上限，且顶部消息可通过 beforeSeq 重新取回。；内存快照：加载 20 页前后 JS heap 增量 <5MB。

## 转交 deferred
- security → reliability · Agent 侧 /agent/* 无速率限制（observe_limiter 只是并发信号量），额度耗尽与雪崩风险需可靠性视角复核。
- security → correctness · X-Forwarded-For 取首段与网关 $proxy_add_x_forwarded_for 追加语义不一致，属限流口径错误，建议与部署/网关一起修正。
- reliability → correctness · backend-agent/app/memory/relay.py · memory_outbox 认领后卡在 RUNNING、requeue_stuck 无定时调用。本轮已作为 C-006 报告故不重复计数；其“失败不可见”的一面已在 REL-03/REL-06 的监控建议中覆盖。
- reliability → maintainability · backend/lianyu-service/src/main/java/com/lianyu/service/ai/AiResilience.java · AiResilience / UpstreamCircuitBreakerFactory 与 application.yml 的 resilience4j 配置没有任何注入点，属死代码 + 失效配置；Python 侧已有等价熔断，不构成本轮可证的可靠性损失，建议交 maintainability 处理。
- reliability → maintainability · frontend/src/composables/useVoiceRecorder.js · onChunk 失败被 `.catch(() => {})` 吞掉；全仓只有它自己的单测引用该 composable，生产代码未使用，证据不足以证明可达。
- reliability → reliability · frontend/electron · updater/qqBridge/napcat 空 catch 扫描命中 31 处，逐处核对均为单条音频/代理探测/状态同步的可降级分支，上层有状态机与日志兜底，不构成失败不可见。
- maintainability → perf · backend-agent/app/main.py · 单文件 1278 行、25 个路由未拆 router；已作为 M03 上报，若拆分排期靠后可作为独立重构任务跟踪。
- maintainability → correctness · frontend/electron/main.js · 孤儿 IPC handler desktop:notify-window-changed：68 个 ipcMain.handle 与 66 个 preload invoke 全部匹配，仅此通道无暴露/无调用；删除属机械改动，价值低于已上报的 12 条。
- maintainability → correctness · AGENTS.md · 文档与配置漂移：AGENTS.md:139 仍写“当前最新 V55”（实际 V57）；docker-compose.yml:110,157 传的 LIANYU_AGENT_SHADOW_MODE 与 :59 的 LIANYU_MEMORY_OUTBOX_ENABLED 在 backend 无任何读取。
- maintainability → correctness · admin/src/pages/ManagementPage.vue · configs.users.columns 先定义后被整段覆盖，前一处为死赋值；admin 后台为壳子状态，等该页真正开发时一并整理。
- maintainability → perf · frontend/electron/startupProfiler.js · electron/startupProfiler.js 11 行与 src/utils/startupProfiler.js 18 行同签名重复（时钟基准不同）；合并需统一时钟语义，暂列观察。

> 这些不是当前专家的 finding，但必须写入 verdict.deferred，不能静默丢弃。

## 机器提示（仅供参考）
- 没有检测到明显的跨专家重复候选。

## 交给主编
1. 读本文件与原始 handoff；不要只依赖机器提示。
2. 合并重复项，保留最可信的证据；被合并的 ID 记入 verdict.dropped。
3. 写 `verdict.json`（schemaVersion 2、findings、coverage、dropped），再写 `report.md`。
4. `latest` 由 `verdict.mjs` 在默认五专家完整且 contract 通过后自动发布；不要手动复制。
5. 工作区根目录：`C:\Users\hp\Desktop\LianYu-PC`；运行目录：`C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261106-114636-5b02a3-master`。
