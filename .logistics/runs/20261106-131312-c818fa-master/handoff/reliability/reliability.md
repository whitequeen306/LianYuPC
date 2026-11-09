# 健壮性审查报告

审查对象：予念 YuNian PC 全仓库（1108 个文件 / 140294 行 / 12 个审查单元）。
审查方式：逐单元跨文件模式扫描 + 关键链路全文阅读；只读源码，未修改任何文件。
测试门禁：4 条命令中 2 条通过、1 条未执行、1 条在同一 commit 上时红时绿。

## 范围与覆盖

| 单元 | 模块 | 状态 | 读取文件数 | 说明 |
|---|---|---|---|---|
| u01 | backend/lianyu-service (1/3) | reviewed | 34 | AI 端口、`AiResilience`、`AiBackgroundConsumer`、`AsrStreamClient`、`ModelCatalogService`、`SseChatStreamHelper` 全文读，其余按风险行扫描 |
| u02 | backend/lianyu-service (2/3) | sampled | 34 | DTO 仅校验注解；memory/moments 服务按失败分支扫描 |
| u03 | frontend/src (1/2) | reviewed | 34 | `httpCore.js` 全文（超时/401 单飞刷新/重放）、api 封装逐个看超时与 `skipGlobalError` |
| u04 | scripts (1/2) | sampled | 34 | 运维/发布脚本，全部显式 `timeout`，失败多数直接抛出 |
| u05 | backend/lianyu-dao | sampled | 34 | Flyway 迁移用 `IF NOT EXISTS` / `information_schema` 守卫，未发现非幂等 DDL |
| u06 | frontend/src (2/2) | sampled | 34 | `ChatPage.handleSend` 流式发送/重试/回滚逐行读，其余页面按风险行扫描 |
| u07 | backend-agent | reviewed | 34 | `resilience.py` / `gateway.py` / `stream_chat.py` / `http_client.py` 全文；熔断误记已用脚本复现 |
| u08 | backend/lianyu-web | reviewed | 34 | 全部 controller 与 `web/agent` 适配器扫描，`AgentStreamAdapter` / `AgentStreamBridge` / `AgentRuntimeClient` 全文 |
| u09 | frontend/electron | sampled | 34 | `updater.js` 失败路径逐条核对（空闲超时、sha512、断点续传回退、错误态），QQ/NapCat 抽样 |
| u10 | 其他模块（root、.github、admin 等） | sampled | 34 | CI workflow、admin 前端、Dockerfile、pom；确认 CI 无 admin job |
| u11 | backend 小模块合集 | sampled | 34 | 后端测试集合，用于 tests 章节判定（无 abort/断流用例） |
| u12 | backend/lianyu-app、lianyu-common 等 | sampled | 34 | 安装器有 8/10 分钟看门狗；Jackson TypeHandler 解析失败直接抛，不吞 |

合计 408 个文件路径，覆盖全部 144 个 core 文件。

## 发现

- [critical] [u08] [plan] R1 — SSE 断流时把半截回复当完整回复落库
  详细问题：SSE 断流时把半截回复当完整回复落库
  位置：backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentStreamBridge.java:126 · 证据：`Relay.accept()` 在 `emitter.send` 抛 `IOException` 时调用 `abort()`（:74-78、:83-87），而 `abort()`（:126-140）先 `emitter.complete()` 再 `callback.onComplete(partial, null)`——error 明确传 null；`ConversationService.onComplete`（ConversationService.java:377-407）只在 `error != null && !isClientStreamDisconnect(error)` 时跳过落库，于是断流前缀被 `saveAssistantReplies` + `recordAssistantTurn` + `enqueueSummary` + `notifyAssistantMessage` 当作完整回复处理。另一侧 `isClientStreamDisconnect` 只认 `error instanceof IOException`（:427-429），而 `SseChatStreamHelper.finishSseError` 传的是 `BusinessException`（SseChatStreamHelper.java:82-83），两个分支语义正好接反。
  影响：用户关窗/断网时，屏幕上那半句话被写成正式回复，并进入关系状态、记忆摘要与推送通知；重进会话看到的是被截断的“完整”回复，服务端只有一条 `log.info`。这是失败报成功的数据污染。 · 修复：给 Relay 增加 `truncated/abortReason`，`abort()` 改传专门的 `ClientStreamDisconnectedException`；`ConversationService` 按该类型判断并拒绝落库（或写入不完整标记）；`finishSseError` 同步改用该类型；补 `AgentStreamBridgeTest` 的 IOException 用例断言不落库。

- [high] [u07] [direct] R2 — 客户端取消/断连被计入上游失败，5 次即可把共享熔断打到 OPEN
  详细问题：客户端取消/断连被计入上游失败，5 次即可把共享熔断打到 OPEN
  位置：backend-agent/app/gateway.py:279 · 证据：`except BaseException as exc: await breaker.on_error(exc); raise`（:279-281）把生成器关闭时的 `GeneratorExit` 与 `asyncio.CancelledError` 一并记成失败；`resilience.py` 的 `on_error`（:132-152）只把 `GatewayBusinessError` 当成功，其余 `append(False)`，`_maybe_open`（:156-166）在 20 样本窗口失败率 ≥50% 即 OPEN。实测（venv 解释器）：连续 5 次「读一帧后 `aclose()`」每次记录 `GeneratorExit`，第 5 次后 `state=open`，日志 `breaker probe OPEN (failure_rate=100%, samples=5)`；`athrow(asyncio.CancelledError())` 同样被记为失败。
  影响：用户点停止、切会话、关窗口都会消耗该 (provider|base_url) 共享熔断器的失败预算，5 次即可让所有用户在该模型上被快速失败、收到“对方模型暂时繁忙”，而真实上游健康。 · 修复：改为 `except (asyncio.CancelledError, GeneratorExit): raise` + `except Exception` 记账；`on_error` 对取消/关闭直接 return；补 `test_client_disconnect_does_not_trip_breaker` 与 `test_cancelled_task_not_counted`。

- [high] [u06] [plan] R3 — 流式发送失败后自动重试没有幂等键，重复入库并重复调用模型
  详细问题：流式发送失败后自动重试没有幂等键，重复入库并重复调用模型
  位置：frontend/src/pages/ChatPage.vue:1437 · 证据：`handleSend` 在 `isNetworkError(err)` 时 `sleep(800)` 后直接重放同一 POST（:1437-1446）；`sendMessageStream`（frontend/src/api/conversation.js:67-83）只带 provider/model/content/imageUrl；`SendMessageRequest`（SendMessageRequest.java:10-33）没有 clientMessageId；服务端 `ConversationService.sendMessageStream` 在流建立前就 `messageMapper.insert(turn.userMsg())`（:328-330）并开始调用模型。
  影响：弱网或代理超时会稳定触发重试：同一句话出现两条用户消息、两次模型调用（双份 token 与配额），且第一次请求的服务端流不会取消，两条回复可能并发写入同一会话。 · 修复：前端生成 `clientMessageId` 并在重试时复用；服务端加（conversation_id, client_message_id）唯一索引，冲突时返回已有结果；命中幂等时不重复扣配额与写记忆；补「断网重放只落一条」用例。

- [medium] [u01] [plan] R4 — 后台 AI 任务遇到未知类型只打 warn 就 ack，任务静默消失
  详细问题：后台 AI 任务遇到未知类型只打 warn 就 ack，任务静默消失
  位置：backend/lianyu-service/src/main/java/com/lianyu/service/ai/background/AiBackgroundConsumer.java:94 · 证据：`default -> log.warn("AI background unknown type: {}", task.type())`（:94）之后方法正常返回，RabbitMQ 视为消费成功并 ack，消息不重投也不进 DLQ；`isAgentTransient`（:102-116）靠匹配中文文案（“prompt 组装失败”/“消息发送失败”/“上游超时…”）判断是否重试。
  影响：发布期新旧版本混跑或任务类型改名后，记忆摘要、角色日记、朋友圈等后台生成永久缺失且不可补，只留一行 warn；文案驱动的重试判定会在改文案时静默失效。 · 修复：未知类型抛 `AmqpRejectAndDontRequeueException` 进 DLQ（或专门异常）并打 error + 计数；用 `instanceof AgentTransientException` 取代中文文本匹配；发布期保留旧类型处理器至少一个版本；补未知 type 与瞬断判定用例。

- [medium] [u08] [plan] R5 — 客户端断开后不取消上游模型流，SSE 30 分钟超时也无回调
  详细问题：客户端断开后不取消上游模型流，SSE 30 分钟超时也无回调
  位置：backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentStreamAdapter.java:92 · 证据：`client.streamChat(...).subscribe(relay::accept, error -> {...}, relay::finish)`（:92-98）把返回 boolean 的 `relay::accept` 当 Consumer 丢弃，返回的 `Disposable` 未保存，emitter 也没注册 `onCompletion/onError/onTimeout`；`SSE_TIMEOUT_MS = 1_800_000L`（:25），超时后 `SseEmitter` 自行 complete，但没有任何代码取消上游 Flux。
  影响：一次关窗/断网后 Java→Python→供应商的流继续跑满（最长 30 分钟），token 照常计费，结果既不落库也不报错，还占用 bulkhead/连接名额，放大高峰期排队。 · 修复：保存 `Disposable`，在 `accept` 返回 false、`onCompletion/onError/onTimeout` 中统一 dispose；超时主动发错误帧并释放 permit；补「accept 返回 false 后上游被 dispose」用例。

- [medium] [u10] [direct] R6 — 受信本地端点走零超时 WebClient/RestClient 分支
  详细问题：受信本地端点走零超时 WebClient/RestClient 分支
  位置：backend/lianyu-ai/src/main/java/com/lianyu/ai/SsrfPinningClientFactory.java:86 · 证据：`restClientBuilder(ValidatedEndpoint)` 在 `!endpoint.isPinningRequired()` 时直接 `return RestClient.builder();`（:86-88），`webClientBuilder` 同样 `return WebClient.builder();`（:103-105），均无超时；同文件 javadoc（:59-63）写明“reactor-netty 默认无任何读超时——对端挂起会让调用线程在 `Mono.block()` 里永久 park（线上已发生全场 HTTP 卡死）”。`isPinningRequired() = !pinnedIps.isEmpty()`（OutboundUrlValidator.java:119-121），Ollama 本机 11434 端点被放行且不固定 IP（:58），`ModelCatalogService.fetchOllamaModels` 的 :215-217 正好命中该分支。
  影响：自建/本机 Ollama 配置后模型列表拉取使用无超时客户端，对端半开连接时线程长期被占，按该文件自述会拖垮整体 HTTP 线程池。 · 修复：两个非固定分支改为复用 `defaultRestClientBuilder()` / `defaultWebClientBuilder()`；补「黑洞端点 60s 内失败」用例。

- [medium] [u01] [plan] R7 — 主动消息/群聊/摘要等后台 AI 失败只落 debug，线上静默停摆不可见
  详细问题：主动消息/群聊/摘要等后台 AI 失败只落 debug，线上静默停摆不可见
  位置：backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ProactiveChatScheduler.java:173 · 证据：三处失败分支只 `log.debug("Proactive chat skipped ...")`（:173-174、:214-215、:264-265）；`GroupChatService` 主动群聊失败 `log.debug`（:288-289）；`SessionSummaryService` 读取失败 `log.debug`（:114-115）；`MomentsScheduler` tick 失败 `log.debug`（:144-145）；`MomentsCommentService` 角色评论失败 `log.debug`（:158-161）。生产默认 INFO 级，这些行不进日志。
  影响：模型/记忆/网关不可用时，用户只感觉“角色不再主动找我、不写日记、不总结”，服务端日志与指标完全安静，无法区分“没触发”与“每次都失败”。 · 修复：为 proactive_chat / group_chat / moments / session_summary 各加带 reason 标签的 Micrometer counter，按周期输出触发/成功/失败汇总，失败首条与每分钟首条用 warn，细节保留 debug。

- [medium] [u10] [direct] R8 — 前端门禁默认并行且未按 AGENTS.md 串行，同一 commit 两次结果不同
  详细问题：前端门禁默认并行且未按 AGENTS.md 串行，同一 commit 两次结果不同
  位置：frontend/package.json:18 · 证据：`"test": "vitest --passWithNoTests"` 没有 `--no-file-parallelism`；`.github/workflows/ci.yml:39` 也是 `npx vitest run`；AGENTS.md §4 明确要求 `npx vitest run --no-file-parallelism`。同一 commit 实测：满载时 `Test Files 5 failed | 70 passed (75)`、`Tests 8 failed | 451 passed (459)`（`wechatChannelRelease.test.js` 的 `findWechatChannelLaunch(root)?.hostPath` 为 undefined、`ENOENT ...wechat-channel\v0.1.0\payload\node.exe`；bootstrap、bootstrapRestoreRoute、launcherBootstrap 三条 `Test timed out in 5000ms`）；空闲时 `75 passed / 459 passed`。
  影响：门禁结果取决于机器负载，真回归会被当成 flake 重跑掉，假红又逼人反复重跑；本地、CI、文档三处口径不一致，等于没有统一门禁。 · 修复：脚本与 CI 都改为 `vitest run --no-file-parallelism`；启动类用例显式 `testTimeout`；连跑 3 次（含满载）确认稳定。

- [medium] [u10] [plan] R9 — admin 模块测试不在 CI 内且脚本带 --passWithNoTests
  详细问题：admin 模块测试不在 CI 内且脚本带 --passWithNoTests
  位置：admin/package.json:9 · 证据：`"test": "vitest --passWithNoTests"`；仓库只有 `admin/src/__tests__/menu.test.js`（8 行，断言菜单顺序）；`.github/workflows/ci.yml` 只有 backend / frontend / deps-audit 三个 job（:9-55），没有 admin；实测 `npm --prefix admin test` 报 `'vitest' 不是内部或外部命令`（`admin/node_modules` 不存在）。
  影响：管理端（登录、发布、用户处置）无任何自动化保护，测试写坏或依赖装错时 CI 永远绿；`--passWithNoTests` 还会在用例被误删时静默通过。 · 修复：CI 增加 admin job（`npm ci` + `npm test`）；去掉 `--passWithNoTests`；补登录失败/OTP/发布失败/会话过期用例；AGENTS.md 门禁表补一行。

- [medium] [u08] [plan] R10 — SSE 断流/取消与发送重试路径无任何回归测试
  详细问题：SSE 断流/取消与发送重试路径无任何回归测试
  位置：backend/lianyu-web/src/test/java/com/lianyu/web/agent/AgentStreamBridgeTest.java:25 · 证据：`AgentStreamBridgeTest` 只覆盖正常 finish 与错误帧（:178、:191 两处 `newRelay` 用例）；在 `backend/**/src/test` 检索 `AgentStreamBridge|AgentStreamAdapter|isClientStreamDisconnect|abort()` 仅命中 11 行，无 `abort` 用例；前端未覆盖 `ChatPage.handleSend:1437-1446` 的「网络错误后重放」分支；backend-agent 侧 1157 个用例也没有“取消不应计入熔断”的断言。
  影响：本轮 critical/high 缺陷（半截回复落库、重复发送、取消误记熔断）全部落在无测试路径上，修复后没有护栏，改动异常类型即可能复发。 · 修复：补三条最小回归用例（断流不落库、重试幂等、取消不记账），并写进 AGENTS.md「非空转验证」清单。

### 已查未发现问题

- u04 scripts：均为显式 `timeout` 的发布/诊断脚本，失败直接抛出；与运行时链路弱相关。
- u05 backend/lianyu-dao：迁移用 `IF NOT EXISTS` / `information_schema` 守卫，可重入；实体与 DTO 无逻辑。
- u09 frontend/electron：`updater.js` 有 45s 空闲超时、sha512 强校验、Range 探测失败回退单流下载、错误态上报；NapCat 下载/解压有 sha256 校验与候选源重试；未发现新的可靠性缺陷。
- u12 杂项：安装器有 8/10 分钟看门狗与 `finally` 清理；Jackson TypeHandler 解析失败直接抛 `RuntimeException`；未发现吞错。
- u03 前端 API 层：`httpCore.js` 60s 超时、401 单飞刷新 + 重放、`_authReplayed` 防连坐，均有测试，未发现问题。

## 未验证 / 抽样说明

- u02、u04、u05、u06、u09、u10、u11、u12 为 sampled：每个单元读取了 34 个文件（含全部 core），但没有逐行走完全部 100+ 文件；其中 u06 只对 `ChatPage` 发送链路做了逐行阅读，其余页面为风险行扫描。
- 运行时行为证据来自代码路径推演与本地复现：熔断误记用 `backend-agent/.venv` 跑过脚本（5 次 `aclose()` → OPEN）；前端门禁 flaky 是两次真实运行的对比结果。SSE 断流落库与重试重复发送属确定性代码路径，未起完整服务端做端到端复现。
- `npm --prefix admin test` 未执行成功：`admin/node_modules` 不存在，按只读约束未安装依赖；该模块测试覆盖结论仅基于文件内容与 CI 配置。
- `pytest -q backend-agent` 使用仓库内 venv 解释器；系统 python3.11 缺 `respx`，直接跑会有 48 个收集错误——这本身也是门禁文档缺口。

## 建议修复顺序

1. **R1（断流落库）**：先把“失败报成成功”堵住，改动集中在 `AgentStreamBridge` + `ConversationService`，风险可控。
2. **R3（重复发送）**：与 R1 同链路，先加幂等键再谈重试策略，避免修复期间继续产生重复数据。
3. **R2（取消误记熔断）**：一处 `except` 收窄 + 两条用例，成本最低、收益最直接（误伤全量用户）。
4. **R5（不取消上游）**：与 R1/R3 同文件族，一起改可减少回归面。
5. **R4、R6**：超时与消息丢弃类，独立改动，可并行。
6. **R7**：可观测性补齐，建议随下一次发布一起上，便于验证前面几项修复的效果。
7. **R8、R9、R10**：门禁与回归测试，最后收口，保证上述修复不被回退。

## 跨专家转交

- 安全：`scripts/_check_yml.py` 等运维脚本硬编码生产 IP 与 root 账号；`SsrfPinningClientFactory` 的 DNS 重绑定/IP 固定策略（本次只报超时缺失）。
- 正确性：`AssistantReplyService` 的分片/替换逻辑与 `pieces` 契约未纳入本次审查。
- 可维护性：`AiBackgroundConsumer` 的中文文案驱动重试、`ProactiveChatScheduler` 多处 `null` 静默返回，建议一并整理。
