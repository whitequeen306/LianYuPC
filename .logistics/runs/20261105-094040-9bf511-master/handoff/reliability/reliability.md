# 健壮性审查报告

- 专家：reliability（健壮性）
- 范围：`C:/Users/hp/Desktop/LianYu-PC` 全项目（1100 文件 / 135978 行 / 12 个审查单元），不是 diff，也不是最近一次提交
- 基线：`c652e14`（工作区干净）
- 关注面：错误处理、失败传播、重试/幂等、部分失败、超时、清理、可观测性、测试缺口与回归
- 本轮结论：**11 条发现**（high 3 / medium 5 / low 3），无 critical；整体状态 `ok`（12 个单元全部至少抽样，无 skipped）

---

## 1. 范围与覆盖

| 单元 | 内容 | 文件数/行数 | 状态 | 实读文件 | 说明 |
|---|---|---|---|---|---|
| u01 | backend/lianyu-service (1/3) | 120 / 12117 | reviewed | 9 | AI 后台消费、熔断隔舱、回合门面、SSE 结束帧、落库回调 |
| u02 | backend/lianyu-service (2/3) | 120 / 16014 | sampled | 5 | 全模块空 catch / 吞异常模式扫描 + moments、memory、voice 抽查 |
| u03 | frontend/src (1/2) | 120 / 15840 | reviewed | 11 | 聊天页流式链路、SSE 解析、等待态、HTTP toast |
| u04 | scripts (1/2) | 120 / 12803 | sampled | 6 | 全量故障模式 grep + 部署脚本关键段精读 |
| u05 | backend/lianyu-dao | 110 / 2934 | sampled | 4 | 全量扫描；模块内无测试类（见 §4） |
| u06 | backend-agent | 102 / 16514 | reviewed | 14 | 网关、熔断、流式、工具循环、记忆写入与中继、main 入口 |
| u07 | frontend/src (2/2) | 95 / 24648 | sampled | 7 | 30 处 `.catch(()=>{})` 与空 catch 逐点定位判读 |
| u08 | backend/lianyu-web | 71 / 7840 | reviewed | 7 | 流桥、适配器、运行时客户端、记忆适配器 |
| u09 | frontend/electron | 68 / 14745 | reviewed | 8 | 主进程错误钩子、updater 空 catch 逐个判定 |
| u10 | 其他模块（root/.github/admin/deploy 等） | 67 / 4923 | reviewed | 7 | CI、admin、vitest 配置、nginx、发布契约 |
| u11 | backend 小模块合集 | 59 / 3141 | sampled | 3 | lianyu-ai 出站、security 拦截、storage 失败分支 |
| u12 | 其他模块（app/common/installer 等） | 48 / 4459 | sampled | 3 | application.yml、ErrorCode、脚本抽查 |

**覆盖方式**：先用模式扫描把全仓「失败可能被吞掉」的位置一次圈出来（Java 空 catch 19 处、Python bare except 2 处、JS 空 catch 12 处、`.catch(() => {})` 30 处、被跳过的测试 0 处），再对 AI 链路、SSE 流桥、后台消费者、聊天前端逐文件精读。`reviewed` 指读了该单元的关键文件并做了针对性检查；`sampled` 指做了全量模式扫描但只精读部分文件，**不能当成全量已读**。

### 已查未发现问题的区域

- `frontend/electron`：`logger.js` 已挂 `uncaughtException` / `unhandledRejection` / `render-process-gone` / `child-process-gone`（`frontend/electron/logger.js:143-167`、`frontend/electron/main.js:2976-2980`）；updater 的 7 处 `catch (_) {}` 全是清理临时文件与 abort 兜底（`frontend/electron/updater/updater.js:157/158/217/223/301/319/337`），不构成静默失败。
- `scripts`：`_cloud_deploy_pull.py` 的 `run()` 每个远端命令后都 `recv_exit_status()` 并 `raise SystemExit(code)`（`scripts/_cloud_deploy_pull.py:34-43`），部署失败会中止；未发现吞错。
- `backend-agent` 出站客户端：`http_client.py` 统一构造、显式 connect/read/write/pool 超时；`main.py` 的两条 SSE 端点外层都有 `except Exception` → error 帧 + `[DONE]` 兜底（`backend-agent/app/main.py:252-256`、`:519-523`），不会静默断流。
- `backend-agent` 中继退避：`min(3600, 2**attempts)`、embedding/Milvus 不可用走 defer 不消耗重试次数（`backend-agent/app/memory/relay.py`），策略本身正确。
- `backend/lianyu-service` 的熔断/隔舱：interactive 与 background 双 bulkhead + 双线程池隔离、`tryAcquirePermission` 快失败、失败沿 cause 链找 `BusinessException` 记成功（`backend/lianyu-service/.../ai/AiResilience.java`），设计正确（其文案与后台重试判定的错配见 F2）。
- `u11`、`u12` 抽查：未发现缺失超时或吞错。

---

## 2. 发现清单

| # | 严重度 | 单元 | 位置 | 一句话 |
|---|---|---|---|---|
| F1 | high | u06 | `backend-agent/app/gateway.py:279`（另 `:407`） | 取消/断连被计入共享熔断器，5 次中断即可误开 30s |
| F2 | high | u01 | `backend/lianyu-service/.../AiBackgroundConsumer.java:102` | 重试判定靠文案，熔断文案不匹配 → 任务进无人消费的 DLQ |
| F3 | medium | u08 | `backend/lianyu-web/.../AgentStreamBridge.java:99`（另 `:126`） | 没有 `[DONE]` 的截断流被当成功落库、发通知、写记忆 |
| F4 | low | u06 | `backend-agent/app/memory/writer.py:123` | 注释称整批事务，实现逐条提交，500 与部分落库并存 |
| F5 | medium | u06 | `backend-agent/app/memory/relay.py:207` | 卡 RUNNING 的 outbox 行不自愈，重排只有手工接口 |
| F6 | medium | u03 | `frontend/src/pages/ChatPage.vue:1430` | 聊天流无空闲超时，半开连接永久锁住输入框 |
| F7 | low | u06 | `backend-agent/app/tool_loop.py:101` | 工具异常分类过窄，单工具失败升级为整轮失败 |
| F8 | low | u08 | `backend/lianyu-web/.../AgentRuntimeClient.java:414` | 健康探测失败只写 debug，整链路静默回退不可见 |
| F9 | high | u10 | `.github/workflows/ci.yml:22`（另 `:39`） | CI 不跑 pytest 与 admin，前端还并行跑（文档要求串行） |
| F10 | medium | u10 | `frontend/package.json:18`（另 `admin/package.json:9`） | `--passWithNoTests` 让 0 用例也返回成功 |
| F11 | medium | u08 | `backend/lianyu-web/src/test/.../AgentStreamBridgeTest.java:84` | 取消与「无 DONE 截断」两条语义零回归测试 |

---

## 3. 详细发现

### F1（high）模型网关熔断器把客户端取消/断连计为上游失败

- 位置：`backend-agent/app/gateway.py:279`、`:407`；`backend-agent/app/resilience.py:132`
- 证据：

  ```python
  except BaseException as exc:
      await breaker.on_error(exc)
      raise
  ```

  `resilience.py:132` 的 `on_error` 只把 `GatewayBusinessError` 记成功，其余（含 `CancelledError` / `GeneratorExit`）一律 `self._results.append(False)`。窗口 20、最小 5 样本、失败率 50% 即 `_open()`，OPEN 30s；HALF_OPEN 期任一失败立即重开。breaker key 是 provider + base_url（`breaker_key()`），使用同一官方端点的**所有用户共享同一个 breaker**。
- 触发路径：用户在生成中途关窗/切会话/点停止 → SSE 任务被取消 → `CancelledError` 正好落在 `yield delta` 上 → 被 `except BaseException` 捕获 → 记一次上游失败。5 次此类取消即可打开熔断。
- 影响：正常用户行为就能在 30s 内把共享熔断器打开，此后所有会话在 `try_acquire` 处快失败，统一提示「对方模型暂时繁忙，请稍后再试」；越有人中途退出越难恢复，而上游其实完全健康。日志里只有 `breaker OPEN` 的 warning，定位不到“取消”这个真因。
- 修复摘要（direct）：`except asyncio.CancelledError: raise` 提前拦截，`except BaseException` 收窄为 `except Exception`；`on_error` 对 `CancelledError` / `GeneratorExit` 直接返回；补「连续 5 次取消不得改变 breaker 状态」的用例。

### F2（high）后台 AI 任务按异常文案判重试，熔断文案正好落在名单外

- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/ai/background/AiBackgroundConsumer.java:102-116`；`AiResilience.java:108`；`RabbitMqConfig.java:129/148`
- 证据：

  ```java
  return message.contains("prompt 组装失败")
          || message.contains("消息发送失败")
          || message.contains("服务暂时不可用");
  ```

  而 `AiResilience.java:108` 熔断/隔舱拒绝抛的是 `BusinessException(ErrorCode.AI_PROVIDER_ERROR, "对方模型暂时繁忙，请稍后再试")` —— 三个子串一个都不匹配 → `retryable=false` → `throw last`；容器 `defaultRequeueRejected(false)`，消息进 `ai.background.dlq`。全仓 `git grep "dlq"` 只有队列/绑定声明（`RabbitMqConfig.java:23/26/30/33/66/81/96/106`），**没有任何消费者或重放脚本**。
- 影响：上游真的繁忙/熔断——最该退避重试的场景——反而被判不可重试，日记、动态评论、语音通话总结等任务整条丢弃，只留一行 `log.error`；DLQ 是黑洞，没有告警。附带问题：重试用 `Thread.sleep` 阻塞 listener 线程（默认 1500ms×n），并发下会占满消费线程。
- 修复摘要（plan）：错误码/异常携带 `retryable` 语义，不再匹配中文文案；`Thread.sleep` 改为延迟队列；给 DLQ 加消费者（落死信表 + 指标）或重放脚本。

### F3（medium）流式桥不区分「收到 `[DONE]`」与「上游半途断开」

- 位置：`backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentStreamBridge.java:99-113`、`:126-139`；`ConversationService.java:377-407`
- 证据：`accept()` 里 `case DONE, IGNORED -> { }`（`:93-95`）不记录任何终止状态；`finish()` 只在内容为空时报错，否则 `finishSseSuccess`；`abort()` 直接 `callback.onComplete(partial, null)`。`ConversationService.onComplete` 在 `error == null` 时执行 `saveAssistantReplies` + 关系状态 + `memoryWriter.enqueueSummary` + `notifyAssistantMessage`。
- 触发路径：backend-agent 重启/OOM/网关断开导致流没有 `[DONE]` 就结束；或客户端 `sendSseChunk` 抛 `IOException` 触发 `abort()`。
- 影响：半截回复被当完整消息落库、写关系与记忆摘要、推送通知；用户看到一句没说完的话，既没错误提示也没重试入口，日志里只有正常的 saved 记录，事后无法复盘。
- 修复摘要（plan）：增加 `sawDone`；`finish()` 在未收到 `[DONE]` 时走 `finishSseError` 并以显式异常回调；`abort()` 不再用 `null` 冒充成功；截断内容是否落库（以及是否禁止写记忆/通知）需产品决策。

### F4（low）记忆批量写入：注释是「整批事务」，实现是逐条提交

- 位置：`backend-agent/app/memory/writer.py:3`、`:123`；`backend-agent/app/main.py:917`、`:952-953`
- 证据：三处 docstring 都写「整批在同一事务内，任一失败全回滚」，但 `_insert_new`(:175)、`_update_meta`(:196)、`_replace_and_enqueue`(:209) 各自 `async with transaction()`；写失败时 `raise HTTPException(500, "memory write failed")`，此前提交的条目保留。
- 影响：批次中途失败 → 部分记忆已落库、部分丢失，调用方只拿到 500 无从判断写了多少；Java 侧 `AgentMemoryAdapter.java:95-104` 见 null/error 会回退启发式重写，是否重复完全依赖两侧 `source_hash` 口径一致。按注释假设「全回滚」写恢复逻辑的人会踩坑。
- 修复摘要（direct）：把事务边界上移到 `write_memories`，helper 接收 cursor；返回体补 `created/updated/failed`；补「第二条失败时第一条不存在」的用例。

### F5（medium）记忆 outbox 卡 RUNNING 不自愈，重排只有手工接口

- 位置：`backend-agent/app/memory/relay.py:207`；`backend-agent/app/main.py:967-971`
- 证据：`drain_once` 只认领 `status='PENDING'` 的行；进程在认领后崩溃/重启，该行永远停在 RUNNING。`requeue_stuck()` 能处理 DEAD 与超 10 分钟的 RUNNING，但唯一入口是 `POST /agent/memory/relay/requeue`；`git grep "relay/requeue"` 在 backend/scripts/CI 中零命中。
- 影响：每次部署或 OOM 都可能留下永久 RUNNING 的行 —— 对应记忆不写 Milvus，向量检索永远查不到，角色表现成「突然忘了」，而日志只有一条认领成功的 info。DEAD 行同样只能人工重排，无告警。
- 修复摘要（direct）：`_relay_loop` 每轮或每 10 分钟调用一次 `requeue_stuck()`，异常只 warning；对重排计数打日志；补「卡 RUNNING 超时后被重排」的用例。

### F6（medium）前端聊天流没有空闲超时

- 位置：`frontend/src/pages/ChatPage.vue:1430`；`frontend/src/utils/assistantStreamDrain.js:26-35`；`frontend/src/utils/useStreamAbort.js`
- 证据：`drainAssistantStream(response, { signal })` 进入 `reader.read()` 循环，除 AbortController 外没有任何计时器；全前端没有 stream 相关 timeout 常量；`waitingReply` 直接 disabled 输入框与发送键（`ChatPage.vue:215/225/250/1363`），只在 `finally`（`:1476`）复位。
- 影响：代理/NAT 保持 TCP 但不返回数据，或服务端线程卡死不结束响应时，`read()` 永不 resolve，`waitingReply` 永久为 true：用户无法再发消息，界面一直「正在输入」，没有错误提示也不会自动重试，只能离开页面重进。
- 修复摘要（direct）：加空闲 watchdog（45–60s 无新 chunk 即 abort），超时错误单独提示并保留已接收内容，补假时钟用例。

### F7（low）工具循环只把 httpx/RuntimeError 当作可降级错误

- 位置：`backend-agent/app/tool_loop.py:101`
- 证据：`except (httpx.HTTPError, RuntimeError) as exc:` 时把异常写进工具结果继续对话；`ValueError` / `KeyError` / `aiomysql` 异常直接冒出，最终由 `main.py` 外层兜底成 `sse_error("生成失败，请重试")`。
- 影响：单个工具的数据异常会把局部失败升级为整条回复失败，已生成文本作废，用户只看到通用错误，日志也缺少「哪个工具」的信息。
- 修复摘要（direct）：改为 `except Exception`（`CancelledError` 继承 BaseException，不会被误吞）并 `logger.exception`；同一工具连续失败超阈值时短路后续轮次。

### F8（low）AGENT 健康探测失败只写 debug 日志

- 位置：`backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentRuntimeClient.java:414`
- 证据：`log.debug("Agent health probe failed: {}", e.toString());` 后 `return false`，`ChatTurnAdapter.stream` / `reachable()` 拿到 false 返回 null，上层静默回退 Java 链路；生产默认 INFO 级别看不到这条日志。
- 影响：backend-agent 整体不可用或地址/证书配错时，全量流量悄悄回退旧链路（无工具调用、记忆抽取方式不同），用户与运维都不可见，排障只能靠猜。
- 修复摘要（direct）：提升为带节流的 `log.warn`（复用 `AgentFailureLogThrottle`）+ Micrometer 降级计数 + 响应标记 `degraded`。

### F9（high）CI 不运行 backend-agent 与 admin 测试，前端还并行跑

- 位置：`.github/workflows/ci.yml:22`、`:39`
- 证据：只有三个 job —— `backend`（`mvn -B -ntp test`）、`frontend`（`npx vitest run`）、`deps-audit`（OSV，`continue-on-error: true`）。没有 pytest 步骤，没有 admin 的 `npm test`；`AGENTS.md` §4 要求前端 `npx vitest run --no-file-parallelism`（updater 并发用例在满载并行下已知 flake），CI 里是并行。
- 影响：backend-agent 全部用例（含本轮 F1/F4/F5/F7 涉及的模块）合并前不执行；admin 的测试从不运行；前端并行执行把已知 flake 带进 CI，红灯被当噪音，真失败更容易漏掉。
- 修复摘要（direct）：新增 `backend-agent`（setup-python 3.11 + `pytest -q backend-agent`）与 `admin`（`npm --prefix admin ci && npm --prefix admin test`）两个 job，前端改串行，并按路径过滤。

### F10（medium）`vitest --passWithNoTests` 让「零用例」通过

- 位置：`frontend/package.json:18`；`admin/package.json:9`
- 证据：两个 test 脚本都带 `--passWithNoTests`；`frontend/vitest.config.js` 的 include 是三条 glob，目录调整或配置漂移导致 0 命中时命令仍 exit 0。
- 影响：测试被移动、include 写错、依赖缺失导致收集为空时门禁依旧绿，覆盖率静默归零；与 `AGENTS.md`「测试要能非空转验证」直接冲突。
- 修复摘要（direct）：删除该 flag；CI 断言用例数下限（frontend ≥ 400）；需要空跑的场景新建 `test:optional` 单独使用。

### F11（medium）取消与「无 `[DONE]` 截断」语义零回归测试

- 位置：`backend/lianyu-web/src/test/java/com/lianyu/web/agent/AgentStreamBridgeTest.java:84`
- 证据：8 个用例的输入全部带 `data: [DONE]`（`relaysContentIncrementallyThenFinishesWithDone`、`replaceOverrides`、`errorFrameFinishesWithErrorAndPartialContent`、`emptyContent`、`ignoresUnknownFrames`、`clientDisconnectStopsRelaying`、`failReportsErrorMessage`、`finishIsIdempotent`），没有「流结束但没有 DONE」的断言；`git grep "cancel\(\)\|CancelledError" backend-agent/tests` 命中 0。
- 影响：F1 与 F3 都没有护栏，任何重构都可能在无人察觉下恢复旧行为，而这两条影响全体用户的可用性与数据正确性。
- 修复摘要（direct）：补 `truncatedStreamWithoutDoneReportsError`、`test_cancel_does_not_open_breaker`、stream 生成器 aclose 用例，随 CI 接入后自动守护。

---

## 4. 未验证与抽样说明

- **未执行测试命令**：本轮是只读审查，为避免在仓库里产生 `target/`、`.pytest_cache`、`dist/` 等构建产物，4 条命令（`npm --prefix admin test`、`mvn -q -f backend/pom.xml test`、`pytest -q backend-agent`、`npm --prefix frontend test`）均未实际运行。F9/F10 的结论来自脚本与 CI 配置本身，不依赖执行结果。
- **u02 / u04 / u05 / u07 / u11 / u12 为 sampled**：做了全模块模式扫描（空 catch、bare except、`.catch(()=>{})`、跳过测试），但没有逐文件通读，可能漏掉不含这些模式的失败处理写法（例如返回值判空后静默 return）。
- **u05（lianyu-dao）**：111 个受控文件、迁移 V7–V57，模块内 `class *Test` 为 0，SQL 与迁移正确性没有自动化回归。这属于测试缺口，但修复方式涉及 DB 测试基础设施，本轮未展开，见 §5。
- **前端 30 处 `.catch(() => {})`**：逐处定位后判定 qqBridge 轮询、notifications 刷新等为刻意静默（避免每次后台刷新弹 toast），没有逐处验证其对用户可见性的真实影响。
- **SSE 超时交互未实测**：`ChatTurnAdapter` 构造 `SseEmitter` 时的 `SSE_TIMEOUT_MS` 与前端等待态之间的实际行为（超时后 `sendSseChunk` 是否抛 `IOException` 触发 `abort()`）仅从代码推断。
- **未落成 finding 的旁证**：`backend/lianyu-app/src/main/resources/application.yml:71-80` 声明了 `spring.rabbitmq.listener.simple.retry.enabled=true`，但 `RabbitMqConfig` 手工 new 的 listener factory 未走 Boot 的 configurer，重试拦截可能不生效。与 F2 同源，未单列。

---

## 5. 建议修复顺序

1. **F1（取消不参与熔断）** —— 改动小、影响面最大，直接决定高峰期是否会出现「全员模型暂时繁忙」。
2. **F9（CI 补 pytest/admin + 串行）** —— 先有门禁，后续修复才有护栏；顺序上应先于其它代码修复落地。
3. **F2（重试分类去文案化 + DLQ 兜底）** —— 决定后台任务在模型繁忙时是否静默丢失，需要设计判断，安排人工评审。
4. **F3（`[DONE]` 语义）** —— 涉及「截断是否落库」的产品决策，建议与 F11 的测试一起提交。
5. **F5（outbox 自愈）** —— 独立小改动，可在 relay 循环里一并加指标。
6. **F6（前端空闲超时）** —— 纯前端，风险低，可并行。
7. **F10 / F4 / F7 / F8** —— 低风险清理项，可合并成一两个提交。

## 6. 跨专家转交

- **correctness**：`backend/lianyu-dao` 迁移 V7–V57 无测试保护；`MemoryWriter`（Java 侧）与 `writer.py`（Python 侧）的 `source_hash` 口径是否一致，决定了 F4 部分失败后回退重写会不会产生重复记忆。
- **security**：`AgentRuntimeClient` 的健康探测与 `postJson` 失败会静默回退 Java 链路（F8），若同时存在凭据解析失败，可能把错误信息藏进 debug 日志。
- **performance**：F3 的 `abort()` 只置 `closed`，没有 dispose WebClient 订阅，客户端断开后上游仍继续产 token（计费）——按项目规则性能问题归 plan，本轮只在 F3 里作为副作用提及，未单独定级。
- **maintainability**：F2 的「异常文案当协议」、F4 的「注释与实现相反」都是典型的契约漂移，建议纳入同一批清理。
