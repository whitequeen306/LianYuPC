# 健壮性审查报告

本报告只覆盖健壮性（错误处理 / 韧性 / 可观测性 / 测试）一个维度，范围为**整个项目**（1108 个文件 / 139994 行 / 12 个审查单元），不是 diff，也不是最近一次提交。全部结论来自源码阅读与实跑：源码只读，未做任何修改。

## 范围与覆盖

| 单元 | 范围 | 状态 | 实读单元内文件 | 理由 |
| --- | --- | --- | --- | --- |
| u01 | backend/lianyu-service (1/3) | reviewed | 41 | SSE 收尾、回合编排、主动消息调度、群聊并发、记忆写入与后台消费者逐个核对，3 个 `@Scheduled` 与 `@RabbitListener` 的失败路径全部读到 |
| u02 | backend/lianyu-service (2/3) | sampled | 38 | 12 个 DTO core 全读（纯数据类，无可靠性面），通知/关系/广场服务的失败分支按 rg 扫描后深读 WebPushService、NotificationPushConsumer |
| u03 | frontend/src (1/2) | reviewed | 34 | 前端 API 层全读：httpCore 的 60s 超时与 401 单飞、错误文案映射、SSE 解析/drain、录音与回放 composable 的 catch 分支 |
| u04 | scripts (1/2) | sampled | 31 | 12 个 core 脚本读头与关键函数；确认 `_cloud_deploy_pull.py` 已有 timeout + 非 PTY（AGENTS.md 提到的 compose 挂死已修），上传脚本遵循 manifest-last |
| u05 | backend/lianyu-dao | sampled | 30 | 20 个 Flyway 迁移 + 5 个 mapper 接口；纯 DDL 与 MyBatis-Plus 桩，幂等写法（`IF NOT EXISTS` / `NOT EXISTS` / `DELETE`），无运行时代码 |
| u06 | frontend/src (2/2) | sampled | 35 | ChatPage 流式收发与 4 个 store 深读，其余页面按 rg 扫 catch / `Promise.allSettled` |
| u07 | backend-agent | reviewed | 36 | 网关/韧性/流式/入口/配置/写出/记忆 relay 全读，并实跑了熔断器复现脚本；49 个测试文件的覆盖点逐个对照 |
| u08 | backend/lianyu-web | reviewed | 36 | AgentRuntimeClient、ChatTurnAdapter、AgentStreamBridge、AgentImageAdapter、RabbitMqConfig 全读，Controller 层扫异常传播 |
| u09 | frontend/electron | sampled | 31 | updater / qqBridge / napcat / wechat 主文件与失败分支；Electron 侧整体健壮（重试、状态机、`uncaughtException` 兜底） |
| u10 | 其他模块（root/.github/admin 等） | sampled | 30 | admin 控制台 13 个源文件全部读完，另读 `.env.example`、Dockerfile、pom、网关脚本等部署件 |
| u11 | backend 小模块合集 | sampled | 30 | 12 个 core 测试类全读，另抽 lianyu-admin；多为薄壳，`MemoryWriterDebounceTest` 有真实 5.2s sleep |
| u12 | 其他模块（lianyu-app/common、installer、Pets、scripts） | sampled | 31 | common 工具类、安装器 C# 工程、Pets/scripts 工具逐个读头与关键分支 |

- 12 个单元全部覆盖，`scope.json` 每单元的 `core` 文件全部出现在实读清单里（每单元实读 30–41 个，`minFilesPerUnit = 30`）。
- 四个 section 都做了检查：error-handling 2 条、resilience 3 条、observability 3 条、tests 3 条，合计 11 条发现（0 条表示“已检查且干净”，本报告没有用 0 冒充未查的 section）。
- 测试命令实跑结果：Java `mvn -q -f backend/pom.xml test` 通过（BUILD SUCCESS）；前端 `npm --prefix frontend test` 通过（75 文件 / 459 用例）；Python `pytest -q backend-agent` 失败（48 个 collection error）；`npm --prefix admin test` 失败（vitest 未安装）。详见文末“未验证 / 抽样说明”。

## 发现（按严重度）

- [high] [u07] [direct] REL-01 — 用户中途关窗会被记成模型服务故障，攒 5 次就让所有用户看到“模型繁忙”
  详细问题：客户端中断被记成上游失败，5 次用户放弃就能打开熔断器
  位置：backend-agent/app/gateway.py:279 · 证据：`try:`（L259）包住 `async for delta in self._stream_once(...): yield delta`（L264-268），消费方关闭连接产生的 `GeneratorExit`/任务取消会落到 `except BaseException as exc: await breaker.on_error(exc); raise`（L279-281）；`resilience.py` 的 `on_error` 对非 `GatewayBusinessError` 一律 `_results.append(False)` + `_maybe_open()`；窗口 20 样本 / 最少 5 次 / 阈值 50% / 等 30s。已用脚本复现 5 次中断：`attempt 4 permit True -> state CircuitState.OPEN`，日志 `breaker provider=platform|base=https://api.example.com OPEN (failure_rate=100%, samples=5)`，随后 `try_acquire()` 返回 False。
  影响：5 次关窗（或前端 abort、网关超时、进程取消）就能把该 provider 的熔断器打开 30 秒，期间所有用户统一收到“对方模型暂时繁忙”，日志还写成上游故障、误导排查 · 修复：拆分 `except BaseException`，取消类异常直接透传不记熔断；只对真实上游错误 `on_error`；补“N 次中断后仍是 CLOSED”的用例。

- [high] [u01] [direct] REL-02 — 回复收尾时冒出非网络异常，流不结束、回复也不落库，界面一直转圈
  详细问题：finishSseSuccess 只接 IOException：收尾异常会让流不结束、回复不落库
  位置：backend/lianyu-service/src/main/java/com/lianyu/service/ai/SseChatStreamHelper.java:51 · 证据：L51-65 的 `try { callback.beforeStreamComplete(...); emitter.send(...data("[DONE]")); emitter.complete(); } catch (IOException e) {...}` 只接 IOException，`callback.onComplete(fullContent, null)` 写在 try 之外（L62-64）；调用点是 reactor 完成回调 `subscribe(..., relay::finish)`（ChatTurnAdapter.java:72-79），回调抛 RuntimeException 会被 Reactor 当 dropped error，emitter 不 complete、`onComplete` 不执行；`SSE_TIMEOUT_MS = 1_800_000L`（ChatTurnAdapter.java:37），而落库/记忆/通知只在 ConversationService.onComplete（L377-407）；`emitter.send` 在已完成状态下抛的是 IllegalStateException 而非 IOException；`AgentStreamBridgeTest` 的 RecordingCallback 永不抛异常，故无覆盖。
  影响：截断的回复最长挂 30 分钟不收尾，刷新后这条回复消失（未落库、未触发记忆与通知），服务端只剩一条 Reactor onErrorDropped · 修复：收尾/[DONE]/落库三段各自 try，finally 里强制 `emitter.complete()`；`onComplete` 外层 catch；补“beforeStreamComplete 抛 RuntimeException”用例。

- [medium] [u01] [plan] REL-03 — 后台生成只重试约 4.5 秒，比熔断恢复的 30 秒还短，一抖就整批进死信
  详细问题：后台任务重试预算（3 次 / 约 4.5s）短于熔断窗口（30s），熔断一开就全进死信
  位置：backend/lianyu-service/src/main/java/com/lianyu/service/ai/background/AiBackgroundConsumer.java:71 · 证据：`MAX_ATTEMPTS = 3`（L32）、`retryDelayMs = 1500L`（L43）、`delay = Math.max(1L, retryDelayMs) * attempt`（L71）→ 共 1.5s+3s；只有 `isAgentTransient`（L102-116，按三条中文文案匹配）才重试；上游 `wait_duration_seconds=30.0`（resilience.py L67-76、application.yml:523）；熔断期间 Java 侧统一映射成 AI_PROVIDER_ERROR“消息发送失败”，三次重试全部落在同一窗口，最后 `throw last` → `defaultRequeueRejected(false)` → DLQ。
  影响：上游抖动 30 秒就永久丢掉这段时间排队的朋友圈评论 / 角色日记 / 主动问候，用户侧表现为“角色忽然不主动说话了”，无补偿、无告警 · 修复：拉长重试窗口或对“繁忙”类错误 requeue、按错误码而非中文文案判定、给 DLQ 加重放入口与监控。

- [medium] [u08] [direct] REL-04 — 推送队列没配死信，消费者也没兜底，推错一次这条通知就彻底丢了
  详细问题：event.broadcast.queue 没有死信配置，消费者无 try/catch：抛错即丢通知
  位置：backend/lianyu-web/src/main/java/com/lianyu/web/config/RabbitMqConfig.java:84 · 证据：`eventBroadcastQueue()`（L84-87）为 `QueueBuilder.durable(QUEUE_EVENT_BROADCAST).build()`，没有 `x-dead-letter-*`，而同文件 `memorySummaryQueue()`（L54-62）与 `aiBackgroundQueue()`（L69-77）都设了 DLX；`rabbitListenerContainerFactory`（L119-132）`setDefaultRequeueRejected(false)` 且注释“交给 DLQ”，但推送消费者用的是默认工厂；`NotificationPushConsumer.onPushTask`（L20-25）直接调用 `webPushService.sendToUser(...)`，无 try/catch，`task == null || task.userId() == null` 时静默 return（消息被 ack）。
  影响：推送消费抛异常（订阅查询/DB/编码）时该通知既不在队列也无死信可查，用户永远收不到、运维无法重放；空 payload 连日志都没有 · 修复：补 DLX/RK 与死信队列声明、消费者外层 try/catch 后重抛、空 task 记 warn。

- [medium] [u07] [direct] REL-07 — 聊天流崩掉时服务端只打印一行原因、没有调用栈，出了代码级毛病定位不到
  详细问题：流式端点顶层异常只打印 repr，没有 traceback
  位置：backend-agent/app/main.py:253 · 证据：L252-256 `except Exception as exc:  # noqa: BLE001` → `logger.warning("chat-stream failed: user=%s frames=%d err=%r", ...)`，无 `exc_info=True`；`/agent/chat-turn/stream` L520-522 同写法。两处是主聊天链路唯一兜底（`stream_chat_events` 只处理 GatewayRejected/GatewayError，其余异常一路冒到这里）。
  影响：`AttributeError`/`KeyError`/`TypeError` 这类最该被兜底捕获的 bug 只留一行 repr，没有文件行号；这正是兜底 catch 存在的理由 · 修复：加 `exc_info=True`、未知异常升 ERROR 并计数、补 caplog 断言 traceback 的用例。

- [medium] [u07] [direct] REL-09 — 按文档跑 Python 测试直接报错跑不起来，只有先手工装虚拟环境才行
  详细问题：`pytest -q backend-agent` 在干净环境 100% 失败：缺 pythonpath，只有 .venv 能跑
  位置：backend-agent/pyproject.toml:30 · 证据：Gap=AGENTS.md §4 要求 `cd backend-agent && pytest` 全绿。Current=仓库根跑出 48 个 collection error（全部 `ModuleNotFoundError: No module named 'app'`），`cd backend-agent` 下同样失败；`pyproject.toml` L30-32 只有 `asyncio_mode` 与 `testpaths`，没有 `pythonpath`，目录下也没有 conftest.py；只有 `.venv/Scripts/python.exe -m pytest -q` 能跑（1157 passed），因为 `.venv/Lib/site-packages/_editable_impl_lianyu_agent.pth` 指向 backend-agent（曾做过 `pip install -e .`）。Proposed=让导入路径不依赖机器残留。Why=否则任何人都无法从干净检出复现测试结果。
  影响：换机器/CI 跑门禁 100% 失败或被当成“环境问题”跳过，Python 侧 1157 个用例长期不在门禁里跑 · 修复：`pythonpath = ["."]`（或提交 requirements 并写清 `pip install -e .`）、固定 ≥3.12、加 `import app.main` 冒烟、文档同步。

- [low] [u01] [direct] REL-05 — 群聊里某个角色生成失败，用户只看到它不说话
  详细问题：群聊单角色生成失败被吞：静默缺席 + 日志无堆栈
  位置：backend/lianyu-service/src/main/java/com/lianyu/service/conversation/GroupChatService.java:414 · 证据：L412-416 `try { reply = f.join(); } catch (Exception ignored) { // handled in generateCharacterReply }`；被调 `generateCharacterReply` L525-527 `catch (Exception e) { log.error("Group chat error for character {}: {}", member.getCharacterId(), e.getMessage()); return null; }`，随后 L417-419 `continue`。
  影响：该角色这一轮静默缺席、前端无任何提示，用户只觉得“不理人”；日志只有 message、定位不到根因 · 修复：catch 保留堆栈、下发 `GROUP_MEMBER_FAILED` 结构化事件供前端提示、补异常用例。

- [low] [u01] [direct] REL-06 — 主动问候失败只写 DEBUG，整条链路停摆也没有一条 WARN
  详细问题：主动消息失败只记 DEBUG：整条链路停摆时无 WARN、无指标
  位置：backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ProactiveChatScheduler.java:174 · 证据：发送循环 L148-176 `catch (Exception e) { log.debug("Proactive chat skipped for convId={}, reason={}", ...) }`，L215-217（等待语音）与 L265（定时语音）同族；`tick()` 无外层 try/catch，批次查询（L68-95）不在 per-candidate 隔离内。
  影响：主动问候整体失效时按 INFO 收敛或按 WARN/ERROR 告警都看不到，也没有计数器，只能靠用户反馈 · 修复：单次失败升 warn、按分钟聚合、暴露成功/跳过/失败计数、tick 外层兜底。

- [low] [u01] [direct] REL-08 — 三个定时任务失败只留一行文字原因，没有堆栈也没有连续失败告警
  详细问题：三个定时任务失败只记 e.getMessage()：无堆栈、无连续失败告警
  位置：backend/lianyu-service/src/main/java/com/lianyu/service/character/EmotionDecayScheduler.java:34 · 证据：L33-35 `catch (Exception e) { log.warn("Emotion decay tick failed: {}", e.getMessage()); }`；`RelationshipDecayScheduler` L33-35 与 `MomentsCommentReconciliationScheduler` L59-61 完全同构，均无堆栈、无计数、无指标。
  影响：连续失败（连接池耗尽、SQL 报错、迁移缺列）时日志里只有同类文本，判断不出根因，也没有“连续 N 次”的告警触发点 · 修复：`log.warn(..., e)`、连续 3 次升 error、加 tick 成功/失败指标。

- [low] [u10] [plan] REL-10 — 管理后台只有一个菜单测试，还允许“一个测试都没有也算通过”
  详细问题：admin 控制台仅 1 个菜单测试，且 `--passWithNoTests` 让空套件也变绿
  位置：admin/package.json:9 · 证据：`"test": "vitest --passWithNoTests"`；`admin/src/__tests__/menu.test.js` 只有 8 行、仅断言菜单 id；`admin/src/api.js`（token 存 localStorage、登录/登出）与 `admin/src/stores/session.js` 无任何用例；实跑 `npm --prefix admin test` 直接报 `'vitest' 不是内部或外部命令`（依赖未安装）。
  影响：登录态过期、权限 401/403、列表加载失败等改动静默通过门禁 · 修复：去掉 `--passWithNoTests`、补 session/api 错误分支用例、CI 安装依赖后执行。

- [low] [u11] [direct] REL-11 — 记忆防抖用例写死真实等待 5.2 秒，跑得慢还容易偶发失败
  详细问题：MemoryWriterDebounceTest 硬等 5.2 秒验证补发，慢且时序脆弱
  位置：backend/lianyu-service/src/test/java/com/lianyu/service/memory/MemoryWriterDebounceTest.java:72 · 证据：`ReflectionTestUtils.invokeMethod(writer, "maybeReschedule", task)` 之后 `Thread.sleep(5200);` 再断言补发一次；被验证常量是 `RESCHEDULE_DELAY_SECONDS = 5`。
  影响：单条用例固定 5.2s，是全套件最慢的一条；机器繁忙时 5.2s 可能不够 → 偶发红，之后真实回归也会被当成已知 flake · 修复：延迟可注入 + 毫秒级延迟 + Awaitility 轮询、改用真实入口 enqueueSummary。

## 已查未发现问题

- **u09 frontend/electron**：updater 有 `UPDATE_DOWNLOAD_RETRIES` 重试与状态机、失败清理安装包；napcatHost 有重启次数上限、多源下载回退、桥接失败保活；qqBridge 中继失败会回落 `fallbackText`；`logger.js` 装了 `uncaughtException`/`unhandledRejection`。31 处空 catch 逐处核对后属可降级分支（单条音频、代理探测、状态同步），上层有日志与状态机兜底。
- **u03/u06 渲染端流式消费**：`shared/sseParser.js` 把错误帧转成异常、`assistantStreamDrain.js` 在 abort 时 cancel reader、`ChatPage.handleSend` 仅对网络错误自动重试一次并恢复草稿、错误经 `humanizeError` 呈现给用户 —— 失败可见、有退路。
- **u05 dao**：迁移幂等（`IF NOT EXISTS` / `NOT EXISTS` / `DELETE` / information_schema 守卫），worker 无运行时代码，可靠性面为空。
- **u04 scripts**：CLI 工具均 `sys.exit(1)`/`raise SystemExit` 失败即停，凭据只从 env/.env 读；`_cloud_deploy_pull.py` 已按 AGENTS.md 的教训加了 `timeout=900/1800/600` 与 `get_pty=False`。
- **u08 AgentRuntimeClient**：全部出站调用走 `SsrfPinningClientFactory` 且显式超时，`postBytes` 有 120s 上限，失败有 `AgentFailureLogThrottle`（10 分钟 512 条）节流。
- **u12 common/installer**：`OutboundUrlValidator` 有 DNS 重试与白名单，安装器对每一步失败都有错误输出与退出码。

## 未验证 / 抽样说明

- 只有 u01/u03/u07/u08 是 `reviewed`（关键路径文件逐个读）；u02/u04/u05/u06/u09/u10/u11/u12 是 `sampled`：读了核心文件 + 按模式扫描后抽查，**不能当作全量已读**。
- 未做压测与故障注入：并发/熔断相关结论来自代码路径与已执行的熔断复现脚本（5 次 GeneratorExit → OPEN），不是生产观测。
- `pytest -q backend-agent` 与 `npm --prefix admin test` 未通过（见 REL-09/REL-10），因此“Java 与前端绿”之外的两条门禁结论是“当前不可用”，不是“代码有问题”。
- 前一轮 correctness 已报告 `memory_outbox` 认领后卡在 RUNNING（relay.py:41，C-006），本报告不重复计数，只在其监控缺失处给了建议。

## 建议修复顺序

1. **REL-01、REL-02**（high）：两处都会把“局部小故障”放大成“全员不可用 / 回复丢失”，且改动都在单文件内、有明确测试落点。
2. **REL-09、REL-10**（medium，tests）：门禁失效会让上面两条修复无法被后续回归保护，应与修复同批落地。
3. **REL-03、REL-04**（medium，resilience）：涉及重试预算与 MQ 拓扑，需要人判断取舍，建议下一个迭代处理。
4. **REL-07、REL-05、REL-06、REL-08、REL-11**（低）：日志与测试质量，可随相关模块改动顺带完成。

## 跨专家转交

- **maintainability**：`AiResilience` / `UpstreamCircuitBreakerFactory` 与 `application.yml` 的 `resilience4j`（bulkhead `ai-chat` 80、time-limiter 30s、circuit-breaker）没有任何注入点（全仓仅被自身与一个测试引用），属于死代码 + 失效配置；Python 侧已有等价熔断，因此本轮不作为可靠性损失上报。
- **security**：`/agent/memory/reconcile`、`/agent/memory/relay/requeue` 两个运维接口只做内部签名校验，建议确认暴露面与鉴权。
- **correctness**：`gateway.py` 在 `[DONE]` 处 return 会丢弃聚合好的 `tool_calls`（已由 correctness 报告），修 REL-01 时请勿改动该分支语义。
