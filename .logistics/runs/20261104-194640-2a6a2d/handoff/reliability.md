# 健壮性审查报告

> 审查对象：`C:/Users/hp/Desktop/LianYu-PC`（分支 `main`，head `c652e14`，工作区干净）
> 审查模式：整项目（非 diff、非单次提交）
> 审查范围：1100 个文件 / 135978 行 / 12 个审查单元
> 角色：reliability（错误处理、失败传播、重试与幂等、部分失败、测试缺口与回归保护）
> 结论：11 条发现（error-handling 9 条，tests 2 条），其中 high 2 条、medium 6 条、low 2 条、info 0 条；未发现 critical。

---

## 一、范围与覆盖

本次按 `scope.json` 的 `project.units` 逐单元推进。体量大的单元（120 文件级）先用 ripgrep 做跨文件模式扫描（`catch` / `except` / `subprocess.run` / `set -e` / `.skip` / SSE 生命周期 / MQ 确认），再对命中点与核心链路逐文件精读。

| 单元 | 范围 | 状态 | 实读文件数 | 理由 |
|---|---|---|---|---|
| u01 | backend/lianyu-service (1/3) 120 文件 / 12117 行 | reviewed | 5 | 精读 `ConversationService.sendMessageStream`（落库时机）、`WebPushService`、`NotificationPushConsumer` |
| u02 | backend/lianyu-service (2/3) 120 文件 / 16014 行 | reviewed | 4 | 精读 `MemoryConsumer`、`AiBackgroundConsumer` 注释与消费者语义 |
| u03 | frontend/src (1/2) 120 文件 / 15840 行 | reviewed | 6 | 精读 `ChatPage.vue` 发送/重试/收尾、`api/conversation.js`、`useStreamAbort.js`、`assistantStreamDrain.js`、`shared/sseParser` |
| u04 | scripts (1/2) 120 文件 / 12803 行 | reviewed | 3 | 通读 `_cloud_deploy_pull.py`，全量统计 `subprocess.run` 与 `check=True`、shell `set -e` |
| u05 | backend/lianyu-dao 110 文件 / 2934 行 | sampled | 2 | 模式扫描 `catch (Exception)`（零命中）+ 实体常量；未逐条读 mapper |
| u06 | backend-agent 102 文件 / 16514 行 | reviewed | 15 | 精读 gateway / resilience / http_client / db / write / memory.relay / main / stream_chat / tool_loop / chat_turn / prompt_pipeline / data_readers / embedding |
| u07 | frontend/src (2/2) 95 文件 / 24648 行 | sampled | 3 | 抽查 `QuickChatPage.vue` 与 catch 分布；与 u03 共用同一套 API/composable |
| u08 | backend/lianyu-web 71 文件 / 7840 行 | reviewed | 9 | 精读 `AgentRuntimeClient`（全文）、`AgentStreamAdapter`、`AgentStreamBridge`、`AgentStreamBridgeTest`、`GlobalExceptionHandler` |
| u09 | frontend/electron 68 文件 / 14745 行 | sampled | 2 | 核对 `main.js` 与 `logger.js` 的全局异常兜底；桥接/更新器未逐文件读 |
| u10 | 其他模块（.github、admin/src、security、deploy 等）67 文件 / 4923 行 | reviewed | 3 | 通读 `.github/workflows/ci.yml`、`TotpUtil.java`、admin 测试入口 |
| u11 | backend 小模块合集（lianyu-admin 32 + lianyu-service 27）59 文件 / 3141 行 | sampled | 3 | 模式扫描 lianyu-admin 的全部 catch 块 |
| u12 | backend/lianyu-common、backend/scripts、installer、lianyu-app 等 48 文件 / 4459 行 | sampled | 1 | 仓级脚本扫描覆盖；其余仅模式扫描 |

**没有单元被 skipped**，因此整体状态为 `ok`。u05 / u07 / u09 / u11 / u12 是 `sampled`：这些单元做了针对性模式扫描并读了关键文件，但**不能**视为全量已读（详见第五节）。

已排除：`.logistics/` 与 `docs/logistics/` 下由本工具产生的文件。

---

## 二、发现（按单元组织）

### u01 / u03：单聊发送链路（最严重）

#### 1)【high · plan】前端网络错误自动重发，服务端无幂等键：一次抖动即重复落库与双份生成

- 位置：`frontend/src/pages/ChatPage.vue:1439`（重发分支）；`backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java:328-331`（落库时机）
- 证据：`ChatPage.vue:1437-1442` 在 `isNetworkError(err)` 时 `sleep(800)` 后再次 `await attemptStream()`；`attemptStream` 发的是 `POST /conversation/{id}/messages/stream`，请求体由 `frontend/src/api/conversation.js:67-83` 组装，只有 `provider/model/content/imageUrl`，**没有 clientMsgId 或 Idempotency-Key**。而“读流中途断网”抛出的 `TypeError` 同样落进这个分支（`frontend/src/utils/assistantStreamDrain.js:26-27` 的 `reader.read()` 直接上抛）。服务端 `ConversationService.java:328-331` 在请求一开始就 `getNextSeq` + `messageMapper.insert(turn.userMsg())`，随后 `recordUserTurn` 与 `characterStateService.afterUserMessage` 一并执行，全程没有去重。
- 影响：一次网络抖动 = 两条相同用户消息 + 两次完整模型生成（双倍 token/时长）+ 两条助手回复，关系分与情绪被重复累计；若第一次已经流出半截，前端只用第二次结果渲染，库里那条重复用户消息会永远留着。
- 修复摘要：前端 `handleSend` 生成一次 `clientMsgId`，重发复用；`SendMessageRequest` 增字段，服务端在 insert 前按 `(conversationId, clientMsgId)` 查重，dao 加唯一索引；把“是否允许重发”收敛成“尚未收到任何 content 帧”才允许。

### u06：backend-agent

#### 2)【high · plan】记忆 relay 只捞 PENDING：进程崩溃后停在 RUNNING 的行永久卡死且无告警

- 位置：`backend-agent/app/memory/relay.py:208`（`requeue_stuck`）；`backend-agent/app/main.py:54-65`（后台循环）
- 证据：`_claim` 用 `UPDATE ... SET status='RUNNING' WHERE id=? AND status='PENDING'` 抢占，`drain_once` 只取 `status='PENDING' AND next_attempt_at<=NOW()`；后台 `_relay_loop` 只调 `drain_once`，异常仅 `logger.warning`，从不回捞 RUNNING。唯一能把 RUNNING 拉回可重试状态的是人工接口 `POST /agent/memory/relay/requeue` → `requeue_stuck`（`relay.py:208`，端点注册在 `app/main.py:967-972`），全仓没有任何定时器、指标或告警引用它。
- 影响：backend-agent 被 kill / OOM / 滚动重启时，正在处理的那条 `memory_outbox` 永远停在 RUNNING，该轮对话的记忆不再落库，后续 reconcile 也补不回来，日志里甚至没有异常（进程是外部杀掉的）。对“陪伴靠记忆”的产品，表现为角色静默“忘事”，且只能人工恢复。
- 修复摘要：引入 `claimed_at` / `locked_until` 语义与 RUNNING 超时阈值，让 `_relay_loop` 每轮先回收超时行（按 `_retry_or_dead` 计数，超限置 DEAD 并 `logger.error`），并把 DEAD / 超时条数打成可观测指标。

#### 3)【medium · plan】工具循环只捕获 `httpx.HTTPError` / `RuntimeError`，单个工具的入参异常打崩整轮

- 位置：`backend-agent/app/tool_loop.py:99`
- 证据：`except (httpx.HTTPError, RuntimeError) as exc`（`tool_loop.py:99-104`），错误文本回灌模型。工具实现里常见的 `ValueError` / `KeyError` / `TypeError` 不在捕获范围，会冲出 `run_tool_loop`；`stream_chat_events` 只捕 `GatewayRejectedError` 与 `GatewayError`，于是落到 `app/main.py` 的生成器兜底 `yield sse_error("生成失败，请重试")`。
- 影响：模型给工具传错参数时，本应“工具报错 → 模型自我纠正”，实际会把整轮已流出内容作废，用户只收到一句通用失败。
- 修复摘要：`except Exception` 兜底但显式重抛 `asyncio.CancelledError`，按可恢复/不可恢复分类，日志带工具名与参数摘要。

#### 4)【medium · direct】`pool()` 无锁惰性创建，并发冷启动可能建两个池并泄漏一个

- 位置：`backend-agent/app/db.py:42`
- 证据：`pool()` 先查模块级缓存再 `await aiomysql.create_pool`，无 `asyncio.Lock`、无“创建中”标记；两个协程可在首个 `await` 期间交错，后赋值覆盖先赋值。
- 影响：冷启动/重建时多出一份 MySQL 连接（按池上限倍数泄漏），长期可能顶到 `max_connections` 让后续请求全部连接失败，且泄漏的池没有日志可追。
- 修复摘要：模块级 `_pool_lock`，`async with` 后二次检查再创建；`close_db()` 同锁；补并发用例断言 `create_pool` 只被调用一次。

#### 5)【medium · direct】`call_with_transient_retry` 立即重试、无退避无 jitter，且读超时不重试

- 位置：`backend-agent/app/resilience.py:302`
- 证据：`resilience.py:302-325` 里两次尝试之间没有任何 `asyncio.sleep`/退避/jitter，`max_attempts=2`；`is_transient_failure` 只认 DNS / 连接被拒 / 连接超时，`httpx.ReadTimeout` 不算瞬时失败。
- 影响：agent 滚动更新那 1-3 秒窗口内两次尝试几乎同毫秒发出、双双失败，重试形同虚设；无 jitter 时多实例同刻重连会把刚起来的对端再压垮；读超时（“对端慢但活着”）反而直接失败。
- 修复摘要：指数退避 + full jitter（可配上限），`ReadTimeout` 纳入可重试，但已产生输出（`emitted_any`）的流式调用维持不重试。

### u01 / u02 / u08：Java 侧失败传播与后台任务

#### 6)【medium · plan】死信队列无人消费、广播队列没有 DLX：后台任务失败后既不重试也无人看见

- 位置：`backend/lianyu-web/src/main/java/com/lianyu/web/config/RabbitMqConfig.java:86`
- 证据：`RabbitMqConfig.java:56-67`、`71-82` 给 `memory.summary` / `ai.background` 配了 `x-dead-letter-exchange` 与 `*.dlq`，但全仓没有任何 `@RabbitListener` 消费 `memory.summary.dlq` / `ai.background.dlq`，也没有 admin 接口或告警读取；`event.broadcast.queue`（第 84-87 行）连 `x-dead-letter-*` 都没有，而容器统一 `defaultRequeueRejected(false)`（第 130、146 行）。`NotificationPushConsumer.java:20-25` 直接调 `webPushService.sendToUser`，`WebPushService.sendOne` 对每个订阅的失败只 `log.warn`（`WebPushService.java:111-121`，含瞬时网络错误同样只 warn 后 return）。
- 影响：推送任务一失败即丢（无 DLX、无重投、无入库）；memory/ai-background 的失败只堆在无人消费的队列里，队列涨了也没人知道，等于“静默失败 + 无重放入口”。
- 修复摘要：给 `event.broadcast.queue` 补 DLX；为三个 DLQ 增加消费者落库 + error 日志；提供最小重放入口；push 瞬时失败改为有限退避重试或落 DLQ。

#### 7)【medium · plan】SSE 超时或客户端断开后不取消上游订阅，模型在后台继续生成到结束

- 位置：`backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentStreamAdapter.java:92`
- 证据：`subscribe(relay::accept, error -> {…relay.fail(…);}, relay::finish)`（`AgentStreamAdapter.java:89-98`）丢弃了返回的 `Disposable`；全仓 `backend/**/*.java` 没有 `emitter.onTimeout` / `onCompletion` / `onError` 注册，也没有 `dispose()`（rg 零命中）。`SSE_TIMEOUT_MS = 1_800_000L`（第 25 行）。客户端断开后 `relay.abort()` 只是把 `closed` 置真（`AgentStreamBridge.java:126-140`），随后 `accept` 立刻 `return false`（第 65-67 行），但 Reactor 订阅并不会因此取消。
- 影响：用户关窗或超时后，Python 仍为该用户跑完整回合（最长 30 分钟），白烧 token 并长期占 `LaneLimiter` 名额；并发高时闸门被挤满，其他用户收到 `GatewayRejectedError`（“对方模型暂时繁忙”），一个人的操作放大成全站故障。
- 修复摘要：保存 `Disposable` 到 Relay，注册 `onCompletion/onTimeout/onError` 与 `abort()` 中 `dispose`，保持现有“断开不算业务失败”语义，并补超时取消用例。

#### 8)【low · direct】兜底异常只记类名与 message，不传异常对象，500 排障没有堆栈

- 位置：`backend/lianyu-web/src/main/java/com/lianyu/web/handler/GlobalExceptionHandler.java:163`
- 证据：`log.error("Unhandled exception: {} - {}", e.getClass().getSimpleName(), e.getMessage())`；SSE 分支同样只记类名（第 159 行），全仓没有一处把 `e` 作为 logger 最后一个参数传入。
- 影响：生产上唯一能定位 500 的日志只剩一行类名 + 消息，`NullPointerException` 的抛出点完全丢失，客户端只得到笼统的 `INTERNAL_ERROR`。
- 修复摘要：`log.error("Unhandled exception", e)`，响应体保持不含堆栈。

### u04：脚本与发布链路

#### 9)【low · direct】部署自检用 grep 判定，无匹配即 exit 1，把成功部署报成失败

- 位置：`scripts/_cloud_deploy_pull.py:145`
- 证据：`run()` 在远端退出码非 0 时 `raise SystemExit(code)`（第 39-43 行）。第 145 行执行 `docker logs lianyu-backend 2>&1 | grep -E 'all ports wired|ports missing' | tail -2`，日志里没有这两句时 `grep` 退出码为 1，脚本在此中止——而这发生在第 130-143 行健康检查已通过、容器已经起来之后。同一脚本第 149-158 行的能力自检却只 `print` 警告，两种口径并存。
- 影响：一次成功部署被报成失败：`DEPLOY_DONE` 不打印，`ship-release.ps1` 第 2 段抛错，Electron/Release 段不再执行；运维要么去查一个不存在的问题，要么从此习惯性忽略这段失败（真正的失败也会被一起忽略）。
- 修复摘要：改成 `|| true` 或 `grep -c` 取值后在 Python 里判定并 print 警告；需要门禁就写显式断言。

### u10：CI 门禁

#### 10)【medium · plan】CI 只跑 Java 与前端，backend-agent 与 admin 完全不在自动化里，前端还跑在已知会 flake 的并行模式

- 位置：`.github/workflows/ci.yml:9`
- 证据：`jobs` 下只有 `backend`（`mvn -B -ntp test`）、`frontend`（`npx vitest run`）、`deps-audit`（`continue-on-error: true`）。没有 `backend-agent` 的 `pytest`，也没有 `admin` 的 `npm test`——而本次审查的四个门禁里就包含这两条，AGENTS.md §4 也把它们列为提交前必跑。`frontend` 任务未带 `--no-file-parallelism`，而 AGENTS.md §4 明确写着 updater 并发用例在满载并行下会偶发红。
- 影响：16514 行的 backend-agent（网关熔断、工具循环、prompt 组装、记忆 relay）与 admin 前端在任何 PR 上都没有自动化回归保护，只能靠人记得本地跑；前端唯一门禁又因已知 flake 允许长期红，红绿灯失去信号价值。
- 修复摘要：新增 agent（setup-python + `pytest -q backend-agent`）与 admin（`npm --prefix admin ci && npm --prefix admin test`）两个 job；frontend 改 `--no-file-parallelism`；先以 `continue-on-error` 观察再转必需。

### u03 / u07：测试缺口

#### 11)【medium · plan】流式回复消费与网络重发路径没有任何测试覆盖

- 位置：`frontend/src/utils/assistantStreamDrain.js:10`
- 证据：rg 在测试文件中搜不到 `drainAssistantStream` 与 `ChatPage`；`frontend/src/utils/__tests__/` 只有 `sseParser.test.js`（只覆盖单行解析与累计器），没有 `assistantStreamDrain` 的用例；`ChatPage.vue`（2266 行）没有对应测试。后端侧 `AgentStreamBridgeTest` 有 `clientDisconnectStopsRelayingAndCompletes`（第 162-172 行），但没有“同一请求被重发两次”的用例。
- 影响：把重试条件从 `isNetworkError` 放宽、或让 `drainAssistantStream` 吞掉中途断开，都不会有测试变红；问题只能等线上出现重复消息才被发现。
- 修复摘要：补 `assistantStreamDrain` 四类用例（正常/中途 TypeError/AbortError/无 reader），把重发决策抽成 `shouldRetrySend(err, receivedAny)` 纯函数并单测，后端补“同 clientMsgId 两次只产生一条消息”用例。

---

## 三、已查未发现问题的区域

以下位置按本次关注面检查过，未发现值得上报的问题（如有相反意见欢迎用行号反驳）：

- **Electron 全局异常兜底**（u09）：`frontend/electron/main.js:2976-2981` 与 `frontend/electron/logger.js:149-154` 都注册了 `uncaughtException` / `unhandledRejection`，并落盘到统一 logger；`render-process-gone` / `child-process-gone` 也有处理。
- **客户端断开语义**（u08）：`AgentStreamBridge.abort()` 把“客户端断开”当成功回调是**有意设计**，且有 `AgentStreamBridgeTest:162-172`（`clientDisconnectStopsRelayingAndCompletes`、断言 `callback.completedError == null`）锁定，未作为缺陷上报。
- **SSE 错误帧可见性**（u06/u03）：`backend-agent/app/main.py` 两个流端点的 `event_source()` 都有 `except Exception` → `sse_error(...)` + `[DONE]` 兜底；前端 `shared/sseParser` 的累计器在收到 `error` 帧时 `throw`，错误对用户可见，不存在“静默截断当成功”的问题。
- **HTTP 超时纪律**（u06）：`app/http_client.py` 的 `outbound_timeout()` / `new_client()` 统一加了超时、UA、强制 IPv4；`MilvusConfig` 也显式设置了 RPC deadline。
- **写库事务**（u06）：`app/write.py` 的 `transaction()` 显式 begin/commit/rollback，rollback 失败只降级为 warning（可接受，因为连接已不可用）。
- **admin TOTP**（u10/u11）：`TotpUtil.generate` 捕获异常返回空串，但 `verify` 只做 `equals` 比较，失败一律 false，属 fail-closed，不构成"静默放行"。
- **lianyu-dao**（u05）：模式扫描未发现吞异常（无 `catch (Exception)` 后返回空/默认值的用法）。
- **被跳过/被禁用的测试**：全仓（js/ts/vue/java/py）搜索 `.skip(` / `.only(` / `xit(` / `xdescribe(` / `@Disabled` / `pytest.mark.skip`，**零命中**；同时 js/ts/vue 中空 `catch {}` 也是零命中。

---

## 四、建议修复顺序

1. **第 1 条（重复消息 / 双份生成）** —— 用户直接可感知，且每次网络抖动都在烧钱。先加 `clientMsgId` 与唯一索引，再把“已收到内容后不重发”收紧。
2. **第 2 条（记忆 RUNNING 卡死）** —— 静默丢记忆，修一次长期受益；先上“超时回收”，指标可后补。
3. **第 10 条（CI 门禁）** —— 不是产品故障，但没有它，前两条以后的回归都无人兜；成本极低，建议与 1、2 并行。
4. **第 6 条（DLQ 无人消费）** 与 **第 7 条（不取消上游订阅）** —— 一个让失败不可见，一个让故障放大成全站；都影响容量与排障。
5. **第 3 条（工具循环捕获过窄）**、**第 4/5 条（连接池与退避）** —— 触发条件更局部，按季度计划排。
6. **第 9/8 条（部署自检、日志堆栈）** —— 机械、低风险，可穿插在任意提交里顺手修。
7. **第 11 条（流消费测试）** —— 与第 1 条配套：先写测试锁定现状，再改行为。

---

## 五、未验证 / 抽样说明（不要当成全量已读）

- **u05（lianyu-dao，110 文件）、u07（frontend/src 2/2，95 文件）、u09（frontend/electron，68 文件）、u11（backend 小模块，59 文件）、u12（48 文件）** 只做了模式扫描 + 关键文件精读，**不是**全量已读。其中 u09 的 QQ/微信桥重连、子进程回收、更新器失败路径，u05 的 Flyway 迁移与事务边界，都在 `deferred` 里明确留待后续。
- **四个门禁命令均未运行**（`npm --prefix admin test`、`mvn -q -f backend/pom.xml test`、`pytest -q backend-agent`、`npm --prefix frontend test`）：本次为只读审查。所有 tests 类发现都基于**源码与 CI 配置**（缺 job、缺测试文件），不是失败日志。
- 工具限制：`ops_grep` 会因 `invalid-encoding` 跳过约 68 个文件，跨文件扫描改用 `C:/Users/hp/AppData/Local/hermes/tools/ripgrep-15.2.0-win32-x64/rg.exe` 重做；"rg 零命中"类结论（如无 `onTimeout`、无 `.skip`）在该范围成立。
- 第 7 条（不取消上游订阅）为 `confidence: medium`：代码证据明确（无 `onTimeout` 注册、丢弃 `Disposable`），但 Spring 在超时后对已 complete 的 emitter 的写行为未做运行时验证。

---

## 六、跨专家转交项

| 转交 | 内容 | 理由 |
|---|---|---|
| security | `scripts/_cloud_deploy_pull.py:9-10,55` 硬编码生产 IP 且 `AutoAddPolicy()`；同文件把 `.env` 里的 SSH 口令读入环境变量 | 属于凭据/主机校验问题，不做 reliability 上报 |
| security | `WebPushService.sendOne` 对 404/410 会 `disableSubscriptionByEndpoint`，但对 4xx 其它状态只 warn——是否存在订阅被第三方伪造 endpoint 触发的禁用风险 | 需要授权面判断 |
| correctness | `ConversationService.sendMessageStream` 的 `getNextSeq` + `insert` 是否在事务内、并发下 seq 是否可能重号 | 并发正确性，超出 reliability 关注面 |
| perf | 第 7 条修好后可复测“客户端断开后 Python 侧生成时长与 lane 占用”，量化浪费 | 需要运行时数据 |
| maintainability | `AgentRuntimeClient.java:303` 的 `body) {        try {` 格式错位；`AgentStreamAdapter.java:114` 收尾的 `}}` 挤在方法末尾 | 纯可读性问题 |
| security | `.github/workflows/ci.yml` 的 `deps-audit` 用 `continue-on-error: true`（CI 里唯一一条永远绿的 job） | 供应链风险，归 security |
