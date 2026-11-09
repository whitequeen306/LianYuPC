# 予念（LianYu-PC）全项目审查报告

## 一句话结论

本次审查覆盖 12 个单元、1100 个文件、135978 行，确认 32 条可操作问题：1 条 critical、6 条 high、16 条 medium、9 条 low。最紧急的是 backend-agent 的 /agent/* 端点可被公网匿名调用；其次是 Python 出站无 SSRF 校验、客户端断开污染共享断路器、上游流不取消继续计费。整体不是“到处都烂”，而是三条运行时边界在 agent 入口处失守、若干状态一致性缺口、以及测试体系与 CI 脱节。

## 覆盖情况

- 运行：main / HEAD c652e14，project 模式，1100 个已审文件，12 个单元，144 个核心文件全部命中。
- 四位默认专家全部完成：correctness 6 条、security 7 条、reliability 10 条、maintainability 11 条；合并同根因 CI 两条后保留 32 条，dropped 2 条，deferred 9 条。
- 性能维度未运行（not-selected）；需要性能结论时另跑 `/logi-perf`。
- 覆盖状态：u01–u12 均无 skipped；审阅与抽样的分布见下表，抽样单元仍满足每单元至少 30 个文件。

| 单元 | 范围 | 文件/行 | 正确性 | 安全 | 健壮性 | 可维护性 |
|---|---|---:|---|---|---|---|
| u01 | backend/lianyu-service (1/3) | 120/12117 | 审阅 36 | 抽样 32 | 审阅 40 | 抽样 32 |
| u02 | backend/lianyu-service (2/3) | 120/16014 | 审阅 36 | 抽样 32 | 抽样 40 | 抽样 32 |
| u03 | frontend/src (1/2) | 120/15840 | 审阅 32 | 抽样 32 | 审阅 39 | 抽样 32 |
| u04 | scripts (1/2) | 120/12803 | 抽样 32 | 抽样 32 | 抽样 38 | 抽样 32 |
| u05 | backend/lianyu-dao | 110/2934 | 审阅 36 | 审阅 32 | 抽样 37 | 抽样 32 |
| u06 | backend-agent | 102/16514 | 审阅 36 | 审阅 32 | 审阅 47 | 抽样 32 |
| u07 | frontend/src (2/2) | 95/24648 | 审阅 32 | 抽样 32 | 抽样 36 | 抽样 32 |
| u08 | backend/lianyu-web | 71/7840 | 审阅 36 | 审阅 32 | 审阅 53 | 审阅 32 |
| u09 | frontend/electron | 68/14745 | 抽样 39 | 抽样 32 | 审阅 40 | 审阅 32 |
| u10 | 其他模块（(root)、.github、admin 等） | 67/4923 | 抽样 36 | 审阅 32 | 抽样 34 | 审阅 32 |
| u11 | backend（小模块合集 59 文件） | 59/3141 | 审阅 36 | 审阅 32 | 抽样 31 | 审阅 32 |
| u12 | 其他模块（backend/lianyu-app、backend/lianyu-common、backend 等） | 48/4459 | 抽样 36 | 审阅 32 | 抽样 36 | 审阅 32 |

## 发现

以下按 critical → high → medium → low 排序，不按单元分节。每条保留原始“详细问题”标题，证据均来自源码或已复现的测试。

- [critical] [u06] [plan] SEC-59BE5 — backend-agent 全部 /agent/* 端点只用可选 X-User-Id 做一致性比对，公网网关无鉴权直转，未认证请求可跨用户读写记忆并消耗平台模型额度
  简述：任何人都能直接调用予念后端的 26 个 AI 接口，不需要登录，就能读写别人账号里的记忆、看到别人的提示词，还会消耗平台付费的模型额度。
  详细问题：backend-agent 全部 /agent/* 端点只用可选 X-User-Id 做一致性比对，公网网关无鉴权直转，未认证请求可跨用户读写记忆并消耗平台模型额度
  位置：backend-agent/app/main.py:150 · 证据：main.py 中 18 处同款守卫：`user_id_header: str | None = Header(default=None, alias="X-User-Id")` + `if user_id_header and str(body.user_id) != user_id_header: raise HTTPException(403)`（main.py:150/206/313/365/476/504/663/735/789/842/862/918/988/1064/1148/1182/1224/1260），header 缺省时整段校验被跳过。deploy/api-gateway/nginx.conf:252-283 的 `location /api/agent/` 只有 limit_req、OPTIONS 处理、`set $agent_upstream http://agent:8001`、`rewrite ^/api(/agent/.*)$ $1 break` 与 `proxy_pass $agent_upstream`，没有 auth_request、没有 allow/deny、也不重置客户端自带的 X-User-Id；同段注释却写着「外部请求必须带 X-User-Id 且与 body.userId 一致」。docker-compose.yml:239-242 把 api-gateway 的 80/443/4443 发布到全接口，因此该 location 对外可达。backend-agent/app/config.py:57 定义了 `internal_signature_window_seconds`，但全仓没有任何签名校验实现（无 X-LianYu-Signature 校验代码）。
  影响：匿名攻击者可以：1) `POST /api/agent/memory/write`（main.py:982-1006）按任意 userId+characterId 写入伪造记忆，污染他人角色人格；2) `POST /api/agent/prompt/assemble`（main.py:657-700，use_internal_data 默认 True）经 app/prompt_pipeline.py 的 `_fill_internal_data` → app/data_readers.load_memory_context 读取受害者记忆并回传 systemPrompt，构成跨租户读取；3) `POST /api/agent/memory/relay/drain|requeue`（main.py:959/967）与 `/agent/memory/reconcile`（:974）无需任何身份即可触发运维动作；4) `/agent/desktop-observe`（:143-171，body 无 key 时用平台 settings.multimodal_api_key）、`/agent/tts/synthesize`（:1208）、`/agent/tts/stream`（:1242）、`/agent/voice/enroll`（:1140）、`/agent/character/generate`（:837）可被匿名批量调用，直接烧平台 API 额度。 · 修复：在边缘层把 /api/agent/ 收回到鉴权之后，并让 agent 对内部调用做强制签名校验，缺省即拒绝。

- [high] [u06] [plan] SEC-3C38A — Python 出站请求没有 host/IP 白名单，/agent/chat-stream 等端点的 textBaseUrl 来自请求体，可让 agent 容器向内网任意地址发 POST
  简述：AI 服务会照着请求里给的地址去发网络请求，但完全不检查这个地址是不是内网，攻击者可以借它探测并访问服务器内网和云厂商的元数据接口。
  详细问题：Python 出站请求没有 host/IP 白名单，/agent/chat-stream 等端点的 textBaseUrl 来自请求体，可让 agent 容器向内网任意地址发 POST
  位置：backend-agent/app/gateway.py:92 · 证据：gateway.py:92-110 `normalize_openai_base_url(base_url)` 仅做去尾斜杠/去 /v1 后 `f"{trimmed}/v1/chat/completions"`，没有任何 scheme/host/IP 校验；backend-agent/app/http_client.py:25-37 `new_client()` 只设置 timeout、User-Agent 与 `httpx.AsyncHTTPTransport(local_address="0.0.0.0")`；main.py:186-196 `ChatStreamBody.text_base_url/text_api_key/text_model` 全部来自请求体，main.py:229 直接 `stream_chat_events(..., base_url=body.text_base_url, 请求体中的 textApiKey, ...)`；/agent/chat-image、/agent/text/complete、/agent/desktop-observe 同样是请求体给 base_url。Java 侧已有 backend/lianyu-common/src/main/java/com/lianyu/common/util/OutboundUrlValidator.java（BLOCKED_HOSTS + DNS 解析后私网地址拒绝）与 backend/lianyu-ai 的 SsrfPinningClientFactory（PinnedDns 防重绑定），Python 侧完全没有对应实现。
  影响：配合 SEC-001（/api/agent/ 免鉴权），攻击者可以让 agent 容器向 mysql:3306、redis:6379、minio:9000、backend:8080 的内部端点、docker 网关 172.18.0.1 以及 169.254.169.254 发起带 JSON body 与自定义 Authorization 的 POST，用于内网端口探测、绕过只监听内网的运维接口、读取云元数据；也可把平台 API Key 发往攻击者控制的地址造成凭据外泄。 · 修复：在 Python 出站客户端统一做与 Java OutboundUrlValidator 等价的 URL 白名单 + 解析后 IP 校验，并固定已解析 IP 防 DNS 重绑定。

- [high] [u02] [plan] COR-837C2 — 同一条消息抽出的多条记忆共享 source_hash，互相覆盖或来回改写
  简述：用户一句话里说出的几件事，系统只记得住最后一件，其余会被悄悄覆盖，每次重新整理记忆还会来回改写并重复同步向量库。
  详细问题：同一条消息抽出的多条记忆共享 source_hash，互相覆盖或来回改写
  位置：backend/lianyu-service/src/main/java/com/lianyu/service/memory/MemoryWriter.java:270 · 证据：MemoryWriter.java:270-275 的 resolveSourceHash 只要 sourceIds 非空就 `return computeSourceIdsHash(sourceIds)`，把 summary 参数丢掉；:277-282 的 computeSourceIdsHash 只做 `sha256(sorted ids joined by ",")`。:104-126 每条 ExtractedMemory 只带一个 sourceMsgId（List.of(memory.sourceMsgId())），所以同一条用户消息抽出的 N 条记忆 hash 完全相同。:200 的 findExistingMemory 用 `selectOne(...eq(sourceHash).last("LIMIT 1"))` 命中先插入的那行，:227-251 因 summary 不同走覆盖分支（改写 summary/memoryType/importance、setMilvusVecId(null)、enqueueDelete + enqueueUpsert）。DB 侧 V1__init_schema.sql:64,67 为 `source_hash CHAR(64)` + `UNIQUE KEY uk_source_hash (source_hash)`（全局唯一）。Python 侧同一语义的实现是 backend-agent/app/memory/writer.py:62-74 `payload = f"{joined}|{summary.strip()}" if summary else joined`，其 docstring 自称与 Java 规则一致，实际 Java 未跟上。
  影响：一句“我叫小明，我喜欢猫”会让【长期记忆/姓名】与【长期记忆/爱好】落到同一行，只剩一条；重复抽取时该行在两条内容间来回改写，每轮都往 memory_outbox 追加一次删除+写入，Milvus 反复重投影。用户侧表现为角色记不住刚说过的事。 · 修复：把记忆内容纳入 source_hash 口径，让 Java 与 Python 算出同一个不可碰撞的 hash，并迁移重算历史行。

- [high] [u07] [direct] COR-DFF3C — 嵌套括号时按原串下标删除区间，误删正文并重复渲染内心独白
  简述：当角色回复里出现括号套括号时，关闭内心独白会让整句正文被删空或删错字，开启时同一条独白会重复显示并漏出一个右括号。
  详细问题：嵌套括号时按原串下标删除区间，误删正文并重复渲染内心独白
  位置：frontend/src/utils/innerThoughtFilter.js:171 · 证据：stripInnerThoughts(:167-177) 取 findParenthesisRanges 后按 start 倒序排序，再对**已经变短的 result** 用原串下标切片：`ranges = findParenthesisRanges(text).sort((a,b)=>b.start-a.start); for (...) result = result.slice(0,start)+result.slice(end+1)`。findParenthesisRanges(:108-126) 在遇到右括号时 pop，内层区间先入数组、外层后入，返回时只按 start 排序，重叠区间从未合并。node 实测（stripInnerThoughts(s,false)）：'（a（b）c）嗨' → ''，'（有点紧张（笑））你来了' → ''，'（笑）你好' → '你好'，'hi (a(b)c) there' → 'hi ere'。parseInnerThoughtSegments('（有点紧张（笑））你来了') 返回 [{inner:'（有点紧张（笑））'},{inner:'（笑）'},{speech:'）你来了'}]。
  影响：关闭内心独白（角色设置项）时，用户看到的助手气泡被删成空白或残缺单词（如 there → ere），内容等于丢失；开启时独白重复出现并在正文里多一个“）”。嵌套括号在中文口语化台词里很常见（“（笑（无奈））”），因此不是罕见输入。 · 修复：改为一次性按区间拼装输出，并让区间集合先合并嵌套，避免在变短的字符串上复用旧下标。

- [high] [u06] [direct] REL-0486A — 客户端断开被计为上游调用失败，5 次取消就能打开共享断路器
  简述：用户中途关掉聊天窗口或断网时，这次「用户自己取消」被当成模型服务坏了记账；攒够 5 次后，同一个模型对所有用户都会被判定不可用，别人发消息只会看到「对方模型暂时繁忙」。
  详细问题：客户端断开被计为上游调用失败，5 次取消就能打开共享断路器
  位置：backend-agent/app/gateway.py:279 · 证据：stream_text() 在 `except BaseException as exc: await breaker.on_error(exc); raise`（gateway.py:279-281）里记账；CancelledError（Starlette 在客户端断开时取消 StreamingResponse 任务）与 GeneratorExit（async generator 被 aclose）都走这条分支；resilience.py:132-138 的 on_error 只把 GatewayBusinessError 记成功，其余一律算失败，默认参数为滑动窗口 20 样本 / 最少 5 次 / 阈值 50% / 冷却 30s（resilience.py:71-75）。用真实 ModelGateway + 真实 UpstreamBreaker 复现：读 1 个 delta 后 agen.aclose()（_repro_breaker.py）与 task.cancel()（_repro_breaker2.py）各重复 5 次，均得到 state=open、window=[False×5]、下一次 try_acquire() 返回 False。
  影响：任何用户切页、断网或前端超时都会给共享断路器加一次失败；30 秒内同 provider+baseUrl 的其他用户全部被判断路器打开而快失败，故障面从「一个用户的一次请求」扩大到「所有人」。日志里只留下 breaker OPEN，无法看出是被用户取消触发的。 · 修复：把「调用方取消」与「上游故障」分开记账：取消/关闭迭代器不计入熔断失败。

- [high] [u08] [plan] REL-438AC — 客户端断开后上游模型流不会被取消，回复继续生成并继续计费
  简述：用户在流式回复中途切走或关窗时，服务端只是不再往前端写字，仍在继续向模型要完整回答，这段没人看的生成照常消耗额度，也让这次断开变成上游的一条失败记录。
  详细问题：客户端断开后上游模型流不会被取消，回复继续生成并继续计费
  位置：backend/lianyu-web/src/main/java/com/lianyu/web/agent/ChatTurnAdapter.java:72 · 证据：`client.postStream(STREAM_URI, request.getUserId(), body).subscribe(relay::accept, error -> {...}, relay::finish); return emitter;`（ChatTurnAdapter.java:72-80）—— subscribe 返回的 Disposable 被丢弃，类里没有任何 dispose 路径。而 AgentStreamBridge.java:64-96 的 `public boolean accept(String line)` 在 closed 之后恒返回 false（CONTENT 分支 catch IOException → log.info("Agent stream client disconnected, stop relaying: ...") → abort()），这个 false 在 ChatTurnAdapter 里未被使用；abort() 还会以 callback.onComplete(partial, null) 收尾，使断开在 Java 侧被当成成功。
  影响：断开后上游 Flux 继续拉取 Python 的 SSE 直到 [DONE]，token 与时长照常计费；Python 侧同时把这次取消记成上游失败（见 REL-001），进一步污染断路器。日志只显示 stop relaying，看不出上游仍在跑。 · 修复：保存订阅句柄，在客户端断开、超时或出错时 dispose，让上游流真正取消。

- [high] [u10] [plan] REL-5D862 — Python 侧 1222 个测试既不在 CI 里，也无法从仓库根跑通
  简述：后端模型运行时的错误处理、熔断和流式契约全靠 1222 个测试兜着，但 CI 从不跑它们，而且按项目给的命令从仓库根执行会直接收集失败——这层保护目前是关着的。
  详细问题：Python 侧 1222 个测试既不在 CI 里，也无法从仓库根跑通
  位置：.github/workflows/ci.yml:25 · 证据：ci.yml 只有 3 个 job：backend（`mvn -B -ntp test`，21-22 行）、frontend（`npx vitest run`，39 行）、deps-audit（continue-on-error: true），没有任何 pytest job。按 scope 命令在仓库根执行 `pytest -q backend-agent`：48 个收集错误，全部为 `ModuleNotFoundError: No module named 'app'`（tests 里 `from app.main import app`）；进入 backend-agent 目录执行则报 `ModuleNotFoundError: No module named 'respx'`（dev 依赖未装）。backend-agent/pyproject.toml 的 [tool.pytest.ini_options] 只有 `asyncio_mode = "auto"` 与 `testpaths = ["tests"]`，既无 pythonpath 也没有 conftest.py。
  影响：REL-001、REL-005 这类改动的回归不会被任何自动化拦住；AGENTS.md 里「pytest 全绿 1222+」在 CI 上无法被验证，等于一条失效的承诺。 · 修复：让 pytest 从仓库根可收集，并把 Python 测试接进 CI。

- [medium] [u10] [direct] SEC-061DE — CI 依赖漏洞扫描 continue-on-error，action 全部按可变 tag 引用，且扫描范围漏掉 backend-agent
  简述：CI 里的依赖漏洞扫描被设成失败也不影响流水线，而且扫描范围不含 Python 服务，等于依赖漏洞永远不会挡住合并。
  详细问题：CI 依赖漏洞扫描 continue-on-error，action 全部按可变 tag 引用，且扫描范围漏掉 backend-agent
  位置：.github/workflows/ci.yml:47 · 证据：.github/workflows/ci.yml:39-52 deps-audit job：`- name: OSV-Scanner (best-effort)` + `continue-on-error: true` + `uses: google/osv-scanner-action/osv-scanner-action@v2.0.3`，scan-args 只有 `./backend ./frontend ./admin`；同文件 :14/:15/:28/:29/:45 的 `actions/checkout@v4`、`actions/setup-java@v4`、`actions/setup-node@v4` 均为可变 tag，未固定 commit SHA。
  影响：1) 任何高危依赖即使被 OSV 扫到也不会让流水线红，只有人翻日志才可能发现；2) Python 侧 1157 个测试依赖的 backend-agent（uv.lock）完全不在扫描范围内；3) action 按 tag 引用时，上游 tag 被移动或账号被劫持即可在 CI 中执行任意代码并窃取仓库 secrets（部署 SSH 口令就在同一套 secrets 里）。 · 修复：让扫描成为阻断门禁，补齐 Python 依赖，并把第三方 action 固定到 commit SHA。

- [medium] [u10] [direct] SEC-D0229 — LIANYU_MASTER_KEY 缺失时 JasyptUtil.encrypt 直接返回明文，用户模型 API Key 以明文落库且无失败信号
  简述：如果生产环境漏配加密主密钥，用户的模型 API Key 会被明文存进数据库，而系统只在日志里留一句警告，没有任何人会发现。
  详细问题：LIANYU_MASTER_KEY 缺失时 JasyptUtil.encrypt 直接返回明文，用户模型 API Key 以明文落库且无失败信号
  位置：backend/lianyu-security/src/main/java/com/lianyu/security/util/JasyptUtil.java:61 · 证据：JasyptUtil.java:20-27 构造函数：`String masterKey = System.getenv("LIANYU_MASTER_KEY"); if (masterKey == null || masterKey.isBlank()) { log.warn("LIANYU_MASTER_KEY not set — Jasypt encryption disabled..."); return; }`；encrypt()（:61-72）在 `currentVersion == null || encryptors.isEmpty()` 时 `log.warn("No Jasypt encryptor available, returning plaintext")` 并 `return plaintext`；decrypt()（:76-84）在 encryptors 为空时原样返回密文。调用点 backend/lianyu-service/src/main/java/com/lianyu/service/vault/ApiKeyVaultService.java 第 74 行 与 :130 直接把结果写入 api_key_vault.api_key_encrypted。算法本身正确（PBEWITHHMACSHA512ANDAES_256 + RandomSalt/RandomIv + 1000 iterations + v1/v2 多版本）。
  影响：密钥环境变量漏配时：1) 新写入的 API Key 明文入库，DB 备份、只读账号或运维误导出都会直接泄露用户的付费模型密钥；2) 已有密文与明文混存，事后无法区分；3) 只有一条 WARN，启动不失败、无健康检查、无指标，生产可能长期带病运行。 · 修复：让「没有主密钥」成为生产启动失败而不是静默降级，并对存量数据做一次明文检测与迁移。

- [medium] [u04] [plan] SEC-D0C34 — 114 个运维脚本用 paramiko.AutoAddPolicy 关闭 SSH 主机密钥校验并以 root+密码直连生产机，生产 IP 硬编码在仓库
  简述：仓库里 100 多个运维脚本连生产服务器时关掉了 SSH 指纹校验，还用 root 口令登录，网络上一旦被冒充，生产机和口令都会落到别人手里。
  详细问题：114 个运维脚本用 paramiko.AutoAddPolicy 关闭 SSH 主机密钥校验并以 root+密码直连生产机，生产 IP 硬编码在仓库
  位置：scripts/_backend_logs.py:19 · 证据：`grep -rl AutoAddPolicy scripts backend/scripts | wc -l` = 114；scripts/_backend_logs.py:19-20 `client.set_missing_host_key_policy(paramiko.AutoAddPolicy())` + `client.connect(HOST, <部署账号>, <部署口令>, timeout=30)`（password 取 DEPLOY_SSH_PASSWORD）；scripts/_cloud_500_check.py:26-27 `c.set_missing_host_key_policy(paramiko.AutoAddPolicy())` + `c.connect("156.233.228.18", <部署账号>, ...)`；scripts/diag/_debug_captcha_cases.py:9 `CAPTCHA_URL = "https://156.233.228.18/api/auth/captcha"`；scripts/e2e/lianyu_e2e.py:27 `SSL_CTX.check_hostname = False`。
  影响：AutoAddPolicy 会接受任何主机密钥，DNS 劫持/ARP 欺骗/恶意 Wi-Fi 即可冒充生产机，骗取 root 口令（DEPLOY_SSH_PASSWORD）与后续部署能力；生产公网 IP 与 root 账号硬编码在仓库，任何能读代码的人都拿到入口清单。 · 修复：把运维脚本统一到一套 SSH 连接工厂：校验 known_hosts、改用密钥登录、目标地址从环境变量读取。

- [medium] [u01] [plan] COR-28242 — 消息 seq 只靠 Redis INCR，未按数据库 max(seq) 播种，缓存丢失后与唯一键冲突且不自愈
  简述：消息序号只记在 Redis 计数器里，Redis 一旦被清空或换实例，序号会从 1 重来，跟数据库里已有的序号撞车，之后这个会话再也发不出消息。
  详细问题：消息 seq 只靠 Redis INCR，未按数据库 max(seq) 播种，缓存丢失后与唯一键冲突且不自愈
  位置：backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java:483 · 证据：ConversationService.java:479-487 getNextSeq → reserveSeqBlock 直接 `Long lastSeq = redisTemplate.opsForValue().increment(SEQ_KEY_PREFIX + conversationId, safeCount)`（SEQ_KEY_PREFIX = "msg_seq:"，:72），既没有 setIfAbsent 播种，也没有按 `select max(seq)` 回填；GroupChatService.java:1314-1325、VoiceCallService.java:722 是同一份实现。只有删会话（ConversationService.java:225-228）、clearMessages（:243）、删角色会话（CharacterService.java:297-300）会 delete 这个 key。DB 侧 V1__init_schema.sql:54 有 `UNIQUE KEY uk_conv_seq (conversation_id, seq)`，而所有 messageMapper.insert 调用点都没有 DuplicateKeyException 处理（ConversationService.java:262,330,722、GroupChatService.java:355,574,709、ProactiveMessageService.java:549、VoiceCallService.java:196,206,351,370,453）。缓解事实：docker-compose.yml:271-282 的 redis 用 `--appendonly yes` + 命名卷 redis_data，未配置 maxmemory 驱逐，只有 flush/换库/卷丢失才会触发。
  影响：触发后发消息会持续 500（唯一键冲突），且不会自愈——除非人工删会话或手工 SET 回正确序号。运维迁移 Redis、清理缓存、恢复备份都属于会触发的高频操作。 · 修复：让数据库成为序号真相源：Redis 只做加速，缺失时按 max(seq) 播种，并对冲突做一次重试。

- [medium] [u06] [plan] COR-76C81 — 工具轮次之间文本直接首尾拼接，SSE 内容缺少轮次分隔
  简述：当模型先聊一句再调用工具时，用户会看到两段话直接粘在一起，中间没有空格或换行，读起来像一句错话。
  详细问题：工具轮次之间文本直接首尾拼接，SSE 内容缺少轮次分隔
  位置：backend-agent/app/stream_chat.py:105 · 证据：stream_chat.py:105-107 `round_text.append(delta); collected.append(delta); yield sse_content(delta)`，第 118-132 行把 assistant 消息与 tool 结果回灌后进入下一轮继续 append；`content = "".join(collected)`（:144）同样不带分隔。两轮文本因此在客户端首尾相接，例如第一轮“我先查一下今天”+ 第二轮“北京晴，26 度”直接变成“我先查一下今天北京晴，26 度”。
  影响：含工具调用的回合读起来像错句，且前端拿到的完整 content 与分段 pieces 都是黏连版本，属于面向客户端的内容契约问题；用户会认为角色说话结巴或逻辑断裂。 · 修复：在轮次边界补一个显式分隔（换行或空格），并让 pieces 与最终 content 使用同一份带分隔的文本。

- [medium] [u02] [plan] COR-EF936 — relationship_state 读改写无版本控制，用户名回合与衰减调度并发丢更新
  简述：关系分数是“读出来—改—整行写回”的，后台每半小时的衰减任务和用户聊天同时跑时会互相覆盖，导致刚加的亲密分或刚算的衰减凭空丢失。
  详细问题：relationship_state 读改写无版本控制，用户名回合与衰减调度并发丢更新
  位置：backend/lianyu-service/src/main/java/com/lianyu/service/relationship/RelationshipStateService.java:185 · 证据：recordUserTurn(:107) 在 :132 用 `relationshipStateMapper.updateById(state)` 整行写回；recordAssistantTurn(:136) 在 :165 同样整行写回；decayAll(:172-190) 先 `selectList(orderByAsc(lastDecayAt).last("LIMIT 500"))`（:177-181），再在 :185 `updateById(state)` 整行写回。调度器 backend/lianyu-service/src/main/java/com/lianyu/service/relationship/RelationshipDecayScheduler.java:23-24 为 `@Scheduled(fixedDelayString = "${lianyu.relationship.decay.interval-ms:1800000}")`（默认 30 分钟）。实体 backend/lianyu-dao/src/main/java/com/lianyu/dao/entity/RelationshipState.java 无 @Version 字段，updateById 为全列覆盖。
  影响：衰减任务读到旧快照后写回，会把同一时刻用户回合刚加的 trust/intimacy、streak_days、last_interaction_at 覆盖回旧值；反之用户回合写回会把已结算的 last_decay_at 抹掉，使该行下一轮重复结算或长期轮不到。结果是关系分与连续天数偶发倒退，且没有日志可查。 · 修复：给 relationship_state 引入版本号或原子增量更新，避免整行覆盖式读改写。

- [medium] [u01] [plan] REL-2959C — 群聊回合的异步任务没有失败出口，前端可能永远停在「正在输入」
  简述：群聊里角色回复是后台异步跑的；一旦中途抛异常，前端既收不到「本轮失败」也收不到「本轮结束」，那几个角色会一直显示正在输入。
  详细问题：群聊回合的异步任务没有失败出口，前端可能永远停在「正在输入」
  位置：backend/lianyu-service/src/main/java/com/lianyu/service/conversation/GroupChatService.java:370 · 证据：`CompletableFuture.runAsync(() -> {...}, taskExecutor)`（GroupChatService.java:370-455）整个 lambda 没有 try/catch，返回的 Future 也未保存、没有 exceptionally/whenComplete；getRecentMessages(383) 与 persistAndBroadcastReplies(428) 都在任何 try 之外，抛异常即静默终止任务，而 TURN_ERROR/TURN_COMPLETE 只在正常走到 440-454 时才发出。前端 frontend/src/pages/GroupChatPage.vue:534-545 只在收到 TURN_* 时清 streamingChar/speakingCharId，没有超时兜底；仓库内没有 GroupChatServiceTest（scripts/e2e/lianyu_e2e.py:325-329 只验证页面可见）。
  影响：用户看到「正在输入」长期挂着，只能刷新页面；本轮已生成的回复可能只落库一半（persistAndBroadcastReplies 逐条插入，异常时前几条已写、后续未写），客户端与服务端状态不一致。 · 修复：给异步回合加统一失败出口：任何异常都转成 TURN_ERROR，并在前端加超时兜底。

- [medium] [u01] [plan] REL-3785E — 流式回复中途报错时，前端已显示的部分回答不会落库
  简述：回答打到一半时模型或网络出错，前端已经把半截回答显示给用户了，但这段文字不会存进会话，用户一刷新就消失，记忆和关系分也只当这轮没发生。
  详细问题：流式回复中途报错时，前端已显示的部分回答不会落库
  位置：backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java:378 · 证据：onComplete（ConversationService.java:377-407）`if (error != null && !isClientStreamDisconnect(error)) { log.warn("Assistant stream failed, skip persist: ..."); return; }`，而 isClientStreamDisconnect 只认 `error instanceof java.io.IOException`（427-429）；Python 侧出错时发 sse_error（GatewayError/GatewayBusinessError → BusinessException）后发 [DONE]，此时 fullContent 已有内容，前端的 content 帧早已渲染。该行为被 ConversationServiceStreamErrorTest.java:107-143 固定（`onComplete("partial te...", BusinessException)` 后 verify(messageMapper, never()).insert(...)），属有意取舍。
  影响：用户看到过的回答在刷新或换端后消失，同一句还可能被重新生成；memoryWriter 与 relationshipState 不更新，记忆和关系状态与真实对话不一致；半截回复没有任何「不完整」标记可供区分。 · 修复：区分「上游失败但已有内容」与「彻底失败」，前者以不完整标记落库。

- [medium] [u06] [direct] REL-78C0C — 工具执行只隔离两类异常，与「单个工具失败不影响整轮」的注释不符
  简述：某个工具（例如天气接口返回了非 JSON）抛出别的异常类型时，整轮对话直接失败，模型连「工具坏了」都看不到，用户只收到一句「生成失败，请重试」。
  详细问题：工具执行只隔离两类异常，与「单个工具失败不影响整轮」的注释不符
  位置：backend-agent/app/tool_loop.py:101 · 证据：execute_tool_calls 的 docstring（tool_loop.py:77-81）承诺「单个工具失败只影响它自己那条结果」，实际只 `except (httpx.HTTPError, RuntimeError)`（101-103）；weather_tool.py:44-52 的 response.json() 在被网关返回 HTML/空响应时抛 JSONDecodeError（ValueError 子类），JSON 顶层是 list 时 data.get 抛 AttributeError，工具参数缺键抛 KeyError，都会冒泡；stream_chat_events 只捕获 GatewayError，最终由 main.py 的 event_source 兜底成 sse_error「生成失败，请重试」。
  影响：第三方服务抖动会退化成整轮对话失败，模型失去「看到工具错误后重试或改用别的工具」的机会；用户只能重发，日志里也只剩一条笼统的生成失败。 · 修复：把工具失败隔离范围放宽到 Exception（保留取消与中断透传），并按既有契约返回失败文本。

- [medium] [u07] [direct] REL-7FC42 — 角色列表首屏加载失败被静默吞掉，用户只看到空列表
  简述：首屏加载角色失败时页面不给任何提示，用户看到的是空列表，会以为自己创建的角色都没了，也没有重试入口。
  详细问题：角色列表首屏加载失败被静默吞掉，用户只看到空列表
  位置：frontend/src/pages/CharactersPage.vue:442 · 证据：fetchCharacters()（CharactersPage.vue:431-445）用 Promise.all([...])，其中 conversations 与 notifications 各自 `.catch(() => [])`（436-437）静默降级，整体 `} catch {} finally { loading.value = false }`（442）把 charactersStore.fetchList 的失败也吞掉，页面只剩空列表；loadCharacterStates()（422-429）`catch { emotionByCharacterId.value = {} }` 同样静默。同类写法见 frontend/src/pages/GroupChatPage.vue:602 创建群聊 `} catch {} finally { creatingGroup.value = false }`，失败无任何提示。
  影响：网络或 5xx 时用户无法区分「没有角色」和「加载失败」，既不会重试也不会报障；情绪与未读角标被清空，界面信息与真实数据不一致。 · 修复：首屏主数据失败要可见：给出错误态与重试入口，次要数据降级至少留日志。

- [medium] [u03] [direct] REL-A64C9 — 刷新令牌时的网络错误被当成登录过期，用户会被强制登出并清掉本地会话
  简述：网络一抖或服务端 5xx，前端就当作「登录过期」把用户踢回登录页，还顺带清掉主进程里保存的登录凭据，用户必须重新输密码。
  详细问题：刷新令牌时的网络错误被当成登录过期，用户会被强制登出并清掉本地会话
  位置：frontend/src/api/httpCore.js:69 · 证据：singleFlightRefresh()（httpCore.js:38-53）`try { await refreshAuthToken(); return true } catch { return false }` 把超时、断网、5xx 一并折成 false；handle401（61-75）`const ok = await singleFlightRefresh(); if (!ok) return rejectSessionExpired();`；rejectSessionExpired()（23-32）执行 clearTokenStorage() + useUserStore().clearAuth({keepUsername:true}) + 跳 `#/`，并 reject「登录已过期，请重新登录」。对比 stores/user.js:160-171 的 restoreSession 明确区分「401/会话失效 → clearAuth」与「网络或服务暂不可用 → 保留本地 token 并 persistSession()」；clearAuth（user.js:199-220）还会调用 electronAPI.clearAuthSession()，抹掉主进程 safeStorage 会话。
  影响：弱网或后端重启时用户被强制登出，桌面端需重新输密码；同一产品两条路径对「网络错误」的处理互相矛盾；api/__tests__/sessionRefresh.test.js 只覆盖 401，等于把该行为固化。 · 修复：只在明确的鉴权失败时清会话；网络、超时与 5xx 视为暂时不可用。

- [medium] [u02] [plan] MNT-1C7A4 — 角色广场的 prompt 文案在 Java 与数据库里各存一份，数据库那份永远不会被读取
  简述：角色广场每个角色的设定文案同时写在 Java 代码和数据库插入脚本里，程序只会用 Java 那份，所以有人改数据库里那份会以为生效、其实完全没用，等于给自己埋了个假开关。
  详细问题：角色广场的 prompt 文案在 Java 与数据库里各存一份，数据库那份永远不会被读取
  位置：backend/lianyu-service/src/main/java/com/lianyu/service/square/CharacterSquareCatalog.java:219 · 证据：CharacterSquareCatalog.java 内 String resolvedPrompt = promptPack != null ? promptPack.prompt() : template.getPromptTemplate();（约 219-222 行）。展示入口在 CharacterSquareService.java:145 先做 if (slug == null || !CharacterSquareCatalog.isKnownSlug(slug)) 过滤，因此走到回退分支的 template 必然有对应 promptPack，pack == null 分支不可达。数据库侧：backend/lianyu-dao/src/main/resources/db/migration 下 18 个迁移文件写入 prompt_template，共 55 条 INSERT INTO character_square_template（例如 V18__character_square_genshin.sql、V13__character_square_dal.sql）。同时 CharacterSquareCatalog 系列类里保存了大量 zh/ja/en 角色设定文案，与 AGENTS.md §3.2「prompt 只允许出现在 backend-agent/app/prompts/ 与 Python 代码里」冲突。
  影响：改一句角色设定要改两处，且只有一处生效；维护者按数据库改文案会白改，也没有任何测试能发现这种“改了没效果”。长期看角色文案会在 Java、DB、Python 三处继续分叉。 · 修复：确认 CharacterSquareCatalog 的 prompt pack 为唯一真值源，删除数据库回退路径并让 schema 不再承载 prompt 副本。

- [medium] [u04] [plan] MNT-695AC — scripts/ 里 97 个下划线开头的一次性脚本、7 个部署变体、4 组重复脚本和 2 个 0 字节文件长期留在版本库里
  简述：仓库里堆着上百个一次性运维脚本，其中同一个部署动作有七个版本、同一段逻辑被复制了多份，还有硬编码的生产服务器地址和作者本机路径，别人 clone 下来根本跑不通，也不知道哪个才是“正统”。
  详细问题：scripts/ 里 97 个下划线开头的一次性脚本、7 个部署变体、4 组重复脚本和 2 个 0 字节文件长期留在版本库里
  位置：scripts/_cloud_deploy_pull.py:1 · 证据：git ls-files scripts 共 132 个文件，其中 97 个以 _ 开头（73%），而 AGENTS.md §3.1 规定一次性脚本应放 local/ 或不提交；backend/scripts 10 个、frontend/scripts 13 个同类文件。同一部署流程有 7 个变体：_cloud_deploy_agent_base_url.py、_cloud_deploy_agent_layer.py、_cloud_deploy_backend_layer.py、_cloud_deploy_backend_only.py、_cloud_deploy_layered.py、_cloud_deploy_prep.py、_cloud_deploy_pull.py。scripts/ 内 81 个文件重复定义 def load_dotenv、16 个重复定义 def load_env、105 个直接用 paramiko SSHClient、109 个硬编码主机 156.233.228.18。重复脚本组：scripts/gen_elysia_openai.py(331 行) 与 Pets/skills/hatch-lianyu-pet/scripts/gen_pet_images.py(355 行) 14 个同名函数；scripts/gen_pet_fixed_voices.py(296) 与 scripts/diag/_gen_pet_fixed_voices.py(271) 7 个；scripts/_repair_flyway_v34.py(118) 与 scripts/ops/_repair_flyway_v34_mark_applied.py(96) 4 个；scripts/enroll_pet_voices.py 与 Pets/skills/hatch-lianyu-pet/scripts/enroll_pet_voices.py md5 完全相同。本机绝对路径硬编码 7 处：scripts/gen_elysia_dashscope.py:111、scripts/gen_elysia_openai.py:255、scripts/replay_catalog.py:13、scripts/_check_yml.py:6、backend/scripts/dedupe-java-imports.ps1:1、backend/scripts/fix-service-cross-imports.ps1:2、backend/scripts/repackage-service.ps1:3（均为 c:\Users\hp\Desktop\LianYu-PC\...）。0 字节但已跟踪：frontend/scripts/_run-release.ps1、backend/scripts/_vault_export.tsv。
  影响：新人无法判断该跑哪个脚本，改一个部署行为要同步改 7 份；硬编码主机与作者本机路径让脚本在别人机器上必然失败，也让仓库变成“只有作者能用的工具堆”。 · 修复：收敛成一套带参数的运维脚本（deploy/upload/diag 三条），一次性脚本移出仓库或放进 local/，并清掉重复与空文件。

- [medium] [u09] [plan] MNT-75E6F — Electron 主进程 main.js 是 3077 行的上帝模块，71 个 IPC 注册点且没有任何测试
  简述：桌面端的启动文件把所有窗口、托盘、更新、桥接逻辑塞在一个 3000 多行的文件里，谁改动都要在整份文件里找位置，而且现有测试完全碰不到它，改错了只能靠手工点界面发现。
  详细问题：Electron 主进程 main.js 是 3077 行的上帝模块，71 个 IPC 注册点且没有任何测试
  位置：frontend/electron/main.js:1 · 证据：frontend/electron/main.js 共 3077 行、111 个函数、71 处 ipcMain.handle/ipcMain.on 注册；顶部一次性 import electron 的 app/BrowserWindow/Tray/Menu/globalShortcut/Notification/powerMonitor/dialog/session/screen/net 等 20+ 能力。frontend/electron/__tests__ 下 11 个测试文件只覆盖 launcherBounds/startupOrchestrator/startupProfiler/updater 等被抽出的叶子模块，没有一处 require('./main') 或 import main.js。
  影响：窗口、托盘、快捷聊天、更新器、QQ/微信桥接的生命周期互相耦合在同一个文件里：任何一处 IPC 改动都可能影响其它入口，且缺少可回归的自动化证据；新人上手成本最高。 · 修复：按能力把 main.js 拆成若干 registerXxx(ctx) 模块，main.js 只保留 app 生命周期与装配顺序，并给每个注册函数补 fake ipcMain 的注册断言。

- [medium] [u07] [plan] MNT-9B09F — SSE 帧解析有三份实现，前端单元测试测的是文件内自造的副本而不是生产代码
  简述：流式回复的解析规则被写了三遍（前端共享模块、Java 转发层、Python 运行时），而且有个前端测试自己在测试文件里抄了一份解析函数来测，生产代码改坏了它也照样绿。
  详细问题：SSE 帧解析有三份实现，前端单元测试测的是文件内自造的副本而不是生产代码
  位置：frontend/src/utils/__tests__/sseParser.test.js:4 · 证据：frontend/src/utils/__tests__/sseParser.test.js:4 在测试文件内定义 function parseSseDataLine(line) 并只测这份副本；真正的共享实现在 frontend/shared/sseParser.js（107 行，导出 parseSseDataPayload:31、parseSseStream:57、createSseAccumulator:81），被 frontend/src/utils/assistantStreamDrain.js:1、frontend/electron/qqBridge/qqBridge.js:17、frontend/electron/wechatBridge/wechatBridge.js:5 引用，且已有 frontend/shared/__tests__/sseParser.test.js。服务端还有第三份：backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentSseParser.java（160 行）；帧的生产方是 backend-agent/app/stream_chat.py（DONE_FRAME/HEARTBEAT 常量）。
  影响：AGENTS.md §3.4 要求 SSE 帧契约变更必须三端同步，但当前没有一条测试能从帧样本出发校验两端解析一致；前端那份副本测试给出的是虚假的安全感。 · 修复：删掉测试文件里的解析副本，改为直接测 frontend/shared/sseParser.js，并用同一组 golden 帧样本约束 Java 与 Python 两侧。

- [medium] [u03] [plan] MNT-B0A65 — 语言清单存在 4 份互相不一致的定义，zh-TW 语言包 529 行无人引用，中英日三语缺 6 个仍在使用的 key
  简述：同一个“支持哪些语言”的事实被 Java、Python、前端常量、前端 i18n 各写了一遍，结果四份都不一样：繁体中文包已经写好却从来没被加载，英文和日文界面有 6 处会直接显示 key 名而不是人话。
  详细问题：语言清单存在 4 份互相不一致的定义，zh-TW 语言包 529 行无人引用，中英日三语缺 6 个仍在使用的 key
  位置：frontend/src/constants/language.js:3 · 证据：四份定义：backend/lianyu-common/src/main/java/com/lianyu/common/i18n/OutputLanguage.java:6-9 枚举 ZH/ZH_TW/JA/EN；backend-agent/app/language_gate.py:22 SUPPORTED = ("zh", "zh-TW", "ja", "en")；frontend/src/constants/language.js:3 SUPPORTED_LANGUAGES = ['zh','ja','en']；frontend/src/i18n/index.js:16-17 fallbackLocale: 'zh' + messages: { zh, ja, en }。frontend/src/i18n/locales/zh-TW.js 共 529 行、588 个扁平 key，全仓无任何 import（仅 frontend/src/utils/dateLocale.js:4 出现过 'zh-TW' 字面量）。key 数量：zh 583 / zh-TW 588 / en 582 / ja 575；en 与 ja 都缺 chat.scrollToBottom（ChatPage.vue:173、GroupChatPage.vue:131 在用）、qqBridge.binding.providerPlaceholder（QqBridgePage.vue:157）、header.apiSettings、header.changePassword、header.logoutConfirm、home.emotionHint；memory.* 共 11 个 key 只存在于 zh-TW（MemoryPage.vue:11,76 在用）；characterSquare.addDialogTitle 在 zh-TW 缺失（CharacterSquarePage.vue:223 在用）。frontend 内不存在 i18n key 一致性测试。
  影响：切到英文或日文的用户会看到裸 key（例如 chat.scrollToBottom、qqBridge.binding.providerPlaceholder），记忆页整块文案在 zh/ja/en 下都缺；新增文案没有任何机制保证四语齐全，AGENTS.md 的“四语齐”约定事实上已经失守。 · 修复：把语言清单收敛成一份可跨端读取的事实，并为 i18n 补一致性测试；先决定 zh-TW 是上线还是删除，再补齐缺失 key。

- [low] [u09] [plan] SEC-2C21F — 打包版渲染进程 CSP 保留 script-src 'unsafe-eval'，XSS 可升级为调用 preload 能力窃取 token
  简述：桌面客户端页面允许执行动态生成的脚本，万一以后某处渲染了用户内容又被绕过过滤，攻击者就能在客户端里拿到登录凭证。
  详细问题：打包版渲染进程 CSP 保留 script-src 'unsafe-eval'，XSS 可升级为调用 preload 能力窃取 token
  位置：frontend/electron/main.js:329 · 证据：frontend/electron/main.js:326-334 CSP 为 `default-src 'self'; script-src 'self' 'unsafe-eval'; ...; object-src 'none'; frame-ancestors 'none'`（注释说明 vue-i18n 编译需要 eval）；frontend/index.html:7 同款 meta CSP；preload 暴露了 auth:set-session、apiRequest 等能力（frontend/electron/preload.js 白名单 + main.js assertTrustedSender），渲染进程一旦执行任意脚本即可读取登录态并代替用户发请求。目前 v-html 仅 2 处：frontend/src/pages/MomentsPage.vue:146 经 sanitizeHtml（DOMPurify 白名单），frontend/src/pages/LandingPage.vue:122 为静态常量。
  影响：当前没有可直接利用的注入点，风险是「防线只剩 sanitizeHtml 一层」：任何新增未净化 HTML/第三方脚本都会直接拿到与主进程同等的 API 调用能力（含 lianyu-token），且 'unsafe-eval' 让基于字符串的注入更容易落地。 · 修复：用 vue-i18n 的预编译关闭方式去掉 unsafe-eval，并把渲染进程可调用的 IPC 能力再收窄。

- [low] [u10] [plan] SEC-65BBD — 私聊语音与克隆音色文件经匿名 /api/public/files/** 下发，URL 泄露后长期可下载且不可吊销
  简述：私聊语音和用户克隆的音色文件走的是匿名下载地址，链接一旦被截图、日志或第三方拿到，别人就能长期下载这段私人录音。
  详细问题：私聊语音与克隆音色文件经匿名 /api/public/files/** 下发，URL 泄露后长期可下载且不可吊销
  位置：deploy/api-gateway/nginx.conf:168 · 证据：deploy/api-gateway/nginx.conf:168 `location ^~ /api/public/files/chat-voice/` 直接代理到 backend，无 auth_request；backend/lianyu-web/src/main/java/com/lianyu/web/controller/PublicFileController.java:34 `@GetMapping("/api/public/files/**")` 匿名，返回头含 `Access-Control-Allow-Origin: *` 与 `Cache-Control: public, max-age=86400`；backend/lianyu-service/src/main/java/com/lianyu/service/storage/FileStorageService.java:50-52 的 SAFE_OBJECT_KEY 白名单包含 `chat-voice/` 与 `custom-voices/[0-9]+/`，即这两类私人音频属于「公开可读」集合。
  影响：聊天里的语音消息与用户上传的克隆音色是私人内容，却只要拿到 URL 就能匿名、跨域、无需登录地反复下载，且在浏览器/中间缓存里保留一天；对象名是 32 位随机串，属于「URL 即凭据」，一旦出现在客服截图、日志、第三方分析或分享链接里就无法吊销（只能删对象）。 · 修复：把私人音频移出匿名公开前缀，改为短期签名 URL 或鉴权流式下载。

- [low] [u01] [direct] COR-42F8E — Java 内心独白剥离只做单层替换，嵌套括号残留且与前端结果不一致
  简述：后端在关闭内心独白时只删一层括号，遇到括号套括号会留下一段带括号的残句，跟前端删得不一样。
  详细问题：Java 内心独白剥离只做单层替换，嵌套括号残留且与前端结果不一致
  位置：backend/lianyu-service/src/main/java/com/lianyu/service/ai/InnerThoughtFilter.java:28 · 证据：InnerThoughtFilter.java:11-12 只有单层模式 `FULL_WIDTH_PARENS = （[^（）]*）`、`HALF_WIDTH_PARENS = \([^()]*\)`；:24-31 的 strip 对全角、半角各跑一次 replaceAll，不循环也不按区间处理。因此“（a（b）c）”只会删掉内层“（b）”，留下“（ac）”。同一段文本前端 stripInnerThoughts 走区间整段删除（见 C-02），两端结果不同。调用点：ConversationService.java:704、GroupChatService.java:1155、VoiceCallService.java:533,588、voice/SentenceSplitter.java:217、voice/VoiceCallDuplexSession.java:491（落库展示与 TTS 朗读都会用到）。现有 InnerThoughtFilterTest 只有 5 条基础断言，无嵌套用例。
  影响：关闭内心独白后，带嵌套括号的回复会以“（ac）”这种残句落库并显示，TTS 也会把括号读出来；同一段文本在客户端被整段删除、在服务端只删一层，前后端展示不一致。 · 修复：按最外层配对整体删除，而不是按单层正则替换。

- [low] [u10] [direct] REL-35BDA — 管理后台只有一个测试文件，且空套件也会被判为通过
  简述：管理后台几乎没有自动化测试，连「一个测试都没跑」都会被算成功，所以那部分功能的回归不会被任何检查发现。
  详细问题：管理后台只有一个测试文件，且空套件也会被判为通过
  位置：admin/package.json:9 · 证据：`"test": "vitest --passWithNoTests"`（admin/package.json:9），全库只有 admin/src/__tests__/menu.test.js 一个测试文件；CI 没有 admin job；按 scope 执行 `npm --prefix admin test` 直接报 `'vitest' 不是内部或外部命令`（admin/node_modules 未安装）。
  影响：管理后台的登录/会话/api.js 封装等逻辑零覆盖；--passWithNoTests 让「测试文件被删或路径写错」也显示通过，属真空转。 · 修复：去掉空套件放行，补最小用例并接入 CI。

- [low] [u09] [plan] MNT-020D0 — startupProfiler 在 electron 与 src 下各有一份实现，时间基准不同
  简述：启动耗时统计被抄成了两份，一份用系统时钟、一份用高精度时钟，两边数字不可比，排查启动慢的时候容易被误导。
  详细问题：startupProfiler 在 electron 与 src 下各有一份实现，时间基准不同
  位置：frontend/electron/startupProfiler.js:1 · 证据：frontend/electron/startupProfiler.js:1 export function createStartupProfiler({ prefix, log, now = () => Date.now() })；frontend/src/utils/startupProfiler.js:2-8 默认 now 走 performance.now() 并在缺失时回退 Date.now()，且对结果做 Math.round。两份各带测试：frontend/electron/__tests__/startupProfiler.test.js 与 frontend/src/utils/__tests__/startupProfiler.test.js。
  影响：主进程与渲染进程的启动耗时用不同基准，日志里同名指标不可横向比较；同一处修复要改两遍。 · 修复：抽一份共享实现（前端 side-effect free），两端注入各自的时钟并统一取整规则。

- [low] [u05] [plan] MNT-2D162 — 57 个 Flyway 迁移里有 33 个含非 ASCII 字符，与 AGENTS.md 的 ASCII-only 约定相反
  简述：文档要求数据库迁移脚本只能是纯英文，但仓库里超过一半的迁移文件写了中文注释和中文数据，换一台编码不一致的机器或客户端就可能读成乱码。
  详细问题：57 个 Flyway 迁移里有 33 个含非 ASCII 字符，与 AGENTS.md 的 ASCII-only 约定相反
  位置：backend/lianyu-dao/src/main/resources/db/migration:1 · 证据：backend/lianyu-dao/src/main/resources/db/migration 下 57 个 .sql，grep -lP '[^\x00-\x7F]' 命中 33 个（V1、V10–V29 中的大部分，如 V10__character_square.sql、V13__character_square_dal.sql 的中文注释与中文 INSERT 值）；AGENTS.md §3.3 明确要求「迁移文件保持 ASCII-only」。
  影响：在 charset 不一致的客户端/容器里执行迁移可能写入乱码或校验失败；历史迁移已被 Flyway 记录校验和，事后修改会直接导致 validate 失败，因此这个约定只能靠新文件守住。 · 修复：不改历史迁移（会破坏 Flyway checksum），改为新增一个门禁脚本 + 模板注释，确保从此往后新迁移都是 ASCII-only。

- [low] [u10] [direct] MNT-318EC — frontend 的 devDependencies 里挂着两个全仓零引用的包
  简述：桌面端项目的开发依赖里装了两个从来没被用到的包，每次安装都要多下载一份，也让人误以为项目里有对应的能力。
  详细问题：frontend 的 devDependencies 里挂着两个全仓零引用的包
  位置：frontend/package.json:37 · 证据：frontend/package.json:37 "@vue/test-utils": "^2.4.6"、:44 "vite-plugin-electron-renderer": "^1.0.0"；在 frontend/src、frontend/electron、frontend/shared、frontend/scripts 与各 vite 配置中均无引用（测试全部是 vitest 直接测纯函数/模块，不做组件挂载）。对比：@vue/test-utils 在仓库其它包（如 admin）也无使用记录。
  影响：依赖体积与安装时间无谓增加，且给后来者留下“有组件测试设施”的错误印象；安全审计面也多两个包。 · 修复：从 devDependencies 删除这两个包并同步 lockfile。

- [low] [u10] [direct] MNT-DD43B — admin 后台的 6 个源文件被压成单行/少行，编辑器与 code review 都无法阅读
  简述：后台管理页面的样式和组件被压缩成了一行几千个字符，任何改动都会显示成“整行全改”，评审时看不出改了什么，也没法用编辑器正常定位。
  详细问题：admin 后台的 6 个源文件被压成单行/少行，编辑器与 code review 都无法阅读
  位置：admin/src/styles.css:1 · 证据：admin/src/styles.css 全文 1 行、最长行 5759 字符；admin/src/management.css 2 行（最长 1057）；admin/src/App.vue 15 行但单行最长 2178 字符；admin/src/pages/ManagementPage.vue 15 行、最长 1058；admin/src/stores/session.js 2 行（635）；admin/src/components/DataTable.vue 5 行（667）。
  影响：这些文件的 diff 永远是整行替换，无法评审也无法 blame 到具体改动；出问题时不能用编辑器跳转定位。 · 修复：用格式化工具把 admin/src 下的压缩源文件按常规风格展开，仅格式变更。

- [low] [u07] [plan] MNT-F6920 — 三个前端测试用 readFileSync 读 .vue 源码做字符串断言，测的是排版不是行为
  简述：有几个前端测试不是验证功能，而是把页面源码当文本读进来找字符串，谁调一下顺序或改个缩进就会莫名其妙变红，和用户实际体验无关。
  详细问题：三个前端测试用 readFileSync 读 .vue 源码做字符串断言，测的是排版不是行为
  位置：frontend/src/pages/__tests__/settingsLayout.test.js:1 · 证据：frontend/src/pages/__tests__/settingsLayout.test.js、wechatBridgePage.test.js、momentsCommentThread.test.js 都用 readFileSync(resolve(currentDir, '../XxxPage.vue')) 后对源码做断言（例如 settingsLayout.test.js 断言 SettingsPage.vue 中区块出现顺序）。同一目录下的 charactersRealtime.test.js 只测导出的纯函数，是更可持续的写法。
  影响：这类测试对行为零保护，却会在无害重构时变红，长期降低大家对红灯的信任；也让人不敢重排模板结构。 · 修复：把源码文本断言换成对导出纯函数或渲染结果的断言，保留真正有价值的契约检查。

## 可直接修

以下为 [可直接修] 项；这些改动边界清楚、无需产品决策，可按条目直接开修。

- COR-DFF3C（u07）— 当角色回复里出现括号套括号时，关闭内心独白会让整句正文被删空或删错字，开启时同一条独白会重复显示并漏出一个右括号。
- REL-0486A（u06）— 用户中途关掉聊天窗口或断网时，这次「用户自己取消」被当成模型服务坏了记账；攒够 5 次后，同一个模型对所有用户都会被判定不可用，别人发消息只会看到「对方模型暂时繁忙」。
- SEC-061DE（u10）— CI 里的依赖漏洞扫描被设成失败也不影响流水线，而且扫描范围不含 Python 服务，等于依赖漏洞永远不会挡住合并。
- SEC-D0229（u10）— 如果生产环境漏配加密主密钥，用户的模型 API Key 会被明文存进数据库，而系统只在日志里留一句警告，没有任何人会发现。
- REL-78C0C（u06）— 某个工具（例如天气接口返回了非 JSON）抛出别的异常类型时，整轮对话直接失败，模型连「工具坏了」都看不到，用户只收到一句「生成失败，请重试」。
- REL-7FC42（u07）— 首屏加载角色失败时页面不给任何提示，用户看到的是空列表，会以为自己创建的角色都没了，也没有重试入口。
- REL-A64C9（u03）— 网络一抖或服务端 5xx，前端就当作「登录过期」把用户踢回登录页，还顺带清掉主进程里保存的登录凭据，用户必须重新输密码。
- COR-42F8E（u01）— 后端在关闭内心独白时只删一层括号，遇到括号套括号会留下一段带括号的残句，跟前端删得不一样。
- REL-35BDA（u10）— 管理后台几乎没有自动化测试，连「一个测试都没跑」都会被算成功，所以那部分功能的回归不会被任何检查发现。
- MNT-318EC（u10）— 桌面端项目的开发依赖里装了两个从来没被用到的包，每次安装都要多下载一份，也让人误以为项目里有对应的能力。
- MNT-DD43B（u10）— 后台管理页面的样式和组件被压缩成了一行几千个字符，任何改动都会显示成“整行全改”，评审时看不出改了什么，也没法用编辑器正常定位。

## 需方案

以下为 [需方案] 项；涉及协议、数据迁移、跨端契约或产品取舍，先定方案再动手。

- SEC-59BE5（u06）— 任何人都能直接调用予念后端的 26 个 AI 接口，不需要登录，就能读写别人账号里的记忆、看到别人的提示词，还会消耗平台付费的模型额度。
- SEC-3C38A（u06）— AI 服务会照着请求里给的地址去发网络请求，但完全不检查这个地址是不是内网，攻击者可以借它探测并访问服务器内网和云厂商的元数据接口。
- COR-837C2（u02）— 用户一句话里说出的几件事，系统只记得住最后一件，其余会被悄悄覆盖，每次重新整理记忆还会来回改写并重复同步向量库。
- REL-438AC（u08）— 用户在流式回复中途切走或关窗时，服务端只是不再往前端写字，仍在继续向模型要完整回答，这段没人看的生成照常消耗额度，也让这次断开变成上游的一条失败记录。
- REL-5D862（u10）— 后端模型运行时的错误处理、熔断和流式契约全靠 1222 个测试兜着，但 CI 从不跑它们，而且按项目给的命令从仓库根执行会直接收集失败——这层保护目前是关着的。
- SEC-D0C34（u04）— 仓库里 100 多个运维脚本连生产服务器时关掉了 SSH 指纹校验，还用 root 口令登录，网络上一旦被冒充，生产机和口令都会落到别人手里。
- COR-28242（u01）— 消息序号只记在 Redis 计数器里，Redis 一旦被清空或换实例，序号会从 1 重来，跟数据库里已有的序号撞车，之后这个会话再也发不出消息。
- COR-76C81（u06）— 当模型先聊一句再调用工具时，用户会看到两段话直接粘在一起，中间没有空格或换行，读起来像一句错话。
- COR-EF936（u02）— 关系分数是“读出来—改—整行写回”的，后台每半小时的衰减任务和用户聊天同时跑时会互相覆盖，导致刚加的亲密分或刚算的衰减凭空丢失。
- REL-2959C（u01）— 群聊里角色回复是后台异步跑的；一旦中途抛异常，前端既收不到「本轮失败」也收不到「本轮结束」，那几个角色会一直显示正在输入。
- REL-3785E（u01）— 回答打到一半时模型或网络出错，前端已经把半截回答显示给用户了，但这段文字不会存进会话，用户一刷新就消失，记忆和关系分也只当这轮没发生。
- MNT-1C7A4（u02）— 角色广场每个角色的设定文案同时写在 Java 代码和数据库插入脚本里，程序只会用 Java 那份，所以有人改数据库里那份会以为生效、其实完全没用，等于给自己埋了个假开关。
- MNT-695AC（u04）— 仓库里堆着上百个一次性运维脚本，其中同一个部署动作有七个版本、同一段逻辑被复制了多份，还有硬编码的生产服务器地址和作者本机路径，别人 clone 下来根本跑不通，也不知道哪个才是“正统”。
- MNT-75E6F（u09）— 桌面端的启动文件把所有窗口、托盘、更新、桥接逻辑塞在一个 3000 多行的文件里，谁改动都要在整份文件里找位置，而且现有测试完全碰不到它，改错了只能靠手工点界面发现。
- MNT-9B09F（u07）— 流式回复的解析规则被写了三遍（前端共享模块、Java 转发层、Python 运行时），而且有个前端测试自己在测试文件里抄了一份解析函数来测，生产代码改坏了它也照样绿。
- MNT-B0A65（u03）— 同一个“支持哪些语言”的事实被 Java、Python、前端常量、前端 i18n 各写了一遍，结果四份都不一样：繁体中文包已经写好却从来没被加载，英文和日文界面有 6 处会直接显示 key 名而不是人话。
- SEC-2C21F（u09）— 桌面客户端页面允许执行动态生成的脚本，万一以后某处渲染了用户内容又被绕过过滤，攻击者就能在客户端里拿到登录凭证。
- SEC-65BBD（u10）— 私聊语音和用户克隆的音色文件走的是匿名下载地址，链接一旦被截图、日志或第三方拿到，别人就能长期下载这段私人录音。
- MNT-020D0（u09）— 启动耗时统计被抄成了两份，一份用系统时钟、一份用高精度时钟，两边数字不可比，排查启动慢的时候容易被误导。
- MNT-2D162（u05）— 文档要求数据库迁移脚本只能是纯英文，但仓库里超过一半的迁移文件写了中文注释和中文数据，换一台编码不一致的机器或客户端就可能读成乱码。
- MNT-F6920（u07）— 有几个前端测试不是验证功能，而是把页面源码当文本读进来找字符串，谁调一下顺序或改个缩进就会莫名其妙变红，和用户实际体验无关。

## 测试证据

按 scope 的四条 canonical 命令逐条记录：

1. `npm --prefix admin test`：failed（未真正运行）。admin 没有安装 node_modules，命令返回 `'vitest' 不是内部或外部命令`；因此 admin 的 1 个测试文件也没有被执行。这不是产品代码失败，而是测试环境缺失。
2. `mvn -q -f backend/pom.xml test`：passed。531 个测试，0 failure、0 error，覆盖 10 个 Maven 模块。
3. `pytest -q backend-agent`：failed（未真正运行）。从仓库根执行出现 48 个 collection error，均为 `ModuleNotFoundError: No module named 'app'`；进入 backend-agent 后仍缺 aiomysql/respx。security 专家用 backend-agent/.venv 跑出 1157 passed，但这不是 scope 指定的 canonical 命令，不能作为四条门禁已通过的证据。
4. `npm --prefix frontend test`：passed。71 个测试文件、417 个用例全绿；本地未触发 updater 的并行 flake，但 CI 仍在用并行模式（见 REL-5D862）。

补充：REL-5D862 本身就是“Python 测试不在 CI 且根目录跑不通”，因此第 3 条的失败既是测试环境问题，也是被审查出来的高优先级缺口，不应被“换个 venv 就能过”掩盖。

## 修复顺序

1. P0（当天）：先修 SEC-59BE5 与 SEC-3C38A。前者把 /api/agent/ 收回鉴权并补内部签名，后者在 Python 出站客户端做与 Java 等价的白名单 + 解析后 IP 校验；两条一起修，否则 SSRF 仍可被匿名触发。
2. P0（当天）：修 REL-0486A 与 REL-438AC。把 CancelledError/GeneratorExit 排除出熔断失败，并在 Java 侧保存 Disposable、客户端断开时 dispose 上游流；这两条直接决定“一个用户切页会不会拖垮所有人”。
3. P1（本周）：修 COR-837C2 与 COR-DFF3C。前者统一 Java/Python 的 source_hash 口径并迁移历史行；后者把嵌套括号改成区间合并后一次性拼装，先补三组已复现的用例。
4. P1（本周）：修 SEC-D0229、SEC-D0C34、SEC-061DE。分别处理 Jasypt 明文回退、运维脚本 SSH 主机密钥校验、CI 依赖扫描放行与 action 固定 SHA。
5. P2（本迭代）：处理状态一致性（COR-EF936、COR-28242、REL-2959C、REL-3785E）与前端失败可见性（REL-A64C9、REL-7FC42）。这些需要迁移、并发控制或产品语义确认，不宜和 P0 混在一个提交里。
6. P3（排期）：处理可维护性项，优先 MNT-75E6F（main.js 拆分）与 MNT-9B09F（SSE 单一实现 + golden 帧），其余脚本清理、i18n、admin 格式化按维护窗口分批做。

## 跨单元系统性问题

1. 运行时边界在 agent 入口失守：SEC-59BE5 让 /api/agent/* 绕过 Sa-Token；SEC-3C38A 又让请求体里的 baseUrl 直达内网。两条合起来，Java 侧的 OutboundUrlValidator 和 SsrfPinningClientFactory 等于只保护了 Java 自己的出站，没有保护 Python 运行时。
2. 断链路的两次记账错误：REL-438AC 让 Java 断开后上游继续生成，REL-0486A 又把 Python 侧收到的取消计成断路器失败；一次用户切页会同时造成“白烧 token”和“污染共享熔断器”。
3. 同一语义多套实现：前后端各有一套内心独白剥离（COR-DFF3C/COR-42F8E），SSE 解析有三份（MNT-9B09F），启动 profiler 有两份（MNT-020D0），语言清单有四份（MNT-B0A65）。这不是风格问题，而是修复时容易只改一边、线上表现不一致。
4. 状态一致性缺少数据库兜底：记忆 source_hash 碰撞（COR-837C2）、relationship 整行覆盖（COR-EF936）、消息 seq 只靠 Redis（COR-28242）都属于“进程内看起来对，换实例/并发就丢”的问题。
5. 测试体系与 CI 脱节：Python 1200+ 用例、admin 后台、前端串行口径都没有进入 CI；这使上述高风险修复缺少回归保护（REL-5D862/REL-35BDA）。
6. 运维脚本既是安全面也是卫生面：114 个脚本关闭 SSH 主机密钥校验并以 root 直连生产（SEC-D0C34），同时 97 个下划线脚本、7 个部署变体、109 处硬编码 IP 留在仓库（MNT-695AC）。两条同文件但根因不同，需要一起清理。

## 覆盖缺口与未验证

- 性能维度未运行：本次没有跑 /logi-perf，不能对启动耗时、热路径复杂度、包体、内存增长下结论。
- admin 与 pytest 的 canonical 命令没有真正跑通：admin 缺 node_modules；pytest 从仓库根 collection 失败，backend-agent 内缺 aiomysql/respx。已用 .venv 得到 1157 passed 作为参考，但不替代 canonical 结果。
- 抽样单元：correctness 的 u04/u09/u10/u12、security 的 u01–u04/u07/u09、reliability 的 u02/u04/u05/u07/u10–u12、maintainability 的 u01–u07 为 sampled；每个单元至少读了 30 个文件且 core 全覆盖，但未逐行审阅全部 1100 个文件。
- 两个 dropped 项（REL-E07C8、MNT-F7190）与 9 条 deferred 未在报告正文展开；deferred 含 backend-agent 直写 MySQL、moments 评论锁 owner token、衰减轮转饥饿、脚本 get_pty、Sa-Token 30 天等，已在 verdict.json 保留供后续分派。
- 未验证项：没有做真实公网渗透、没有对生产 Docker 网络做 SSRF 实测、没有跑打包后的 Electron 冒烟；SEC-59BE5/SEC-3C38A 的证据来自源码路径与本地契约，不是生产利用结果。

## 专家报告索引

- [正确性审查报告](handoff/correctness/correctness.md) — 6 条，含记忆 hash、嵌套括号、关系并发、消息 seq。
- [安全审查报告](handoff/security/security.md) — 7 条，含 agent 匿名入口、SSRF、Jasypt、运维脚本、CI 供应链。
- [健壮性审查报告](handoff/reliability/reliability.md) — 10 条，含断路器污染、上游不取消、刷新令牌误判、群聊无失败出口。
- [可维护性审查报告](handoff/maintainability/maintainability.md) — 11 条，含 main.js 上帝模块、i18n 漂移、SSE 三实现、scripts 堆积。

---

需要我帮你开始直接修复吗？还是你拿上报告去新开一个会话施行修复？
