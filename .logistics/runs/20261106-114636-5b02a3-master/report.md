# 予念（LianYu-PC）全项目审查报告

- 运行：`20261106-114636-5b02a3-master`
- 范围：整个仓库（非 diff），分支 `main`，HEAD `c652e14`，1108 个文件 / 139994 行
- 专家：5 位（正确性 / 安全 / 健壮性 / 可维护性 / 性能），12 个单元，共 50 条发现（critical 1 / high 10 / medium 27 / low 12）
- 结论口径：本报告只描述证据与影响，不给健康分、不做门禁判定；所有结论均可回溯到 `file:line` 与专家报告。

## 一句话结论

这套「Electron + Java + Python」三层分层的骨架是清楚的，真正的问题集中在两类：**边界上的默认值**和**异常路径的收尾**。前者让公网 `/api/agent/*` 在缺少身份头时直接放行、让调用方自带模型地址、让缺主密钥时退回明文、让更新器在无公钥时跳过验签；后者让流式 `[DONE]` 吞掉工具调用、让客户端中断打开熔断、让 SSE 收尾异常把回复卡到 30 分钟超时、让群聊失败静默缺席。50 条发现里 1 条 critical、10 条 high——没有一条需要推翻架构，但 P0 的两条必须在下次公网发布前修掉。

范围与方法：5 位专家各自只读审查同一份 12 单元切分，独立给证据、独立跑测试；主编逐条复核了 critical/high 的源码与复现描述，并按严重度重新排序、合并关系。专家原始报告保留在 `handoff/<expert>/<expert>.md`，本报告不替代它们。

## 覆盖情况

12 个单元 × 5 位专家全部覆盖，无 skipped；`R` = reviewed（通读核心文件）、`S` = sampled（按核心清单抽样）、`—` = 未覆盖。每格为该专家在本单元实际读过的文件数。

| 单元 | 主题 | 文件数 | 正确性 | 安全 | 健壮性 | 可维护性 | 性能 |
|---|---|---:|---|---|---|---|---|
| u01 | backend/lianyu-service (1/3) | 120 | R 36 | S 32 | R 41 | S 30 | R 32 |
| u02 | backend/lianyu-service (2/3) | 120 | R 36 | S 32 | S 38 | S 30 | S 32 |
| u03 | frontend/src (1/2) | 120 | S 36 | S 32 | R 34 | S 30 | S 32 |
| u04 | scripts (1/2) | 120 | S 36 | S 32 | S 31 | S 30 | S 32 |
| u05 | backend/lianyu-dao | 110 | R 36 | S 32 | S 30 | S 30 | R 32 |
| u06 | frontend/src (2/2) | 103 | S 36 | S 32 | S 35 | S 30 | R 32 |
| u07 | backend-agent | 102 | R 36 | S 32 | R 36 | S 30 | R 32 |
| u08 | backend/lianyu-web | 71 | S 36 | S 32 | R 36 | S 30 | S 32 |
| u09 | frontend/electron | 68 | S 36 | S 32 | S 31 | S 30 | S 32 |
| u10 | 其他模块（(root)、.github、admin 等） | 67 | S 36 | S 32 | S 30 | S 30 | S 32 |
| u11 | backend（小模块合集 59 文件） | 59 | S 36 | S 32 | S 30 | R 30 | S 32 |
| u12 | 其他模块（backend/lianyu-app、backend/lianyu-common、backend 等） | 48 | S 36 | S 32 | S 31 | S 30 | S 32 |

覆盖结论：5/5 专家、12/12 单元，全部单元达到 30 文件下限并覆盖 core 清单；u04（scripts）、u09（electron）、u10（root/.github/admin）、u12（backend 小模块）没有专家标 reviewed，只做了抽样——这四块是本轮结论最薄的地方，已在「覆盖缺口」里单列。

## 发现

共 50 条，按严重度排序（critical → high → medium → low），同级别内按专家顺序。每条四行：简述 / 详细问题 / 位置与证据 / 影响与修复。文末按 [可直接修] 与 [需方案] 两类分别给出清单。

### 严重（1）

- [critical] [u07] [plan] SEC-EA2CB — 简述：任何人只要把请求里的用户编号换成别人，就能读到对方的聊天记忆和日记；这些内部接口目前没有登录校验。
详细问题：Agent 运行时缺少 X-User-Id 时直接放行，body.userId 可任意指定导致跨用户读取记忆。
位置：backend-agent/app/main.py:150 · 证据：backend-agent/app/main.py:146-151 只做“有头才比对”：user_id_header: str | None = Header(default=None, alias="X-User-Id") → if user_id_header and str(body.user_id) != user_id_header: raise HTTPException(403)。同一写法出现在 :206(chat-stream)、:313(session-summary)、:365(text/chat)、:476、:504，缺头即跳过全部校验。（详见专家报告）
影响：攻击者无需账号即可枚举 userId 读取他人记忆摘要、日记、动态与评论，内容以模型可读的明文形式返回；还能借平台额度生成任意回复。Java 侧 Sa-Token 鉴权被整条绕过，等于把跨租户读取能力挂在公网网关路径上。 · 修复：在 Agent 侧引入真正的服务间鉴权（共享密钥签名 + 时间窗 + 重放防护），并由网关默认拒绝外部直连 /api/agent/。

### 高（10）

- [high] [u07] [direct] COR-559B1 — 简述：模型在流式回复里要求调用工具（查时间、天气、记忆等）时，这条请求会被丢掉，用户只看到角色「说要做」却没有动作。
详细问题：流式回合收到 [DONE] 时提前 return，聚合好的 tool_calls 被丢弃，工具永不执行。
位置：backend-agent/app/gateway.py:313 · 证据：`_stream_once` 在 `data == "[DONE]"` 时直接 `return`（gateway.py:312-313），而把聚合好的 `pending` 写进 `tool_sink` 的收尾代码在 `async with` 之后（gateway.py:348-352），return 会整体跳过。用 httpx.MockTransport 复现：上游流以 `data: [DONE]` 结尾时 `tool_sink == []`；（详见专家报告）
影响：所有走流式通道的回合，只要模型选择调用工具就不会执行：查时间/天气/记忆检索等工具形同失效，回复退化成「我这就帮你查」之类的空话，而且没有任何错误提示与日志（pending 非空但从未上报）。 · 修复：把收尾的 return 改成 break，让循环退出后仍然执行 tool_sink 落盘。

- [high] [u02] [plan] COR-CA186 — 简述：用户一句话里说出的多件事（例如「以后叫我小林，我今天很崩溃」），只有最后一件会被记住，前面几件被同一条记录悄悄覆盖。
详细问题：同一条用户消息命中的多条记忆共用 sourceHash，后写的覆盖先写的。
位置：backend/lianyu-service/src/main/java/com/lianyu/service/memory/MemoryWriter.java:199 · 证据：`resolveSourceHash`（MemoryWriter.java:199）→ `computeSourceIdsHash`（:266-281）只用排序后的 source_msg_ids 求 sha256，而 `processSummary` 每次传 `List.of(memory.sourceMsgId())`（:105-108）。另一侧 `MemoryExtractionHeuristics.extractAll`（:20-30）会把 profile/relationship/keyword 三组结果拼在一起，（详见专家报告）
影响：记忆能力静默丢数据：一句话里的多个事实只留下最后一个，用户会反复发现「我刚说过它又忘了」；每次覆盖还清空 vec_id 并触发一次向量删除+重建，检索存在短暂空洞，嵌入成本翻倍。 · 修复：让哈希标识「一条记忆」而不是「一条消息」：把记忆类型与归一化摘要一起纳入 sourceHash，并为存量行做一次回填。

- [high] [u06] [plan] COR-54E29 — 简述：发送消息遇到网络抖动时客户端会自动重发，但服务端每次都当成新消息，于是同一句话被记两遍、角色也回两遍。
详细问题：网络失败后原样重发同一回合，用户消息与角色回复各落两份。
位置：frontend/src/pages/ChatPage.vue:1439 · 证据：ChatPage.vue:1433-1441：`catch (err) { if (isNetworkError(err)) { await sleep(800); if (signal.aborted) return; streamResult = await attemptStream() } }`，而 `attemptStream` 就是再 POST 一次 `/conversation/{id}/messages/stream`（:1420-1421）。（详见专家报告）
影响：一次网络抖动就会让同一条消息在历史里出现两次、角色回复两遍，模型调用与 token 双倍计费；群聊/连续会话的 seq 与气泡顺序也会错乱，用户看到「自己说了两遍」。 · 修复：给发送加客户端幂等键并在服务端去重，或把自动重试限制在「请求确定未送达」的场景。

- [high] [u07] [plan] SEC-E06CB — 简述：外部调用者可以指定“模型地址”，服务就会照这个地址发请求，内网服务因此可能被探测，返回内容还会被带出来。
详细问题：Agent 接受调用方自填 baseUrl/apiKey，出站请求可打到任意主机并回显响应。
位置：backend-agent/app/gateway.py:92 · 证据：backend-agent/app/main.py:284-285 TextChatBody.base_url/api_key 来自请求体（session-summary :347-348、chat-stream :213 的 text_base_url/text_api_key 同理）；backend-agent/app/gateway.py:92 normalize_openai_base_url 仅做字符串归一（去尾斜杠、去 /v1、固定追加 /v1/chat/completions），随后经 app/http_client.py new_client() 用 httpx 发出请求；（详见专家报告）
影响：结合 SEC-01 无需登录即可让服务向内网任意 HTTP 服务发起 POST（backend:8080、minio:9000、milvus、运维面板等），把 2xx 响应体带回；也可作为数据外带通道，把请求体里的内容送到攻击者服务器。 · 修复：Python 出站对 base_url 做与 Java OutboundUrlValidator 等价的白名单校验（禁私网/回环/元数据地址，解析结果钉 IP），并只允许 vault 中登记的域名。

- [high] [u08] [plan] SEC-A3F36 — 简述：客户端的限流按“来源 IP”算，而这个 IP 可以从请求头里随便写，攻击者每次换一个就能绕过注册和上传的次数限制。
详细问题：默认信任 X-Forwarded-For 首个值，公网可伪造来源 IP 绕过按 IP 的限流。
位置：backend/lianyu-web/src/main/java/com/lianyu/web/util/ClientIpResolver.java:28 · 证据：ClientIpResolver.java:16 @Value("${lianyu.security.trust-forwarded-for:true}") 默认为 true；:24-28 if (trustForwardedFor) { String forwarded = request.getHeader("X-Forwarded-For"); … int comma = forwarded.indexOf(','); return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim(); } —— 取的是客户端可写的…（详见专家报告）
影响：所有按 IP 的额度（注册 3 次/日、头像 20 次/日、桌面感知 30 次/小时、认证 30 次/分钟）在公网均可被任意绕过，可批量注册、刷模型额度、规避风控；按用户名维度的限额仍生效但无法挡住换号。 · 修复：改为只信任网关写入的 X-Real-IP（或取 XFF 最后一段），并把 trust-forwarded-for 默认改为 false，部署环境显式打开。

- [high] [u10] [direct] SEC-701CA — 简述：少配一个环境变量，用户自己填的模型密钥就会以明文存进数据库，而系统只在启动日志里警告一句。
详细问题：未配置 LIANYU_MASTER_KEY 时 Jasypt 静默降级为明文，用户模型 API Key 直接明文入库。
位置：backend/lianyu-security/src/main/java/com/lianyu/security/util/JasyptUtil.java:63 · 证据：JasyptUtil.java:23 无主密钥时 log.warn("LIANYU_MASTER_KEY not set — Jasypt encryption disabled. Set env var before production use.") 然后不建 encryptor；:61-65 public String encrypt(String plaintext) { … if (currentVersion == null || encryptors.isEmpty()) { log.warn("No Jasypt encryptor available, returning p…（详见专家报告）
影响：运维漏配一个变量，全部用户自填的模型 Key 会以明文落库；数据库备份、只读账号、误导出或任意 SQL 读取都会造成 Key 泄露，而调用方看不出任何异常（只有启动时一条 warn）。 · 修复：加密路径失败即关闭（fail closed）：无 encryptor 时抛异常而不是返回明文，并在启动阶段对缺失主密钥做硬校验。

- [high] [u07] [direct] REL-34EFB — 简述：用户中途关掉聊天窗口，系统会把它当成模型服务故障记一笔；攒够几次，所有用户都会被提示“模型繁忙”，哪怕模型本身好好的。
详细问题：客户端中断被记成上游失败，5 次用户放弃就能打开熔断器。
位置：backend-agent/app/gateway.py:279 · 证据：stream_text 里 `try:`（L259）包住了 `async for delta in self._stream_once(...): yielded; yield delta`（L264-268），于是消费方关闭连接产生的 GeneratorExit 与任务取消会落到 `except BaseException as exc: await breaker.on_error(exc); raise`（L279-281）。（详见专家报告）
影响：一次 5 个用户手滑关窗（或前端 abort、网关超时、进程收到取消）就能把该 provider+baseUrl 的熔断器打开 30 秒；期间所有用户的所有请求都不再打上游、统一返回“对方模型暂时繁忙，请稍后再试”，日志还把原因写成上游故障，误导排查方向。 · 修复：把“消费方主动放弃”从熔断样本里剔除，只对真实上游错误记失败。

- [high] [u01] [direct] REL-42BC8 — 简述：回复已经生成完、准备收尾时只要冒出一点非网络异常，这条流就不会发结束标记、回复也不会存进数据库，用户界面会一直转圈。
详细问题：finishSseSuccess 只接 IOException：收尾异常会让流不结束、回复不落库。
位置：backend/lianyu-service/src/main/java/com/lianyu/service/ai/SseChatStreamHelper.java:51 · 证据：L51-65：`try { if (callback != null) callback.beforeStreamComplete(emitter, fullContent); emitter.send(SseEmitter.event().data("[DONE]")); emitter.complete(); } catch (IOException e) { log.warn("SSE complete failed", e); emitter.complete(); }`，（详见专家报告）
影响：收尾阶段一旦抛非 IOException（emitter 已完成/客户端刚断、回调里 DB 写入失败、序列化失败），用户拿到的是截断的回复且连接最长挂 30 分钟不收尾；刷新后这条回复消失（没落库、没触发记忆摘要与通知），服务端只留一条 Reactor onErrorDropped，几乎无法定位。 · 修复：收尾与 [DONE] 发送、落库回调三段各自兜底，保证 emitter 一定被 complete、onComplete 一定被调用一次。

- [high] [u01] [plan] PRF-71D14 — 简述：群聊里同时有 8 个会话在回复时，所有回复会永久停住，用户只能重启应用或等服务重启。
详细问题：群聊编排任务与内层生成共用同一个 8 线程池，外层 join 阻塞导致自饥饿死锁。
位置：backend/lianyu-service/src/main/java/com/lianyu/service/conversation/GroupChatService.java:370 · 证据：第 370 行 `CompletableFuture.runAsync(() -> {` 把整轮编排提交到 groupChatExecutor；第 394-399 行对每个成员 `CompletableFuture.supplyAsync(() -> generateCharacterReply(...), taskExecutor)` 又提交到同一个池；第 401-407 行 `while (!pending.isEmpty()) { CompletableFuture.anyOf(pending.toArray(EMPTY_CF_ARRAY)).join();` 在池线程里阻塞等待。（详见专家报告）
影响：群聊功能在 8 个并发回合后整体挂死：外层不返回、内层不执行，没有超时兜底，只能重启进程恢复；4-7 个并发时内层生成被压到剩余线程上串行执行，回复延迟成倍上升且多角色顺序错乱；被卡住的线程还占着未收尾的 SSE 请求。 · 修复：把「回合编排」和「单角色生成」拆成两个线程池（或改为非阻塞聚合），禁止池线程阻塞等待同池任务。

- [high] [u06] [plan] PRF-9225F — 简述：桌宠和快捷聊天窗口为了几个按钮，启动时要解析 1.1MB 的脚本（压缩后 361KB），窗口打开明显变慢、内存占用变高。
详细问题：element-plus 全量注册把 1118 KB 的 launcherBootstrap chunk 拖进桌宠/快捷聊入口。
位置：frontend/src/main.js:3 · 证据：实测 frontend/dist（与源码同批构建）：`launcherBootstrap-CEXfa8Qx.js` 1118.0 KB raw / 360.9 KB gzip，文件内含 element-plus 标识 10 次、ElMessage 5、ElScrollbar 4、el-button 3、el-dialog 1；`main-DTgPoq__.css` 398.7 KB raw / 54.3 KB gzip 为 element-plus 全量样式；dist/launcher.html 引用的 `launcher-BE_O1lcz.js` 自身仅 706 字节，（详见专家报告）
影响：桌宠窗口与快捷聊天窗口每次冷启动都要解析/编译 1.1MB JS 与 398KB CSS（gzip 后 415KB 需解压），而它们只用到几个 el-* 组件；Electron 在启动后 4s/10s/12s 预热这两个窗口（frontend/electron/main.js:1496-1513），三个窗口同时解压同一份大 chunk，低配机首屏可感知卡顿、常驻桌宠多吃几十 MB 渲染内存。 · 修复：element-plus 改按需引入，并让 launcher/quick 入口不再依赖含全量 UI 库的共享 chunk。

### 中（27）

- [medium] [u02] [plan] COR-05886 — 简述：后台每半小时的关系衰减和用户聊天会同时改同一份关系数据，用户的信任/亲密度增长经常被旧快照覆盖，表现为「聊很久关系也不涨」。
详细问题：关系状态整行读改写且无版本控制，衰减任务会覆盖互动增量。
位置：backend/lianyu-service/src/main/java/com/lianyu/service/relationship/RelationshipStateService.java:132 · 证据：`recordUserTurn`（RelationshipStateService.java:116-133）流程是 `loadOrCreateState` → 内存里累加事件/在场分 → `relationshipStateMapper.updateById(state)` 写回整行；`recordAssistantTurn`(:163-165) 与 `decayAll`(:183-187) 同一模式。（详见专家报告）
影响：trust/intimacy 增量被静默吞掉（用户感知是关系不推进）；`lastInteractionAt` 被写回旧值后 `isNewPresenceDay` 会重复判定「新的一天」，DAILY_PRESENCE 连续天数被重复累加，里程碑判定随之偏移。 · 修复：把关系状态改成带版本的条件更新，或让衰减只更新衰减相关列，避免整行回写覆盖并发增量。

- [medium] [u05] [plan] COR-F6ADD — 简述：修「重复会话」的那次数据库迁移直接把多余会话删了，里面的聊天记录从界面上消失，却永远留在库里。
详细问题：V34 去重删掉重复会话，但会话下的消息既不迁移也不清理。
位置：backend/lianyu-dao/src/main/resources/db/migration/V34__conversation_single_unique.sql:2 · 证据：V34 开头 `DELETE c1 FROM conversation c1 INNER JOIN conversation c2 ... AND c1.id > c2.id` 只删 conversation 行（第 1-8 行），之后才建唯一键 `uk_conversation_user_character_mode`。`message` 表（V1__init.sql:45-56）只有普通列 conversation_id 和 `UNIQUE KEY uk_conv_seq (conversation_id, seq)`，没有外键约束，所以被删会话下的消息不会被级联删除，（详见专家报告）
影响：受影响的用户打开角色会话只剩被保留的那条，历史消息在 UI 上永久消失（数据其实还在，只是永远读不到）；同时 message 表留下永久孤儿行，按会话统计/清理/备份都会失真。 · 修复：补一条数据修复迁移：先把重复会话的消息改挂到保留会话并重排 seq，无法改挂的先归档再删除，最后校验孤儿行为 0。

- [medium] [u07] [plan] COR-50DF0 — 简述：记忆向量同步任务如果做到一半进程被重启，这条任务会一直卡在「执行中」，之后再也不会重试，那条记忆就永远搜不到或永远删不掉。
详细问题：memory_outbox 认领后进程崩溃会永久停在 RUNNING，无人回收。
位置：backend-agent/app/memory/relay.py:41 · 证据：relay 用 `UPDATE memory_outbox SET status = 'RUNNING' WHERE id = %s AND status = 'PENDING'` 认领（relay.py:41-48），失败走 `_retry_or_dead` 指数退避（:161-176），`drain_once` 只捞 `status='PENDING' AND next_attempt_at <= NOW()`（:111-119）。`requeue_stuck()`（:207-225）的 docstring 写明「运维用」，（详见专家报告）
影响：进程在认领后被杀（部署滚动重启、OOM、容器驱逐）时该行永久 RUNNING：UPSERT 场景记忆没有向量、检索不到；DELETE 场景向量残留在 Milvus，用户删掉的记忆仍会被角色说出来。没有任何告警，只能靠人工调运维接口。 · 修复：给认领加租约并在常驻循环里定期回收超时的 RUNNING 行，而不是依赖人工调用运维接口。

- [medium] [u11] [direct] SEC-1101A — 简述：管理员密码一旦泄露，第二步验证码可以无限次尝试，因为只有密码输错才会计数锁定。
详细问题：管理员登录 TOTP 错误不累计失败次数，6 位验证码可在线穷举。
位置：backend/lianyu-admin/src/main/java/com/lianyu/admin/identity/AdminAuthService.java:43 · 证据：AdminAuthService.java:37-39 仅密码不匹配分支执行 UPDATE admin_user SET failed_attempts=failed_attempts+1, locked_until=CASE WHEN failed_attempts+1>=5 THEN DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 15 MINUTE) …；:42 缺 otp 时返回 otpRequired；（详见专家报告）
影响：攻击者拿到管理员密码（复用/撞库/钓鱼）后可对 6 位 TOTP 做在线穷举（成功概率随请求数线性上升），5 次锁定策略形同虚设；管理员后台可读用户列表、发放客服授权、发布客户端版本。 · 修复：TOTP 校验失败与密码失败同等计数，达到阈值同样锁定并写审计。

- [medium] [u07] [plan] SEC-D6178 — 简述：外部任何人都能反复调用识图接口，用平台自己的模型额度生成内容，花的是运营方的钱。
详细问题：无鉴权的桌面感知接口会消耗平台自己的多模态 Key，且没有速率限制。
位置：backend-agent/app/main.py:143 · 证据：backend-agent/app/main.py:143-151 /agent/desktop-observe 无鉴权（同 SEC-01 的头比对逻辑）；app/vision.py:69-70 base_url=settings.multimodal_base_url、api_key=[REDACTED:secret] DashScope Key）；app/desktop_observe.py:86 只有调用方未带 text_base_url/text_api_key 时才回落到平台 Key；（详见专家报告）
影响：外部可无限次把任意图片与 windowTitle 送进平台模型，按调用量直接消耗运营方额度（成本攻击），并让平台承担内容风险；桌面截图内容也会流向模型供应商。 · 修复：该端点只允许通过内部签名从 Java 转发，并加用户/IP 双维度配额与单次图片大小上限。

- [medium] [u05] [plan] SEC-1AD35 — 简述：十个真实的模型密钥以加密形式写进了代码库，而解密用的那把钥匙全组共用，谁拿到代码和钥匙谁就能用这些额度。
详细问题：团队默认 Key 池的十份密文随迁移入库，解密主密钥在全体开发间共享。
位置：backend/lianyu-dao/src/main/resources/db/migration/V22__team_default_vault_pool_encrypted.sql:1 · 证据：V22__team_default_vault_pool_encrypted.sql:1-13 连续 10 条 UPDATE api_key_vault SET api_key_encrypted 列写 <base64 密文>, key_version='v1', base_url='https://clove.dpdns.org', model_default='deepseek-v4-flash' WHERE id=N AND vault_scope='DEFAULT'；（详见专家报告）
影响：任何同时拿到仓库与团队主密钥的人（含离职人员、泄露的 .env、CI 缓存）都能解出 10 个真实模型 Key 并直接消耗额度；主密钥一换，所有环境所有密文同时失效，只能全量重新加密与轮换。 · 修复：把默认 Key 池移出仓库，改为部署时注入；密文与主密钥分离保管，并按环境使用不同主密钥。

- [medium] [u09] [plan] SEC-CA474 — 简述：客户端安装更新包前只比对一个同样来自更新服务器的校验值，等于自己给自己盖章；一旦更新服务器被控，就会静默装上任意程序。
详细问题：自动更新只校验同源清单里的 sha512，清单签名校验默认关闭。
位置：frontend/electron/updater/updater.js:81 · 证据：frontend/electron/updater/updater.js:81-90 async function verifyManifestOrThrow(ymlText, ymlUrl, apiOrigin) { const publicKeyBase64 = resolveManifestPublicKey(); if (!publicKeyBase64) { logger.warn('updater', 'latest.yml signature check disabled: no updateManifestPublicKey'); return } … } ⇒ 无公钥时直接跳过…（详见专家报告）
影响：控制更新源（对象存储凭据、4443 端口服务、发布流水线）即可推送任意安装包，客户端下次检查更新即静默下载安装；TLS 证书固定只能防中间人，防不了源端被改或内鬼。 · 修复：按既定三步走完成强制验签：发布 .sig、填充公钥、把缺公钥从“跳过”改为“拒绝更新”。

- [medium] [u01] [plan] REL-388F4 — 简述：后台生成（朋友圈评论、角色日记、主动问候）总共只重试约 4.5 秒，而上游熔断要 30 秒才恢复，所以一次短暂抖动就把这批任务全丢进死信队列，不会自动补做。
详细问题：后台任务重试预算（3 次 / 约 4.5s）短于熔断窗口（30s），熔断一开就全进死信。
位置：backend/lianyu-service/src/main/java/com/lianyu/service/ai/background/AiBackgroundConsumer.java:71 · 证据：AiBackgroundConsumer：`MAX_ATTEMPTS = 3`（L32）、`retryDelayMs = 1500L`（L43）、`long delay = Math.max(1L, retryDelayMs) * attempt`（L71）→ 重试两次、共等 1.5s+3s；只有 `isAgentTransient(e)`（L102-116）为真才重试，判定方式是字符串匹配“prompt 组装失败/消息发送失败/服务暂时不可用”。上游 Python 熔断参数 wait_duration_seconds=30.0（resilience.py L67-76），（详见专家报告）
影响：上游抖动 30 秒，就会永久丢掉这段时间排队的所有 MOMENTS_*/CHARACTER_DIARY/主动问候任务：用户侧表现为角色忽然不再发朋友圈、不再主动说话，既没有补偿也没有告警，只能靠人工重放死信。 · 修复：让重试窗口覆盖熔断恢复时间，并给死信一条可自动补做的路。

- [medium] [u08] [direct] REL-49628 — 简述：推送通知的队列没有配置死信队列，消费者也没有兜底捕获，一旦推送出错这条通知就彻底丢了，既不能重投也查不到。
详细问题：event.broadcast.queue 没有死信配置，消费者无 try/catch：抛错即丢通知。
位置：backend/lianyu-web/src/main/java/com/lianyu/web/config/RabbitMqConfig.java:84 · 证据：RabbitMqConfig：`eventBroadcastQueue()`（L84-87）是 `QueueBuilder.durable(QUEUE_EVENT_BROADCAST).build()`，没有 `x-dead-letter-exchange`/`x-dead-letter-routing-key`；同文件 `memorySummaryQueue()`（L54-62）与 `aiBackgroundQueue()`（L69-77）都设了 DLX。（详见专家报告）
影响：推送消费一旦抛异常（订阅查询/DB/编码），这条通知既不在队列里、也没有死信可查，用户永远收不到，运维无法重放；空 payload 的推送则被静默吞掉，连日志都没有。 · 修复：把推送队列接上死信通道，并让消费者把异常显式抛出去。

- [medium] [u07] [direct] REL-38C23 — 简述：聊天流中途崩掉时，服务端只打印一行错误摘要、没有调用栈，出了代码级毛病根本定位不到哪里炸的。
详细问题：流式端点顶层异常只打印 repr，没有 traceback。
位置：backend-agent/app/main.py:253 · 证据：L252-256：`except Exception as exc: # noqa: BLE001 - 生成器异常必须可见，否则上游只看到空回复` → `logger.warning("chat-stream failed: user=%s frames=%d err=%r", body.user_id, frames, exc)`，没有 `exc_info=True`；`/agent/chat-turn/stream` 的 L520-522 是同一写法（`logger.warning("chat-turn stream failed: ... err=%r", ...)`）。（详见专家报告）
影响：遇到代码级异常（AttributeError/KeyError/TypeError/NoneType 调用）时，日志只有一行 repr，没有文件与行号；而这正是兜底 catch 存在的唯一理由，等于把最容易出 bug 的路径变成了最难定位的路径。 · 修复：兜底日志带堆栈，并区分“预期失败”和“未知异常”。

- [medium] [u07] [direct] REL-F9370 — 简述：按项目文档跑 Python 测试会直接报错跑不起来，只有先手工装好虚拟环境才行，所以这道门禁在新机器和 CI 上是失效的。
详细问题：`pytest -q backend-agent` 在干净环境 100% 失败：缺 pythonpath，只有 .venv 能跑。
位置：backend-agent/pyproject.toml:30 · 证据：Gap：AGENTS.md §4 要求 `cd backend-agent && pytest` 全绿。Current：在仓库根跑 `pytest -q backend-agent` → 48 个 collection error，全部是 `ModuleNotFoundError: No module named 'app'`（`from app.main import app`）；`cd backend-agent && pytest -q` 同样失败（全局解释器 Python 3.11）。（详见专家报告）
影响：换机器或 CI 按文档跑测试会 100% 失败，容易被当成“代码坏了”；反过来也可能被忽略成“环境问题”，导致 Python 侧 1157 个用例长期不在门禁里跑。 · 修复：让 pytest 从仓库根就能导入 app 包，并固定解释器版本。

- [medium] [u08] [plan] MNT-9D4F4 — 简述：后端把“Python 服务是否活着”的检查在五个类里各抄了一遍，每个都带自己的 3 秒缓存；同目录另一个类却用另一套开关判断，所以改一次刷新间隔要改五处，漏一处就会出现“有的功能能连通、有的直接跳过”。
详细问题：Agent 健康探测在 5 个适配器里各写一份，门控策略还不一致。
位置：backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentBlockingAdapter.java:126 · 证据：5 个类各有一份完全相同的实现（grep 'private boolean reachable()'）: AgentBlockingAdapter.java:126, AgentStreamAdapter.java:106, AgentPromptAssemblyAdapter.java:108, AgentSessionSummaryAdapter.java:109, ChatTurnAdapter.java:204，（详见专家报告）
影响：健康判定的 TTL 与语义没有单一所有者：调整 3 秒窗口或新增“半开”策略要改 5 处；TextAdapter 与其余 4 个适配器对“服务可用”给出不同答案，排查“文本能回、流式不回”时容易被误导。 · 修复：抽一个 AgentHealthGate（持有 client 与 TTL），5 个适配器注入同一个实例，并显式对齐或注明 TextAdapter 的门控差异。

- [medium] [u07] [plan] MNT-258C6 — 简述：模型运行时的入口文件把聊天、语音、记忆、prompt 组装等二十多个接口全塞在同一个 1200 多行的文件里，一个函数最长一百多行；只想改语音接口的人也得在这个大文件里翻找，改动容易互相踩。
详细问题：backend-agent/app/main.py 1278 行、25 个端点没有拆 router。
位置：backend-agent/app/main.py:1 · 证据：`wc -l backend-agent/app/main.py` = 1278；文件内 16 个 import、31 个 def、25 个 `@app.post`/`@app.get` 装饰器，0 处 `APIRouter`；最长回调 async def chat_turn_stream 102 行（起始 495），chat_stream 94 行（201），text_chat 92 行（360），proactive_turn 81 行（1060）；同目录第二大模块 gateway.py 仅 504 行。
影响：所有端点的装配、鉴权前缀、异常映射都耦合在一个文件里：改任一接口都要重读 1278 行上下文，合并冲突集中在单文件，单测只能整体导入 app，无法按域隔离。 · 修复：按域把端点拆成 APIRouter（chat / voice / memory / prompt / observe），main.py 只保留 app 装配与 include_router，路径与响应结构不变。

- [medium] [u01] [plan] MNT-0DED5 — 简述：后端服务层里“AI 能力”和“角色”两个包互相引用：AI 包要用角色的导入解析器与日记服务，角色包又要用 AI 包的密钥保管、语音注册和后台任务。结果这两个包只能一起改、一起测，想单独拿走其中一个都做不到。
详细问题：com.lianyu.service.ai 与 com.lianyu.service.character 包级互相依赖。
位置：backend/lianyu-service/src/main/java/com/lianyu/service/character/CharacterDiaryService.java:18 · 证据：ai→character：AiChatService.java:11 `import com.lianyu.service.character.CharacterImportDraftMapper;`、:12 `CharacterImportSourceParser`；ai/background/AiBackgroundConsumer.java:5 `import com.lianyu.service.character.CharacterDiaryService;`。（详见专家报告）
影响：两个包（ai 20 类、character 15 类）无法独立抽取或做包级测试；任何一方的构造签名变化都会同时牵动另一边，包边界名存实亡。 · 修复：把被交叉引用的能力抽成 service/port 下的接口（密钥保管、语音注册、日记后台任务、角色导入解析），两边只依赖接口。

- [medium] [u06] [plan] MNT-938BA — 简述：前端通知模块和“点通知跳转”的组件互相 import，形成整个前端唯一一个循环依赖；通知 store 同时管长连接、提示音、弹窗和跳转，改其中任何一块都得把另一半也读一遍。
详细问题：notifications store 与 useNotificationNavigation 互相 import（全前端唯一环）。
位置：frontend/src/stores/notifications.js:14 · 证据：notifications.js:14 `import { navigateToNotification } from '@/composables/useNotificationNavigation'`；useNotificationNavigation.js:3 `import { useNotificationsStore } from '@/stores/notifications'`。对 frontend/src 219 个模块、476 条 import 边做 Tarjan 强连通检测，仅此一处在环上。该 store 417 行，（详见专家报告）
影响：环使这两个模块无法单独加载/单测（store 测试必须连 router 与跳转逻辑），也让“通知到达→选哪个页面”的决策散落在 store 与 composable 两侧，新增通知类型要同时改两处。 · 修复：让 store 只发事件（或回调注入），导航决策留在 composable/router 侧，打断 import 环。

- [medium] [u01] [plan] MNT-A43FA — 简述：“哪些角色能打语音电话”这件事被写了两份：Java 里一个常量集合、前端一个常量数组，两边注释都写着“必须和对方保持一致”。新增一个能语音的角色时漏改一边，就会出现前端显示可拨打、后端直接拒绝。
详细问题：语音可拨打角色白名单在 Java 与 JS 各手写一份，靠注释人工同步。
位置：backend/lianyu-service/src/main/java/com/lianyu/service/conversation/VoiceCallService.java:69 · 证据：VoiceCallService.java:67 注释 `Keep in sync with frontend {@code voiceCallPets.js}.`，:69-83 `private static final Set<String> VOICE_CALL_PET_IDS = Set.of("klee","ganyu","ayaka","raiden","elysia","erii_uesugi","yae_miko","kokomi","shenhe","nahida","hu_tao","furina","noelle")`；（详见专家报告）
影响：同一事实两处定义且没有任何测试比对：漏改时前端仍显示“语音通话”入口，后端会走 else 分支返回不可拨打，用户看到的是“按钮在但打不通”。 · 修复：改由后端作为唯一来源（提供可拨打角色列表接口，或在构建期生成前端常量），并加一致性测试兜底。

- [medium] [u03] [plan] MNT-745BE — 简述：仓库里有一份 617 行的繁体中文语言包，但程序从来没加载过它——用户选不到繁体，代码里也把它从语言列表里去掉了；只剩一个测试还在检查它的 key 是否齐全，等于逼着后来人继续维护一份没人看得到的译文。
详细问题：zh-TW 语言包未被注册（CONFIRMED_DEAD），仅测试在续命。
位置：frontend/src/i18n/index.js:12 · 证据：i18n/index.js:9-16 只 `import zh/ja/en`，`messages: { zh, ja, en }`；constants/language.js:3 `export const SUPPORTED_LANGUAGES = /** @type {const} */ (['zh', 'ja', 'en'])`，normalizeLanguage 会把未知值回退到 'zh'；locales/zh-TW.js 617 行完整译文，（详见专家报告）
影响：617 行死译文 + 一个专门保护它的测试：按 AGENTS.md §3.2“四语齐”的要求，后续新增 key 还得往这里补，否则测试红；而真实用户永远看不到繁体界面。 · 修复：先确认产品是否需要繁体中文：不需要就删除语言包并把“四语齐”改为三语；需要就把它真正接进语言列表和 UI 选项。

- [medium] [u03] [plan] MNT-B9294 — 简述：前端有四个模块已经没人用了，合计近 800 行：一个录音 composable（只有它自己的测试在用）、一个聊天截图工具、一个反调试工具，还有一个只被复制走逻辑的语音转写 API。读代码的人会以为这些能力还在生效。
详细问题：4 个前端模块无任何生产引用（合计 791 行），其中一个只被自己的测试引用。
位置：frontend/src/composables/useVoiceRecorder.js:1 · 证据：对 frontend/src 下 170 个 js/vue 模块（排除 __tests__，入口 main.js/App.vue）建 import 图：useVoiceRecorder.js（466 行）唯一引用为 composables/__tests__/useVoiceRecorder.test.js:2；utils/captureChatShareScreenshot.js（220 行）`export async function captureChatShareScreenshot(container, selectedIds)` 在 :128，全仓 0 调用；（详见专家报告）
影响：四个死模块 + 一个为死代码保绿的测试，让“录音/截图/反调试/ASR”四个能力在代码库里看起来仍然存在；新人改 ASR 时可能改到 asr.js 而线上实际走 voiceCall.js。 · 修复：删除这 4 个模块与对应测试；若截图或录音功能仍计划上线，先建 issue 再从历史提交取回。

- [medium] [u04] [plan] MNT-BEAD5 — 简述：脚本目录自己写了一条规矩“根目录只放常用脚本，一次性的放 diag/ 或 ops/”，但根目录实际堆了 94 个条目、23 个云部署脚本，还有几对只差一个数字的重复脚本；想知道“部署到底该跑哪个”只能逐个点开看。
详细问题：scripts/ 根目录膨胀且违反自身 README 约定，存在成对重复脚本。
位置：scripts/README.md:18 · 证据：scripts/README.md:18 `新增一次性排障脚本请放入 diag/ 或 ops/，避免堆在根目录。`；实际 `ls scripts | wc -l` = 94 个条目、132 个跟踪文件，根目录 23 个 `_cloud_*.py` 共 1779 行；成对重复：scripts/diag/_diag_community_feed.py 与 _diag_community_feed2.py、scripts/diag/_fix_gateway_connect.py / _fix_gateway_network.py / _fix_gateway_order.py、scripts/gen…（详见专家报告）
影响：部署与排障入口不可枚举：94 个根目录条目里混着只用过一次的迁移脚本，`_fix_gateway_*` 三个变体哪个是最终版无从判断；新人容易跑错脚本或重复实现已有的云部署逻辑。 · 修复：按 README 约定把一次性脚本移入 diag/ops（或直接删除已完成的），合并重复变体，并在 CI 里限制根目录条目数。

- [medium] [u12] [plan] MNT-0CAA3 — 简述：backend/scripts 里留着五个月前的五个 PowerShell 迁移脚本，路径写死在作者本机的 C:\Users\hp\... 上，其中一个的注释直接写“用来回滚另一个脚本”；同目录还有两份写死版本号的 Flyway 修复 SQL 和一个 0 字节的 vault 导出文件。别人跑这些脚本只会改错目录或改到已迁完的代码。
详细问题：backend/scripts 残留一次性迁移脚本：硬编码个人绝对路径 + 0 字节占位文件。
位置：backend/scripts/restore-service-imports.ps1:3 · 证据：5 个 ps1 硬编码 `c:\Users\hp\Desktop\LianYu-PC\...`：dedupe-java-imports.ps1:1、fix-service-cross-imports.ps1:2、repackage-service.ps1:3-4、restore-service-imports.ps1:3、strip-utf8-bom.ps1:1；restore-service-imports.ps1:1 注释 `# Restore java.* / third-party imports stripped by fix-service-cross-imports.ps1`，（详见专家报告）
影响：目录名 scripts 让人以为可以照跑：实际路径指向作者机器、SQL 绑定已过去的迁移版本、0 字节 tsv 看起来像凭据导出；误跑会改错文件或写入错误的 flyway_schema_history。 · 修复：删除已执行完的一次性脚本与空占位文件，只保留仍在用的 _upload_backend_image.py；如需留档则移入 docs/refactor-2026-06/ 并注明执行日期与结果。

- [medium] [u09] [plan] MNT-2AC9C — 简述：Electron 主进程还留着一套“从 localStorage 解密登录 token”的注入脚本，但渲染层早已改成只把 token 放内存、不再写那两个键；这段代码现在每次开窗都会执行一遍、必然返回空，注释却还声称“与 secureToken.js 保持一致”，会误导后面的人以为存在 localStorage 回退。
详细问题：rendererTokenScript 解密的是已被移除的 localStorage token（恒返回 null）。
位置：frontend/electron/rendererTokenScript.js 第 1 行 · 证据：rendererTokenScript.js 第 1 行 注释 `在渲染进程 executeJavaScript 中解密 secureToken 存储的 _ltt` / `逻辑与 src/utils/secureToken.js 保持一致`，脚本体读 `localStorage.getItem('_ltt')` 与 `_lkt` 做 AES-GCM 解密；而 src/utils/secureToken.js 的注释 注释 `Token 内存态存储（#14：移除 localStorage 加密剧场）`，:9 `token 仅驻留渲染进程内存（rawToken），（详见专家报告）
影响：每次 ensureAuthSessionFromRenderer 都会对每个候选窗口执行一次必然失败的 executeJavaScript，且注释与真实实现相反：维护者会以为删掉 secureToken.js 的 localStorage 分支是 bug，或继续在 token 路径上叠加兼容代码。 · 修复：删除 rendererTokenScript.js 与 pullRendererAuthToken 分支，认证恢复统一走主进程 safeStorage；loggedIn 判定改用 'lianyu-user-profile'。

- [medium] [u05] [plan] PRF-9FA45 — 简述：服务端每 30 秒为所有单聊会话各算一次「最后一条消息时间」，会话越多越慢，最终拖慢聊天接口和数据库。
详细问题：主动搭话扫描每 30 秒执行一次带相关子查询的 ORDER BY，代价随会话总数线性增长。
位置：backend/lianyu-dao/src/main/java/com/lianyu/dao/mapper/ConversationMapper.java:20 · 证据：SQL 为 `SELECT c.id, c.user_id, c.character_id, c.mode, c.title, c.created_at FROM conversation c WHERE c.mode='SINGLE' AND c.character_id IS NOT NULL ORDER BY COALESCE((SELECT MAX(m.created_at) FROM message m WHERE m.conversation_id = c.id), c.created_at) DESC LIMIT #{limit}`（第 20-31 行）。（详见专家报告）
影响：每次扫描的工作量 ≈ 单聊会话数 次索引下探 + 同等行数排序，且每天固定执行 2880 次；会话数到 5 万量级时单次已是 5 万次子查询 + 5 万行文件排序，持续占用 CPU 与 buffer pool，用户侧表现为聊天/列表接口的周期性抖动。 · 修复：把「最后消息时间」变成可索引的冗余列，或改成派生表聚合后再排序，避免 ORDER BY 内出现相关子查询。

- [medium] [u01] [plan] PRF-454DD — 简述：情绪衰减定时任务每次把全表角色状态读进内存再逐行回写，角色多时一次要跑很久，期间数据库连接和写锁都被长期占着。
详细问题：decayAllEmotions 无 LIMIT 全表扫描并在同一事务内逐行 updateById。
位置：backend/lianyu-service/src/main/java/com/lianyu/service/character/CharacterStateService.java:179 · 证据：第 179-221 行 `public int decayAllEmotions()` 带 @Transactional：第 182-188 行 `stateMapper.selectList(...isNotNull(currentEmotion).ne(currentEmotion,"平静").and(w -> w.isNull(emotionUpdatedAt).or().le(emotionUpdatedAt, threshold)))` 没有 LIMIT；（详见专家报告）
影响：单次任务耗时随 character_state 行数线性增长，超过 15 分钟间隔后任务开始堆叠；长事务占住 20 个 Hikari 连接中的 1 个并持有行锁，在线聊天写 character_state 时被阻塞；N 次单行 UPDATE 还把 binlog 与 redo 放大 N 倍。 · 修复：按主键分页取一批、只更新真正变化的行，并用批量 UPDATE 替换逐行 updateById。

- [medium] [u05] [plan] PRF-5C4A1 — 简述：每天两次为「日记」统计当天聊天量，需要扫描整张消息表，聊天记录越多越慢。
详细问题：message 表无 created_at 单独索引，日记候选查询退化为全表扫描。
位置：backend/lianyu-dao/src/main/java/com/lianyu/dao/mapper/ConversationMapper.java:37 · 证据：SQL（第 37-51 行）：`SELECT c.id AS conversationId, ... SUM(m.role='USER') AS userMsgCount, MAX(m.created_at) AS lastActivityAt FROM conversation c JOIN message m ON m.conversation_id = c.id WHERE c.mode='SINGLE' AND c.character_id IS NOT NULL AND m.created_at >= #{since} GROUP BY c.id, ... HAVING userMs…（详见专家报告）
影响：`m.created_at >= since` 无法单独走索引，只能按 conversation 驱动逐条下探或直接全表扫 message（项目里最大的表，content 为 MEDIUMTEXT）；每天 2 次把冷数据扫进 buffer pool，冲掉热聊天的缓存页，随后一段时间在线聊天 IO 命中率下降。 · 修复：给 message.created_at 补一个可用的索引（或改由 conversation.last_message_at 过滤），让按时间窗口的统计不再全表扫。

- [medium] [u01] [plan] PRF-E4A17 — 简述：打开会话列表时，服务端把每个会话最后一条消息的完整正文都取回来，只为显示 120 字预览，白传了大量文本。
详细问题：会话列表批量取 MEDIUMTEXT 全文，仅用于截断到 120 字符的预览。
位置：backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java:489 · 证据：list() 第 166-169 行取最多 200 个会话；第 489-517 行 buildLastMessageSnippetMap / buildLastCharacterMessageSnippetMap 批量调 MessageMapper.selectLatestByConversationIds 与 selectLatestAssistantByConversationIds；（详见专家报告）
影响：一次列表请求最多取回 200 条完整正文，长回复/长文导入场景下单次可达数 MB 文本，MySQL 还需读 MEDIUMTEXT 溢出页、JDBC 逐行反序列化；用户侧表现为侧边栏会话列表打开变慢、内存抖动。 · 修复：在 SQL 侧只取预览所需的长度，不要整列取回 MEDIUMTEXT。

- [medium] [u06] [plan] PRF-D16A1 — 简述：聊天记录越长，打开会话和收到新消息时越卡，因为每次都要重建整条时间线并重排全部消息。
详细问题：ChatPage 无虚拟滚动，messageTimeline 全量重建 + 每次变更全量 sort。
位置：frontend/src/pages/ChatPage.vue:650 · 证据：`messageTimeline` computed（第 650-720 行）在 messages 每次变更时全量重建，对每条消息执行 `expandAssistantForDisplay`（:640-648，含 stripInnerThoughts 正则）并 push 分组标记；`mergePolledMessages`（:965-1041）发现变化后调用 `sortMessagesInTimelineOrder()` 对全量数组排序（:961-963）；（详见专家报告）
影响：n 条消息时每次增量更新都是 O(n) 重建 + O(n log n) 排序 + O(n) 的 VDOM diff 与布局；消息到几千条后（loadOlderMessages 无上限，见 P10）滚动与发送都会掉帧，流式回复结束的回填也会触发一次全量重算。 · 修复：引入虚拟滚动并按 key 增量更新，避免每次变更全量重建与全量排序。

- [medium] [u06] [plan] PRF-F4602 — 简述：聊天窗口每 10 秒重新下载最近 50 条消息，即使一条新消息都没有；窗口切到后台也照旧。
详细问题：会话轮询固定拉取最近 50 条全文且无增量游标，隐藏窗口也不停止。
位置：frontend/src/pages/ChatPage.vue:1203 · 证据：常量 MESSAGE_PAGE_SIZE=50（:484）、NORMAL_POLL_MS=10000（:589）、FAST_POLL_MS=2000（:587）；`pollCurrentConversationMessages(force)`（:1197-1231）每次 `const page = await getMessages(convId, { limit: MESSAGE_PAGE_SIZE }, { silent: true })`（:1203），请求只带 limit 没有 afterSeq/游标；（详见专家报告）
影响：单个前台聊天窗口每分钟 6 次 × 50 条完整消息（含 MEDIUMTEXT 正文，见 P05）≈ 每分钟 300 条正文的下载与 JSON 解析；桌宠/快捷聊常驻时后台窗口也在轮询，多窗口并存时请求量与主进程解析开销成倍叠加，是弱网下消息延迟与电量消耗的主要来源。 · 修复：改成增量拉取（afterSeq/游标）+ 页面不可见时暂停或降频轮询。

### 低（12）

- [low] [u02] [direct] COR-B3226 — 简述：每写一条新记忆都会排队两次，第二次会把刚建好的向量删掉再插一遍，这期间这条记忆搜不到。
详细问题：新建记忆被重复写入 outbox，每条记忆触发两次向量重建。
位置：backend/lianyu-service/src/main/java/com/lianyu/service/memory/MemoryWriter.java:120 · 证据：`upsertTypedMemory` 在 CREATED 分支已经 `memoryOutboxService.enqueueUpsert(meta.getId())`（MemoryWriter.java:223）；回到 `processSummary` 又对 `saved.getMilvusVecId()` 为空的行再入队一次（MemoryWriter.java:117-126），而刚创建的行 vec_id 必然为空 → 同一个 memory_id 产生两条 UPSERT。（详见专家报告）
影响：嵌入调用与 Milvus 写入翻倍（成本与延迟），两次 UPSERT 之间存在向量缺失窗口，刚说完的话可能检索不到；第二条执行失败时 memory_meta.vec_id 已指向被删掉的向量。 · 修复：去掉 processSummary 里重复的入队，只在 outbox 中确实没有该记忆的待处理行时才补一次。

- [low] [u10] [direct] SEC-AFEFF — 简述：构建流水线用的第三方组件没有锁定版本，依赖漏洞扫描报错也不会让构建失败，等于没人管。
详细问题：CI 以可变 tag 引用第三方 Action，依赖漏洞扫描失败不阻断流水线。
位置：.github/workflows/ci.yml:48 · 证据：.github/workflows/ci.yml:14/28/45 actions/checkout@v4、:15 actions/setup-java@v4、:29 actions/setup-node@v4、:48 google/osv-scanner-action/osv-scanner-action@v2.0.3 全部按 tag/分支引用；:47 continue-on-error: true 使依赖漏洞扫描失败不影响 job 结论。
影响：上游 action 仓库被打新 tag 或劫持时，CI 会执行攻击者代码并接触仓库令牌与发布凭据；osv-scanner 的告警被吞掉，已知漏洞依赖可以长期留在主干。 · 修复：Actions 固定到 commit SHA，漏洞扫描改为阻断（或至少单独出 job 并告警到人）。

- [low] [u10] [direct] SEC-6B08B — 简述：页面一旦被人塞进恶意脚本，这个脚本能用 eval 拼载荷，还能把读到的内容发到任意外部网站。
详细问题：渲染进程 CSP 允许 unsafe-eval，且 connect-src 放开到任意 https 域名。
位置：frontend/index.html:7 · 证据：frontend/index.html:7 <meta http-equiv="Content-Security-Policy" content="default-src 'self'; script-src 'self' 'unsafe-eval'; … connect-src 'self' https: wss:; object-src 'none'; base-uri 'self'; frame-ancestors 'none'">；frontend/launcher.html:7 同样的 script-src 'unsafe-eval'。（详见专家报告）
影响：任何 DOM XSS（社区/评论渲染、第三方依赖、未来新增富文本）都会放大为可 eval 任意代码、可把页面内容与渲染进程内 token 发往任意 https 主机的完整接管。 · 修复：去掉 unsafe-eval，把 connect-src 收紧到实际使用的 API/更新域名。

- [low] [u01] [direct] REL-97AFD — 简述：群聊里某个角色生成失败时，用户只看到它不说话，前端没有任何提示，日志里也只有一行不带调用栈的原因。
详细问题：群聊单角色生成失败被吞：静默缺席 + 日志无堆栈。
位置：backend/lianyu-service/src/main/java/com/lianyu/service/conversation/GroupChatService.java:414 · 证据：L401-419 的完成循环：`try { reply = f.join(); } catch (Exception ignored) { // handled in generateCharacterReply }`，随后 `if (reply == null || reply.content() == null || reply.content().isBlank()) continue;`；（详见专家报告）
影响：某个角色这一轮生成失败时，它在群聊里静默缺席，前端没有任何“暂时无法回复”的提示，用户只会觉得角色不理人；排查时日志只有一行 message，定位不到是上游还是本地异常。 · 修复：失败要留堆栈、要能被前端看见。

- [low] [u01] [direct] REL-E2A96 — 简述：主动问候和定时语音发送失败时只写一行 DEBUG 日志，运维按 WARN 告警的话完全看不到“角色不再主动说话”这件事。
详细问题：主动消息失败只记 DEBUG：整条链路停摆时无 WARN、无指标。
位置：backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ProactiveChatScheduler.java:174 · 证据：发送循环 L148-176：`catch (Exception e) { log.debug("Proactive chat skipped for convId={}, reason={}", item.conv().getId(), e.getMessage()); }`；`trySendWaitNudgeVoices` L215-217 与定时语音 L265 同族，都是 log.debug + e.getMessage()。tick()（L62-63）本身没有外层 try/catch，批次查询（L68-95）也不在 per-candidate 隔离内。（详见专家报告）
影响：主动问候整体失效（例如定时语音全部被策略跳过、或每轮都抛异常）时，日志里既没有 WARN 也没有指标，只能等用户反馈“角色怎么不理我了”。 · 修复：把失败从 debug 提升到可见级别，并加最小计数。

- [low] [u01] [direct] REL-7C13B — 简述：关系衰减、情绪衰减、朋友圈评论补偿这三个定时任务失败时只留一行文字原因，没有调用栈也没有连续失败计数。
详细问题：三个定时任务失败只记 e.getMessage()：无堆栈、无连续失败告警。
位置：backend/lianyu-service/src/main/java/com/lianyu/service/character/EmotionDecayScheduler.java:34 · 证据：EmotionDecayScheduler L33-35 `catch (Exception e) { log.warn("Emotion decay tick failed: {}", e.getMessage()); }`；RelationshipDecayScheduler L33-35 `catch (Exception e) { log.warn("Relationship decay tick failed: {}", e.getMessage()); }`；（详见专家报告）
影响：定时任务连续失败（连接池耗尽、SQL 报错、迁移缺列）时，日志里只有一堆同类文本，看不出根因，也没有“连续失败 N 次”的告警触发点；衰减/补偿长期失效只能靠用户察觉数值不变化。 · 修复：定时任务失败要带堆栈，并能在连续失败时升级报警。

- [low] [u10] [plan] REL-BC684 — 简述：管理后台只有一个 8 行的菜单测试，而且脚本允许“一个测试都没有也算通过”，所以后台登录态和接口出错不会被任何门禁拦住。
详细问题：admin 控制台仅 1 个菜单测试，且 `--passWithNoTests` 让空套件也变绿。
位置：admin/package.json:9 · 证据：`"test": "vitest --passWithNoTests"`；`admin/src/__tests__/menu.test.js` 只有 8 行、仅断言菜单 id；`admin/src/api.js`（token 存 localStorage、adminLogin/adminLogout/getOverview）、`admin/src/stores/session.js`（单行 pinia 持久化 token）没有任何测试。实跑 `npm --prefix admin test` 直接失败：`'vitest' 不是内部或外部命令`（admin 未安装依赖），（详见专家报告）
影响：`--passWithNoTests` 让“没有测试”也返回成功，管理后台的登录态过期、权限接口 401/403、列表加载失败等改动静默通过门禁；一旦线上后台登录异常，没有任何用例能先一步发现。 · 修复：去掉“空套件也算过”的退路，并给后台登录/接口补最小用例。

- [low] [u11] [direct] REL-416DA — 简述：记忆防抖那条用例里写死了真实等待 5.2 秒，跑得慢还容易在机器繁忙时偶发失败。
详细问题：MemoryWriterDebounceTest 硬等 5.2 秒验证补发，慢且时序脆弱。
位置：backend/lianyu-service/src/test/java/com/lianyu/service/memory/MemoryWriterDebounceTest.java:72 · 证据：`maybeReschedule_pendingFlagSendsFollowUpTask` 通过 `ReflectionTestUtils.invokeMethod(writer, "maybeReschedule", task)` 触发后 `Thread.sleep(5200);` 再断言补发只发生一次；被验证的常量是 `RESCHEDULE_DELAY_SECONDS = 5`（MemoryWriter 的重排调度器）。
影响：单条用例固定占用 5.2 秒，是全套件里最慢的一条；机器繁忙或 CI 抖动时 5.2s 可能不够 → 偶发红，被视为“已知 flake”后真实回归也会被忽略。 · 修复：把重排延迟做成可注入值，测试用毫秒级延迟 + 轮询断言。

- [low] [u06] [direct] MNT-66CE2 — 简述：三个聊天页面各自复制了一段“把消息时间转成毫秒、再渲染成时间分隔条”的小函数，文本完全一样；哪天要改分隔条规则（比如跨天显示日期），必须记得三个文件都改。
详细问题：消息时间解析/分隔条格式化在 3 个页面各写一份。
位置：frontend/src/pages/ChatPage.vue:1556 · 证据：ChatPage.vue:1556-1565、GroupChatPage.vue:883-892、QuickChatPage.vue:157-166 三处的 `function parseMessageTime(msg) { const ts = msg?.createdAt || msg?._time; if (!ts) return Date.now(); const ms = new Date(ts).getTime(); return Number.isNaN(ms) ? Date.now() : ms }` 与 `function formatTimeDivider(ms) { re…（详见专家报告）
影响：分隔条策略（脏时间回退、跨天格式、locale 透传）被拆成三份，修改时容易只改一处，导致三个页面时间显示不一致；新增页面还会继续复制第四份。 · 修复：把 parseMessageTime / formatTimeDivider 提到 frontend/src/utils/messageTimeline.js，三个页面改成 import。

- [low] [u10] [plan] MNT-10A82 — 简述：管理后台的 package.json 里装了表格库、图表库、路由和一套图标，但后台一共只有十个源文件的壳子，这四样东西一次都没被 import 过；每次装依赖都要多下一批包，也给升级维护增加无谓的负担。
详细问题：admin 声明了 4 个从未被引用的依赖。
位置：admin/package.json:10 · 证据：admin/package.json:10 单行 dependencies 含 `@element-plus/icons-vue`、`@tanstack/vue-table`、`echarts`、`vue-router`（以及实际在用的 axios/element-plus/pinia/vue）；在 admin/src 与 admin/electron 内 grep 这四个包名命中数为 0。（详见专家报告）
影响：多装 4 个未使用的包（含 echarts 这类体积较大的库），安装/审计/升级都要为它们付出成本；也让人误以为后台已有图表与路由能力。 · 修复：从 admin/package.json 移除这四个依赖并更新 lockfile；若后台确实要做图表/路由，另开任务再接。

- [low] [u07] [plan] PRF-585C8 — 简述：记忆检索时一条一条地向 Redis 取向量，50 条候选就是 50 次往返，时间全花在等待上。
详细问题：vector_cache 逐条串行 Redis GET/SET，N 次往返叠加在对话回合内。
位置：backend-agent/app/memory/vector_cache.py:59 · 证据：第 59-73 行 `_vectors_for(texts)`：`for i, text in enumerate(texts): vector = await cache_get_json(_text_key(text))`，每条一次 Redis GET；缺失项再 `for slot, idx in enumerate(missing_idx): await cache_put_json(...)` 逐条 SET。rank_by_vector（:77-109）先 `embed([query])` 再 `_vectors_for(texts)`，（详见专家报告）
影响：每次语义记忆检索在对话回合内串行叠加最多 50 次 RTT（跨可用区 1-2ms 时约 50-100ms），缓存未命中时再叠 50 次 SET；这段延迟直接落在用户等待回复的首字时间上，而 CPU 侧几乎没有开销。 · 修复：用 MGET/pipeline 把逐条读写改成一次往返，并把 cosine 计算向量化。

- [low] [u06] [plan] PRF-2EAFA — 简述：往上翻聊天记录时消息数组只增不减，翻得越多前端内存越大、越卡。
详细问题：loadOlderMessages 每页 50 条前插且无上限，messages 数组无限增长。
位置：frontend/src/pages/ChatPage.vue:908 · 证据：`loadOlderMessages`（第 908-949 行）以 MESSAGE_PAGE_SIZE=50 分页把更早消息 unshift 进 `messages.value`，没有总条数上限、也没有回收旧页；同一数组同时被 `messageTimeline`（:650-720）全量重建与 `sortMessagesInTimelineOrder`（:961-963）全量排序使用，切换会话时（onUnmounted/会话切换逻辑）才整体替换。
影响：长时间连续上翻后 messages 可达数千条，与 P08 的全量重建/排序叠加，内存与帧率持续恶化；桌面端常驻窗口不会因切页释放，属于缓慢累积的泄漏式增长。 · 修复：给会话消息数组设定保留上限并配套「向上翻页」的窗口化策略。

## 专家报告索引

- [correctness.md](handoff/correctness/correctness.md) — 正确性，7 条发现
- [security.md](handoff/security/security.md) — 安全，10 条发现
- [reliability.md](handoff/reliability/reliability.md) — 健壮性，11 条发现
- [maintainability.md](handoff/maintainability/maintainability.md) — 可维护性，12 条发现
- [perf.md](handoff/perf/perf.md) — 性能，10 条发现

## 可直接修

证据已闭合、改动局部、不需要先做方案评审的项（按严重度）。每条都带复现路径或明确断言，修完即可用对应测试锁定。

- COR-559B1 [high] [u07] — 模型在流式回复里要求调用工具（查时间、天气、记忆等）时，这条请求会被丢掉，用户只看到角色「说要做」却没有动作。
- SEC-701CA [high] [u10] — 少配一个环境变量，用户自己填的模型密钥就会以明文存进数据库，而系统只在启动日志里警告一句。
- REL-34EFB [high] [u07] — 用户中途关掉聊天窗口，系统会把它当成模型服务故障记一笔；攒够几次，所有用户都会被提示“模型繁忙”，哪怕模型本身好好的。
- REL-42BC8 [high] [u01] — 回复已经生成完、准备收尾时只要冒出一点非网络异常，这条流就不会发结束标记、回复也不会存进数据库，用户界面会一直转圈。
- SEC-1101A [medium] [u11] — 管理员密码一旦泄露，第二步验证码可以无限次尝试，因为只有密码输错才会计数锁定。
- REL-49628 [medium] [u08] — 推送通知的队列没有配置死信队列，消费者也没有兜底捕获，一旦推送出错这条通知就彻底丢了，既不能重投也查不到。
- REL-38C23 [medium] [u07] — 聊天流中途崩掉时，服务端只打印一行错误摘要、没有调用栈，出了代码级毛病根本定位不到哪里炸的。
- REL-F9370 [medium] [u07] — 按项目文档跑 Python 测试会直接报错跑不起来，只有先手工装好虚拟环境才行，所以这道门禁在新机器和 CI 上是失效的。
- COR-B3226 [low] [u02] — 每写一条新记忆都会排队两次，第二次会把刚建好的向量删掉再插一遍，这期间这条记忆搜不到。
- SEC-AFEFF [low] [u10] — 构建流水线用的第三方组件没有锁定版本，依赖漏洞扫描报错也不会让构建失败，等于没人管。
- SEC-6B08B [low] [u10] — 页面一旦被人塞进恶意脚本，这个脚本能用 eval 拼载荷，还能把读到的内容发到任意外部网站。
- REL-97AFD [low] [u01] — 群聊里某个角色生成失败时，用户只看到它不说话，前端没有任何提示，日志里也只有一行不带调用栈的原因。
- REL-E2A96 [low] [u01] — 主动问候和定时语音发送失败时只写一行 DEBUG 日志，运维按 WARN 告警的话完全看不到“角色不再主动说话”这件事。
- REL-7C13B [low] [u01] — 关系衰减、情绪衰减、朋友圈评论补偿这三个定时任务失败时只留一行文字原因，没有调用栈也没有连续失败计数。
- REL-416DA [low] [u11] — 记忆防抖那条用例里写死了真实等待 5.2 秒，跑得慢还容易在机器繁忙时偶发失败。
- MNT-66CE2 [low] [u06] — 三个聊天页面各自复制了一段“把消息时间转成毫秒、再渲染成时间分隔条”的小函数，文本完全一样；哪天要改分隔条规则（比如跨天显示日期），必须记得三个文件都改。

## 需方案

需要先定方案（数据迁移、限流口径、线程模型、索引与前端架构），不宜在热修里顺手做。

- SEC-EA2CB [critical] [u07] — 任何人只要把请求里的用户编号换成别人，就能读到对方的聊天记忆和日记；这些内部接口目前没有登录校验。
- COR-CA186 [high] [u02] — 用户一句话里说出的多件事（例如「以后叫我小林，我今天很崩溃」），只有最后一件会被记住，前面几件被同一条记录悄悄覆盖。
- COR-54E29 [high] [u06] — 发送消息遇到网络抖动时客户端会自动重发，但服务端每次都当成新消息，于是同一句话被记两遍、角色也回两遍。
- SEC-E06CB [high] [u07] — 外部调用者可以指定“模型地址”，服务就会照这个地址发请求，内网服务因此可能被探测，返回内容还会被带出来。
- SEC-A3F36 [high] [u08] — 客户端的限流按“来源 IP”算，而这个 IP 可以从请求头里随便写，攻击者每次换一个就能绕过注册和上传的次数限制。
- PRF-71D14 [high] [u01] — 群聊里同时有 8 个会话在回复时，所有回复会永久停住，用户只能重启应用或等服务重启。
- PRF-9225F [high] [u06] — 桌宠和快捷聊天窗口为了几个按钮，启动时要解析 1.1MB 的脚本（压缩后 361KB），窗口打开明显变慢、内存占用变高。
- COR-05886 [medium] [u02] — 后台每半小时的关系衰减和用户聊天会同时改同一份关系数据，用户的信任/亲密度增长经常被旧快照覆盖，表现为「聊很久关系也不涨」。
- COR-F6ADD [medium] [u05] — 修「重复会话」的那次数据库迁移直接把多余会话删了，里面的聊天记录从界面上消失，却永远留在库里。
- COR-50DF0 [medium] [u07] — 记忆向量同步任务如果做到一半进程被重启，这条任务会一直卡在「执行中」，之后再也不会重试，那条记忆就永远搜不到或永远删不掉。
- SEC-D6178 [medium] [u07] — 外部任何人都能反复调用识图接口，用平台自己的模型额度生成内容，花的是运营方的钱。
- SEC-1AD35 [medium] [u05] — 十个真实的模型密钥以加密形式写进了代码库，而解密用的那把钥匙全组共用，谁拿到代码和钥匙谁就能用这些额度。
- SEC-CA474 [medium] [u09] — 客户端安装更新包前只比对一个同样来自更新服务器的校验值，等于自己给自己盖章；一旦更新服务器被控，就会静默装上任意程序。
- REL-388F4 [medium] [u01] — 后台生成（朋友圈评论、角色日记、主动问候）总共只重试约 4.5 秒，而上游熔断要 30 秒才恢复，所以一次短暂抖动就把这批任务全丢进死信队列，不会自动补做。
- MNT-9D4F4 [medium] [u08] — 后端把“Python 服务是否活着”的检查在五个类里各抄了一遍，每个都带自己的 3 秒缓存；同目录另一个类却用另一套开关判断，所以改一次刷新间隔要改五处，漏一处就会出现“有的功能能连通、有的直接跳过”。
- MNT-258C6 [medium] [u07] — 模型运行时的入口文件把聊天、语音、记忆、prompt 组装等二十多个接口全塞在同一个 1200 多行的文件里，一个函数最长一百多行；只想改语音接口的人也得在这个大文件里翻找，改动容易互相踩。
- MNT-0DED5 [medium] [u01] — 后端服务层里“AI 能力”和“角色”两个包互相引用：AI 包要用角色的导入解析器与日记服务，角色包又要用 AI 包的密钥保管、语音注册和后台任务。结果这两个包只能一起改、一起测，想单独拿走其中一个都做不到。
- MNT-938BA [medium] [u06] — 前端通知模块和“点通知跳转”的组件互相 import，形成整个前端唯一一个循环依赖；通知 store 同时管长连接、提示音、弹窗和跳转，改其中任何一块都得把另一半也读一遍。
- MNT-A43FA [medium] [u01] — “哪些角色能打语音电话”这件事被写了两份：Java 里一个常量集合、前端一个常量数组，两边注释都写着“必须和对方保持一致”。新增一个能语音的角色时漏改一边，就会出现前端显示可拨打、后端直接拒绝。
- MNT-745BE [medium] [u03] — 仓库里有一份 617 行的繁体中文语言包，但程序从来没加载过它——用户选不到繁体，代码里也把它从语言列表里去掉了；只剩一个测试还在检查它的 key 是否齐全，等于逼着后来人继续维护一份没人看得到的译文。
- MNT-B9294 [medium] [u03] — 前端有四个模块已经没人用了，合计近 800 行：一个录音 composable（只有它自己的测试在用）、一个聊天截图工具、一个反调试工具，还有一个只被复制走逻辑的语音转写 API。读代码的人会以为这些能力还在生效。
- MNT-BEAD5 [medium] [u04] — 脚本目录自己写了一条规矩“根目录只放常用脚本，一次性的放 diag/ 或 ops/”，但根目录实际堆了 94 个条目、23 个云部署脚本，还有几对只差一个数字的重复脚本；想知道“部署到底该跑哪个”只能逐个点开看。
- MNT-0CAA3 [medium] [u12] — backend/scripts 里留着五个月前的五个 PowerShell 迁移脚本，路径写死在作者本机的 C:\Users\hp\... 上，其中一个的注释直接写“用来回滚另一个脚本”；同目录还有两份写死版本号的 Flyway 修复 SQL 和一个 0 字节的 vault 导出文件。别人跑这些脚本只会改错目录或改到已迁完的代码。
- MNT-2AC9C [medium] [u09] — Electron 主进程还留着一套“从 localStorage 解密登录 token”的注入脚本，但渲染层早已改成只把 token 放内存、不再写那两个键；这段代码现在每次开窗都会执行一遍、必然返回空，注释却还声称“与 secureToken.js 保持一致”，会误导后面的人以为存在 localStorage 回退。
- PRF-9FA45 [medium] [u05] — 服务端每 30 秒为所有单聊会话各算一次「最后一条消息时间」，会话越多越慢，最终拖慢聊天接口和数据库。
- PRF-454DD [medium] [u01] — 情绪衰减定时任务每次把全表角色状态读进内存再逐行回写，角色多时一次要跑很久，期间数据库连接和写锁都被长期占着。
- PRF-5C4A1 [medium] [u05] — 每天两次为「日记」统计当天聊天量，需要扫描整张消息表，聊天记录越多越慢。
- PRF-E4A17 [medium] [u01] — 打开会话列表时，服务端把每个会话最后一条消息的完整正文都取回来，只为显示 120 字预览，白传了大量文本。
- PRF-D16A1 [medium] [u06] — 聊天记录越长，打开会话和收到新消息时越卡，因为每次都要重建整条时间线并重排全部消息。
- PRF-F4602 [medium] [u06] — 聊天窗口每 10 秒重新下载最近 50 条消息，即使一条新消息都没有；窗口切到后台也照旧。
- REL-BC684 [low] [u10] — 管理后台只有一个 8 行的菜单测试，而且脚本允许“一个测试都没有也算通过”，所以后台登录态和接口出错不会被任何门禁拦住。
- MNT-10A82 [low] [u10] — 管理后台的 package.json 里装了表格库、图表库、路由和一套图标，但后台一共只有十个源文件的壳子，这四样东西一次都没被 import 过；每次装依赖都要多下一批包，也给升级维护增加无谓的负担。
- PRF-585C8 [low] [u07] — 记忆检索时一条一条地向 Redis 取向量，50 条候选就是 50 次往返，时间全花在等待上。
- PRF-2EAFA [low] [u06] — 往上翻聊天记录时消息数组只增不减，翻得越多前端内存越大、越卡。

## 测试证据

四条门禁命令逐条记录；`主编实跑` 指本轮在仓库根目录直接执行，`专家实跑` 指该专家在 handoff 中声明的执行结果。

| 命令 | 结果 | 证据 |
|---|---|---|
| `npm --prefix admin test` | **failed**（exit 1） | 主编实跑：`'vitest' 不是内部或外部命令`，`admin/node_modules` 不存在。correctness 在 handoff 里写 passed，与实测不符，本报告按实测记 failed。 |
| `mvn -q -f backend/pom.xml test` | **passed** | 4 位专家实跑一致：各模块编译并跑完单测，`BUILD SUCCESS`；日志里的 WARN/ERROR 来自负路径断言（鉴权失败、网关异常），不是构建失败。 |
| `pytest -q backend-agent` | **failed**（干净环境）/ 1157 passed（`.venv`） | 主编实跑：根目录 48 个 collection error（exit 2），缺 `aiomysql` 等依赖与 `pythonpath`。security / reliability 在 `backend-agent/.venv` 内实跑为 1157 passed；两者不矛盾，说明命令本身不可移植（REL-F9370）。 |
| `npm --prefix frontend test` | **passed** | perf 实跑 76 files / 460 tests；maintainability 报 75 / 459，差异来自运行时的文件筛选，均 exit 0。 |

补充：correctness 与 reliability 各自用 MockTransport / 单测复现了 C-001 与 REL-34EFB；这两条不是纸面推断。admin 的「passed」是本轮唯一一处专家声明与实测冲突，已按实测纠正，并据此保留 REL-BC684 / MNT-10A82。

## 跨单元系统性风险

单条发现修完只是止血，下面五组是同一类土壤长出来的，建议按组处理：

1. **agent 边界默认放行**（SEC-EA2CB / SEC-D6178 / SEC-E06CB，结构土壤见 MNT-258C6）：`main.py` 25 个端点复制同一段 `if user_id_header and ...` 样板，缺头就落到 body；网关 `location /api/agent/` 只限流不鉴权。三条发现是同一扇门的不同利用方式，只修其中一条等于没修。
2. **异常路径把失败记成成功**（COR-559B1 / REL-34EFB / REL-42BC8 / REL-49628）：流式 `[DONE]` 提前 return、`GeneratorExit` 记成上游故障、SSE 收尾只 catch IOException、event 队列无死信无 try/catch——共同点是「结束时没人负责收尾」，表现为工具不执行、熔断误开、回复不落库、通知静默丢。
3. **同一事实多处写、无幂等**（COR-CA186 / COR-B3226 / COR-50DF0 / REL-388F4）：记忆 sourceHash 冲突覆盖、新建记忆重复入队、outbox 卡 RUNNING 无人回收、后台重试预算短于熔断窗口——记忆链路的写入端和投影端各自假设对方会兜底。
4. **危险默认值**（SEC-A3F36 / SEC-701CA / SEC-CA474 / REL-BC684）：默认信任 XFF、缺主密钥退回明文、无公钥跳过清单验签、`--passWithNoTests` 让空套件也绿。四条都是「配置缺失时选择了更宽松的一侧」，与 AGENTS.md §3.3「默认取最保守的一侧」直接冲突。
5. **前端首屏与轮询成本**（PRF-9225F / PRF-D16A1 / PRF-F4602 / PRF-2EAFA）：element-plus 全量注册让 launcher/quick 入口静态背上 1.1MB chunk，聊天页无虚拟滚动、10s 轮询拉 50 条全文、上拉无上限——桌宠与快捷聊是常驻窗口，这部分成本是持续付的。

## 修复顺序

**P0（公网发布前必须）**：SEC-EA2CB 网关侧加 `auth_request` 或内部签名，并在 agent 端点强制身份（缺头 401，不再回退 body）；SEC-701CA 缺主密钥时拒绝加密/启动失败，同时轮换已明文落库的 Key。
**P1（本周）**：COR-559B1（`return`→`break`）、REL-34EFB（只对真实上游错误计熔断）、REL-42BC8（收尾 catch 扩到 Exception 并保证 complete）、SEC-E06CB（baseUrl 白名单 + 禁回显）、SEC-A3F36（默认不信任 XFF，按部署配置显式开启）。
**P2（两周）**：PRF-71D14（群聊内外层线程池隔离）、COR-CA186 + COR-B3226（记忆 hash 与入队去重）、COR-50DF0 + REL-388F4（outbox 回收定时 + 重试预算 ≥ 熔断窗口）、SEC-D6178（桌面感知独立鉴权 + 配额）、SEC-1101A（TOTP 失败计数与锁定）。
**P3（迭代）**：MNT-258C6（main.py 拆 router，顺手把鉴权收成一处）、PRF-9225F（element-plus 按需）、PRF-D16A1 / PRF-F4602（虚拟滚动 + afterSeq 增量）、索引类 PRF-9FA45 / PRF-5C4A1、清理类 MNT-745BE / MNT-B9294 / MNT-10A82。

## 覆盖缺口

- **深度**：每单元实际读 30–41 个文件，相对 1108 个文件仍是抽样；u04 / u09 / u10 / u12 无 reviewed，只有 sampled，结论强度弱于其余单元。
- **运行态**：未连生产库做 `EXPLAIN` 或压测，性能条目的数据库成本是索引与 SQL 形态的静态推导（PRF-9FA45 / PRF-454DD / PRF-5C4A1 尤其需要真实执行计划确认）。
- **测试可信度**：admin 套件本轮从未真正执行（依赖缺失）；`pytest -q backend-agent` 在干净环境不可跑，1157 passed 依赖 `.venv`；这两点会让「本地全绿」高估真实覆盖率。
- **未验证**：未做浏览器/E2E、未验证生产 nginx 实际配置与 `.env`、未验证 MinIO 更新资产与签名链、未做并发压测（群聊死锁由线程模型推导 + 代码路径确认，未在真实负载下复现）。

## 转交与保留

专家之间转交但未计入上述 50 条的观察，共 11 条；它们不是「没问题」，而是证据强度或归属暂不构成本轮发现：

- security → reliability — Agent 侧 /agent/* 无速率限制（observe_limiter 只是并发信号量），额度耗尽与雪崩风险需可靠性视角复核。
- security → correctness — X-Forwarded-For 取首段与网关 $proxy_add_x_forwarded_for 追加语义不一致，属限流口径错误，建议与部署/网关一起修正。
- reliability → correctness · `backend-agent/app/memory/relay.py` — memory_outbox 认领后卡在 RUNNING、requeue_stuck 无定时调用。本轮已作为 C-006 报告故不重复计数；其“失败不可见”的一面已在 REL-03/REL-06 的监控建议中覆盖。
- reliability → maintainability · `backend/lianyu-service/src/main/java/com/lianyu/service/ai/AiResilience.java` — AiResilience / UpstreamCircuitBreakerFactory 与 application.yml 的 resilience4j 配置没有任何注入点，属死代码 + 失效配置；Python 侧已有等价熔断，不构成本轮可证的可靠性损失，建议交 maintainability 处理。
- reliability → maintainability · `frontend/src/composables/useVoiceRecorder.js` — onChunk 失败被 `.catch(() => {})` 吞掉；全仓只有它自己的单测引用该 composable，生产代码未使用，证据不足以证明可达。
- reliability → reliability · `frontend/electron` — updater/qqBridge/napcat 空 catch 扫描命中 31 处，逐处核对均为单条音频/代理探测/状态同步的可降级分支，上层有状态机与日志兜底，不构成失败不可见。
- maintainability → perf · `backend-agent/app/main.py` — 单文件 1278 行、25 个路由未拆 router；已作为 M03 上报，若拆分排期靠后可作为独立重构任务跟踪。
- maintainability → correctness · `frontend/electron/main.js` — 孤儿 IPC handler desktop:notify-window-changed：68 个 ipcMain.handle 与 66 个 preload invoke 全部匹配，仅此通道无暴露/无调用；删除属机械改动，价值低于已上报的 12 条。
- maintainability → correctness · `AGENTS.md` — 文档与配置漂移：AGENTS.md:139 仍写“当前最新 V55”（实际 V57）；docker-compose.yml:110,157 传的 LIANYU_AGENT_SHADOW_MODE 与 :59 的 LIANYU_MEMORY_OUTBOX_ENABLED 在 backend 无任何读取。
- maintainability → correctness · `admin/src/pages/ManagementPage.vue` — configs.users.columns 先定义后被整段覆盖，前一处为死赋值；admin 后台为壳子状态，等该页真正开发时一并整理。
- maintainability → perf · `frontend/electron/startupProfiler.js` — electron/startupProfiler.js 11 行与 src/utils/startupProfiler.js 18 行同签名重复（时钟基准不同）；合并需统一时钟语义，暂列观察。

## 关系图

[graph.html](graph.html) 由 verdict.relations 生成，标出同文件、同根因、依赖与相关四类边；本轮 10 条关系里最值得先看的是 `SEC-EA2CB ↔ SEC-D6178`（同一扇门）与 `PRF-9FA45 ↔ PRF-5C4A1`（同一张表的两条慢查询）。
