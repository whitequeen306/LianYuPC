# 后勤主编材料 · 20261104-184431-c1eee4

> 这是给主会话模型（主编）的候选材料，不是最终报告。
> 去重、定级、取舍、模块组织由主编决定；机器只负责收集与校验。

## 运行
- 根目录：`C:/Users/hp/Desktop/LianYu-PC`
- 范围：1100 个文件 / 12 个审查单元 / 135978 行
- 测试命令：（未检测到）

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
| u01 | backend/lianyu-service (1/3) | reviewed | reviewed | sampled | reviewed | missing |
| u02 | backend/lianyu-service (2/3) | reviewed | sampled | sampled | reviewed | missing |
| u03 | frontend/src (1/2) | sampled | sampled | sampled | reviewed | missing |
| u04 | scripts (1/2) | sampled | sampled | sampled | sampled | missing |
| u05 | backend/lianyu-dao | reviewed | sampled | reviewed | reviewed | missing |
| u06 | backend-agent | sampled | reviewed | reviewed | sampled | missing |
| u07 | frontend/src (2/2) | sampled | sampled | sampled | sampled | missing |
| u08 | backend/lianyu-web | reviewed | reviewed | reviewed | reviewed | missing |
| u09 | frontend/electron | sampled | reviewed | sampled | sampled | missing |
| u10 | 其他模块（(root)、.github、admin 等） | sampled | sampled | reviewed | reviewed | missing |
| u11 | backend（小模块合集 59 文件） | reviewed | reviewed | sampled | reviewed | missing |
| u12 | 其他模块（backend/lianyu-app、backend/lianyu-common、backend 等） | sampled | sampled | sampled | sampled | missing |

## 专家摘要
### correctness · ok
- 摘要：整仓 12 个单元全部过检（5 个逐文件精读、7 个按模式扫描后抽查关键文件），共 8 条发现：事务边界形同虚设（@Transactional 标在 protected 方法且被同类 this 自调用，共 7 处）、关系状态读改写无并发保护、主动消息调度里恒真的空闲判断让活跃度打分被架空、前端流式重试无幂等键会导致重复消息与双倍计费、发布解析接口对可空列用 Map.of 会 NPE、关系衰减每次丢弃不足一天的余量、消息 seq 只活在 Redis 与唯一键冲突、客户端中途断开时被截断的回复被当作完整回复落库。
- sections：logic=1 · state=1 · concurrency=1 · data-integrity=2 · contracts=1 · architecture=1 · edge-cases=1
- notes：只读审查：未修改任何源码。工具限制：该 shell 内没有 rg，改用 ops_grep；/tmp 不可写，验证均在仓库根内联执行。已核实并主动放弃的两个候选：V57 把 trust 100→50 后的 phase 残留（RelationshipPhaseResolver 在 trust>=45 一律 FAMILIAR，且 V57 只命中 intimacy<=40，相位仍可派生一致）、读取工具显示层把反斜杠翻倍造成的 sseParser 正则误报（用 repr 复核后源码正确）。u04/u06/u09/u10/u12 因体量按模式扫描后抽查，未逐文件通读。
- 发现数：8
- 转交其他专家：3 条
  - → 未指定 · 关系状态加载 waitForExistingState 里的 Thread.sleep 轮询（阻塞请求线程）与 MemoryWriter 抛 RuntimeException 触发 MQ 重试，属可靠性范畴，建议交给 reliability 专家。
  - → 未指定 · mapExistingByCharacterIds 按 characterId IN 全量拉取（无 user 过滤）以及 ProactiveChatScheduler 每 30 秒的多表加载，属性能范畴，建议交给 perf 专家。
  - → 未指定 · getNextSeq/clearMessages 中 'msg_seq:' 前缀 4 处硬编码、ProactiveMessageService 与其余 7 处 persist 方法写法不一致，属可维护性范畴，建议交给 maintainability 专家。

### security · ok
- 摘要：全项目安全审查（12 个单元 / 1100 文件）覆盖认证、授权与租户隔离、注入、浏览器安全、上传与 SSRF、密钥、加密、数据暴露、供应链九个面。最严重的问题是网关把 /api/agent/ 整段直连到 Python agent 容器，而 Python 侧只做「请求头存在才校验」的等值比较，导致 22 个 agent 端点（含 memory/write、memory/reconcile、voice/delete、chat-image、desktop-observe）可被未认证调用，可写入/覆盖任意 userId 的记忆并消耗平台自有付费多模态 Key；同一条通道还放任调用方自带的 baseUrl 出站，构成可从公网触发、可探测 mysql/redis/minio 等内网服务的 SSRF。其次是运维链路的缺失：更新清单的 Ed25519 验签公钥在出厂配置里仍是注释（等同于关闭验签）且未做代码签名，NapCat 控制面用硬编码口令 lianyupc 长期固定，主进程 custom-tts 通道会带着用户会话 token 去请求渲染层指定的任意 URL，Jasypt 在缺少 LIANYU_MASTER_KEY 时静默返回明文落库。核心业务面（Sa-Token 拦截、admin 会话二次校验、租户条件 SQL、上传白名单与重编码、公开文件对象键校验、参数化 SQL、DOMPurify、Electron 三项 webPreferences 与 egress 白名单）实测是干净的，未发现 SQL 注入、越权读写、XSS 落地或命令注入。
- sections：auth=2 · authz=0 · injection=1 · browser=1 · uploads=1 · secrets=2 · crypto=1 · exposure=0 · supply-chain=2
- notes：只读审查，未修改任何源码。所有 critical/high 结论均由源码行与可复现请求构成。injection/authz/exposure 三条计数为 0 是「已检查且未发现」，对应证据见 sections.notes：SQL 全参数化、无 ${} 拼接、唯一命令执行点无 shell；13/14 个控制器以登录主体取 userId、STOMP 订阅有归属校验、admin 权限逐方法 require；错误响应不回栈、actuator 仅 health/info 且不在网关路由、中间件端口全绑 127.0.0.1、API Key 只回掩码。sections 计数与 findings 逐项一致（auth 2、authz 0、injection 1、browser 1、uploads 1、secrets 2、crypto 1、exposure 0、supply-chain 2，共 10 条）。

各 section 覆盖说明（计数见 sections）：
- auth 2 条：SaTokenConfig 拦截 /api/**（排除登录/注册/图形验证码/公开文件），admin 前缀由 AdminSecurityConfig + admin_session 表二次校验，admin 登录有 bcrypt(12)/TOTP/5 次失败锁 15 分钟。问题一为网关分流绕过 agent 认证，问题二为改密不吊销其它会话且 token 30 天无空闲过期。
- authz 0 条：14 个 web 控制器中 13 个从 StpUtil.getLoginIdAsLong() 取主体、仅 PublicFileController 公开（设计如此）；Memory/Conversation/ApiKeyVault 全部以 userId 作为查询条件；admin 侧 AdminAuthorizationService.require 覆盖 11 个控制器，SupportGrant 的 issue/redeem 也在服务层做权限校验；STOMP 订阅仅 /topic/group/{id} 需要归属校验且已实现，/queue 走 convertAndSendToUser 按会话主体投递。未发现跨租户读写。
- injection 1 条：MyBatis XML 无 ${}（41 处 ${} 全在 pom.xml），jdbc 调用全部参数化 ?,MemoryQueryService 的 LIMIT/OFFSET 来自已 clamp 的 int；唯一命令执行点 CustomVoiceAudioTrimmer 用 ProcessBuilder 参数数组且无 shell。命中的是日志注入：X-Trace-Id 请求头原样进 MDC。
- browser 1 条：客户端 XSS 面已收敛：唯一 v-html 走 DOMPurify（sanitize.js 只允许 strong/em/b/i/u/span/br 与 class），LandingPage 为静态图标，useSquareDanmaku 只置空 innerHTML；Electron 主进程覆盖 CSP、setWindowOpenHandler 全 deny、外链仅白名单。运营台 admin 应用没有 CSP/安全头且把管理员 token 落 localStorage。
- uploads 1 条：上传侧干净：chat-image 5MB + jpeg/png/webp/gif 白名单、头像统一经 ImageUploadValidator 重编码为 PNG、对象名为 UUID、SAFE_OBJECT_KEY 正则挡住穿越、公开下载禁用 html/svg/js 并强制 nosniff。命中项是 SSRF：Java 保存期用 OutboundUrlValidator/SsrfPinningClientFactory 钉 IP，Python 侧对同一 baseUrl 无任何内网黑名单。
- secrets 2 条：仓库未提交真实密钥（secrets/ 与 certs/ 只有 README，.env 被忽略，application.yml 全部走 ${ENV:}）；API Key 查询接口只回显 maskApiKey 掩码；Java/Python 日志未打印 token 或 apiKey。命中项为 NapCat 硬编码口令与 custom-tts 通道的 Bearer 令牌外发。
- crypto 1 条：Jasypt 算法为 PBEWITHHMACSHA512ANDAES_256 + RandomSalt/RandomIv + 多版本轮换，方向正确；缺陷在缺 key 时 fail-open 返回明文。此外未发现 MD5/SHA-1/DES/ECB 或 new Random() 用于安全场景。
- exposure 0 条：错误响应只回错误码与业务文案，未回栈轨迹；actuator 仅暴露 health/info/agentshadow 且不在网关路由内；prod 关闭 knife4j/springdoc；mysql/redis/rabbitmq/milvus/minio 端口全部绑 127.0.0.1；公开文件桶只按对象键白名单下发且不可枚举（UUID）。agent 未认证导致的数据暴露已计入 auth 条，不重复计数。
- supply-chain 2 条：NapCat 下载有 out-of-band sha256 锚点强校验，Electron 有 SPKI 证书钉扎，backend-agent 用 uv --frozen + lock 构建，CI 含 osv-scanner。弱项为更新清单验签默认关闭（出厂无公钥）+ 无代码签名，以及 CI 第三方 action 只用 tag 未钉 SHA。
- 发现数：10
- 转交其他专家：2 条
  - → correctness · backend/lianyu-service/src/main/java/com/lianyu/service/auth/impl/AuthServiceImpl.java:registerInTx · register 幂等登录的用户名枚举取舍：AuthServiceImpl.registerInTx 在用户名已存在且密码匹配时直接登录（注释「幂等重登」），使注册接口可探测用户名是否存在；这既是逻辑取舍也涉及是否保留该行为，交由 correctness/产品确认后再评估是否算安全缺陷。
  - → reliability · backend/lianyu-web/src/main/java/com/lianyu/web/config/WebSocketConfig.java:143 · WS 异常文本回显：WebSocketConfig 把内部异常文本拼进「认证失败：」回给客户端，属信息暴露但影响极小，且修法与异常传播策略相关，交由 reliability/correctness 一并处理。

### reliability · ok
- 摘要：按 12 个单元做了一次全项目可靠性（错误处理 / 测试）审查：先按 scope 文件清单驱动正则扫描（吞异常、裸 except、无超时、跳过测试、空断言），再逐单元读关键文件。整体结论是主干链路相当克制：SSE 收尾（SseChatStreamHelper / AgentStreamBridge）、Python 熔断隔舱（resilience.py）、记忆 outbox relay、MCP/MQ 死信配置、Electron 更新器（idle 超时 / Content-Range 校验 / sha512 校验）都做了显式失败路径。仍然找到 7 条问题：最要紧的是 CI 完全不跑 backend-agent 的 pytest（49 个测试文件 / 492 个 def test_ 只在本地手动跑），会让 prompt 组装、工具循环、网关、SSE 契约这些最容易回归的代码无人拦；其次是 CI 前端 job 与 AGENTS.md §4 门禁不一致（vitest 未串行、没有 vite build）。代码侧 5 条：群聊回合的 runAsync 没有兜底（失败时既不回 TURN_ERROR 也不回 TURN_COMPLETE，前端永久停在输入中）、部署脚本把能力自检降级为 stderr 警告后仍打印 DEPLOY_DONE 并返回 0、主窗口渲染进程崩溃后只弹框不恢复、ASR 客户端在构造函数里读 @Value 导致 TCP 建连超时配置失效、memory/ai 死信队列只有声明没有消费与告警。
- sections：error-handling=5 · tests=2
- notes：覆盖：12 个单元全部有状态，无 skipped（u05/u06/u08/u10 为 reviewed，其余 8 个为 sampled，均做了全单元模式扫描 + 关键文件阅读）。方法：按 scope 的 1100 个文件清单驱动扫描（排除 frontend/dist 里的构建产物），模式包含空 catch / catch 后仅忽略 / 无裸 except / 无超时的 outbound 调用 / 测试跳过与空断言（tests_skipped=0、空断言 0）/ setInterval 与清理。未验证事项：scope.json 里没有 testCommand，所有测试类结论都标注为「未验证」；backend-agent 测试规模为实测（49 个文件、492 个 def test_），未真的执行 pytest；frontend vitest 并行 flake 依据 AGENTS.md §4 的既有陈述，本次未复现。已看过并判定不报的：SseChatStreamHelper 的 `catch (Exception ignored)`（complete 失败且客户端已断开，随后仍回调 onComplete 落库）、AgentStreamBridge.abort 的同名 catch、JasyptUtil 逐版本解密 catch（全失败会抛 IllegalStateException）、qqBridge.js 与 httpCore.js 的 catch（有兜底轮询与单飞刷新）、updater.js 的 withRetries（对 404/416 这类终态也重试，但只有 2 次且无退避，影响仅为延迟报错）、tool_loop.py 的窄 catch（工具实现内部已全面降级）、backend-agent/app/http_client.py 的工厂无默认超时（超时由调用方传入）。
- 发现数：7

### maintainability · ok
- 摘要：全项目 maintainability 审查（12 个单元全覆盖，1100 文件）。最值得先处理的三件事：(1) 前端 i18n 只有 zh/ja/en 三语在跑，frameworks 之外还躺着一个 529 行、从未被 import 的 zh-TW.js，且 12 个 memory.* 键只存在于这个死文件里，导致记忆页的「查看来源/收起来源/删除确认」在三种发布语言下都渲染成原始 key；(2) 后端有一批零引用代码——com.lianyu.service.rules 整套插件框架（89 行、0 实现）、AiResilience（144 行、doc 注释宣称的 bulkhead/熔断实际零调用）、CharacterSticker 实体+映射器+两张空表、以及前端 antiDebug.js/captureChatShareScreenshot.js（313 行，而 logger 注释与加固文档都宣称 antiDebug 在生效）；(3) 两处「同一条业务规则写两遍」已经出现漂移：气泡切分在 Java AssistantReplySplitter/ParenthesisUtils 与 JS assistantReplySplit.js/innerThoughtFilter.js 各一套对译实现（词表逐字复制、双份测试），agent 健康探测在 5 个 Adapter 里复制 5 份。另有 GroupChatService 1335 行/14 协作者的 god class、scripts/ 132 个文件里约 93 个一次性诊断脚本入库、admin 4 个零 import 依赖与权限键三处定义不同步。未发现需要上报的未用前端运行依赖（frontend/package.json 依赖均有真实消费者），backend-agent 无孤儿模块。
- sections：simplify=5 · cleanup=7
- notes：只读审查，未修改任何源码。方法：git ls-files 快照 + 三个自写扫描器（frontend js/vue/mjs、backend-agent py、backend java 的「除自己文件外是否有文本引用」图），把『无引用』清单按 Spring 注解/@RabbitListener/测试类/合法入口逐个排除后才上报，避免把靠扫描或路由存活的类误判为死代码；i18n 用 flatten 后的 595 键并集做四语比对。未列入 findings 的次要项：ManagementPage.vue:9-10 对 configs.users.columns 连续两次整体赋值（前者被瞬间覆盖）、frontend/src/i18n/locales/zh.js:327 的 chat.scrollToBottom 无消费者、backend/lianyu-web 的 AgentWiringHealthCheck 与 5 份 reachable() 口径重叠。performance 类问题未报（一律属 perf 专家）。
- 发现数：12

### perf · not-selected
- 可选专家，本次未选择（不是缺失）。

## 候选 findings
### COR-CDAFE · [中] @Transactional 标在 protected 方法上且被同类 this 自调用，7 处声明的事务从不生效
- 专家 / section：correctness / architecture
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/character/CharacterService.java:101`
- 置信度：high · kind：plan
- 证据：CharacterService.java:88 注释写着「故意不加 @Transactional：…落库走 persistNewCharacter 事务」，但 101 行的 `@Transactional protected CharacterResponse persistNewCharacter(...)` 唯一调用点是同类 97 行的 `return persistNewCharacter(userId, request, settings);`。Spring 事务默认走代理，同类自调用不经代理；仓库内也没有 AspectJ 织入配置（backend 源码与 pom 中搜不到 @EnableTransactionManagement(mode=ASPECTJ) 或 aspectjweaver）。同仓库 ProactiveMessageService.java:141 声明 `private ProactiveMessageService self;`、238/337/617/697 用 `self.persistXxx(...)` 才是能穿过代理的写法。同型问题另有 6 处：AuthServiceImpl.java:40（registerInTx）、AuthServiceImpl.java:122（persistAvatarUrl）、CharacterDiaryService.java:378（persistDiary）、VoiceCallService.java:167（persistVoiceTurn）、CharacterSquareService.java:231（persistSquareCharacter）、CustomVoiceService.java:94（persistUpsert）。
- 影响：VoiceCallService.persistVoiceTurn（186-207 行）在同一个「事务」里连插用户消息和角色消息两条记录：没有事务时第二条插入失败（例如 seq 撞唯一键）会留下一条孤立的用户消息，并且方法已经抛错。CharacterService 的注释更危险——它让后来者相信存在一个实际不存在的事务边界，任何在 persistNewCharacter 里新增的第二条写入都会静默失去原子性。
- 修复摘要：按 ProactiveMessageService 的既有写法补齐自注入，让这 7 个 persist 方法真正穿过代理；同时改正在说谎的注释，并加一条扫描测试防止再退化。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/character/CharacterService.java、backend/lianyu-service/src/main/java/com/lianyu/service/auth/impl/AuthServiceImpl.java、backend/lianyu-service/src/main/java/com/lianyu/service/character/CharacterDiaryService.java、backend/lianyu-service/src/main/java/com/lianyu/service/conversation/VoiceCallService.java、backend/lianyu-service/src/main/java/com/lianyu/service/square/CharacterSquareService.java、backend/lianyu-service/src/main/java/com/lianyu/service/voice/CustomVoiceService.java
- 建议测试：（无）

### COR-316C1 · [中] 关系状态是「读-改-写」且无事务/版本/行锁，同一角色的并发回合会静默丢掉一次更新
- 专家 / section：correctness / concurrency
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/relationship/RelationshipStateService.java:132`
- 置信度：high · kind：plan
- 证据：recordUserTurn 的写回路径是 130-133 行：`applyEvents(state, events, now)` → `applyPresence(state, now)` → `relationshipStateMapper.updateById(state)` → `recordMilestones(...)`；state 来自同一方法开头的 loadOrCreateState 读取，整个类没有任何 @Transactional，实体没有 version 字段，读的是普通 selectById 而不是 SELECT ... FOR UPDATE。recordAssistantTurn（163-165 行）形状相同。updateById 会把所有非空列整体写回。
- 影响：同一个 (user, character) 上两次回合交错时——例如流式回复还没结束时用户又发一条、或 ProactiveMessageService 的主动消息与用户回合撞上——后写者用旧快照覆盖前者的 trust/intimacy/security/anticipation 与 lastInteractionAt/streakDays。先发生的那次事件已经写进 relationship_event，但分数没算进去；相位又由分数派生，于是事件表与状态表长期对不上，且没有任何冲突可观测。
- 修复摘要：把分数更新做成原子增量（或加乐观锁重试），让并发回合的两次事件都落到分数上。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/relationship/RelationshipStateService.java、backend/lianyu-dao/src/main/java/com/lianyu/dao/entity/RelationshipState.java、backend/lianyu-dao/src/main/resources/db/migration/V59__relationship_state_version.sql
- 建议测试：（无）

### COR-770E7 · [中] 流式发送的网络重试没有幂等键，一次抖动会写出两条用户消息和两条 AI 回复
- 专家 / section：correctness / data-integrity
- 位置：`frontend/src/pages/ChatPage.vue:1439`
- 置信度：high · kind：plan
- 证据：ChatPage.vue:1420-1431 的 attemptStream 把 `sendMessageStream(...)`（fetch POST）和 `drainAssistantStream(response, {signal})`（读响应体）包在同一个 try 里；1437-1445 的 catch 在 isNetworkError(err) 为真时 `await sleep(800)` 后重新 `attemptStream()`，即重新 POST 同一段文本。useStreamAbort.js:8-15 的 isNetworkError 对任何 `err.name === 'TypeError'` 直接返回 true——浏览器在流读到一半连接被重置时抛的正是 TypeError，于是「服务端已收下并开始生成」的情况也会重发。服务端 ConversationService.java:328-330 在调用模型之前无条件 `messageMapper.insert(turn.userMsg())`，而 SendMessageRequest.java 里没有客户端消息 ID 字段（只有 provider/model/content/imageUrl 等），前端 api/conversation.js:67-83 也不带任何幂等头或字段，服务端无法识别这是同一条消息。
- 影响：家庭网络抖动或 Wi-Fi 切换时，用户会看到自己那句话出现两次、角色回两段，token 计费翻倍，关系分数（recordUserTurn）与记忆摘要也会被算两遍；这类重复在对话历史里无法与用户真的重发区分。
- 修复摘要：前端为每条草稿生成稳定的 clientMessageId，后端按 (user_id, client_message_id) 去重；同时把重试范围收窄到「还没拿到响应」的阶段。
- 修复文件：frontend/src/pages/ChatPage.vue、frontend/src/api/conversation.js、backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java、backend/lianyu-service/src/main/java/com/lianyu/service/dto/SendMessageRequest.java、backend/lianyu-dao/src/main/resources/db/migration/V58__message_client_id.sql
- 建议测试：（无）

### COR-B0734 · [中] 主动消息调度里的空闲判断恒为真，EngagementFrequencyScorer 的活跃度/新鲜度打分被架空
- 专家 / section：correctness / logic
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ProactiveChatScheduler.java:128`
- 置信度：high · kind：plan
- 证据：112-122 行先用 isEligible(...) 过滤，而 isEligible 的第 314 行是 `return !lastActivityAt.isAfter(LocalDateTime.now().minusMinutes(Math.max(1, effectiveMinIdleMinutes)));`，已经保证空闲时长 >= effectiveMinIdle。随后 127-131 行又算 `long idleMinutes = ChronoUnit.MINUTES.between(lastActivityAt, now); if (idleMinutes >= effectiveMinIdle) { effectiveProb = Math.max(effectiveProb, behavior.triggerProbability() * engagementScorer.personalityMultiplier(state, relationship.phase())); }`，这个 if 在到达时恒为真。
- 影响：保底值恒定生效：125-126 行 EngagementFrequencyScorer.proactiveTriggerProbability 里按用户近期消息量（activityBoost）与时间新鲜度（recencyBoost，72 小时甜点区）算出的连续分只在高于保底时才有意义。半小时前刚聊过的用户和十小时没说话的用户拿到同一个保底触发率，调 activity/recency 权重对线上行为几乎无效，且没有任何日志或配置说明。
- 修复摘要：让保底只作为可观测的下限参与，或删掉这段恒真分支，恢复活跃度/新鲜度的实际作用。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ProactiveChatScheduler.java、backend/lianyu-service/src/main/java/com/lianyu/service/conversation/EngagementFrequencyScorer.java
- 建议测试：（无）

### COR-5B82B · [中] 更新解析接口对可空列用 Map.of 组装响应，notes/sha512 为 NULL 时直接 NPE 500
- 专家 / section：correctness / contracts
- 位置：`backend/lianyu-admin/src/main/java/com/lianyu/admin/release/ReleaseResolverService.java:21`
- 置信度：high · kind：direct
- 证据：ReleaseResolverService.java:21 是 `return Map.of("version", row.get("version"), "url", row.get("package_url"), "sha512", row.get("sha512"), "size", row.get("package_size"), "mandatory", row.get("mandatory"), "notes", row.get("notes"));`，19 行的查询明确取了 sha512 与 notes。V51__admin_management_schema.sql:85 是 `sha512 CHAR(128) NULL`、:88 是 `notes TEXT NULL`；ReleaseService.java:19 的 create(version, channel, notes, mandatory) 只写这几列，ReleaseController.java:20 在 body 没有 notes 时照原样传 null。java.util.Map.of 只要有任一 value 为 null 就抛 NullPointerException。
- 影响：只要某条 published/rollout 记录的 notes 或 sha512 为空（正常流程是先 create 再上传，上传前这两列就是空的），`GET /api/public/admin-release` 对整条渠道直接 500：不是返回「无更新」，而是所有人都拿不到解析结果。当前仓库内没有客户端调用这个公开端点（全仓搜 admin-release 无命中），所以影响面限于该接口本身与后续接入方，但故障形态是整渠道 500，排查成本高。
- 修复摘要：用允许 null 的 LinkedHashMap 组装响应，并在缺 package_url/sha512 时跳过该条记录而不是让整条渠道失败。
- 修复文件：backend/lianyu-admin/src/main/java/com/lianyu/admin/release/ReleaseResolverService.java
- 建议测试：（无）

### COR-7AD38 · [低] 关系衰减按整天计算后把 lastDecayAt 重置为 now，不足一天的余量每次都被丢掉
- 专家 / section：correctness / state
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/relationship/RelationshipStateService.java:221`
- 置信度：high · kind：plan
- 证据：applyDecay（192-226 行）用 `long days = ChronoUnit.DAYS.between(anchor, now);`（199 行）取整，days < 1 时返回 false 并保留 anchor；但只要 days >= 1，就用 `int elapsed = (int) Math.min(days, MAX_DECAY_DAYS);` 结算，并在 221 行 `state.setLastDecayAt(now);`。25 小时的空档按 1 天结算，剩下 1 小时被抹掉，下一次 anchor 变成 now。
- 影响：anticipation 每天 +3（208-209 行）与 intimacy 的每日衰减（210-214 行）在长期运行中系统性偏浅：一个每 25 小时才被扫描到的用户，100 天只会结算约 96 天，误差随时间累积且无法从数据反推。对「隔天上线」的典型节奏偏差约 4%。
- 修复摘要：结算后把 anchor 前移整天而不是重置到 now，让余量自然滚入下一轮。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/relationship/RelationshipStateService.java
- 建议测试：（无）

### COR-A3CDE · [低] 消息 seq 计数器只存在于 Redis，与 message 表唯一键冲突时会持续发送失败
- 专家 / section：correctness / edge-cases
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java:483`
- 置信度：medium · kind：plan
- 证据：getNextSeq（479 行）→ reserveSeqBlock（483-487 行）：`Long lastSeq = redisTemplate.opsForValue().increment(SEQ_KEY_PREFIX + conversationId, safeCount); return lastSeq != null ? lastSeq : safeCount;`，前缀 'msg_seq:'（72 行）没有从数据库 MAX(seq) 播种的兜底；而 V1__init_schema.sql:54 有 `UNIQUE KEY uk_conv_seq (conversation_id, seq)`。同一前缀在 CharacterService.java:60、GroupChatService.java:104、VoiceCallService.java:62 各自复制了一份。Redis 侧是 `--appendonly yes` + 命名卷（docker-compose.yml:271-283），所以触发条件是 FLUSHALL、卷丢失或换实例——不是日常路径，但一旦发生计数器会从头开始。
- 影响：计数器归零后新消息的 seq 撞上历史行的唯一键，messageMapper.insert 抛 DuplicateKeyException：用户点发送直接失败，且要等计数器自然爬过历史最大 seq 才恢复（发一条失败一条）。群聊与语音通话用同一套前缀，问题一致。
- 修复摘要：让 seq 有数据库兜底：Redis 只作为加速层，缺失时用 MAX(seq) 播种。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java、backend/lianyu-service/src/main/java/com/lianyu/service/character/CharacterService.java、backend/lianyu-service/src/main/java/com/lianyu/service/conversation/GroupChatService.java、backend/lianyu-service/src/main/java/com/lianyu/service/conversation/VoiceCallService.java
- 建议测试：（无）

### COR-DFE20 · [低] 客户端中途断开时，被截断的半句话会以「完整回复」落库并触发推送与记忆摘要
- 专家 / section：correctness / data-integrity
- 位置：`backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentStreamBridge.java:138`
- 置信度：medium · kind：plan
- 证据：AgentStreamBridge.java:73-88 在向客户端 emitter 转发时捕获 IOException 并调用 abort()；abort()（126-140 行）算出 `String partial = lastReplacement != null ? lastReplacement : content.toString();` 然后 `callback.onComplete(partial, null);`——错误位传的是 null。ConversationService.java:377-382 的 onComplete 只在 `error != null && !isClientStreamDisconnect(error)` 时跳过落库，null 让它一路走到 383-405 行：saveAssistantReplies 落库、recordAssistantTurn、memoryWriter.enqueueSummary、notificationService.notifyAssistantMessage。
- 影响：用户在生成中途关掉聊天页或切走窗口时，落库的是尚未结束的半句话，但它与正常回复在库里没有区别：会进入最近 30 条上下文、被写进记忆摘要、还会触发一条「角色给你发来消息」的推送。用户回来会看到一条语义截断的回复，记忆里也多了一句残句。
- 修复摘要：区分「客户端断开」与「正常完成」：允许保留已生成内容，但要打标记，并跳过推送与记忆摘要。
- 修复文件：backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentStreamBridge.java、backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java、backend/lianyu-service/src/main/java/com/lianyu/service/agent/AgentStreamCallback.java
- 建议测试：（无）

### SEC-FCAD9 · [严重] api-gateway 把 /api/agent/ 整段直连 Python，未认证即可写他人记忆并消耗平台自有模型 Key
- 专家 / section：security / auth
- 位置：`deploy/api-gateway/nginx.conf:252`
- 置信度：high · kind：plan
- 证据：nginx 的 `location /api/agent/`（252-274 行）只有限流与 `set $agent_upstream http://agent:8001; rewrite ^/api(/agent/.*)$ $1 break; proxy_pass $agent_upstream;`，整段没有 Sa-Token 校验、没有剥离或校验 X-User-Id；而 Python 侧的校验是「头在才校验」：backend-agent/app/main.py:150 `if user_id_header and str(body.user_id) != user_id_header: raise HTTPException(status_code=403, detail="user mismatch")`，memory_write 内同款判断（main.py:987 附近）后直接 main.py:1003 `write_memories(body.user_id, body.character_id, inputs)`。省略 X-User-Id 头即完全跳过校验；grep middleware/X-Internal/interceptor 在 backend-agent/app 下无任何结果，agent 自身零鉴权。该 location 也不被任何客户端需要：Java 走 docker-compose.yml:60 `LIANYU_AGENT_BASE_URL: ${LIANYU_AGENT_BASE_URL:-http://agent-lb:8001}`，frontend/、admin/、backend/ 中 grep `api/agent` 无命中。可复现：`curl -k -X POST https://<gateway>/api/agent/memory/write -H 'Content-Type: application/json' -d '{"userId":1,"characterId":1,"memories":[{"summary":"x"}]}'` 不需要任何 token（注意正则 location `~ ^/api/.*stream` 会先吃掉 chat-stream/chat-turn/stream/tts/stream 等带 stream 的路径转给 Java，所以直达的是 desktop-observe、chat-image、text/chat、prompt/*、character/*、memory/*、proactive-turn、voice/*、tts/synthesize 等 22 个端点）。加重：main.py:741 `vision_key = body.vision_api_key or settings.multimodal_api_key`，未认证请求可回落到平台自有付费多模态 Key；observe_limiter 以 body.user_id 为键，改值即可绕过限流。
- 影响：公网任意人可对任意 userId 写入/合并记忆、触发 memory/reconcile 全量对账写库、删除他人克隆音色、生成/导入角色、无限消耗平台付费多模态与文本模型额度；配合下一条可进一步探测内网。这是本项目当前最严重的未认证写入面。
- 修复摘要：关掉公网到 agent 的直达通道，并把 Python 侧改成 fail-closed 的内部鉴权，不再依赖调用方自觉携带用户头。
- 修复文件：deploy/api-gateway/nginx.conf、backend-agent/app/main.py、backend-agent/app/config.py、backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentTextStreamAdapter.java、backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentRuntimeClient.java
- 建议测试：backend-agent/tests/test_internal_auth.py：缺/错 secret → 401，userId 不匹配 → 403，正常链路 → 200；网关回归：对 /api/agent/memory/write 直接请求应返回 404/401 而非进入 agent

### SEC-AA4AD · [高] Python 网关对调用方自带的 baseUrl 无内网黑名单，可探测 docker 内网服务并绕过 Java 的保存期校验
- 专家 / section：security / uploads
- 位置：`backend-agent/app/gateway.py:288`
- 置信度：high · kind：plan
- 证据：backend-agent/app/gateway.py:288 `url = normalize_openai_base_url(base_url)` 后直接 :302 `async with client.stream("POST", url, json=payload, headers=headers)`；backend-agent/app/http_client.py:27 的 new_client() 只设置 timeout/UA/`local_address="0.0.0.0"`，没有任何 IP 或主机名黑名单（注释声称「对齐 Java 侧 SsrfPinningClientFactory 红线」，实际只对齐了超时）。baseUrl 完全来自请求体：main.py:284 `base_url: str = Field(alias="baseUrl")`、main.py:187 `text_base_url`。Java 侧确实有防护（backend/lianyu-common/src/main/java/com/lianyu/common/util/OutboundUrlValidator.java 阻内网、backend/lianyu-ai/src/main/java/com/lianyu/ai/SsrfPinningClientFactory.java:28 说明用钉 IP 防 DNS 重绑定），但那只发生在 vault 保存时，真正出站在另一个进程重新解析域名，保存期校验既不能覆盖直连 agent 的请求（上一条），也不能防 DNS 重绑定。可复现：`curl -k -X POST https://<gateway>/api/agent/memory/extract-write -d '{"userId":1,"characterId":1,"textBaseUrl":"http://mysql:3306/v1","textApiKey":"x"}'`（无 token），agent 会向 mysql:3306 发起请求并按连接行为返回不同错误；把主机换成 redis:6379、minio:9000、milvus:19530 同理。
- 影响：从公网触发、以 agent 容器身份访问 docker 内网：内网端口探测、对内网 HTTP 服务发任意请求（含带 body 的 POST）、绕开 Java 的域名/IP 校验与钉 IP 机制；配合上一条的未认证入口危害进一步放大。
- 修复摘要：在 Python 出站入口做与 Java 等价的地址校验（拒绝私网/环回/链路本地/元数据地址，并在连接时钉住解析结果），并让 Java 与 Python 共用同一套判定用例。
- 修复文件：backend-agent/app/http_client.py、backend-agent/app/gateway.py、docker-compose.yml
- 建议测试：backend-agent/tests/test_outbound_url_guard.py：私网/环回/元数据地址必须抛错且不发起连接；respx 用例：合法公网地址仍能完成一次 stream 调用

### SEC-29B63 · [中] 客户端更新清单的 Ed25519 验签默认关闭（出厂无公钥）且安装包未做代码签名
- 专家 / section：security / supply-chain
- 位置：`frontend/electron/updater/updater.js:83`
- 置信度：high · kind：plan
- 证据：frontend/electron/updater/updater.js:82-86 `const publicKeyBase64 = resolveManifestPublicKey(); if (!publicKeyBase64) { ... logger.warn('updater', 'latest.yml signature check disabled: no updateManifestPublicKey'); ... }` —— 无公钥时直接放行，只校验 latest.yml 自带的 sha512（清单与包同源，改清单即可同步改哈希）。而 frontend/.env.production.cloud 第 12 行仍是注释 `# VITE_LIANYU_UPDATE_PUBKEY=`（同文件只配了 API_ORIGIN/UPDATE_ORIGIN/CERT_FINGERPRINT），即出厂构建没有公钥；服务端侧 deploy/api-gateway/nginx.conf:81 已经有 `/api/public/files/updates/latest.yml.sig` 路由，说明签名链路只差客户端开关。前端 frontend/package.json:82 另有 `"signAndEditExecutable": false`，安装包没有 Authenticode 签名，Windows 侧无法独立校验发布者。
- 影响：更新通道的完整性依赖 TLS 证书钉扎 + 更新源（MinIO/主机）本身可信；一旦更新源、发布脚本或 CI 凭据被拿下，攻击者可推送任意安装包，客户端会照单全收并执行，属于可升级为全量投毒的高价值供应链面。
- 修复摘要：按既有的上线顺序把清单验签真正打开（先部署 .sig 路由与带签名清单，再取消公钥注释并重打客户端），并补 Windows 代码签名以形成第二道校验。
- 修复文件：frontend/electron/updater/updater.js、frontend/electron/updater/manifestSignature.js、frontend/.env.production.cloud、frontend/package.json、scripts/sign_update_manifest.py
- 建议测试：frontend/electron/updater/__tests__：无公钥 + 生产模式 → 启动失败；签名不匹配 → 拒绝更新；发布脚本回归：electron:release 产出的 latest.yml 与 .sig 成对上传

### SEC-5E409 · [中] custom-tts IPC 把用户会话 token 当 Authorization 发给渲染层指定的任意 URL
- 专家 / section：security / secrets
- 位置：`frontend/electron/main.js:2634`
- 置信度：high · kind：direct
- 证据：frontend/electron/main.js:2634 `const token = session?.token || ''`，:2635 `let refUrl = payload?.refAudioUrl || ''`，仅当以 `/` 开头才拼到 apiOrigin，否则原样使用，:2639 交给 synthesizeSovits；frontend/electron/customTts.js:38-39 `if (token) headers.Authorization = `Bearer ${token}`; const resp = await fetch(url, { headers })`。整条路径不经过 apiProxy.js 的 isAllowedEgressUrl/sanitizeEgressHeaders 白名单（那是 `api:request` 通道专用的），因此渲染层只要能让主进程执行一次 `custom-tts:sovits`，就能把用户 30 天有效期的 lianyu-token 送到任意 https 主机。endpoint 参数本身受 customTts.js:13 isAllowedEndpoint 限制为回环/私网，但 refAudioUrl 无任何限制。
- 影响：渲染进程一旦被 XSS 或恶意第三方内容利用，可静默把用户身份令牌外发到攻击者服务器，绕过 secureToken.js 的「token 只驻留内存」设计；令牌 30 天有效且改密不吊销（见 auth 第二条），外发后长期可用。
- 修复摘要：把 refAudioUrl 收敛到 API 源站，复用既有 egress 白名单，不再对任意 URL 附带 Authorization 头。
- 修复文件：frontend/electron/main.js、frontend/electron/customTts.js、frontend/electron/apiProxy.js
- 建议测试：frontend/electron/__tests__/customTts.test.js：refAudioUrl 为 https://evil.example/x.wav 时不发请求、不带头；同源相对路径仍能正常下载

### SEC-7A856 · [中] NapCat 的 OneBot WS 与 WebUI 长年使用硬编码口令 lianyupc，本机任意进程可冒充用户收发 QQ 消息
- 专家 / section：security / secrets
- 位置：`frontend/electron/napcatRuntime/napcatConfig.js:72`
- 置信度：high · kind：plan
- 证据：frontend/electron/napcatRuntime/napcatConfig.js:70-75 `export function ensureNapCatConfig({ wsPort = DEFAULT_WS_PORT, wsToken = 'lianyupc', webuiPort = DEFAULT_WEBUI_PORT, webuiToken = 'lianyupc' } = {})`，注释写明「token 定死为 'lianyupc'（用户指定）」；:96-99 每次启动把已存在配置里的 `srv.token` 强制回写成该常量；frontend/electron/napcatRuntime/napcatHost.js:139-140 同样 `const wsToken = 'lianyupc'; const webuiToken = 'lianyupc'`。服务只绑 127.0.0.1（napcatConfig.js 中 host:'127.0.0.1'），但本机任意进程/脚本都能用这个公开口令连 `ws://127.0.0.1:<port>` 冒充已登录的 QQ 账号发送消息、以及登录 WebUI 改配置。
- 影响：QQ 桥接是「以用户身份说话」的能力面，固定口令使本机沙箱边界内（含被投毒的 npm 依赖、其他同用户进程、渲染层通过 IPC 触发的脚本）任何代码都能接管该账号的收发与配置，且口令在源码里公开可见。
- 修复摘要：改为每个安装生成一次高强度随机口令，用 safeStorage 持久化并在启动时注入，替换所有硬编码常量。
- 修复文件：frontend/electron/napcatRuntime/napcatConfig.js、frontend/electron/napcatRuntime/napcatHost.js
- 建议测试：frontend/electron/napcatRuntime/__tests__：首次生成的口令非固定值且写入两个 json；旧配置含 lianyupc 时被轮换

### SEC-DDDB1 · [中] 缺少 LIANYU_MASTER_KEY 时 JasyptUtil 直接返回明文，用户 API Key 会以明文落库
- 专家 / section：security / crypto
- 位置：`backend/lianyu-security/src/main/java/com/lianyu/security/util/JasyptUtil.java:65`
- 置信度：high · kind：plan
- 证据：JasyptUtil 构造器（20-27 行）在 `System.getenv("LIANYU_MASTER_KEY")` 为空时只 `log.warn("LIANYU_MASTER_KEY not set — Jasypt encryption disabled. Set env var before production use.")` 然后 return；:61-70 `public String encrypt(String plaintext) { ... if (currentVersion == null || encryptors.isEmpty()) { log.warn("No Jasypt encryptor available, returning plaintext"); return plaintext; } ... return plaintext; }`；decrypt 同样在 encryptors 为空时原样返回。写入方 backend/lianyu-service/src/main/java/com/lianyu/service/ai/ApiKeyVaultService.java 把 encrypt 结果直接存进 api_key_vault，因此该环境变量一旦漏配（.env.example:40 里它就是空值，脚本 _cloud_env_check.py 只是「检查」而非阻断启动），用户自带的模型 Key 会明文入库且接口仍显示为「已加密」。
- 影响：明文密钥会随数据库备份、慢查询日志、只读账号或 SQL 注入面直接泄露；且这种降级是静默的（只有一条 warn），运维很难发现，属于典型 fail-open。
- 修复摘要：把「无主密钥」从告警降级改为启动期硬失败，并给存量明文数据一条可验证的加密迁移路径。
- 修复文件：backend/lianyu-security/src/main/java/com/lianyu/security/util/JasyptUtil.java、backend/lianyu-service/src/main/java/com/lianyu/service/ai/ApiKeyVaultService.java、scripts/_cloud_env_check.py、.env.example
- 建议测试：JasyptUtilTest：无主密钥构造可加密对象时抛错；密文与明文形态互不兼容；ApiKeyVaultServiceTest：加密不可用时保存 Key 必须失败而不是落明文

### SEC-5AEC2 · [中] 运营台把管理员 token 存 localStorage，且 admin 页面没有 CSP 与安全响应头
- 专家 / section：security / browser
- 位置：`admin/src/stores/session.js:2`
- 置信度：high · kind：plan
- 证据：admin/src/stores/session.js:2 `state: () => ({ token: localStorage.getItem('lianyu-admin-token'), ... })`，login 时 `localStorage.setItem('lianyu-admin-token', token)`；admin/src/api.js:3 每次请求从 localStorage 取 token 塞进 `lianyu-token` 头。管理端 HTML（admin/index.html）只有一行 `<div id="app"></div><script type="module" src="/src/main.js"></script>`，没有 CSP、没有 X-Content-Type-Options/X-Frame-Options（后端 SecurityHeadersFilter 只作用于 /api/** 响应，不覆盖运营台静态页）。对比客户端侧已把 token 改为仅驻留内存（frontend/src/utils/secureToken.js 注释记录旧方案是「加密剧场」）。
- 影响：管理员会话有效期 8 小时且拥有用户管理、发版、TOTP 等权限；一旦运营台出现任何 XSS（含第三方依赖或富文本渲染），令牌可被直接读取并外带，且没有 CSP 降低利用难度，等于把管理员入口的凭证保护降到与普通页面同级。
- 修复摘要：把管理员令牌移出 localStorage（内存 + 会话级刷新），并给运营台补上与客户端同级的 CSP 与安全响应头。
- 修复文件：admin/src/stores/session.js、admin/src/api.js、admin/index.html、deploy/api-gateway/nginx.conf
- 建议测试：admin/src/__tests__：login 后 localStorage 无 lianyu-admin-token；CSP 回归：构建产物在无 unsafe-inline 下可正常挂载

### SEC-E41BA · [中] 改密码不吊销其它会话，且 token 30 天有效、无空闲过期
- 专家 / section：security / auth
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/auth/impl/AuthServiceImpl.java:154`
- 置信度：high · kind：direct
- 证据：AuthServiceImpl.changePassword（154-162 行）只做 `passwordEncoder.matches(old)` → `user.setPasswordHash(passwordEncoder.encode(new))` → `userMapper.updateById(user)` → log，之后没有任何 `StpUtil.logout(userId)`/kickout/令牌版本号自增；全仓库 grep `StpUtil.logout` 只有当前会话登出（AuthServiceImpl:85-86、AdminAuthService:53）与 admin 会话失效兜底。配合 backend/lianyu-app/src/main/resources/application.yml:133-140 的 `timeout: 2592000`（30 天）、`active-timeout: -1`（不因空闲冻结）、`is-concurrent: true`，被盗令牌在用户改密后仍可使用长达 30 天。
- 影响：「改密码」是用户对账号被盗的唯一自救动作，但目前它无法让攻击者下线；结合 custom-tts 令牌外发与运营台令牌落盘两条，令牌一旦泄露就是长期可用凭据。
- 修复摘要：改密成功后立刻让该用户的其它会话失效，并给令牌加空闲过期作为兜底。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/auth/impl/AuthServiceImpl.java、backend/lianyu-web/src/main/java/com/lianyu/web/controller/AuthController.java、backend/lianyu-app/src/main/resources/application.yml
- 建议测试：AuthServiceTest：changePassword 后 StpUtil.getLoginIdByToken(old) 为 null；接口级：改密接口返回的新 token 可用、旧 token 401

### SEC-1EA4E · [低] X-Trace-Id 请求头原样写入 MDC，可伪造日志行并放大日志体积
- 专家 / section：security / injection
- 位置：`backend/lianyu-common/src/main/java/com/lianyu/common/util/TraceIdFilter.java:15`
- 置信度：high · kind：direct
- 证据：TraceIdFilter:13-17 直接 `String traceId = httpRequest.getHeader("X-Trace-Id");`，非空即 `MDC.put("traceId", traceId)`，无长度上限、无字符集校验、无换行剥离；日志格式 backend/lianyu-app/src/main/resources/application.yml:533 为 `... %clr([%X{traceId:-}]){cyan} ...`，即每条日志都会输出该值。攻击者可发送带 `\n` 的 X-Trace-Id 伪造后续日志行（例如伪造「登录成功」或审计记录），或发送超长值让每行日志膨胀数十 KB。
- 影响：日志是排障与审计的第一手证据，可被外部请求污染；配合运维台的日志导出功能，也可能把伪造内容带进排查结论。危害限于日志面，故定为低。
- 修复摘要：把外部传入的 traceId 当作不可信输入：格式不符或超长时丢弃并重新生成。
- 修复文件：backend/lianyu-common/src/main/java/com/lianyu/common/util/TraceIdFilter.java
- 建议测试：TraceIdFilterTest：X-Trace-Id 含 \n 或长度 200 时 MDC 中的值符合 UUID 形态

### SEC-C1FB2 · [低] CI 的第三方 action 只用可变 tag（v4/v2.0.3），未钉 commit SHA
- 专家 / section：security / supply-chain
- 位置：`.github/workflows/ci.yml:48`
- 置信度：high · kind：direct
- 证据：.github/workflows/ci.yml 中 `uses: actions/checkout@v4`（14、28、45 行）、`actions/setup-java@v4`（15）、`actions/setup-node@v4`（29）、`google/osv-scanner-action/osv-scanner-action@v2.0.3`（48），全部是可重新指向的 tag。第三方仓库（osv-scanner）的 tag 被改写时，流水线会静默执行新代码，而该 job 拥有仓库读权限与 GITHUB_TOKEN。
- 影响：供应链投毒面：上游 action 仓库被入侵或 tag 被移动即可在 CI 中执行任意代码，进而读取 token、篡改构建产物。危害依赖上游被攻陷，故定为低。
- 修复摘要：把 action 引用改为完整 commit SHA（保留 tag 作为注释），并显式声明最小 permissions。
- 修复文件：.github/workflows/ci.yml
- 建议测试：CI 跑通一次完整流水线，确认替换 SHA 后各 step 行为不变

### REL-433F0 · [高] CI 不跑 backend-agent 的 pytest，模型运行时回归无人拦
- 专家 / section：reliability / tests
- 位置：`.github/workflows/ci.yml:9`
- 置信度：high · kind：direct
- 证据：workflow 全文 55 行，jobs 下只有 backend（`cd backend && mvn -B -ntp test`）、frontend（npm ci + vitest）、deps-audit（OSV，continue-on-error: true）三个 job，没有任何 Python 步骤。而 backend-agent/tests 下有 49 个测试文件、492 个 def test_，AGENTS.md §4 把 `cd backend-agent && pytest`（当前 1222+）列为提交前必跑门禁。
- 影响：prompt 组装、工具循环、网关熔断、SSE 契约（backend-agent/app/stream_chat.py、tool_loop.py、gateway.py、resilience.py）这些最容易回归的代码只在本地手动跑测，CI 全绿并不能说明 Python 侧没坏；一旦有人在本地跳过 pytest，回归会一直到线上流式回复异常才暴露。
- 修复摘要：在 ci.yml 增加 python job：setup-python + 安装 backend-agent 依赖 + 执行 pytest，并设为必需检查。
- 修复文件：.github/workflows/ci.yml
- 建议测试：（无）

### REL-B9984 · [中] CI 前端 job 与 AGENTS.md §4 门禁不一致：vitest 未串行、没有 vite build 步骤
- 专家 / section：reliability / tests
- 位置：`.github/workflows/ci.yml:39`
- 置信度：high · kind：direct
- 证据：第 37-39 行 Steps 只有 `npm ci` 与 `run: npx vitest run`，缺少 AGENTS.md §4 要求的 `--no-file-parallelism`（原文理由：「既有 updater 并发用例在满载并行下会偶发红，属已知 flake」，对应用例在 frontend/electron/updater/__tests__/updater.test.js）；同一节还列了 `npx vite build` 门禁，但 job 里完全没有构建步骤。
- 影响：CI 用与约定不同的方式跑同一批用例：一次并行 flake 就让流水线变红，团队逐步学会忽略红灯，真正的回归被淹没；同时构建期错误（坏 import、缺失导出、打包配置问题）在 CI 完全不暴露，只在发版跑 electron-pack 时才炸，而此时已经发了一半。
- 修复摘要：把 CI 命令改成与仓库门禁一致的串行 vitest，并补一步 vite build。
- 修复文件：.github/workflows/ci.yml
- 建议测试：（无）

### REL-74C87 · [中] 群聊回合的 runAsync 没有兜底，生成链路抛错时 TURN_ERROR/TURN_COMPLETE 都不会下发
- 专家 / section：reliability / error-handling
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/conversation/GroupChatService.java:370`
- 置信度：high · kind：direct
- 证据：第 370 行 `CompletableFuture.runAsync(() -> { ... }, taskExecutor)` 的返回值被直接丢弃，lambda 体内没有 try/catch：第 373/420/440 行的 `redisTemplate.opsForValue().get(turnKey)`、`sendToGroup(...)`、`persistAndBroadcastReplies(...)` 任一抛异常，该 future 就以异常结束且无人观察；第 414 行的 `catch (Exception ignored) { /* handled in generateCharacterReply */ }` 只覆盖 `f.join()`（单个角色生成），管不到外层 lambda。因此终点帧（第 442 行 TURN_ERROR、第 449 行 TURN_COMPLETE）不会下发。前端 frontend/src/pages/GroupChatPage.vue:534-540 只在收到 TURN_ERROR/TURN_COMPLETE/ERROR 时清 streamingChar，页面无本地超时兜底。
- 影响：Redis 抖动或广播失败时，用户已看到自己的消息上屏，之后既没有角色回复也没有任何错误提示，群聊界面永久停在「正在输入」；服务端只留下一条未观察异常的线程日志甚至没有日志，无法定位是哪一轮、哪个会话失败。
- 修复摘要：给 runAsync 的 lambda 体加 try/catch(Exception)，失败时记录日志并补发 TURN_ERROR；同时对返回的 future 挂 exceptionally 做二次兜底。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/conversation/GroupChatService.java
- 建议测试：（无）

### REL-E7978 · [中] 部署脚本把能力自检降级为 stderr 警告，缺工具仍打印 DEPLOY_DONE 并返回 0
- 专家 / section：reliability / error-handling
- 位置：`scripts/_cloud_deploy_pull.py:154`
- 置信度：high · kind：plan
- 证据：第 149-158 行：`caps = run(client, "curl -s http://127.0.0.1:18001/agent/capabilities", quiet=True)` 之后，`if chat_hits < 4:` 与 `if desk_hits:` 都只 `print(..., file=sys.stderr)` 一条警告，不改变退出码；紧接着第 163 行仍然 `print("DEPLOY_DONE")` 并正常返回。同一段第 145 行 `docker logs lianyu-backend 2>&1 | grep -E 'all ports wired|ports missing'` 的输出也只打印、不判定。对照同函数第 130-143 行的健康自检：那一段全失败会 `raise SystemExit(56)`。
- 影响：agent 层拉到旧代码、聊天工具缺项（例如 get_weather 缺失，用户侧就是工具直接不可用）时，发版脚本仍以成功退出，local/ship-release.ps1 继续走 Electron 段并把问题夹带到线上；事后只有一次性的 stderr 输出可查，没有失败信号，也没有任何人会回看。
- 修复摘要：把部署后置自检分级：链路可用性相关（缺聊天工具、出现 desk_*、ports missing）视为失败并非零退出，纯提示信息保留为警告。
- 修复文件：scripts/_cloud_deploy_pull.py、local/ship-release.ps1
- 建议测试：（无）

### REL-3E00B · [中] 主窗口渲染进程崩溃后只弹一次错误框，不重载也不重建窗口
- 专家 / section：reliability / error-handling
- 位置：`frontend/electron/main.js:652`
- 置信度：medium · kind：plan
- 证据：第 652-660 行 `win.webContents.on('render-process-gone', (_event, details) => { log(...); if (label === 'main' && !isDev) { dialog.showErrorBox('YuNian', '界面进程异常退出，请重启应用。若反复出现请重新安装最新版本。') } })` 只有日志与弹框，没有 reload / 重建窗口 / 重启应用的分支。对照第 1753-1760 行的 quickChat 壳：那里 did-fail-load 会 `win.destroy()` 并清空 quickChatShell/quickChatShellReady/quickChatPendingShowId，说明本仓库已有重建窗口的清理路径可复用。
- 影响：托盘常驻应用渲染进程崩溃后，用户点掉对话框就只剩一个空白主窗口，托盘图标还在、快捷聊天也可能打不开，只能手动结束进程重启；崩溃只落本地日志，没有次数统计与上报，无法判断是偶发还是某版本必崩。
- 修复摘要：为 render-process-gone 增加带次数上限的自动恢复：先 reload，同一窗口连续崩溃再销毁重建，超过阈值才提示用户重启。
- 修复文件：frontend/electron/main.js
- 建议测试：（无）

### REL-81069 · [低] AsrStreamClient 在构造函数里读 @Value 字段，TCP 连接超时永远取 1000ms 兜底值
- 专家 / section：reliability / error-handling
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/ai/AsrStreamClient.java:54`
- 置信度：high · kind：direct
- 证据：第 49-50 行 `@Value("${lianyu.asr.stream-connect-timeout-ms:8000}") private int connectTimeoutMs;` 是字段注入；第 52-57 行构造函数里 `this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(Math.max(1000, connectTimeoutMs))).build();` 执行时字段尚未注入（默认 0），因此 TCP 建连超时恒为 1000ms。第 68 行 open() 里的 `Math.max(1000, connectTimeoutMs) + 2000L` 用的是注入后的值，两处语义不一致。
- 影响：配置项 lianyu.asr.stream-connect-timeout-ms 对 TCP 建连静默失效：ASR 容器慢启动或跨网段 RTT 偏高时，1s 建连失败会被当成「无法连接语音识别流」，语音通话在部署后一段时间内随机失败，且从日志看不出与配置有关（因为配置看起来是生效的）。
- 修复摘要：把连接超时改为构造参数注入（或改到 @PostConstruct 里建 HttpClient），让配置真正作用在建连上。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/ai/AsrStreamClient.java
- 建议测试：（无）

### REL-9DADC · [低] memory.summary.dlq / ai.background.dlq 只有声明没有消费者，被死信的任务无人重放也无人告警
- 专家 / section：reliability / error-handling
- 位置：`backend/lianyu-web/src/main/java/com/lianyu/web/config/RabbitMqConfig.java:65`
- 置信度：medium · kind：plan
- 证据：第 54-66 行给 memorySummaryQueue 配了 x-dead-letter-exchange/x-dead-letter-routing-key 并声明 memorySummaryDeadLetterQueue（memory.summary.dlq），第 136-140 行工厂设了 `setDefaultRequeueRejected(false)`（注释写明「失败进 DLQ，避免 poison message 无限重试」）；但全仓库 @RabbitListener 只有三处（MemoryConsumer 的 memory.summary.queue、AiBackgroundConsumer、NotificationPushConsumer），没有任何代码监听 *.dlq，也没有对应的积压指标或重放脚本。MemoryWriter.processSummary 第 133-138 行 catch 后 log.error 并重新抛出，消息随即进 DLQ。
- 影响：记忆抽取失败（模型超时、返回非法结构、DB 冲突）后这条对话的记忆就永久停在 DLQ：语义检索查不到、UI 没有任何异常、也没有计数指标，只剩一条 log.error。用户表现为「角色忘了刚说的话」，排障时也没有入口知道死了多少条、死在哪一步。
- 修复摘要：给死信链路补最小可观测与重放入口（积压指标/巡检日志 + 人工重放脚本），并明确失败策略。
- 修复文件：backend/lianyu-web/src/main/java/com/lianyu/web/config/RabbitMqConfig.java、backend/lianyu-service/src/main/java/com/lianyu/service/memory/MemoryWriter.java
- 建议测试：（无）

### MNT-56223 · [中] zh-TW 语言包 529 行从未被 import，四语键已漂移，记忆页显示原始 key
- 专家 / section：maintainability / cleanup
- 位置：`frontend/src/i18n/locales/zh-TW.js:1`
- 置信度：high · kind：plan
- 证据：frontend/src/i18n/index.js:7-9 只 `import zh from './locales/zh'` / `ja` / `en`，第 17 行 `messages: { zh, ja, en }`；frontend/src/constants/language.js:3 `export const SUPPORTED_LANGUAGES = ['zh', 'ja', 'en']`。zh-TW.js 529 行/588 键，全仓 `git grep -n "locales/"` 只有上面三次 import，无人加载它。按 595 个并集键做四语比对：21 个键不齐全；其中 12 个 memory.* 键（deleteConfirmTitle / deleteConfirmMessage / viewSources / collapseSources / count / deleted / roleUser / roleAssistant / roleIndex / allCharacters / emptyDesc / deleteConfirmTitle）**只存在于 zh-TW.js**，而 frontend/src/pages/MemoryPage.vue:76 调 `t('memory.collapseSources') : t('memory.viewSources', { count: mem.sourceMsgIds.length })`、:188 调 `ElMessageBox.confirm(t('memory.deleteConfirmMessage'), t('memory.deleteConfirmTitle'))`。header.logoutConfirm / header.changePassword / header.apiSettings 只在 zh/en（AppHeader.vue:155 使用），zh-TW/ja 缺失；qqBridge.binding.providerRequired 缺 zh-TW/ja；language.zh-TW 只在 zh-TW.js 里。另外 chat.scrollToBottom 仅 zh.js:327 有、且无任何 `t()` 消费者。
- 影响：发布版只有 zh/ja/en 三种语言，zh-TW.js 是纯死配置却看起来像第四语；jue-i18n 缺键会回落 fallbackLocale('zh')，而 zh 也没有 memory.* 这 12 个键，所以记忆页「查看来源/收起来源/删除确认」在三种发布语言下都渲染成 `memory.viewSources` 这样的原始 key。AGENTS.md §3.2「i18n 四语齐，缺一份就视为未完成」在代码里不成立——只有三语，且没有键集合一致性门禁。
- 修复摘要：先决定 zh-TW 去留，再把 memory.* / header.* 等缺失键补齐，并加一条四语键集合相等的单测把它钉住。
- 修复文件：frontend/src/i18n/index.js、frontend/src/i18n/locales/zh.js、frontend/src/i18n/locales/zh-TW.js、frontend/src/i18n/locales/en.js、frontend/src/i18n/locales/ja.js、frontend/src/constants/language.js、frontend/src/pages/MemoryPage.vue
- 建议测试：新增 frontend/src/i18n/__tests__/localeKeys.test.js（四语键集合相等）；cd frontend && npx vitest run --no-file-parallelism

### MNT-EF790 · [中] com.lianyu.service.rules 整套插件框架 89 行：0 个实现、0 个调用
- 专家 / section：maintainability / cleanup
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/rules/PromptRuleEngine.java:8`
- 置信度：high · kind：plan
- 证据：该包 4 个文件共 89 行：PromptRuleSlot.java(8) / PromptRuleHook.java(11) / PromptRuleEngine.java(25) / PromptRuleContext.java(45)。PromptRuleEngine 第 8 行 `public class PromptRuleEngine`，第 16 行 `public String render(PromptRuleSlot slot, PromptRuleContext context)` 对构造注入的 `List<PromptRuleHook> hooks` 做 filter/sort/reduce。`git grep -n "implements .*PromptRuleHook" -- 'backend/*.java'` 只命中接口自身定义（0 个实现类）；`git grep -n PromptRuleEngine -- 'backend/*.java'` 只命中本文件第 8、12 行；`git grep -n "PromptRuleSlot\|PromptRuleContext"` 只命中包内 4 个文件自己。
- 影响：这是一层「prompt 规则插槽」抽象，但没有任何实现类、没有调用点，render 永远返回空串。Java 侧 prompt 早已迁往 Python，这套壳是迁移残留；新人在 service 里看到 rules 包会以为存在可扩展的规则钩子机制，浪费定位时间，也让 `mvn` 编译面变大。
- 修复摘要：确认无引用后整包删除，并在重构台账留一条删除说明。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/rules/PromptRuleEngine.java、backend/lianyu-service/src/main/java/com/lianyu/service/rules/PromptRuleHook.java、backend/lianyu-service/src/main/java/com/lianyu/service/rules/PromptRuleSlot.java、backend/lianyu-service/src/main/java/com/lianyu/service/rules/PromptRuleContext.java
- 建议测试：cd backend && mvn -o -pl lianyu-service test；cd backend && mvn -o test

### MNT-6106E · [中] AiResilience（144 行）零注入零调用，doc 注释宣称的 bulkhead/熔断实际未接线
- 专家 / section：maintainability / cleanup
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/ai/AiResilience.java:27`
- 置信度：high · kind：plan
- 证据：类头注释写「AI 出站韧性集中地：前台/后台两条 lane（bulkhead + executor）、timeLimiter、全局与按 upstream 隔离的熔断器」。`git grep -n "AiResilience" -- 'backend/*.java'` 只返回本文件第 27 行类声明与第 46 行构造函数两处，没有任何字段、参数或调用点。它还是 `UpstreamCircuitBreakerFactory` 的唯一使用者（该类在别处只出现在 backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentRuntimeClient.java:150 的一句注释里）。
- 影响：144 行的 @Component 会被 Spring 装配但永远不会被调用，resilience4j 的 bulkhead/timeLimiter/熔断在 Java 侧不产生任何行为，而注释与命名会让 reviewer 误判出站已有韧性保护（这一点也请可靠性专家独立判断）。同时它把 UpstreamCircuitBreakerFactory 一起拖进「看似在用」的状态，两处都难删。
- 修复摘要：先确认 Java 侧是否还需要这个韧性层：需要就接线到真实 AI 出站调用点并补测试，不需要就连同 UpstreamCircuitBreakerFactory 一起删除。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/ai/AiResilience.java、backend/lianyu-service/src/main/java/com/lianyu/service/ai/UpstreamCircuitBreakerFactory.java、backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentRuntimeClient.java
- 建议测试：cd backend && mvn -o test

### MNT-274CC · [中] 「角色贴纸」功能只剩实体 + 映射器 + 两张空表，前后端都无读写方
- 专家 / section：maintainability / cleanup
- 位置：`backend/lianyu-dao/src/main/java/com/lianyu/dao/entity/CharacterSticker.java:12`
- 置信度：high · kind：plan
- 证据：CharacterSticker.java:12 `@TableName(value = "character_sticker", autoResultMap = true)`；backend/lianyu-dao/src/main/java/com/lianyu/dao/mapper/CharacterStickerMapper.java 是空接口 `extends BaseMapper<CharacterSticker>`，全仓无任何注入/调用点。建表在 backend/lianyu-dao/src/main/resources/db/migration/V7__character_sticker.sql（character_sticker_job + character_sticker 两张表），V8__message_sticker_url.sql 增加的列同样无人读写：`git grep -n "stickerUrl\|sticker_url" -- 'backend/*.java'` 0 命中，frontend/src 里 sticker 关键字 0 命中。
- 影响：一条完整功能链只在后端留下实体、映射器和两张生产库表；改 schema、跑迁移、做数据备份时都要为死表付出成本，后来者也无法判断这是「待做功能」还是「已废弃」。
- 修复摘要：确认产品已放弃角色贴纸后，删实体与映射器；表通过新的 Flyway 迁移显式下线（不直接改历史迁移）。
- 修复文件：backend/lianyu-dao/src/main/java/com/lianyu/dao/entity/CharacterSticker.java、backend/lianyu-dao/src/main/java/com/lianyu/dao/mapper/CharacterStickerMapper.java、backend/lianyu-dao/src/main/resources/db/migration/V7__character_sticker.sql
- 建议测试：cd backend && mvn -o test；本地 Flyway migrate 演练

### MNT-3BE51 · [中] antiDebug.js / captureChatShareScreenshot.js 共 313 行零引用，logger.js 注释与加固文档却称其生效
- 专家 / section：maintainability / cleanup
- 位置：`frontend/src/utils/antiDebug.js:1`
- 置信度：high · kind：plan
- 证据：frontend/src/utils/antiDebug.js 93 行、frontend/src/utils/captureChatShareScreenshot.js 220 行，两者在 frontend/src + frontend/electron 的 import 图中均无引用者。`git grep -n antiDebug -- .` 全仓只有两条：frontend/src/utils/logger.js:4 注释「生产环境 console.* 被 antiDebug 吞掉，本模块走独立 IPC 通道（desktop:renderer-log）」与 docs/reusable-capabilities/electron-client-hardening-zh.md:195 表格「生产 drop console | 构建去掉 console + antiDebug 覆写」；`git grep -n captureChatShareScreenshot -- .` 只命中它自己的定义行 frontend/src/utils/captureChatShareScreenshot.js:128。
- 影响：注释与安全加固文档都把「生产环境 console 被 antiDebug 覆写」当成既有事实，实际没有代码加载它，审计结论因此失真；同时两个 300+ 行文件留在 utils 里，读者会以为存在未接线的功能。
- 修复摘要：二选一：要么把 antiDebug 真正接进 renderer 启动流程并补验证，要么删掉两个文件并修正注释与文档。
- 修复文件：frontend/src/utils/antiDebug.js、frontend/src/utils/captureChatShareScreenshot.js、frontend/src/utils/logger.js、docs/reusable-capabilities/electron-client-hardening-zh.md
- 建议测试：cd frontend && npx vitest run --no-file-parallelism；cd frontend && npx vite build

### MNT-45EA3 · [低] admin 装了 4 个零 import 的依赖，另有两个无人调用的 API 函数
- 专家 / section：maintainability / cleanup
- 位置：`admin/package.json:10`
- 置信度：high · kind：direct
- 证据：admin/package.json:10 dependencies 含 `@element-plus/icons-vue`、`@tanstack/vue-table`、`echarts`、`vue-router`；在 admin/src 与 admin/electron 中逐个 grep `from '<pkg>'` 与 `import('<pkg>')` 计数均为 0（对照：axios 2、element-plus 2、pinia 2、vue 若干）；admin/src/components/DataTable.vue 是手写 6 行表格，既不用 tanstack 也不画 echarts，admin/src/App.vue 用 props 切页而非 router。另 admin/src/api.js:6 `export async function getOverview()` 全仓无消费者；admin/src/pages/ManagementPage.vue:4 `import { fetchAdminList, createRelease, moderateUser } from '../api'` 中 createRelease 从未被调用。
- 影响：4 个包白装进桌面端安装包并进入供应链面（echarts/@tanstack 体量都不小）；两个死函数让「admin 前端调了哪些接口」这一事实多出噪音，也容易让人以为版本创建已经实现。
- 修复摘要：删掉 4 个未用依赖与两个死 API 函数，重新生成 lock。
- 修复文件：admin/package.json、admin/src/api.js、admin/src/pages/ManagementPage.vue
- 建议测试：cd admin && npm run build；cd admin && npm test

### MNT-FBD25 · [中] scripts/ 132 个文件里约 93 个是一次性诊断脚本，只有 2 个是文档化入口
- 专家 / section：maintainability / cleanup
- 位置：`scripts/_cloud_deploy_backend_layer.py:1`
- 置信度：high · kind：plan
- 证据：`git ls-files scripts | wc -l` = 132；其中顶层 `_` 前缀 60 个（scripts/_cloud_deploy_backend_layer.py、_cloud_deploy_agent_layer.py、_cloud_deploy_layered.py、_cloud_deploy_backend_only.py、_cloud_deploy_prep.py、_cloud_deploy_agent_base_url.py、_repair_flyway_* 4 个、_cloud_500_check.py/_cloud_500_ts.py、_prod_verify.py/_run_prod_verify.py/_cloud_verify*.py 等），另有 scripts/diag 20 个、scripts/ops 15 个、scripts/smoke 2 个。被 AGENTS.md 正式登记的只有 AGENTS.md:157-158 的 scripts/_cloud_deploy_pull.py 与 scripts/_upload_update_assets.py 两个。这些脚本文件头注释都是具体某次排障（例如 _cloud_deploy_backend_layer.py 首行「部署阶段 3：backend 层（构建 + 重启 + 等 captcha 200 + 接线自检）」）。
- 影响：scripts/ 已经变成无人敢删的垃圾场，与 AGENTS.md §3.1「一次性排查脚本一律放 local/ 或带 `_` 前缀且不提交」直接冲突；新人无法区分「可直接使用的工具」与「某次排障的一次性快照」，同类重叠（6 个 _cloud_deploy_*、4 个 _repair_flyway_*）还会让人跑错脚本改生产。
- 修复摘要：把 scripts/ 收敛成「正式工具 + diag 归档」两层：入口脚本留在 scripts/ 并被文档引用，其余移出仓库。
- 修复文件：scripts/、AGENTS.md、.github/workflows/ci.yml
- 建议测试：python scripts/check_release_asset_contract.py；powershell scripts/check-java-ai-free.ps1 -Mode strict

### MNT-A1713 · [中] 气泡切分规则 Java/JS 各一套逐方法对译实现，词表逐字复制，测试也抄两份
- 专家 / section：maintainability / simplify
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/ai/AssistantReplySplitter.java:69`
- 置信度：high · kind：plan
- 证据：backend/lianyu-service/src/main/java/com/lianyu/service/ai/AssistantReplySplitter.java:69 注释「须与 frontend/src/utils/assistantReplySplit.js 保持一致」；frontend/src/utils/assistantReplySplit.js:2 反向注释「与后端 {@code AssistantReplySplitter} 一致」。括号工具同样是两份对译：backend/lianyu-service/src/main/java/com/lianyu/service/ai/ParenthesisUtils.java（125 行，countUnclosedDepth/stripLeadingOrphanCloses/isInsideParentheses/splitLinesOutsideParentheses/rebalanceSplitPieces）↔ frontend/src/utils/innerThoughtFilter.js（188 行，countUnclosedParenDepth 与同名四函数）。词表逐字复制：ATTEMPT_MARKERS {现在就试,这就试,我去试,我试试,稍等,盯着,马上帮你,这就去} 与 OUTCOME_MARKERS {这次还是没成,还是没成,还是不行,没帮上忙,报了个错,执行失败,助手还没,无法执行,没有成功,没成,失败了,失败，,失败。}（Java 第 20-27 行 = JS 第 96-101 行）；SPEAKING_STYLE_MAX_REPLIES（活泼3/元气3/温柔2/傲娇2/毒舌2/冷静1/成熟1/慵懒1）又与 CharacterChatBehaviorResolver.StyleProfile 构成第三处镜像。测试侧 backend/lianyu-service/src/test/java/com/lianyu/service/ai/AssistantReplySplitterTest.java:36-42 与 frontend/src/utils/__tests__/assistantReplySplit.test.js:43-51 抄同一段中文用例，无共享 fixture。
- 影响：一条「按括号是否闭合切气泡」的业务规则存在 4 处定义、2 套并行测试，同步只靠注释提醒；已经出现漂移——JS 侧多了 WORD_JOINER('\u2060') 粘括号与 normalizeAssistantContent（展示层），Java 侧没有。任何一侧改词表或阈值（SENTENCE_SPLIT_MIN_CHARS=40、maxRepliesPerTurn）都可能让前后端切出不同数量/不同内容的气泡。
- 修复摘要：把「词表 + 括号规则」收敛成一份共享数据，或明确只在一侧实现、另一侧只做展示裁剪。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/ai/AssistantReplySplitter.java、backend/lianyu-service/src/main/java/com/lianyu/service/ai/ParenthesisUtils.java、frontend/src/utils/assistantReplySplit.js、frontend/src/utils/innerThoughtFilter.js、backend/lianyu-service/src/test/java/com/lianyu/service/ai/AssistantReplySplitterTest.java、frontend/src/utils/__tests__/assistantReplySplit.test.js
- 建议测试：新增共享 golden fixture 并让两侧测试读取；cd backend && mvn -o -pl lianyu-service test；cd frontend && npx vitest run --no-file-parallelism

### MNT-F72F0 · [中] GroupChatService 1335 行 / 55 方法 / 14 个注入协作者，群 CRUD、@mention 决策、主动消息、广播落库挤在一个类
- 专家 / section：maintainability / simplify
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/conversation/GroupChatService.java:60`
- 置信度：high · kind：plan
- 证据：GroupChatService.java:60 `public class GroupChatService`，全文 1335 行、55 个方法；构造注入 14 个协作者（ConversationMapper、GroupMemberMapper、CharacterMapper、MessageMapper、AgentBlockingPort、ApiKeyVaultService、ChatTurnFacade、OutputLanguageService、SimpMessagingTemplate、StringRedisTemplate、FileStorageService、CharacterChatBehaviorResolver、CharacterService、AssistantReplyService）。职责至少五块：群 CRUD（createGroup/updateTitle/listMembers）、主动消息（sendProactiveGroupMessage/persistProactiveGroupReplies/buildProactiveGroupSuffix/pickProactiveMentionTarget）、@mention 决策（extractMentionedCharacterIds/normalizeImplicitMentions/decideMentionByRules/decideMentionByJudgeModel/buildJudgeContext/isValidMentionTarget）、落库与广播（persistAndBroadcastReplies）、未读通知与名字映射（notifyGroupUnread/loadCharacterMap/toNameMap/buildMemberNameMap）；类内还自带 extractJsonObject(第 1132 行)、formatOtherCharacterHistoryLine 等私有工具。同类职责在 ConversationService.java（809 行）另有一份。
- 影响：任何群聊改动都要在 1300 行里横跨 14 个协作者定位；@mention 决策这类纯规则逻辑与 IO/广播缠在一起，无法单独单测；`findOwned`/`isBlocked` 这类校验在 ConversationService、GroupChatService、VoiceCallService 各写一遍，口径已经开始分叉。
- 修复摘要：按「mention 决策 / 主动消息 / 落库广播」三段拆出独立类，GroupChatService 只保留编排。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/conversation/GroupChatService.java、backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java、backend/lianyu-web/src/main/java/com/lianyu/web/controller/GroupChatController.java
- 建议测试：新增 MentionDecisionTest（规则表驱动）；cd backend && mvn -o -pl lianyu-service test

### MNT-D9F1F · [中] 权限键三处定义互不同步：AdminPermissionCatalog 无人引用且缺 support.conversation.read
- 专家 / section：maintainability / simplify
- 位置：`backend/lianyu-admin/src/main/java/com/lianyu/admin/identity/AdminPermissionCatalog.java:5`
- 置信度：high · kind：plan
- 证据：AdminPermissionCatalog（第 5-8 行）声明 `SUPER_ADMIN = "super_admin"` 与 `ALL = Set.of("admin.manage","user.read","user.moderate","release.manage","announcement.manage","audit.read","system.health")`，但 `git grep -n AdminPermissionCatalog -- backend` 只命中它自己（无引用者）。真实调用点全是字面量：`require("release.manage")` 6 处、`require("admin.manage")` 6 处、`require("announcement.manage")` 4 处、`require("support.conversation.read")` 2 处（backend/lianyu-admin/src/main/java/com/lianyu/admin/support/SupportGrantService.java:19-20），后者不在 ALL 里；种子数据在 backend/lianyu-dao/src/main/resources/db/migration/V51__admin_management_schema.sql:142-157 单独 INSERT。同一权限键还同时出现在 controller 与 service 两层（ReleaseController.java:19 与 ReleaseService.java:20/32、ReleaseUploadService.java:21 各 require 一次）。
- 影响：权限清单实际上有三个来源（catalog 常量、散落字面量、Flyway 种子），彼此不校验；catalog 已经是死类却看着像唯一真相源，新增权限要凭记忆改三处，漏改时不会编译失败也不会 CI 红——权限面因此不可审计。
- 修复摘要：让 AdminPermissionCatalog 成为唯一真相：所有 require 走常量，种子数据从清单生成并有测试比对，权限校验收敛到一层。
- 修复文件：backend/lianyu-admin/src/main/java/com/lianyu/admin/identity/AdminPermissionCatalog.java、backend/lianyu-admin/src/main/java/com/lianyu/admin/release/ReleaseController.java、backend/lianyu-admin/src/main/java/com/lianyu/admin/support/SupportGrantService.java、backend/lianyu-dao/src/main/resources/db/migration/V51__admin_management_schema.sql
- 建议测试：新增 AdminPermissionCatalogTest（清单 == 迁移种子键集合）；cd backend && mvn -o -pl lianyu-admin test

### MNT-FFDE9 · [低] 同一段「取第一个 { 到最后一个 }」的 JSON 提取在 service 里逐字复制 4 份
- 专家 / section：maintainability / simplify
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/ai/AiChatService.java:133`
- 置信度：high · kind：direct
- 证据：四份实现逐字相同（只有 static 与实例的差别）：AiChatService.java:133（private static）、backend/lianyu-service/src/main/java/com/lianyu/service/character/CharacterDiaryService.java:502（private 实例）、backend/lianyu-service/src/main/java/com/lianyu/service/character/CharacterImportDraftMapper.java:123（private static）、backend/lianyu-service/src/main/java/com/lianyu/service/conversation/GroupChatService.java:1132（private 实例），方法体都是 `int start = text.indexOf('{'); int end = text.lastIndexOf('}'); if (start >= 0 && end > start) { return text.substring(start, end + 1); } return text;`。
- 影响：LLM 偶发在 JSON 前后带自然语言，这段「剥壳」逻辑是四处共用的隐式契约；改一处（例如改成跳过 markdown 代码块 fence、或处理嵌套花括号）必然漏改其余三处，导致同一个 prompt 在不同服务里解析成功率不同。
- 修复摘要：抽成一个 JSON 提取工具方法，四处改为调用。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/ai/AiChatService.java、backend/lianyu-service/src/main/java/com/lianyu/service/character/CharacterDiaryService.java、backend/lianyu-service/src/main/java/com/lianyu/service/character/CharacterImportDraftMapper.java、backend/lianyu-service/src/main/java/com/lianyu/service/conversation/GroupChatService.java
- 建议测试：新增 JsonObjectExtractorTest；cd backend && mvn -o -pl lianyu-service test

### MNT-288A4 · [低] agent 健康探测（3 秒缓存的 volatile healthy）在 5 个 Adapter 里复制 5 份
- 专家 / section：maintainability / simplify
- 位置：`backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentBlockingAdapter.java:126`
- 置信度：high · kind：direct
- 证据：`git grep -l "private boolean reachable()" -- backend/lianyu-web` 命中 5 个文件：AgentBlockingAdapter.java:126、AgentPromptAssemblyAdapter.java:108、AgentSessionSummaryAdapter.java:109、AgentStreamAdapter.java:106、ChatTurnAdapter.java（同类实现，第 86 行调用点）。每份都自带 `private volatile boolean healthy; private volatile long healthyCheckedAt;` 与同一段 `long now = System.currentTimeMillis(); if (now - healthyCheckedAt < 3_000L) return healthy; healthy = client.isHealthy(); healthyCheckedAt = now; return healthy;`，开关都来自同一个 AgentRuntimeClient。
- 影响：「agent 是否可用」这一事实有 5 份独立缓存：TTL 改 3s、改探测方式（例如改成 ping /health）、或想把探测结果共享给 AgentWiringHealthCheck，都要改 5 个类；各 adapter 的缓存各自过期，同一请求里不同 adapter 可能得出相反结论。
- 修复摘要：抽一个带 3 秒缓存的 AgentHealthProbe 组件，5 个 adapter 注入复用。
- 修复文件：backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentBlockingAdapter.java、backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentStreamAdapter.java、backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentPromptAssemblyAdapter.java、backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentSessionSummaryAdapter.java、backend/lianyu-web/src/main/java/com/lianyu/web/agent/ChatTurnAdapter.java
- 建议测试：新增 AgentHealthProbeTest（缓存命中/过期）；cd backend && mvn -o -pl lianyu-web test

## 转交 deferred
- correctness → 未指定 · 关系状态加载 waitForExistingState 里的 Thread.sleep 轮询（阻塞请求线程）与 MemoryWriter 抛 RuntimeException 触发 MQ 重试，属可靠性范畴，建议交给 reliability 专家。
- correctness → 未指定 · mapExistingByCharacterIds 按 characterId IN 全量拉取（无 user 过滤）以及 ProactiveChatScheduler 每 30 秒的多表加载，属性能范畴，建议交给 perf 专家。
- correctness → 未指定 · getNextSeq/clearMessages 中 'msg_seq:' 前缀 4 处硬编码、ProactiveMessageService 与其余 7 处 persist 方法写法不一致，属可维护性范畴，建议交给 maintainability 专家。
- security → correctness · backend/lianyu-service/src/main/java/com/lianyu/service/auth/impl/AuthServiceImpl.java:registerInTx · register 幂等登录的用户名枚举取舍：AuthServiceImpl.registerInTx 在用户名已存在且密码匹配时直接登录（注释「幂等重登」），使注册接口可探测用户名是否存在；这既是逻辑取舍也涉及是否保留该行为，交由 correctness/产品确认后再评估是否算安全缺陷。
- security → reliability · backend/lianyu-web/src/main/java/com/lianyu/web/config/WebSocketConfig.java:143 · WS 异常文本回显：WebSocketConfig 把内部异常文本拼进「认证失败：」回给客户端，属信息暴露但影响极小，且修法与异常传播策略相关，交由 reliability/correctness 一并处理。

> 这些不是当前专家的 finding，但必须写入 verdict.deferred，不能静默丢弃。

## 机器提示（仅供参考）
- 没有检测到明显的跨专家重复候选。

## 交给主编
1. 读本文件与原始 handoff；不要只依赖机器提示。
2. 合并重复项，保留最可信的证据；被合并的 ID 记入 verdict.dropped。
3. 写 `verdict.json`（schemaVersion 2、findings、coverage、dropped），再写 `report.md`。
4. 只有完整覆盖默认四模块时，才更新 `docs/logistics/latest.md` 与 `.logistics/latest.json`。
5. 工作区根目录：`C:\Users\hp\Desktop\LianYu-PC`；运行目录：`C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261104-184431-c1eee4`。
