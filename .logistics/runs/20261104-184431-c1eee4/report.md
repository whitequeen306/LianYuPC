# 予念（YuNian）PC 端 · 全项目审查报告

- 运行：`20261104-184431-c1eee4` · 分支 `main` · HEAD `c652e14` · 来源 project
- 范围：**整个项目** 1100 个文件 / 12 个审查单元 / 135978 行（Java 450 · JavaScript 252 · Python 220 · SQL 60 · Vue 57），不是 diff、不是最近一次提交
- 专家：correctness、security、reliability、maintainability 四位全部交付，4/4 覆盖、无 skipped、无 failed；perf 本次未选
- 裁决：34 条保留（critical 1 / high 2 / medium 24 / low 7），3 条并入其他条目，9 条跨专家转交

## 一句话结论

**最紧要的是堵住网关把 `/api/agent/` 直连 Python 形成的未认证写入面（SEC-FCAD9）**，其次是同一条链上的 SSRF 与「CI 从不跑 Python 测试」两个盲区；其余问题集中在三处：事务与并发写路径（7 处 `@Transactional` 自调用、关系状态丢更新）、失败路径不留痕（群聊挂死、截断回复当完整回复落库、部署假绿），以及一批看起来在生效、实际未接线的代码（i18n 第四语、Java 韧性层、antiDebug）。

---

## 一、覆盖情况：单元 × 专家

| 单元 | 标签 | correctness | security | reliability | maintainability |
| --- | --- | --- | --- | --- | --- |
| u01 | backend/lianyu-service (1/3) | reviewed | reviewed | sampled | reviewed |
| u02 | backend/lianyu-service (2/3) | reviewed | sampled | sampled | reviewed |
| u03 | frontend/src (1/2) | sampled | sampled | sampled | reviewed |
| u04 | scripts (1/2) | sampled | sampled | sampled | sampled |
| u05 | backend/lianyu-dao | reviewed | sampled | reviewed | reviewed |
| u06 | backend-agent | sampled | reviewed | reviewed | sampled |
| u07 | frontend/src (2/2) | sampled | sampled | sampled | sampled |
| u08 | backend/lianyu-web | reviewed | reviewed | reviewed | reviewed |
| u09 | frontend/electron | sampled | reviewed | sampled | sampled |
| u10 | 其他模块（(root)、.github、admin 等） | sampled | sampled | reviewed | reviewed |
| u11 | backend（小模块合集 59 文件） | reviewed | reviewed | sampled | reviewed |
| u12 | 其他模块（backend/lianyu-app、lianyu-common、backend） | sampled | sampled | sampled | sampled |

没有任何单元是 skipped，也没有 missing。但 **u04（scripts）、u07（frontend/src 2/2）、u12 是四位专家全部 sampled**：它们是按 `rg` 模式扫描 + 关键文件抽查，不是逐文件通读。u03、u09、u02 也各有 3 位专家是 sampled。**因此本报告不能自称「12 个单元全量已读」**——u04/u07/u12 属于「扫过、抽过、未逐行」。

perf 维度未运行，需要时执行 `/logi-perf`。

---

## 二、按模块组织的发现

### 2.1 critical / high（必须先处理）

#### SEC-FCAD9 · critical · api-gateway 把 `/api/agent/` 整段直连 Python，未认证即可写他人记忆并消耗平台 Key

- 位置：`deploy/api-gateway/nginx.conf:252`
- 证据：`location /api/agent/ { proxy_pass http://agent:8001/; }`，该 location 与全文件其它 location 都没有 `auth_request` / 鉴权子请求 / allow-deny；`backend-agent/app/main.py:150` 及另外 18 处端点写作 `user_id_header: str | None = Header(default=None, alias="X-User-Id")`，函数体是 `if user_id_header and str(body.user_id) != user_id_header: raise 403`——**省略请求头即整段跳过校验**；app 内 `add_middleware` / `@app.middleware` / `Depends` 三个关键字全仓 0 命中，运行时完全不校验 Sa-Token。直达路径如 `POST /api/agent/memory/write`（main.py:982-1007 直接 `write_memories(body.user_id, ...)`），同理 memory/reconcile、voice/delete、desktop-observe、角色生成均可达，模型 Key 在 main.py:741 可回落到平台付费 Key。该 location 在仓内无调用方（Java 走 `docker-compose.yml:60` 的 `agent-lb:8001`）。
- 影响：公网任意人可对任意 `user_id` 写/合并记忆、触发全量对账写库、删除他人克隆音色、无限消耗付费模型额度。这是本次唯一的 critical，也是唯一「不需要任何前置条件」的写入面。
- 修复摘要：下线该 location；Python 侧把 `if user_id_header and ...` 改成「缺失即 401 / 不一致即 403」；agent 端口退出宿主映射，只留内网；补「无头请求必须 401」的回归。

#### SEC-AA4AD · high · Python 网关对调用方自带 baseUrl 无内网黑名单

- 位置：`backend-agent/app/gateway.py:288`（`:302` 发请求）
- 证据：`url = normalize_openai_base_url(base_url)` 只做字符串归一，随后 `client.stream("POST", url, ...)`；`backend-agent/app/http_client.py:25-40` 的 `new_client()` 只设 timeout、User-Agent、`transport=httpx.HTTPTransport(local_address="0.0.0.0")`，无内网/回环/元数据地址判定。Java 侧 `OutboundUrlValidator` 只在保存 Key 时校验，Python 侧跨进程重新解析，DNS 重绑定与直连都绕得开；配合 SEC-FCAD9 可直接 POST 任意 baseUrl。
- 影响：可探测 mysql:3306 / redis:6379 / minio:9000 / agent-lb，用响应差异画内网拓扑；内网若有裸 HTTP 服务可被直接调用。
- 修复摘要：在 `http_client` 加 `assert_public_url(url)`（解析后按 IP 校验，私网/回环/链路本地/169.254.169.254 全拒），gateway 发请求前调用，并用解析结果直连防二次解析；用户自定义 baseUrl 走域白名单。

#### REL-433F0 · high · CI 不跑 backend-agent 的 pytest

- 位置：`.github/workflows/ci.yml:9`
- 证据：ci.yml 全文 55 行只有 backend / frontend / deps-audit 三个 job，全文无 python / pytest / backend-agent；`backend-agent/tests` 下 49 个测试文件、492 个 `def test_` 只能本地跑（AGENTS.md §4 要求 1222+ 用例全绿）。
- 影响：prompt 组装、工具循环、SSE 帧契约、网关超时/熔断——最易回归的 Python 逻辑合入主干时零保护。破坏 `stream_chat` 帧格式的改动 CI 依旧全绿，故障只在用户端出现。
- 修复摘要：新增 `agent-tests` job（setup-python → uv sync --frozen → pytest -q），设为必需检查，并用一次故意失败验证非空转。

### 2.2 后端服务（backend/lianyu-service、lianyu-web、lianyu-dao、admin、common、security）

| 严重度 | ID | 标题 | 位置 | 证据摘要 | 修复摘要 |
| --- | --- | --- | --- | --- | --- |
| medium | COR-CDAFE | `@Transactional` 标在 protected 方法且被 `this` 自调用，7 处事务从不生效 | `backend/lianyu-service/src/main/java/com/lianyu/service/character/CharacterService.java:101`（调用点 :97） | 另有 AuthServiceImpl:40/122、CharacterDiaryService:378、VoiceCallService:167、CharacterSquareService:231、CustomVoiceService:94；正确范式见 ProactiveMessageService:141/238（注入 self） | 改为注入 self 后调用或移到独立 Bean 的 public 方法；补「第二步失败必须回滚」测试 |
| medium | COR-316C1 | 关系状态读-改-写无事务/版本/行锁，并发回合静默丢更新 | `backend/lianyu-service/src/main/java/com/lianyu/service/relationship/RelationshipStateService.java:130` | :130-133 读对象→内存改→无条件 `updateById`；无 version、无 `FOR UPDATE`。附注（原 COR-7AD38，同文件 :221）：衰减按整天结算后 `setLastDecayAt(now)`，不足一天的余量每次丢掉 | 加 version 列做乐观锁或 `FOR UPDATE`；衰减只推进已结算天数，余量留在时间戳上 |
| medium | COR-B0734 | 主动消息调度的空闲判断恒真，活跃度打分被架空 | `backend/lianyu-service/src/main/java/com/lianyu/service/proactive/ProactiveChatScheduler.java:128` | :128 的 `idleMinutes >= minIdleMinutes` 在 :314 已保证成立，恒真 → EngagementFrequencyScorer 的 activity/recency 项永远走同一分支 | 删掉二次比较，把原始 idleMinutes 交给打分器；加「刚聊过不得触发」的规则测试 |
| medium | COR-5B82B | 更新解析对可空列用 `Map.of`，notes/sha512 为 NULL 时 NPE 500 | `backend/lianyu-admin/src/main/java/com/lianyu/admin/release/ReleaseResolverService.java:21` | :19 显式取 sha512/notes，V51:85/:88 两列允许 NULL，`Map.of` 禁止 null | 改 `LinkedHashMap` 或 `requireNonNullElse(v, "")`；补 NULL 行序列化测试（唯一 direct 后端修复） |
| low | COR-A3CDE | 消息 seq 只在 Redis，与 `uk_conv_seq` 冲突时持续发送失败 | `backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java:483` | Redis `INCR msg_seq:<id>` 不读 `MAX(seq)` 播种；Redis 清空/回滚后从 1 开始，撞 V1:54 唯一键 | 键不存在时用 `MAX(seq)` 播种；撞键后重取有限次重试 |
| low | COR-DFE20 | 客户端断开时截断的半句话以「完整回复」落库并进记忆摘要 | `backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentStreamBridge.java:138` | abort() 以 `null` 错误调 onComplete，ConversationService:383-405 当完整回复落库+推送+摘要 | onComplete 带 interrupted 标记；partial 不写摘要、不计关系分 |
| medium | SEC-E41BA | 改密码不吊销其它会话，token 30 天、无空闲过期 | `backend/lianyu-service/src/main/java/com/lianyu/service/auth/impl/AuthServiceImpl.java:154` | changePassword 只改哈希；application.yml:133-140 `timeout: 2592000`、`active-timeout: -1`、`is-concurrent: true` | 改密后 `StpUtil.kickout(userId)` 并重签当前设备；`active-timeout` 设 7 天（direct） |
| medium | SEC-DDDB1 | 缺 `LIANYU_MASTER_KEY` 时 JasyptUtil 返回明文，API Key 明文落库 | `backend/lianyu-security/src/main/java/com/lianyu/security/util/JasyptUtil.java:65` | 构造器 :20-27 只 warn；:61-70 `return plaintext`，ApiKeyVaultService 直接落库；`.env.example:40` 该值本身为空 | 缺 key 启动即失败、encrypt 抛异常、存量明文迁移、`_cloud_env_check.py` 改为阻断 |
| low | SEC-1EA4E | `X-Trace-Id` 原样进 MDC，可伪造日志行/撑爆日志 | `backend/lianyu-common/src/main/java/com/lianyu/common/util/TraceIdFilter.java:15` | 无长度/字符集校验；application.yml:533 每条日志输出 `%X{traceId}` | 白名单正则 + 剥离控制字符，不匹配重生成（direct） |
| medium | MNT-EF790 | `com.lianyu.service.rules` 插件框架 89 行，0 实现 0 调用 | `backend/lianyu-service/src/main/java/com/lianyu/service/rules/PromptRuleEngine.java:8` | 4 文件 89 行；`implements PromptRuleHook` 0 命中，render 永远空串 | 复核引用后整包删除 + 台账说明 |
| medium | MNT-6106E | AiResilience 144 行零调用，注释宣称的 bulkhead/熔断未接线 | `backend/lianyu-service/src/main/java/com/lianyu/service/ai/AiResilience.java:27` | 只有 :27 声明与 :46 构造两处命中；是 UpstreamCircuitBreakerFactory 唯一使用者 | 先由 reliability 判断是否需要；要就接线，不要就与工厂一起删 |
| medium | MNT-274CC | 「角色贴纸」只剩实体 + 映射器 + 两张空表 | `backend/lianyu-dao/src/main/java/com/lianyu/dao/entity/CharacterSticker.java:12` | CharacterStickerMapper 空接口无调用；V7 两表、V8 加列无读写；sticker 关键字全仓 0 | 删实体/映射器，表用新 Flyway 迁移下线（不改历史迁移） |
| medium | MNT-D9F1F | 权限键三处定义不同步，catalog 无人引用且缺 `support.conversation.read` | `backend/lianyu-admin/src/main/java/com/lianyu/admin/identity/AdminPermissionCatalog.java:5` | ALL 缺该键（SupportGrantService:19-20 在用）；字面量 `require("release.manage")` 6 处；种子在 V51:142-157；controller+service 双重 require | 以 catalog 为唯一真相，require 全走常量，加「清单 == 种子」测试 |

### 2.3 前端与客户端（frontend/src、frontend/electron、admin）

| 严重度 | ID | 标题 | 位置 | 证据摘要 | 修复摘要 |
| --- | --- | --- | --- | --- | --- |
| medium | MNT-56223 | zh-TW 语言包 529 行从未被 import，记忆页显示原始 key | `frontend/src/i18n/locales/zh-TW.js:1` | i18n/index.js:7-9 只 import zh/ja/en，:17 `messages: { zh, ja, en }`；12 个 `memory.*` 键只存在于 zh-TW.js，而 MemoryPage.vue:76/:188 在用；header.logoutConfirm 等只在 zh/en | 定 zh-TW 去留 → 补齐键 → 加「四语键集合相等」单测。**实际影响等同 high**（用户可见缺文案），按模块口径记 medium |
| medium | COR-770E7 | 流式重试无幂等键，一次抖动写出两条用户消息 + 两条回复 | `frontend/src/pages/ChatPage.vue:1439` | attemptStream 把 fetch 与 drain 包在同一 try，catch 里重 POST；useStreamAbort.js:8-15 对任何 TypeError 都判可重试；服务端 ConversationService:328-330 无条件 insert，请求里无 clientMessageId | 引入 clientMessageId + 唯一键去重；收紧 isNetworkError（已收到字节再中断不得重发） |
| medium | SEC-5E409 | custom-tts IPC 把会话 token 发给渲染层指定的任意 URL | `frontend/electron/main.js:2634` | 以 `Authorization: Bearer <token>` 下载 refAudioUrl，绕过 apiProxy 白名单；customTts.js:38-39 同逻辑 | 只允许同源/白名单域且不转发凭证；跨域改签名 URL 或后端代理 |
| medium | SEC-7A856 | NapCat WS/WebUI 长年硬编码口令 `lianyupc` | `frontend/electron/napcatRuntime/napcatConfig.js:72` | 默认参数与 :96-99 强制回写均为该常量；napcatHost.js:139-140 同样 | 每安装随机口令 + safeStorage 落盘 + 旧口令静默轮换 |
| medium | SEC-5AEC2 | 运营台管理员 token 存 localStorage，且无 CSP/安全头 | `admin/src/stores/session.js:2` | api.js:3 从 localStorage 取 token；admin/index.html 无 CSP；SecurityHeadersFilter 只覆盖 `/api/**` | 改内存态 + HttpOnly 续期；给 `/ops` 加 CSP 与安全响应头 |
| medium | SEC-29B63 | 更新清单验签默认关闭（出厂无公钥）且安装包无代码签名 | `frontend/electron/updater/updater.js:83` | 无公钥即跳过校验；`.env.production.cloud:12` 公钥仍是注释；package.json:82 `signAndEditExecutable: false` | 无公钥构建直接失败、updater 改 fail-closed、补代码签名 |
| medium | REL-74C87 | 群聊 `runAsync` 无兜底，生成抛错时 TURN_ERROR/TURN_COMPLETE 都不下发 | `backend/lianyu-service/src/main/java/com/lianyu/service/conversation/GroupChatService.java:370` | 返回值丢弃、lambda 无 try/catch → :442/:449 都不发；GroupChatPage.vue:534-540 无本地超时 → 永久「正在输入」 | try/catch/finally 必发 TURN_ERROR + TURN_COMPLETE；前端补无帧超时 |
| medium | REL-3E00B | 主窗口渲染进程崩溃后只弹框，不重载不重建 | `frontend/electron/main.js:652` | `render-process-gone` 只 log+dialog；对照 quickChat did-fail-load :1753-1760 有 destroy+重建 | 崩溃后重建一次（节流 + 3 次上限），统一两套恢复入口 |
| medium | MNT-3BE51 | antiDebug.js / captureChatShareScreenshot.js 共 313 行零引用，文档却称其生效 | `frontend/src/utils/antiDebug.js:1` | 无 import 者；logger.js:4 注释与 docs/reusable-capabilities/electron-client-hardening-zh.md:195 均宣称 antiDebug 生效 | 二选一：真正接线并验证，或删文件 + 修注释与文档 |
| medium | MNT-A1713 | 气泡切分 Java/JS 双套对译实现 + 同批重复（JSON 剥壳 4 份、健康探测 5 份） | `backend/lianyu-service/src/main/java/com/lianyu/service/ai/AssistantReplySplitter.java:69` | 词表逐字复制（Java :20-27 = JS :96-101），ParenthesisUtils 125 行 ↔ innerThoughtFilter.js 188 行，双份测试；JS 侧已漂移出 WORD_JOINER | 词表/阈值收敛为共享数据或单一实现；抽 JsonObjectExtractor 与 AgentHealthProbe |
| medium | MNT-F72F0 | GroupChatService 1335 行 / 55 方法 / 14 个注入协作者 | `backend/lianyu-service/src/main/java/com/lianyu/service/conversation/GroupChatService.java:60` | 群 CRUD、@mention 决策、主动消息、落库广播、未读通知挤在一个类 | 按 mention 决策 / 主动消息 / 落库广播拆类，先抽纯函数 |
| low | MNT-45EA3 | admin 4 个零 import 依赖 + 2 个死 API 函数 | `admin/package.json:10` | `@element-plus/icons-vue`/`@tanstack/vue-table`/`echarts`/`vue-router` 均 0 引用；api.js:6 getOverview 无消费者 | 删依赖与死函数，重生成 lock（direct） |

### 2.4 脚本、CI 与运行面（scripts、.github、部署）

| 严重度 | ID | 标题 | 位置 | 证据摘要 | 修复摘要 |
| --- | --- | --- | --- | --- | --- |
| medium | REL-B9984 | CI 前端 job 与 §4 门禁不一致：vitest 未串行、无 vite build | `.github/workflows/ci.yml:39` | `npx vitest run` 缺 `--no-file-parallelism`；整条 job 无 `npx vite build` | 改为串行 vitest + 补 build 步骤（direct） |
| medium | REL-E7978 | 部署脚本把能力自检降级为 stderr 警告，缺工具仍 DEPLOY_DONE 退出 0 | `scripts/_cloud_deploy_pull.py:149` | :149-158 只 print(stderr)，:163 仍打印 DEPLOY_DONE 返回 0；同函数健康自检失败是 `SystemExit(56)` | 自检失败统一非零退出，DEPLOY_DONE 只在全通过后打印 |
| low | REL-81069 | AsrStreamClient 构造函数读 `@Value` 字段，连接超时恒 1000ms | `backend/lianyu-service/src/main/java/com/lianyu/service/ai/AsrStreamClient.java:52` | 构造期字段未注入=0 → `Math.max(1000, 0)`；open() :68 才用注入值 | 移到 `@PostConstruct` 或使用处现算（direct） |
| low | REL-9DADC | 两个 dlq 只有声明没有消费者，死信无人重放也无人告警 | `backend/lianyu-web/src/main/java/com/lianyu/web/config/RabbitMqConfig.java:54` | :54-66 memory.summary.dlq、:136 ai.background.dlq，`defaultRequeueRejected(false)`，全仓无 `@RabbitListener` 监听 `*.dlq` | 加队列深度指标与阈值告警，或补死信巡检/重放脚本 |
| low | SEC-C1FB2 | CI 第三方 action 只用可变 tag，未钉 SHA | `.github/workflows/ci.yml:48` | checkout@v4、setup-java@v4、setup-node@v4、osv-scanner@v2.0.3；未声明顶层 permissions | 钉 commit SHA + `permissions: contents: read`（direct） |
| medium | MNT-FBD25 | scripts/ 132 个文件约 93 个是一次性诊断脚本，只有 2 个被文档登记 | `scripts/_cloud_deploy_backend_layer.py:1` | 顶层 `_` 前缀 60、diag 20、ops 15、smoke 2；AGENTS.md:157-158 只登记两个 | 收敛成「正式工具 + diag 归档」，补 scripts 索引表 |

---

## 三、跨单元的系统性问题

1. **「可选校验」与「保存期校验」构成的鉴权边界漏洞**（SEC-FCAD9 + SEC-AA4AD + SEC-5E409）：Python 侧用 `Header(default=None)` + `if header and ...` 的写法在三处出现（agent 端点、custom-tts、WS 握手），共同特征是「没有就走另一条路」。建议做一次全仓 `Header(default=None)` 与 `if xx is not None` 的审计，把身份校验改成默认拒绝。

2. **事务与并发写路径形同虚设**（COR-CDAFE + COR-316C1 + COR-A3CDE）：7 处 `@Transactional` 自调用、关系状态无版本、seq 只活在 Redis，三者都是「看起来有保护、实际没有」。共同修法是把「写路径的原子性」当成一条显式约定（public + 代理 + 版本/行锁 + 库为事实来源），而不是逐处打补丁。

3. **失败路径不留痕**（REL-74C87 + COR-DFE20 + REL-E7978 + REL-9DADC + REL-3E00B）：群聊挂死、截断回复当完整回复、部署假绿、死信无人看、崩溃不重建——共同问题是异常在边界处被「吞掉或改写」而不是被上报。建议统一三类事件：回合级错误帧、部署级非零退出、队列积压指标。

4. **「文档/注释宣称生效，代码未接线」**（MNT-6106E + MNT-3BE51 + MNT-56223 + SEC-29B63）：AiResilience 注释宣称 bulkhead/熔断、logger 注释与加固文档宣称 antiDebug、AGENTS.md 宣称四语齐、更新器注释宣称验签——这四处都会让审计与新人判断失真。这类问题的成本不在代码量，而在「错误的安全感」。

5. **同一条规则多处定义**（MNT-A1713 + MNT-D9F1F + MNT-F72F0）：切分词表 4 处、权限键 3 处、`findOwned`/`isBlocked` 3 处，且已出现实际漂移（JS 侧多出 WORD_JOINER）。

6. **测试与门禁的覆盖率断层**（REL-433F0 + REL-B9984 + MNT-56223 的键集合缺门禁）：Python 侧零 CI、前端串行与构建缺失、i18n 无键集合校验——三类「本地绿、CI 不拦」的缺口叠加。

---

## 四、建议修复顺序

**批次 1 · 立即（critical/high，先止血）**
1. `SEC-FCAD9`：下线 nginx `/api/agent/`，Python 侧改默认拒绝（改动最小、收益最大）。
2. `SEC-AA4AD`：`assert_public_url` 接入 `http_client`，否则上一步只是把门关上、SSRF 仍可由 Java 代理路径进入。
3. `REL-433F0` + `REL-B9984` + `SEC-C1FB2`：三处都在 `.github/workflows/ci.yml`，一个 PR 改完（加 agent-tests job、串行 vitest、vite build、钉 SHA、声明 permissions）。

**批次 2 · 用户可见故障（前端/协议层，一天内可验证）**
4. `MNT-56223`：i18n 缺键（记忆页三个按钮显示原始 key），先补键再决定 zh-TW 去留。
5. `REL-74C87`：群聊永久「正在输入」——与 `MNT-F72F0` 同文件，先做兜底，重构另开。
6. `COR-770E7`：重复消息与双倍计费，需要前后端一起改（幂等键 + 唯一键）。

**批次 3 · 数据正确性（需要迁移/设计判断）**
7. `COR-5B82B`（direct，最小改动，可随手带上）→ `COR-CDAFE` → `COR-316C1` → `COR-A3CDE` → `COR-DFE20` / `COR-B0734`。同一个 `RelationshipStateService` 的两条（316C1 + 原 7AD38）一次改完；`ConversationService` 的 A3CDE 与 DFE20 同文件，合并一个 PR。

**批次 4 · 凭据与客户端加固**
8. `SEC-E41BA`（direct）→ `SEC-DDDB1` → `SEC-5E409` → `SEC-7A856` → `SEC-5AEC2` → `SEC-29B63` → `SEC-1EA4E`（direct）。`AuthServiceImpl` 的 E41BA 与 COR-CDAFE 的 AuthServiceImpl:40/122 同文件，合并。

**批次 5 · 运维可见性**
9. `REL-E7978`、`REL-3E00B`、`REL-81069`（direct）、`REL-9DADC`。

**批次 6 · maintainability 单独批次（不与上面任何功能修复混提交）**
10. `MNT-EF790`、`MNT-6106E`（需 reliability 先给结论）、`MNT-274CC`、`MNT-3BE51`、`MNT-45EA3`、`MNT-FBD25`、`MNT-A1713`、`MNT-F72F0`、`MNT-D9F1F`。

---

## 五、覆盖缺口与未验证项

- **perf 维度未运行**：需要时执行 `/logi-perf`。correctness 转交的两条性能疑点（`mapExistingByCharacterIds` 无 user 过滤的全量拉取、`ProactiveChatScheduler` 每 30 秒多表加载）未被任何人评估成本，**不要当作已确认有性能问题，也不要当作没问题**。
- **u04 scripts / u07 frontend/src(2/2) / u12**：四位专家全部 sampled（模式扫描 + 抽查），未逐文件通读。u03、u09、u02 也各有 3 位 sampled。这五个单元里可能存在本报告未覆盖的问题。
- **security 的 authz / exposure 计数为 0 是「已检查且未发现」**，依据是 14 个控制器逐一核对主体来源、MyBatis 无 `${}`、actuator 仅 health/info、中间件端口全绑 127.0.0.1 等离线证据；没有做运行期越权测试。
- **没有做渗透测试或动态验证**：SEC-FCAD9 / SEC-AA4AD 的复现步骤是静态推导 + 与 nginx 配置对照，建议上线修复前在测试环境用 `curl` 实测一次。
- **DIRECT 修复未被自动应用**：本报告只做审查，没有改任何源码。

---

## 六、跨专家 deferred（转交项）

| 来源 | 转交给 | 位置 | 原因 |
| --- | --- | --- | --- |
| correctness | reliability | `RelationshipStateService.java`（waitForExistingState） | `Thread.sleep` 轮询阻塞请求线程、MemoryWriter 抛 RuntimeException 触发 MQ 重试 |
| correctness | perf（未运行） | `CharacterMapper.mapExistingByCharacterIds`、`ProactiveChatScheduler` | 全量拉取与 30 秒多表加载的成本未评估 |
| correctness | maintainability | `ConversationService.getNextSeq / clearMessages` | `msg_seq:` 前缀 4 处硬编码、persist 写法不统一 |
| security | correctness | `AuthServiceImpl.registerInTx` | 幂等重登导致用户名枚举，需产品确认是否保留该行为 |
| security | reliability | `WebSocketConfig.java:143` | WS 握手异常文本回显 |
| maintainability | correctness | `admin/src/pages/ManagementPage.vue:15` | 模板绑定的 `save` 不存在，「新建版本草稿」保存是死路 |
| maintainability | correctness | `admin/src/App.vue:1` | 模板直接用 `window.adminWindow`，窗口按钮失效 |
| maintainability | reliability | `AiResilience.java:27` | Java 侧是否真需要 bulkhead/熔断，删除前需可靠性判断 |
| maintainability | （待清理轮次） | `EncryptedField.java` | 空 marker 注解全仓无用，因 12 条上限未单独上报 |

## 七、被合并的候选（dropped）

| ID | 原因 |
| --- | --- |
| `COR-7AD38` | 与 `COR-316C1` 同文件同一 update 路径，一次修复覆盖；证据并入 COR-316C1 |
| `MNT-FFDE9` | 与 `MNT-A1713` 同属「重复实现收敛」批次，证据（4 处文件行号）已在 MNT-A1713 保留 |
| `MNT-288A4` | 同上，5 份健康探测已并入 MNT-A1713 证据 |

---

*本报告只描述问题与修复方案，不含健康分、评分、基线或门禁结论。审查为只读，未修改任何源码。*
