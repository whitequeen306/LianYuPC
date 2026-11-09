# 健壮性审查报告

本次审查对象是整份仓库快照（commit `c652e14`，分支 `main`，工作区干净），共 1100 个受审文件 / 12 个审查单元 / 135978 行，聚焦一件事：**失败能不能被看见、能不能传出去、会不会被当成成功、有没有测试兜住**。结论：两处高危都在「用户中途断开」这条路径上，且互相放大；测试侧最强的保护（Python 1222 个用例）目前不在 CI 里，也无法从仓库根一次跑通。

## 范围与覆盖

按 scope 的 12 个单元逐单元检查，实际读取文件数均 ≥ 30，144 个核心文件全部覆盖（缺 0）。状态含义：`reviewed` = 读了关键文件并做了针对性检查；`sampled` = 体量大而抽样，但仍做了全单元模式扫描。

| 单元 | 范围 | 单元文件数 | 实读 | 状态 | 理由 |
| --- | --- | ---: | ---: | --- | --- |
| u01 | backend/lianyu-service (1/3) | 120 | 40 | reviewed | 逐文件读 MQ 消费者、群聊回合编排、会话流回调、ASR/摘要等服务；重点看后台任务失败出口与落库时机。 |
| u02 | backend/lianyu-service (2/3) | 120 | 40 | sampled | 单元以 DTO/请求响应为主，按字段校验与错误契约抽查约 40 个文件，未发现吞异常或错误语义丢失。 |
| u03 | frontend/src (1/2) | 120 | 39 | reviewed | 深读 httpCore（刷新/401/全局错误）、各 api 薄封装与两个 api 测试文件，确认刷新失败语义被当成登录过期。 |
| u04 | scripts (1/2) | 120 | 38 | sampled | 运维/发版脚本按模式扫描后精读部署、上传、校验、e2e 主流程；脚本类失败均以非零退出码或 SystemExit 显式暴露。 |
| u05 | backend/lianyu-dao | 110 | 37 | sampled | 全部迁移文件按破坏性语句（DROP/DELETE/UPDATE）扫描，核心 V1/V10–V19 逐个读过；不可逆迁移只在报告中作跨专家转交。 |
| u06 | backend-agent | 102 | 47 | reviewed | 深读 gateway/resilience/stream_chat/tool_loop/write/memory relay 与相关测试，并对熔断污染做了复现实验。 |
| u07 | frontend/src (2/2) | 95 | 36 | sampled | 深读 CharactersPage/GroupChatPage/路由启动恢复，其余页面按错误处理模式抽样；首屏失败静默集中在角色列表。 |
| u08 | backend/lianyu-web | 71 | 53 | reviewed | 71 个文件中读了 53 个：SSE 桥、适配器、运行时客户端、失败日志节流与控制器入口，确认断开后订阅未被取消。 |
| u09 | frontend/electron | 68 | 40 | reviewed | 深读更新器（空闲超时/校验/重试）、qqBridge/napCat 与 wechatBridge 主链路及其测试；更新失败可重试且报错可见。 |
| u10 | 其他模块（(root)、.github、admin 等） | 67 | 34 | sampled | 覆盖 .github 工作流、.env.example、admin 全量源码、Dockerfile 与两个 pom；确认 CI 缺 pytest/admin job。 |
| u11 | backend（小模块合集 59 文件） | 59 | 31 | sampled | 按测试密度与断言质量扫描 31 个测试文件，确认 Java 侧失败路径覆盖充足（531 用例 0 跳过）。 |
| u12 | 其他模块（backend/lianyu-app、backend/lianyu-common、backend 等） | 48 | 36 | sampled | 读安装器引擎/脚本、common handler/URL 校验、Dockerfile 与 pom；异常均带 cause 或显式抛出。 |

测试命令执行结果（逐条，均在本机实跑）：

| 命令 | 结果 | 说明 |
| --- | --- | --- |
| `npm --prefix admin test` | failed | 命令未能启动：报「'vitest' 不是内部或外部命令」，admin/node_modules 未安装；且 admin 全库仅 1 个测试文件（admin/src/__tests__/menu.test.js），脚本为 vitest --passWithNoTests。 |
| `mvn -q -f backend/pom.xml test` | passed | exit 0；解析 123 个 surefire 报告汇总 tests=531, failures=0, errors=0, skipped=0，报告时间戳为本次运行。 |
| `pytest -q backend-agent` | failed | 在仓库根执行：48 个收集错误，均为 ModuleNotFoundError: No module named 'app'；进入 backend-agent 目录执行则缺 ModuleNotFoundError: No module named 'respx'（dev 依赖未装）。pyproject.toml 无 pythonpath/conftest。 |
| `npm --prefix frontend test` | passed | 71 个测试文件 / 417 个用例全部通过，耗时 5.84s；本次并行模式下 updater 用例未出现已知 flake。 |

套件完整性另做了全仓库扫描：`@Disabled` / `@Ignore` / `xit(` / `it.only` / `pytest.mark.skip` 命中 0，surefire 报告 skipped=0，不存在「被禁用的测试掩盖回归」的情况。

## 发现

共 10 条：high 3 / medium 6 / low 1。按严重度排序，每条都带所属单元。

- [high] [u06] [direct] REL-001 — 用户中途关掉聊天窗口或断网时，这次「用户自己取消」被当成模型服务坏了记账；攒够 5 次后，同一个模型对所有用户都会被判定不可用，别人发消息只会看到「对方模型暂时繁忙」。
详细问题：客户端断开被计为上游调用失败，5 次取消就能打开共享断路器
位置：`backend-agent/app/gateway.py:279` · 证据：stream_text() 在 `except BaseException as exc: await breaker.on_error(exc); raise`（gateway.py:279-281）里记账；CancelledError（Starlette 在客户端断开时取消 StreamingResponse 任务）与 GeneratorExit（async generator 被 aclose）都走这条分支；resilience.py:132-138 的 on_error 只把 GatewayBusinessError 记成功，其余一律算失败，默认参数为滑动窗口 20 样本 / 最少 5 次 / 阈值 50% / 冷却 30s（resilience.py:71-75）。用真实 ModelGateway + 真实 UpstreamBreaker 复现：读 1 个 delta 后 agen.aclose()（_repro_breaker.py）与 task.cancel()（_repro_breaker2.py）各重复 5 次，均得到 state=open、window=[False×5]、下一次 try_acquire() 返回 False。
影响：任何用户切页、断网或前端超时都会给共享断路器加一次失败；30 秒内同 provider+baseUrl 的其他用户全部被判断路器打开而快失败，故障面从「一个用户的一次请求」扩大到「所有人」。日志里只留下 breaker OPEN，无法看出是被用户取消触发的。 · 修复：把「调用方取消」与「上游故障」分开记账：取消/关闭迭代器不计入熔断失败。

- [high] [u08] [plan] REL-002 — 用户在流式回复中途切走或关窗时，服务端只是不再往前端写字，仍在继续向模型要完整回答，这段没人看的生成照常消耗额度，也让这次断开变成上游的一条失败记录。
详细问题：客户端断开后上游模型流不会被取消，回复继续生成并继续计费
位置：`backend/lianyu-web/src/main/java/com/lianyu/web/agent/ChatTurnAdapter.java:72` · 证据：`client.postStream(STREAM_URI, request.getUserId(), body).subscribe(relay::accept, error -> {...}, relay::finish); return emitter;`（ChatTurnAdapter.java:72-80）—— subscribe 返回的 Disposable 被丢弃，类里没有任何 dispose 路径。而 AgentStreamBridge.java:64-96 的 `public boolean accept(String line)` 在 closed 之后恒返回 false（CONTENT 分支 catch IOException → log.info("Agent stream client disconnected, stop relaying: ...") → abort()），这个 false 在 ChatTurnAdapter 里未被使用；abort() 还会以 callback.onComplete(partial, null) 收尾，使断开在 Java 侧被当成成功。
影响：断开后上游 Flux 继续拉取 Python 的 SSE 直到 [DONE]，token 与时长照常计费；Python 侧同时把这次取消记成上游失败（见 REL-001），进一步污染断路器。日志只显示 stop relaying，看不出上游仍在跑。 · 修复：保存订阅句柄，在客户端断开、超时或出错时 dispose，让上游流真正取消。

- [high] [u10] [plan] REL-008 — 后端模型运行时的错误处理、熔断和流式契约全靠 1222 个测试兜着，但 CI 从不跑它们，而且按项目给的命令从仓库根执行会直接收集失败——这层保护目前是关着的。
详细问题：Python 侧 1222 个测试既不在 CI 里，也无法从仓库根跑通
位置：`.github/workflows/ci.yml:25` · 证据：ci.yml 只有 3 个 job：backend（`mvn -B -ntp test`，21-22 行）、frontend（`npx vitest run`，39 行）、deps-audit（continue-on-error: true），没有任何 pytest job。按 scope 命令在仓库根执行 `pytest -q backend-agent`：48 个收集错误，全部为 `ModuleNotFoundError: No module named 'app'`（tests 里 `from app.main import app`）；进入 backend-agent 目录执行则报 `ModuleNotFoundError: No module named 'respx'`（dev 依赖未装）。backend-agent/pyproject.toml 的 [tool.pytest.ini_options] 只有 `asyncio_mode = "auto"` 与 `testpaths = ["tests"]`，既无 pythonpath 也没有 conftest.py。
影响：REL-001、REL-005 这类改动的回归不会被任何自动化拦住；AGENTS.md 里「pytest 全绿 1222+」在 CI 上无法被验证，等于一条失效的承诺。 · 修复：让 pytest 从仓库根可收集，并把 Python 测试接进 CI。

- [medium] [u03] [direct] REL-003 — 网络一抖或服务端 5xx，前端就当作「登录过期」把用户踢回登录页，还顺带清掉主进程里保存的登录凭据，用户必须重新输密码。
详细问题：刷新令牌时的网络错误被当成登录过期，用户会被强制登出并清掉本地会话
位置：`frontend/src/api/httpCore.js:69` · 证据：singleFlightRefresh()（httpCore.js:38-53）`try { await refreshAuthToken(); return true } catch { return false }` 把超时、断网、5xx 一并折成 false；handle401（61-75）`const ok = await singleFlightRefresh(); if (!ok) return rejectSessionExpired();`；rejectSessionExpired()（23-32）执行 clearTokenStorage() + useUserStore().clearAuth({keepUsername:true}) + 跳 `#/`，并 reject「登录已过期，请重新登录」。对比 stores/user.js:160-171 的 restoreSession 明确区分「401/会话失效 → clearAuth」与「网络或服务暂不可用 → 保留本地 token 并 persistSession()」；clearAuth（user.js:199-220）还会调用 electronAPI.clearAuthSession()，抹掉主进程 safeStorage 会话。
影响：弱网或后端重启时用户被强制登出，桌面端需重新输密码；同一产品两条路径对「网络错误」的处理互相矛盾；api/__tests__/sessionRefresh.test.js 只覆盖 401，等于把该行为固化。 · 修复：只在明确的鉴权失败时清会话；网络、超时与 5xx 视为暂时不可用。

- [medium] [u01] [plan] REL-004 — 群聊里角色回复是后台异步跑的；一旦中途抛异常，前端既收不到「本轮失败」也收不到「本轮结束」，那几个角色会一直显示正在输入。
详细问题：群聊回合的异步任务没有失败出口，前端可能永远停在「正在输入」
位置：`backend/lianyu-service/src/main/java/com/lianyu/service/conversation/GroupChatService.java:370` · 证据：`CompletableFuture.runAsync(() -> {...}, taskExecutor)`（GroupChatService.java:370-455）整个 lambda 没有 try/catch，返回的 Future 也未保存、没有 exceptionally/whenComplete；getRecentMessages(383) 与 persistAndBroadcastReplies(428) 都在任何 try 之外，抛异常即静默终止任务，而 TURN_ERROR/TURN_COMPLETE 只在正常走到 440-454 时才发出。前端 frontend/src/pages/GroupChatPage.vue:534-545 只在收到 TURN_* 时清 streamingChar/speakingCharId，没有超时兜底；仓库内没有 GroupChatServiceTest（scripts/e2e/lianyu_e2e.py:325-329 只验证页面可见）。
影响：用户看到「正在输入」长期挂着，只能刷新页面；本轮已生成的回复可能只落库一半（persistAndBroadcastReplies 逐条插入，异常时前几条已写、后续未写），客户端与服务端状态不一致。 · 修复：给异步回合加统一失败出口：任何异常都转成 TURN_ERROR，并在前端加超时兜底。

- [medium] [u06] [direct] REL-005 — 某个工具（例如天气接口返回了非 JSON）抛出别的异常类型时，整轮对话直接失败，模型连「工具坏了」都看不到，用户只收到一句「生成失败，请重试」。
详细问题：工具执行只隔离两类异常，与「单个工具失败不影响整轮」的注释不符
位置：`backend-agent/app/tool_loop.py:101` · 证据：execute_tool_calls 的 docstring（tool_loop.py:77-81）承诺「单个工具失败只影响它自己那条结果」，实际只 `except (httpx.HTTPError, RuntimeError)`（101-103）；weather_tool.py:44-52 的 response.json() 在被网关返回 HTML/空响应时抛 JSONDecodeError（ValueError 子类），JSON 顶层是 list 时 data.get 抛 AttributeError，工具参数缺键抛 KeyError，都会冒泡；stream_chat_events 只捕获 GatewayError，最终由 main.py 的 event_source 兜底成 sse_error「生成失败，请重试」。
影响：第三方服务抖动会退化成整轮对话失败，模型失去「看到工具错误后重试或改用别的工具」的机会；用户只能重发，日志里也只剩一条笼统的生成失败。 · 修复：把工具失败隔离范围放宽到 Exception（保留取消与中断透传），并按既有契约返回失败文本。

- [medium] [u07] [direct] REL-006 — 首屏加载角色失败时页面不给任何提示，用户看到的是空列表，会以为自己创建的角色都没了，也没有重试入口。
详细问题：角色列表首屏加载失败被静默吞掉，用户只看到空列表
位置：`frontend/src/pages/CharactersPage.vue:442` · 证据：fetchCharacters()（CharactersPage.vue:431-445）用 Promise.all([...])，其中 conversations 与 notifications 各自 `.catch(() => [])`（436-437）静默降级，整体 `} catch {} finally { loading.value = false }`（442）把 charactersStore.fetchList 的失败也吞掉，页面只剩空列表；loadCharacterStates()（422-429）`catch { emotionByCharacterId.value = {} }` 同样静默。同类写法见 frontend/src/pages/GroupChatPage.vue:602 创建群聊 `} catch {} finally { creatingGroup.value = false }`，失败无任何提示。
影响：网络或 5xx 时用户无法区分「没有角色」和「加载失败」，既不会重试也不会报障；情绪与未读角标被清空，界面信息与真实数据不一致。 · 修复：首屏主数据失败要可见：给出错误态与重试入口，次要数据降级至少留日志。

- [medium] [u01] [plan] REL-007 — 回答打到一半时模型或网络出错，前端已经把半截回答显示给用户了，但这段文字不会存进会话，用户一刷新就消失，记忆和关系分也只当这轮没发生。
详细问题：流式回复中途报错时，前端已显示的部分回答不会落库
位置：`backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java:378` · 证据：onComplete（ConversationService.java:377-407）`if (error != null && !isClientStreamDisconnect(error)) { log.warn("Assistant stream failed, skip persist: ..."); return; }`，而 isClientStreamDisconnect 只认 `error instanceof java.io.IOException`（427-429）；Python 侧出错时发 sse_error（GatewayError/GatewayBusinessError → BusinessException）后发 [DONE]，此时 fullContent 已有内容，前端的 content 帧早已渲染。该行为被 ConversationServiceStreamErrorTest.java:107-143 固定（`onComplete("partial te...", BusinessException)` 后 verify(messageMapper, never()).insert(...)），属有意取舍。
影响：用户看到过的回答在刷新或换端后消失，同一句还可能被重新生成；memoryWriter 与 relationshipState 不更新，记忆和关系状态与真实对话不一致；半截回复没有任何「不完整」标记可供区分。 · 修复：区分「上游失败但已有内容」与「彻底失败」，前者以不完整标记落库。

- [medium] [u10] [direct] REL-009 — CI 跑前端测试时是并行模式，而仓库里明确记录更新器那组用例在并行下会偶发失败，结果是 CI 随机红灯，真失败容易被当成「又抖了」而忽略。
详细问题：CI 里前端测试按并行执行，已知会随机失败的 updater 用例没有串行保护
位置：`.github/workflows/ci.yml:39` · 证据：ci.yml:39 `run: npx vitest run`（没有 --no-file-parallelism）；AGENTS.md §4 记载「前端测试串行跑（--no-file-parallelism）：既有 updater 并发用例在满载并行下会偶发红，属已知 flake」；frontend/electron/updater/__tests__/updater.test.js 依赖毫秒级 setTimeout(...,1/2/3) 与 `await new Promise((resolve) => setTimeout(resolve, 550))`（513 行）等真实计时，满载并行时容易越界。本次本地 `npm --prefix frontend test` 417 用例 5.84s 全绿，属未触发。
影响：CI 红灯信噪比下降；一旦出现真回归，最省事的处置就是重跑，回归被掩盖的概率变大。 · 修复：把 CI 与本地脚本都固定为串行执行，消除已知 flake。

- [low] [u10] [direct] REL-010 — 管理后台几乎没有自动化测试，连「一个测试都没跑」都会被算成功，所以那部分功能的回归不会被任何检查发现。
详细问题：管理后台只有一个测试文件，且空套件也会被判为通过
位置：`admin/package.json:9` · 证据：`"test": "vitest --passWithNoTests"`（admin/package.json:9），全库只有 admin/src/__tests__/menu.test.js 一个测试文件；CI 没有 admin job；按 scope 执行 `npm --prefix admin test` 直接报 `'vitest' 不是内部或外部命令`（admin/node_modules 未安装）。
影响：管理后台的登录/会话/api.js 封装等逻辑零覆盖；--passWithNoTests 让「测试文件被删或路径写错」也显示通过，属真空转。 · 修复：去掉空套件放行，补最小用例并接入 CI。

## 已查未发现问题的区域

- **u09 更新器 / QQ 桥 / 微信桥**：下载有 120s 空闲超时（`updater.js:156-159`，`download stalled: no data for ...` 后 abort+destroy）、安装包 sha512 校验失败会删文件再抛（`updater.js:333-339`）、清单签名校验失败抛错（`updater.js:81-105`）、下载循环记录 lastError 后重试、探测有看门狗；失败可见且可重试。
- **u04 运维脚本**：`_cloud_deploy_pull.py` 全部 docker 命令走 `get_pty=False` + 显式 timeout（1800s/600s/30s），健康检查 12 次×15s 后 `raise SystemExit(56)`；`_verify_electron_release.py` 用 `check` + 返回码判定；`e2e/lianyu_e2e.py` 逐用例记录 PASS/FAIL 并汇总。
- **u05 迁移**：只读扫描全部破坏性语句（DROP/DELETE/UPDATE），核心 V1/V10–V19 逐个读过；除已转交 security 的 V22 硬编码密钥外，未发现「迁移静默吞错」——Flyway 自身会在失败时中止启动。
- **u08 控制器与适配器降级**：`AiController` 在 agent 不可达时抛 `BusinessException(AI_PROVIDER_ERROR, "消息发送失败，请稍后再试")`，`ChatTurnFacade` 把 null 结果统一转成语义化错误，各 adapter 的 Java 回退都带 `log.warn`，属可见降级。
- **u11 Java 测试**：失败路径覆盖充足（`NotFoundException`/非法输入普遍用 `assertThrows`，`FileStorageServicePublicKeyTest` 还锁了 `updates/../secret` 这类穿越输入），531 用例 0 跳过。
- **u12 安装器与 common**：`InstallerEngine` 在 NSIS 非 0 退出码、缺 payload、找不到主程序时都抛带上下文的异常；`JacksonTypeHandler` 解析失败抛 RuntimeException 并带 cause；`OutboundUrlValidator` 的 catch 只用于「不可解析 → 拒绝」，不影响失败可见性。
- **u06 记忆 outbox**：`relay.py` 用 `UPDATE ... WHERE status=PENDING` 的 affected rows 抢锁（多实例不重复消费），失败按 `min(3600, 2^attempts)` 退避，达上限置 DEAD 并 `logger.error`；embedding 不可用时返回 defer 且不消耗重试次数，属正确设计。

## 未验证与抽样说明

- u02/u04/u05/u07/u10/u11/u12 为 `sampled`：分别实读 u02 40/120、u04 38/120、u05 37/110、u07 36/95、u10 34/67、u11 31/59、u12 36/48，其余文件仅做跨文件模式扫描（catch/except/retry/timeout/TODO），**不能视为逐行已读**。
- REL-001 的复现是在本机用真实 `ModelGateway` + 真实 `UpstreamBreaker`、`monkeypatch` 上游流完成的确定性实验（5 次取消即 open），**未在生产多实例下量化真实触发频率**；但阈值与窗口来自代码默认值，结论不依赖压测。
- REL-002 的「上游继续生成」由代码路径（Disposable 丢弃 + accept 返回 false 未被使用 + 无 dispose）与运行日志 `Agent stream client disconnected, stop relaying` 共同确认，未做端到端抓包计费核对。
- 本地环境无 `rg`，跨文件扫描改用自建只读 node 脚本（按 scope 的单元文件清单逐单元扫描），可能漏掉非典型异常处理写法（如 Kotlin/批处理/配置文件里的重试策略）。
- 前端 `npm --prefix frontend test` 本次并行跑全绿，故 REL-009 的 flake 属「文档记录 + 计时型用例」推断，未能现场复现红灯。

## 建议修复顺序与跨专家转交项

**建议顺序**（先堵「一个用户能拖垮所有人」的口子，再修数据一致性，最后补工程护栏）：
1. REL-001（direct，改动 3 行级）：取消不计入熔断，阻断故障放大；同一提交内补 `test_resilience.py` 用例。
2. REL-002（plan）：保存 Disposable 并在断开时 dispose，与 REL-001 一起上线才能同时解决「白烧 token」和「断路器被污染」。
3. REL-008（plan）：pytest 接入 CI 并能从仓库根跑；这一步决定后面所有 Python 改动是否有护栏。
4. REL-003、REL-006、REL-009、REL-010（direct，均为小改动）：分别修登录误踢、首屏静默、CI flake、空套件放行。
5. REL-004、REL-007（plan）：群聊失败出口与半截回复落库，涉及产品语义与前端配合，放在有回归测试之后做。
6. REL-005（direct）：工具异常隔离放宽到 Exception，补 `test_tool_loop_isolation.py`。

**跨专家转交**：
- → correctness：`backend/lianyu-service/.../GroupChatService.java:428` 的 `persistAndBroadcastReplies` 逐条插入，异常时可能半写；REL-007 的「半截回复」语义需要与产品确认「不完整」如何标记。
- → security：`backend/lianyu-dao/src/main/resources/db/migration/V22__team_default_vault_pool_encrypted.sql:5-6` 在迁移里硬编码第三方 api_key 密文、`V49__drop_default_vault_pool.sql:3` 直接 DELETE 平台密钥行、`V53__drop_community.sql:11-12` DROP 两张表，属不可逆数据/密钥处置，值得单独复核。
- → maintainability：会话失效判定在 `frontend/src/api/httpCore.js:23-75` 与 `frontend/src/stores/user.js:160-171` 有两套口径，建议抽公共函数（REL-003 的修复会顺带消除重复）。
- → perf：REL-002 修复后可用「断开后上游 token 消耗」作为观测指标，评估断路与计费收益。
