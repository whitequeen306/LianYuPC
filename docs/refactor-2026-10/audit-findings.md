# 审计发现台账（2026-10，基线 0580a07 / v0.2.367）

> 四路只读审计合并后的台账。语言级重写时按三档处理：
> **【P0】**先修——不依赖重写，任何时候都该修；
> **【结构债】**重写时消灭——这是重写要直接买断的问题,不迁移;
> **【资产】**保留移植——重写时这些行为/数据/约束必须原样带过去。

---

## 【P0】先修（与重写并行，在现有 Java 代码上修）

### P0-1 prod profile 从未生效
- `backend/lianyu-app/src/main/resources/application.yml:5` 写死 `spring.profiles.active: dev`
- 全仓 grep `SPRING_PROFILES_ACTIVE` 零命中,compose 未注入
- 后果:`application-prod.yml` 的关文档 / WS origin 白名单 / prod CORS 全部空转
- 修法:`${SPRING_PROFILES_ACTIVE:dev}` + compose `environment:` 注入 + `.env.example` 登记

### P0-2 SupportGrant redeem 无审计（已修正审计误报）
- 审计原判「无授权无审计」**不准确**：授权在 Service 层完整存在——`SupportGrantService.issue/redeem` 均调 `authorization.require("support.conversation.read")`，`AdminAuthorizationService.require` 有 super_admin + 权限表双重校验，issue 也有审计
- 实际缺口：`redeem` 成功/失败均无 audit 落库（`SupportGrantService.java:20`）
- 修法（已做）：redeem 补成功/失败双审计
- 附带发现：`GlobalExceptionHandler:88` 兜底把 `IllegalArgumentException`（如「授权码无效」）变 HTTP 500，与 P0-8 同根

### P0-3 语音 WS token 走 query string
- `VoiceWebSocketConfig.java:71-86` 用 query 参数传 `token`;`HeaderOnlyTokenFilter.java:20,25` 只拦 `lianyu-token` 头
- 后果:token 进 nginx access log
- 修法:挪到 WS 首帧或 subprotocol

### P0-4 前端 `syncToken` 未定义导致功能静默失效
- `frontend/src/main.js:11` 只 import 了 `readToken`;`:97` 调 `syncToken()`
- 外层 `try{}catch{}`(:96-104) 静默吞掉 → `lianyu-last-route` 永不写入,"重启恢复上次页面"整体失效
- 修法:1 行 import 修复

### P0-5 `@Async` 无池可用
- `LianYuApplication:14` `@EnableAsync`,容器内 3 个 `TaskExecutor` bean,无 `taskExecutor` 命名 bean、无 `AsyncConfigurer`、`@Async` 无 `@Qualifier`
- Spring 回落到 `SimpleAsyncTaskExecutor`(每任务新建线程,无池化无上限)
- 影响点:`SessionSummaryService:54`、`MomentsCommentOrchestrator:135,167`
- 修法:定义唯一 `taskExecutor` bean 或 `AsyncConfigurer`

### P0-6 无 CI
- 仓库根无 `.github/`,无任何 pipeline;OSV 扫描纯靠约定
- 修法:最小 CI = `mvn -B test` + `vitest` + `eslint` + `OSV-Scanner`,挂 main

### P0-7 事务内慢调用残留
- `AuthServiceImpl.uploadAvatar` `@Transactional`(:145) 内 `uploadAvatar(file)`(:150)+删旧对象(:157),MinIO 超时 60s → 慢上传占住 Hikari
- `AuthServiceImpl.register` `@Transactional`(:41) 内 `StpUtil.login`(:66) Redis 写,回滚时 token 已发出
- `NotificationService.createAndPushNotification:170-181` 被事务方法调用(如 `ProactiveMessageService.persistProactiveReply:211`),回滚后 STOMP/MQ 已推

### P0-8 admin 异常全变 500
- `AdminAuthService.java:32-43` 抛 `IllegalArgumentException/IllegalStateException` → `GlobalExceptionHandler:88` 兜底 → HTTP 500
- 前端无法区分"密码错/账号停用/锁定"

### P0-9 Dockerfile 版本矛盾 + 疑似已坏
- `frontend/Dockerfile` 用 `node:20-alpine`,`package.json:109` 要求 `>=22.12.0`
- 只 `COPY index.html vite.config.js public src`,但 `vite.config.js:79-83` rollup input 无条件含 `launcher.html`/`quick.html` → build 找不到入口(静态推断,未实跑)
- 该 Dockerfile 目前是死配置(compose 只引用 `Dockerfile.gateway`),修或删二选一

---

## 【结构债】重写时直接消灭(不迁移)

### A. Agent 编排层

| # | 问题 | 证据 |
|---|---|---|
| A1 | **两套 Prompt 组装路径**:Graph 内 vs `ChatTurnFacade.assembleSystemPrompt(scene,...)` 绕过后门 | `ChatTurnFacade.java:81-101`;5 处调用:Proactive(3)+Moments(2) |
| A2 | **Graph 覆盖率低**:14 条 AI 链路仅 5 个调用点走图,8 条完全绕过(Moments/Diary/冷启动/城市变更/VoiceCall/桌面感知/ai 直连) | `AiController.java:47,55`、`CharacterDiaryService.java:323-412`、`VoiceCallService.java:495-543`、`VisionChatService.java:98-154` |
| A3 | **Graph 是线性 3 节点、无分支、无 checkpoint、无重试、无并行**;`LOAD_CONTEXT` 是死常量;流式回合 `InvokeModelNode` 空转一次仍消耗图执行 | `ChatTurnGraphConfig.java:33-44`、`ChatTurnNodes.java:11`、`InvokeModelNode.java:37-41` |
| A4 | **工具循环无上限**:只开 `internalToolExecutionEnabled(true)`,Spring AI `OpenAiChatModel.internalCall` 递归调用自身且无计数器(已 javap 反编译 1.1.2 确认);流式不过 TimeLimiter,唯一兜底 30min SSE 硬超时 | `AiChatService.java:769,778,827-837` |
| A5 | **`computer_task` 阻塞 630s + 25s 等重注册**,发生在 AI 调用线程内 | `application.yml:331,334`、`AgentBridgeService.java:58-67,251-279` |
| A6 | **死参数导致重复检索**:`memoryContext` 算完即弃(3 处) | `MomentsService.java:373,391,470-480`、`ProactiveMessageService.java:109-119` |
| A7 | **死代码**:`sendProactiveGroupMessage`(GroupChatService:183-315 无调用者)、`max-group-proactive-per-run`、`group-scan-limit`、`AiConfig.embeddingDim`、`ChatTurnFacade.assembleSystemPrompt(ChatTurnCommand)` | 各文件如左 |
| A8 | **枚举即策略**:新增场景须同步改 `ChatTurnScene` 7 个方法;`DIARY.enableChatTools()=true` 与 `CharacterDiaryService.java:397 setDisableChatTools(true)` 结论相反 | `ChatTurnScene.java:15-44` |
| A9 | **Prompt 散布**:≥15 个类内嵌多语言字面量,粗算 1500+ 行 | `CharacterStateService.java:262-459`、`ReplyBehaviorRuleHook.java:38-247`、`OutputLanguageService.java:155-203`、`ChatTurnPromptAssembler.java:159-243` 等 |

### B. 模型网关

| # | 问题 | 证据 |
|---|---|---|
| B1 | **没有网关接口**:`VisionChatService` 直调 `AiChatService` 8 个 public 方法(buildChatModel/resolveVault/resolveApiKeyForProvider/buildPrompt/toSpringMessages/extractStreamDelta/enforceExpectedLanguage/withChatToolScope) | `VisionChatService.java:104-317` 多处 |
| B2 | **AiChatService 混杂**:网关+语言门重试+JSON 抽取+错误文案+工具作用域,968 行 | `AiChatService.java` |
| B3 | **无模型级缓存**:每请求重建 `OpenAiApi/OpenAiChatModel` | `AiChatService.java:148,267,389`、`VisionChatService.java:104,141,240,260,299` |
| B4 | **仅 2 种协议**:OpenAI-compatible + Ollama;provider 是用户自定义别名而非枚举 | `ChatModelFactory.java:29-67` |

### C. 记忆子系统

| # | 问题 | 证据 |
|---|---|---|
| C1 | **双写无事务**:MySQL 成功后 Milvus 失败只 warn;删旧向量/插新向量之间崩溃产生孤儿 | `MemoryWriter.java:186-194,210-229` |
| C2 | **双读视图不融合**:结构化只看 importance,向量只看相似度;向量命中不复用 `memory_id` 回查 MySQL | `MemoryRetriever.java:53-59` vs `130-137`、`MemoryVectorStore.java:120,142-145` |
| C3 | **Rerank 硬编码 DashScope URL**,却用用户 vault key → 非 DashScope 用户必 401,静默降级 0.5 分保序,重排实际失效 | `RerankerService.java:60-76,126-133` |
| C4 | **LIKE 提升无中文分词**:取「首个 2-12 字 token,否则前 12 字符」→ 中文退化为前 12 字 LIKE | `MemoryRetriever.java:184-196` |
| C5 | **抽取失败全静默**(debug 级),记忆"悄悄不再增长"无告警 | `MemoryLlmExtractor.java:86-89` |
| C6 | **MemoryWriter 自建 daemon 调度器**,非 Spring 托管,停机不排空 | `MemoryWriter.java:51-55` |
| C7 | `embedding+Milvus+rerank` 全在模型调用线程内同步 | `MemorySearchTool.java:27-39` |

### D. 横切与重复

| # | 问题 | 证据 |
|---|---|---|
| D1 | **同一业务三处实现**:Conversation/GroupChat/Moments 各自实现 seq 分配+历史取窗+Prompt 拼装+AI 调用+回复切分+落库+通知;重复 private 方法:toResponse×8、extractJsonObject×5、isBlocked×3、reserveSeqBlock×2 | 三个 service |
| D2 | **24 类直拼 Redis key**,26 个前缀无封装 | 全 service 层 |
| D3 | **157 个 `@Value` / 141 key,仅 5 个 `@ConfigurationProperties`,零 `@Validated`** | 全 service 层 |
| D4 | **MQ 常量两处定义**:`RabbitMqConfig:19-39` vs service 内字面量 8 处(pom 依赖方向强制) | `AiBackgroundConsumer.java:27` 等 |
| D5 | **admin 全 JdbcTemplate 裸 SQL**:14 类 ~40 条,零 DAO 零事务;封禁状态塞在 `settings_json`(JSON_SET),非独立列 | `AdminManagementController.java:22-36`、`AdminOperationsController.java:28-31` |
| D6 | **角色广场数据双源**:54 角色 × 4 语言 = 1,800+ 行 Java 字面量(7 个 catalog 类)+ 18 个 Flyway seed + `CharacterSquareAvatarSync`(206 行 ApplicationRunner) | `service/square/` |
| D7 | **keyStrategies 漏注册** `VISION_MODEL`、`IMAGE_DESCRIPTION`(框架回落 REPLACE 兜住,契约表与实际不同步) | `ChatTurnGraphConfig.java:46-82` |
| D8 | **魔法字符串**:settings 键、`audioUrl` 哨兵 `system/voice-call-turn`、`【长期记忆/xx】`槽位格式、群聊前缀「群聊-」 | `ChatToolContext.java:20-26` 等 |
| D9 | **`@Lazy` 循环依赖**×2(ConversationService↔SingleChatOpeningScheduler;ProactiveMessageService self 自代理) | `ConversationService.java:100-101`、`ProactiveMessageService.java:141-144` |
| D10 | **无 Micrometer 指标**(仅 health/info);熔断/记忆命中/工具轮次/token 成本无时序 | `application.yml:475-482` |

### E. 前端 / Electron

| # | 问题 | 证据 |
|---|---|---|
| E1 | **3 份 SSE 解析互不相同** + 测试内第 4 份 | `assistantStreamDrain.js:30-53`、`qqBridge.js:416-450`、`wechatBridge.js:76-90`、`sseParser.test.js:4` |
| E2 | **AI 对话发送双进程实现**:渲染进程 `ChatPage.handleSend:1471` vs 主进程 `qqBridge.js:156-219`+`wechatBridge.js:158`,分段/兜底/超时各不同 | — |
| E3 | **IPC 通道字符串 3 处散落**(preload/main/coordinator),77 handle + 24 push 无常量表无校验 | `preload.js:126+`、`mcpHost.js:233,301`、两个 coordinator |
| E4 | **electron/main.js 3,319 行**,`registerIpcHandlers` 单函数 890 行 73 handler | `main.js:2195-3084` |
| E5 | **ChatPage.vue 2,424 行 / 59 函数 / 7 store**;GroupChat 1,500 / Landing 1,366 / Characters 1,186 / QqBridge 1,150 | pages 目录 |
| E6 | **18/22 页面穿透 api 层**(34 条 import),store 只覆盖 30% 数据 | 全 pages |
| E7 | **死代码**:zh-TW.js(517 行)、AppSidebar.vue(208 行)、api/index.js(1 行)、stores/layout.js(13 行)、_run-release.ps1(0 字节)、vitest.e2e.config.js(无 script) | 各文件 |
| E8 | **测试质量**:22 个「自证式」测试(不 import 产品代码只测副本,如 sseParser.test.js 测不存在的 src/utils/sseParser.js)+ 8 个源码文本断言(readFileSync 比 indexOf) | `src/**/__tests__/` |
| E9 | **组件测试 0 个**(@vue/test-utils 在 devDeps 但 0 引用);页面测试 0;E2E 0;IPC 契约测试 0 | — |
| E10 | **无 ESLint/Prettier**;94 处硬编码 hex;151 处 `!important`;i18n 覆盖 ~40%(LandingPage 1,366 行 0 个 t()) | — |
| E11 | **双更新通道**:客户端只认 MinIO latest.yml;后端 /api/public/admin-release(含 rollout 分流)前端零引用 | `updater.js:14` vs `ReleaseResolverController` |
| E12 | **主进程状态镜像无版本号**:desktop/qqBridge/wechatBridge store 靠 push,丢一次永久不一致 | 三个 store |

### F. 基建 / 发版

| # | 问题 | 证据 |
|---|---|---|
| F1 | **版本 5 个真相源**:client 0.2.367 / backend 0.1.0-SNAPSHOT / admin 0.2.363 / admin App.vue:14 硬编码 / cleanup/resume 脚本旧版本 | 各文件 |
| F2 | **52 个入仓脚本硬编码服务器 IP**(156.233.228.18,77 文件 86 处),全部 paramiko root 密码 | `scripts/*.py` |
| F3 | **cert 轮换是手工运维**:SPKI 指纹硬编码在 env,漏同步 → 旧客户端全部连不上 | `.env.production.cloud:2-3` |
| F4 | **可观测性**:无 Prometheus/Grafana/OTel;com.lianyu prod 仍 DEBUG;docker log 无 max-size | `application.yml:529-531` |
| F5 | **双安装器抢同一产物路径**:WPF 安装器原地覆盖 electron-builder NSIS exe,win-unpacked 只活在 %TEMP%,跨进程靠猜路径 | `prepare-branded-release.mjs` + `build-offline-installer.ps1:34-43` |
| F6 | **okhttp 依赖泄漏**:lianyu-ai import okhttp3 但任何 pom 未声明(经 minio 传递) | `SsrfPinningClientFactory.java:9` |

---

## 【资产】重写时必须原样保留/移植

### 行为资产(用户体验直接依赖)

1. **Prompt 20 层组装顺序**——尤其「情绪块放在回复规则之后以取得更高权重」(`CharacterPromptBuilder.java:100` 注释),这是隐性契约,重写丢掉 = 回复风格全变
2. **场景裁剪矩阵**(`ChatTurnScene` 7 个布尔):SINGLE 全量 / GROUP 无时间城市关系 / VOICE_CALL 极简(压首包延迟) / MOMENTS·DIARY 无时间
3. **伪 user 触发句约定**:`PROACTIVE_USER_TRIGGER = "（系统触发主动发言，用户未发送新消息。）"`,任务说明一律放 system(`ProactiveMessageService.java:88-106`)——历史上模型把编号要求当用户乱码指令的事故教训
4. **SSE 事件契约**:`{"content"}`/`{"replace"}`/`{"pieces":[...]}`/`{"error"}`/`[DONE]`,收尾顺序 beforeStreamComplete→[DONE]→complete→onComplete
5. **主动行为引擎全套参数与节律**:EngagementFrequencyScorer 概率模型(想念1.38/撒娇1.30/生气0.42×关系阶段)、未回抑制(2次→催回复语音,5次→暂停7天)、固定时段语音(11:30-13:30/18:30-21:00)、INJURED 阻断
6. **记忆启发式槽位正则**(`MemoryExtractionHeuristics.java:54-66`):姓名/爱好/忌口/偏好/禁忌 + `【长期记忆/x】`格式
7. **关系/情绪状态机**:四分数(trust/intimacy/security/expectation)+phase+衰减矩阵+15%转移概率
8. **群聊轮次协议**:turnId 中断、anyOf 按完成序落库、bubbleGapMs 500ms 连发、@ 裁决两级(规则→小模型)
9. **语言门**:最多 2 次完整阻塞重生成 + 心跳;输出语言跟随输入习惯
10. **ASR/TTS 链路**:SenseVoice 容器、DashScope realtime WS(2.5s 快失败→HTTP 回退)、双工 VoiceCallDuplexSession
11. **Electron 安全基线**:contextIsolation/nodeIntegration:false/sandbox/证书 pin/CSP/fuses/assertTrustedSender——资产不是债
12. **updater**:6 线程 Range 分片 + sha512 + probeRangeSupport 降级,全仓质量最高模块
13. **SSRF 防护**:SsrfPinningClientFactory 的 DNS 预解析 + IP 固定,用户自填 baseUrl 必须过它

### 数据资产(37 张表,Flyway V1→V52)

- 对话主链:user/character/conversation/group_member/message/memory_meta/api_key_vault
- 广场/状态:character_square_template/character_state/character_diary/square_*/community_*
- 关系:relationship_state/relationship_event
- 朋友圈:moments_post/moments_comment/moments_interaction_state
- 通知:user_notification/web_push_subscription;语音:user_custom_voice(_models)
- 管理中枢(V51):admin_*/app_release/announcement/release_rollout/support_access_grant
- **重写约束**:新后端必须继续跑同一套 Flyway(或等价物),只增不改;MySQL 表结构不动 → 迁移期新老后端可共存
- Milvus `memory_vectors_v2`(dim 1024, IVF_FLAT+COSINE);Redis 26 个 key 前缀(Sa-Token db1,业务 db0)
- Jasypt 加密的 api_key_vault:**新后端必须能解密同一批密文**(主密钥 LIANYU_MASTER_KEY 环境变量,支持多版本轮换)

### 约束资产(红线)

- 慢调用不进事务(2026-08-07 全站 499/504 事故教训)
- 出站 HTTP 显式超时,SsrfPinningClientFactory 唯一合法
- `local/ship-release.ps1` 唯一发版入口,参数语义不变(用户明确约束)
- Flyway V30 占位禁复用
- 前端 DESIGN.md 令牌 + 四条渲染红线
- Electron 打包链:electron-pack → WPF 安装器 → gh release → MinIO → agent-latest.yml,四外部依赖(gh CLI/installer ps1/python 脚本/AgentEngine zip)
