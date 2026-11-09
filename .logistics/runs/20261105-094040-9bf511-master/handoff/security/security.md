# 安全审查报告

**项目**：予念 / LianYu-PC（Electron + Spring Boot 多模块 + Python FastAPI agent）
**运行**：`20261105-094040-9bf511-master`，分支 `main`，HEAD `c652e14`，project 模式（全量审查，非 diff）
**范围**：1100 个受审文件 / 12 个审查单元 / 135978 行代码
**结论**：11 条发现（critical 1 / high 5 / medium 3 / low 2）。最严重的一条是 **Python 运行时整层没有鉴权且被公网 nginx 直接反代**；其次是管理端 2FA 可被静默重置、Jasypt 主密钥缺失时明文落库、Python 出站无 SSRF 校验、生产口令硬编码在已跟踪脚本里。

---

## 1. 范围与覆盖

| 单元 | 范围 | 状态 | 实读文件数 | 理由 |
|---|---|---|---|---|
| u01 | backend/lianyu-service (1/3) | reviewed | 14 | 认证/存储/语音链路逐文件读：`AuthServiceImpl`、`AuthRateLimiter`、`CaptchaService`、`FileStorageService`、`CustomVoiceAudioTrimmer/Validator`、`ConversationService` 片段 |
| u02 | backend/lianyu-service (2/3) | sampled | 7 | 120 文件 / 16014 行。按 `selectById` + `eq(userId)` 模式扫描后抽查 `MemoryQueryService`、`ConversationService`、`GroupChatService` 的归属过滤，未逐行通读 |
| u03 | frontend/src (1/2) | sampled | 4 | 前端不承载信任边界，重点核 token 存储与 XSS sink：`secureToken.js`、`utils/sanitize.js`、`api/httpCore.js`；其余靠 `v-html/innerHTML/dangerouslyUseHTMLString` 模式扫描（源码 0 命中） |
| u04 | scripts (1/2) | sampled | 3 | 120 个运维脚本，按 `password/secret/token` 赋值模式全量扫描后抽查 `_prod_verify.py`、`sign_update_manifest.py`、`_has_deploy_pw.py` |
| u05 | backend/lianyu-dao | reviewed | 4 | Flyway：`V1` 的 `api_key_vault`、`V20/V22/V43/V49/V50` schema 演进、`V51` 管理端建表；确认敏感列均为加密列、无明文种子口令 |
| u06 | backend-agent | reviewed | 11 | `main.py`（20+ 端点鉴权模式）、`http_client.py`、`gateway.py`、`memory/writer.py`、`db.py`、`tools/recent_activity_tool.py` |
| u07 | frontend/src (2/2) | sampled | 3 | 95 文件 / 24648 行。抽查 `MomentsPage`（`v-html` 走 `sanitizeHtml`）、`LandingPage`、`useSquareDanmaku`，其余靠模式扫描 |
| u08 | backend/lianyu-web | reviewed | 13 | 14 个 Controller 逐个核对「handler 数 vs `getLoginIdAsLong` 数」，抽查 Memory/Conversation/ApiKeyVault 的归属过滤；`CorsConfig`、`SecurityHeadersFilter`、`ClientIpResolver`、`PublicFileController`、`HeaderOnlyTokenFilter` |
| u09 | frontend/electron | reviewed | 8 | `webPreferences`、`setWindowOpenHandler` + `isAllowedExternalUrl`、`updater.js` + `manifestSignature.js`、napcat/wechat 下载器、`authSessionStore` |
| u10 | (root)、.github、admin 等 | reviewed | 6 | `ci.yml`、`frontend/index.html` 的 CSP、`.env.example`、`.gitignore` 的密钥忽略规则、admin 前端入口 |
| u11 | backend 小模块合集 | reviewed | 10 | 安全关键件全读：`AdminSecurityConfig`、`AdminAuthorizationService`、`AdminAuthService`、`AdminTotpController`、`TotpUtil`、`PasswordConfig`、`AdminBootstrapService`、`SaTokenConfig`、`HeaderOnlyTokenFilter`、`JasyptUtil` |
| u12 | lianyu-app / lianyu-common / backend 等 | reviewed | 7 | `application.yml`、`application-prod.yml`、`OutboundUrlValidator`、`ImageUploadValidator`、`SsrfPinningClientFactory`、`ModelCatalogService` 的调用方式 |

- **reviewed 8 个 / sampled 4 个 / skipped 0 个**，因此整体状态为 `ok`（无跳过单元）。
- 环境说明：本机没有 `rg`，全部用 `grep -rn` / Python 脚本做跨文件模式扫描；4 个测试命令（`npm --prefix admin test`、`mvn -q -f backend/pom.xml test`、`pytest -q backend-agent`、`npm --prefix frontend test`）本次**未执行**，只作为修复后的验证手段列出。

---

## 2. 发现（按模块组织）

### 2.1 Python 运行时（u06：backend-agent）—— 最严重

#### S-01 [critical] agent 全部端点无鉴权：`X-User-Id` 可选即短路，且被公网 nginx 直接暴露

- **位置**：`backend-agent/app/main.py:988`（同模式见 `:206` chat-stream、`:1064` proactive-turn、`:146` desktop-observe 等 20+ 处）；旁证 `deploy/api-gateway/nginx.conf:252-274`
- **证据**：所有端点都写成 `if user_id_header and str(body.user_id) != user_id_header: raise HTTPException(403)` —— **不带 `X-User-Id` 时整个校验被短路**，服务端没有任何会话/令牌校验。生产 nginx 的 `location /api/agent/` 只做 `rewrite` + `proxy_pass $agent_upstream`（`http://agent:8001`），既无 `auth_request` 也不注入/覆盖该头，而 api-gateway 暴露 `80/443`。另外 `/agent/chat-image`（`main.py:740-742`）在请求未带凭据时回落到 `settings.multimodal_base_url/api_key` 平台级视觉密钥。
- **影响**：未认证的公网请求即可调用：记忆写入（污染任意用户 prompt）、记忆重算/中继（触发全量向量重投影，可当 DoS）、声纹注册/删除、TTS 合成、任意 `base_url` 的模型调用，以及白用平台视觉密钥。这是整个系统最外层的信任边界缺失。
- **修复摘要**：agent 只接受来自 Java 的可信调用 —— nginx 侧加 `auth_request` 或不再暴露 `/api/agent/`；Python 侧加统一 HMAC/共享密钥中间件，把「缺头即放行」改成「缺凭据即 401」；去掉 `/chat-image` 的平台凭据回落。

#### S-02 [high] 记忆写入信任请求体里的 `userId/characterId`，缺对象级归属校验

- **位置**：`backend-agent/app/memory/writer.py:122`（入口 `main.py:982-1007`，`/agent/memory/extract-write` `main.py:912` 同构）
- **证据**：`main.py:1003` 把 `body.user_id` / `body.character_id` 原样传给 `write_memories`，`writer.py:122` 直接进入 `_insert_new(...)`（`:145-149`）落 `memory_meta`，既没校验 character 属于该 user，也不校验调用者身份（`X-User-Id` 由客户端自带、nginx 不覆盖）。
- **影响**：任意人可向任意 `(userId, characterId)` 注入任意条「记忆」（`summary`/`importance` 可控），之后被拼进受害者的人设 prompt —— 等于第三方能给别人的 AI 伴侣植入虚假事实或指令式记忆（跨租户写入 + prompt 污染）。
- **修复摘要**：`userId` 只从可信身份源推导，写入前校验 character 归属，`source_msg_ids` 一并校验；该端点从公网摘除。

#### S-03 [high] 出站 HTTP 无 SSRF 校验，模型 `base_url` 可指向内网或云元数据

- **位置**：`backend-agent/app/http_client.py:25`（使用点 `app/gateway.py:288`）
- **证据**：`new_client()` 只设置超时、UA、`local_address`，没有任何目标地址校验；`gateway.py:92-110` 的 `normalize_openai_base_url` 只拼 `/v1/chat/completions`，随后直接 POST。`base_url` 来自请求体（`textBaseUrl`/`visionBaseUrl`）或 DB 配置，经 S-01 的无鉴权端点即可由外部指定。**对照 Java 侧**：`backend/lianyu-common/.../OutboundUrlValidator.java` 拦 `BLOCKED_HOSTS`、`.local/.internal`、loopback/link-local/`169.254` 并固化 DNS，配合 `backend/lianyu-ai/.../SsrfPinningClientFactory.java` 防重绑定（`ModelCatalogService.java:180-183` 正确使用）—— Python 侧没有等价物，等于从 Python 旁路了 Java 的防护。
- **影响**：可让后端容器向内网服务（MySQL/Redis/MinIO/Milvus、docker 网络内的 Java 服务）或 `169.254.169.254` 元数据端点发 POST，做盲 SSRF / 端口探测 / 借错误信息外带内容。
- **修复摘要**：移植 Java 的校验规则到 `app/outbound_guard.py`（协议白名单、IP/域名黑名单、DNS 解析后固化 IP、禁止跨域重定向），所有 `base_url` 入口统一走它。

#### S-04 [low] 对话内容（SSE 帧原文）以 INFO 写日志

- **位置**：`backend-agent/app/main.py:247`
- **证据**：`logger.info("chat-stream frame[%d]=%s", frames, frame[:160]...)`，`frame` 是包含模型回复正文的 SSE 数据行；`:223-225` 还记录 `userId`、`model` 与 system 长度。
- **影响**：私密对话明文进入容器日志并被 `docker logs`/采集长期留存，扩大日志读取方的数据可见面。
- **修复摘要**：降为 `debug` 且只记录帧类型与长度，不记正文；prod 日志级别收紧到 INFO 以上。

### 2.2 管理端（u11：lianyu-admin / lianyu-security）

#### S-05 [high] 2FA 可被绕过：`/totp/setup` 无 step-up 可静默重置，OTP 失败不计数

- **位置**：`backend/lianyu-admin/src/main/java/com/lianyu/admin/identity/AdminTotpController.java:19`（关联 `AdminAuthService.java:38-44`、`TotpUtil.java:13-18`）
- **证据**：`/setup` 只用 `authorization.currentAdminId()`，不校验密码或当前 OTP，直接改写 `admin_user.totp_secret` 并置 `totp_enabled=FALSE`；而**同一文件 `:37-44` 的 `/disable` 却要求 password 或当前 OTP**，反差明显。`AdminAuthService.java:30-51` 只在密码错误时 `failed_attempts+1`（`:38`），OTP 校验失败（`:43`）既不计数也不触发锁定/独立限流；`TotpUtil.verify` 接受 `counter-1..counter+1`（±30s，共 90s 窗口）且无 last-used 重放防护。
- **影响**：拿到一个有效 admin 会话即可静默把 2FA 换成自己的验证器并保持 `totp_enabled=false`，实现持久账号接管，同时把合法管理员锁在 2FA 外；已知口令后还能无限次爆破 6 位 TOTP。管理端有发布桌面安装包的权限（`release.manage`），该缺口可传导为供应链投毒。
- **修复摘要**：`/setup` 加与 `/disable` 同级的 step-up；改为「生成待确认密钥 + 一次成功 verify 才启用」；OTP 失败纳入锁定与限流；记录已用 counter 拒绝重放。

**已查未发现问题**：`AdminSecurityConfig` 对 `/api/admin/v1/**`（除登录）强制 `StpUtil.checkLogin()` 且 loginId 必须以 `admin:` 开头；`AdminAuthorizationService.currentAdminId()` 还校验 `admin_session.token_hash`（SHA-256）+ `expires_at` + `revoked_at IS NULL` + `u.status='active'`；`ReleaseService`（`:20`、`:32`）、`ReleaseUploadService`（`:21`）、`SupportGrantService`（`:19-20`）均有 `authorization.require(...)`；`AdminBootstrapService.java:21-31` 无默认口令；`AdminAuthService` 用 `BCryptPasswordEncoder(12)`，会话 token 只存 SHA-256、8 小时过期。

### 2.3 认证与账号（u01 / u08 / u12）

#### S-06 [medium] 客户端 IP 取自 `X-Forwarded-For` 最左值，可伪造绕过 per-IP 登录限流

- **位置**：`backend/lianyu-web/src/main/java/com/lianyu/web/util/ClientIpResolver.java:24`
- **证据**：取 XFF 第一个（最左）值；`backend/lianyu-app/src/main/resources/application.yml:172` 的 `lianyu.security.trust-forwarded-for` 默认 `${LIANYU_TRUST_FORWARDED_FOR:true}`；`deploy/api-gateway/nginx.conf:52` 用 `$proxy_add_x_forwarded_for` **追加**而非覆盖，最左值仍是客户端自己填的。`AuthController.java:78` 的登录限流与 `AuthRateLimiter.java:26-35`（IP 30/min、用户名 10/min）都依赖这个 IP。
- **影响**：每请求换一个伪造 XFF 即可让 per-IP 限流失效，撞库速率仅受 per-username 10/min 约束；配合可 OCR 的算式验证码，撞库成本明显降低。
- **修复摘要**：优先用 `X-Real-IP`（`$remote_addr`），XFF 只在开启 trust 且从右往左剥离可信代理后取第一个不可信地址；默认值改为 `false`。

#### S-07 [medium] 改密不吊销已签发 token，被盗会话在 30 天内继续有效

- **位置**：`backend/lianyu-service/src/main/java/com/lianyu/service/auth/impl/AuthServiceImpl.java:152`
- **证据**：`changePassword` 只 `userMapper.updateById(user)`，不 `StpUtil.logout`、不失效其它会话；`application.yml:133-146` 为 `timeout: 2592000`（30 天）、`active-timeout: -1`、`is-concurrent: true`、`is-share: false`、`is-read-cookie: false`。
- **影响**：账号被盗后「改密码」这个最常用的自救动作对攻击者无效，旧 token 仍可读写该用户的对话、记忆与 API Key 配置，最长 30 天。
- **修复摘要**：改密成功后 `StpUtil.logout(userId)` 踢掉全部会话，返回 `needRelogin`，前端清 token 并跳登录页。

**已查未发现问题**：`AuthServiceImpl` 登录失败统一文案、登录需图形验证码（`AuthController.java:79`）、`HeaderOnlyTokenFilter` 对 query 参数中的 token 直接 400；`SaTokenConfig` 对 `/api/**` 全量 `checkLogin()`，仅放行 login/register/captcha/public/admin（admin 由独立 `AdminSecurityConfig` 接管）。

### 2.4 授权与租户隔离（u08 / u02）

抽查结论：**Java 用户态接口未发现 IDOR**。

- `MemoryController`（3 个 handler 全部 `StpUtil.getLoginIdAsLong()`）→ `MemoryQueryService.detail/delete` 查询都带 `.eq(MemoryMeta::getUserId, userId)`（`:78-82`、`:113-117`）。
- `ConversationController` 12 个 handler 全部先取 userId 再透传 service；`ApiKeyVaultService.update/delete` 都做 `!userId.equals(vault.getUserId()) || !USER_SCOPE.equalsIgnoreCase(...)` 校验（`:118-121`、`:153-156`），`toResponse` 只回脱敏 key（`maskApiKey`），只有 create 时 `showFull=true`。
- 14 个 Controller 逐个比对「handler 数 vs `getLoginIdAsLong` 数」，唯一不取 loginId 的 `LegacyCommunityController` 是无业务逻辑的兼容垫片，`PublicFileController` 是有意的公开读取。

真正的授权缺口在 Python 侧，见 **S-02**（对象级归属校验缺失）。

### 2.5 注入（injection = 0，已查未发现问题）

- Java：MyBatis-Plus 全部 `#{}` 参数化；mapper XML 中 `${}` 计数为 **0**；未发现把用户输入拼进 `orderBy`/`last()`（都是常量或 Integer）。
- Python：`app/db.py:37-40` 的 `_assert_select` 强制 SELECT-only；`tools/recent_activity_tool.py:132,144` 的 f-string 只拼 `", ".join(["%s"] * n)` 占位符；全仓无 `eval`/`exec`/`pickle.load`/`os.system`/`subprocess`/`yaml.load`。
- 唯一命令执行点：`backend/lianyu-service/src/main/java/com/lianyu/service/voice/CustomVoiceAudioTrimmer.java:44` 的 `ProcessBuilder("ffmpeg", ...)` 用**参数数组**、不经过 shell，文件路径为 `Files.createTempFile` 生成，`extension` 只影响临时文件后缀（含分隔符会直接抛异常），不构成命令/参数注入。

### 2.6 浏览器安全 / Electron（u09 / u10 / u03 / u07）

#### S-08 [low] CSP 允许 `unsafe-eval` 与任意 https 连接，Electron 无 `will-navigate` 守卫

- **位置**：`frontend/index.html:7`
- **证据**：meta CSP 为 `script-src 'self' 'unsafe-eval'; connect-src 'self' https: wss:; img-src 'self' data: blob: https:` —— `unsafe-eval` 保留字符串到代码的执行路径，`https:` 允许任意域作为外联出口。`frontend/electron/main.js` 只在 `setWindowOpenHandler`（`:667-673`）用 `isAllowedExternalUrl` 拦新窗口，全文没有 `will-navigate`。
- **影响**：一旦出现未被 DOMPurify 覆盖的 DOM 注入点，CSP 基本不提供纵深防御（eval 可用、外联不受限）；渲染进程若被诱导导航，主进程没有第二道拦截。
- **修复摘要**：移除 `unsafe-eval`、把 `connect-src`/`img-src` 收窄到具体域名；`main.js` 增加 `will-navigate` 白名单。

**已查未发现问题**：
- 存储型 XSS：全仓 `v-html` 仅 `MomentsPage.vue:146`（走 `sanitizeHtml`）与 `LandingPage.vue:122`（静态常量）；`utils/sanitize.js:1-12` 是 DOMPurify + `ALLOWED_TAGS=['strong','em','b','i','u','span','br']` + `ALLOWED_ATTRS=['class']`；源码中 `dangerouslyUseHTMLString` 0 命中。
- Electron 隔离：`main.js:139-146` `contextIsolation:true` / `nodeIntegration:false` / `sandbox:true` / `partition:'persist:lianyu'`；`isAllowedExternalUrl`（`:361-375`）拒绝 `file:/data:/javascript:` 且要求 https + host 白名单；未发现 `webSecurity:false` 或 `allowRunningInsecureContent`。
- Token 存储：`frontend/src/utils/secureToken.js` token 只在内存；主存储走 `safeStorage`（`authSessionStore.js` 在 safeStorage 不可用时降级到明文文件，属可接受权衡，已在报告备注）。

### 2.7 上传与公开文件（u01 / u12）

**已查未发现问题（实现扎实）**：
- 头像/背景：`ImageUploadValidator`（8MB、8192 边长、magic bytes + ImageIO 解码 + 重编码 PNG 剥离嵌入内容），`FileStorageService` 用 UUID 文件名、contentType 固定 `image/png`。
- 公开读取：`PublicFileController` 虽直接截取 URI 后段，但下游 `statObject/getObject/readObjectBytes/deleteObjectQuietly` 全部先过 `validateObjectKey` + `SAFE_OBJECT_KEY` 白名单（不允许 `..`），**不构成路径穿越或越权读**；`resolveSafePublicContentType` 只回 image/audio/text-yaml，`isDangerousPublicContentType` 拒 html/svg/js，`updates/` 非 manifest 走 `Content-Disposition: attachment`。
- SSRF（Java 侧）：`OutboundUrlValidator` + `SsrfPinningClientFactory` 固定 IP；`LocalTtsEndpointValidator`（仅 loopback/私网）、`DashScopeCloudEndpointValidator`（host 精确匹配）均正确。

**抽样说明**：`uploadChatImage`（`FileStorageService.java:253-307`）只校验**客户端声明的** Content-Type + 5MB，不重编码、扩展名取自原始文件名。因响应固定 `X-Content-Type-Options: nosniff` 且类型只能是 `image/*`，当前不构成存储型 XSS，仅列入 `deferred` 交 code 评审决定是否统一到 `ImageUploadValidator`。

### 2.8 密钥与凭据（u04 / u10）

#### S-09 [high] 生产账号口令硬编码在已跟踪的运维脚本里

- **位置**：`scripts/_prod_verify.py:22`
- **证据**：该文件由 `git ls-files` 跟踪（提交 `3399884`），第 21-22 行以源码常量写死账号与口令（两处均已脱敏），用于对 `http://127.0.0.1:8080` 做真实登录与对话验证（第 23 行还会读取服务器 `/opt/lianyu/.env`）。真实生产账号口令因此进入仓库历史。
- **影响**：任何能读仓库的人（含 fork、CI 日志、历史克隆）都能直接以该账号登录生产，读写其对话与记忆、消耗其模型额度；若该账号与管理员或其它服务复用口令，影响面更大。**因为已进 git 历史，仅删除该行不足以消除暴露。**
- **修复摘要**：立即轮换该口令；脚本改为从环境变量读取凭据；用 `git filter-repo`/BFG 清理历史；CI 加 gitleaks/trufflehog 扫描。

**已查未发现问题**：`.env.example` 全为 `change-me-*` 占位（`LIANYU_MASTER_KEY=`、`LIANYU_UPDATE_SIGNING_KEY=` 为空并注明只在发版机使用）；`application.yml` 中 DB/Redis/RabbitMQ/MinIO 全部走 `${ENV:}` 且多数默认为空；跟踪文件做 `sk-/AKIA/PRIVATE KEY/赋值型 password|secret|token` 扫描**无命中**。
**本地备注**（未列入 11 条）：工作区存在未跟踪且被 `.gitignore` 忽略的明文密钥 —— `secrets/platform-keys.txt:2`、`config.json:104/115`、`certs/server.key`，建议单独做一次本地轮换与磁盘清理。

### 2.9 加密（u11 / u05）

#### S-10 [high] Jasypt 主密钥缺失时静默回退明文，API Key 与 TOTP 种子以明文落库

- **位置**：`backend/lianyu-security/src/main/java/com/lianyu/security/util/JasyptUtil.java:63`
- **证据**：构造器在 `LIANYU_MASTER_KEY` 为空时只打一条 warn 就 return（`encryptors` 保持空）；`encrypt()` 在 `:63-66`、`:68-70` 执行 `log.warn("No Jasypt encryptor available, returning plaintext"); return plaintext;`。调用方 `ApiKeyVaultService` 第 130 行（update 时）把返回值直接写入 `api_key_vault.api_key_encrypted`，`AdminTotpController:24` 写入 `admin_user.totp_secret`。`.env.example:40` 的 `LIANYU_MASTER_KEY=` 是空占位，缺变量时服务照常启动。
- **影响**：一次部署疏忽（漏配环境变量）就让用户自带模型 API Key 与管理员 2FA 种子以明文长期留在数据库与备份中，且**没有任何报错**；之后即使补上密钥，已写入的明文行也不会被识别（decrypt 解密失败后原值返回）。属加密能力静默降级（fail-open）。
- **修复摘要**：`encrypt()` 无 encryptor 时抛 `crypto_unavailable`；启动时校验主密钥（生产缺失直接启动失败）；本地开发用显式开关 `LIANYU_CRYPTO_ALLOW_PLAINTEXT` 且仅限 dev；补一次性迁移把已存明文重新加密。

**已查未发现问题**：加密算法配置本身正确（`PBEWITHHMACSHA512ANDAES_256` + `RandomSaltGenerator` + `RandomIvGenerator` + 1000 迭代）；`api_key_vault.api_key_encrypted`/`key_version` 与 `admin_user.totp_secret` 在 schema 层均为加密列，迁移里无明文种子。

### 2.10 供应链（u09 / u10 / u12）

#### S-11 [medium] 更新清单验签已实现但未启用（公钥留空即跳过），依赖审计也不阻塞合并

- **位置**：`frontend/.env.production.cloud:12`（机制 `frontend/electron/updater/updater.js:81-107`，关联 `.github/workflows/ci.yml:46-48`）
- **证据**：`verifyManifestOrThrow` 在 `resolveManifestPublicKey()` 为空时只 `logger.warn('latest.yml signature check disabled')` 然后 return（fail-open）；公钥来自打包时的 `VITE_LIANYU_UPDATE_PUBKEY`，而 `frontend/.env.production.cloud:12` 是**注释掉的** `# VITE_LIANYU_UPDATE_PUBKEY=`，即当前发布的客户端不带公钥。于是安装包完整性只依赖 `latest.yml` 里的 sha512（与该清单同源）。另外 `ci.yml` 的 OSV 扫描带 `continue-on-error: true`，且 action 只用 tag（`@v4`/`@v2.0.3`）而非 commit SHA。
- **影响**：更新渠道一旦被写入（MinIO 凭据泄露、nginx/存储被改、发版脚本被投毒），客户端会静默安装攻击者的包 —— 桌面端等于 RCE；依赖漏洞扫描失败又不会阻断合并，已知漏洞可能长期留在发布物里。
- **修复摘要**：发布时产出 `latest.yml.sig`，客户端内置公钥并把「无公钥」分支改为拒绝更新；`deps-audit` 去掉 `continue-on-error`；actions 固定到 commit SHA。

**已查未发现问题**：`verifyManifestSignature`（Ed25519 + SPKI DER，覆盖清单原始字节）实现正确；TLS 侧有证书 SPKI pin；napcat/wechat 下载器有 sha256 锚点校验（`napcatDownloader.js`）；`extract-zip` 为 2.x（已修复早期 zip-slip）；前端/管理端依赖版本较新（electron 42、vue 3.5、axios 1.7.9/1.18、element-plus 2.8/2.14），未发现已知高危 pinned 版本；`frontend/package-lock.json` 存在且 CI 用 `npm ci`；Python 依赖用 `>=` 但仓库带 `uv.lock`。

---

## 3. 未验证 / 抽样说明

- **sampled 的 4 个单元**（u02、u03、u04、u07）只做了模式扫描 + 关键文件抽查，**不等于全量已读**：`u02`（120 文件/16014 行）的 IDOR 结论来自「按 userId 过滤」模式比对与 3 个代表 service 的细读；`u03`/`u07`（约 40k 行 Vue）的 XSS 结论来自 sink 全量 grep + sanitize 实现核读；`u04`（120 个脚本）的凭据结论来自赋值模式全量扫描，未逐脚本通读，因此不排除存在更隐蔽的凭据残留。
- 未执行任何测试命令（任务要求只记录），因此 S-01/S-02/S-03 等「可复现」结论是代码路径级推演，没有动态验证；`X-User-Id` 可选短路的判断依据是 `if user_id_header and ...` 这一短路语义与 nginx 不注入该头。
- 未验证项：生产 `.env` 是否真的配置了 `LIANYU_MASTER_KEY`（决定 S-10 是否已经实际发生明文落库）、生产 `HJKL` 账号是否为真实活跃账号、`latest.yml.sig` 是否已被上传（决定 S-11 的实际暴露窗口）。这三项都需要运维侧确认，代码层面无法判定。
- 未覆盖：`.logistics/` 与 `docs/logistics/` 下由本工具产生的文件；`frontend/dist` 为构建产物，只在必要处 grep 过，未作为审查对象。

---

## 4. 建议修复顺序

1. **立即（当天）**：S-09 轮换生产口令 + 清历史；S-01 关掉 `/api/agent/` 的公网入口（最省事的临时缓解是 nginx 直接 `return 404` 该 location，Java 走内网直连）。
2. **本周**：S-03 Python 出站校验（可直接导致内网穿透）；S-10 Jasypt fail-closed；S-05 管理端 2FA step-up 与 OTP 计数。
3. **两周内**：S-02 记忆写入归属校验（与 S-01 的中间件一起做）；S-06 IP 解析口径；S-07 改密吊销会话。
4. **随版本**：S-11 更新验签上线 + 依赖审计转阻塞；S-04 日志脱敏；S-08 CSP/导航守卫。

## 5. 跨专家转交项

| 转交对象 | 内容 | 理由 |
|---|---|---|
| correctness | `FileStorageService.uploadChatImage` 只校验声明 Content-Type、不重编码，与头像/背景路径不一致（潜在健壮性缺口） | 属实现一致性问题，非可达安全问题 |
| reliability | agent 无鉴权端点中的 `/agent/memory/reconcile`、`/relay/drain|requeue` 可被外部触发全量重投影，缺幂等/并发保护 | 失败放大与重试语义属可靠性关注面 |
| maintainability | `JasyptUtil.decrypt` 在多版本密钥间「失败即吞异常」（`:85`、`:89`）导致密钥轮换问题不可观测 | 可观测性/可维护性 |
| 产品/风控 | 算式图形验证码可 OCR（`CaptchaService`），是否升级为行为验证或第三方验证码 | 需产品权衡，非纯技术缺陷 |

---

**附**：机器可读 handoff 见同目录 `security.json`（11 条 finding，section 计数与 findings 逐项一致；units 覆盖 u01–u12，无 skipped）。
