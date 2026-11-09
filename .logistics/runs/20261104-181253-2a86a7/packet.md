# 后勤主编材料 · 20261104-181253-2a86a7

> 这是给主会话模型（主编）的候选材料，不是最终报告。
> 去重、定级、取舍、模块组织由主编决定；机器只负责收集与校验。

## 运行
- 根目录：`C:/Users/hp/Desktop/LianYu-PC`
- 范围：1100 个文件 / 12 个审查单元 / 135978 行
- 测试命令：（未检测到）

## 覆盖
- 预期单元 / 专家：reliability
- 已收到：reliability
- 缺失：（无）
- 无效：（无）
- 失败：（无）
- partial：（无）
- 可选未选：perf

## 单元覆盖矩阵

| 单元 | 标签 | reliability | perf |
| --- | --- | --- | --- |
| u01 | backend/lianyu-service (1/3) | reviewed | missing |
| u02 | backend/lianyu-service (2/3) | sampled | missing |
| u03 | frontend/src (1/2) | reviewed | missing |
| u04 | scripts (1/2) | reviewed | missing |
| u05 | backend/lianyu-dao | sampled | missing |
| u06 | backend-agent | reviewed | missing |
| u07 | frontend/src (2/2) | reviewed | missing |
| u08 | backend/lianyu-web | reviewed | missing |
| u09 | frontend/electron | reviewed | missing |
| u10 | 其他模块（(root)、.github、admin 等） | sampled | missing |
| u11 | backend（小模块合集 59 文件） | sampled | missing |
| u12 | 其他模块（backend/lianyu-app、backend/lianyu-common、backend 等） | sampled | missing |

## 专家摘要
### reliability · ok
- 摘要：审查 12 个单元（u01/u03/u04/u06/u07/u08/u09 逐文件读，u02/u05/u10/u11/u12 按 scope 清单做全量模式扫描后抽查）。最严重的是 backend-agent 熔断记账：gateway.py:279 把用户中途离开产生的 GeneratorExit/CancelledError 记成上游失败，而前端 abortOnUnmount 默认 true，5 次请求里 3 次中断即可让 breaker 打开 30 秒、全体用户快失败（F1，high）。其次是失败可见性：Web Push 全吞异常使 MQ 永远 ack、两个死信队列（memory.summary.dlq / ai.background.dlq）声明了却没有消费者（F3/F4）；/agent/health 恒 UP 却被 compose 健康检查、发版自检、Java 降级判据三处当真（F5）；工具异常隔离窄于 docstring 承诺、记忆批量写入的事务承诺与逐条提交不符（F2/F6）。测试面 Java 与 Electron 侧表现良好（断开/重试/分片/sha512 均有用例），缺口集中在 backend-agent：取消路径与 execute_tool_calls 隔离均为 0 覆盖（F8/F9）。未验证：scope.json 的 testCommand 为 None，本次未执行任何测试，结论均来自静态阅读与既有测试文件清点。
- sections：error-handling=7 · tests=2
- notes：只读审查，未修改任何源码；未执行测试（testCommand=None）。u02/u05/u10/u11/u12 为 sampled：按 scope.json 文件清单做全量模式扫描（空 catch / except-pass / @Transactional / 阻塞等待 / DLQ 引用）后抽查关键文件。 主编规范化：原始 sections 为 {count,status,notes} 对象，已按 count 归一为非负整数，明细保留在 sectionsDetail。
- 发现数：9
- 转交其他专家：2 条
  - → 未指定 · 流式失败后前端是否自动重试属产品策略，未纳入 error-handling 计数
  - → 未指定 · lianyu-dao 仅有 mapper/DAL，无错误处理面，未逐文件深读

### perf · not-selected
- 可选专家，本次未选择（不是缺失）。

## 候选 findings
### REL-32349 · [高] 客户端中途离开抛出的取消被记成上游失败，可把模型熔断器打穿
- 专家 / section：reliability / error-handling
- 位置：`backend-agent/app/gateway.py:279`
- 置信度：high · kind：plan
- 证据：stream_text 用 `except BaseException as exc: await breaker.on_error(exc); raise` 包住整条流（gateway.py:279-281；`_post_chat` 在 gateway.py:407 同构）。resilience.py:131-153 的 on_error 只把 `GatewayBusinessError` 记成成功，其余在 CLOSED 态直接 `self._results.append(False)`；_maybe_open 的门槛是「样本 >=5 且失败率 >=50%」（resilience.py:158-170），即 5 次请求里 3 次中断就 OPEN 30 秒。而 GeneratorExit（`async for` 消费者提前关闭生成器）与 asyncio.CancelledError 都是 BaseException 子类，正好落在这条记账路径上。触发路径在正常使用中就存在：frontend/src/composables/useStreamAbort.js 的 `abortOnUnmount` 默认 true，用户中途切页/关聊天窗 → 前端 abort SSE → Java AgentStreamBridge 感知断开并关闭到 agent 的连接 → Python 生成器被关闭。
- 影响：用户正常的中途离开被累计成「上游故障」：任意 5 次对话里 3 次中断即可让 breaker 打开 30 秒，期间所有用户的流式请求直接拿 GatewayRejectedError（前端表现为「模型繁忙/生成失败」），而真实上游完全健康；半开态下再来一次取消会立刻重新 OPEN（resilience.py:145-148），无法自愈。
- 修复摘要：把取消/提前关闭从熔断记账里摘出去，与业务错同等对待（中性、不计数）
- 修复文件：backend-agent/app/gateway.py、backend-agent/app/resilience.py、backend-agent/tests/test_resilience.py
- 建议测试：backend-agent/tests/test_resilience.py::test_cancelled_stream_does_not_count_as_upstream_failure；backend-agent/tests/test_resilience.py::test_generator_close_is_neutral_for_breaker

### REL-58FC8 · [中] 工具执行只兜底 httpx.HTTPError/RuntimeError，其余异常会打断整轮回复
- 专家 / section：reliability / error-handling
- 位置：`backend-agent/app/tool_loop.py:101`
- 置信度：high · kind：direct
- 证据：execute_tool_calls 的 docstring（tool_loop.py:80）承诺「某个工具失败只影响它自己那条结果」，实现却是 `except (httpx.HTTPError, RuntimeError) as exc:`（tool_loop.py:99-103）。工具里其它异常直接穿透：backend-agent/app/tools/weather_tool.py:59 `data = response.json()` 在 wttr.in 返回 HTML 限流页时抛 json.JSONDecodeError（ValueError 子类），KeyError/TypeError 同样穿透。上层 stream_chat.py 只捕获 GatewayRejectedError/GatewayError，最终落到 main.py:252 的 `except Exception` → 整轮变成 sse_error("生成失败，请重试")。
- 影响：一次第三方天气接口返回非 JSON（限流页/维护页）就让整轮对话失败，用户已经看到的流式内容被丢弃重来；按设计本应只是那一条工具结果显示「工具调用失败」，其余内容照常返回。
- 修复摘要：把工具级兜底从两类异常放宽到 Exception（asyncio.CancelledError 在 3.8+ 属 BaseException，不会被吞掉），并保留堆栈日志
- 修复文件：backend-agent/app/tool_loop.py
- 建议测试：backend-agent/tests/test_tools.py::test_execute_tool_calls_isolates_json_error；backend-agent/tests/test_tools.py::test_execute_tool_calls_isolates_key_error

### REL-2669A · [中] Web Push 发送异常全部吞成 WARN，消费者恒成功且该队列没有死信兜底
- 专家 / section：reliability / error-handling
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/notification/WebPushService.java:112`
- 置信度：high · kind：plan
- 证据：sendToUser 对每条订阅 `catch (Exception e)`：瞬断（isTransientNetworkError）`log.warn(...); return;`（WebPushService.java:111-120），非瞬断也只 `log.warn`（:123-127）；外层 :81 再兜一层 `log.warn("web push client error: {}")`。因此 NotificationPushConsumer.java:19-25 的 `@RabbitListener` 永远正常返回、永远 ack。配套的 `eventBroadcastQueue()`（RabbitMqConfig.java:85-87）没有 `x-dead-letter-exchange`，工厂 `setDefaultRequeueRejected(false)`（:130）。
- 影响：推送服务瞬断或返回 5xx 时通知对用户永久丢失，现场只留一条 WARN：没有重试、没有死信、没有可观测计数，事后无法判断「用户没收到」是没发还是丢了。
- 修复摘要：把「全部订阅都失败且属瞬断」升级为消费者失败，让 MQ 重试/死信真正生效；并给广播队列补死信出口
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/notification/WebPushService.java、backend/lianyu-service/src/main/java/com/lianyu/service/notification/NotificationPushConsumer.java、backend/lianyu-web/src/main/java/com/lianyu/web/config/RabbitMqConfig.java
- 建议测试：backend/lianyu-service/src/test/java/com/lianyu/service/notification/WebPushServiceTest.java::transientFailureRethrowsForRetry；backend/lianyu-web/src/test/java/com/lianyu/web/config/RabbitMqConfigTest.java::eventBroadcastQueueHasDeadLetter

### REL-784C6 · [中] 两个死信队列声明了却没有任何消费者，记忆摘要失败三次后消息静默滞留
- 专家 / section：reliability / error-handling
- 位置：`backend/lianyu-web/src/main/java/com/lianyu/web/config/RabbitMqConfig.java:23`
- 置信度：high · kind：plan
- 证据：RabbitMqConfig.java:23/26 声明 `memory.summary.dlq` 与 `ai.background.dlq`，:66/:81 建队列、:96/:106 绑定，但全仓 `@RabbitListener` 只有 3 处且都监听主队列（MemoryConsumer.java:15 `memory.summary.queue`、NotificationPushConsumer.java:16 `event.broadcast.queue`、AiBackgroundConsumer.java:45），没有任何监听 dlq 的消费者。失败链路是实的：MemoryWriter.processSummary 捕获异常后 `throw new RuntimeException("memory summary process failed, convId=" + task.conversationId(), e)`（MemoryWriter.java:135），配合 application.yml:71-79 的 retry（max-attempts 3）与 setDefaultRequeueRejected(false)，第 3 次失败即进 DLQ。
- 影响：记忆摘要/主动消息这类后台任务失败后消息永久停在 DLQ：没有消费者、没有指标、没有告警、没有重放入口；用户侧表现为「记忆不再更新 / 主动问候消失」，运维侧只能人肉进管理台翻队列才会发现。
- 修复摘要：给两个 DLQ 补可观测终态（计数 + 落库 + 告警），并提供受控重放入口
- 修复文件：backend/lianyu-web/src/main/java/com/lianyu/web/config/RabbitMqConfig.java、backend/lianyu-service/src/main/java/com/lianyu/service/memory/MemoryWriter.java、backend/lianyu-service/src/main/java/com/lianyu/service/ai/background/AiBackgroundConsumer.java
- 建议测试：backend/lianyu-service/src/test/java/com/lianyu/service/mq/DlqMonitorTest.java::recordsDeadLetterAndIncrementsCounter

### REL-380F5 · [中] /agent/health 恒返回 UP，却被容器健康检查、发版自检和 Java 降级判据三处当作真实可用性
- 专家 / section：reliability / error-handling
- 位置：`backend-agent/app/main.py:102`
- 置信度：high · kind：plan
- 证据：main.py:102-104 原文是 `@app.get("/agent/health")` + `async def health() -> dict[str, str]: return {"status": "UP"}`，不探测 MySQL/Redis/Milvus 或模型凭据。三处消费它：docker-compose.yml 的 agent/agent2 healthcheck（:135-137、:180-182）打这个端点；scripts/_cloud_deploy_pull.py:130-143 把 `agent=200` 当作部署成功判据（12 次 ×15s，否则 `raise SystemExit(56)`）；backend/lianyu-web/.../agent/AgentRuntimeClient.java:406-414 的 isHealthy() 也打它，被 AgentBlockingAdapter / AgentPromptAssemblyAdapter / AgentSessionSummaryAdapter / AgentStreamAdapter 用作降级判据。
- 影响：依赖挂掉（DB 只读、Milvus 不可达、embedding 凭据失效）时 agent 依旧 healthy：compose 不重建、发版自检照样打印 DEPLOY_DONE、Java 认为 agent 可用不回退，故障最终只体现为用户侧「生成失败，请重试」，且没有任何线索指向 agent 依赖。
- 修复摘要：拆开 liveness 与 readiness：/agent/health 只证进程活着，新增 /agent/ready 真探依赖，并让发版自检与 Java 判据改用它
- 修复文件：backend-agent/app/main.py、docker-compose.yml、scripts/_cloud_deploy_pull.py、backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentRuntimeClient.java
- 建议测试：backend-agent/tests/test_api_contract.py::ready_reports_degraded_when_dependency_down；backend-agent/tests/test_api_contract.py::health_stays_up_regardless

### REL-6DAB0 · [中] 记忆批量写入的 docstring 承诺整批事务，实现是逐条事务，中途失败会留下半批已提交数据
- 专家 / section：reliability / error-handling
- 位置：`backend-agent/app/memory/writer.py:123`
- 置信度：high · kind：plan
- 证据：writer.py:123 docstring 写「批量写入；整批在同一事务内，任一失败全回滚」，但 write_memories 是 `for memory in memories:` 逐条处理（:128-170），每条各自 `async with transaction() as cur:`（_insert_new :175、_update_meta :196、_replace_and_enqueue :209）。第 N 条失败时，前面 N-1 条已经提交。
- 影响：调用方按「整批失败」处理（可能整批重试或按失败丢弃），数据库里却留下半批记忆：既不全有也不全无，且没有 partial 标记，事后无法判断这次抽取到底成没成。
- 修复摘要：二选一：把整批放进一个事务（评估锁持有时间），或在 docstring 与返回值里如实暴露「部分写入」
- 修复文件：backend-agent/app/memory/writer.py、backend-agent/tests/test_memory_writer.py
- 建议测试：backend-agent/tests/test_memory_writer.py::batch_write_rolls_back_all_on_failure；backend-agent/tests/test_memory_writer.py::partial_failure_is_reported_in_outcome

### REL-2D7B1 · [低] shared_client 在事件循环变化时直接丢弃旧 client，不关闭连接池
- 专家 / section：reliability / error-handling
- 位置：`backend-agent/app/http_client.py:74`
- 置信度：medium · kind：plan
- 证据：http_client.py:67-75：命中缓存要求 `cached_loop is loop and not client.is_closed`，否则 `client = new_client(limits=_SHARED_LIMITS); _SHARED[name] = (loop, client)`，旧 client 既不 aclose 也不再被任何地方引用。
- 影响：同一进程里重建事件循环（多 loop 的测试、将来若加 worker 复用、热重载）时，每个 loop 泄漏一个连接池与套接字，长期运行累积 fd；当前单 loop 生产形态影响有限，故记 low。
- 修复摘要：换 loop 时显式关闭旧 client（在旧 loop 上调度 aclose），或把缓存改成按 loop 分组 + 弱引用清理
- 修复文件：backend-agent/app/http_client.py、backend-agent/tests/test_http_client.py
- 建议测试：backend-agent/tests/test_http_client.py::shared_client_closes_previous_loop_client

### REL-1BDFC · [中] 熔断记账没有「取消不算失败」的回归用例，该缺陷因此无人发现
- 专家 / section：reliability / tests
- 位置：`backend-agent/tests/test_resilience.py:1`
- 置信度：high · kind：plan
- 证据：Gap: stream_text 在 `except BaseException` 里对取消/生成器关闭的记账行为没有任何用例锁定。
Current: tests/test_resilience.py 的 17 个用例全部用显式异常驱动（httpx.ConnectError/ReadTimeout/GatewayBusinessError）；在 backend-agent/tests 内检索 `CancelledError|GeneratorExit` 为 0 命中；tests/test_gateway.py 的 8 个用例只覆盖 HTTP 错误与 4xx/5xx 映射。
Proposed: 在 test_resilience.py 增加 (a) `test_cancelled_stream_does_not_count_as_upstream_failure`——构造流式生成器后 `aclose()`，断言 breaker 仍 CLOSED 且失败样本数为 0；(b) 对包装流式调用的任务 `task.cancel()` 断言同样中性；并补反例 `test_read_timeout_still_counts`（ReadTimeout 5 次后 breaker 打开）。
Why: 取消是产品里最常见的中断（前端 abortOnUnmount 默认 true），没有用例等于没有护栏；而修复时若把 on_error 一并去掉，熔断会彻底失效也无测试报警，所以必须成对锁定正反两面。
- 影响：gateway.py:279 的取消记账缺陷没有任何回归网；将来若把 except 收窄过度（例如直接删掉 on_error），熔断彻底失效同样无人察觉。
- 修复摘要：补「取消中性 + 真实错误仍计数」的成对用例，并把取消路径纳入 gateway 测试矩阵
- 修复文件：backend-agent/tests/test_resilience.py、backend-agent/tests/test_gateway.py
- 建议测试：backend-agent/tests/test_resilience.py::test_cancelled_stream_does_not_count_as_upstream_failure；backend-agent/tests/test_resilience.py::test_read_timeout_still_counts

### REL-44D37 · [中] execute_tool_calls 的单工具失败隔离没有任何测试，docstring 的承诺无人验证
- 专家 / section：reliability / tests
- 位置：`backend-agent/tests/test_tools.py:1`
- 置信度：high · kind：plan
- 证据：Gap: tool_loop.execute_tool_calls 承诺「某个工具失败只影响它自己那条结果」（tool_loop.py:80），这条隔离语义没有用例。
Current: 在 backend-agent 内检索 `execute_tool_calls` 只命中 app/（stream_chat.py:78、tool_loop.py:72/194），tests/ 为 0 引用；tests/test_tools.py 的 14 个用例只覆盖 handler 直调（handle_get_weather、read_current_time_fact）、schema 清单与 run_tool_loop 的 3 轮上限，且注入的异常恰好是 httpx.HTTPError / RuntimeError（:101 已捕获的两类），正好绕开窄 except 的破口。
Proposed: 新增 test_execute_tool_calls_isolates_json_error 与 …_isolates_key_error：用抛 json.JSONDecodeError / KeyError 的假 handler 与正常 handler 同批调用，断言正常结果仍在、失败工具返回「工具 X 调用失败：…」而非向上抛；再用 `raise asyncio.CancelledError` 的 handler 断言取消仍透传。
Why: 没有这条用例，把 except 收窄成任何白名单都不会被门禁发现；第三点则防止修复时把取消一起吞掉，制造新的静默失败。
- 影响：工具异常隔离的实际边界与文档不一致且不可验证；任何新增工具只要抛非白名单异常就会打断整轮回复，而 pytest 全绿不会提示。
- 修复摘要：为工具隔离语义补三类用例：非白名单异常、取消透传、正常工具不受影响
- 修复文件：backend-agent/tests/test_tools.py
- 建议测试：backend-agent/tests/test_tools.py::test_execute_tool_calls_isolates_json_error；backend-agent/tests/test_tools.py::test_execute_tool_calls_propagates_cancellation

## 转交 deferred
- reliability → 未指定 · 流式失败后前端是否自动重试属产品策略，未纳入 error-handling 计数
- reliability → 未指定 · lianyu-dao 仅有 mapper/DAL，无错误处理面，未逐文件深读

> 这些不是当前专家的 finding，但必须写入 verdict.deferred，不能静默丢弃。

## 机器提示（仅供参考）
- 没有检测到明显的跨专家重复候选。

## 交给主编
1. 读本文件与原始 handoff；不要只依赖机器提示。
2. 合并重复项，保留最可信的证据；被合并的 ID 记入 verdict.dropped。
3. 写 `verdict.json`（schemaVersion 2、findings、coverage、dropped），再写 `report.md`。
4. 只有完整覆盖默认四模块时，才更新 `docs/logistics/latest.md` 与 `.logistics/latest.json`。
5. 工作区根目录：`C:\Users\hp\Desktop\LianYu-PC`；运行目录：`C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261104-181253-2a86a7`。
