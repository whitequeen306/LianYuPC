# 安全审查报告

**项目**：予念 YuNian（PC 端）· 全项目审查（非 diff）  
**审查单元**：12 个 · **文件**：1100 个 / 135978 行 · **发现**：7 条（critical 1 / high 1 / medium 3 / low 2）  
**审查口径**：认证、授权/租户隔离、注入、浏览器安全、上传/SSRF、密钥、加密、数据暴露、依赖与供应链  
**方法**：12 个单元逐文件跑安全模式扫描（命令执行 / 反序列化 / HTML 注入 / TLS 关闭 / 明文 http / 密钥字面量 / DSN / token 落 localStorage / CORS / 弱随机），再对核心文件与命中点人工精读；只报告可复现、可达的弱点。

## 1. 范围与覆盖

| 单元 | 范围 | 状态 | 读取文件数 | 说明 |
| --- | --- | --- | --- | --- |
| u01 | backend/lianyu-service (1/3) | sampled（全单元模式扫描 + 核心文件精读） | 32 | lianyu-service 第一半（agent 端口/图编排/工具）。全单元 120 文件按安全模式扫描；抽查进程调用、出站 URL 与日志暴露，未发现注入/命令执行面。 |
| u02 | backend/lianyu-service (2/3) | sampled（全单元模式扫描 + 核心文件精读） | 32 | lianyu-service 第二半（voice/ai/dto）。重点看 ffmpeg 调用与本地 TTS 端点校验：ProcessBuilder 用参数数组、临时文件路径服务端生成，无命令注入；LocalTtsEndpointValidator 只允许本机/私网。 |
| u03 | frontend/src (1/2) | sampled（全单元模式扫描 + 核心文件精读） | 32 | frontend/src 第一半（api 层/composables）。核对 token 存放（IPC + safeStorage，不落 localStorage）、innerHTML 用法（仅清空）、Math.random 均为动画抖动非安全随机。 |
| u04 | scripts (1/2) | sampled（全单元模式扫描 + 核心文件精读） | 32 | scripts 第一半（运维/端到端脚本）。发现 114 个脚本用 paramiko.AutoAddPolicy 关闭主机密钥校验并以 root 口令登录，作为一条 secrets 发现上报。 |
| u05 | backend/lianyu-dao | reviewed（关键文件精读 + 定向检查） | 32 | lianyu-dao（实体/mapper/Flyway）。逐文件核查无 ${} 拼接、无动态表名、无明文密钥列；迁移脚本仅 DDL 与种子数据。 |
| u06 | backend-agent | reviewed（关键文件精读 + 定向检查） | 32 | backend-agent 全部 102 文件。逐端点核对鉴权、出站客户端与数据读取过滤；SEC-001/SEC-002 两条发现均落在此单元。 |
| u07 | frontend/src (2/2) | sampled（全单元模式扫描 + 核心文件精读） | 32 | frontend/src 第二半（页面/路由/store）。两处 v-html 均为静态常量或经 DOMPurify 的 sanitizeHtml；无 dangerouslySetInnerHTML。 |
| u08 | backend/lianyu-web | reviewed（关键文件精读 + 定向检查） | 32 | lianyu-web 全部 controller/config。逐个控制器核对 StpUtil 取号、会话归属校验、CORS/WS 配置与公开文件下发边界。 |
| u09 | frontend/electron | sampled（全单元模式扫描 + 核心文件精读） | 32 | frontend/electron 68 文件。核对窗口 webPreferences、CSP、外部 URL 白名单、apiProxy 出站白名单与 NapCat 下载 sha256 校验。 |
| u10 | 其他模块（(root)、.github、admin 等） | reviewed（关键文件精读 + 定向检查） | 32 | 根目录/.github/admin/部署配置。核对 compose 端口暴露、网关 location、CI 供应链步骤与 admin 前端 token 存储。 |
| u11 | backend（小模块合集 59 文件） | reviewed（关键文件精读 + 定向检查） | 32 | backend 小模块合集（admin + service 测试）。核对 TotpUtil、管理端登录锁定与测试夹具中的假密钥。 |
| u12 | 其他模块（backend/lianyu-app、backend/lianyu-common、backend 等） | reviewed（关键文件精读 + 定向检查） | 32 | lianyu-app/common/installer/Pets 脚本。核对 OutboundUrlValidator、Jackson handler、安装器与 Python 辅助脚本的出站/外调。 |

覆盖结论：12 个单元全部有结论，无 skipped；`units[].files` 只列了每个单元 32 个代表文件（核心文件全部在内），其余文件由脚本按安全模式逐行扫描，未逐个列名 —— 因此本报告的“已读”指“扫描 + 精读”而非人工逐行通读 1100 个文件。

## 2. 发现（按严重度排序）

- [critical] [u06] [plan] SEC-001 — 任何人都能直接调用维梦后端的 26 个 AI 接口，不需要登录，就能读写别人账号里的记忆、看到别人的提示词，还会消耗平台付费的模型额度。
  详细问题：backend-agent 全部 /agent/* 端点只用可选 X-User-Id 做一致性比对，公网网关无鉴权直转，未认证请求可跨用户读写记忆并消耗平台模型额度
  位置：backend-agent/app/main.py:150 · 证据：main.py 中 18 处同款守卫：`user_id_header: str | None = Header(default=None, alias="X-User-Id")` + `if user_id_header and str(body.user_id) != user_id_header: raise HTTPException(403)`（main.py:150/206/313/365/476/504/663/735/789/842/862/918/988/1064/1148/1182/1224/1260），header 缺省时整段校验被跳过。deploy/api-gateway/nginx.conf:252-283 的 `location /api/agent/` 只有 limit_req、OPTIONS 处理、`set $agent_upstream http://agent:8001`、`rewrite ^/api(/agent/.*)$ $1 break` 与 `proxy_pass $agent_upstream`，没有 auth_request、没有 allow/deny、也不重置客户端自带的 X-User-Id；同段注释却写着「外部请求必须带 X-User-Id 且与 body.userId 一致」。docker-compose.yml:239-242 把 api-gateway 的 80/443/4443 发布到全接口，因此该 location 对外可达。backend-agent/app/config.py:57 定义了 `internal_signature_window_seconds`，但全仓没有任何签名校验实现（无 X-LianYu-Signature 校验代码）。
  影响：匿名攻击者可以：1) `POST /api/agent/memory/write`（main.py:982-1006）按任意 userId+characterId 写入伪造记忆，污染他人角色人格；2) `POST /api/agent/prompt/assemble`（main.py:657-700，use_internal_data 默认 True）经 app/prompt_pipeline.py 的 `_fill_internal_data` → app/data_readers.load_memory_context 读取受害者记忆并回传 systemPrompt，构成跨租户读取；3) `POST /api/agent/memory/relay/drain|requeue`（main.py:959/967）与 `/agent/memory/reconcile`（:974）无需任何身份即可触发运维动作；4) `/agent/desktop-observe`（:143-171，body 无 key 时用平台 settings.multimodal_api_key）、`/agent/tts/synthesize`（:1208）、`/agent/tts/stream`（:1242）、`/agent/voice/enroll`（:1140）、`/agent/character/generate`（:837）可被匿名批量调用，直接烧平台 API 额度。 · 修复：在边缘层把 /api/agent/ 收回到鉴权之后，并让 agent 对内部调用做强制签名校验，缺省即拒绝。

- [high] [u06] [plan] SEC-002 — AI 服务会照着请求里给的地址去发网络请求，但完全不检查这个地址是不是内网，攻击者可以借它探测并访问服务器内网和云厂商的元数据接口。
  详细问题：Python 出站请求没有 host/IP 白名单，/agent/chat-stream 等端点的 textBaseUrl 来自请求体，可让 agent 容器向内网任意地址发 POST
  位置：backend-agent/app/gateway.py:92 · 证据：gateway.py:92-110 `normalize_openai_base_url(base_url)` 仅做去尾斜杠/去 /v1 后 `f"{trimmed}/v1/chat/completions"`，没有任何 scheme/host/IP 校验；backend-agent/app/http_client.py:25-37 `new_client()` 只设置 timeout、User-Agent 与 `httpx.AsyncHTTPTransport(local_address="0.0.0.0")`；main.py:186-196 `ChatStreamBody.text_base_url/text_api_key/text_model` 全部来自请求体，main.py:229 直接 `stream_chat_events(..., base_url=body.text_base_url, api_key=[REDACTED:secret], ...)`；/agent/chat-image、/agent/text/complete、/agent/desktop-observe 同样是请求体给 base_url。Java 侧已有 backend/lianyu-common/src/main/java/com/lianyu/common/util/OutboundUrlValidator.java（BLOCKED_HOSTS + DNS 解析后私网地址拒绝）与 backend/lianyu-ai 的 SsrfPinningClientFactory（PinnedDns 防重绑定），Python 侧完全没有对应实现。
  影响：配合 SEC-001（/api/agent/ 免鉴权），攻击者可以让 agent 容器向 mysql:3306、redis:6379、minio:9000、backend:8080 的内部端点、docker 网关 172.18.0.1 以及 169.254.169.254 发起带 JSON body 与自定义 Authorization 的 POST，用于内网端口探测、绕过只监听内网的运维接口、读取云元数据；也可把平台 API Key 发往攻击者控制的地址造成凭据外泄。 · 修复：在 Python 出站客户端统一做与 Java OutboundUrlValidator 等价的 URL 白名单 + 解析后 IP 校验，并固定已解析 IP 防 DNS 重绑定。

- [medium] [u10] [direct] SEC-003 — 如果生产环境漏配加密主密钥，用户的模型 API Key 会被明文存进数据库，而系统只在日志里留一句警告，没有任何人会发现。
  详细问题：LIANYU_MASTER_KEY 缺失时 JasyptUtil.encrypt 直接返回明文，用户模型 API Key 以明文落库且无失败信号
  位置：backend/lianyu-security/src/main/java/com/lianyu/security/util/JasyptUtil.java:61 · 证据：JasyptUtil.java:20-27 构造函数：`String masterKey = System.getenv("LIANYU_MASTER_KEY"); if (masterKey == null || masterKey.isBlank()) { log.warn("LIANYU_MASTER_KEY not set — Jasypt encryption disabled..."); return; }`；encrypt()（:61-72）在 `currentVersion == null || encryptors.isEmpty()` 时 `log.warn("No Jasypt encryptor available, returning plaintext")` 并 `return plaintext`；decrypt()（:76-84）在 encryptors 为空时原样返回密文。调用点 backend/lianyu-service/src/main/java/com/lianyu/service/vault/ApiKeyVaultService.java:[REDACTED:secret] 与 :130 直接把结果写入 api_key_vault.api_key_encrypted。算法本身正确（PBEWITHHMACSHA512ANDAES_256 + RandomSalt/RandomIv + 1000 iterations + v1/v2 多版本）。
  影响：密钥环境变量漏配时：1) 新写入的 API Key 明文入库，DB 备份、只读账号或运维误导出都会直接泄露用户的付费模型密钥；2) 已有密文与明文混存，事后无法区分；3) 只有一条 WARN，启动不失败、无健康检查、无指标，生产可能长期带病运行。 · 修复：让「没有主密钥」成为生产启动失败而不是静默降级，并对存量数据做一次明文检测与迁移。

- [medium] [u04] [plan] SEC-004 — 仓库里 100 多个运维脚本连生产服务器时关掉了 SSH 指纹校验，还用 root 口令登录，网络上一旦被冒充，生产机和口令都会落到别人手里。
  详细问题：114 个运维脚本用 paramiko.AutoAddPolicy 关闭 SSH 主机密钥校验并以 root+密码直连生产机，生产 IP 硬编码在仓库
  位置：scripts/_backend_logs.py:19 · 证据：`grep -rl AutoAddPolicy scripts backend/scripts | wc -l` = 114；scripts/_backend_logs.py:19-20 `client.set_missing_host_key_policy(paramiko.AutoAddPolicy())` + `client.connect(HOST, username=[REDACTED:username], password=[REDACTED:secret], timeout=30)`（password 取 DEPLOY_SSH_PASSWORD）；scripts/_cloud_500_check.py:26-27 `c.set_missing_host_key_policy(paramiko.AutoAddPolicy())` + `c.connect("156.233.228.18", username=[REDACTED:username], ...)`；scripts/diag/_debug_captcha_cases.py:9 `CAPTCHA_URL = "https://156.233.228.18/api/auth/captcha"`；scripts/e2e/lianyu_e2e.py:27 `SSL_CTX.check_hostname = False`。
  影响：AutoAddPolicy 会接受任何主机密钥，DNS 劫持/ARP 欺骗/恶意 Wi-Fi 即可冒充生产机，骗取 root 口令（DEPLOY_SSH_PASSWORD）与后续部署能力；生产公网 IP 与 root 账号硬编码在仓库，任何能读代码的人都拿到入口清单。 · 修复：把运维脚本统一到一套 SSH 连接工厂：校验 known_hosts、改用密钥登录、目标地址从环境变量读取。

- [medium] [u10] [direct] SEC-005 — CI 里的依赖漏洞扫描被设成失败也不影响流水线，而且扫描范围不含 Python 服务，等于依赖漏洞永远不会挡住合并。
  详细问题：CI 依赖漏洞扫描 continue-on-error，action 全部按可变 tag 引用，且扫描范围漏掉 backend-agent
  位置：.github/workflows/ci.yml:47 · 证据：.github/workflows/ci.yml:39-52 deps-audit job：`- name: OSV-Scanner (best-effort)` + `continue-on-error: true` + `uses: google/osv-scanner-action/osv-scanner-action@v2.0.3`，scan-args 只有 `./backend ./frontend ./admin`；同文件 :14/:15/:28/:29/:45 的 `actions/checkout@v4`、`actions/setup-java@v4`、`actions/setup-node@v4` 均为可变 tag，未固定 commit SHA。
  影响：1) 任何高危依赖即使被 OSV 扫到也不会让流水线红，只有人翻日志才可能发现；2) Python 侧 1157 个测试依赖的 backend-agent（uv.lock）完全不在扫描范围内；3) action 按 tag 引用时，上游 tag 被移动或账号被劫持即可在 CI 中执行任意代码并窃取仓库 secrets（部署 SSH 口令就在同一套 secrets 里）。 · 修复：让扫描成为阻断门禁，补齐 Python 依赖，并把第三方 action 固定到 commit SHA。

- [low] [u10] [plan] SEC-006 — 私聊语音和用户克隆的音色文件走的是匿名下载地址，链接一旦被截图、日志或第三方拿到，别人就能长期下载这段私人录音。
  详细问题：私聊语音与克隆音色文件经匿名 /api/public/files/** 下发，URL 泄露后长期可下载且不可吊销
  位置：deploy/api-gateway/nginx.conf:168 · 证据：deploy/api-gateway/nginx.conf:168 `location ^~ /api/public/files/chat-voice/` 直接代理到 backend，无 auth_request；backend/lianyu-web/src/main/java/com/lianyu/web/controller/PublicFileController.java:34 `@GetMapping("/api/public/files/**")` 匿名，返回头含 `Access-Control-Allow-Origin: *` 与 `Cache-Control: public, max-age=86400`；backend/lianyu-service/src/main/java/com/lianyu/service/storage/FileStorageService.java:50-52 的 SAFE_OBJECT_KEY 白名单包含 `chat-voice/` 与 `custom-voices/[0-9]+/`，即这两类私人音频属于「公开可读」集合。
  影响：聊天里的语音消息与用户上传的克隆音色是私人内容，却只要拿到 URL 就能匿名、跨域、无需登录地反复下载，且在浏览器/中间缓存里保留一天；对象名是 32 位随机串，属于「URL 即凭据」，一旦出现在客服截图、日志、第三方分析或分享链接里就无法吊销（只能删对象）。 · 修复：把私人音频移出匿名公开前缀，改为短期签名 URL 或鉴权流式下载。

- [low] [u09] [plan] SEC-007 — 桌面客户端页面允许执行动态生成的脚本，万一以后某处渲染了用户内容又被绕过过滤，攻击者就能在客户端里拿到登录凭证。
  详细问题：打包版渲染进程 CSP 保留 script-src 'unsafe-eval'，XSS 可升级为调用 preload 能力窃取 token
  位置：frontend/electron/main.js:329 · 证据：frontend/electron/main.js:326-334 CSP 为 `default-src 'self'; script-src 'self' 'unsafe-eval'; ...; object-src 'none'; frame-ancestors 'none'`（注释说明 vue-i18n 编译需要 eval）；frontend/index.html:7 同款 meta CSP；preload 暴露了 auth:set-session、apiRequest 等能力（frontend/electron/preload.js 白名单 + main.js assertTrustedSender），渲染进程一旦执行任意脚本即可读取登录态并代替用户发请求。目前 v-html 仅 2 处：frontend/src/pages/MomentsPage.vue:146 经 sanitizeHtml（DOMPurify 白名单），frontend/src/pages/LandingPage.vue:122 为静态常量。
  影响：当前没有可直接利用的注入点，风险是「防线只剩 sanitizeHtml 一层」：任何新增未净化 HTML/第三方脚本都会直接拿到与主进程同等的 API 调用能力（含 lianyu-token），且 'unsafe-eval' 让基于字符串的注入更容易落地。 · 修复：用 vue-i18n 的预编译关闭方式去掉 unsafe-eval，并把渲染进程可调用的 IPC 能力再收窄。

## 3. 已查未发现问题的区域

- **授权/租户隔离（authz）**：`ConversationAccessService.assertUserOwnsConversation` 以 id+userId 查询、查不到即抛；`MemoryController` 的 list/detail/delete、`MomentsController` 全部端点都先 `StpUtil.getLoginIdAsLong()` 再把 userId 下传；`CharacterController` 每个方法都取登录号；管理端 `AdminSecurityConfig.java:12-18` 对 `/api/admin/v1/**` 校验 loginId 必须以 `admin:` 开头。未发现 IDOR / 越权读写。
- **注入（injection）**：全仓无 MyBatis `${}`、无 `createStatement`/字符串拼接 SQL；`backend-agent/app/db.py:39` 的 `_assert_select` 只放行 SELECT 且全部 `%s` 参数化；`CustomVoiceAudioTrimmer` 与 `deploy/asr/server.py:177` 的 ffmpeg 调用均为参数数组、临时路径服务端生成；未发现命令注入、模板注入或反序列化入口。
- **上传与浏览器安全**：头像走 `ImageUploadValidator` 的 magic bytes + ImageIO 解码 + 重编码 PNG；`PublicFileController` 对危险 content-type（html/svg/js）返回 404 并带 `nosniff`；对话图片上传有 5MB 上限与 image/* 白名单。前端仅 2 处 `v-html`：`frontend/src/pages/MomentsPage.vue:146` 经 `frontend/src/utils/sanitize.js` 的 DOMPurify 白名单，`frontend/src/pages/LandingPage.vue:122` 是静态常量。
- **Electron 边界**：`frontend/electron/main.js:360-374` 外部链接只允许 https + 精确 host 白名单，拒绝 `file:`/`data:`/`javascript:`；`frontend/electron/apiProxy.js` 出站只允许 API origin、剥离渲染层自带的 `lianyu-token`/`authorization` 后由主进程注入，并带 TokenBucket 限流与 20MB 体积上限；登录态用 `safeStorage` 加密落盘（`frontend/electron/authSessionStore.js:82-113`）。
- **WebSocket / SSE**：`backend/lianyu-web/.../config/WebSocketConfig.java` 在 CONNECT 阶段强制 token，SUBSCRIBE/SEND 要求已登录，群聊 topic 走会话归属校验；`VoiceDuplexHandler` 在首帧 auth 前丢弃一切业务帧；`HeaderOnlyTokenFilter` 拒绝 URL 里的 token。
- **密钥与凭据暴露**：`git ls-files` 中 `secrets/`、`certs/` 只有 README.md，`.env` 未入库且被 `.gitignore` 忽略，`.env.example` 只有占位；`ApiKeyVaultService` 只在创建时返回一次明文，其余走 `maskApiKey`；日志中无 token/apiKey/password 全文。
- **供应链（除 SEC-005 外）**：`frontend/Dockerfile` 用 `npm ci` + `package-lock.json`，`backend-agent/Dockerfile:11` 用 `uv sync --frozen` + `uv.lock`，构建可复现；NapCat 运行时用 out-of-band `PINNED_RELEASES`（tag + sha256）锚定并在下载后强校验，做法正确。

## 4. 未验证 / 抽样说明

- **sampled 单元**：u01、u02、u03、u04、u07、u09 为 `sampled` —— 该单元全部文件都过了安全模式扫描，但只有 32 个核心/高相关文件做了人工精读；其余文件是“扫描过”而不是“逐行读过”，报告中的结论对这些单元只覆盖扫描能命中的模式。
- **u04（scripts）**：120 个一次性运维脚本只做了模式扫描与个别精读，未逐行审计；SEC-004 的结论基于 114 个文件的 `AutoAddPolicy` 命中与 3 个脚本的精读。
- **测试**：`npm --prefix admin test` 因 admin/ 未安装依赖（无 node_modules，`vitest` 不可解析）无法执行；`pytest -q backend-agent` 按原命令在根目录执行时因系统 Python 缺 `aiomysql` 失败（48 个收集错误），改用 `backend-agent/.venv` 后 1157 passed —— 这两条都记为 failed，不是“安全缺陷”。
- **未做运行时红队**：Electron 渲染进程、网关与 agent 的真实网络拓扑只在静态层面审阅，未做实际渗透；SEC-001 的可利用性依据是代码与 compose/nginx 配置的组合推断，未在线上环境复现。
- **未覆盖**：`docs/logistics/` 与 `.logistics/` 由本工具生成，按要求忽略；第三方镜像（MySQL/Redis/MinIO/Milvus）自身的加固不在本次范围。

## 5. 建议修复顺序

1. **SEC-001**（critical）：先在网关把 `/api/agent/` 收回鉴权，再让 agent 拒绝缺 header 的请求；这是唯一一条“匿名即可读写他人数据”的通道，优先级高于其余全部。
2. **SEC-002**（high）：与 SEC-001 同批上线，否则拿到内部转发权后 SSRF 面更大；直接把 Java 的 `OutboundUrlValidator` 语义移植到 Python 出站客户端。
3. **SEC-003**（medium）：Jasypt 的静默降级改成生产启动失败，并清理存量明文行 —— 影响的是用户付费密钥，越晚修存量越大。
4. **SEC-004**（medium）：运维脚本统一 SSH 工厂 + 密钥登录 + 去硬编码 IP；属于“改一次、全仓受益”，但需要逐脚本回归。
5. **SEC-005**（medium）：CI 门禁改成阻断 + 固定 action SHA + 补 `./backend-agent`，成本最低，可并行做。
6. **SEC-006 / SEC-007**（low）：随下一次版本迭代处理，SEC-006 需要前端播放链路配合，SEC-007 需要先完成 vue-i18n 预编译。

## 6. 跨专家转交

- **correctness**：`backend/lianyu-service/src/main/java/com/lianyu/service/storage/FileStorageService.java:50-52` 的 `SAFE_OBJECT_KEY` 中 `updates/` 分支 `\\.exe` / `\\.yml` 双重转义，正则实际要求一个反斜杠，导致该分支永不匹配（fail-closed）；更新包若经 Java 下发会 404，建议按可用性缺陷处理。
- **reliability**：SEC-001 修复时请同时补“agent 不可达/签名失败”的降级路径与告警；`backend-agent/app/config.py:57` 的 `internal_signature_window_seconds` 目前是死配置，删除或实现需二选一并加启动自检。
- **maintainability**：114 个脚本各自复制了 SSH 建连样板，适合抽 `scripts/_ssh.py` 单一入口；`deploy/api-gateway/nginx.conf` 的 `/api/agent/` 注释与实际行为不一致（注释声称会校验 X-User-Id），修 SEC-001 时一并订正注释。
- **perf**：本次未发现值得转交的性能-安全耦合点。

## 7. 结论

项目的安全基础不差：Sa-Token 拦截范围、管理端二次校验、对象级授权、DOMPurify、Electron 隔离与出站白名单、上传校验、NapCat 供应链锚定都做对了。真正的风险集中在“新增的 Python 服务被当成内网组件、却被公网网关直接暴露”这一处架构错位上 —— SEC-001 与 SEC-002 都源于此，修好这两条，整体风险等级会从 critical 降到 medium 以下。
