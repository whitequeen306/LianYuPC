# 安全审查报告

> 审查对象：予念 YuNian PC（`C:/Users/hp/Desktop/LianYu-PC`）· 项目级全量审查（非 diff）· 共 1108 个文件 / 139994 行 / 12 个审查单元。

## 一、范围与覆盖

本次审查覆盖整个仓库：后端 Spring 多模块、Python Agent 运行时、Electron 客户端、管理台、部署网关、Flyway 迁移与发布脚本。每个单元精读 32 个文件（含该单元全部 `core` 路径），其余文件按模式做跨文件扫描（密钥/私钥、SQL 字符串拼接与 `${}`、出站 HTTP、`v-html`/`innerHTML`、上传与对象键、限流与鉴权注解、CSP 与窗口安全配置）。

| 单元 | 内容 | 状态 | 精读文件数 | 说明 |
| --- | --- | --- | --- | --- |
| u01 | backend/lianyu-service (1/3) | sampled | 32 | lianyu-service 业务层：Controller/Service 全部使用 StpUtil 登录态，ApiKeyVaultService 只回掩码 Key，CharacterService 唯一拼接 SQL 的入参为 Long 已确认不可注入；重点核对 agent 端口与 AI 服务调用。 |
| u02 | backend/lianyu-service (2/3) | sampled | 32 | lianyu-service DTO/会话/广场：DTO 不带可越权字段，评论与文件上传取登录态 userId，VaultEntryResponse 仅返回掩码。 |
| u03 | frontend/src (1/2) | sampled | 32 | frontend/src API 层：token 只存渲染进程内存（secureToken.js），httpCore/auth 通过 Electron IPC bootstrap，未发现 localStorage 写 token。 |
| u04 | scripts (1/2) | sampled | 32 | scripts 运维脚本：SSH 口令来自 DEPLOY_SSH_PASSWORD 环境变量，无硬编码生产口令；TestPass1a/1216qwer 属测试与历史自检脚本。 |
| u05 | backend/lianyu-dao | sampled | 32 | lianyu-dao：逐份核对 V1 schema 与业务迁移，仅 V22 团队 Key 池以密文入库；mapper SQL 全部参数化。 |
| u06 | frontend/src (2/2) | sampled | 32 | frontend/src 页面层：v-html 仅用于静态图标与经 DOMPurify 白名单的评论内容，未发现用户内容进入 innerHTML。 |
| u07 | backend-agent | sampled | 32 | backend-agent：路由鉴权、工具上下文注入、出站网关、桌面感知与记忆读取逐条核对，三条高危（无鉴权/SSRF/额度消耗）出自本单元。 |
| u08 | backend/lianyu-web | sampled | 32 | lianyu-web：12 个 Controller 全量核对鉴权注解与限流，重点复核 ClientIpResolver 的 X-Forwarded-For 取值与观察接口限流。 |
| u09 | frontend/electron | sampled | 32 | frontend/electron：窗口安全、API 代理白名单与 token 注入、更新器验签、QQ/微信桥出站校验与本地端口绑定。 |
| u10 | 其他模块（(root)、.github、admin 等） | sampled | 32 | 杂项：.env.example、admin SPA、CI 工作流、Dockerfile、网关 nginx、CSP、ASR 服务与部署脚本。 |
| u11 | backend（小模块合集 59 文件） | sampled | 32 | lianyu-admin 管理端与 service 单测：管理会话 token 哈希校验、审计脱敏、TOTP、权限点与发布导入器。 |
| u12 | 其他模块（backend/lianyu-app、backend/lianyu-common、backend 等） | sampled | 32 | 其余后台模块：公共工具、OutboundUrlValidator、安装器、Pets/脚本的凭据来源。 |

状态说明：`reviewed` 表示已精读关键文件并做针对性检查；`sampled` 表示该单元体量大于精读量，做了 key 文件精读 + 全量模式扫描，结论覆盖风险面但不等于逐行通读；本次无 `skipped` 单元。

## 二、发现（按严重度排序）

### 严重（1）

- [critical] [u07] [plan] SEC-01 — 任何人只要把请求里的用户编号换成别人，就能读到对方的聊天记忆和日记；这些内部接口目前没有登录校验。
详细问题：Agent 运行时缺少 X-User-Id 时直接放行，body.userId 可任意指定导致跨用户读取记忆
位置：backend-agent/app/main.py:150 · 证据：backend-agent/app/main.py:146-151 只做“有头才比对”：user_id_header: str | None = Header(default=None, alias="X-User-Id") → if user_id_header and str(body.user_id) != user_id_header: raise HTTPException(403)。同一写法出现在 :206(chat-stream)、:313(session-summary)、:365(text/chat)、:476、:504，缺头即跳过全部校验。deploy/api-gateway/nginx.conf:252-274 的 location /api/agent/ 没有 auth_request 也没有 internal：set $agent_upstream http://agent:8001; rewrite ^/api(/agent/.*)$ $1 break; proxy_pass $agent_upstream; ⇒ 公网可直达 8001。body.userId 会一路进入工具上下文：app/main.py:228-240 tool_context={"_user_id": body.user_id, …}；app/tool_loop.py:98-100 merged.update(tool_context) 覆盖模型参数后 spec.handler(merged)；app/tools/memory_search_tool.py:158 user_id = (arguments or {}).get("_user_id") 再执行 :52/:62 的 WHERE character_id = %s AND user_id = %s。
影响：攻击者无需账号即可枚举 userId 读取他人记忆摘要、日记、动态与评论，内容以模型可读的明文形式返回；还能借平台额度生成任意回复。Java 侧 Sa-Token 鉴权被整条绕过，等于把跨租户读取能力挂在公网网关路径上。 · 修复：在 Agent 侧引入真正的服务间鉴权（共享密钥签名 + 时间窗 + 重放防护），并由网关默认拒绝外部直连 /api/agent/。

### 高（3）

- [high] [u07] [plan] SEC-02 — 外部调用者可以指定“模型地址”，服务就会照这个地址发请求，内网服务因此可能被探测，返回内容还会被带出来。
详细问题：Agent 接受调用方自填 baseUrl/apiKey，出站请求可打到任意主机并回显响应
位置：backend-agent/app/gateway.py:92 · 证据：backend-agent/app/main.py:284-285 TextChatBody.base_url/api_key 来自请求体（session-summary :347-348、chat-stream :213 的 text_base_url/text_api_key 同理）；backend-agent/app/gateway.py:92 normalize_openai_base_url 仅做字符串归一（去尾斜杠、去 /v1、固定追加 /v1/chat/completions），随后经 app/http_client.py new_client() 用 httpx 发出请求；main.py:369 附近把模型返回的 content 直接回给调用方。AgentRuntimeClient.java 注释称“仅访问固定地址 agent:8001、无 SSRF 面”，SSRF 面其实在 Python 的这一段。
影响：结合 SEC-01 无需登录即可让服务向内网任意 HTTP 服务发起 POST（backend:8080、minio:9000、milvus、运维面板等），把 2xx 响应体带回；也可作为数据外带通道，把请求体里的内容送到攻击者服务器。 · 修复：Python 出站对 base_url 做与 Java OutboundUrlValidator 等价的白名单校验（禁私网/回环/元数据地址，解析结果钉 IP），并只允许 vault 中登记的域名。

- [high] [u08] [plan] SEC-03 — 客户端的限流按“来源 IP”算，而这个 IP 可以从请求头里随便写，攻击者每次换一个就能绕过注册和上传的次数限制。
详细问题：默认信任 X-Forwarded-For 首个值，公网可伪造来源 IP 绕过按 IP 的限流
位置：backend/lianyu-web/src/main/java/com/lianyu/web/util/ClientIpResolver.java:28 · 证据：ClientIpResolver.java:16 @Value("${lianyu.security.trust-forwarded-for:true}") 默认为 true；:24-28 if (trustForwardedFor) { String forwarded = request.getHeader("X-Forwarded-For"); … int comma = forwarded.indexOf(','); return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim(); } —— 取的是客户端可写的第一段。backend/lianyu-app/src/main/resources/application.yml:172 trust-forwarded-for: ${LIANYU_TRUST_FORWARDED_FOR:true}，.env.example 与 docker-compose.yml 均未设置该变量。deploy/api-gateway/nginx.conf:306 `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;` 把真实 IP 追加在客户端值之后，因此第一段永远是攻击者可控内容。使用方：AuthController.java:65/:78（登录/注册/验证码，30 次/IP/分钟、3 次/日/IP 注册额度）、ObserveController.java:57（30 次/小时/IP）、头像上传 20 次/日/IP。
影响：所有按 IP 的额度（注册 3 次/日、头像 20 次/日、桌面感知 30 次/小时、认证 30 次/分钟）在公网均可被任意绕过，可批量注册、刷模型额度、规避风控；按用户名维度的限额仍生效但无法挡住换号。 · 修复：改为只信任网关写入的 X-Real-IP（或取 XFF 最后一段），并把 trust-forwarded-for 默认改为 false，部署环境显式打开。

- [high] [u10] [direct] SEC-04 — 少配一个环境变量，用户自己填的模型密钥就会以明文存进数据库，而系统只在启动日志里警告一句。
详细问题：未配置 LIANYU_MASTER_KEY 时 Jasypt 静默降级为明文，用户模型 API Key 直接明文入库
位置：backend/lianyu-security/src/main/java/com/lianyu/security/util/JasyptUtil.java:63 · 证据：JasyptUtil.java:23 无主密钥时 log.warn("LIANYU_MASTER_KEY not set — Jasypt encryption disabled. Set env var before production use.") 然后不建 encryptor；:61-65 public String encrypt(String plaintext) { … if (currentVersion == null || encryptors.isEmpty()) { log.warn("No Jasypt encryptor available, returning plaintext"); return plaintext; } … }（:69-70 还有第二处同样回退）；:75-78 decrypt 在 encryptors 为空时原样返回。写入方 EncryptedStringTypeHandler 与 ApiKeyVaultService 都直接调用 encrypt，因此 api_key_vault.api_key_encrypted 会存明文。
影响：运维漏配一个变量，全部用户自填的模型 Key 会以明文落库；数据库备份、只读账号、误导出或任意 SQL 读取都会造成 Key 泄露，而调用方看不出任何异常（只有启动时一条 warn）。 · 修复：加密路径失败即关闭（fail closed）：无 encryptor 时抛异常而不是返回明文，并在启动阶段对缺失主密钥做硬校验。

### 中（4）

- [medium] [u11] [direct] SEC-05 — 管理员密码一旦泄露，第二步验证码可以无限次尝试，因为只有密码输错才会计数锁定。
详细问题：管理员登录 TOTP 错误不累计失败次数，6 位验证码可在线穷举
位置：backend/lianyu-admin/src/main/java/com/lianyu/admin/identity/AdminAuthService.java:43 · 证据：AdminAuthService.java:37-39 仅密码不匹配分支执行 UPDATE admin_user SET failed_attempts=failed_attempts+1, locked_until=CASE WHEN failed_attempts+1>=5 THEN DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 15 MINUTE) …；:42 缺 otp 时返回 otpRequired；:43 if (otpRequired && !TotpUtil.verify(jasypt.decrypt(String.valueOf(row.get("totp_secret"))), request.otp(), System.currentTimeMillis())) throw new IllegalArgumentException("用户名或密码错误") —— 不写 failed_attempts、不锁定；:49 成功后才清零。
影响：攻击者拿到管理员密码（复用/撞库/钓鱼）后可对 6 位 TOTP 做在线穷举（成功概率随请求数线性上升），5 次锁定策略形同虚设；管理员后台可读用户列表、发放客服授权、发布客户端版本。 · 修复：TOTP 校验失败与密码失败同等计数，达到阈值同样锁定并写审计。

- [medium] [u07] [plan] SEC-06 — 外部任何人都能反复调用识图接口，用平台自己的模型额度生成内容，花的是运营方的钱。
详细问题：无鉴权的桌面感知接口会消耗平台自己的多模态 Key，且没有速率限制
位置：backend-agent/app/main.py:143 · 证据：backend-agent/app/main.py:143-151 /agent/desktop-observe 无鉴权（同 SEC-01 的头比对逻辑）；app/vision.py:69-70 base_url=settings.multimodal_base_url、api_key=[REDACTED:secret] DashScope Key）；app/desktop_observe.py:86 只有调用方未带 text_base_url/text_api_key 时才回落到平台 Key；main.py:76-79 observe_limiter 是 asyncio.Semaphore（并发上限），不是速率限制。
影响：外部可无限次把任意图片与 windowTitle 送进平台模型，按调用量直接消耗运营方额度（成本攻击），并让平台承担内容风险；桌面截图内容也会流向模型供应商。 · 修复：该端点只允许通过内部签名从 Java 转发，并加用户/IP 双维度配额与单次图片大小上限。

- [medium] [u05] [plan] SEC-07 — 十个真实的模型密钥以加密形式写进了代码库，而解密用的那把钥匙全组共用，谁拿到代码和钥匙谁就能用这些额度。
详细问题：团队默认 Key 池的十份密文随迁移入库，解密主密钥在全体开发间共享
位置：backend/lianyu-dao/src/main/resources/db/migration/V22__team_default_vault_pool_encrypted.sql:1 · 证据：V22__team_default_vault_pool_encrypted.sql:1-13 连续 10 条 UPDATE api_key_vault SET api_key_encrypted='<base64 密文>', key_version='v1', base_url='https://clove.dpdns.org', model_default='deepseek-v4-flash' WHERE id=N AND vault_scope='DEFAULT'；文件头注释写明用团队共用 LIANYU_MASTER_KEY 解密（“新同事 clone 后：填好 .env 的 LIANYU_MASTER_KEY”），本机 .env:41 即为该主密钥（.env 被 gitignore，未入库）。JasyptUtil.java:52 使用 PBEWithHMACSHA512AndAES_256 + 1000 次迭代，迭代次数偏低。
影响：任何同时拿到仓库与团队主密钥的人（含离职人员、泄露的 .env、CI 缓存）都能解出 10 个真实模型 Key 并直接消耗额度；主密钥一换，所有环境所有密文同时失效，只能全量重新加密与轮换。 · 修复：把默认 Key 池移出仓库，改为部署时注入；密文与主密钥分离保管，并按环境使用不同主密钥。

- [medium] [u09] [plan] SEC-08 — 客户端安装更新包前只比对一个同样来自更新服务器的校验值，等于自己给自己盖章；一旦更新服务器被控，就会静默装上任意程序。
详细问题：自动更新只校验同源清单里的 sha512，清单签名校验默认关闭
位置：frontend/electron/updater/updater.js:81 · 证据：frontend/electron/updater/updater.js:81-90 async function verifyManifestOrThrow(ymlText, ymlUrl, apiOrigin) { const publicKeyBase64 = resolveManifestPublicKey(); if (!publicKeyBase64) { logger.warn('updater', 'latest.yml signature check disabled: no updateManifestPublicKey'); return } … } ⇒ 无公钥时直接跳过验签；frontend/.env.production.cloud 中 VITE_LIANYU_UPDATE_PUBKEY 被注释掉（注释：留空 = 客户端暂不强制验签）；:57 的 sha512 取自同一份 latest.yml，:333 verifyInstallerSha512 只比对两者一致性；frontend/package.json:82 signAndEditExecutable: false ⇒ 安装包无 Authenticode 签名。
影响：控制更新源（对象存储凭据、4443 端口服务、发布流水线）即可推送任意安装包，客户端下次检查更新即静默下载安装；TLS 证书固定只能防中间人，防不了源端被改或内鬼。 · 修复：按既定三步走完成强制验签：发布 .sig、填充公钥、把缺公钥从“跳过”改为“拒绝更新”。

### 低（2）

- [low] [u10] [direct] SEC-09 — 构建流水线用的第三方组件没有锁定版本，依赖漏洞扫描报错也不会让构建失败，等于没人管。
详细问题：CI 以可变 tag 引用第三方 Action，依赖漏洞扫描失败不阻断流水线
位置：.github/workflows/ci.yml:48 · 证据：.github/workflows/ci.yml:14/28/45 actions/checkout@v4、:15 actions/setup-java@v4、:29 actions/setup-node@v4、:48 google/osv-scanner-action/osv-scanner-action@v2.0.3 全部按 tag/分支引用；:47 continue-on-error: true 使依赖漏洞扫描失败不影响 job 结论。
影响：上游 action 仓库被打新 tag 或劫持时，CI 会执行攻击者代码并接触仓库令牌与发布凭据；osv-scanner 的告警被吞掉，已知漏洞依赖可以长期留在主干。 · 修复：Actions 固定到 commit SHA，漏洞扫描改为阻断（或至少单独出 job 并告警到人）。

- [low] [u10] [direct] SEC-10 — 页面一旦被人塞进恶意脚本，这个脚本能用 eval 拼载荷，还能把读到的内容发到任意外部网站。
详细问题：渲染进程 CSP 允许 unsafe-eval，且 connect-src 放开到任意 https 域名
位置：frontend/index.html:7 · 证据：frontend/index.html:7 <meta http-equiv="Content-Security-Policy" content="default-src 'self'; script-src 'self' 'unsafe-eval'; … connect-src 'self' https: wss:; object-src 'none'; base-uri 'self'; frame-ancestors 'none'">；frontend/launcher.html:7 同样的 script-src 'unsafe-eval'。渲染进程内存中持有登录 token（frontend/src/utils/secureToken.js），并可通过 preload 暴露的 IPC 调用主进程能力。
影响：任何 DOM XSS（社区/评论渲染、第三方依赖、未来新增富文本）都会放大为可 eval 任意代码、可把页面内容与渲染进程内 token 发往任意 https 主机的完整接管。 · 修复：去掉 unsafe-eval，把 connect-src 收紧到实际使用的 API/更新域名。

## 三、已查未发现问题的区域

- **注入（injection）**：全部 MyBatis mapper 无 `${}` 拼接，JDBC 调用均参数化；`CharacterService.java:195` 唯一字符串拼接 SQL 的入参为 Long，不可注入；Agent 侧 SQL 全部 `%s` 占位；`ffmpeg`、ASR 的 `subprocess` 均以参数数组调用，无 shell 拼接。已查未发现问题。
- **数据暴露（exposure）**：`ApiKeyVaultService` 只返回掩码 Key（前 3 + 后 4），全量 Key 仅在创建时返回一次；错误响应统一为通用错误码，无堆栈外泄；公开文件接口仅允许 `SAFE_OBJECT_KEY` 白名单内的对象键，`updates/../secret` 在测试中被断言拒绝。已查未发现问题。
- **对象存储与上传**：头像走魔数校验 + 解码 + 重编码为 PNG；聊天图片限 5MB 且仅允许 image/* 类型；公开下载按存储类型收敛 Content-Type 并拒绝 html/svg/js。已查未发现问题。
- **WebSocket**：STOMP CONNECT 必须携带 token，SUBSCRIBE/SEND 需已认证，群组 topic 校验归属，消息体上限 512KB；语音 WS 首帧鉴权，query token 仅作旧客户端回退。已查未发现问题。
- **管理员后台**：`/api/admin/v1/**` 独立拦截器要求 `admin:` 前缀登录态，每个接口显式 `authorization.require(...)`，会话表存 token 哈希、8 小时过期，审计日志脱敏；`AdminBootstrapService` 无默认口令（仅在配置用户名/口令且无超级管理员时创建）。已查未发现问题。

## 四、未验证与抽样说明

- 12 个单元全部标记为 `sampled`：每单元精读 32 个文件（含全部 core 路径），未逐行通读 1108 个文件；大文件（如 `frontend/electron/main.js` 3077 行、`backend-agent/app/main.py` 1279 行）按函数与风险点定位阅读。
- `npm --prefix admin test` 未运行：`admin/node_modules` 未安装，`vitest` 不可执行；管理台前端的安全结论来自静态阅读而非用例验证。
- `pytest -q backend-agent` 需在 `backend-agent` 目录使用仓库自带 venv 运行；仓库根直接执行会因包路径与依赖缺失失败。
- 依赖漏洞未做本地扫描（CI 的 osv-scanner 未在本次执行），供应链结论限于 CI 配置与更新链路，不含 CVE 清单。
- 运行时/生产环境行为（真实 `.env`、线上 nginx 配置是否与仓库一致、MinIO 凭据权限）无法在本机验证。

## 五、建议修复顺序与跨专家转交

1. **立即（当天）**：SEC-01 Agent 接口鉴权与网关收口——当前公网可跨用户读取记忆，是唯一可被直接利用的越权。
2. **本周**：SEC-02 出站 SSRF 白名单、SEC-04 Jasypt fail-closed、SEC-03 XFF 取值修正（三者改动小、影响面清晰，可并行）。
3. **两周内**：SEC-05 TOTP 计数、SEC-06 观察接口配额、SEC-07 默认 Key 池移出仓库并轮换供应商 Key。
4. **随下个客户端版本**：SEC-08 强制更新验签、SEC-10 CSP 收紧、SEC-09 CI 固定 SHA。

跨专家转交：

- → **reliability**：Agent 侧 `/agent/*` 无速率限制（`observe_limiter` 仅为并发信号量），额度耗尽与雪崩风险需可靠性视角复核。
- → **correctness**：`X-Forwarded-For` 取首段与网关 `$proxy_add_x_forwarded_for` 追加语义不一致，属限流口径错误，建议与部署/网关一并修正。

---

完整结构化结果见同目录 `security.json`。
