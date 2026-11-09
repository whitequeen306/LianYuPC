# 后勤主编材料 · 20261104-194640-2a6a2d

> 这是给主会话模型（主编）的候选材料，不是最终报告。
> 去重、定级、取舍、模块组织由主编决定；机器只负责收集与校验。

## 运行
- 根目录：`C:/Users/hp/Desktop/LianYu-PC`
- 范围：1100 个文件 / 12 个审查单元 / 135978 行
- 测试命令（4 个模块）：
  - `npm --prefix admin test`（package.json:admin）
  - `mvn -q -f backend/pom.xml test`（maven:backend）
  - `pytest -q backend-agent`（pytest:backend-agent）
  - `npm --prefix frontend test`（package.json:frontend）

## 覆盖
- 预期单元 / 专家：correctness、security、reliability、maintainability
- 已收到：correctness、security、reliability、maintainability
- 缺失：（无）
- 无效：（无）
- 失败：（无）
- partial：（无）
- 可选未选：perf

## 单元覆盖矩阵

| 单元 | 标签 | correctness | security | reliability | maintainability | perf |
| --- | --- | --- | --- | --- | --- | --- |
| u01 | backend/lianyu-service (1/3) | reviewed | reviewed | reviewed | reviewed | missing |
| u02 | backend/lianyu-service (2/3) | sampled | sampled | reviewed | sampled | missing |
| u03 | frontend/src (1/2) | sampled | sampled | reviewed | sampled | missing |
| u04 | scripts (1/2) | sampled | sampled | reviewed | reviewed | missing |
| u05 | backend/lianyu-dao | reviewed | reviewed | sampled | reviewed | missing |
| u06 | backend-agent | reviewed | reviewed | reviewed | reviewed | missing |
| u07 | frontend/src (2/2) | sampled | sampled | sampled | sampled | missing |
| u08 | backend/lianyu-web | reviewed | reviewed | reviewed | sampled | missing |
| u09 | frontend/electron | sampled | reviewed | sampled | reviewed | missing |
| u10 | 其他模块（(root)、.github、admin 等） | sampled | reviewed | reviewed | reviewed | missing |
| u11 | backend（小模块合集 59 文件） | sampled | reviewed | sampled | sampled | missing |
| u12 | 其他模块（backend/lianyu-app、backend/lianyu-common、backend 等） | sampled | reviewed | sampled | sampled | missing |

## 专家摘要
### correctness · ok
- 摘要：全项目 12 个审查单元已逐单元检查（u01/u05/u06/u08 读关键文件做针对性核对，其余单元按模式扫描 + 抽样精读），共 5 条发现：1 条高（前端内心独白过滤在嵌套括号下重复渲染并吞掉台词，已用 node 实跑复现）、3 条中/低并发正确性（朋友圈锁无 owner token、会话摘要读改写无锁、关系状态整行覆盖无事务/版本）、1 条低数据完整性（消息 seq 计数器无 MAX(seq) 回种）。跨端契约（SSE 帧、关系 DTO 字段、分段规则、更新器签名、发布资产命名）逐项对齐检查未发现漂移；frontend/mvn 门禁在本机全绿，pytest 因缺 dev 依赖 respx 无法采集，非代码缺陷。
- 专家报告：`C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261104-194640-2a6a2d/handoff/correctness.md`（11326 字符）
- sections：logic=1 · state=0 · concurrency=3 · data-integrity=1 · contracts=0 · architecture=0 · edge-cases=0
- notes：测试：npm --prefix frontend test 全绿（71 files / 417 tests）；npm --prefix admin test 退出码 0；mvn -q -f backend/pom.xml test 退出码 0；pytest -q backend-agent 在采集阶段即失败（缺 respx），故 Python 侧只有代码级结论、无测试佐证。抽样说明：u02/u03/u04/u07/u09/u10/u11/u12 为模式扫描 + 抽样精读（filesRead 见 units），u10 的 .github/ci.yml、installer/*.cs、Pets/ 与 u12 的 installer 未逐行读，不能视作全量已读。已查未发现问题：SSE 双端帧解析（AgentSseParser/AgentStreamBridge/frontend/shared/sseParser.js）、关系 DTO 字段与前端 utils/relationship.js、Java AssistantReplySplitter 与前端 assistantReplySplit.js、更新器 compareVersions 与 manifest 签名链、release-bump-version.mjs 三条正则（对真实文件实跑命中）、check_release_asset_contract.py、lianyu-common 的 TextUtils/JacksonListTypeHandler/ImageUploadValidator、qqBridge 消息提取与 pieces 回退、V56/V57 迁移回填与 phase 一致性。另核对 RelationshipHeuristics（:57-103）后确认「同一批次先受伤后道歉」的 REPAIR 跳过分支当前不可达（DISMISSIVE 要求 safe.length()<=2，REPAIR 最短词 3 字），故未作为缺陷报告。 各 section 判定说明：logic: 重点核对内心独白分段/删除、关系阶段推导、SSE 累积器、分段规则双端一致性；state: 关系 phase 重算、streak/lastInteractionAt、摘要 lastSummarizedSeq 状态迁移已读，状态机本身不产生错误终态（并发窗口另计 concurrency）；concurrency: Redis 锁语义、摘要读改写、关系行覆盖写、限流计数与服务端调度器；data-integrity: seq 唯一键、V56/V57 迁移回填、图片校验与重编码、导出转录；contracts: SSE content/replace/pieces/error/[DONE] 双端解析、关系 DTO 字段、Java/JS 分段规则、更新器 manifest 签名、发布资产命名；architecture: Java 侧未见新增 prompt 文案或模型调用点；msg_seq 前缀四处重复属既有债，已在 data-integrity 条目中一并说明；edge-cases: 空输入/未闭合括号/半角全角混排/嵌套括号/超长分段/版本号缺段等边界均实跑或读代码核对，边界相关缺陷统一归入 logic 条目
- 发现数：5
- 转交其他专家：5 条
  - → reliability · backend-agent 测试无法采集：48 errors，ModuleNotFoundError: No module named 'respx'。本机缺 dev 依赖，非代码缺陷；CI 已装依赖时可正常跑，建议核对 requirements-dev 是否声明 respx
  - → maintainability · tool_loop.run_tool_loop 的 nudged 恒为 False（死标志，注释称空口承诺防护）。backend-agent/app/tool_loop.py:153
  - → reliability · ProactiveChatScheduler.tick 内异常仅 log.debug 吞掉。backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ProactiveChatScheduler.java
  - → 未指定 · AiChatQuotaService 超限请求也计入当日配额（count > limit 后才抛 AI_RATE_LIMITED）。属设计取舍，未作为缺陷报告
  - → maintainability · msg_seq:/session:summary: 等 Redis key 命名散落在多个服务类。与 data-integrity 第 5 条一并处理

### security · ok
- 摘要：对 12 个审查单元、1100 个文件做了只读安全审查。最严重的问题是 backend-agent 完全无认证、而网关 /api/agent/ 又直接对外代理，且 X-User-Id 校验可省略——公网匿名即可按 userId 读取任何用户的记忆画像、关系与会话摘要，并写入伪造记忆；其次为 X-Forwarded-For 默认被信任导致限流与注册配额可绕过、生产账号口令硬编码入库、Jasypt 主密钥缺失时静默明文存储厂商 API Key、agent 出站无白名单可作内网 SSRF。Electron 主进程加固（contextIsolation/sandbox/IPC 白名单/egress 白名单/证书 pinning）、Java 侧 SQL 全参数化、上传对象键白名单与文档面收敛整体到位，未发现 SQL 注入与可直接利用的 XSS。
- 专家报告：`C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261104-194640-2a6a2d/handoff/security.md`（18995 字符）
- sections：auth=1 · authz=1 · injection=0 · browser=2 · uploads=2 · secrets=2 · crypto=1 · exposure=1 · supply-chain=2
- notes：只读审查：未修改源码、未提交、未删除文件；仅在 handoff 目录写入本文件与 security.md。环境无 rg，改用 ops_grep / ops_bash 做跨文件模式扫描。4 条测试命令未执行（审查不改代码，测试对安全结论无增益；可作为修复后的验证手段）。忽略 .logistics/ 与 docs/logistics/ 下工具产物。工作区 .env 含真实厂商 Key 但已在 .gitignore:5 忽略、未入库，故不计为发现；.env.example 全为 change-me 占位。
- 发现数：12
- 转交其他专家：4 条
  - → security · 生产环境实际配置未验证（LIANYU_MASTER_KEY / LIANYU_TRUST_FORWARDED_FOR / VITE_LIANYU_UPDATE_PUBKEY）：仓库里只能看到默认值与 .env.example 占位，而这三项决定 crypto、auth、supply-chain 三条发现在生产是否真的触发，需要在服务器 .env 与打包产物上确认。
  - → security · 依赖 CVE 未做真实扫描：本次无网络，只审阅了 frontend/admin 的 package-lock.json、backend-agent/uv.lock 与 CI 配置，没有跑 osv-scanner 与 npm audit；建议在 CI 里跑并保留报告，作为 supply-chain 的补充证据。
  - → reliability · agent 公网可达性未做运行时验证：authz 与 uploads 两条高危发现的可利用性依赖 443 → nginx → agent:8001 的真实部署链路，建议在预发环境用匿名 curl 验证一次，并把该链路纳入回归。
  - → security · admin 端 XSS 可能性未做依赖级审计：admin/src 目前无 v-html，但第三方依赖版本与后台富文本渲染未逐版本核对，需要与 browser section 的管理端 token 发现一起排期。

### reliability · ok
- 摘要：本次按单元覆盖了 1100 文件 / 12 单元，重点核查错误处理、失败传播、重试与幂等、部分失败与回归保护。最严重的两条：前端在任意网络错误（含读流中途断网）后自动重发同一条消息，而服务端在请求开始即落库且没有幂等键，一次抖动就会产生重复用户消息、双份模型生成与重复的关系/情绪累计；backend-agent 的记忆 relay 只把 PENDING 行取出来跑，崩溃后停在 RUNNING 的行没有任何自动回收或告警，只能人工调内部接口。其余问题集中在 tool_loop 捕获过窄、RabbitMQ 死信队列无人消费/广播队列无 DLX、SSE 超时或断开后不取消上游订阅、连接池与重试策略的并发细节，以及 CI 未覆盖 backend-agent 与 admin 两条门禁、流式消费重发路径完全没有测试。
- 专家报告：`C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261104-194640-2a6a2d/handoff/reliability.md`（13207 字符）
- sections：error-handling=9 · tests=2
- notes：只读审查，未修改任何源码，未运行任何测试命令（4 个门禁均为“未运行”）。工具说明：系统 PATH 里没有 rg，使用 C:/Users/hp/AppData/Local/hermes/tools/ripgrep-15.2.0-win32-x64/rg.exe 完成跨文件扫描；ops_grep 因 invalid-encoding 会跳过约 68 个文件，故模式扫描以该 rg 为准。已核对但未列为发现的地方：electron/main.js:2976-2981 与 electron/logger.js:149-154 的全局异常兜底齐全；TotpUtil.verify 失败一律 false（fail-closed）；AgentStreamBridge.abort() 的“客户端断开不算业务失败”是设计选择且有测试锁定（AgentStreamBridgeTest:162-172），未作为缺陷上报；全仓无 .skip/.only/@Disabled，无空 catch 块。
- 发现数：11
- 转交其他专家：3 条
  - → 未指定 · Electron 主进程与 QQ/微信桥的长时间运行可靠性（重连、子进程回收、更新器失败路径）：u09 仅抽样确认了全局 uncaughtException/unhandledRejection/render-process-gone 兜底已注册并落盘，未逐文件读 68 个文件中的桥接与更新器实现。
  - → 未指定 · 数据库迁移与 lianyu-dao 的失败语义（Flyway 迁移不可回滚、事务边界）：u05 为模式扫描抽样，未逐条核对 110 个 DAO/mapper 文件与 V1-V55 迁移。
  - → 未指定 · 四个门禁命令的实际执行结果：本次审查只读，未运行 npm/mvn/pytest；findings 中的测试缺口均基于源码与 CI 配置，而非失败日志。

### maintainability · ok
- 摘要：可维护性审查覆盖全部 12 个单元（1100 文件 / 135978 行）。整体结构分层清晰（Java 只做事实、prompt 归 Python、Electron 只做本机动作），但存在 11 处可维护性债务，集中在三类：一是同一事实有两份实现且无单一事实源（记忆画像块、粒子动画、QQ/微信桥协调器、主题选择器的生成器副本），二是确证死代码未清理（com.lianyu.service.rules 整套 prompt 规则抽象、CharacterSticker 死表与死 Mapper、@Deprecated 的 deleteVectors 仍在被调用），三是仓库卫生与门禁漂移（97 个 `_` 前缀一次性脚本入库其中 83 个零引用、scripts/diag 被 gitignore 却仍被跟踪、CI 只跑了 AGENTS.md 六条门禁里的两条）。全部发现均为行为保持类改动，无 critical；severity 上限 medium。
- 专家报告：`C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261104-194640-2a6a2d/handoff/maintainability.md`（12094 字符）
- sections：simplify=5 · cleanup=6
- notes：只读审查，未修改任何源码。sections 计数与 findings 逐项一致：simplify 5 条（4 重复实现 + 1 上帝模块）、cleanup 6 条（2 死代码 + 1 未用依赖 + 1 弃用路径 + 1 孤儿脚本 + 1 CI 门禁漂移），合计 11 条，未超 12 条上限。所有 severity 均在 medium 及以下（维护性类问题上限 medium），critical/high 为 0。kind 全部为 plan：死代码删除涉及产品判断与数据库迁移、依赖移除属依赖变更、CI 与拆分类改动需人决定边界，均不适合白名单内机械直修。u02/u03/u07/u08/u11/u12 为 sampled（大单元按模式扫描后抽查关键文件），已在 units.notes 说明抽样依据；无 skipped 单元。
- 发现数：11
- 转交其他专家：6 条
  - → 未指定 · frontend/src 的 478 个导出中有 105 个在模块外零引用，但自动扫描噪音过大（动态 import、模板内引用、defineExpose），未逐条坐实，故未作为 findings 上报。
  - → 未指定 · Java 类名孤儿扫描命中 140 个文件，绝大多数是 @RestController/@Scheduled/@Mapper 等注解装配的组件，按名字判断不可靠，仅保留了 CharacterStickerMapper 这一条有二次证据的。
  - → 未指定 · .env.example（48 键）与代码实际读取的环境变量存在差异，但扫描器漏掉了 yml 占位符与 Docker Compose 注入，结论不可靠，未上报。
  - → 未指定 · ChatPage.vue(2266 行) 与 GroupChatPage.vue(1500 行) 有 21 个同构窗口，核对后几乎全是 SCSS 样式块，属弱信号，未上报。
  - → 未指定 · backend-agent/app/main.py（1278 行、25 个路由）同属上帝模块，但严重度低于 electron/main.js，为避免超出 12 条上限，未单列；建议与 F5 同批处理。
  - → 未指定 · 未执行 mvn/pytest/npm 全量测试：本次为只读审查，测试命令仅作为修复验证入口列在 findings.fix.tests 中。

### perf · not-selected
- 可选专家，本次未选择（不是缺失）。

## 候选 findings
### COR-6408B · [高] 嵌套括号时内心独白分段重复渲染并吞掉台词（stripInnerThoughts 会删空整条回复）
- 专家 / section：correctness / logic
- 位置：`frontend/src/utils/innerThoughtFilter.js:146`
- 置信度：high · kind：plan
- 证据：findParenthesisRanges（:108-126）用栈配对后直接返回按 start 升序的重叠区间：`（心想（笑）才不是呢）好呀` 得到 [{start:0,end:12},{start:3,end:6}]。parseInnerThoughtSegments（:146-155）只用单指针 lastIndex 前进：外层先 push inner 并把 lastIndex 置 13，内层 start=3 不大 13 但仍 push 一条 inner，随后第 154 行 `lastIndex = range.unclosed ? text.length : end + 1` 把 lastIndex 回退成 5，循环末尾 :156-158 再 push `text.slice(5)`。本会话用 node 动态 import 实跑：parseInnerThoughtSegments('（心想（笑）才不是呢）好呀') 返回 [{inner:'（心想（笑）才不是呢）'},{inner:'（笑）'},{speech:'才不是呢）好呀'}]（同一段文字出现两次，且「才不是呢）」被当成台词输出）；stripInnerThoughts 对同一输入按 start 降序删除（:171-175）返回空串「」，另一例 '嗯（我（偷偷）看了你一眼）' 在隐藏模式下返回 '嗯'（丢掉真实台词）。
- 影响：用户开启「内心独白」时，带嵌套括号的回复会在气泡里重复显示同一段内心独白，并把括号内的动作文字泄漏成台词；隐藏模式下整条回复可能变成空白，聊天记录与导出的转录文件同样受损。现有 frontend/src/utils/__tests__/innerThoughtFilter.test.js 只覆盖单层与未闭括号，故 417 条前端用例全绿仍未拦住。
- 修复摘要：在 findParenthesisRanges 之后先做区间归一化（外层吞并内层），再让分段与删除都基于互不重叠的区间；删除改为一次性重建而不是按索引累积 slice。
- 修复文件：frontend/src/utils/innerThoughtFilter.js、frontend/src/utils/__tests__/innerThoughtFilter.test.js
- 建议测试：npm --prefix frontend test -- innerThoughtFilter；npx vitest run --no-file-parallelism src/utils/__tests__/innerThoughtFilter.test.js src/utils/__tests__/exportChatTranscript.test.js

### COR-45E50 · [中] 朋友圈评论锁 unlock 直接 DEL，TTL 过期后会删掉新持有者的锁
- 专家 / section：correctness / concurrency
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/moments/MomentsCommentOrchestrator.java:822`
- 置信度：medium · kind：plan
- 证据：tryLock（:816-820）`redisTemplate.opsForValue().setIfAbsent(LOCK_PREFIX + postId, "1", Duration.ofSeconds(90))`；unlock（:822-824）`redisTemplate.delete(LOCK_PREFIX + postId)`，锁值恒为 "1"，没有 owner token。锁内包含模型调用：processAuthorReplyJob（:271-296）在 tryLock 成功后读 post/trigger 并调用 runAuthorReply，executePeerComment（:326 起）在锁内做角色查询、prompt 渲染与模型调用，单次模型调用耗时可达数十秒，与 90s TTL 同量级。
- 影响：当一次持锁超过 90s（模型慢、下游超时重试）时锁自动过期，另一个 worker 拿到锁开始同一 post 的处理；随后第一个 worker 的 finally unlock 会把新持有者的锁键删掉，第三个 worker 因此可以同时进入，互斥彻底失效。后果是同一帖子重复生成/重复写入角色评论（幂等键只覆盖 peer 维度，不覆盖轮次），并重复消耗模型配额。
- 修复摘要：把锁改成带持有者标识的可安全释放锁：值为随机 token，解锁用 Lua 比对后删除；同时让 TTL 大于模型调用的超时上限，或在长耗时前续期。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/moments/MomentsCommentOrchestrator.java
- 建议测试：mvn -q -f backend/pom.xml test -Dtest=MomentsCommentOrchestrator*Test；mvn -q -f backend/pom.xml test

### COR-152CF · [中] 会话摘要 Redis 状态是「读 → 调模型 → 写」，并发 merge 会让 lastSummarizedSeq 回退并重复摘要
- 专家 / section：correctness / concurrency
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/conversation/SessionSummaryService.java:83`
- 置信度：medium · kind：plan
- 证据：@Async maybeMergeAsync（:49-52）转调 maybeMerge（:54-98）：`SessionSummaryState state = loadState(conversationId)`（:73，:107-118 读 Redis `session:summary:<convId>`）→ 取 pending 窗口 → `merger.merge(...)`（:83，模型调用，秒级）→ `saveState(conversationId, newState)`（:95，:160 写回）。全程没有锁、没有 CAS、没有版本号，写回的 lastSummarizedSeq 取的是本批 pending 的最大 seq（:88-94）。另 state==null 分支（:74-76）为死代码：loadState 只在 Redis 命中时返回对象，否则新建，从不返回 null。
- 影响：同一会话连续两条用户消息（或重连补发）会各自触发一次异步摘要：后启动者可能先完成并写入较大的 lastSummarizedSeq，随后先启动者用较小的值覆盖回去，导致下一轮把已摘要过的消息再摘要一次（重复摘要 + 重复模型调用），并把上下文窗口向前多推一段；反向交错时则会跳过一段未摘要的消息，摘要文本与 lastSummarizedSeq 不一致。
- 修复摘要：把摘要更新改成带版本/序列比对的条件写：写入前用 Redis WATCH/MULTI 或 Lua 比较 lastSummarizedSeq，只允许单调前进；并对同一会话合并并发触发。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/conversation/SessionSummaryService.java
- 建议测试：mvn -q -f backend/pom.xml test -Dtest=SessionSummaryService*Test；mvn -q -f backend/pom.xml test

### COR-AC3D2 · [低] 关系状态整行覆盖写且无事务/版本号，回合更新与定时衰减会互相覆盖
- 专家 / section：correctness / concurrency
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/relationship/RelationshipStateService.java:132`
- 置信度：medium · kind：plan
- 证据：recordUserTurn（:107-134）流程为 loadOrCreateState → applyEvents → applyPresence → `relationshipStateMapper.updateById(state)`（:132）→ recordMilestones；recordAssistantTurn（:136-166）同样在 :165 整行 updateById，decayAll（:172-190）在 :185 整行 updateById。类上没有 @Transactional，实体无 version 字段，updateById 按主键覆盖所有列（含 intimacy/trust/security/anticipation/streak/lastInteractionAt/phase）。applyEvents 末尾 :256-257 会用 RelationshipPhaseResolver.derive(...) 重算并写 phase，applyPresence（:260-277）维护 streak/lastInteractionAt。
- 影响：用户消息线程写入回合增量与 @Scheduled decayAll 的衰减写并发时，后提交者用自己读到的旧快照覆盖全行：刚加的 intimacy/trust 被衰减值回滚，或衰减被增量覆盖（衰减被吞），streak 也可能回退。单用户消息串行时不会触发，故定为 low；但生产上多端同时发消息、或衰减任务与服务共用同一实例时会出现可观察的分数跳变。
- 修复摘要：给关系状态加乐观锁（version 列）或改用「只更新变化列 + 条件 WHERE」的写法，并让一次回合的多次写在一个事务内完成。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/relationship/RelationshipStateService.java、backend/lianyu-dao/src/main/java/com/lianyu/dao/entity/RelationshipState.java、backend/lianyu-dao/src/main/resources/db/migration/V58__relationship_state_version.sql
- 建议测试：mvn -q -f backend/pom.xml test -Dtest=RelationshipStateService*Test；mvn -q -f backend/pom.xml test

### COR-3AB56 · [低] 消息 seq 只靠 Redis INCR 单调分发，计数器丢失后从 1 重发，会与既有 seq 撞 uk_conv_seq
- 专家 / section：correctness / data-integrity
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java:483`
- 置信度：medium · kind：plan
- 证据：reserveSeqBlock（:483-487）：`Long lastSeq = redisTemplate.opsForValue().increment(SEQ_KEY_PREFIX + conversationId, safeCount); return lastSeq != null ? lastSeq : safeCount;`，key 为 `msg_seq:<conversationId>`（:72），无 TTL、无回种逻辑；saveAssistantRepliesLimited（:712-716）用 `firstSeq = lastSeq - cleanedPieces.size() + 1` 逐条 setSeq。而 backend/lianyu-dao/src/main/resources/db/migration/V1__init_schema.sql:45-54 的 message 表含 `UNIQUE KEY uk_conv_seq (conversation_id, seq)`。MessageMapper 里已有基于 `MAX(seq)` 的 @Select（selectLatestByConversationIds 等），但从未用于回种计数器；`msg_seq:` 前缀还在 GroupChatService.java:104、VoiceCallService.java:62、CharacterService.java:60 重复定义。
- 影响：Redis 数据一旦丢失（FLUSHALL、卷被重建、AOF 未落盘即崩溃、迁移到新实例），计数器从 1 重新递增，与该会话已有的 seq 冲突，随后每次发消息落库都抛 DuplicateKeyException，该会话持续 500，直到手动删 key 或写脚本修复。当前 docker-compose 用 `--appendonly yes` + 命名卷，正常运维下不易触发，故定为 low；但没有任何自愈路径，故障后不可恢复。
- 修复摘要：INCR 前用数据库 MAX(seq) 兜底回种，或 INCR 结果小于库内最大值时重置计数器；同时把 msg_seq 前缀收敛到单一常量。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java、backend/lianyu-service/src/main/java/com/lianyu/service/conversation/GroupChatService.java、backend/lianyu-service/src/main/java/com/lianyu/service/conversation/VoiceCallService.java、backend/lianyu-service/src/main/java/com/lianyu/service/character/CharacterService.java、backend/lianyu-dao/src/main/java/com/lianyu/dao/mapper/MessageMapper.java
- 建议测试：mvn -q -f backend/pom.xml test -Dtest=ConversationService*Test；mvn -q -f backend/pom.xml test

### SEC-50919 · [严重] 公网未认证可读写任意用户记忆：/api/agent/ 直通 Python，X-User-Id 校验可省略
- 专家 / section：security / authz
- 位置：`backend-agent/app/main.py:657`
- 置信度：high · kind：plan
- 证据：deploy/api-gateway/nginx.conf:252-285 的 location /api/agent/ 只做 limit_req 与 OPTIONS 预检，随后 `set $agent_upstream http://agent:8001; rewrite ^/api(/agent/.*)$ $1 break; proxy_pass $agent_upstream;`，整份配置 auth_request / internal 均 0 命中——公网 443 可直达 agent 容器。agent 侧 25 个路由无任何认证依赖（backend-agent/app 内 add_middleware / Depends( 0 命中），唯一校验是 `if user_id_header and str(body.user_id) != user_id_header: raise HTTPException(403, "user mismatch")`（main.py:659-661）：X-User-Id 缺省即放行，body.userId 被信任。PromptAssembleBody.use_internal_data=true 时 assemble_turn_prompt 自读库（app/prompt_pipeline.py:118-165、app/data_readers.py:225-264），把该 userId 的记忆画像/关系/会话摘要拼进 systemPrompt，main.py:703 原样返回；memory/write 用 body.userId 直接 `INSERT INTO memory_meta (character_id, user_id, summary, ...)`（main.py:982-1005、app/memory/writer.py:122-181）。复现：`curl -sX POST https://<host>/api/agent/prompt/assemble -H 'Content-Type: application/json' -d '{"userId":2,"characterId":1,"scene":"SINGLE","useInternalData":true}'` 返回含该用户记忆的 systemPrompt；`curl -X POST https://<host>/api/agent/memory/write -H 'Content-Type: application/json' -d '{"userId":2,"characterId":1,"memories":[{"summary":"伪造记忆","memoryType":"FACT","importance":5}]}'` 写入他人账户。
- 影响：无需任何凭据即可按 userId 枚举读取全体用户的记忆画像、关系分与会话摘要（含用户主动告知的姓名/住址/健康等私密事实），并可向任意账户写入伪造记忆污染后续对话；同样可匿名触发 /agent/memory/relay/drain、/agent/memory/reconcile、/agent/desktop-observe 等运维与推理端点。
- 修复摘要：网关收回公开的 /api/agent/ 入口，agent 侧对全部路由强制校验内部签名（HMAC + 时间窗 + nonce），并把 X-User-Id 改为必填且必须等于 body.userId。
- 修复文件：backend-agent/app/main.py、backend-agent/app/config.py、backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentRuntimeClient.java、deploy/api-gateway/nginx.conf、.env.example
- 建议测试：backend-agent/tests/test_agent_internal_auth.py：无签名 401、时间窗过期 401、nonce 重放 401、X-User-Id 缺失 403、跨 userId 403；部署后 `curl -s -o /dev/null -w '%{http_code}' -X POST https://<host>/api/agent/prompt/assemble -d '{"userId":1}'` 期望 401/404；pytest -q backend-agent

### SEC-C1744 · [高] X-Forwarded-For 默认被信任且网关只追加，限流与注册配额可被请求头伪造绕过
- 专家 / section：security / auth
- 位置：`backend/lianyu-web/src/main/java/com/lianyu/web/util/ClientIpResolver.java:16`
- 置信度：high · kind：plan
- 证据：ClientIpResolver.java:16 `@Value("${lianyu.security.trust-forwarded-for:true}")` 默认 true，resolve()（:25-31）直接取 X-Forwarded-For 逗号前第一段；backend/lianyu-app/src/main/resources/application.yml:172 `trust-forwarded-for: ${LIANYU_TRUST_FORWARDED_FOR:true}`，application-prod.yml 未覆盖该项 → 生产默认 true。deploy/api-gateway/nginx.conf:278 用 `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for` 只做追加，客户端自带的 `X-Forwarded-For: 1.2.3.4` 会成为首段并被取用。调用点：AuthController 的 authRateLimiter.checkLoginOrRegister、register 的 `rate:register:ip:` 3 次/天、头像 20 次/天、ObserveController 每 IP 30 次/时。复现：连续 `curl -H 'X-Forwarded-For: <每次随机IP>' https://<host>/api/auth/register`，每次都被当成新 IP，不受每日 3 次配额限制。
- 影响：登录/注册防爆破、每日注册配额、头像与桌面观察配额都可被无限重置：攻击者可批量注册账号、对同一账号持续试密码（验证码仍需通过，因此是限额放大而非完全绕过），也可放大验证码等资源消耗。
- 修复摘要：默认不信任 XFF，信任时从右向左取可信代理追加的那一段，并在生产显式开启、限定代理跳数。
- 修复文件：backend/lianyu-web/src/main/java/com/lianyu/web/util/ClientIpResolver.java、backend/lianyu-app/src/main/resources/application.yml、backend/lianyu-app/src/main/resources/application-prod.yml、deploy/api-gateway/nginx.conf
- 建议测试：backend/lianyu-web 单测：带伪造 XFF 的请求 resolve() 返回真实对端地址；集成：同一 IP 第 4 次注册返回 429；mvn -q -f backend/pom.xml test

### SEC-94E01 · [高] 生产账号口令硬编码在入库脚本 scripts/_prod_verify.py
- 专家 / section：security / secrets
- 位置：`scripts/_prod_verify.py:22`
- 置信度：high · kind：plan
- 证据：scripts/_prod_verify.py:21-22 `USERNAME = "hjkl"` / `PASSWORD = "12...er"`（原文为明文口令，此处脱敏）；同文件 `BASE = "http://127.0.0.1:8080"`、`ENV_FILE = "/opt/lianyu/.env"` 与文件头注释表明这是部署后在服务器本机执行的生产验证脚本（登录、6 条对话、SSE、推送垫片、404）。该文件被 git 跟踪。
- 影响：任何能读仓库的人（含 CI、外包、历史 fork）即拿到一个可用生产账号口令：可登录生产读取该账号的对话与记忆、消耗模型额度；若口令与其他系统复用则可横向扩散，且轮换前无法从代码判断其是否仍有效。
- 修复摘要：轮换该账号口令，脚本改为从环境变量读取凭据，并评估 git 历史清理。
- 修复文件：scripts/_prod_verify.py、.env.example
- 建议测试：全仓 grep 旧口令（排除 .logistics/）无命中；未设置环境变量时脚本报错退出，不回落到内置口令

### SEC-B78A1 · [高] Jasypt 主密钥缺失时静默降级明文存储厂商 API Key
- 专家 / section：security / crypto
- 位置：`backend/lianyu-security/src/main/java/com/lianyu/security/util/JasyptUtil.java:61`
- 置信度：high · kind：plan
- 证据：JasyptUtil.java:61-73 `if (currentVersion == null || encryptors.isEmpty()) { log.warn("No Jasypt encryptor available, returning plaintext"); return plaintext; }`；:75-80 decrypt() 在 encryptors 为空时同样原样返回。主密钥来自环境变量 LIANYU_MASTER_KEY，而 .env.example:40 是空占位 `LIANYU_MASTER_KEY=`。写入路径：ApiKeyVaultService.java:74、:130 调用 jasyptUtil.encrypt(apiKey) 后落库。
- 影响：.env.example 提供了「留空也能启动」的默认路径，运维照抄后用户自填的 DashScope/OpenAI 等真实计费 Key 以明文写进 MySQL，仅有一条 warn 日志；DB 备份、只读账号或任一 SQL 读取面即可批量窃取，且明文与密文无版本前缀、事后无法区分哪些行未加密。
- 修复摘要：主密钥缺失时 fail-fast 而不是降级明文，并清理/重加密存量数据。
- 修复文件：backend/lianyu-security/src/main/java/com/lianyu/security/util/JasyptUtil.java、backend/lianyu-service/src/main/java/com/lianyu/service/ai/ApiKeyVaultService.java、.env.example、backend/lianyu-app/src/main/resources/application.yml
- 建议测试：单测：未设置 LIANYU_MASTER_KEY 时 encrypt() 抛异常；单测：正常配置下 encrypt→decrypt 往返一致且密文含版本前缀；mvn -q -f backend/pom.xml test

### SEC-A203B · [高] agent 模型出站不校验 baseUrl，配合未认证入口可作内网 SSRF 跳板
- 专家 / section：security / uploads
- 位置：`backend-agent/app/gateway.py:92`
- 置信度：high · kind：plan
- 证据：app/gateway.py:92-110 `normalize_openai_base_url(base_url)` 只做去尾斜杠、去 /v1、拼 `/v1/chat/completions`，不校验 scheme 与 host；:413-427 `_do_post` 用该 URL 直接发起请求；app/http_client.py:26-63 `new_client()` 只设超时/UA/local_address，无网段黑名单。对比 app/dashscope.py:56-78 对 TTS 有严格白名单（仅 https、host 属于 dashscope(-intl).aliyuncs.com、禁 userinfo、禁非 443）。入口 /agent/text/chat（main.py:359-365）与 /agent/chat-stream（main.py:200-206）接受 body.textBaseUrl，且按 authz 条所述无认证。复现：`curl -X POST https://<host>/api/agent/text/chat -H 'Content-Type: application/json' -d '{"userId":1,"textBaseUrl":"http://127.0.0.1:6379","textApiKey":"x","textModel":"m","messages":[{"role":"user","content":"PING"}]}'`，按响应耗时/错误差异判断内网端口是否开放。
- 影响：匿名攻击者可借 agent 容器向 127.0.0.1 与内网网段（MySQL 3306、Redis 6379、MinIO 9000、云元数据 169.254.169.254）发起带 JSON body 的 POST，用于端口探测与内网服务指纹识别；若内网存在无鉴权 HTTP 服务则可进一步操作。
- 修复摘要：agent 出站统一走带白名单的 client：只允许公网 http(s)，拒绝环回/私网/链路本地地址，并与 Java 侧 OutboundUrlValidator 语义对齐。
- 修复文件：backend-agent/app/gateway.py、backend-agent/app/http_client.py、backend-agent/app/dashscope.py
- 建议测试：backend-agent/tests/test_outbound_url.py：私网/环回/元数据地址一律拒绝；pytest -q backend-agent

### SEC-020E1 · [中] NapCat OneBot WS 与 WebUI 令牌硬编码为 lianyupc 并强制覆盖
- 专家 / section：security / secrets
- 位置：`frontend/electron/napcatRuntime/napcatConfig.js:4`
- 置信度：high · kind：plan
- 证据：napcatConfig.js:4-17 注释与常量写明「把 token 定死为 lianyupc（用户指定）」，wsToken/webuiToken 均为 'lianyupc'（:65-74）；:96-131 与 :201-205 在写 config/onebot11.json、onebot11_<uin>.json、webui.json 时强制覆盖既有 token，注释自称「必赢过它自身的『默认密码→随机安全密码』重生成」；napcatHost.js:210 把 token 拼进 `http://127.0.0.1:${webuiPort}/webui?token=${webuiToken}`。
- 影响：本机任意进程（低权限用户态程序、其他应用内嵌 webview、恶意脚本）都知道该常量：可连 127.0.0.1:3001 的 OneBot 正向 WS 读取并伪造全部 QQ 消息、调用发送接口，也可打开 6099 WebUI 改 NapCat 配置；等于取消 NapCat 自带的随机口令保护，QQ 号事实上被本机所有进程共享。
- 修复摘要：改用每安装实例随机生成的令牌并持久化到 userData，通过受控 env 注入，不再覆盖成常量。
- 修复文件：frontend/electron/napcatRuntime/napcatConfig.js、frontend/electron/napcatRuntime/napcatHost.js、frontend/electron/qqBridge/qqBridgeCoordinator.js
- 建议测试：frontend/electron/__tests__ 新增：两次启动生成的 token 不同且被写进 onebot11*.json；手工冒烟：QQ 桥可收发消息，NapCat WebUI 仅在带正确 token 时可登录

### SEC-A77C8 · [中] 更新清单签名公钥缺失时静默跳过校验，只剩同源 sha512
- 专家 / section：security / supply-chain
- 位置：`frontend/electron/updater/updater.js:86`
- 置信度：high · kind：plan
- 证据：updater.js:72 读取 secrets?.updateManifestPublicKey，:84-89 为空时 `logger.warn('updater','latest.yml signature check disabled: no updateManifestPublicKey')` 后直接 return，不抛错也不阻断更新。公钥来自打包期注入：frontend/scripts/electron-pack.mjs:240 `updateManifestPublicKey: cloudEnv.VITE_LIANYU_UPDATE_PUBKEY || ''`、electron-pack-main-only.mjs:50 同理、pack-runtime-secrets.mjs:34-44 写入 runtime-secrets.bin、frontend/electron/runtimeSecrets.js:92-114 读取。剩余唯一校验是 sha512，而 sha512 与 latest.yml 同源（updater.js verifyInstallerSha512）。
- 影响：发布流水线漏注入 VITE_LIANYU_UPDATE_PUBKEY（默认空字符串）时，任何能写 MinIO updates/ 或 MITM 更新域名的一方都能同时替换 latest.yml 与安装包，客户端仍「校验通过」并自动安装——Ed25519 分离签名这道防线在生产可能根本没生效，且无告警。
- 修复摘要：生产构建缺少公钥时直接失败；运行时空公钥时拒绝更新而不是放行。
- 修复文件：frontend/electron/updater/updater.js、frontend/scripts/electron-pack.mjs、frontend/scripts/pack-runtime-secrets.mjs、frontend/electron/runtimeSecrets.js
- 建议测试：frontend/electron/__tests__/updater.test.js：publicKey 为空时 verifyManifestOrThrow 抛错；frontend/scripts 契约测试：缺少 VITE_LIANYU_UPDATE_PUBKEY 时打包脚本非 0 退出

### SEC-4C548 · [低] 依赖审计 job 设 continue-on-error，OSV 漏洞永不阻断合并
- 专家 / section：security / supply-chain
- 位置：`.github/workflows/ci.yml:47`
- 置信度：high · kind：direct
- 证据：.github/workflows/ci.yml:41-47 的 deps-audit job 中，步骤名 'OSV-Scanner (best-effort)' 且 `continue-on-error: true`，使用 google/osv-scanner-action/osv-scanner-action@v2.0.3；workflow 无 `permissions:` 声明，actions 均按 tag（checkout@v4、setup-java@v4、setup-node@v4）而非 commit SHA 引用；.github 下无 dependabot.yml。
- 影响：新增带已知 CVE 的前端/后端依赖不会让 CI 变红，供应链漏洞只能靠人翻日志发现；tag 引用与默认 GITHUB_TOKEN 权限也放大了第三方 action 被篡改的影响面。
- 修复摘要：让 OSV 扫描成为阻断项，并补最小权限与 SHA pin。
- 修复文件：.github/workflows/ci.yml
- 建议测试：向 package.json 临时引入一个已知高危旧版依赖，CI 应失败（验证后撤销）；npm --prefix frontend test 仍全绿

### SEC-2F43E · [中] 管理端 token 存 localStorage 且 admin 无 CSP，任一 XSS 可窃取 8 小时管理员会话
- 专家 / section：security / browser
- 位置：`admin/src/api.js:3`
- 置信度：high · kind：plan
- 证据：admin/src/api.js:3 `localStorage.getItem('lianyu-admin-token')` 注入 lianyu-token 请求头；admin/src/stores/session.js:2 登录成功后 `localStorage.setItem('lianyu-admin-token', token)`；admin/index.html 无 Content-Security-Policy meta（0 命中），deploy/api-gateway/nginx.conf 也只下发 X-Frame-Options/nosniff/Referrer-Policy，无 CSP。服务端会话 admin_session 有效期 8 小时（backend/lianyu-admin/src/main/java/com/lianyu/admin/identity/AdminAuthService.java:44-51）。admin/src 当前无 v-html / dangerouslyUseHTMLString（0 命中），需依赖或后续改动引入 XSS 才可触发。
- 影响：一旦管理端出现任意 XSS（第三方依赖、公告/发布说明富文本渲染、未来新增 v-html），攻击脚本可直接读走 localStorage 中的 token，在 8 小时内以管理员身份调用 /api/admin/v1/**（含发布、发放 support grant），服务端无法区分与真实管理员。
- 修复摘要：管理端会话改为 HttpOnly+SameSite Cookie（或内存态 token + 短时刷新），并给 admin 加 CSP。
- 修复文件：admin/src/api.js、admin/src/stores/session.js、admin/index.html、backend/lianyu-admin/src/main/java/com/lianyu/admin/identity/AdminAuthService.java、deploy/api-gateway/nginx.conf
- 建议测试：手工：登录后 localStorage 不再出现 token，刷新页面仍保持登录；手工：登出后旧 token 再请求 /api/admin/v1/auth/me 返回 401；npm --prefix admin test

### SEC-E9165 · [低] 渲染端 CSP 的 script-src 含 unsafe-eval
- 专家 / section：security / browser
- 位置：`frontend/index.html:7`
- 置信度：high · kind：direct
- 证据：frontend/index.html:7 的 CSP meta：`default-src 'self'; script-src 'self' 'unsafe-eval'; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src 'self' https://fonts.gstatic.com; img-src 'self' data: blob: https:; connect-src 'self' https: wss:; media-src 'self' data: blob: file:; object-src 'none'; base-uri 'self'; frame-ancestors 'none';`。Electron 渲染进程加载的正是这份 dist 页面（SHARED_WEB_PREFS 已开 contextIsolation/sandbox/nodeIntegration:false）。
- 影响：CSP 作为 XSS 的第二道防线被削弱：任何把攻击者字符串送进 eval/new Function 的注入点（第三方 SDK、Vue 运行时编译、动态模板）都能执行；style-src 的 unsafe-inline 也让 CSS 注入可覆盖 UI。
- 修复摘要：移除 unsafe-eval（Vue 生产构建使用预编译模板，通常不需要），并逐步收敛样式内联。
- 修复文件：frontend/index.html、frontend/vite.config.js
- 建议测试：npm --prefix frontend test；npx vite build 后手工冒烟：聊天/广场/设置页无 CSP violation；静态断言脚本在含 unsafe-eval 时失败

### SEC-9885B · [低] 聊天图片上传只信客户端 Content-Type，扩展名直接取自原始文件名
- 专家 / section：security / uploads
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/storage/FileStorageService.java:284`
- 置信度：high · kind：plan
- 证据：uploadImageToPath（:283-286）`String ext = getExtension(file.getOriginalFilename())` 直接拼进 object key，contentType 由 normalizeImageContentType（:582-595）优先取客户端 header；validateChatImage（:566-580）只校验 <=5MB 与 contentType 属于 {image/jpeg,image/png,image/webp,image/gif}，无 magic byte 或解码校验。缓解：PublicFileController.java:40-44 用 isDangerousPublicContentType（FileStorageService.java:547-557）对 text/html、xhtml、image/svg、*/javascript 返回 404，且响应带 X-Content-Type-Options: nosniff；网关未直通 MinIO bucket。
- 影响：任何二进制只要声明为 image/png 即可落进 chat-images/ 并得到可公开访问的 image/* URL（扩展名可任意，如 .svg/.html）；目前因内容类型过滤与 nosniff 不能直接执行，一旦将来接 CDN 或放宽类型映射就变成存储型内容伪装与存储滥用（5MB × 无限次）。
- 修复摘要：上传时用图像解码器验证并重编码（与头像同一路径），扩展名由内容类型决定而非文件名。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/storage/FileStorageService.java、backend/lianyu-web/src/main/java/com/lianyu/web/controller/ChatController.java
- 建议测试：单测：把 HTML 字节声明为 image/png 上传应 400；单测：正常 png/jpg/webp/gif 上传成功且 object key 扩展名正确；mvn -q -f backend/pom.xml test

### SEC-CE03E · [低] 聊天语音与图片的公共读 URL 无鉴权、无过期，仅靠 UUID 保密
- 专家 / section：security / exposure
- 位置：`backend/lianyu-web/src/main/java/com/lianyu/web/controller/PublicFileController.java:62`
- 置信度：high · kind：plan
- 证据：/api/public/files/** 在 SaTokenConfig 白名单内完全匿名；FileStorageService.java:50-52 的 SAFE_OBJECT_KEY 覆盖 chat-voice/{petId}/、custom-voices/{userId}/、chat-images/、avatars/；对象名为 UUID（不可猜）但无 TTL、无签名；响应头 `Access-Control-Allow-Origin: *`（PublicFileController.java:62）与长 Cache-Control 允许任意站点 JS 读取。
- 影响：语音/图片 URL 一旦出现在日志、客服工单、分享或截图中，任何持有者（含任意网站脚本）都能长期读取并转存；撤销只能依赖调用方主动删除对象，浏览器/MinIO 缓存期内仍可访问。对私密语音（自定义音色、角色语音）而言是长期暴露面。
- 修复摘要：对私密媒体改为短期签名 URL 或经鉴权的代理读取，缓存策略区分公开与私有。
- 修复文件：backend/lianyu-web/src/main/java/com/lianyu/web/controller/PublicFileController.java、backend/lianyu-service/src/main/java/com/lianyu/service/storage/FileStorageService.java
- 建议测试：单测：不带 sign 或过期 sign 的 chat-voice URL 返回 403；单测：updates/latest.yml 仍匿名可读；mvn -q -f backend/pom.xml test

### REL-E4D38 · [高] 前端网络错误自动重发同一请求，服务端无幂等键：一次抖动即重复落库与双份生成
- 专家 / section：reliability / error-handling
- 位置：`frontend/src/pages/ChatPage.vue:1439`
- 置信度：high · kind：plan
- 证据：ChatPage.vue:1437-1442 在 isNetworkError(err) 时 sleep(800) 后再次 `await attemptStream()`；attemptStream 内部发 POST /conversation/{id}/messages/stream（frontend/src/api/conversation.js:67-83，body 只有 provider/model/content/imageUrl，没有 clientMsgId 或 Idempotency-Key）。读流中途断网抛出的 TypeError 同样落进这个分支（frontend/src/utils/assistantStreamDrain.js:26-27 的 reader.read() 直接上抛）。服务端 ConversationService.java:328-331 在请求一开始就 `long userSeq = getNextSeq(conversationId); ... messageMapper.insert(turn.userMsg());`，随后 recordUserTurn / characterStateService.afterUserMessage 也一起执行，全程没有按内容或客户端 id 去重。
- 影响：用户网络抖动一次就会产生两条相同的用户消息、两次完整模型生成（双倍 token 与时长）、两条助手回复，关系分与情绪状态被重复累计；对已经流出半截的回复，重发后前端只用第二次的结果渲染，库里的第一条用户消息与第一次生成仍然留着，历史看着像用户自问自答。
- 修复摘要：给发送链路加客户端幂等键并在服务端做去重，网络错误的重发才是安全的。
- 修复文件：frontend/src/pages/ChatPage.vue、frontend/src/pages/QuickChatPage.vue、frontend/src/api/conversation.js、backend/lianyu-service/src/main/java/com/lianyu/service/dto/SendMessageRequest.java、backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java、backend/lianyu-dao/src/main/resources/db/migration/
- 建议测试：前端单测：handleSend 在网络错误路径上复用同一 clientMsgId；Java 集成/单测：同 clientMsgId 第二次请求不再 insert 且不触发模型调用；回归：现有 ConversationController 发送用例保持通过

### REL-BC96A · [高] 记忆 relay 只捞 PENDING：进程崩溃后停在 RUNNING 的行永久卡死且无告警
- 专家 / section：reliability / error-handling
- 位置：`backend-agent/app/memory/relay.py:208`
- 置信度：high · kind：plan
- 证据：relay.py 的 _claim 用 `UPDATE ... SET status='RUNNING' WHERE id=? AND status='PENDING'` 抢占，drain_once 只取 `status='PENDING' AND next_attempt_at<=NOW()`；后台循环 _relay_loop（app/main.py:54-65）只调 drain_once，异常仅 logger.warning，从不回捞 RUNNING。仓库里唯一把 RUNNING 改回可重试状态的是人工接口 POST /agent/memory/relay/requeue → requeue_stuck（relay.py:208，app/main.py:967-972），全仓没有任何定时器/指标/告警引用它。
- 影响：backend-agent 被 kill、OOM 或滚动重启时，正在处理的那条 memory_outbox 永远停在 RUNNING：该轮对话的记忆不再落库，后续 reconcile 也补不回来，而日志里没有任何异常（进程是被外部杀掉的），运维完全看不到。对一个“陪伴靠记忆”的产品，这类静默丢记忆会直接表现为角色“忘事”，且只能靠人工调内部接口恢复。
- 修复摘要：把 RUNNING 的回收做成后台自动行为，并把长期卡死暴露成指标与日志。
- 修复文件：backend-agent/app/memory/relay.py、backend-agent/app/main.py、backend-agent/tests/
- 建议测试：pytest：requeue_stuck 超时回收用例（RUNNING 超时 → PENDING + attempts+1）；pytest：未超时的 RUNNING 不被误抢

### REL-4F286 · [中] 工具循环只捕获 httpx.HTTPError/RuntimeError，单个工具的入参异常会打崩整轮回复
- 专家 / section：reliability / error-handling
- 位置：`backend-agent/app/tool_loop.py:99`
- 置信度：high · kind：plan
- 证据：execute_tool_calls 的 try 只 `except (httpx.HTTPError, RuntimeError) as exc`（backend-agent/app/tool_loop.py:99-104），把错误文本回灌给模型。工具实现里常见的 ValueError/KeyError/TypeError（参数解析失败、字段缺失、类型不符）不在捕获范围，会直接冲出 run_tool_loop；stream_chat_events 只捕 GatewayRejectedError 与 GatewayError（backend-agent/app/stream_chat.py），因此这类异常一路走到 app/main.py event_source 的兜底 `yield sse_error("生成失败，请重试")`。
- 影响：模型给某个工具传错参数时，本来应该“工具报错 → 模型自我纠正”，实际会把整轮已经流出的内容作废、直接给用户一句通用失败；前端已经把前半段渲染出来的气泡也会被错误帧覆盖，属于可避免的用户可见故障。
- 修复摘要：把工具执行边界统一成“任何异常都变成工具结果文本”，只有取消/连接归零才上抛。
- 修复文件：backend-agent/app/tool_loop.py、backend-agent/app/stream_chat.py、backend-agent/tests/
- 建议测试：pytest：工具抛 ValueError / KeyError 时整轮不中断；pytest：CancelledError 仍会向上传播

### REL-A6B06 · [中] 死信队列无人消费、广播队列没有 DLX：后台任务失败后既不重试也无人看见
- 专家 / section：reliability / error-handling
- 位置：`backend/lianyu-web/src/main/java/com/lianyu/web/config/RabbitMqConfig.java:86`
- 置信度：high · kind：plan
- 证据：RabbitMqConfig.java:56-67、71-82 给 memory.summary / ai.background 声明了 x-dead-letter-exchange 与 *.dlq 队列，但全仓 rg 没有任何 @RabbitListener 消费 memory.summary.dlq / ai.background.dlq（只有声明与绑定），也没有 admin 接口或告警去读它们。event.broadcast.queue（第 84-87 行）连 x-dead-letter-* 参数都没有，而容器统一 defaultRequeueRejected(false)（第 130、146 行）。NotificationPushConsumer.java:20-25 直接调 webPushService.sendToUser，WebPushService.sendOne 对每个订阅的失败只 log.warn（WebPushService.java:111-121，含瞬时网络错误同样只 warn 后 return）。
- 影响：推送任务一失败就被丢弃（无 DLX、无重投、无入库），用户收不到通知且没有任何补偿路径；memory.summary / ai.background 的失败只堆在一个没人消费、没人监控的队列里，队列长度再涨也不会有人知道，等价于“静默失败 + 无重放入口”。
- 修复摘要：给所有业务队列补齐死信出口，并给死信一个可观测、可重放的归宿。
- 修复文件：backend/lianyu-web/src/main/java/com/lianyu/web/config/RabbitMqConfig.java、backend/lianyu-service/src/main/java/com/lianyu/service/notification/NotificationPushConsumer.java、backend/lianyu-service/src/main/java/com/lianyu/service/notification/WebPushService.java、backend/lianyu-service/src/main/java/com/lianyu/service/memory/MemoryConsumer.java
- 建议测试：Java 单测：容器 RejectAndDontRequeue 后消息出现在 DLQ（Testcontainers/嵌入式 broker 或 mock 通道）；Java 单测：DLQ 消费者落库失败时自身不吞异常

### REL-12BFB · [中] SSE 超时或客户端断开后不取消上游订阅，模型在后台继续生成到结束
- 专家 / section：reliability / error-handling
- 位置：`backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentStreamAdapter.java:92`
- 置信度：medium · kind：plan
- 证据：`client.streamChat(...).subscribe(relay::accept, error -> {...relay.fail(...)}, relay::finish)`（AgentStreamAdapter.java:89-98）丢弃了返回的 Disposable；全仓 backend/**/*.java 对 emitter 没有 onTimeout/onCompletion/onError 注册，也没有任何 dispose()（rg 零命中）。SseEmitter 的超时是 SSE_TIMEOUT_MS = 1_800_000L（第 25 行）。客户端断开后 relay.abort() 只会把 closed 置 true（AgentStreamBridge.java:126-140），随后 accept 立刻 return false（第 65-67 行），但 Reactor 订阅并没有因此取消，仍在把 Python 的整条流读完。
- 影响：用户关窗或 SSE 超时后，Python 侧仍为该用户跑完整回合（最长 30 分钟），白烧 token 并长期占用 LaneLimiter 名额；并发高时会把闸门挤满，其他用户直接收到 GatewayRejectedError（“对方模型暂时繁忙”），属于一个用户的操作放大成全站故障。
- 修复摘要：把 SseEmitter 生命周期与上游订阅绑定，断开/超时即 dispose。
- 修复文件：backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentStreamAdapter.java、backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentStreamBridge.java、backend/lianyu-web/src/test/java/com/lianyu/web/agent/AgentStreamBridgeTest.java
- 建议测试：Java 单测：onTimeout 触发后 Flux 收到 cancel；Java 单测：正常 finish 时不重复 dispose

### REL-950FD · [中] backend-agent 的 pool() 无锁惰性创建连接池，并发冷启动可能建两个池并泄漏一个
- 专家 / section：reliability / error-handling
- 位置：`backend-agent/app/db.py:42`
- 置信度：high · kind：direct
- 证据：`async def pool()` 先检查模块级缓存再创建（backend-agent/app/db.py:42-61），没有 asyncio.Lock 或创建中标记；同一事件循环里两个协程可以在首个 await（aiomysql.create_pool）期间交错，各自建出一个池，后赋值的覆盖先赋值的。相比之下 http_client.shared_client 至少做了按循环分键。
- 影响：服务冷启动或池被关闭后重建时，会多出一份 MySQL 连接（每实例按池上限倍数泄漏），长期运行可能顶到 max_connections 导致后续请求全部连接失败；泄漏的池没有任何日志，排查时看不到来源。
- 修复摘要：用 asyncio.Lock 把池的首次创建串行化，并确保创建失败不留半成品。
- 修复文件：backend-agent/app/db.py
- 建议测试：pytest：并发 10 个 pool() 调用 create_pool 只被调用 1 次；pytest：close_db 后再 pool() 能重建且旧池被 close

### REL-1511E · [中] call_with_transient_retry 立即重试、无退避无 jitter，且读超时不重试
- 专家 / section：reliability / error-handling
- 位置：`backend-agent/app/resilience.py:302`
- 置信度：high · kind：direct
- 证据：backend-agent/app/resilience.py:302-325 的 call_with_transient_retry 是 for 循环内 try/except，捕获瞬时失败后立刻进入下一次尝试（没有任何 asyncio.sleep/退避/jitter），max_attempts=2；is_transient_failure 只认 DNS/连接被拒/连接超时，读超时不视为瞬时失败。
- 影响：agent 滚动更新那 1-3 秒不可用窗口内，两次尝试几乎同毫秒发出、必然双双失败，用户直接看到连接失败，重试形同虚设；没有 jitter 时多实例会在同一时刻一起重连，把刚起来的对端再压垮，形成重连风暴。读超时不重试则让“对端慢但活着”这种最常见故障直接失败。
- 修复摘要：给重试加指数退避 + 抖动，并把读超时纳入可重试集合（流式请求保留“首字后不重试”的保护）。
- 修复文件：backend-agent/app/resilience.py、backend-agent/app/gateway.py、backend-agent/app/config.py
- 建议测试：pytest：瞬时失败重试前有退避且 jitter 在范围内；pytest：emitted_any=True 时不重试（现有语义不回退）

### REL-5CEF0 · [低] 部署自检用 grep 判定，无匹配即 exit 1，把成功部署报成失败
- 专家 / section：reliability / error-handling
- 位置：`scripts/_cloud_deploy_pull.py:145`
- 置信度：high · kind：direct
- 证据：run() 在远端退出码非 0 时 `raise SystemExit(code)`（scripts/_cloud_deploy_pull.py:39-43）。第 145 行执行 `docker logs lianyu-backend 2>&1 | grep -E 'all ports wired|ports missing' | tail -2`：日志里没有这两句时 grep 退出码为 1，整个脚本在此中止，而这行发生在第 130-143 行健康检查已通过、容器已经起来之后。紧接着第 149-158 行的能力自检则只 print 警告（同一份脚本里两种口径）。
- 影响：一次已经成功的部署被报成失败：DEPLOY_DONE 不打印，ship-release.ps1 第 2 段抛错，Electron/Release 段不再执行，流水线中断且没有真实故障；运维会去排查一个不存在的问题，或者干脆习惯性忽略这段失败（真正的失败也会被一起忽略）。
- 修复摘要：把这类“看日志做自检”的步骤与门禁区分开，自检不能改变退出码。
- 修复文件：scripts/_cloud_deploy_pull.py
- 建议测试：本地 dry-run：远端 grep 无匹配时脚本仍打印 DEPLOY_DONE；手工核对一次真实部署日志

### REL-1B028 · [低] 兜底异常处理只记类名与 message，不传异常对象，500 排障没有堆栈
- 专家 / section：reliability / error-handling
- 位置：`backend/lianyu-web/src/main/java/com/lianyu/web/handler/GlobalExceptionHandler.java:163`
- 置信度：high · kind：direct
- 证据：handleUnknown 里 `log.error("Unhandled exception: {} - {}", e.getClass().getSimpleName(), e.getMessage())`（GlobalExceptionHandler.java:163），SSE 分支同样只记类名（第 159 行）；没有任何一处把 e 作为最后一个参数传给 logger，因此 SLF4J 不会输出堆栈。全仓其余 handler 也普遍只记 message。
- 影响：生产上唯一能定位 500 的日志里只剩一行类名 + 消息，NullPointerException 这类异常的真正抛出点完全丢失；客户端拿到的是笼统的 INTERNAL_ERROR，复现与定位只能靠猜或本地重放。
- 修复摘要：兜底分支把异常对象交给 logger，保留堆栈；对外响应体保持不含堆栈。
- 修复文件：backend/lianyu-web/src/main/java/com/lianyu/web/handler/GlobalExceptionHandler.java
- 建议测试：Java 单测：触发未捕获异常时响应体为 INTERNAL_ERROR 且不含堆栈字符串

### REL-A3AFA · [中] CI 只跑 Java 与前端，backend-agent 与 admin 两条门禁完全不在自动化里，前端还跑在已知会 flake 的并行模式
- 专家 / section：reliability / tests
- 位置：`.github/workflows/ci.yml:9`
- 置信度：high · kind：plan
- 证据：.github/workflows/ci.yml 的 jobs 只有 backend（`mvn -B -ntp test`）、frontend（`npx vitest run`）、deps-audit（continue-on-error: true）。没有 backend-agent 的 `pytest`，也没有 admin 的 `npm test`——尽管本次审查的四个门禁里就有这两个命令，AGENTS.md §4 也把它们列为提交前必跑。frontend 任务没有带 `--no-file-parallelism`，而 AGENTS.md §4 明确写着“既有 updater 并发用例在满载并行下会偶发红，属已知 flake”。
- 影响：16514 行的 backend-agent（网关熔断、工具循环、prompt 组装、记忆 relay）和 admin 前端在任何 PR 上都没有自动化回归保护，只能靠人记得在本地跑；前端唯一那道门禁又因为已知 flake 允许长期红，红绿灯失去信号价值，真正的回归会被当成又一次 flake 忽略。
- 修复摘要：把四个门禁都搬进 CI，并让前端跑在约定的串行模式。
- 修复文件：.github/workflows/ci.yml、backend-agent/requirements.txt、admin/package.json
- 建议测试：在 PR 上验证三个 job 都能被触发并给出真实结论；故意红一次（改坏一个断言）确认 job 会失败而不是跳过

### REL-6E2FE · [中] 流式回复消费与网络重发路径没有任何测试覆盖
- 专家 / section：reliability / tests
- 位置：`frontend/src/utils/assistantStreamDrain.js:10`
- 置信度：high · kind：plan
- 证据：rg 在测试文件里搜不到 drainAssistantStream / ChatPage：frontend/src/utils/__tests__ 只有 sseParser.test.js（只测单行解析与累计器），没有针对 assistantStreamDrain 的用例；ChatPage.vue（2266 行）没有对应测试文件。后端侧 AgentStreamBridgeTest 有 clientDisconnectStopsRelayingAndCompletes（第 162-172 行）覆盖客户端断开，但没有“同一请求被重发两次”的用例。
- 影响：重发/断流这类最容易出事、又最容易被顺手改动的路径完全没有保护：把重试条件从 isNetworkError 放宽、或把 drainAssistantStream 改成吞掉中途断开，都不会有测试变红，问题只能等用户在线上看到重复消息才发现。
- 修复摘要：为流消费与重发决策补一组小而直的用例，先锁定当前语义再改行为。
- 修复文件：frontend/src/utils/__tests__/assistantStreamDrain.test.js、frontend/src/utils/assistantStreamDrain.js、frontend/src/pages/ChatPage.vue、backend/lianyu-service/src/test/
- 建议测试：新增 assistantStreamDrain 单测 4 例；新增 shouldRetrySend 单测；新增服务端重复 clientMsgId 用例

### MNT-3D40F · [中] 记忆画像块的格式有两份实现，golden 测试锁的是生产未走的那一份
- 专家 / section：maintainability / simplify
- 位置：`backend-agent/app/memory/profile_context.py:18`
- 置信度：high · kind：plan
- 证据：profile_context.py:18-23 定义 SECTION_LABELS，:39 build_profile_context 渲染 [用户画像]/[近期情绪线索]/[关系事件]/[专属仪式] 四段块；git grep 显示该模块只被 tests/test_profile_context.py 与 app/prompts/profile_context_golden.json 引用。生产路径是 data_readers.py:174-179 的 TYPE_LABELS + :182 _render_profile_block（经 :201 load_profile_block 由 :242 memory_block 消费）。两份 label 元组逐字相同，分组、去重、拼接逻辑逐行同构。
- 影响：记忆块格式的单一事实源不存在：改格式必须同时改两处，而 golden 只保护生产未走的那份，测试全绿也挡不住生产格式漂移；后来者无法判断哪份是权威。
- 修复摘要：让 data_readers._render_profile_block 复用 memory/profile_context.build_profile_context，删除重复的 TYPE_LABELS 与同构渲染循环，只保留一份 label 表。
- 修复文件：backend-agent/app/data_readers.py、backend-agent/app/memory/profile_context.py、backend-agent/tests/test_data_readers.py
- 建议测试：pytest -q backend-agent；pytest -q backend-agent/tests/test_profile_context.py backend-agent/tests/test_data_readers.py

### MNT-CAA84 · [低] 登录/注册页与落地页各有一份几乎相同的粒子 canvas 动画组件
- 专家 / section：maintainability / simplify
- 位置：`frontend/src/components/auth/AuthParticles.vue:1`
- 置信度：high · kind：plan
- 证据：AuthParticles.vue（187 行）与 landing/LandingParticles.vue（196 行）有 21 个相同的 10 行窗口，差异只在调参：Auth 多一个 MIST 色 {r:140,g:180,b:220}，粒子数 40/58/78 对 36/52/72，parallax 22/14 对 18/12。引用点分别为 LoginPage.vue:9/126、RegisterPage.vue:9/146 与 LandingPage.vue:8/225。
- 影响：canvas 尺寸、DPR、resize 监听、清理逻辑都是两份；修一处渲染缺陷要改两个文件，且极易只改一边导致两个页面观感不一致。
- 修复摘要：抽一个 <ParticleCanvas :preset="..."> 组件，把差异收敛为两份 preset 常量（颜色/数量/parallax），两个页面各传自己的 preset。
- 修复文件：frontend/src/components/auth/AuthParticles.vue、frontend/src/components/landing/LandingParticles.vue、frontend/src/pages/LoginPage.vue、frontend/src/pages/RegisterPage.vue、frontend/src/pages/LandingPage.vue
- 建议测试：npm --prefix frontend test；npx --prefix frontend vite build

### MNT-FCE78 · [中] QQ 桥与微信桥各有一套状态协调器，生命周期与告警逻辑重复
- 专家 / section：maintainability / simplify
- 位置：`frontend/electron/qqBridge/qqBridgeCoordinator.js:1`
- 置信度：high · kind：plan
- 证据：qqBridgeCoordinator.js（334 行）与 wechatBridge/wechatBridgeCoordinator.js（229 行）共享 12 个相同的 10 行窗口，涵盖 broadcast、Notification 封装、状态推送与 unwrap 模式；qq 版额外有 sendQqBridgeAlert(titleMap) 处理 kicked/disconnected/not_logged_in/restart_failed 四种告警。两者都有各自的 __tests__ 目录。
- 影响：桥接状态机有两套并行实现：修 QQ 侧的崩溃重启/断线重连不会自动修到微信侧，回归测试也要写两遍；新增第三种通道时只能再拷一份。
- 修复摘要：抽 BridgeCoordinatorBase（广播、状态推送、Notification、unwrap 都下沉），各通道只注入自己的告警文案表与协议适配器。
- 修复文件：frontend/electron/qqBridge/qqBridgeCoordinator.js、frontend/electron/wechatBridge/wechatBridgeCoordinator.js、frontend/electron/bridge/bridgeCoordinatorBase.js
- 建议测试：npm --prefix frontend test

### MNT-A9144 · [低] 一次性生成脚本把整份 Vue 组件内嵌在字符串里，与已提交产物构成第二事实源
- 专家 / section：maintainability / simplify
- 位置：`scripts/ops/_write_theme_picker.py:3`
- 置信度：high · kind：plan
- 证据：scripts/ops/_write_theme_picker.py:3 以 CONTENT = r'''<template> …''' 内嵌整份组件，:275 还在做 CONTENT.replace('g ap:', 'gap:') 这种字符串补丁；它与 frontend/src/components/ThemeColorPicker.vue（270 行）有 149 个相同的 10 行窗口。git grep _write_theme_picker 在全仓零引用。
- 影响：组件此后如果继续演进，任何人再跑一次这个脚本都会用旧副本覆盖 ThemeColorPicker.vue；而副本与产物的差异只能靠肉眼比对，分量还依赖脆弱的字符串替换。
- 修复摘要：删除该一次性脚本（组件已是稳定产物），或改写为只输出模板骨架、不再内嵌整份组件。
- 修复文件：scripts/ops/_write_theme_picker.py、frontend/src/components/ThemeColorPicker.vue
- 建议测试：npm --prefix frontend test

### MNT-8E5C7 · [中] Electron 主进程入口 3077 行、71 个 IPC 处理器全部挤在 main.js
- 专家 / section：maintainability / simplify
- 位置：`frontend/electron/main.js:1`
- 置信度：high · kind：plan
- 证据：frontend/electron/main.js 共 3077 行，含 71 处 ipcMain.handle/ipcMain.on；同目录已经按域拆出 updater/、qqBridge/、wechatBridge/、napcatRuntime/、starter/ 等 20+ 模块，但窗口创建、托盘、快捷键、更新检查、桥接启动、日志装配仍全部留在入口文件里。
- 影响：任何一处 IPC 变更都要在三千行文件里定位，改动面与回归面强耦合；单测只能整文件加载 main.js，无法按域验证，也拉长了启动路径的审查成本。
- 修复摘要：把 main.js 收敛为组合根，按域拆出 bootstrap/windows/tray/shortcuts/ipc/*.js，每个域自行注册自己的 IPC。
- 修复文件：frontend/electron/main.js、frontend/electron/preload.js
- 建议测试：npm --prefix frontend test

### MNT-0ADD8 · [中] com.lianyu.service.rules 整套 prompt 规则抽象无任何实现与调用方
- 专家 / section：maintainability / cleanup
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/rules/PromptRuleEngine.java:1`
- 置信度：high · kind：plan
- 证据：CONFIRMED_DEAD。目录下只有 PromptRuleContext.java、PromptRuleEngine.java、PromptRuleHook.java、PromptRuleSlot.java 4 个文件，另有空的 hooks/ 子目录（git ls-files 只列出这 4 个文件）；git grep 显示包外零引用、零测试引用。PromptRuleEngine.render(PromptRuleSlot, PromptRuleContext) 依赖注入 List<PromptRuleHook>，但全仓没有任何 PromptRuleHook 实现类。
- 影响：这套抽象正对着 AGENTS.md 的红线：Java 不许拼 prompt、prompt 只能出现在 backend-agent。留下同名 seam 会诱使后来者把规则写回 Java，而 JavaAiLogicRatchetTest 只查 prompt 文本字面量与已知 AI 类，查不到这层抽象，红线形同虚设。
- 修复摘要：整包删除（含空 hooks 目录），保留 Python 侧 prompt_pipeline 作为唯一规则入口。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/rules/PromptRuleContext.java、backend/lianyu-service/src/main/java/com/lianyu/service/rules/PromptRuleEngine.java、backend/lianyu-service/src/main/java/com/lianyu/service/rules/PromptRuleHook.java、backend/lianyu-service/src/main/java/com/lianyu/service/rules/PromptRuleSlot.java
- 建议测试：mvn -q -f backend/pom.xml test；scripts/check-java-ai-free.ps1 -Mode strict

### MNT-DA052 · [低] CharacterStickerMapper 与 CharacterSticker 实体全仓零引用，V7 建的两张表无读写方
- 专家 / section：maintainability / cleanup
- 位置：`backend/lianyu-dao/src/main/java/com/lianyu/dao/mapper/CharacterStickerMapper.java:8`
- 置信度：high · kind：plan
- 证据：CONFIRMED_DEAD。CharacterStickerMapper（@Mapper interface … extends BaseMapper<CharacterSticker>）与 entity/CharacterSticker.java 在全仓零引用——git grep CharacterSticker 只命中这两处自身定义与 import；frontend/src 与 frontend/electron 中 grep "sticker" 零命中；V7__character_sticker.sql 建的表在 dao 的 SQL/XML 中没有任何 select/insert。MyBatis 会自动注册该 mapper，但没有任何调用方。
- 影响：死表 + 死 Mapper 会让人误判“贴纸功能还在维护中”，也让数据库 schema 与代码能力脱节；每次审计都要重新确认一遍它是否真的死了。
- 修复摘要：确认产品侧无贴纸计划后删 mapper 与实体，另起迁移 drop 两张表（迁移需 ASCII-only）。
- 修复文件：backend/lianyu-dao/src/main/java/com/lianyu/dao/mapper/CharacterStickerMapper.java、backend/lianyu-dao/src/main/java/com/lianyu/dao/entity/CharacterSticker.java、backend/lianyu-dao/src/main/resources/db/migration/V7__character_sticker.sql
- 建议测试：mvn -q -f backend/pom.xml test

### MNT-46D8A · [低] admin 声明了四个从未 import 的重依赖（echarts / vue-router / vue-table / element-plus 图标）
- 专家 / section：maintainability / cleanup
- 位置：`admin/package.json:10`
- 置信度：high · kind：plan
- 证据：admin/package.json:10 的 dependencies 含 @element-plus/icons-vue ^2.3.1、@tanstack/vue-table ^8.21.3、echarts ^6.1.0、vue-router ^4.4.5。但 admin/src 一共只有 5 个文件（main.js 8 行、App.vue 与 ManagementPage.vue 各 15 行、api.js 9 行、menu.js 8 行），实际 import 只有 vue/pinia/element-plus/axios；main.js 只注册 ElementPlus 与两个 css，既没有 createRouter，也没有 echarts 与图标注册。
- 影响：四个包白占安装体积与 OSV 审计面（ci.yml 的 deps-audit 会扫 admin），也让接手者误以为后台已具备图表与路由能力，容易照着依赖去写不存在的功能。
- 修复摘要：从 admin 的 dependencies 移除四个未用包并重锁 package-lock。
- 修复文件：admin/package.json、admin/package-lock.json
- 建议测试：npm --prefix admin test

### MNT-01429 · [中] 标注有害并已 @Deprecated 的 deleteVectors 仍是唯一调用方在走的路径
- 专家 / section：maintainability / cleanup
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/memory/MemoryWriter.java:184`
- 置信度：high · kind：plan
- 证据：MemoryWriter.java:184-187 的 @Deprecated public void deleteVectors(List<String> vectorIds) 注释明确写着绕过 outbox 会留下“行已删、向量仍在”的幽灵记忆，新代码应使用 deleteVectorForMemory(Long, String)；但 CharacterService.java:214 仍在调用 memoryWriter.deleteVectors(...)。
- 影响：两套删除语义并存且没有收口：删角色时记忆向量与元数据可能不一致，后人看到 @Deprecated 也不敢动（因为唯一调用方就在隔壁），弃用形同虚设。
- 修复摘要：把 CharacterService 的删除流程迁到 deleteVectorForMemory（走 outbox），确认等价后删除 @Deprecated 方法。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/memory/MemoryWriter.java、backend/lianyu-service/src/main/java/com/lianyu/service/character/CharacterService.java
- 建议测试：mvn -q -f backend/pom.xml test

### MNT-3E52E · [中] 97 个 `_` 前缀一次性脚本入库，83 个全仓零引用；scripts/diag 被 gitignore 却仍被跟踪
- 专家 / section：maintainability / cleanup
- 位置：`scripts/diag/_diag_square_images.py:1`
- 置信度：high · kind：plan
- 证据：git ls-files 命中 97 个 `_` 前缀一次性脚本（scripts/ 根级 60、scripts/diag/ 20、scripts/ops/ 13、scripts/smoke/ 4），其中 83 个在仓库（含 local/ 与 docs/）零引用。AGENTS.md §3.1.4 明写一次性排查脚本应“带 _ 前缀且不提交”。.gitignore:106-107 整目录忽略 scripts/diag/（注释：One-off cloud incident diag/fix scripts），但该目录仍有 21 个文件被跟踪——ignore 对已跟踪文件无效。scripts/gen_pet_fixed_voices.py 的 docstring 自述是 scripts/diag/_gen_pet_fixed_voices.py 的可版本化副本，两份有 231 个相同的 10 行窗口；scripts/_repair_flyway_v31.py、scripts/_repair_flyway_v34.py、scripts/ops/_repair_flyway_v30_mark_applied.py、scripts/ops/_repair_flyway_v34_mark_applied.py 四份近似重复（25 个相同窗口），都硬编码 HOST="156.233.228.18" 并走 paramiko + mysql 直连，同样零引用。
- 影响：生产主机地址与直连运维脚本长期驻留仓库，scripts/ 已无法区分“发布流水线”与“某次事故的临时探针”，新人（和 Agent）无法判断哪些可删、哪些不能删；diag 的 gitignore 规则事实上失效，反而让后来者以为该目录不会被提交。
- 修复摘要：把 scripts/ 分成三堆：流水线依赖（转正 + 进 README 索引）、事故留档（移 docs 或删除）、重复对（只留一份），并让 gitignore 规则真正生效。
- 修复文件：scripts/diag/、scripts/ops/、scripts/gen_pet_fixed_voices.py、scripts/_repair_flyway_v31.py、scripts/_repair_flyway_v34.py、.gitignore
- 建议测试：python scripts/check_release_asset_contract.py；pytest -q backend-agent

### MNT-5B12C · [中] CI 只跑了 AGENTS.md 六条门禁里的两条，两个棘轮脚本完全不在流水线里
- 专家 / section：maintainability / cleanup
- 位置：`.github/workflows/ci.yml:39`
- 置信度：high · kind：plan
- 证据：.github/workflows/ci.yml 只有 backend 的 `mvn -B -ntp test`（:22）与 frontend 的 `npx vitest run`（:39）。AGENTS.md §4 声明的门禁里，pytest backend-agent、npm --prefix frontend test 之外的 admin 测试、npx vite build、scripts/check-java-ai-free.ps1、python scripts/check_release_asset_contract.py 全部缺席；且 :39 没有加 --no-file-parallelism，与 AGENTS.md「前端测试串行跑（既有 updater 并发用例在满载并行下会偶发红）」的口径直接冲突。
- 影响：两道棘轮（Java 零 AI 逻辑、发布资产契约）只在本地手工跑，PR 上不生效，红线随时可能被悄悄突破；Python 1222+ 测试与 admin 完全无 CI 保护；CI 的跑法还和文档不一致，已知 flake 会被当成真红并稀释信号。
- 修复摘要：补齐 Python、admin 与两个棘轮脚本的 CI job，并让前端测试按文档串行跑。
- 修复文件：.github/workflows/ci.yml、AGENTS.md
- 建议测试：pytest -q backend-agent；npm --prefix admin test；npm --prefix frontend test；mvn -q -f backend/pom.xml test

## 转交 deferred
- correctness → reliability · backend-agent 测试无法采集：48 errors，ModuleNotFoundError: No module named 'respx'。本机缺 dev 依赖，非代码缺陷；CI 已装依赖时可正常跑，建议核对 requirements-dev 是否声明 respx
- correctness → maintainability · tool_loop.run_tool_loop 的 nudged 恒为 False（死标志，注释称空口承诺防护）。backend-agent/app/tool_loop.py:153
- correctness → reliability · ProactiveChatScheduler.tick 内异常仅 log.debug 吞掉。backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ProactiveChatScheduler.java
- correctness → 未指定 · AiChatQuotaService 超限请求也计入当日配额（count > limit 后才抛 AI_RATE_LIMITED）。属设计取舍，未作为缺陷报告
- correctness → maintainability · msg_seq:/session:summary: 等 Redis key 命名散落在多个服务类。与 data-integrity 第 5 条一并处理
- security → security · 生产环境实际配置未验证（LIANYU_MASTER_KEY / LIANYU_TRUST_FORWARDED_FOR / VITE_LIANYU_UPDATE_PUBKEY）：仓库里只能看到默认值与 .env.example 占位，而这三项决定 crypto、auth、supply-chain 三条发现在生产是否真的触发，需要在服务器 .env 与打包产物上确认。
- security → security · 依赖 CVE 未做真实扫描：本次无网络，只审阅了 frontend/admin 的 package-lock.json、backend-agent/uv.lock 与 CI 配置，没有跑 osv-scanner 与 npm audit；建议在 CI 里跑并保留报告，作为 supply-chain 的补充证据。
- security → reliability · agent 公网可达性未做运行时验证：authz 与 uploads 两条高危发现的可利用性依赖 443 → nginx → agent:8001 的真实部署链路，建议在预发环境用匿名 curl 验证一次，并把该链路纳入回归。
- security → security · admin 端 XSS 可能性未做依赖级审计：admin/src 目前无 v-html，但第三方依赖版本与后台富文本渲染未逐版本核对，需要与 browser section 的管理端 token 发现一起排期。
- reliability → 未指定 · Electron 主进程与 QQ/微信桥的长时间运行可靠性（重连、子进程回收、更新器失败路径）：u09 仅抽样确认了全局 uncaughtException/unhandledRejection/render-process-gone 兜底已注册并落盘，未逐文件读 68 个文件中的桥接与更新器实现。
- reliability → 未指定 · 数据库迁移与 lianyu-dao 的失败语义（Flyway 迁移不可回滚、事务边界）：u05 为模式扫描抽样，未逐条核对 110 个 DAO/mapper 文件与 V1-V55 迁移。
- reliability → 未指定 · 四个门禁命令的实际执行结果：本次审查只读，未运行 npm/mvn/pytest；findings 中的测试缺口均基于源码与 CI 配置，而非失败日志。
- maintainability → 未指定 · frontend/src 的 478 个导出中有 105 个在模块外零引用，但自动扫描噪音过大（动态 import、模板内引用、defineExpose），未逐条坐实，故未作为 findings 上报。
- maintainability → 未指定 · Java 类名孤儿扫描命中 140 个文件，绝大多数是 @RestController/@Scheduled/@Mapper 等注解装配的组件，按名字判断不可靠，仅保留了 CharacterStickerMapper 这一条有二次证据的。
- maintainability → 未指定 · .env.example（48 键）与代码实际读取的环境变量存在差异，但扫描器漏掉了 yml 占位符与 Docker Compose 注入，结论不可靠，未上报。
- maintainability → 未指定 · ChatPage.vue(2266 行) 与 GroupChatPage.vue(1500 行) 有 21 个同构窗口，核对后几乎全是 SCSS 样式块，属弱信号，未上报。
- maintainability → 未指定 · backend-agent/app/main.py（1278 行、25 个路由）同属上帝模块，但严重度低于 electron/main.js，为避免超出 12 条上限，未单列；建议与 F5 同批处理。
- maintainability → 未指定 · 未执行 mvn/pytest/npm 全量测试：本次为只读审查，测试命令仅作为修复验证入口列在 findings.fix.tests 中。

> 这些不是当前专家的 finding，但必须写入 verdict.deferred，不能静默丢弃。

## 机器提示（仅供参考）
- 没有检测到明显的跨专家重复候选。

## 交给主编
1. 读本文件与原始 handoff；不要只依赖机器提示。
2. 合并重复项，保留最可信的证据；被合并的 ID 记入 verdict.dropped。
3. 写 `verdict.json`（schemaVersion 2、findings、coverage、dropped），再写 `report.md`。
4. 只有完整覆盖默认四模块时，才更新 `docs/logistics/latest.md` 与 `.logistics/latest.json`。
5. 工作区根目录：`C:\Users\hp\Desktop\LianYu-PC`；运行目录：`C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261104-194640-2a6a2d`。
