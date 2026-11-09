# 健壮性审查报告 · 20261104-181253-2a86a7

## 一句话结论

全项目 12 个单元里，失败传播的**骨架是好的**（Java 流式回执、Electron 更新器、发版脚本的失败路径都有断言），真正的问题集中在两处：**backend-agent 的熔断记账把用户取消当成上游故障**（任何 5 次对话里 3 次中途退出就能把模型熔断器打开 30 秒，全体用户快失败），以及**Java 侧的失败可见性**——Web Push 全吞异常让 MQ 永远 ack、两个死信队列声明了却没有消费者，记忆摘要与推送失败后消息静默滞留，没有任何告警或重放入口。

- 范围：1100 个文件 / 12 个审查单元 / 135978 行，Git `main` HEAD `c652e14`（fix(relationship): 修正被刷高的历史信任分（V57））
- 专家：reliability（唯一专家），sections `error-handling` 7 条 + `tests` 2 条
- 结果：9 条发现（high 1 / medium 7 / low 1；direct 1 / plan 8），deferred 2 条，dropped 0 条
- 未执行测试：`scope.json` 的 `testCommand` 为 `None`，全部结论来自静态阅读与既有测试文件清点

## 覆盖情况

| 单元 | 模块 | reliability | 说明 |
| --- | --- | --- | --- |
| u01 | backend/lianyu-service (1/3) | reviewed | 读 10 个关键文件：ChatTurnFacade / ConversationService / MemoryWriter / MemoryConsumer / NotificationPushConsumer / WebPushService / RabbitMqConfig / AiBackgroundConsumer |
| u02 | backend/lianyu-service (2/3) | sampled | 120 文件中 13 处 `@Transactional`、61 处 `catch(Exception|Throwable)` 全量扫描后抽查 dto/moments 编排 |
| u03 | frontend/src (1/2) | reviewed | 16 处静默 catch 逐条看上下文，均有全局 toast 或良性降级 |
| u04 | scripts (1/2) | reviewed | `_cloud_deploy_pull.py` 通读：`get_pty=False` + timeout + `recv_exit_status`，build 与 up 分开，health 12 次 |
| u05 | backend/lianyu-dao | sampled | 110 文件 0 处 `@Transactional`、0 处 catch，纯 mapper/DAL |
| u06 | backend-agent | reviewed | 读 18 个文件：gateway / resilience / http_client / stream_chat / tool_loop / main / memory(writer,relay) / tools |
| u07 | frontend/src (2/2) | reviewed | 页面与 store 抽查，静默 catch 前均有 toast 或对账兜底 |
| u08 | backend/lianyu-web | reviewed | AgentStreamBridge / AgentRuntimeClient / RabbitMqConfig / WebPushService 通读 |
| u09 | frontend/electron | reviewed | apiProxy.js / updater.js 全文；updater.test.js 22 个用例覆盖关键失败路径 |
| u10 | (root)/.github/admin/deploy | sampled | 抽查 MilvusConfig / MinioConfig 启动降级、deploy/asr/server.py |
| u11 | backend 小模块合集 | sampled | 模式扫描（空 catch 1 处），抽查 lianyu-admin 鉴权与发版服务 |
| u12 | app/common/Pets | sampled | 抽查 OutboundUrlValidator（DNS 重试有边界）、gen_pet_images.py 重试分支 |

专家覆盖：reliability 1/1 收到，无 missing / invalid / failed / partial；12 个单元全部覆盖，0 skipped（7 reviewed + 5 sampled）。perf 为可选专家，本次未选。

> sampled 不等于「全量已读」：u02/u05/u10/u11/u12 是按 scope 文件清单做全量模式扫描（空 catch / except-pass / `@Transactional` / 阻塞等待 / DLQ 引用）之后抽查关键文件。

## 发现

### backend-agent（u06）

#### REL-32349 · 高 · 客户端中途离开产生的取消被记成上游失败，可把模型熔断器打穿
`backend-agent/app/gateway.py:279`（非流式同构点 `:407`）

`stream_text` 用 `except BaseException as exc: await breaker.on_error(exc); raise` 包住整条流（259-281）。`CircuitBreaker.on_error`（`resilience.py:132-152`）只把 `GatewayBusinessError` 记成成功，其余在 CLOSED 态 `self._results.append(False)`（:151），HALF_OPEN 态任何错误直接 `self._open()`（:146-149）。门槛是 `minimum_number_of_calls=5` / `failure_rate_threshold=50.0`（`resilience.py:72-73`、`156-166`）——5 条样本里 3 次失败即 OPEN。

`GeneratorExit` 与 `asyncio.CancelledError` 都是 `BaseException` 子类，正好落在这条记账路径上；而触发源是产品常态：`frontend/src/composables/useStreamAbort.js:17-34` 的 `abortOnUnmount` 默认 true，组件卸载即 abort，SSE 断开后 Java 关闭到 agent 的连接，Python 生成器被关闭/任务被取消。

**影响**：正常的中途离开被累计成上游故障，5 次对话里 3 次中断就让 breaker OPEN 30 秒，期间所有用户拿 `GatewayRejectedError`（前端显示「模型繁忙/生成失败」），真实上游却健康；半开态下再取消一次立刻重新 OPEN，等于无法自愈。

**修复**：把 `CancelledError`/`GeneratorExit` 从记账里摘出去（`on_error` 增加「中性」分支，HALF_OPEN 下按成功释放 inflight），同时用正反两组用例锁定——取消中性 + ReadTimeout 仍计数。

#### REL-58FC8 · 中 · 工具执行只兜底 httpx.HTTPError/RuntimeError，其余异常会打断整轮回复
`backend-agent/app/tool_loop.py:101`（`kind: direct`）

docstring（77-81）承诺「单个工具失败只影响它自己那条结果」，实现是 `except (httpx.HTTPError, RuntimeError)`。其余异常直接穿透：`weather_tool.py:59` 的 `response.json()` 在 200 但非 JSON 时抛 `JSONDecodeError`；`memory_search_tool.py` / `recent_activity_tool.py` 走 `app/db.py` 的 `fetch_all`，而 `db.py` 对数据库异常不做包装（只有 `ReadOnlyViolation` 一处主动 raise），aiomysql 的 `OperationalError` 原样抛出。上层 `stream_chat.py` 只捕 `GatewayRejectedError/GatewayError`，最终落到 `main.py:252` 的 `except Exception` → 只吐一帧 `sse_error("生成失败，请重试")`。

**影响**：一次工具级故障（天气接口返回非 JSON、记忆检索时数据库瞬断）就让整轮对话失败，用户已看到的流式内容被丢弃重来。

**修复**：`except Exception` + `exc_info=True`（`CancelledError` 属 `BaseException`，不会被吞）。

#### REL-380F5 · 中 · /agent/health 恒返回 UP，却被三处当作真实可用性
`backend-agent/app/main.py:102`

`async def health() -> dict[str, str]: return {"status": "UP"}`，不探 MySQL/Redis/Milvus/凭据。三处消费：`docker-compose.yml:136`、`:181` 的 healthcheck；`scripts/_cloud_deploy_pull.py:130-143` 以 `agent=200` 作部署成功判据（否则 `SystemExit(56)`）；`backend/lianyu-web/.../agent/AgentRuntimeClient.java:406-414` 的 `isHealthy()`，被 `AgentBlockingAdapter.java:131`、`AgentPromptAssemblyAdapter.java:113`、`AgentSessionSummaryAdapter.java:114`、`AgentStreamAdapter.java:111`、`ChatTurnAdapter.java:209` 当降级判据。

**影响**：依赖挂掉时 agent 仍 healthy：compose 不重建、发版自检照样 DEPLOY_DONE、Java 不回退，故障只体现为「生成失败，请重试」，无线索指向依赖。

**修复**：拆 liveness / readiness，新增 `/agent/ready` 真探依赖，发版自检与 Java 判据改用 readiness。

#### REL-6DAB0 · 中 · 记忆批量写入承诺整批事务，实现逐条提交
`backend-agent/app/memory/writer.py:123`

docstring 写「整批在同一事务内，任一失败全回滚」，实现是 `for memory in memories:`（128-166）逐条各自 `async with transaction()`：`_insert_new`（175-190）、`_update_meta`（196-202）、`_replace_and_enqueue`（209-214）。第 N 条失败时前 N-1 条已提交，`WriteOutcome` 计数随异常丢失。

**影响**：调用方按「整批失败」处理，库里留下半批记忆，既不全有也不全无、无 partial 标记，事后无法判断这次抽取成没成。

**修复**：要么整批一个事务（先量化持锁时间），要么在 docstring 与返回值里如实暴露部分写入。

#### REL-2D7B1 · 低 · shared_client 换 loop 时丢弃旧 client，不关闭连接池
`backend-agent/app/http_client.py:73`

命中缓存要求 `cached_loop is loop and not client.is_closed`，否则直接覆盖 `_SHARED[name] = (loop, client)`（73-74），旧 client 既不 aclose 也不再被引用；清理只有进程退出时的 `aclose_shared()`（78-85）。

**影响**：多 loop 场景（测试、将来 worker 复用、热重载）每换一个 loop 泄漏一个连接池；当前单 loop 生产形态影响有限，故 low。

#### REL-1BDFC · 中 · 熔断记账没有「取消不算失败」的回归用例
`backend-agent/tests/test_resilience.py:1`

`tests/test_resilience.py` 的 17 个用例全部用显式异常驱动（ConnectError / ReadTimeout / GatewayBusinessError）；在 `backend-agent/tests` 下检索 `CancelledError|GeneratorExit` 为 **0 命中**；`tests/test_gateway.py` 只覆盖 HTTP 错误与 4xx/5xx 映射。

**为什么要单独记一条**：REL-32349 的修复必须同时锁住正反两面——只测「取消中性」，可能把 `on_error` 整个删掉而让熔断彻底失效且无人报警。

**修复**：`aclose()` 触发 GeneratorExit 的用例 + `task.cancel()` 用例 + `ReadTimeout` 反例 + 端到端「取消后正常请求仍成功」。

#### REL-44D37 · 中 · execute_tool_calls 的失败隔离没有任何测试
`backend-agent/tests/test_tools.py:1`

`execute_tool_calls` 在 `backend-agent` 内只被 `app/` 引用（`stream_chat.py`、`tool_loop.py:72/194`），`tests/` 为 **0 引用**；`tests/test_tools.py` 的 14 个用例只覆盖 handler 直调、schema 清单与 `run_tool_loop` 轮次上限，注入的异常恰好是 `:101` 已捕获的两类，正好绕开窄 except 的破口。

**修复**：`JSONDecodeError` / `KeyError` / `CancelledError` 三类用例，断言「正常结果仍在、失败工具转文案、取消仍透传」。

### backend/lianyu-service（u01）

#### REL-2669A · 中 · Web Push 异常全吞成 WARN，消费者恒成功且队列无死信
`backend/lianyu-service/src/main/java/com/lianyu/service/notification/WebPushService.java:112`

`sendOne` 对每条订阅 `catch (Exception)`：瞬断走 `log.warn(...); return;`（111-119），非瞬断也只 warn（:120）；非 2xx 仅 WARN（108-110）。外层 `sendToUser` 再兜一层 warn（81-83）。因此 `NotificationPushConsumer.onPushTask`（20-25）永远正常返回、永远 ack。配套 `eventBroadcastQueue`（`backend/lianyu-web/.../RabbitMqConfig.java:84-87`）没有 `x-dead-letter-*`，工厂 `setDefaultRequeueRejected(false)`（:130）。

**影响**：推送瞬断/5xx 时通知永久丢失，只有一条 WARN：无重试、无死信、无计数。

**修复**：`sendToUser` 返回投递结果；「订阅非空且全部瞬断失败」时抛异常交给 retry/DLQ；广播队列补死信出口。

### backend/lianyu-web（u08）

#### REL-784C6 · 中 · 两个死信队列声明了却没有任何消费者
`backend/lianyu-web/src/main/java/com/lianyu/web/config/RabbitMqConfig.java:23`

`memory.summary.dlq`（:23）与 `ai.background.dlq`（:26）都已建队列（:65-67/:80-82）并绑定（:95-97/:105-107），主队列也挂了 DLX（:56-61/:71-76）。但全仓 `@RabbitListener` 只有 3 处且都指向主队列：`AiBackgroundConsumer.java:45`、`MemoryConsumer.java:15`、`NotificationPushConsumer.java:16`——没有消费者监听 DLQ。失败链路是实的：`MemoryWriter.java:133-135` 抛 `RuntimeException`，配合 `application.yml:71-79` 的 `max-attempts: 3` + `setDefaultRequeueRejected(false)`，第 3 次失败即进 DLQ。

**影响**：记忆摘要与 AI 后台任务失败后消息永久静默滞留：无消费者、无指标、无告警、无重放；用户侧表现为「记忆不再更新 / 主动问候消失」。

**修复**：给两个 DLQ 补 `DlqMonitor`（落库 + 计数 + ERROR/health indicator）与受控重放入口。

## 跨单元的系统性问题

1. **失败被降级成日志**：`WebPushService`（u01）与 `NotificationPushConsumer`（u01）、`MemoryWriter → DLQ`（u08）是同一个模式——异常在边界处变成 WARN，MQ 事务边界看到的是成功。一旦失败不可见，重试、死信、告警全部失效，剩下只有「用户没收到」这个事实。
2. **健康判据与真实依赖脱钩**：`/agent/health` 一处恒 UP，被容器编排（根目录）、发版脚本（u04）、Java 降级（u08）三处共享——同一份「不探测」的事实被复制到三个决策点，故障因此能在整条链路上无声通过。
3. **「承诺」与「实现」不一致，且没有用例兜住**：`tool_loop.execute_tool_calls` 的 docstring（u06）、`memory.write_memories` 的 docstring（u06）、`AgentStreamBridge` 的回执语义（u08，这次核验为**一致**）——凡是没有测试锁定的 docstring 承诺，都在实现里悄悄变窄。
4. **记账口径把「我方行为」算成「上游故障」**：熔断器只看到异常，看不到异常来源。取消、断开、超时是我方常态，被记成上游失败后，熔断反而成了放大器。

## 建议修复顺序

**第一批（先止血，1 个 PR）**
1. REL-32349：取消/生成器关闭不计入熔断，同时落地 REL-1BDFC 的正反用例（改一个文件、加一组用例，风险最低、收益最大）。
2. REL-58FC8：把工具兜底放宽到 `Exception` 并保留堆栈（一行改动 + 三条用例）。

**第二批（可观测性，按队列/端点各一个 PR）**
3. REL-784C6：DLQ 监控与重放（先给记忆摘要这条钱路）。
4. REL-2669A：推送失败升级为消费者失败 + 广播队列 DLX。
5. REL-380F5：拆 readiness，改发版自检与 Java 判据（改 4 个文件，注意 compose healthcheck 保持 liveness 语义）。

**第三批（语义对齐，可与功能迭代合并）**
6. REL-6DAB0：批写事务语义（先量化持锁时间再定方向）。
7. REL-2D7B1：换 loop 时关闭旧 client（低优先，顺手做）。
8. REL-44D37：工具隔离用例（可与新工具开发一起提交）。

## 覆盖缺口与未验证项

- **未执行任何测试**：`scope.json` 的 `testCommand` 为 `None`（项目有 pytest / mvn / vitest 命令，但 scope 未检测到），因此本报告不含「跑过了、全绿」的结论；所有 findings 均为静态证据。
- **u02/u05/u10/u11/u12 为 sampled**：做了全量模式扫描（空 catch、`except: pass`、`@Transactional`、阻塞等待、DLQ 引用）后抽查关键文件，不是逐文件阅读。
- **backend/lianyu-dao 未深读错误处理面**：110 个文件只有 mapper/DAL，0 处 `@Transactional`、0 处 catch；SQL 异常语义属于 correctness/data-integrity 维度。
- **未覆盖的运行时面**：多实例并发下的熔断阈值调优、RabbitMQ 实际队列参数（是否已按旧参数声明过，改动需删队列重建）、生产环境的推送失败率——这些需要运行态数据，静态审查给不出结论。
- **性能维度未运行**：需要时执行 `/logi-perf`。

## 转交给其他维度（deferred）

| 来源 | 目标 | 文件 | 原因 |
| --- | --- | --- | --- |
| reliability | 产品/前端（未指定专家） | `frontend/src/composables/useStreamAbort.js:17-34` | 流式失败后前端是否自动重试属产品策略；若重试，会与 REL-32349 形成「重试放大熔断」的叠加效应，需产品先给预期行为 |
| reliability | correctness / data-integrity | `backend/lianyu-dao` | 仅有 mapper/DAL，无错误处理面；SQL 异常语义与事务边界不在 reliability 维度 |

## 说明

本次为单模块、非默认专家组合（仅 reliability），按约定**未更新** `docs/logistics/latest.md` 与 `.logistics/latest.json`；只写本次 run 的 `verdict.json` 与 `report.md`。本报告不含健康分、评分或门禁结论。
