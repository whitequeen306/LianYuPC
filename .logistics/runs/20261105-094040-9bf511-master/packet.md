# 后勤主编材料 · 20261105-094040-9bf511-master

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
| u01 | backend/lianyu-service (1/3) | reviewed | reviewed | reviewed | sampled | missing |
| u02 | backend/lianyu-service (2/3) | reviewed | sampled | sampled | sampled | missing |
| u03 | frontend/src (1/2) | reviewed | sampled | reviewed | sampled | missing |
| u04 | scripts (1/2) | sampled | sampled | sampled | sampled | missing |
| u05 | backend/lianyu-dao | reviewed | reviewed | sampled | sampled | missing |
| u06 | backend-agent | reviewed | reviewed | reviewed | sampled | missing |
| u07 | frontend/src (2/2) | reviewed | sampled | sampled | sampled | missing |
| u08 | backend/lianyu-web | reviewed | reviewed | reviewed | reviewed | missing |
| u09 | frontend/electron | sampled | reviewed | reviewed | sampled | missing |
| u10 | 其他模块（(root)、.github、admin 等） | sampled | reviewed | reviewed | sampled | missing |
| u11 | backend（小模块合集 59 文件） | sampled | reviewed | sampled | sampled | missing |
| u12 | 其他模块（backend/lianyu-app、backend/lianyu-common、backend 等） | sampled | reviewed | sampled | sampled | missing |

## 专家摘要
### correctness · ok
- 摘要：正确性审查覆盖 12 个单元、1100 个文件（135978 行），重点核对了回合落库与 SSE 收尾、关系状态机与衰减调度、动态评论编排锁、message.seq 分配、Python 工具循环与提示词装配、前端流式重试与 i18n 四语、dao 迁移与 Mapper、admin/scripts 小模块。共 10 条发现：1 条 high（前端断流重试无幂等键导致同一句用户消息重复落库、重复生成并留下幽灵消息），9 条 medium/low（评论锁无 owner token 可被抢占、关系分数整行覆盖的 lost update、seq 计数器无 DB 播种、衰减批次被未满一天的行占满导致其余行永不衰减、断流时半截回复按正式消息落库、无工具分支丢弃 system prompt、msg_seq 常量四处重复、i18n 缺少 key 且 zh-TW 未注册、输入策略正则误杀正当内容）。SSE 帧契约、ChatTurn 请求/响应字段、分页 seq 游标、迁移版本完整性等已逐一核对，未发现契约冲突。
- 专家报告：`C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261105-094040-9bf511-master/handoff/correctness/correctness.md`（12842 字符）
- sections：logic=1 · state=1 · concurrency=2 · data-integrity=1 · contracts=1 · architecture=2 · edge-cases=2
- notes：只读审查，未修改任何源码。逐单元覆盖：u01/u02/u03/u05/u06/u07/u08 为 reviewed（读关键文件并做针对性检查），u04/u09/u10/u11/u12 为 sampled（精读代表性文件 + 文件清单与 grep 扫描），无 skipped 单元。已核对未发现问题的面：SSE 帧契约（Python 发出的 content/replace/error/DONE 与 Java AgentStreamBridge、前端 sseParser.js 三方语义一致，[DONE] 由 Java 在 pieces 之后补发；main.py 两个 chat 端点都用 try/except 包住整段 async for，异常时补 error+DONE，不存在无 error 帧断流）；ChatTurn 请求/响应字段 camelCase 两侧一致；message 分页 seq 游标（lt + desc + LIMIT n+1 + reverse + nextBeforeSeq）无重叠无空洞；Flyway 57 个迁移版本连续无重号，NOT NULL ADD COLUMN 均带默认值；nginx 两级 SSE 段均为 buffering off 且 read_timeout 与 Java 1800s 匹配；qqBridge 的 404 自愈与会话隔离、updater 的 sha512 与出站白名单逻辑自洽。测试执行情况：`pytest -q backend-agent` 在本机无法运行——收集阶段即报 26 个 ERROR，根因是 `ModuleNotFoundError: No module named 'aiomysql'`（本机未装 dev 依赖，属环境缺失而非代码缺陷）；`mvn -q -f backend/pom.xml test` 与两个 npm 套件因体量大、admin 无 node_modules 未执行。因此本报告全部结论来自静态证据与调用链推演，不依赖测试结果；若需测试背书，应先 `pip install -r backend-agent/requirements-dev.txt` 后重跑。
- 发现数：10
- 转交其他专家：5 条
  - → 未指定 · V57__relationship_trust_anomaly_repair.sql 只改 trust_score 不重算 phase：按 RelationshipPhaseResolver 对 trust=50、intimacy<=40 的推导结果仍是 FAMILIAR，与旧值一致，未构成缺陷，不报。
  - → 未指定 · Python 侧从不发送 pieces 帧（stream_chat.py 注释明确本层不产出 pieces），Java AgentSseParser 不解析 pieces 与现状一致，不算契约漂移。
  - → 未指定 · updater.js 用 x.y.z 纯数字比较版本，预发布后缀（-beta）会被解析成 NaN 而不提示更新；属边缘命名约定问题，未达上报门槛，转交 maintainability。
  - → 未指定 · TotpUtil.verify 用 String.equals 比较验证码（非恒定时间）、AdminAuthorizationService.require 每次请求两次 currentAdminId 查库：前者属安全面，后者是次要性能问题，均转交对应专家。
  - → 未指定 · frontend/electron/main.js（3078 行）与 installer 的 C# 工程只做结构扫描，IPC 参数校验、安装器回滚等未逐行核对。

### security · ok
- 摘要：按 logi-security 的 9 个关注面逐单元审查了 12 个审查单元（1100 文件 / 135978 行）。最严重的问题是 Python 运行时（backend-agent）整层没有鉴权：20+ 个 /agent/* 端点都用『有 X-User-Id 头才比对』的写法，缺头即短路，而生产 nginx 的 /api/agent/ location 直接把公网请求反代到 agent:8001 且不校验 token（critical，可跨租户写记忆 + 白用平台视觉凭据）。其次：管理端 TOTP /setup 无 step-up 且 OTP 失败不计数（2FA 可被绕过）、Jasypt 在主密钥缺失时静默回退明文落库（fail-open）、Python 出站 HTTP 完全没有 SSRF 校验（Java 侧有 OutboundUrlValidator，形成旁路）、scripts/_prod_verify.py 把生产账号口令写死在跟踪文件里。Java 侧的用户态接口经抽查未发现 IDOR：Memory/Conversation/ApiKeyVault 都按 userId 过滤，管理端 RBAC 与发布/上传权限校验齐全；注入面干净（mapper XML 中 ${} 为 0，全部 #{}；Python 只拼 %s 占位符且强制 SELECT-only）。
- 专家报告：`C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261105-094040-9bf511-master/handoff/security/security.md`（16952 字符）
- sections：auth=4 · authz=1 · injection=0 · browser=1 · uploads=1 · secrets=[REDACTED:secret] · crypto=1 · exposure=1 · supply-chain=1
- notes：方法：无 ripgrep 环境，用 grep -rn/python 做跨文件模式扫描（Sink 模式：v-html/innerHTML、Runtime.exec/ProcessBuilder/ObjectInputStream/Spel、eval/exec/pickle/os.system/yaml.load、mapper 中 ${}、actuator/swagger、密钥字面量），再按单元读关键文件。已验证为干净的面：SQL 注入（Java mapper 全 #{}、XML 中 ${} 计数 0；Python db.py 强制 SELECT-only + %s 占位符）、存储型 XSS（唯一 v-html 走 DOMPurify 白名单 ALLOWED_TAGS=strong/em/b/i/u/span/br）、头像/背景上传（magic bytes + ImageIO 解码 + 重编码 PNG + UUID 文件名）、公开文件读取（SAFE_OBJECT_KEY 白名单在 statObject/getObject 均生效，无路径穿越）、管理端 RBAC 与 admin_session 校验链、Java 出站 SSRF 校验（OutboundUrlValidator + 固定 IP）、Electron 渲染隔离（contextIsolation/nodeIntegration=false/sandbox）与外部链接白名单。限制：u02/u03/u04/u07 为抽样（见 units 备注），前端 24648 行与 120 个运维脚本未逐行通读；4 个测试命令均未执行（task 要求仅记录）。
- 发现数：11

### reliability · ok
- 摘要：本轮以「失败是否可见、是否被正确分类、是否有护栏」为主线审查了 12 个单元：Python 侧重点看 gateway/resilience/stream_chat/tool_loop/memory relay+writer，Java 侧重点看 AI 后台消费、熔断隔舱、SSE 流桥与落库回调，前端重点看聊天流与等待态，另有 CI 与测试脚本核对。共 11 条发现：2 条高危错误处理缺陷（取消/断连被计入共享熔断器；后台任务重试判定依赖异常文案，熔断文案恰好进入无人消费的 DLQ）+ 1 条高危测试缺口（CI 不跑 pytest 与 admin）；3 条中危错误处理（截断回复当成功落库、outbox 卡 RUNNING 不自愈、前端聊天流无空闲超时）；2 条中危测试缺口（vitest 允许 0 测试通过、取消/截断语义零回归）；3 条低危（工具循环异常分类过窄、记忆批量写入事务语义与注释不符、健康探测仅 debug 日志）。整体结论：SSE 帧契约与 Java 侧降级链路设计成熟，主要风险集中在「取消/中断语义」与「重试分类靠文案」两处，均已有可复现路径。
- 专家报告：`C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261105-094040-9bf511-master/handoff/reliability/reliability.md`（12889 字符）
- sections：error-handling=8 · tests=3
- notes：方法：先读 scope.json，按单元做全量模式扫描（Java 空 catch 19 处、Python bare except、JS 空 catch 12 处、`.catch(()=>{})` 30 处、跳过测试 0 处），再对 AI 链路（gateway/resilience/stream_chat/tool_loop/memory）、SSE 流桥、后台消费者、聊天前端逐文件精读。已排除的假线索：scripts/_cloud_deploy_pull.py run() 缩进正确、无 UnboundLocalError；AgentStreamBridge 的 7 处 updater 空 catch 均为清理兜底；MemoryPage/CharactersPage 的空 catch 有下游空态或 toast 兜底。
- 发现数：11
- 转交其他专家：5 条
  - → 未指定 · backend/lianyu-dao（111 文件，V7–V57 迁移，模块内零测试类）：SQL 与迁移正确性无自动化回归，建议由 correctness 专家评估，本轮未展开。
  - → 未指定 · backend/lianyu-app application.yml:71-80 声明 spring.rabbitmq.listener.simple.retry.enabled=true，但 RabbitMqConfig 手工 new 的 listener factory 未走 Boot configurer，重试拦截可能不生效；与 F2 同源，未单独成条。
  - → 未指定 · frontend/src 的 30 处 `.catch(() => {})`（qqBridge 轮询、notifications 刷新等）判定为刻意静默，未逐处验证其对用户可见性的真实影响。
  - → 未指定 · SseEmitter 的 SSE 超时（ChatTurnAdapter 构造参数 SSE_TIMEOUT_MS）与前端等待态之间的交互未实测，仅从代码推断。
  - → 未指定 · 4 条测试命令本轮未实际执行（只读审查，避免产生 target/、.pytest_cache 等构建产物）；F9/F10 的结论来自脚本与 CI 配置本身。

### maintainability · ok
- 摘要：对全部 12 个审查单元（1100 文件 / 135978 行）做了可维护性检查：12 个单元全部覆盖（u08 reviewed，其余按体量 sampled + 全量模式扫描），共 12 条发现，其中 simplify 5 条、cleanup 7 条，无 critical/high（能力上限为 medium）。最集中的三类问题是：(1) 已下线能力留下的“孤儿物”仍在仓库里冒充生效——前端无引用的 antiDebug/截图分享/ASR HTTP 模块连同误导性注释、zh-TW 整份 529 行语言包未接入 i18n、V55 agent_capability_audit 表无读写方、未接线的 EncryptedField/EncryptedStringTypeHandler 加密 seam、backend-agent 里对齐已删 Java 实现的 profile_context 及名为 matches_java 的测试；(2) 复制粘贴型重复——scripts 下 16 份 load_env、49 个自拼 paramiko 的一次性部署脚本（合计 3968 行且部分仍用 get_pty=True）、lianyu-web 8 处凭据解析 + 5 份逐字复制的健康缓存、两个大定时任务各自的候选装载/概率抽样骨架；(3) 层次与体量失控——角色广场把 4 语人设与整段 prompt 硬编码进 2602 行 Java（与“prompt 归 Python”红线冲突）、electron/main.js 3077 行承载 71 个 IPC 通道。测试基线：frontend 417 用例、mvn backend、backend-agent venv 内 pytest 全绿；admin 因未安装依赖无法运行（仅静态审查）。
- 专家报告：`C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261105-094040-9bf511-master/handoff/maintainability/maintainability.md`（12315 字符）
- sections：simplify=5 · cleanup=7
- notes：覆盖口径：scope.json 的 12 个单元全部覆盖，u08 为 reviewed（9 个文件全读 + 22 个 agent 类交叉核对），其余为 sampled 并做了跨文件模式扫描（import 图、类/方法引用、长方法、clone 检测、git ls-files 引用统计），每个单元的 filesRead 与理由见 units。sections：simplify 5 条、cleanup 7 条，合计 12 条，与 findings 逐一对应；两个 section 均做了实质检查，没有用 0 冒充已查。测试基线（本机实跑）：`npm --prefix frontend test` 71 文件 / 417 用例全绿；`mvn -q -f backend/pom.xml test` 退出码 0；`pytest -q backend-agent` 在 backend-agent/.venv/Scripts/python.exe 下全绿（系统 python 因缺依赖在收集阶段报 48 个 error，属环境问题）。`npm --prefix admin test` 无法执行：admin/node_modules 不存在，报 'vitest' 不是内部或外部命令 —— admin 单元（u10 的一部分）因此只有静态结论，已写入 deferred 边界。所有结论均为只读分析，未修改任何源码。
- 发现数：12
- 转交其他专家：5 条
  - → 未指定 · frontend/src/pages/LoginPage.vue（205 行）与 frontend/src/pages/RegisterPage.vue（258 行）行级相似度 0.74（difflib，去注释与空行）：表单校验与提交态可抽 composable，但两侧文案与 OTP 分支不同，属需要产品确认的重构，本次不单列。
  - → 未指定 · backend/lianyu-admin/src/main/java/com/lianyu/admin/identity/AdminPermissionCatalog.java 9 行零引用（`grep -rn AdminPermissionCatalog --include=*.java backend` 排除自身为空）：影响极小，建议随下次权限改动顺手删除。
  - → 未指定 · backend/scripts 下 8 个一次性迁移/修复脚本（dedupe-java-imports.ps1、fix-service-cross-imports.ps1、restore-service-imports.ps1、repackage-service.ps1、strip-utf8-bom.ps1、repair-flyway-v10.sql、repair-flyway-v14-checksum.sql、check-v10-state.sql）仅被 gitignored 的 .specstory 历史文档提到，与 scripts/ 的一次性脚本问题同类，合并处理即可。
  - → 未指定 · 性能类可疑点（如 Agent 健康探测 3 秒缓存、定时任务每轮全量扫描）不在本专家范围，转交 perf。
  - → 未指定 · AsrController 的 /api/asr/transcribe 端点在前端已无调用方，是否下线涉及对外契约，转交 correctness 判定。

### perf · not-selected
- 可选专家，本次未选择（不是缺失）。

## 候选 findings
### COR-D1203 · [高] 流式中断重试没有幂等键：同一条用户消息落库两次，第一次的半截回复还会变成幽灵消息
- 专家 / section：correctness / logic
- 位置：`frontend/src/pages/ChatPage.vue:1440`
- 置信度：high · kind：plan
- 证据：ChatPage.vue:1437-1443 在 attemptStream() 抛错且 isNetworkError(err) 为真时，`await sleep(800)` 后直接再调一次 attemptStream()；useStreamAbort.js:10 的 `if (err.name === 'TypeError') return true` 把任何 TypeError（包括流读到一半 reader.read() 抛出的）都判成网络错误。api/conversation.js:67 的 POST /conversation/{id}/messages/stream 请求体只有 content/imageUrl，全仓 grep idempotencyKey 只命中 MomentsCommentService，聊天发送无任何幂等键；后端 ConversationService.java:330 在调用模型前无条件 `messageMapper.insert(turn.userMsg())`，并由 reserveSeqBlock 分配新 seq。因此第二次请求会再插一条同样的用户消息并再跑一次模型；第一次请求的 SSE 连接被重置后，AgentStreamBridge.java:138 走 `callback.onComplete(partial, null)`，把当时已生成的部分文本按正式回复落库。
- 影响：弱网或代理抖动（哪怕只是首帧之后连接被重置）时，用户看到自己发的话出现两遍，服务端生成两次、上游计费两次；第一次的半截回复被静默写进历史，刷新后出现客户端从未完整展示过的幽灵消息，并进入下一轮上下文。
- 修复摘要：给每次发送引入客户端幂等键并在重试时复用，后端按键去重；同时把重试限定在请求尚未产生任何响应字节的阶段。
- 修复文件：frontend/src/pages/ChatPage.vue、frontend/src/composables/useStreamAbort.js、frontend/src/api/conversation.js、backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java、backend/lianyu-dao/src/main/resources/db/migration/V58__message_client_id_idempotency.sql
- 建议测试：frontend/src/pages/__tests__ 新增 retryIdempotency 用例：drain 抛 TypeError 时 POST 次数为 1；backend/lianyu-service 新增 ConversationIdempotencyTest：相同 clientMessageId 两次请求只落一条 user 消息

### COR-3CC0C · [中] 客户端断流时把未写完的半句回复按正式消息落库，与错误路径的不落库策略相反
- 专家 / section：correctness / state
- 位置：`backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentStreamBridge.java:138`
- 置信度：high · kind：plan
- 证据：AgentStreamBridge 的 accept() 在 emitter.send 抛 IOException 时于 :74-76、:83-85 调 abort()，abort() 最终执行 `callback.onComplete(partial, null)`（:138）—— error 为 null，ConversationService.onComplete 因此走正常落库分支（saveAssistantReplies）。同一条链路的失败收尾 SseChatStreamHelper.java:67-85 则传 BusinessException，让 onComplete 因 `if (error != null && !isClientStreamDisconnect(error)) return;` 直接返回不落库；而 isClientStreamDisconnect 只判 java.io.IOException。也就是说同一段未完成文本，按错误收尾不落库、按断流收尾落库，两条路径策略相反且都没有给消息打 partial 标记。
- 影响：用户关窗、切网、代理断连时，半句回复被当成完整回复存库；客户端早已丢弃该气泡，刷新后却冒出一段截断文字，并被当作已发生的事实进入下一轮上下文（关系/记忆提取也会读到它）。
- 修复摘要：统一两条收尾路径对未完成内容的策略：要么都不落库，要么落库但显式标记为已中断并让渲染端可见。
- 修复文件：backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentStreamBridge.java、backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java、backend/lianyu-service/src/main/java/com/lianyu/service/ai/SseChatStreamHelper.java
- 建议测试：backend/lianyu-web 新增 AgentStreamBridgeAbortTest：断流后半成品消息的落库状态断言；backend/lianyu-service 新增 onComplete 断流分支用例

### COR-8FD65 · [中] 动态评论锁没有持有者标识，90 秒 TTL 过期后可被抢占，unlock 又会删掉别人的锁
- 专家 / section：correctness / concurrency
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/moments/MomentsCommentOrchestrator.java:816`
- 置信度：high · kind：plan
- 证据：tryLock(postId) 在 :816-820 用 `redisTemplate.opsForValue().setIfAbsent(LOCK_PREFIX + postId, "1", Duration.ofSeconds(90))`，值恒为 "1"，没有 owner token；unlock(postId) 在 :822-824 直接 `redisTemplate.delete(LOCK_PREFIX + postId)`。锁内要做模型调用（executePeerComment :326-400），超过 90 秒锁自动过期，第二个 worker 拿到锁；第一个 worker 在 finally 里无条件 delete，把第二个 worker 的锁删掉，第三个 worker 随即又能进入。existsIdempotency(:810-815) 只能挡住已经 insert 完成的评论，两个 worker 都还在模型调用阶段时都查不到记录。
- 影响：同一条动态下同一角色会发出重复评论（并重复计费），processAuthorReplyJob(:271-296) 也会因锁被误删而重复执行作者回复任务；因为锁值没有 owner，日志里也无法判断到底是谁删的。
- 修复摘要：锁值改成每次尝试唯一的 owner token，释放时用 Lua 比较后删除，并把 TTL 与外层模型调用超时对齐（或加续期）。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/moments/MomentsCommentOrchestrator.java
- 建议测试：backend/lianyu-service 新增 MomentsLockTest：owner token 不匹配时 unlock 不删除他人锁；并发用例：同 post 两个 worker 只允许一条评论落库

### COR-493CC · [中] 关系分数全类无事务无版本号，衰减批量与用户回合都用整行 updateById，后写者覆盖先写者
- 专家 / section：correctness / concurrency
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/relationship/RelationshipStateService.java:185`
- 置信度：high · kind：plan
- 证据：该类只有 @Service/@RequiredArgsConstructor，grep 全文件 Transactional 计数为 0；RelationshipState 实体没有 @Version。decayAll 在 :177-181 一次 select 最多 500 行，随后 :184-185 逐行 applyDecay + `relationshipStateMapper.updateById(state)`（写回全部列）；recordUserTurn/recordAssistantTurn 也走 :132/:165 的 updateById，由 ConversationService.java:265/333/300/390 在模型调用前后调用。衰减侧用的是批次开始时读到的旧分数，会覆盖这段时间内用户回合刚写入的 trust/intimacy/security/anticipation 与 phase。
- 影响：用户刚聊完看到好感上涨，下一次 30 分钟衰减任务把整行写回旧值，增量凭空消失并触发 phase 重算；两个并发的用户回合（连续快发）之间也会互相吞掉分数，表现为「聊了没反应」，且没有任何异常可见。
- 修复摘要：把分数更新改成数据库侧原子增量（或加乐观锁重试），decay 只更新它自己负责的列，避免整行覆盖。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/relationship/RelationshipStateService.java、backend/lianyu-dao/src/main/java/com/lianyu/dao/mapper/RelationshipStateMapper.java、backend/lianyu-dao/src/main/resources/db/migration/V58__relationship_state_version.sql
- 建议测试：backend/lianyu-service 新增 RelationshipConcurrentUpdateTest：两线程分别 recordUserTurn 与 applyDecay，断言最终分数包含两者的增量

### COR-B2E9F · [中] message.seq 只靠 Redis INCR，计数器丢失后不会用 DB 最大值回填，复用旧 seq 撞唯一键
- 专家 / section：correctness / data-integrity
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java:485`
- 置信度：medium · kind：plan
- 证据：reserveSeqBlock(:483-486) 只有 `redisTemplate.opsForValue().increment(SEQ_KEY_PREFIX + conversationId, safeCount)`，取不到值时回落 safeCount；全仓 grep 确认没有任何地方用 `MAX(seq)` 播种该键（只有 CharacterService.java:298 与 delete/clearMessages 会删键）。message 表有 `UNIQUE KEY uk_conv_seq (conversation_id, seq)`（V1__init_schema.sql:54）。Redis 以 AOF 持久化（docker-compose.yml:271-281），但 down -v、AOF 尾部丢失或换实例后计数归零，INCR 会重新给出已用过的 seq。
- 影响：该会话下一次 insert 直接撞 uk_conv_seq 抛 DuplicateKeyException，发送接口 500；由于 seq 从 1 重新开始，同一会话会连续失败，直到计数追上历史最大值，期间没有任何自愈路径，也没有降级提示。
- 修复摘要：计数器缺失时用数据库最大值播种，并把「播种 + 分配」做成原子操作；冲突时重试一次。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java、backend/lianyu-dao/src/main/java/com/lianyu/dao/mapper/MessageMapper.java
- 建议测试：backend/lianyu-service 新增 MessageSeqAllocatorTest：Redis 为空且已有历史消息时分配不冲突

### COR-78D9B · [中] 四语契约漂移：zh-TW 语言包从未注册成为孤儿文件，en/ja 缺键后回落成中文
- 专家 / section：correctness / contracts
- 位置：`frontend/src/i18n/index.js:17`
- 置信度：high · kind：direct
- 证据：i18n/index.js:7-9 只 import zh/ja/en，:17 `messages: { zh, ja, en }`，:16 `fallbackLocale: 'zh'`；locales/zh-TW.js（约 26KB）在全仓没有任何引用（grep -rn zh-TW frontend/src 只命中它自身与 utils/dateLocale.js:4）；constants/language.js:3 `SUPPORTED_LANGUAGES = ['zh', 'ja', 'en']`，normalizeLanguage 永远返回不了 zh-TW。脚本拉平键位后比对：zh 583 / zh-TW 588（缺 7 键、多 12 键）/ en 582（缺 1 键）/ ja 575（缺 8 键）。其中 chat.scrollToBottom 只存在于 zh.js:327，而 ChatPage.vue:173、GroupChatPage.vue:131 都在用它，en/ja 会显示中文「回到底部」；同类还有 header.logoutConfirm（AppHeader.vue:155，en/ja/zh-TW 缺）、home.eyebrow（HomePage.vue:4，ja 缺）、qqBridge.binding.providerRequired（QqBridgePage.vue:733，ja/zh-TW 缺）。
- 影响：英文/日文界面出现中文按钮与确认文案（AGENTS.md §3.2 要求 zh/zh-TW/en/ja 四语齐）；繁体用户只能看简体，繁中文件成为死资产，之后每个新增 key 都会继续漏，且没有测试能拦住。
- 修复摘要：注册 zh-TW 语言与选择项、补齐缺失键，并加一条键位一致性单测把四语锁住。
- 修复文件：frontend/src/i18n/index.js、frontend/src/i18n/locales/zh-TW.js、frontend/src/i18n/locales/en.js、frontend/src/i18n/locales/ja.js、frontend/src/constants/language.js
- 建议测试：frontend/src/i18n/__tests__/localeParity.test.js：四语键集合相等、无空值

### COR-F368E · [中] 工具循环的无工具分支把 system prompt 整段丢掉，触发后角色人设与记忆静默消失
- 专家 / section：correctness / architecture
- 位置：`backend-agent/app/tool_loop.py:128`
- 置信度：high · kind：direct
- 证据：run_tool_loop 在 :128 `if not specs:` 时走 `gateway.chat_sync(system=None, user_text=_flatten(messages), ...)`，而 _flatten(:205-214) 的实现是 `for msg in messages: if role == "system": continue`——该分支把组装好的 system prompt（人设/记忆/关系/时间）全部丢弃，只剩用户原话。两个调用点 chat_turn.py:265 与 graphs/proactive_turn.py:116 都在 `if tools:` 之后，工具名单是 Python 常量 CHAT_TOOL_NAMES，与 Java 侧 AgentToolCatalog.java:42 的第二份名单完全独立（chat-turn 请求体只传 enableChatTools，不传具体工具名），任何一侧改名或注册表导入失败使 resolve_tools 过滤为空，就会静默走进这条无 system 分支。
- 影响：一旦命中，回复不再是角色口吻、记忆与关系上下文全部丢失，但 HTTP 仍 200、无告警，属于完全静默的降级；因为两份工具名单没有契约校验，这种不一致只能在用户投诉里被发现。
- 修复摘要：该分支改为把 system 作为 system 消息传下去，并给「工具全部解析失败」加显式日志与降级告警；工具名单收敛为单一来源。
- 修复文件：backend-agent/app/tool_loop.py、backend-agent/app/chat_turn.py、backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentToolCatalog.java、backend/lianyu-web/src/main/java/com/lianyu/web/agent/ChatTurnAdapter.java
- 建议测试：backend-agent/tests/test_tool_loop.py：unknown_tool_names 时 system 仍在 prompt 中；backend-agent/tests/test_config_consistency.py：CHAT_TOOL_NAMES 与 Java 名单一致

### COR-3F587 · [低] msg_seq 键名前缀与 seq 分配函数在四个服务里各自定义、各自实现
- 专家 / section：correctness / architecture
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java:72`
- 置信度：high · kind：direct
- 证据：同一个 Redis 键前缀在四处各自声明：CharacterService.java:60（MESSAGE_SEQ_KEY_PREFIX）、ConversationService.java:72（SEQ_KEY_PREFIX）、GroupChatService.java:104、VoiceCallService.java:62，全部是字面量 "msg_seq:"；分配逻辑也是两份实现——ConversationService.java:479-487 与 GroupChatService.java:1314-1322 各自写了一份 getNextSeq/reserveSeqBlock，VoiceCallService.java:722 又单独写了一次 increment。四处写的是同一个会话的同一段编号空间。
- 影响：任何一处改前缀或改增量语义，同一会话就会长出两套编号，只有在撞 uk_conv_seq 时才会暴露；这正是 AGENTS.md「单一事实来源」明令禁止的形态，也让上面那条 seq 播种修复没法一次改全。
- 修复摘要：抽一个 MessageSequenceAllocator 单点持有键前缀与分配逻辑，四个服务统一注入使用，并加一条交错分配不重复的测试。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/conversation/MessageSequenceAllocator.java、backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java、backend/lianyu-service/src/main/java/com/lianyu/service/conversation/GroupChatService.java、backend/lianyu-service/src/main/java/com/lianyu/service/conversation/VoiceCallService.java、backend/lianyu-service/src/main/java/com/lianyu/service/character/CharacterService.java
- 建议测试：backend/lianyu-service 新增 MessageSequenceAllocatorTest：多入口交错分配唯一性

### COR-CC8D6 · [中] 衰减批次被「不满一天」的行长期占满：这些行不写 last_decay_at，永远排在最前，其余行再也轮不到
- 专家 / section：correctness / edge-cases
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/relationship/RelationshipStateService.java:180`
- 置信度：high · kind：direct
- 证据：decayAll(:177-181) 用 `orderByAsc(lastDecayAt).last("LIMIT 500")` 取批（DECAY_BATCH_LIMIT=500，:43）；applyDecay 在 `days < 1` 时于 :200 `return false`，此时既不写 lastDecayAt 也不 update，于是这些行的 last_decay_at 保持 NULL 或旧值——MySQL 中 NULL 排序最前，每轮都会被优先选中、每轮都被丢弃，稳定占住 500 个名额。:174-179 的注释称「结算过的行会排到队尾，形成轮转」，但那只对通过 days≥1 的行成立，被跳过的活跃行不参与轮转。
- 影响：当活跃行数超过 500（代码注释提到线上已 936 行）时，批次会被最近活跃过的行填满，其余用户的关系衰减彻底停止：亲密不回落、期待不累积，而且没有任何错误或告警，只能通过「分数不再变化」间接发现。
- 修复摘要：取批条件加上「确实到期待结算」的过滤，让不满足条件的行不再占用批次名额。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/relationship/RelationshipStateService.java、backend/lianyu-service/src/main/java/com/lianyu/service/relationship/RelationshipDecayScheduler.java
- 建议测试：backend/lianyu-service 新增 DecayBatchRotationTest：600 行两轮轮转覆盖断言

### COR-5F138 · [中] 输入策略正则误杀正当内容：正常聊天里出现 sudo、role: system、「执行以下命令」会被直接拒绝发送
- 专家 / section：correctness / edge-cases
- 位置：`backend/lianyu-common/src/main/java/com/lianyu/common/util/UserInputSanitizer.java:60`
- 置信度：high · kind：plan
- 证据：FORCED_COMMAND_PATTERNS（:59 起）含 `\bsudo\s+`（:60）、`(?i)rm\s+-rf`、`执行(以下|如下)?命令|运行(以下|如下)?命令`；INJECTION_PATTERNS 含 `(?i)role\s*:\s*system`、`act\s+as\s+(a\s+)?(system|admin|root)`。assertPolicyAllowed(:105) 命中即抛 BusinessException(CONTENT_POLICY_VIOLATION)，没有任何豁免或分级。调用点在主聊天链路上：ConversationService.java:623（发送消息）、GroupChatService.java:349、VoiceCallService.java:145、VoiceCallDuplexSession.java:406；wrapStoredTextForModel(:141) 还会对已入库文本再判一次。
- 影响：用户分享技术经历（装了软件、跑了命令）、玩角色扮演（「你现在是系统管理员」）、或作为开发者提问时，会被判成提示词注入而发不出去，且提示语是「包含疑似强制系统/终端命令内容」，用户无法理解也无法绕过；这类误杀在陪伴场景里会直接打断对话。
- 修复摘要：把硬拒绝收敛到真正危险的少数模式，其余降级为「包裹后照常发送」，并区分聊天文本与生成描述两类入口的策略。
- 修复文件：backend/lianyu-common/src/main/java/com/lianyu/common/util/UserInputSanitizer.java、backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java
- 建议测试：backend/lianyu-common 新增 UserInputSanitizerTest：正样本放行 / 负样本拒绝的样本表驱动用例

### SEC-31F68 · [严重] backend-agent 全部端点无鉴权：X-User-Id 头可选即短路，且被公网 nginx 直接暴露
- 专家 / section：security / auth
- 位置：`backend-agent/app/main.py:988`
- 置信度：high · kind：plan
- 证据：20+ 个端点统一写成 `if user_id_header and str(body.user_id) != user_id_header: raise HTTPException(403)`（main.py:206 chat-stream、:988 memory/write、:1064 proactive-turn、:146 desktop-observe、:469/:497/:660/:732/:787/:840/:860/:915/:1062/:1143/:1177/:1211/:1245 等），不带 X-User-Id 时整个校验被短路，服务端没有任何会话/令牌校验。`deploy/api-gateway/nginx.conf:252-274` 的 `location /api/agent/` 仅 `rewrite` + `proxy_pass $agent_upstream`（http://agent:8001），既无 auth_request 也未注入/覆盖 X-User-Id，其注释（248-250 行）也承认依赖 app 侧二次校验；api-gateway 在 docker-compose 中暴露 80/443。此外 `/agent/chat-image`（main.py:740-742）在请求未带凭据时回落到 `settings.multimodal_base_url/api_key` 平台级视觉密钥。
- 影响：未认证的公网请求可以直接调用记忆写入（污染任意用户 prompt）、记忆重算/中继（触发全量向量重投影，可当 DoS）、声纹注册/删除、TTS 合成、任意 base_url 的模型调用等 20+ 端点；`/agent/chat-image` 还能白用平台视觉密钥消耗成本。这是本项目最外层的信任边界缺失。
- 修复摘要：把 agent 运行时变成只接受来自 Java 的可信调用：nginx 侧加 auth_request 或直接不再暴露 /api/agent/，Python 侧加统一的共享密钥/HMAC 中间件并把『缺头即放行』改为『缺凭据即 401』。
- 修复文件：deploy/api-gateway/nginx.conf、backend-agent/app/main.py、backend-agent/app/config.py、docker-compose.yml
- 建议测试：pytest -q backend-agent（新增 tests/test_auth_middleware.py）；curl -s -o /dev/null -w '%{http_code}' https://<gateway>/api/agent/memory/relay/drain -X POST 期望 401；python scripts/check_release_asset_contract.py

### SEC-E682A · [高] 管理端 2FA 可被绕过：/totp/setup 无 step-up 可静默重置，OTP 失败不计数可爆破
- 专家 / section：security / auth
- 位置：`backend/lianyu-admin/src/main/java/com/lianyu/admin/identity/AdminTotpController.java:19`
- 置信度：high · kind：plan
- 证据：AdminTotpController.java:19-26 的 `@PostMapping("/setup")` 只用 `authorization.currentAdminId()`，不校验密码或当前 OTP，直接 `UPDATE admin_user SET totp_secret=[REDACTED:secret], totp_enabled=FALSE WHERE id=?`（jasypt.encrypt(secret)）；而同文件 :37-44 的 `/disable` 却要求 password 或当前 OTP，形成明显反差。AdminAuthService.java:30-51 的登录只在密码错误时 `failed_attempts+1`（:38），OTP 校验失败（:43）既不计数也不触发锁定/独立限流；TotpUtil.java:13-18 接受 counter-1..counter+1（±30s，合计 90s 窗口）且无 last-used 重放防护。
- 影响：拿到一个有效 admin 会话（令牌泄露、共享电脑未退出）即可静默把 2FA 换成自己的验证器并保持 totp_enabled=false，实现持久账号接管，同时把合法管理员锁在 2FA 外；已知口令后还能以无限次尝试爆破 6 位 TOTP。管理端拥有发布桌面安装包的权限（ReleaseService/release.manage），因此该缺口可传导为供应链投毒。
- 修复摘要：为 /totp/setup 增加与 /disable 同级的 step-up（当前口令或当前 OTP），并把 OTP 失败纳入锁定计数、拒绝重放。
- 修复文件：backend/lianyu-admin/src/main/java/com/lianyu/admin/identity/AdminTotpController.java、backend/lianyu-admin/src/main/java/com/lianyu/admin/identity/AdminAuthService.java、backend/lianyu-admin/src/main/java/com/lianyu/admin/identity/TotpUtil.java
- 建议测试：mvn -q -f backend/pom.xml test -Dtest=AdminTotp*Test；mvn -q -f backend/pom.xml test

### SEC-C279B · [中] 客户端 IP 取自 X-Forwarded-For 最左值，可伪造绕过 per-IP 登录限流
- 专家 / section：security / auth
- 位置：`backend/lianyu-web/src/main/java/com/lianyu/web/util/ClientIpResolver.java:24`
- 置信度：high · kind：direct
- 证据：ClientIpResolver.java:24-29 取 `X-Forwarded-For` 的第一个（最左）值作为客户端 IP；`backend/lianyu-app/src/main/resources/application.yml:172` 的 `lianyu.security.trust-forwarded-for` 默认 `${LIANYU_TRUST_FORWARDED_FOR:true}`；`deploy/api-gateway/nginx.conf:52` 用 `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for` 追加而非覆盖，最左值仍是客户端自己填的。AuthController.java:78 的登录限流与 AuthRateLimiter.java:26-35（IP 30/min、用户名 10/min）都依赖这个 IP。
- 影响：攻击者对每个请求换一个伪造的 XFF 值即可让 per-IP 30/min 失效，把撞库/密码喷洒速率放大到仅受 per-username 10/min 约束，配合 CaptchaService 的 OCR 可解算式验证码，撞库成本显著降低。
- 修复摘要：在可信代理边界上取 IP：优先用 nginx 覆盖写入的 X-Real-IP（$remote_addr），XFF 只在 trust-forwarded-for=true 且从左往右跳过可信代理后取第一个不可信地址。
- 修复文件：backend/lianyu-web/src/main/java/com/lianyu/web/util/ClientIpResolver.java、deploy/api-gateway/nginx.conf、backend/lianyu-app/src/main/resources/application.yml
- 建议测试：mvn -q -f backend/pom.xml test -Dtest=ClientIpResolver*Test；mvn -q -f backend/pom.xml test

### SEC-00767 · [中] 改密不吊销已签发 token，被盗会话在 30 天内继续有效
- 专家 / section：security / auth
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/auth/impl/AuthServiceImpl.java:152`
- 置信度：high · kind：direct
- 证据：AuthServiceImpl.java:152-162 的 changePassword 只做 `userMapper.updateById(user)`（写新 BCrypt 口令），既不调用 StpUtil.logout 也不失效其它会话；`backend/lianyu-app/src/main/resources/application.yml:133-146` 的 sa-token 配置为 `timeout: 2592000`（30 天）、`active-timeout: -1`、`is-concurrent: true`、`is-share: false`、`is-read-cookie: false`，token 存在 Redis 且与口令无关。
- 影响：账号被盗后『改密码』这个最常用的自救动作对攻击者无效：旧 token 仍可读写该用户的对话、记忆、API Key 配置，最长 30 天；用户会误以为自己已经踢掉了对方。
- 修复摘要：改密成功后立即踢掉该用户的全部 sa-token 会话，并让当前请求返回需要重新登录的语义。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/auth/impl/AuthServiceImpl.java、backend/lianyu-web/src/main/java/com/lianyu/web/controller/AuthController.java
- 建议测试：mvn -q -f backend/pom.xml test -Dtest=AuthServiceImplTest；mvn -q -f backend/pom.xml test

### SEC-E49FD · [高] 记忆写入端点信任请求体里的 userId/characterId，缺对象级归属校验
- 专家 / section：security / authz
- 位置：`backend-agent/app/memory/writer.py:122`
- 置信度：high · kind：plan
- 证据：main.py:982-1007 的 `/agent/memory/write` 把 `body.user_id` / `body.character_id` 原样传给 writer.write_memories（:1003）；writer.py:122 的 `write_memories(user_id, character_id, memories)` 直接进入 `_insert_new(...)`（:145-149）落 memory_meta，未校验 character 是否属于该 user、也不校验调用者身份（X-User-Id 由客户端自带，nginx 不覆盖）。/agent/memory/extract-write（main.py:912）同构。Java 正常链路是在进程内直连 agent 并把会话 userId 放进 body，但同一端点又被 nginx 的 /api/agent/ 暴露给公网。
- 影响：任意人可向任意 (userId, characterId) 注入任意条『记忆』（summary/importance 可控），这些内容之后会被拼进受害者的人设 prompt —— 相当于第三方可以给别人的 AI 伴侣植入虚假事实、偏好或指令式记忆，属于跨租户写入与 prompt 污染。
- 修复摘要：记忆写入必须由服务端身份推导 userId，并校验 characterId 归属；无法证明归属的请求一律拒绝。
- 修复文件：backend-agent/app/main.py、backend-agent/app/memory/writer.py、deploy/api-gateway/nginx.conf
- 建议测试：pytest -q backend-agent（新增 tests/test_memory_ownership.py）；pytest -q backend-agent

### SEC-78045 · [低] 渲染端 CSP 允许 unsafe-eval 与任意 https 连接，未设置导航守卫
- 专家 / section：security / browser
- 位置：`frontend/index.html:7`
- 置信度：high · kind：plan
- 证据：frontend/index.html:7 的 meta CSP 为 `script-src 'self' 'unsafe-eval'; connect-src 'self' https: wss:; img-src 'self' data: blob: https:`，`'unsafe-eval'` 使字符串到代码的执行路径保持开启，`https:` 允许把任意域当外联出口（数据外带不受限）。frontend/electron/main.js 只在 setWindowOpenHandler（:667-673）里用 isAllowedExternalUrl 拦新窗口，全文没有 `will-navigate` 守卫，窗口内导航不以白名单收口。
- 影响：一旦出现未被 DOMPurify 覆盖的 DOM 注入点（当前 v-html 仅 1 处且已白名单），CSP 基本不提供纵深防御：eval 可用、外联到任意 https 域不受限，能把本地数据送出；若渲染进程被诱导导航，Electron 侧没有第二道拦截。
- 修复摘要：收敛 CSP 到具体域名并移除 unsafe-eval，同时给 BrowserWindow 加 will-navigate 白名单。
- 修复文件：frontend/index.html、frontend/electron/main.js
- 建议测试：npm --prefix frontend test；npx vite build（frontend）后检查 dist/index.html 的 CSP

### SEC-3BB6D · [高] Python 出站 HTTP 无 SSRF 校验，模型 base_url 可指向内网或云元数据
- 专家 / section：security / uploads
- 位置：`backend-agent/app/http_client.py:25`
- 置信度：high · kind：plan
- 证据：backend-agent/app/http_client.py:25-40 的 new_client() 只设置超时、UA 与 local_address，没有任何目标地址校验；app/gateway.py:288 直接把 `normalize_openai_base_url(base_url)`（:92-110 仅拼接 /v1/chat/completions）交给该 client POST。base_url 来自请求体（main.py 的 textBaseUrl/visionBaseUrl）或 DB 配置，经上面那条无鉴权端点即可由外部指定。对照 Java 侧：backend/lianyu-common/.../OutboundUrlValidator.java 会拦 BLOCKED_HOSTS、.local/.internal、loopback/link-local/169.254 并固化 DNS 结果，配合 backend/lianyu-ai/.../SsrfPinningClientFactory.java 防重绑定，ModelCatalogService.java:180-183 正确使用 —— Python 侧完全没有等价物。
- 影响：未认证攻击者可让后端容器向内网服务（MySQL/Redis/MinIO/Milvus、docker 网络里的 Java 服务）或云厂商元数据端点 169.254.169.254 发起 POST，读取响应或至少做端口/服务探测（盲 SSRF），并可借错误信息外带内网内容。
- 修复摘要：在 Python 出站层加与 Java 等价的校验：协议白名单、域名/IP 黑名单、DNS 解析后固化 IP，并禁止跟随跨域重定向。
- 修复文件：backend-agent/app/http_client.py、backend-agent/app/gateway.py、backend-agent/app/main.py
- 建议测试：pytest -q backend-agent（新增 tests/test_outbound_guard.py）；pytest -q backend-agent

### SEC-86656 · [高] 生产账号口令硬编码在已跟踪的运维脚本里
- 专家 / section：security / secrets
- 位置：`scripts/_prod_verify.py:22`
- 置信度：high · kind：direct
- 证据：scripts/_prod_verify.py 是 `git ls-files` 跟踪的文件（提交 3399884），第 21-22 行写死 `USERNAME = [REDACTED:username] / `PASSWORD = [REDACTED:secret] `http://127.0.0.1:8080` 做登录并跑真实对话验证；同文件第 23 行还会读取服务器 `/opt/lianyu/.env`。也就是说一个真实生产账号的口令进入了仓库历史，任何能读仓库的人（含 fork/CI 日志/历史克隆）都能直接登录生产。
- 影响：仓库读者可直接以该账号登录生产环境，读写其对话与记忆、消耗其模型额度；若该账号被复用为管理员或与其它服务共享口令，影响面进一步扩大。因为已经进了 git 历史，仅删除该行不足以消除暴露。
- 修复摘要：立即轮换该账号口令，把脚本改成从环境变量读取凭据，并清理仓库历史中的明文口令。
- 修复文件：scripts/_prod_verify.py、.github/workflows/ci.yml、AGENTS.md
- 建议测试：grep -rn 'PASSWORD\s*=' scripts/*.py 期望无字面量；python -c "import os; os.environ.pop('LIANYU_VERIFY_PASSWORD', None)" 后运行脚本期望明确报错退出

### SEC-1E385 · [高] Jasypt 主密钥缺失时静默回退明文，API Key 与 TOTP 密钥以明文落库
- 专家 / section：security / crypto
- 位置：`backend/lianyu-security/src/main/java/com/lianyu/security/util/JasyptUtil.java:63`
- 置信度：high · kind：direct
- 证据：JasyptUtil.java:20-27 在 `LIANYU_MASTER_KEY` 为空时只打一条 warn 后 return（encryptors 保持空）；encrypt() 在 :63-66 与 :68-70 遇到 encryptors 为空或 current 版本缺失时执行 `log.warn("No Jasypt encryptor available, returning plaintext"); return plaintext;`。调用方 ApiKeyVaultService.java:[REDACTED:secret] 时 `vault.setApiKeyEncrypted(jasyptUtil.encrypt(...))`）与 AdminTotpController:24 会把这个明文直接写进 `api_key_vault.api_key_encrypted` / `admin_user.totp_secret`。`.env.example:40` 的 `LIANYU_MASTER_KEY=` 是空占位，缺变量时功能照常启动。
- 影响：一次部署疏忽（漏配环境变量）就会让用户自带的模型 API Key 与管理员 2FA 种子以明文形态长期留在数据库与备份里，且没有任何报错；之后即便补上密钥，已写入的明文行也不会被识别（decrypt 会尝试解密失败后返回原值）。属于加密能力的静默降级（fail-open）。
- 修复摘要：把加密从 fail-open 改为 fail-closed：没有可用主密钥时拒绝写入并要求显式开启降级开关，同时给已存明文做一次迁移。
- 修复文件：backend/lianyu-security/src/main/java/com/lianyu/security/util/JasyptUtil.java、backend/lianyu-service/src/main/java/com/lianyu/service/ai/ApiKeyVaultService.java、backend/lianyu-admin/src/main/java/com/lianyu/admin/identity/AdminTotpController.java、.env.example
- 建议测试：mvn -q -f backend/pom.xml test -Dtest=JasyptUtilTest；mvn -q -f backend/pom.xml test

### SEC-0DE83 · [低] 对话内容（SSE 帧原文）以 INFO 级别写入日志
- 专家 / section：security / exposure
- 位置：`backend-agent/app/main.py:247`
- 置信度：high · kind：direct
- 证据：backend-agent/app/main.py:247-249 在前 3 帧上执行 `logger.info("chat-stream frame[%d]=%s", frames, frame[:160].replace("\n", "\\n"))`，frame 是包含模型回复正文的 SSE 数据行；:223-225 也会记录 userId、model 与 system 长度。`backend/lianyu-app/src/main/resources/application.yml` 中 com.lianyu 侧日志级别为 DEBUG 且 prod profile 未收紧。
- 影响：角色回复（含用户输入触发的私密内容）会以明文进入容器日志并被 docker logs/日志采集长期留存，扩大日志读写方的数据可见面；排障时贴日志也容易把用户对话带出去。
- 修复摘要：把帧内容日志降级为 debug 并默认关闭，或只记录帧类型与长度而不记正文。
- 修复文件：backend-agent/app/main.py、backend-agent/app/logging_config.py
- 建议测试：pytest -q backend-agent

### SEC-96CF9 · [中] 自动更新清单验签已在代码里实现但未启用（公钥留空即跳过），依赖审计也不阻塞合并
- 专家 / section：security / supply-chain
- 位置：`frontend/.env.production.cloud:12`
- 置信度：high · kind：plan
- 证据：frontend/electron/updater/updater.js:81-107 的 verifyManifestOrThrow 在 `resolveManifestPublicKey()` 为空时仅 `logger.warn('latest.yml signature check disabled')` 然后 return（fail-open）；公钥来自 runtimeSecrets.updateManifestPublicKey，打包时取 `VITE_LIANYU_UPDATE_PUBKEY`，而仓库内 `frontend/.env.production.cloud:12` 是注释掉的 `# VITE_LIANYU_UPDATE_PUBKEY=`，即当前发布的客户端不带公钥。于是安装包完整性只依赖 latest.yml 里的 sha512（与该清单同源），清单被改即可换包。另外 .github/workflows/ci.yml:46-48 的 OSV 扫描带 `continue-on-error: true`，且 action 只用 tag（@v4/@v2.0.3）而非 commit SHA。
- 影响：更新渠道一旦被写入（MinIO 凭据泄露、nginx/存储被改、发版脚本被投毒），客户端会静默安装攻击者的安装包 —— 桌面端等于远程代码执行；而依赖漏洞扫描失败不会阻断合并，已知漏洞可能长期留在发布物里。
- 修复摘要：给更新链补上强制验签（发布带 latest.yml.sig + 客户端内置公钥），并把依赖审计改为阻塞项、action 固定到 SHA。
- 修复文件：frontend/.env.production.cloud、frontend/electron/updater/updater.js、scripts/sign_update_manifest.py、.github/workflows/ci.yml
- 建议测试：npm --prefix frontend test（updater/manifestSignature 用例）；python scripts/sign_update_manifest.py --help；python scripts/check_release_asset_contract.py

### REL-D63D0 · [高] 模型网关熔断器把客户端取消/断连计为上游失败，5 次中断即可误开共享熔断
- 专家 / section：reliability / error-handling
- 位置：`backend-agent/app/gateway.py:279`
- 置信度：high · kind：direct
- 证据：stream_text 与 _post_chat 都用 `except BaseException as exc: await breaker.on_error(exc); raise`（gateway.py:279、407）；resilience.py:132 的 `on_error` 只把 `GatewayBusinessError` 记成功，其余一律 `self._results.append(False)`，CLOSED 下窗口 20 / 最小 5 样本 / 50% 即 `_open()`，OPEN 30s，HALF_OPEN 任一失败立即重开。用户关闭窗口或点「停止生成」时，SSE 任务被取消，`CancelledError`/`GeneratorExit` 正好落在 `yield delta` 上，被 `except BaseException` 捕获并计为失败；breaker key 为 provider+base_url，使用同一官方端点的所有用户共享同一个 breaker。
- 影响：正常用户行为（中途关窗、切换会话、点停止）就能在 30s 内把共享熔断器打开，此后所有用户的会话在 try_acquire 处快失败，统一提示「对方模型暂时繁忙，请稍后再试」；HALF_OPEN 期再遇到一次取消即重新打开，故障可自持。上游其实完全健康，监控与日志里也只会看到 breaker OPEN 的 warning，难以定位到“取消”这个真实原因。
- 修复摘要：把取消/断连与上游失败区分开：取消不参与熔断统计，只记录一次 info 日志。
- 修复文件：backend-agent/app/gateway.py、backend-agent/app/resilience.py、backend-agent/tests/test_resilience.py
- 建议测试：新增 test_cancel_does_not_open_breaker：对 UpstreamBreaker 连续调用 on_error(asyncio.CancelledError()) 5 次后断言 state 仍为 CLOSED。；新增 test_stream_cancel_not_counted：用假 gateway 制造生成器中途 aclose，断言 on_error 未被调用。；跑 `pytest -q backend-agent` 全绿。

### REL-205D3 · [高] 后台 AI 任务按异常文案判定可重试，恰好漏掉熔断/隔舱的「对方模型暂时繁忙」，任务直接进无人消费的 DLQ
- 专家 / section：reliability / error-handling
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/ai/background/AiBackgroundConsumer.java:102`
- 置信度：high · kind：plan
- 证据：isAgentTransient 沿 cause 链找到 `BusinessException(ErrorCode.AI_PROVIDER_ERROR)` 后按 message 子串判定：`message.contains("prompt 组装失败") || message.contains("消息发送失败") || message.contains("服务暂时不可用")`（AiBackgroundConsumer.java:102-116）。而 AiResilience.java:108 在熔断/隔舱拒绝时抛的文案是「对方模型暂时繁忙，请稍后再试」，三个子串一个都不匹配 → retryable=false → `throw last`；容器工厂设置 `defaultRequeueRejected(false)`（RabbitMqConfig.java:129/148），消息进入 `ai.background.dlq`。全仓 `git grep -n "dlq"` 只有队列/绑定声明（RabbitMqConfig.java:23/26/30/33/66/81/96/106），没有任何消费者或重放脚本。
- 影响：上游模型真的繁忙或熔断打开——最应该退避重试的场景——反而被判为不可重试，日记、动态评论、语音通话总结等后台任务被整条丢弃，只在日志留一行 error；DLQ 无消费者也无告警，等于静默丢数据。另外重试靠 `Thread.sleep` 阻塞 RabbitMQ listener 线程（默认 1500ms×n），并发下会占满消费线程。
- 修复摘要：用错误码 + 明确的 retryable 标记替代文案匹配，并让 DLQ 至少有消费者或告警，而不是黑洞。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/ai/background/AiBackgroundConsumer.java、backend/lianyu-service/src/main/java/com/lianyu/service/ai/AiResilience.java、backend/lianyu-service/src/main/java/com/lianyu/service/config/RabbitMqConfig.java
- 建议测试：AiBackgroundConsumerTest：传入 BusinessException(AI_PROVIDER_ERROR, "对方模型暂时繁忙，请稍后再试") 断言 isAgentTransient 为 true。；AiBackgroundConsumerTest：传入 key 无效一类业务错断言 false 且不重试。；跑 `mvn -q -f backend/pom.xml test`。

### REL-A1E33 · [中] 流式桥不区分「收到 [DONE]」与「上游半途断开」，截断回复按成功落库并触发通知与记忆摘要
- 专家 / section：reliability / error-handling
- 位置：`backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentStreamBridge.java:99`
- 置信度：high · kind：plan
- 证据：accept() 里 `case DONE, IGNORED -> { }`（AgentStreamBridge.java:93-95）不记录任何「已收到终止帧」状态；finish() 只在内容为空时报错，否则一律 `finishSseSuccess`（:99-113）。上游连接被关掉时 WebClient 走 onComplete → relay::finish，同样按成功处理。abort() 更直接：`callback.onComplete(partial, null)`（:126-139），把截断内容以 null 异常上抛。ConversationService.onComplete 收到 error==null 时执行 saveAssistantReplies + relationshipStateService + memoryWriter.enqueueSummary + 发通知（ConversationService.java:377-407）。
- 影响：backend-agent 重启、OOM、网关中途断开导致没有 [DONE] 时，半截回复被当作完整消息落库、写入关系与记忆摘要并推送通知；用户看到一句话没说完的回答，既没有错误提示也没有重试入口，事后从日志也看不出异常（只有一条正常的 saved 日志）。客户端断连时同理。
- 修复摘要：引入 sawDone 状态，把「正常终止」与「上游断开」区分开：断开时以错误收尾并显式标记，是否落库作为产品决策显式写出。
- 修复文件：backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentStreamBridge.java、backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java、backend/lianyu-web/src/test/java/com/lianyu/web/agent/AgentStreamBridgeTest.java
- 建议测试：AgentStreamBridgeTest：只推 content 不推 [DONE] 后调用 finish，断言发出 error 帧且 callback 收到非空异常。；AgentStreamBridgeTest：abort 后断言 callback 的 error 参数非 null。；跑 `mvn -q -f backend/pom.xml test`。

### REL-4B9D6 · [低] 记忆批量写入注释声称「整批同一事务，任一失败全回滚」，实现却是逐条事务提交
- 专家 / section：reliability / error-handling
- 位置：`backend-agent/app/memory/writer.py:123`
- 置信度：high · kind：direct
- 证据：writer.py:3 与 :123 都写「整批在同一事务内，任一失败全回滚」，main.py:917 的 endpoint docstring 同样写「整批单事务」；但 `_insert_new`(175)、`_update_meta`(196)、`_replace_and_enqueue`(209) 各自 `async with transaction()`，逐条提交。main.py:952-953 在异常时 `raise HTTPException(500, "memory write failed")`，此前已提交的条目保留。
- 影响：批次中途失败时部分记忆已落库、部分丢失，调用方只拿到 500，无法判断写入了多少；Java 侧 AgentMemoryAdapter 见 null/error 会回退到启发式重写（AgentMemoryAdapter.java:95-104），是否重复完全依赖两侧 source_hash 口径一致。按注释假设「全回滚」去写恢复逻辑的人会踩坑。
- 修复摘要：把事务边界上移到 write_memories，让实现与文档一致；helper 改为接收 cursor。
- 修复文件：backend-agent/app/memory/writer.py、backend-agent/app/main.py、backend-agent/tests/test_memory_writer.py
- 建议测试：新增 test_batch_is_atomic：mock 第二条插入抛错，断言事务回滚后行数为 0。；跑 `pytest -q backend-agent`。

### REL-7EE2B · [中] 记忆 outbox 卡在 RUNNING 的行不会自愈，重排只有手工接口且全仓无调用方
- 专家 / section：reliability / error-handling
- 位置：`backend-agent/app/memory/relay.py:207`
- 置信度：high · kind：direct
- 证据：drain_once 只认领 `status='PENDING'` 的行（`UPDATE ... WHERE status='PENDING'` 影响行数判定）；进程在认领后崩溃或重启，该行永远停在 RUNNING。relay.py:207 提供了 `requeue_stuck()`（DEAD 与超 10 分钟的 RUNNING），但唯一入口是 main.py:967-971 的 `POST /agent/memory/relay/requeue`；`git grep -n "relay/requeue"` 在 backend/scripts/CI 中零命中，说明没有任何定时调用。
- 影响：每次部署/重启/OOM 都可能留下永久 RUNNING 的行：对应记忆不写 Milvus，向量检索永远查不到这条，角色表现成「突然忘了」，而日志里只有一条认领成功的 info。DEAD 行（超过重试上限）同样只能人工重排，且没有告警。
- 修复摘要：在 _relay_loop 中定期调用 requeue_stuck()，并给 RUNNING 超时与 DEAD 行打指标日志。
- 修复文件：backend-agent/app/main.py、backend-agent/app/memory/relay.py、backend-agent/tests/test_memory_relay.py
- 建议测试：新增 test_relay_loop_requeues_stuck：mock 时间把 next_attempt_at 拨到过去，断言 drain 后状态不再是 RUNNING。；跑 `pytest -q backend-agent`。

### REL-6FD30 · [中] 前端聊天流没有空闲超时，半开连接会让输入框永久停在等待态
- 专家 / section：reliability / error-handling
- 位置：`frontend/src/pages/ChatPage.vue:1430`
- 置信度：high · kind：direct
- 证据：attemptStream() 在 `drainAssistantStream(response, { signal })` 处进入 `reader.read()` 循环（assistantStreamDrain.js:26-35），除 AbortController 外没有任何计时器；useStreamAbort.js 只负责 abort，没有 watchdog；全前端搜不到 stream 相关的 timeout 常量。waitingReply 直接 disabled 输入框与发送键（ChatPage.vue:215/225/250/1363），只在 finally 里复位（:1476）。
- 影响：代理/NAT 保持 TCP 但不再返回数据，或服务端线程卡死不结束响应时，read() 永不 resolve，waitingReply 永久为 true：用户无法再发消息，界面一直显示正在输入，没有错误提示也不会自动重试；只能离开页面重进。网络层错误只覆盖了 sendMessageStream 立刻抛错的情况。
- 修复摘要：给流式读取加空闲超时：超过 N 秒没有新 chunk 就 abort 并提示可重试。
- 修复文件：frontend/src/pages/ChatPage.vue、frontend/src/utils/assistantStreamDrain.js、frontend/src/utils/useStreamAbort.js
- 建议测试：新增 src/utils/__tests__/assistantStreamDrain.idle.test.js：reader.read 挂起，fake timers 推过阈值后断言 reject 超时错误。；跑 `npm --prefix frontend test`。

### REL-FEB0B · [低] 工具循环只把 httpx/RuntimeError 当作可降级错误，其余异常会让整轮回复失败
- 专家 / section：reliability / error-handling
- 位置：`backend-agent/app/tool_loop.py:101`
- 置信度：medium · kind：direct
- 证据：`except (httpx.HTTPError, RuntimeError) as exc:` 时把异常写进工具结果继续对话（tool_loop.py:101）；ValueError/KeyError/aiomysql 等异常直接冒出 execute_tool_calls，最终由 main.py 外层 `except Exception` 收尾，产出 `sse_error("生成失败，请重试")` 并结束整轮。
- 影响：单个工具的数据异常（例如返回 NULL 触发 KeyError、参数校验 ValueError）会把本可降级的局部失败升级成整条回复失败，已生成的文本作废，用户只看到一句通用错误，排障时也缺少是哪个工具的信息。
- 修复摘要：把工具执行的所有常规异常都降级为工具错误文本，并记录堆栈。
- 修复文件：backend-agent/app/tool_loop.py
- 建议测试：新增 test_tool_value_error_is_degraded：工具抛 ValueError，断言对话继续且结果文本含「调用失败」。；跑 `pytest -q backend-agent`。

### REL-17E6F · [低] AGENT 链路健康探测失败只写 debug 日志，整链路静默回退 Java 旧链路
- 专家 / section：reliability / error-handling
- 位置：`backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentRuntimeClient.java:414`
- 置信度：high · kind：direct
- 证据：`log.debug("Agent health probe failed: {}", e.toString());` 后 return false；ChatTurnAdapter.stream 与 reachable() 拿到 false 后返回 null，上层静默回退 Java 链路。生产默认日志级别是 INFO，这条记录不会出现。
- 影响：backend-agent 整体不可用、证书/地址配错时，全部流量悄悄回退到旧链路，行为差异（无工具调用、记忆抽取方式不同）对用户和运维都不可见；既没有 WARN 也没有指标，排障只能靠猜「为什么没有工具回复」。
- 修复摘要：把探测失败提升为带节流的 warn 并暴露降级计数。
- 修复文件：backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentRuntimeClient.java、backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentStreamAdapter.java
- 建议测试：AgentRuntimeClientTest：健康探测抛错时断言降级计数 +1。；跑 `mvn -q -f backend/pom.xml test`。

### REL-CFE67 · [高] CI 不运行 backend-agent 的 pytest 与 admin 测试，前端还违反仓库要求的串行执行
- 专家 / section：reliability / tests
- 位置：`.github/workflows/ci.yml:22`
- 置信度：high · kind：direct
- 证据：ci.yml 只有三个 job：backend（`mvn -B -ntp test`，:22）、frontend（`npx vitest run`，:39）、deps-audit（OSV，continue-on-error）。没有 pytest 步骤、没有 admin 的 npm test。AGENTS.md §4 明确要求前端用 `npx vitest run --no-file-parallelism`（updater 并发用例在满载并行下已知 flake），CI 里是并行跑。
- 影响：backend-agent 全部用例（含本轮 F1/F4/F5/F7 涉及的 gateway/resilience/tool_loop/memory）在合并前不执行，Python 侧错误处理改动零门禁；admin 的 1 个测试也从不运行；前端并行执行会把已知 updater flake 带进 CI，红灯被当噪音忽略，真正失败的用例因此更容易漏掉。
- 修复摘要：补齐两条 CI job，并把前端测试改成与文档一致的串行调用。
- 修复文件：.github/workflows/ci.yml
- 建议测试：PR 上确认三个 job 都触发并通过。；本地等价命令：`pytest -q backend-agent`、`npm --prefix admin test`、`npx --prefix frontend vitest run --no-file-parallelism`。

### REL-C3C45 · [中] `vitest --passWithNoTests` 让「一个测试都没跑」也返回成功
- 专家 / section：reliability / tests
- 位置：`frontend/package.json:18`
- 置信度：high · kind：direct
- 证据：frontend/package.json:18 `"test": "vitest --passWithNoTests"`，admin/package.json:9 相同。frontend/vitest.config.js 的 include 是三条 glob（src/shared/electron 下的 __tests__），目录调整或配置漂移导致 0 命中时，命令仍然 exit 0。
- 影响：测试文件被移动、include 写错、依赖缺失导致收集为空时，门禁依旧是绿的，覆盖率静默归零而无人发现；与 AGENTS.md「测试要能非空转验证」的纪律直接冲突。admin 全模块本只有 1 个测试文件，风险更高。
- 修复摘要：去掉 --passWithNoTests，让「零用例」成为失败；需要空跑的场景用显式环境变量。
- 修复文件：frontend/package.json、admin/package.json
- 建议测试：临时改坏 include 后执行 `npm --prefix frontend test`，确认退出码非 0。；恢复后执行 `npm --prefix frontend test` 与 `npm --prefix admin test`，确认全绿且用例数 > 0。

### REL-8CDE1 · [中] 取消与「无 [DONE] 截断」两条语义零回归测试，正好覆盖本轮两个高危缺陷
- 专家 / section：reliability / tests
- 位置：`backend/lianyu-web/src/test/java/com/lianyu/web/agent/AgentStreamBridgeTest.java:84`
- 置信度：high · kind：direct
- 证据：AgentStreamBridgeTest 的 8 个用例（relaysContentIncrementallyThenFinishesWithDone、replaceOverrides、errorFrameFinishesWithErrorAndPartialContent、emptyContent、ignoresUnknownFrames、clientDisconnectStopsRelaying、failReportsErrorMessage、finishIsIdempotent）输入全部带 `data: [DONE]`，没有任何「流结束但没有 DONE」的断言；`git grep -n "cancel\(\)\|CancelledError" backend-agent/tests` 命中 0，熔断器没有取消相关用例。
- 影响：F1（取消被计为上游失败）与 F3（截断当成功落库）都没有护栏：任何人重构 gateway/stream bridge 都可能在无人察觉的情况下恢复旧行为，而这两条恰好影响全体用户的可用性与数据正确性。
- 修复摘要：为取消与截断各补最小用例，并纳入 CI。
- 修复文件：backend/lianyu-web/src/test/java/com/lianyu/web/agent/AgentStreamBridgeTest.java、backend-agent/tests/test_resilience.py
- 建议测试：新用例单独跑绿：`mvn -q -f backend/pom.xml -Dtest=AgentStreamBridgeTest test`。；`pytest -q backend-agent/tests/test_resilience.py`。

### MNT-07031 · [中] 前端三个零引用模块仍被注释描述为生效中（antiDebug / 截图分享 / ASR HTTP）
- 专家 / section：maintainability / cleanup
- 位置：`frontend/src/utils/antiDebug.js:12`
- 置信度：high · kind：direct
- 证据：CONFIRMED_DEAD。`grep -rn "antiDebug\|initAntiDebug" frontend/src frontend/electron frontend/shared` 只命中本文件定义 frontend/src/utils/antiDebug.js:12 `export function initAntiDebug() {`（文件 93 行）与两处注释：frontend/src/main.js:38「---- 全局错误捕获（须在 initAntiDebug 之前注册）----」、frontend/src/utils/logger.js:4「生产环境 console.* 被 antiDebug 吞掉，本模块走独立 IPC 通道（desktop:renderer-log）」。同批零引用：frontend/src/utils/captureChatShareScreenshot.js:128 `export async function captureChatShareScreenshot(container, selectedIds) {`（220 行，`grep -rn captureChatShareScreenshot frontend` 仅命中定义行）；frontend/src/api/asr.js:4 `export function transcribeAudio(blob, filename = 'voice.webm') {`（12 行，`grep -rn "api/asr\|transcribeAudio" frontend` 仅命中自身）。实际语音链路走 WebSocket 的 asr.partial/asr.final（frontend/src/pages/ChatPage.vue:1293、:1301，frontend/src/composables/useVoiceDuplex.js:124），后端端点仍在 backend/lianyu-web/src/main/java/com/lianyu/web/controller/AsrController.java:31 `@PostMapping("/transcribe")`。
- 影响：注释把一个不存在的生产防护说成在用：后来者会以为 console 已被兜底、全局错误已被捕获，排查线上无日志时会先怀疑构建流程而不是这段代码根本不存在。317 行（93 + 220 + 12）死代码还让每次前端重构都要顺带确认它们是否还该保留；同时形成「前端无调用方、后端仍暴露 /api/asr/transcribe」的悬空契约。
- 修复摘要：删除三个零引用模块，改写两处误导性注释，并把后端 ASR HTTP 端点的去留交 correctness 判定（前端实际只用 WS 通道）。
- 修复文件：frontend/src/utils/antiDebug.js、frontend/src/utils/captureChatShareScreenshot.js、frontend/src/api/asr.js、frontend/src/main.js、frontend/src/utils/logger.js
- 建议测试：npm --prefix frontend test；npm --prefix frontend run build

### MNT-307E0 · [中] zh-TW 整份 529 行语言包未接入 i18n，与 AGENTS.md 的四语约定互相打脸
- 专家 / section：maintainability / cleanup
- 位置：`frontend/src/i18n/locales/zh-TW.js:1`
- 置信度：high · kind：plan
- 证据：CONFIRMED_DEAD。frontend/src/i18n/index.js:7-9 只 `import zh from './locales/zh'`、`import ja from './locales/ja'`、`import en from './locales/en'`，:17 `messages: { zh, ja, en }`；frontend/src/constants/language.js:1 `/** @typedef {'zh'|'ja'|'en'} AppLanguage */`、:3 `export const SUPPORTED_LANGUAGES = ['zh', 'ja', 'en']`，normalizeLanguage 把 zh-TW 归到 zh。全库 zh-TW 只命中该文件自身（:8 标签 `'zh-TW': '繁體中文'`），无任何 import。key 数：zh 378、ja 373、en 377、zh-TW 381（比 zh 多 3 个 key，说明它被单独维护过）。AGENTS.md §3.2 写「i18n 四语齐（zh / zh-TW / en / ja），新增 key 必须四份一起加」。
- 影响：381 个 key 的繁体文案永远到不了用户，但每次加 key 都要按文档同步维护第四份；文档与代码对「支持哪些语言」给出两个答案，新同学第一周就会踩。若产品确实要繁体，缺的是接线而不是文案。
- 修复摘要：二选一并一次性收敛：要么把 zh-TW 接进 i18n（补 SUPPORTED_LANGUAGES、语言切换与持久化），要么删除该文件并把 AGENTS.md 改为三语。
- 修复文件：frontend/src/i18n/locales/zh-TW.js、frontend/src/i18n/index.js、frontend/src/constants/language.js、AGENTS.md
- 建议测试：npm --prefix frontend test

### MNT-6B905 · [低] admin 有 4 个声明依赖零 import，页面还用硬编码占位数字冒充真实状态
- 专家 / section：maintainability / cleanup
- 位置：`admin/package.json:10`
- 置信度：high · kind：plan
- 证据：admin/package.json:10 起 dependencies 为 @element-plus/icons-vue ^2.3.1、@tanstack/vue-table ^8.21.3、axios ^1.18.0、echarts ^6.1.0、element-plus ^2.14.5、pinia ^2.2.6、vue ^3.5.13、vue-router ^4.4.5。`grep -rn "echarts\|vue-router\|@tanstack\|element-plus/icons" admin/src admin/index.html admin/vite.config.*` 输出为空 → echarts、@tanstack/vue-table、vue-router、@element-plus/icons-vue 四个依赖无任何引用（axios/element-plus/pinia/vue 有用）。另 admin/src/App.vue:14 的单行模板里硬编码「最新客户端 v0.2.363」「待处理事项 12」以及 3 行活动记录（`v-for="row in [{e:'客户端版本发布 v0.2.363'...}]"`）。
- 影响：四个未用依赖持续放大安装体积与供应链面（后台是内网工具，实际成本主要是每次装包/审计时的噪音）。App.vue 的写死版本号与待处理数会随时间失真，让人误判发布状态；这类「假数据占位」比空状态更难被发现。
- 修复摘要：移除四个未引用依赖；把 App.vue 的版本号/待处理数改成真实数据源，取不到就显式显示占位符。
- 修复文件：admin/package.json、admin/src/App.vue
- 建议测试：npm --prefix admin test；npm --prefix admin run build

### MNT-A038F · [中] scripts 下 49 个 _ 前缀一次性脚本无人引用（3968 行），部署入口实际只有 _cloud_deploy_pull.py
- 专家 / section：maintainability / cleanup
- 位置：`scripts/_cloud_deploy_backend_layer.py:1`
- 置信度：high · kind：plan
- 证据：`git ls-files scripts | grep -c '^scripts/_'` = 55；对每个脚本文件名在被跟踪的 md/py/ps1/yml/json/mjs/js/sh 里做全文引用检索，仅 6 个有引用：_cloud_deploy_pull.py（AGENTS.md、Pets/skills/hatch-lianyu-pet/SKILL.md、scripts/README.md）、_upload_update_assets.py、_cloud_deploy_layered.py、_prod_verify.py、_audit_installer_unpack.py、_upload_wechat_channel.py；其余 49 个零引用、合计 3968 行。典型：scripts/_cloud_deploy_backend_layer.py（104 行）、scripts/_cloud_deploy_agent_layer.py（105 行）、scripts/_cloud_deploy_agent_base_url.py（84 行）、scripts/_cloud_deploy_backend_only.py（63 行）、scripts/_cloud_deploy_prep.py（94 行）、scripts/_verify_electron_release.py（253 行）、scripts/_diagnose_moments_deep.py（168 行）。AGENTS.md §3.1 明确「临时物不进仓库：一次性排查脚本一律放 local/ 或带 _ 前缀且不提交」，§6 又把 _cloud_deploy_pull.py 定为唯一部署入口。
- 影响：发布链路的「哪一个是真入口」被 54 个同族脚本淹没：它们都叫 _cloud_deploy_*、都连生产 SSH、口径还各不相同（见下一条 timeout/PTY 漂移），误跑一个就可能对生产执行过期逻辑。同时 scripts 目录的维护成本与审计面被无谓放大。
- 修复摘要：保留 6 个有引用的脚本，其余 49 个移到 local/ 或直接删除；并在 scripts/README.md 写清唯一入口。
- 修复文件：scripts/_cloud_deploy_backend_layer.py、scripts/_cloud_deploy_agent_layer.py、scripts/_cloud_deploy_agent_base_url.py、scripts/_cloud_deploy_backend_only.py、scripts/_cloud_deploy_prep.py、scripts/_verify_electron_release.py、scripts/README.md
- 建议测试：python scripts/check_release_asset_contract.py；python -c "import ast,glob;[ast.parse(open(p,encoding='utf-8').read()) for p in glob.glob('scripts/*.py')]"

### MNT-0829F · [中] 部署脚本 16 份 load_env 与 49 份 paramiko 样板，超时口径漂移且仍有 get_pty=True
- 专家 / section：maintainability / simplify
- 位置：`scripts/_cloud_deploy_backend_layer.py:26`
- 置信度：high · kind：plan
- 证据：`grep -rn "def load_env" scripts` 命中 16 处，其中 14 处逐字相同（每份 9 行，含 scripts/_cloud_chat20.py:13、scripts/_cloud_deploy_agent_base_url.py:13、scripts/_cloud_deploy_agent_layer.py:16、scripts/_cloud_deploy_backend_layer.py:16、scripts/_cloud_deploy_backend_only.py:13、scripts/_cloud_deploy_prep.py:22、scripts/_prod_verify.py:35、scripts/_run_prod_verify.py、scripts/_verify_prod_tts.py 等），另 2 处为变体（scripts/_prod_verify.py:35 与 scripts/ops/prod_error_watch.py）。49 个脚本各自 `import paramiko` 并手拼 exec_command；超时默认值三套：scripts/_cloud_deploy_agent_base_url.py / _cloud_deploy_agent_layer.py 为 2400，scripts/_cloud_deploy_backend_layer.py:26 `def run(c, cmd, timeout=3600, must=True):` 为 3600，其余散落 120/300。scripts/_cloud_deploy_backend_layer.py:28 `c.exec_command(cmd, timeout=timeout, get_pty=True)` 恰好是 AGENTS.md §6 记录的坑：「compose 输出接 PTY 时可能永不返回 EOF，deploy 段会假死」。
- 影响：同一份「读 .env → 连 SSH → 跑命令」的逻辑有 16 份副本，改一处连接参数（端口、密钥、超时、非 PTY）要改 16 处，实际只改了 1 处 —— 这就是 PTY 假死逻辑还活着的直接原因：_cloud_deploy_pull.py 改对了，同族脚本没跟上。超时 2400/3600 的漂移还让「一条命令卡多久算失败」不可预测。
- 修复摘要：抽出 scripts/_ssh.py（load_env + connect + run(cmd, timeout, must) ，统一 get_pty=False），让保留的部署脚本各自只留业务命令序列。
- 修复文件：scripts/_cloud_deploy_pull.py、scripts/_prod_verify.py、scripts/_cloud_deploy_backend_layer.py、scripts/_cloud_deploy_agent_layer.py、scripts/_cloud_deploy_agent_base_url.py、scripts/_cloud_deploy_backend_only.py
- 建议测试：python -c "import ast;ast.parse(open('scripts/_ssh.py',encoding='utf-8').read())"；python scripts/check_release_asset_contract.py

### MNT-89C04 · [低] backend-agent 的 profile_context 只被自己的测试调用，测试名 matches_java 已无对端
- 专家 / section：maintainability / cleanup
- 位置：`backend-agent/app/memory/profile_context.py:39`
- 置信度：high · kind：plan
- 证据：CONFIRMED_DEAD（生产路径）。backend-agent/app/memory/profile_context.py:39 `def build_profile_context(items: Sequence[Iterable]) -> str | None:`（文件 59 行）；`grep -rn "profile_context\|build_profile_context" backend-agent` 只命中自身与 backend-agent/tests/test_profile_context.py:13（import）、:38 `def test_profile_context_matches_java(golden)`（断言 app/prompts/profile_context_golden.json）。app/ 内零调用。:1 的 docstring 写「对齐 `MemoryRetriever.retrieveProfileContext`」，但 `grep -rn "MemoryRetriever\|retrieveProfileContext" backend` 全库 0 命中 —— 被对齐的 Java 实现已不存在。
- 影响：一条保护「Python 与 Java profile 拼装一致」的 golden 测试，实际两个对端只剩一个，却依然全绿。它给 reviewer 一种跨端已对齐的错觉，也把 59 行无人调用的拼装逻辑长期留在记忆模块里，干扰「记忆上下文到底由谁组装」的判断。
- 修复摘要：二选一：删除 profile_context.py + 其测试 + golden（对端已不存在），或把它真正接进记忆上下文组装并重命名为 Python 侧唯一事实来源。
- 修复文件：backend-agent/app/memory/profile_context.py、backend-agent/tests/test_profile_context.py、backend-agent/app/prompts/profile_context_golden.json
- 建议测试：backend-agent/.venv/Scripts/python.exe -m pytest -q backend-agent/tests/test_profile_context.py

### MNT-3F647 · [中] EncryptedField / EncryptedStringTypeHandler 从未接线，注解存在但字段并未加密
- 专家 / section：maintainability / cleanup
- 位置：`backend/lianyu-security/src/main/java/com/lianyu/security/handler/EncryptedStringTypeHandler.java:12`
- 置信度：high · kind：plan
- 证据：CONFIRMED_DEAD。backend/lianyu-security/src/main/java/com/lianyu/security/handler/EncryptedStringTypeHandler.java:12 `public class EncryptedStringTypeHandler extends BaseTypeHandler<String> {`（40 行）+ backend/lianyu-security/src/main/java/com/lianyu/security/annotation/EncryptedField.java:1（9 行 `public @interface EncryptedField`）。`grep -rn "EncryptedStringTypeHandler\|@EncryptedField\|EncryptedField" --include=*.java backend` 排除这两个文件后零命中：既没有 `@TableField(typeHandler = EncryptedStringTypeHandler.class)`，也没有任何字段标注 @EncryptedField。Handler 内部通过 `SpringContextHolder.getBean(JasyptUtil.class)` 取密钥，说明它依赖运行时上下文，一旦被误用会以难排查的方式失败。
- 影响：这是一条「看起来已经做了字段加密」的假线索：安全评审看到注解会假定敏感字段（vault 密钥、个人资料）已落库加密，而实际是明文。留着比删掉更危险，因为它把「未做」伪装成「已做」。
- 修复摘要：要么接线（明确列出应加密的列并补迁移/回填方案），要么删除这两个类型，避免伪造安全语义。
- 修复文件：backend/lianyu-security/src/main/java/com/lianyu/security/handler/EncryptedStringTypeHandler.java、backend/lianyu-security/src/main/java/com/lianyu/security/annotation/EncryptedField.java
- 建议测试：mvn -q -f backend/pom.xml test

### MNT-235E2 · [中] V55 建了 agent_capability_audit 表，写入方 AgentBridgeService 已随能力层删除
- 专家 / section：maintainability / cleanup
- 位置：`backend/lianyu-dao/src/main/resources/db/migration/V55__agent_capability_audit.sql:15`
- 置信度：high · kind：plan
- 证据：CONFIRMED_DEAD。backend/lianyu-dao/src/main/resources/db/migration/V55__agent_capability_audit.sql:15 `CREATE TABLE IF NOT EXISTS \`agent_capability_audit\` (`，:11 注释「Ownership: written by Java (AgentBridgeService), never by the Python agent」，表头还写「the product surfaces a 'recent actions' panel」。`grep -rn agent_capability_audit` 只命中该迁移文件与 target/ 构建副本；`grep -rn AgentBridgeService --include=*.java backend` 在 src 下 0 命中（com.lianyu.service.tools.bridge 于提交 a8d9fc0 / 2026-10-25 删除，仅 target/surefire-reports 有残留）。生产库因此有一张永不写入、也无人读取的表。
- 影响：schema 与代码的叙事脱节：迁移注释描述了一个已下线的「最近动作」面板，运维/新同学据此以为存在审计数据可查，实际永远是空表。Flyway 历史不可改，越晚处理越只能靠新增迁移绕开，成本会持续累积。
- 修复摘要：新增一条迁移 DROP 该表（或明确保留为历史归档并修正注释），并同步清理 target 里的残留报告口径。
- 修复文件：backend/lianyu-dao/src/main/resources/db/migration/V55__agent_capability_audit.sql、backend/lianyu-dao/src/main/resources/db/migration/V56__drop_agent_capability_audit.sql
- 建议测试：mvn -q -f backend/pom.xml test

### MNT-F08A4 · [中] lianyu-web agent 层：8 处凭据解析 + 5 份逐字复制的健康缓存，参数还各不相同
- 专家 / section：maintainability / simplify
- 位置：`backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentStreamAdapter.java:52`
- 置信度：high · kind：plan
- 证据：「取 vault → 决定用解密 key 还是 apiKey → 空则回落 modelDefault → 判 baseUrl/apiKey 为空」这段在至少 8 处重复：backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentStreamAdapter.java:52、AgentTextStreamAdapter.java:49、ChatTurnAdapter.java:135、AgentBlockingAdapter.java:51、AgentImageAdapter.java:160、AgentMemoryAdapter.java:47、AgentSessionSummaryAdapter.java:48，以及 DesktopObserveOrchestrator.java:55/94/123 同一个类里三份。健康探测（3 秒缓存）在 5 个类逐字复制：AgentStreamAdapter.java:101/103/106、ChatTurnAdapter.java:199/201/204（注释写「与 AgentStreamAdapter 一致」）、AgentBlockingAdapter.java:121/123/126、AgentPromptAssemblyAdapter.java:103/105/108、AgentSessionSummaryAdapter.java:104/106/109，字段皆为 `private volatile boolean healthy; private volatile long healthyCheckedAt;` + `private boolean reachable()`。参数已漂移：AgentStreamAdapter.java:90 传 0.8 + maxTokens，AgentTextStreamAdapter.java:87 传 `0.7, 256`，AgentBlockingAdapter.java:72 默认 0.7，ChatTurnAdapter.java:189 默认 0.8/1024。
- 影响：「同一个 agent 在不同链路上用不同 temperature/maxTokens」不是设计，是复制后各改各的产物：同一句话在流式、阻塞、会话摘要三条链路上行为不同，排查时看不出原因。凭据解析重复 8 份意味着密钥轮换或 vault 字段变更要改 8 处，漏一处就是静默 fallback 到 Java。健康缓存的副本连注释都在解释「我抄的」，是可读性债务的典型信号。
- 修复摘要：抽出 AgentCredentials（resolve + 判空 + 错误码）与 AgentHealthProbe（3 秒缓存）两个协作对象，注入到各 Adapter；把采样参数收敛到一处默认值表。
- 修复文件：backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentStreamAdapter.java、backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentTextStreamAdapter.java、backend/lianyu-web/src/main/java/com/lianyu/web/agent/ChatTurnAdapter.java、backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentBlockingAdapter.java、backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentSessionSummaryAdapter.java
- 建议测试：mvn -q -f backend/pom.xml test；mvn -q -f backend/pom.xml -pl lianyu-web -Dtest=Agent*Test test

### MNT-D4749 · [中] 主动聊天与朋友圈两个定时任务各自复制同一套候选装载 / 概率抽样骨架
- 专家 / section：maintainability / simplify
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/moments/MomentsScheduler.java:50`
- 置信度：high · kind：plan
- 证据：backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ProactiveChatScheduler.java:63 `public void tick() {`（114 行）与 backend/lianyu-service/src/main/java/com/lianyu/service/moments/MomentsScheduler.java:50 `public void tick() {`（114 行）结构逐段重合：characterIds 收集 → `Map<Long, Character> characterMap = new HashMap<>(); if (!characterIds.isEmpty()) { characterMapper.selectByIds(characterIds).forEach(...) }`（两文件 :74-78 逐字相同）→ convIds + `messageMapper.selectLatestUserByConversationIds(convIds)` 的同一段 Collector → `loadUserMessageCounts(convIds, since)`（ProactiveChatScheduler.java:272 与 MomentsScheduler.java:153 各复制一份，逐字相同）→ `weighted.sort(Comparator.comparingDouble(X::effectiveP).reversed())` → quota 循环里 `if (ThreadLocalRandom.current().nextDouble() > item.effectiveP()) continue;` 与只 log.debug 的 try/catch（ProactiveChatScheduler.java:152、MomentsScheduler.java:131）。两个候选 record 同构：ProactiveChatScheduler.java:355 `ScoredCandidate`、MomentsScheduler.java:169 `WeightedCandidate`。配置口径也分裂：ProactiveChatScheduler 为 lianyu.chat.proactive.scan-interval-ms:30000 / max-conversations-per-run:2 / scan-limit:40，MomentsScheduler 为 lianyu.moments.scan-interval-ms:300000 / max-posts-per-run:1 / trigger-probability:0.12。
- 影响：「扫候选会话 → 按角色/亲密度打分 → 概率抽样 → 配额内逐条 try/catch」是同一件事，却有两份实现和两套命名（Scored/Weighted、quota/maxPosts、effectiveProb/effectiveP）。改一处限流口径（比如按用户维度去重）必须记得改两处，否则只修一半；两套 @Value 键名也让运维在配置文件里找不到统一开关。
- 修复摘要：抽出 CandidateScanner（装载 character/最新消息/消息计数）与 ProbabilityGate（排序 + 配额 + 抽样 + 逐条异常隔离），两个调度器只保留各自的打分与执行动作。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ProactiveChatScheduler.java、backend/lianyu-service/src/main/java/com/lianyu/service/moments/MomentsScheduler.java
- 建议测试：mvn -q -f backend/pom.xml test；mvn -q -f backend/pom.xml -pl lianyu-service -Dtest='*Scheduler*Test' test

### MNT-D8078 · [中] 角色广场把 4 语人设与整段 prompt 写进 2602 行 Java，形成第二份人设文案来源
- 专家 / section：maintainability / simplify
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/square/CharacterSquareCatalogGenshin.java:14`
- 置信度：high · kind：plan
- 证据：backend/lianyu-service/src/main/java/com/lianyu/service/square/ 下 8 个 Catalog 合计 2602 行：CharacterSquareCatalog.java 462、CharacterSquareCatalogGenshin.java 590、CharacterSquareCatalogDal.java 421、CharacterSquareCatalogBlueArchive.java 369、CharacterSquareCatalogReZero.java 326、CharacterSquareCatalogDanganronpa.java 227、CharacterSquareCatalogHonkai.java 61、CharacterSquareCatalogShizhong.java 61。每个角色都要手写 4 份 locale 条目 + 3 个 `promptZhX()/promptJaX()/promptEnX()` 文本块：CharacterSquareCatalogGenshin.java:14 `static void register(Map<String, Map<String, CharacterSquareCatalog.LocalePack>> map) {` 内注册 13 个角色（nahida/kokomi/furina/shenhe/hu_tao/yae_miko/nilou/klee/raiden/mavuika/noelle/zhongli/ayaka），:148 起是逐角色的 prompt 方法。同一记录存在两种构造方式：CharacterSquareCatalog.java 内直接 `new LocalePack(...)`，子 Catalog 用 `CharacterSquareCatalog.localePack(...)`。这些 prompt 经 backend/lianyu-service/src/main/java/com/lianyu/service/square/CharacterSquareService.java:378 `.promptTemplate(pack.prompt())` 交给 Python。
- 影响：AGENTS.md §2 规定 prompt 只允许出现在 backend-agent/app/prompts 与 Python 代码里；广场角色的整段人设却以 Java 字符串形式存在并由 Java 传给 Python，等于第二份 prompt 事实来源——改人设口径要同时改 Java 常量与 Python 模板，且 Java 侧无法参与 prompt golden。另外 2602 行手写数据（每角色 4 语 × ~20 字段 + 3 段 prompt）让代码评审实质上无法发现文案错误（漏一个 ja 字段照样编译通过）。
- 修复摘要：把广场角色从 Java 常量迁移成后端可读的数据文件（JSON/YAML，按角色一文件），Java 只保留加载与校验，prompt 字段交 Python 侧模板。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/square/CharacterSquareCatalog.java、backend/lianyu-service/src/main/java/com/lianyu/service/square/CharacterSquareCatalogGenshin.java、backend/lianyu-service/src/main/java/com/lianyu/service/square/CharacterSquareCatalogDal.java、backend/lianyu-service/src/main/java/com/lianyu/service/square/CharacterSquareCatalogBlueArchive.java、backend/lianyu-service/src/main/java/com/lianyu/service/square/CharacterSquareCatalogReZero.java、backend/lianyu-service/src/main/java/com/lianyu/service/square/CharacterSquareCatalogDanganronpa.java、backend/lianyu-service/src/main/java/com/lianyu/service/square/CharacterSquareService.java
- 建议测试：mvn -q -f backend/pom.xml test；scripts/check-java-ai-free.ps1 -Mode strict

### MNT-BAE80 · [中] electron/main.js 3077 行承载 71 个 IPC 通道，主进程已无清晰分区
- 专家 / section：maintainability / simplify
- 位置：`frontend/electron/main.js:1`
- 置信度：high · kind：plan
- 证据：frontend/electron/main.js 共 3077 行；`grep -c "ipcMain.handle(" frontend/electron/main.js` = 63，`grep -c "ipcMain.on("` = 8，合计 71 个通道；文件顶部 import 约 20 个本地模块（logger、exportTextFile、desktopSettings、authSessionStore、customTts、appearanceStore、desktopObserver、greetingAudio、lastChatModelStore、apiProxy、qqBridge、qqBridgeCoordinator 等）。同目录已有 64 个 js 模块，但通道注册仍集中在这一个文件里。
- 影响：新增一个 IPC 就得在 3000 行里找位置，通道的权限边界（preload 白名单 ↔ main 处理器）无法在一个视图内核对；该类文件也是合并冲突与「改 A 弄坏 B」的高发区。AGENTS.md 把 IPC 白名单视为安全边界，边界散落在大文件里会降低审查质量。
- 修复摘要：按域把 ipcMain 注册拆到 electron/ipc/*.js（窗口与托盘、聊天与导出、TTS/音频、QQ 桥、更新器），main.js 只保留装配顺序。
- 修复文件：frontend/electron/main.js、frontend/electron/preload.js
- 建议测试：npm --prefix frontend test；cd frontend && npx vite build

## 转交 deferred
- correctness → 未指定 · V57__relationship_trust_anomaly_repair.sql 只改 trust_score 不重算 phase：按 RelationshipPhaseResolver 对 trust=50、intimacy<=40 的推导结果仍是 FAMILIAR，与旧值一致，未构成缺陷，不报。
- correctness → 未指定 · Python 侧从不发送 pieces 帧（stream_chat.py 注释明确本层不产出 pieces），Java AgentSseParser 不解析 pieces 与现状一致，不算契约漂移。
- correctness → 未指定 · updater.js 用 x.y.z 纯数字比较版本，预发布后缀（-beta）会被解析成 NaN 而不提示更新；属边缘命名约定问题，未达上报门槛，转交 maintainability。
- correctness → 未指定 · TotpUtil.verify 用 String.equals 比较验证码（非恒定时间）、AdminAuthorizationService.require 每次请求两次 currentAdminId 查库：前者属安全面，后者是次要性能问题，均转交对应专家。
- correctness → 未指定 · frontend/electron/main.js（3078 行）与 installer 的 C# 工程只做结构扫描，IPC 参数校验、安装器回滚等未逐行核对。
- reliability → 未指定 · backend/lianyu-dao（111 文件，V7–V57 迁移，模块内零测试类）：SQL 与迁移正确性无自动化回归，建议由 correctness 专家评估，本轮未展开。
- reliability → 未指定 · backend/lianyu-app application.yml:71-80 声明 spring.rabbitmq.listener.simple.retry.enabled=true，但 RabbitMqConfig 手工 new 的 listener factory 未走 Boot configurer，重试拦截可能不生效；与 F2 同源，未单独成条。
- reliability → 未指定 · frontend/src 的 30 处 `.catch(() => {})`（qqBridge 轮询、notifications 刷新等）判定为刻意静默，未逐处验证其对用户可见性的真实影响。
- reliability → 未指定 · SseEmitter 的 SSE 超时（ChatTurnAdapter 构造参数 SSE_TIMEOUT_MS）与前端等待态之间的交互未实测，仅从代码推断。
- reliability → 未指定 · 4 条测试命令本轮未实际执行（只读审查，避免产生 target/、.pytest_cache 等构建产物）；F9/F10 的结论来自脚本与 CI 配置本身。
- maintainability → 未指定 · frontend/src/pages/LoginPage.vue（205 行）与 frontend/src/pages/RegisterPage.vue（258 行）行级相似度 0.74（difflib，去注释与空行）：表单校验与提交态可抽 composable，但两侧文案与 OTP 分支不同，属需要产品确认的重构，本次不单列。
- maintainability → 未指定 · backend/lianyu-admin/src/main/java/com/lianyu/admin/identity/AdminPermissionCatalog.java 9 行零引用（`grep -rn AdminPermissionCatalog --include=*.java backend` 排除自身为空）：影响极小，建议随下次权限改动顺手删除。
- maintainability → 未指定 · backend/scripts 下 8 个一次性迁移/修复脚本（dedupe-java-imports.ps1、fix-service-cross-imports.ps1、restore-service-imports.ps1、repackage-service.ps1、strip-utf8-bom.ps1、repair-flyway-v10.sql、repair-flyway-v14-checksum.sql、check-v10-state.sql）仅被 gitignored 的 .specstory 历史文档提到，与 scripts/ 的一次性脚本问题同类，合并处理即可。
- maintainability → 未指定 · 性能类可疑点（如 Agent 健康探测 3 秒缓存、定时任务每轮全量扫描）不在本专家范围，转交 perf。
- maintainability → 未指定 · AsrController 的 /api/asr/transcribe 端点在前端已无调用方，是否下线涉及对外契约，转交 correctness 判定。

> 这些不是当前专家的 finding，但必须写入 verdict.deferred，不能静默丢弃。

## 机器提示（仅供参考）
- 没有检测到明显的跨专家重复候选。

## 交给主编
1. 读本文件与原始 handoff；不要只依赖机器提示。
2. 合并重复项，保留最可信的证据；被合并的 ID 记入 verdict.dropped。
3. 写 `verdict.json`（schemaVersion 2、findings、coverage、dropped），再写 `report.md`。
4. 只有完整覆盖默认四模块时，才更新 `docs/logistics/latest.md` 与 `.logistics/latest.json`。
5. 工作区根目录：`C:\Users\hp\Desktop\LianYu-PC`；运行目录：`C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261105-094040-9bf511-master`。
