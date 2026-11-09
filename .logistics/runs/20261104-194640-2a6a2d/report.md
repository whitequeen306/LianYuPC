# 予念（LianYu-PC）全项目审查报告

- runId：`20261104-194640-2a6a2d`
- 仓库：`C:/Users/hp/Desktop/LianYu-PC` · 分支 `main` · HEAD `c652e14`（fix(relationship): 修正被刷高的历史信任分（V57））
- 范围：全项目 **1100 文件 / 12 单元 / 135978 行**（Java 450 · JS 252 · Python 220 · SQL 60 · Vue 57），不是 diff 审查
- 参与专家：correctness、security、reliability、maintainability 四位（默认组合，完整覆盖）；**性能维度未运行**
- 编号规则：`<专家前缀>-<5 位序号>`（COR/SEC/REL/MNT），序号即专家报告内第 N 条发现——如 `SEC-00001` 对应 security 报告的 S-01

## 1. 一句话结论

架构分层与注入面总体干净（SQL 全参数化、无 `Runtime`/`ObjectInputStream`/SpEL、SSE 三端帧契约一致），但**存在一个公网可匿名访问、可读写任意用户记忆的 critical 缺口**，外加 7 条 high：前端网络重发无幂等键、Python 记忆 relay 崩溃后永久卡死、内心独白解析在嵌套括号下吞掉整条回复，以及 3 条与鉴权/凭据直接相关的安全缺陷。适合先修 SEC-00001 与 REL-00001/COR-00001 这 3 条「用户可感知且会被真实触发」的问题，再处理凭据与静默降级类缺陷。

## 2. 覆盖矩阵（单元 × 专家）

`R` = reviewed（逐文件读）· `S` = sampled（抽样/模式扫描）· `-` = 未覆盖。无 skipped、无 missing。

| 单元 | 范围 | correctness | security | reliability | maintainability |
|---|---|---|---|---|---|
| u01 | backend/lianyu-service (1/3)，120 文件 | R | R | R | R |
| u02 | backend/lianyu-service (2/3)，120 文件 | S | S | R | S |
| u03 | frontend/src (1/2)，120 文件 | S | S | R | S |
| u04 | scripts (1/2)，120 文件 | S | S | R | R |
| u05 | backend/lianyu-dao，110 文件 | R | R | S | R |
| u06 | backend-agent，102 文件 | R | R | R | R |
| u07 | frontend/src (2/2)，95 文件 | S | S | S | S |
| u08 | backend/lianyu-web，71 文件 | R | R | R | S |
| u09 | frontend/electron，68 文件 | S | R | S | R |
| u10 | 其他模块（(root)、.github、admin 等）67 文件 | S | R | R | R |
| u11 | backend 小模块合集，59 文件 | S | R | S | S |
| u12 | 其他模块（lianyu-app、lianyu-common 等）48 文件 | S | R | S | S |

合计：correctness 4 reviewed / 8 sampled；security 8 / 4；reliability 7 / 5；maintainability 6 / 6。**sampled 不等于已全量读**，缺口见第 7 节。

## 3. 发现（按严重度，37 条保留 / 2 条合并）

### 3.1 Critical

**SEC-00001 公网未认证即可读写任意用户记忆** — `backend-agent/app/main.py:657`（配合 `deploy/api-gateway/nginx.conf:252-285`）
证据：nginx 的 `location /api/agent/` 只有 `limit_req zone=api burst=20 nodelay` + rewrite + `proxy_pass`，**没有 `auth_request`**；Python 侧 25 条路由无任何认证依赖，`prompt_assemble` 只做 `if user_id_header and str(body.user_id) != user_id_header: raise 403`——**不带头就跳过校验**，且 `use_internal_data` 原样透传，会按请求体里的 `user_id` 读库（记忆画像/关系/情绪）。
影响：匿名请求即可用任意 `user_id` 拉取用户记忆画像、写入记忆、触发 relay；`limit_req` 只限速不鉴权。
修复：网关层对 `/api/agent/` 加 `auth_request`（复用 Java 的 Sa-Token 校验），并让 Python 侧把 `X-User-Id` 改为**必需**且与 body 强一致，缺头直接 401。

### 3.2 High

**COR-00001 嵌套括号导致内心独白重复渲染并吞掉台词** — `frontend/src/utils/innerThoughtFilter.js:146`
证据（主编实跑复现）：输入 `（心想（笑）才不是呢）好呀`，输出三段 `inner:"（心想（笑）才不是呢）"`、`inner:"（笑）"`、`speech:"才不是呢）好呀"`；隐藏模式下 `stripInnerThoughts(s,false)` 返回 `""`，整条回复被删空。根因：`findParenthesisRanges` 产出 `[0..11]` 与 `[4..6]` 两个重叠区间，`parseInnerThoughtSegments` 处理第二个区间时把 `lastIndex` 回退到 7。
影响：角色一用括号嵌套（「心想（笑）」是常见写法）就出现重复片段 + 台词错位；开启隐藏独白时用户看到空回复。
修复：区间求并集/取最外层，禁止重叠区间单独出段；`lastIndex` 只允许单调前进。

**REL-00001 前端网络错误自动重发，服务端无幂等键** — `frontend/src/pages/ChatPage.vue:1439`
证据：`isNetworkError(err)` 时 `sleep(800)` 后再次 `await attemptStream()`；`frontend/src/api/conversation.js:67-83` 的 body 无 `clientMsgId`/`Idempotency-Key`；服务端 `ConversationService.java:328-331` 一进来就 `getNextSeq` + `insert(turn.userMsg())` 并 `recordUserTurn`，全程无去重。
影响：一次抖动 = 两条相同用户消息 + 两次完整生成（双倍 token）+ 关系分/情绪重复累计；半截流后重发还会留下「用户自问自答」的历史。
修复：前端生成 `clientMsgId` 并在重发时复用；服务端按 `(conversationId, clientMsgId)` 查重 + 唯一索引；重发命中直接返回上次结果。

**REL-00002 记忆 relay 只捞 PENDING，崩溃后永久卡死** — `backend-agent/app/memory/relay.py:208`
证据：`drain_once` 的 SQL 为 `WHERE status = %s AND next_attempt_at <= NOW()`（仅 PENDING）；`backend-agent/app/main.py:54-65` 的 `_relay_loop` 只调 `drain_once`，从不回捞 RUNNING；唯一入口是人工接口 `requeue_stuck`（relay.py:208 / main.py:967-972）。
影响：进程在写记忆途中崩溃，outbox 行永远停在 RUNNING——记忆静默停更，无告警、无自愈。
修复：`_relay_loop` 增加按 `updated_at` 超时的 RUNNING 回捞（或租约 + 心跳），并对积压深度出指标告警。

**SEC-00002 X-Forwarded-For 默认被信任** — `backend/lianyu-web/src/main/java/com/lianyu/web/util/ClientIpResolver.java:16`
证据：构造参数 `@Value("${lianyu.security.trust-forwarded-for:true}") boolean trustForwardedFor`，默认 true，网关只追加不覆盖。
影响：伪造 `X-Forwarded-For` 即可绕过按 IP 的限流与注册配额。
修复：默认改为 false，仅在可信反代网段内解析，并取最右侧可信跳。

**SEC-00003 生产账号口令硬编码入库** — `scripts/_prod_verify.py:22`
证据：`BASE = "http://127.0.0.1:8080"`、`USERNAME = "hjkl"`、`PASSWORD = "1216qwer"`、`ENV_FILE = "/opt/lianyu/.env"`；`git log` 显示来自提交 `3399884`，已进历史。
影响：仓库/历史任何读者拿到即可登录生产；口令需视为已泄露。
修复：改为读环境变量或 `.env`，**轮换该口令**，并清理脚本里的用户名与环境路径。

**SEC-00004 Jasypt 主密钥缺失时静默降级明文** — `backend/lianyu-security/src/main/java/com/lianyu/security/util/JasyptUtil.java:61`
证据：`currentVersion == null || encryptors.isEmpty()` 时 `log.warn("No Jasypt encryptor available, returning plaintext"); return plaintext;`，厂商 API Key 会以明文落库。
影响：一次配置疏漏就把密钥存成明文，且只有一条 warn，监控看不到。
修复：缺密钥时 fail-fast（启动即失败或加密切记抛错），禁止明文回退。

**SEC-00005 agent 出站不校验 baseUrl，可作内网 SSRF 跳板** — `backend-agent/app/gateway.py:92`
证据：`baseUrl` 不做协议/主机校验，`backend-agent/app/http_client.py:26` 无私网地址拦截；与 SEC-00001 的匿名入口组合即可让服务端向内网发请求。
影响：匿名者借模型网关访问内网元数据/管理端口。
修复：baseUrl 白名单 + 解析后校验 IP 不落在私网/环回/链路本地，并禁用重定向跟随。

### 3.3 Medium（18 条）

| ID | 标题 | 位置 | 证据要点 / 修复摘要 |
|---|---|---|---|
| COR-00002 | 评论锁 unlock 直接 DEL，TTL 过期会删掉新持有者的锁 | `backend/lianyu-service/.../moments/MomentsCommentOrchestrator.java:822` | 锁值恒为 `"1"`、无 owner token、TTL 90s 而锁内含模型调用；改随机 token + Lua 比对删除 |
| COR-00003 | 会话摘要读-改-写无 CAS，并发会让 `lastSummarizedSeq` 回退 | `.../conversation/SessionSummaryService.java:83` | `loadState → merge(模型,秒级) → saveState`；改 WATCH/MULTI 或 Lua 条件写，只许单调前进 |
| SEC-00006 | NapCat WS/WebUI 令牌硬编码 `lianyupc` 并强制覆盖 | `frontend/electron/napcatRuntime/napcatConfig.js:4` | 写 `onebot11*.json`/`webui.json` 时覆盖既有 token；改每实例随机令牌 |
| SEC-00007 | 更新清单公钥缺失时静默跳过签名校验 | `frontend/electron/updater/updater.js:86` | 空公钥只 `logger.warn` 后放行，只剩同源 sha512；生产构建缺公钥应失败 |
| SEC-00009 | 管理端 token 存 localStorage 且 admin 无 CSP | `admin/src/api.js:3` | 8 小时会话可被任一 XSS 窃取；改 HttpOnly Cookie + CSP |
| REL-00003 | 工具循环只捕 `httpx.HTTPError`/`RuntimeError` | `backend-agent/app/tool_loop.py:99` | `ValueError/KeyError/TypeError` 会冲出循环打崩整轮；边界统一成「异常→工具结果文本」 |
| REL-00004 | 死信队列无消费者、广播队列无 DLX | `backend/lianyu-web/.../config/RabbitMqConfig.java:86` | `*.dlq` 只有声明与绑定，零 `@RabbitListener`；补齐可观测可重放出口 |
| REL-00005 | SSE 超时/断开不取消上游订阅 | `backend/lianyu-web/.../agent/AgentStreamAdapter.java:92` | `subscribe(...)` 丢弃 Disposable，无 onTimeout/dispose；模型继续生成到结束 |
| REL-00006 | `pool()` 无锁惰性创建，并发冷启动可能建两个池 | `backend-agent/app/db.py:42` | 首个 `await` 期间交错；用 `asyncio.Lock` 串行化 |
| REL-00007 | 瞬时重试无退避无 jitter，读超时不重试 | `backend-agent/app/resilience.py:302` | `for + except` 立即重试，`max_attempts=2`；加指数退避并纳入读超时 |
| REL-00010 | CI 只跑 Java 与前端，backend-agent/admin 门禁缺失 | `.github/workflows/ci.yml:9` | 无 `pytest`、无 admin `npm test`，前端还跑并行（已知 flake）；四门禁全搬进 CI，并补 `scripts/check-java-ai-free.ps1` 与发布资产契约检查 |
| REL-00011 | 流式消费与重发路径零测试 | `frontend/src/utils/assistantStreamDrain.js:10` | `__tests__` 只有 sseParser；ChatPage 2266 行无测试 |
| MNT-00001 | 记忆画像块两份实现，golden 锁的是没走的那份 | `backend-agent/app/memory/profile_context.py:18` | 生产走 `data_readers.py:174`；让生产复用同一函数 |
| MNT-00003 | QQ 桥与微信桥各一套协调器 | `frontend/electron/qqBridge/qqBridgeCoordinator.js:1` | 12 个相同 10 行窗口；抽 `BridgeCoordinatorBase` |
| MNT-00005 | `main.js` 3077 行、71 处 ipcMain | `frontend/electron/main.js:1` | 收敛为组合根，按域注册 IPC |
| MNT-00006 | `service/rules` 整套 prompt 规则抽象零引用 | `backend/lianyu-service/.../rules/PromptRuleEngine.java:1` | 4 文件 + 空 hooks 目录；整包删除，prompt 归 Python（棘轮查不到该包） |
| MNT-00009 | `@Deprecated deleteVectors` 仍是唯一在走的路径 | `.../memory/MemoryWriter.java:184` | 绕过 outbox 会留「行已删、向量仍在」；`CharacterService.java:214` 应改走 `deleteVectorForMemory` |
| MNT-00010 | 97 个 `_` 一次性脚本入库，83 个零引用 | `scripts/diag/_diag_square_images.py:1` | `.gitignore:106-107` 忽略了 `scripts/diag/` 但仍有 21 个被跟踪；`scripts/_cloud_deploy_pull.py` 被 `local/ship-release.ps1` 引用不可删 |

（MNT-00004、MNT-00011 已按第 5 节并入其他条目，不在上表。）

### 3.4 Low（11 条）

| ID | 标题 | 位置 |
|---|---|---|
| COR-00004 | 关系状态整行覆盖写、无事务无版本号 | `.../relationship/RelationshipStateService.java:132`（另 :165、:185） |
| COR-00005 | `msg_seq` 纯 Redis INCR，无 TTL、无 `MAX(seq)` 回种 | `.../conversation/ConversationService.java:483` |
| SEC-00008 | 依赖审计 `continue-on-error`，OSV 永不阻断 | `.github/workflows/ci.yml:47` |
| SEC-00010 | 渲染端 CSP 含 `unsafe-eval` | `frontend/index.html:7` |
| SEC-00011 | 聊天图片只信客户端 Content-Type，扩展名取自原文件名 | `.../storage/FileStorageService.java:284` |
| SEC-00012 | 公共读 URL 无鉴权无过期，仅靠 UUID 保密 | `backend/lianyu-web/.../PublicFileController.java:62` |
| REL-00008 | 部署自检 `grep` 无匹配即 exit 1，成功被报失败 | `scripts/_cloud_deploy_pull.py:145` |
| REL-00009 | 兜底异常只记类名与 message，无堆栈 | `backend/lianyu-web/.../GlobalExceptionHandler.java:163` |
| MNT-00002 | 登录/注册与落地页两份粒子动画组件 | `frontend/src/components/auth/AuthParticles.vue:1` |
| MNT-00007 | `CharacterStickerMapper` + 实体零引用，V7 两表无读写方 | `backend/lianyu-dao/.../CharacterStickerMapper.java:8` |
| MNT-00008 | admin 四个未 import 的重依赖（echarts/vue-router/vue-table/icons） | `admin/package.json:10` |

（上表 11 条：SEC-00008/SEC-00010 由 security 归为 low；1 critical + 7 high + 18 medium + 11 low = 37 条保留。）

## 4. 专家报告索引

| 专家 | 报告 | 一句话结论 |
|---|---|---|
| correctness | `handoff/correctness.md` | 逻辑/并发缺陷集中在内心独白解析（high）与三处 Redis 锁/状态写（medium/low），四个门禁中三个实测全绿。 |
| security | `handoff/security.md` | `/api/agent/` 无鉴权是唯一 critical，另有 4 条 high（XFF、明文口令、Jasypt 降级、SSRF），注入面干净。 |
| reliability | `handoff/reliability.md` | 失败不可见是主线：重发无幂等、relay 卡死、DLX 无人消费、SSE 不取消、重试无退避，CI 还漏两个门禁。 |
| maintainability | `handoff/maintainability.md` | 全为 medium 以下的结构债：重复实现、上帝模块、死代码与一次性脚本，建议单独批次处理。 |

## 5. 裁决说明（合并与取舍）

- **MNT-00011 → 并入 REL-00010（dropped）**：两者同根因「CI 门禁漂移」。保留 REL-00010（逐 job 对照 AGENTS.md §4，且指出前端并行 flake）；MNT-00011 的增量点「`check-java-ai-free.ps1`、`check_release_asset_contract.py` 不在流水线」并入 REL-00010 修复步骤。
- **MNT-00004 → 并入 MNT-00010（dropped）**：`scripts/ops/_write_theme_picker.py` 属 MNT-00010 的 83 个零引用脚本；「把 Vue 组件内嵌成第二事实源」的风险在 MNT-00010 的清理批次里一并消除。
- 其余 37 条无跨专家重复；同一文件的多条（`ci.yml`、`ConversationService.java`）根因不同，分别保留。

## 6. 跨单元系统性问题

1. **鉴权边界只画在 Java 侧**：`/api/**` 有 Sa-Token，`/api/agent/` 直通 Python 且 Python 无认证依赖（SEC-00001），SEC-00005 的 SSRF 因此可被匿名放大。
2. **幂等与去重缺失**：前端重发（REL-00001）、消息 seq（COR-00005）、摘要状态（COR-00003）都是「先写后算」且无键，抖动/并发会污染数据。
3. **失败不可见**：静默降级（SEC-00004、SEC-00007）、吞异常（REL-00003、REL-00009）、无人消费的死信（REL-00004）、卡死的 RUNNING（REL-00002）——共同点是「出事只有一条 warn 或什么都没有」。
4. **门禁与文档漂移**：AGENTS.md §4 六条门禁，CI 只跑两条（REL-00010），且 `service/rules` 这种违反「prompt 归 Python」红线的代码棘轮查不到（MNT-00006）。
5. **第二事实源**：golden 锁住的是生产未走的 `profile_context`（MNT-00001），生成脚本内嵌组件副本（MNT-00010）。

## 7. 覆盖缺口与未验证项

- u07（frontend/src 2/2，24648 行）、u02、u11、u12 在多数专家处为 sampled；u09（frontend/electron）correctness/reliability 为 sampled，桥接与更新器的长跑可靠性未逐文件核。
- **未运行**：`pytest backend-agent` 本机采集阶段 48 errors，根因 `ModuleNotFoundError: No module named 'respx'`（缺 dev 依赖，非代码缺陷），因此 Python 侧结论为纯代码级，无测试佐证。
- **未做真实扫描**：依赖 CVE（无网络，未跑 osv-scanner/npm audit）；生产 `.env` 三项（`LIANYU_MASTER_KEY`、`LIANYU_TRUST_FORWARDED_FOR`、`VITE_LIANYU_UPDATE_PUBKEY`）决定 SEC-00004/SEC-00002/SEC-00007 在生产是否真的触发。
- **未做运行时验证**：SEC-00001/SEC-00005 的可利用性依赖 443 → nginx → agent:8001 真实链路，建议预发环境用匿名 curl 复验。
- 审查为只读：未提交、未改源码、未删文件。

## 8. 跨专家 deferred（18 条，摘要）

- correctness → reliability：backend-agent 测试缺 `respx` 无法采集；`ProactiveChatScheduler.tick` 异常仅 `log.debug`。
- correctness → maintainability：`tool_loop.py:153` 的 `nudged` 恒为 False（死标志）；Redis key 前缀散落多处。
- correctness → 产品：`AiChatQuotaService` 超限请求仍计入当日配额（设计取舍）。
- security → security：生产 env 三项未验证；依赖 CVE 未真实扫描；admin 端依赖级 XSS 未审计。
- security → reliability：agent 公网可达性未运行时验证。
- reliability → 全部：Electron/桥长跑可靠性、DAO 与 Flyway 失败语义未逐文件核；四门禁未实跑（correctness 已补跑三项）。
- maintainability → 全部：478 个导出中 105 个模块外零引用（噪音大未坐实）；Java 类名孤儿扫描 140 命中（多为注解装配）；`.env.example` 与代码读取键有差异；`ChatPage.vue`/`GroupChatPage.vue` 21 个同构窗口（多为 SCSS）；`backend-agent/app/main.py` 同属上帝模块。

## 9. 修复顺序建议

1. **第一批（critical/high，先止血）**：SEC-00001 → REL-00001 → COR-00001 → SEC-00003（同时轮换口令）；同文件合并：`ci.yml` 的 REL-00010 + SEC-00008 一次改完，`ConversationService.java` 的 REL-00001 + COR-00005 同批。
2. **第二批（high 安全）**：SEC-00002、SEC-00004、SEC-00005（涉及部署配置，需与运维确认默认值再改）。
3. **第三批（medium 可靠性）**：REL-00002、REL-00004、REL-00005、REL-00003、REL-00006、REL-00007；COR-00002/COR-00003 与 REL-00011 的测试同批补。
4. **第四批（结构性）**：MAINT 全部单独一批（MNT-00006 删除、MNT-00009 迁移、MNT-00010 清理脚本），不与功能修复混提。
5. 每批改完跑对应门禁；SEC-00011/SEC-00012 随上传链路一起改。

> 性能维度未运行，需要时执行 `/logi-perf`。
