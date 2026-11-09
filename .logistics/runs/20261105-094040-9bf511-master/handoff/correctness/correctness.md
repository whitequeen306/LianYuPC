# 正确性审查报告

- 运行：`20261105-094040-9bf511-master`（project 模式，全量审查，非 diff）
- 基线：`main` @ `c652e14`（`fix(relationship): 修正被刷高的历史信任分（V57）`），工作区干净
- 范围：1100 个文件 / 135978 行 / 12 个审查单元
- 结论：**10 条发现**（1 high / 8 medium / 1 low），无 critical；7 个 section 全部实际检查

---

## 1. 范围与覆盖

| 单元 | 内容 | 状态 | 实读文件 | 说明 |
|---|---|---|---|---|
| u01 | backend/lianyu-service (1/3) | reviewed | 9 | 逐行读 `ConversationService`（发送 / 流式落库 / 分页 / seq 分配）、`CharacterService` 删除链路、`SseChatStreamHelper` 收尾顺序；模式扫描 `GroupChatService`、`VoiceCallService` |
| u02 | backend/lianyu-service (2/3) | reviewed | 6 | `RelationshipStateService` 全文（decay / applyEvents / recordUserTurn）、`MomentsCommentOrchestrator` 锁与幂等段 |
| u03 | frontend/src (1/2) | reviewed | 10 | `i18n/index.js`、四个语言包键位（脚本拉平后做结构 diff）、`constants/language.js`、`useStreamAbort.js`、`api/conversation.js` |
| u04 | scripts (1/2) | sampled | 4 | 精读 `_upload_update_assets.py`（保留策略、写序、签名），其余按清单 + grep 抽样 |
| u05 | backend/lianyu-dao | reviewed | 9 | 57 个迁移的版本序列、`V1` 的唯一键、`V31/V56/V57` 全文、`NOT NULL ADD COLUMN` 默认值、`Message` 实体与逻辑删除配置 |
| u06 | backend-agent | reviewed | 8 | `tool_loop.py` 全文、`chat_turn.py` 图与工具名单、`stream_chat.py` 帧/异常边界、`gateway.py` 状态码分类、`main.py` 两个 chat 端点、四个工具实现 |
| u07 | frontend/src (2/2) | reviewed | 7 | `ChatPage.vue` 发送/重试/同步气泡段、`assistantStreamDrain.js` 全文、`GroupChatPage.vue` 使用点 |
| u08 | backend/lianyu-web | reviewed | 8 | `AgentSseParser`、`AgentStreamBridge`（accept/abort/finish 三条收尾）、`ChatTurnAdapter`、`AgentToolCatalog` |
| u09 | frontend/electron | sampled | 3 | `qqBridge.js` 消息处理与 404 自愈、`updater.js` 版本比较与校验；`main.js`（3078 行）仅结构扫描 |
| u10 | 其他模块（root/.github/admin 等） | sampled | 6 | 两级 nginx 的 SSE 段、`docker-compose.yml` 的 Redis AOF、`deploy/asr/server.py` 入参与错误分支 |
| u11 | backend 小模块合集 | sampled | 5 | `TotpUtil`、`AdminAuthorizationService`、admin controller 清单、service 测试类清单 |
| u12 | 其他模块（lianyu-app/common 等） | sampled | 6 | `frontend/shared/sseParser.js` 全文、`UserInputSanitizer` 全文、`OutboundUrlValidator` 结构、`test_upload_update_assets.py`；installer 的 C# 工程未逐行读 |

全部 12 个单元均已检查，无 skipped。`sampled` 的 5 个单元只做了代表性精读与清单/模式扫描，**不能视为全量已读**。

---

## 2. 发现

### 2.1 会话回合与流式收尾（u01 / u07 / u08）

#### C-01 · high · `frontend/src/pages/ChatPage.vue:1440` — 流式中断重试没有幂等键

- 证据：`ChatPage.vue:1437-1443` 在 `isNetworkError(err)` 为真时 sleep 800ms 后**再调一次 `attemptStream()`**；`useStreamAbort.js:10` 的 `if (err.name === 'TypeError') return true` 把任何 TypeError（含流读到一半 `reader.read()` 抛的）都判成网络错误。`api/conversation.js:67` 的 `POST /conversation/{id}/messages/stream` 无幂等键（全仓 `idempotencyKey` 只出现在动态评论），后端 `ConversationService.java:330` 在调模型前**无条件** `messageMapper.insert(turn.userMsg())` 并新分配 seq。
- 影响：弱网下同一条用户消息落库两次、模型生成两次、上游计费两次；第一次连接被重置后 `AgentStreamBridge.java:138` 走 `callback.onComplete(partial, null)`，把半截文本按正式回复落库 → 客户端从未完整展示过的幽灵消息进入历史与下一轮上下文。
- 修复：发送侧生成 `clientMessageId` 并在重试时复用，后端按 `(conversation_id, client_message_id)` 去重（Flyway 加列 + 唯一键）；重试只在「未拿到 response」阶段进行，drain 阶段的 TypeError 不再整请求重发。

#### C-02 · medium · `backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentStreamBridge.java:138` — 断流时半句回复按正式消息落库

- 证据：`accept()` 在 `emitter.send` 抛 IOException 时（:74-76 / :83-85）调 `abort()`，最终 `callback.onComplete(partial, null)`（:138）——error 为 null，`ConversationService.onComplete` 走正常落库分支。而同链路的失败收尾 `SseChatStreamHelper.java:67-85` 传 `BusinessException`，`onComplete` 因 `if (error != null && !isClientStreamDisconnect(error)) return;` 直接不落库（`isClientStreamDisconnect` 只判 `java.io.IOException`）。两条收尾路径策略相反，且都没给消息打 partial 标记。
- 影响：用户关窗/切网时，半句回复被当成完整回复存库，刷新后出现截断文字，并被当事实进入下一轮上下文与记忆提取。
- 修复：`abort()` 改用带中断语义的异常，让上层可区分；若产品要求保留，则给 `message` 加 `status/partial` 列并在前端渲染「回复已中断」；补断流落库用例。

#### C-03 · low · `backend/lianyu-service/.../conversation/ConversationService.java:72` — `msg_seq` 键前缀与分配函数四处各自定义

- 证据：字面量 `"msg_seq:"` 在四处独立声明：`CharacterService.java:60`、`ConversationService.java:72`、`GroupChatService.java:104`、`VoiceCallService.java:62`；分配逻辑两份实现（`ConversationService.java:479-487`、`GroupChatService.java:1314-1322`），`VoiceCallService.java:722` 又单独 `increment` 一次。
- 影响：四处写同一会话的同一段编号空间，任一处改前缀/改语义就长出两套编号，只在撞 `uk_conv_seq` 时暴露；也是 AGENTS.md「单一事实来源」的直接违反，并使 C-04 的修复无法一次改全。
- 修复：抽 `MessageSequenceAllocator` 单点持有前缀与分配逻辑，四服务统一注入。

---

### 2.2 关系状态与衰减（u02）

#### C-04 · medium · `backend/lianyu-service/.../relationship/RelationshipStateService.java:180` — 衰减批次被「不满一天」的行长期占满

- 证据：`decayAll`（:177-181）`orderByAsc(lastDecayAt).last("LIMIT 500")`（`DECAY_BATCH_LIMIT=500`，:43）；`applyDecay` 在 `days < 1` 时于 :200 `return false`，**不写 `lastDecayAt`**，这些行的 `last_decay_at` 保持 NULL/旧值，MySQL 中 NULL 排最前 → 每轮都被优先选中、每轮都被丢弃，稳定占住 500 名额。:174-179 注释称「结算过的行会排到队尾形成轮转」，只对通过 `days≥1` 的行成立。
- 影响：活跃行数超过 500（注释提到线上 936 行）后，其余用户的关系衰减彻底停止（亲密不回落、期待不累积），无错误无告警。
- 修复：查询加 `WHERE last_interaction_at IS NOT NULL AND (last_decay_at IS NULL OR last_decay_at < :cutoff)`，按 `COALESCE(last_decay_at, last_interaction_at)` 排序；补 600 行两轮轮转用例。

#### C-05 · medium · `backend/lianyu-service/.../relationship/RelationshipStateService.java:185` — 整行 `updateById` 的 lost update

- 证据：该类无 `@Transactional`（全文件计数 0），`RelationshipState` 无 `@Version`；`decayAll` 一次 select 最多 500 行（:177-181），随后逐行 `updateById(state)`（:185）写回全部列；`recordUserTurn/recordAssistantTurn` 也走 :132/:165 的 `updateById`，由 `ConversationService.java:265/333/300/390` 在模型调用前后调用。衰减侧用的是批次开始时的旧快照。
- 影响：用户刚聊完看到好感上涨，30 分钟一次的衰减把整行写回旧值，增量消失并触发 phase 重算；连续快发的两个回合之间也会互相吞掉分数。
- 修复：加 `version` 列 + `@Version` 或改为数据库侧原子增量，decay 只更新自己负责的列；`recordUserTurn/recordAssistantTurn` 补 `@Transactional`。

#### C-06 · medium · `backend/lianyu-service/.../moments/MomentsCommentOrchestrator.java:816` — 评论锁无 owner token

- 证据：`tryLock`（:816-820）`setIfAbsent(LOCK_PREFIX + postId, "1", 90s)`，值恒为 `"1"`；`unlock`（:822-824）直接 `delete`。锁内要做模型调用（`executePeerComment` :326-400），超过 90s 锁过期后可被第二个 worker 抢到，第一个 worker 在 `finally` 里删掉第二个的锁，第三个随即又能进。`existsIdempotency`（:810-815）只能挡住已 insert 完的评论。
- 影响：同一条动态下同一角色重复评论、重复计费；`processAuthorReplyJob`（:271-296）同样会重复执行。
- 修复：锁值改为 UUID owner token，`unlock` 用 Lua 比较后删除；TTL 与模型超时对齐或续期。

---

### 2.3 数据完整性与迁移（u01 / u05）

#### C-07 · medium · `backend/lianyu-service/.../conversation/ConversationService.java:485` — `message.seq` 计数器无 DB 播种

- 证据：`reserveSeqBlock`（:483-486）只做 `opsForValue().increment(SEQ_KEY_PREFIX + conversationId, safeCount)`，取不到值回落 `safeCount`；全仓无任何 `MAX(seq)` 回填（只有 `CharacterService.java:298` 与 delete/clearMessages 删键）。`message` 表 `UNIQUE KEY uk_conv_seq (conversation_id, seq)`（`V1__init_schema.sql:54`）。Redis 有 AOF（`docker-compose.yml:271-281`），但 `down -v`、AOF 尾部丢失或换实例后计数归零。
- 影响：下一条 insert 撞唯一键 → 发送 500，且 seq 从 1 重来，同一会话连续失败直到追上历史最大值，无自愈路径。
- 修复：Lua 脚本「键不存在则用 `SELECT COALESCE(MAX(seq),0) FROM message WHERE conversation_id=?` 播种再 INCRBY」；或捕获 `DuplicateKeyException` 后重置重试一次。

> 迁移本身已核对干净：57 个 `V*.sql` 版本连续无重号，`NOT NULL ADD COLUMN` 均带默认值，`V56` 的幂等加列与 `V57` 的信任分修复语句与代码方向一致（`V57` 只改 `trust_score` 不重算 `phase`，但 `deriveFromScores` 对 `trust=50 / intimacy≤40` 的推导结果与旧值同为 FAMILIAR，未构成缺陷）。

---

### 2.4 契约：i18n 四语（u03 / u07）

#### C-08 · medium · `frontend/src/i18n/index.js:17` — zh-TW 从未注册，en/ja 缺键回落中文

- 证据：`index.js:7-9` 只 import zh/ja/en，:17 `messages: { zh, ja, en }`，:16 `fallbackLocale: 'zh'`；`locales/zh-TW.js`（约 26KB）全仓无引用（`grep -rn zh-TW frontend/src` 只命中自身与 `utils/dateLocale.js:4`）；`constants/language.js:3` 的 `SUPPORTED_LANGUAGES = ['zh','ja','en']` 永远返回不了 zh-TW。键位 diff：zh 583 / zh-TW 588（缺 7 多 12）/ en 582（缺 1）/ ja 575（缺 8）。`chat.scrollToBottom` 仅存在于 `zh.js:327`，却在 `ChatPage.vue:173`、`GroupChatPage.vue:131` 使用；同类还有 `header.logoutConfirm`（`AppHeader.vue:155`）、`home.eyebrow`（`HomePage.vue:4`，ja 缺）、`qqBridge.binding.providerRequired`（`QqBridgePage.vue:733`）。
- 影响：英文/日文界面出现中文按钮与确认文案（违反 AGENTS.md §3.2 四语齐）；繁体用户只能看简体，繁中文件成为死资产，后续新增 key 会继续漏且无测试拦截。
- 修复：注册 zh-TW 与语言选项、补齐缺键、加键位一致性单测（以 zh 为基准断言四语键集合相等）。

---

### 2.5 架构边界与输入边界（u06 / u12）

#### C-09 · medium · `backend-agent/app/tool_loop.py:128` — 无工具分支丢弃 system prompt

- 证据：`run_tool_loop` 在 :128 `if not specs:` 时走 `gateway.chat_sync(system=None, user_text=_flatten(messages), ...)`（:130-135），而 `_flatten`（:205-214）`for msg in messages: if role == "system": continue`——整段 system prompt（人设/记忆/关系/时间）被丢弃，只剩用户原话。两个调用点（`chat_turn.py:265`、`graphs/proactive_turn.py:116`）都在 `if tools:` 之后，工具名单是 Python 常量 `CHAT_TOOL_NAMES`，与 Java 侧 `AgentToolCatalog.java:42` 是两份独立名单（chat-turn 请求体只传 `enableChatTools`，不传工具名），任一不一致使 `resolve_tools` 过滤为空就会静默走到这里。
- 影响：命中后回复不再是角色口吻、记忆与关系上下文全部丢失，但 HTTP 200、无告警，属完全静默的降级。
- 修复：该分支保留 system（作为 system 消息传入或拼进 user_text）；`not specs` 加 warning 日志；工具名单收敛为单一来源（Java 传 `toolNames`，Python 只做存在性校验）。

#### C-10 · medium · `backend/lianyu-common/.../util/UserInputSanitizer.java:60` — 输入策略正则误杀正当内容

- 证据：`FORCED_COMMAND_PATTERNS`（:59 起）含 `\bsudo\s+`（:60）、`rm -rf`、`执行(以下|如下)?命令`；`INJECTION_PATTERNS` 含 `role\s*:\s*system`、`act as (system|admin|root)`。`assertPolicyAllowed`（:105）命中即抛 `BusinessException(CONTENT_POLICY_VIOLATION)`，无豁免或分级；调用点在主聊天链路：`ConversationService.java:623`、`GroupChatService.java:349`、`VoiceCallService.java:145`、`VoiceCallDuplexSession.java:406`，`wrapStoredTextForModel`（:141）还会对已入库文本再判一次。
- 影响：用户分享技术经历（装软件、跑命令）、玩角色扮演（「你现在是系统管理员」）或提开发者问题时会被判成注入而发不出去，提示语是「包含疑似强制系统/终端命令内容」，用户既看不懂也无法绕过。
- 修复：把终端痕迹类模式从硬拒绝降级为仅记录（消息本就以 `trusted="false"` 包裹下发），注入规则要求多特征同时命中；补正负样本表驱动用例。

---

## 3. 已查未发现问题

- **SSE 帧契约**（u06/u08/u12）：Python 侧 `stream_chat.py` 只产出 `content/replace/error/[DONE]` 与 `: keep-alive`；`main.py:200`、`:494` 两个 chat 端点都把整段 `async for` 包在 `try/except` 内，异常时补 `sse_error` + `DONE_FRAME`，不存在「无 error 帧断流」；Java `AgentSseParser` 的 `content→replace→error` 取值顺序、`extractString` 的转义处理与前端 `shared/sseParser.js` 的解析语义一致，`pieces` 由 Java 在 `beforeStreamComplete` 生成、Python 从不发送，两侧不冲突。
- **ChatTurn 请求/响应字段**（u08）：`ChatTurnAdapter.toBody` 的 camelCase 字段与 Python pydantic alias 同名；`call()` 读取的 `systemPrompt/messages/promptTokens` 与 `main.py:486` 返回值一致。
- **消息分页游标**（u01）：`getMessages` 的 `lt(seq)` + `orderByDesc(seq)` + `LIMIT n+1` + `subList` + `reverse` + `nextBeforeSeq=fetched.get(0).getSeq()` 自洽，翻页无重叠无空洞。
- **Redis 有 AOF**（u10）：`docker-compose.yml:271-281` 的 `redis-server --appendonly yes` + `redis_data` 卷，正常重启不会回退计数（C-07 只在卷丢失/换实例时触发）。
- **网关路由与 SSE 代理**（u10）：`deploy/api-gateway/nginx.conf` 的 SSE 段均 `proxy_buffering off`、`read_timeout` 与 Java 的 1800s 对齐；`deploy/agent-lb/nginx.conf:32-36` 为 `buffering off + chunked_transfer_encoding on`。
- **QQ 桥与更新器**（u09）：`qqBridge.js` 每条消息重读绑定、按 userId 隔离会话、404 时清缓存重解析并只重试一次；`updater.js` 的 `compareVersions` 按点分数字比较，下载前后有 latest.yml 与 sha512 校验、出站白名单。
- **Flyway 迁移完整性**（u05）：57 个版本连续、无重号，`ADD COLUMN ... NOT NULL` 全部带 DEFAULT，`V56` 用 `information_schema + PREPARE` 做幂等加列。
- **admin 小模块**（u11）：`TotpUtil` 的 HMAC-SHA1 动态截断与 ±1 步长实现正确，`AdminAuthorizationService` 会话校验带 `revoked_at/expires_at/status` 三重条件。

---

## 4. 未验证与抽样说明

- `sampled` 单元（u04、u09、u10、u11、u12）只做了代表性文件精读 + 清单/模式扫描，**不代表已全量阅读**：`frontend/electron/main.js`（3078 行）只看了 IPC/窗口注册结构；`installer` 的 C# 工程（`MainWindow.xaml.cs` 539 行、`InstallerEngine.cs` 262 行）未逐行读；`scripts/` 中除 `_upload_update_assets.py` 外的脚本未逐行核对。
- 测试未取得背书：`pytest -q backend-agent` 在收集阶段即 26 个 ERROR，根因 `ModuleNotFoundError: No module named 'aiomysql'`（本机未装 dev 依赖，属环境缺失）；`mvn -q -f backend/pom.xml test` 与两个 npm 套件（`admin` 无 `node_modules`）未执行。本报告结论全部来自静态证据与调用链推演。
- 需要人工确认的一处语义：C-02 的「断流要不要落库」取决于产品预期，两种修法（不落库 / 落库但标记 partial）需产品拍板。

---

## 5. 建议修复顺序

1. **C-01（high）**：先做幂等键与重试收敛，它直接产生重复落库、重复计费与幽灵消息，用户可感知且不可逆。
2. **C-04 + C-05（衰减）**：一起改，前者让衰减「停在 500 行」，后者让衰减「写回旧值」，同属关系分数的可信度问题。
3. **C-06（评论锁）**：改造成本低（owner token + Lua），能立刻止住重复评论与重复计费。
4. **C-07 + C-03（seq 单点）**：先抽 `MessageSequenceAllocator`（C-03），再在同一处补 DB 播种（C-07），避免改两遍。
5. **C-09 + C-02**：都是「静默降级」类问题，一个丢人设、一个存半句，改完补日志后可观测。
6. **C-08（i18n）**：机械但量多，建议连同键位一致性测试一次做完。
7. **C-10（输入策略）**：需要产品/运营确认误杀容忍度后再动正则，放最后。

---

## 6. 跨专家转交

| 转交给 | 事项 | 位置 |
|---|---|---|
| security | `TotpUtil.verify` 用 `String.equals` 比较 6 位验证码（非恒定时间）；`otpauth://` URI 未对 issuer/account 做 URL 编码 | `backend/lianyu-admin/.../identity/TotpUtil.java:16,12` |
| security | `UserInputSanitizer` 的正则是「黑名单语义」，本身可被同形字/分隔符绕过；且策略在 Java 侧硬编码 | `backend/lianyu-common/.../UserInputSanitizer.java:26-75` |
| reliability | `execute_tool_calls` 只捕 `(httpx.HTTPError, RuntimeError)`，`ValueError/KeyError/asyncio.TimeoutError` 会穿出生成器；`gateway` 熔断只按 `provider|baseUrl` 聚合 | `backend-agent/app/tool_loop.py:101` |
| reliability | `RelationshipDecayScheduler` 的异常只 `log.warn`，衰减长期失败不可见（与 C-04 叠加会完全静默） | `backend/lianyu-service/.../relationship/RelationshipDecayScheduler.java` |
| perf | `AdminAuthorizationService.require` 每次调用两次 `currentAdminId()`，即两次带 JOIN 的 COUNT 查询；完整 SELECT * + `updateById` 整行写在大表上是额外写放大（与 C-05 同源） | `backend/lianyu-admin/.../AdminAuthorizationService.java:29-33` |
| maintainability | `updater.js` 的版本校验只接受 `x.y.z` 纯数字，预发布后缀会被解析成 NaN 而不提示更新 | `frontend/electron/updater/updater.js` |
| maintainability | `locales/zh-TW.js` 是无人引用的 26KB 孤儿文件（若确定不上繁体，应先删除而非留着） | `frontend/src/i18n/locales/zh-TW.js` |
