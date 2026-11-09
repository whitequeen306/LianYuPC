# 安全审查报告

- 项目：`C:/Users/hp/Desktop/LianYu-PC`（予念 YuNian PC）
- 审查模式：project（全量，非 diff），基线 `main` @ `c652e14`
- 规模：1100 个受审文件 / 12 个审查单元 / 135978 行
- 审查者：security-auditor（只负责 security 一项，只读）
- 关注面：认证、授权/租户隔离、注入、浏览器安全、上传/SSRF、密钥、加密、数据暴露、依赖与供应链
- 结论：**12 条发现**（critical 1 / high 4 / medium 3 / low 4），其中 1 条需要立即处置（公网未认证读写任意用户记忆）。

---

## 一、结论摘要

这次审查里最要紧的一条不是某个常规漏洞，而是**架构缝隙**：`nginx` 把 `/api/agent/` 原样代理到了 Python agent 容器，而 agent 侧没有任何认证，唯一的 `X-User-Id` 校验写成「头缺失就放行」。于是公网匿名请求可以：

1. 用任意 `userId` 调 `/agent/prompt/assemble`，让 agent 自己从 MySQL 读出该用户的记忆画像、关系分、会话摘要，拼进 `systemPrompt` 原样返回；
2. 用任意 `userId` 调 `/agent/memory/write`，把伪造记忆写进别人的账户，污染之后的对话；
3. 用任意 `baseUrl` 调 `/agent/text/chat`，让 agent 容器替攻击者访问内网地址（SSRF）。

其次是几个「默认值不安全」的问题：`X-Forwarded-For` 默认被信任（限流可绕过）、Jasypt 主密钥缺失时静默明文存厂商 API Key、更新器签名公钥缺失时静默跳过校验。

从正面看，这个项目的安全底子是有的：Electron 主进程（`contextIsolation` / `sandbox` / IPC 白名单 / egress 白名单 / 证书 pinning）、Java 侧 SQL 全参数化、上传对象键白名单、公共文件内容类型过滤、`/internal/**` 无路由暴露、密钥未入库——这些都实际核对过，不是「文档写着有」。真正的问题集中在**服务间信任边界**与**默认配置**两处。

---

## 二、范围与覆盖

| 单元 | 范围 | 文件数/行数 | 状态 | 实读文件数 | 说明 |
|---|---|---|---|---|---|
| u01 | backend/lianyu-service (1/3) | 120 / 12117 | reviewed | 14 | FileStorageService、ApiKeyVaultService、CharacterService、CustomVoiceAudioTrimmer、auth 实现；SQL 拼接与上传路径逐处核对 |
| u02 | backend/lianyu-service (2/3) | 120 / 16014 | sampled | 8 | 按 grep 扫 SQL 拼接 / ProcessBuilder / 凭据，抽查 moments、notification、square、voice、conversation 服务 |
| u03 | frontend/src (1/2) | 120 / 15840 | sampled | 9 | v-html / innerHTML / eval / localStorage token / window.open 全量 grep，抽查 9 个关键页面与 composable |
| u04 | scripts (1/2) | 120 / 12803 | sampled | 12 | 口令密钥正则全量扫描 + 命中项逐个看上下文；`os.system` / `shell=True` 在 scripts 下 0 命中 |
| u05 | backend/lianyu-dao | 110 / 2934 | reviewed | 6 | 110 个迁移全量 grep（INSERT admin_user / GRANT / CREATE USER / 明文口令 0 命中）+ V51 admin schema 全文 |
| u06 | backend-agent | 102 / 16514 | reviewed | 12 | main.py 全量路由与鉴权、config.py、http_client.py、gateway.py、dashscope.py、prompt_pipeline.py、data_readers.py、memory/writer.py |
| u07 | frontend/src (2/2) | 95 / 24648 | sampled | 7 | 同组 grep 覆盖，抽查聊天/广场/设置等高行数页面 |
| u08 | backend/lianyu-web | 71 / 7840 | reviewed | 16 | 14 个 controller + CorsConfig + ClientIpResolver + WebSocketConfig，逐接口核对 userId 来源 |
| u09 | frontend/electron | 68 / 14745 | reviewed | 11 | main.js webPreferences / IPC 白名单 / api:request egress 校验 / setWindowOpenHandler / 图片查看窗 / updater 签名 / napcat 运行时 |
| u10 | 其他模块（root、.github、admin 等） | 67 / 4923 | reviewed | 9 | ci.yml、nginx.conf、.env.example、admin/electron、admin/src、deploy/asr/server.py、frontend/index.html CSP |
| u11 | backend 小模块合集（lianyu-admin 32 + lianyu-service 27） | 59 / 3141 | reviewed | 10 | admin 侧 require() 覆盖扫描（46 处匹配）+ AdminSecurityConfig / AuthorizationService / AuthService / Totp / ReleaseUpload / SupportGrant |
| u12 | 其他模块（lianyu-app、lianyu-common、installer 等） | 48 / 4459 | reviewed | 7 | OutboundUrlValidator、SsrfPinningClientFactory、installer 工程、Pets/skills、backend/pom.xml |

- 12 个单元**全部覆盖**，无 skipped。
- `reviewed` 8 个、`sampled` 4 个（u02/u03/u04/u07）。sampled 单元均做了全量模式扫描 + 关键文件精读，但**没有逐文件通读**，见第五节。

---

## 三、发现

### 3.1 backend-agent（u06）+ 网关（u10）——最严重

#### S-01 公网未认证可读写任意用户记忆（critical，confidence high，kind plan）

- 位置：`backend-agent/app/main.py:657`（`/agent/prompt/assemble`）、`backend-agent/app/main.py:982`（`/agent/memory/write`）、`deploy/api-gateway/nginx.conf:252-285`
- 证据：
  - `deploy/api-gateway/nginx.conf:252-285` 的 `location /api/agent/` 只有 `limit_req` 与 OPTIONS 预检，随后 `set $agent_upstream http://agent:8001; rewrite ^/api(/agent/.*)$ $1 break; proxy_pass $agent_upstream;`。整份配置 `auth_request` 0 命中、`internal;` 0 命中——**公网 443 可直接打到 agent 容器**。
  - agent 侧 25 个路由没有任何认证依赖（`backend-agent/app` 内 `add_middleware` / `Depends(` 0 命中）；`config.py:57` 声明了 `internal_signature_window_seconds`，但全仓无实现。
  - 唯一校验是每个端点重复的三行：`if user_id_header and str(body.user_id) != user_id_header: raise HTTPException(403, "user mismatch")`（如 `main.py:659-661`）——**`X-User-Id` 缺省即放行**，`body.userId` 被直接信任。
  - `PromptAssembleBody.use_internal_data=true` 时 `assemble_turn_prompt` 自读库（`app/prompt_pipeline.py:118-165`、`app/data_readers.py:225-264`），`load_memory_context` 按 `user_id + character_id` 取记忆画像、关系、会话摘要，`main.py:703` 原样 `return {"systemPrompt": prompt}`。
  - `memory/write` 用 `body.userId` 直接 `INSERT INTO memory_meta (character_id, user_id, summary, ...)`（`main.py:982-1005`、`app/memory/writer.py:122-181`）。
  - 复现（无需任何凭据）：
    ```bash
    curl -sX POST https://<host>/api/agent/prompt/assemble \
      -H 'Content-Type: application/json' \
      -d '{"userId":2,"characterId":1,"scene":"SINGLE","useInternalData":true}'
    # → {"systemPrompt": "...<该用户的记忆/关系/摘要>..."}

    curl -X POST https://<host>/api/agent/memory/write \
      -H 'Content-Type: application/json' \
      -d '{"userId":2,"characterId":1,"memories":[{"summary":"伪造记忆","memoryType":"FACT","importance":5}]}'
    ```
- 影响：按 `userId` 枚举即可批量读取全体用户的私密记忆（姓名、住址、健康、关系状态等用户主动告知的事实）与会话摘要；可向任意账户写入伪造记忆，让角色在后续对话里「记得」攻击者编造的内容；`/agent/memory/relay/drain`、`/agent/memory/reconcile`、`/agent/desktop-observe` 等运维/推理端点同样匿名可触发。
- 修复摘要：网关收回 `/api/agent/` 的公开入口（或加 `auth_request` 校验 Sa-Token），agent 侧给全部路由加 HMAC 签名依赖（时间窗 + nonce），并把 `X-User-Id` 改为必填且必须等于 `body.userId`。

#### S-05 agent 出站不校验 baseUrl，可作内网 SSRF 跳板（high，confidence high，kind plan）

- 位置：`backend-agent/app/gateway.py:92`（`normalize_openai_base_url`）、`backend-agent/app/http_client.py:26`
- 证据：`normalize_openai_base_url` 只做去尾斜杠、去 `/v1`、拼 `/v1/chat/completions`，**不校验 scheme 与 host**；`_do_post`（`:413-427`）用该 URL 直接发请求；`http_client.new_client()` 只设超时/UA/`local_address`，无网段黑名单。对比同仓库 `app/dashscope.py:56-78` 对 TTS 有严格白名单（仅 https、host 必须属于 `dashscope(-intl).aliyuncs.com`、禁 userinfo、禁非 443）——**同一份代码里两套标准**。入口 `/agent/text/chat`（`main.py:359-365`）与 `/agent/chat-stream`（`main.py:200-206`）接受 `body.textBaseUrl`，且如上条无认证。
- 影响：匿名攻击者可让 agent 容器向 `127.0.0.1:6379`、`127.0.0.1:3306`、`169.254.169.254`、内网网段发起带 JSON body 的 POST，用响应耗时/错误差异做端口与服务指纹；内网若有未鉴权 HTTP 服务则可进一步操作。
- 修复摘要：新增 `validate_outbound_url()`（解析 DNS 后逐 IP 拒绝 loopback/private/link-local/unspecified 与 `169.254.169.254`，禁 userinfo），在 `gateway` 与 `http_client` 统一挂接，并与 Java 侧 `OutboundUrlValidator` 语义对齐。

### 3.2 认证与限流（u08 / u12 / u10）

#### S-02 X-Forwarded-For 默认被信任，限流与注册配额可伪造绕过（high，confidence high，kind plan）

- 位置：`backend/lianyu-web/src/main/java/com/lianyu/web/util/ClientIpResolver.java:16`
- 证据：`@Value("${lianyu.security.trust-forwarded-for:true}")` **默认 true**，`resolve()`（`:25-31`）直接取 XFF 逗号前第一段；`backend/lianyu-app/src/main/resources/application.yml:172` 同样是 `${LIANYU_TRUST_FORWARDED_FOR:true}`，且 `application-prod.yml` 未覆盖该项。`deploy/api-gateway/nginx.conf:278` 用 `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for` **只做追加**，客户端自带的 `X-Forwarded-For: 1.2.3.4` 就成为首段并被取用。调用点：`AuthController` 的 `checkLoginOrRegister`、`register` 的 `rate:register:ip:` 3 次/天、头像 20 次/天、`ObserveController` 每 IP 30 次/时。
- 复现：`curl -H 'X-Forwarded-For: <每次随机IP>' https://<host>/api/auth/register` 每次都算新 IP，不受每日 3 次配额限制。
- 影响：登录/注册防爆破、每日注册配额、头像与桌面观察配额都能无限重置；验证码仍需通过，因此是**限额放大**而非完全绕过，但足以支撑批量注册与持续试密码。
- 修复摘要：默认不信任 XFF；信任时取最右一段（新增 `lianyu.security.trusted-proxy-hops`），生产显式开启；nginx 侧改为覆盖 `$remote_addr`。

### 3.3 密钥与加密（u04 / u10 / u01 / u09）

#### S-03 生产账号口令硬编码在入库脚本（high，confidence high，kind plan）

- 位置：`scripts/_prod_verify.py:22`（同文件 `:21` 是用户名）
- 证据：`USERNAME = "hjkl"` / `PASSWORD = "12…er"`（原文为明文口令，此处脱敏）；同文件 `BASE = "http://127.0.0.1:8080"`、`ENV_FILE = "/opt/lianyu/.env"` 与文件头注释表明这是**部署后在服务器本机**执行的生产验证脚本（登录、6 条对话、SSE、推送垫片、404）。该文件被 git 跟踪。
- 影响：任何能读仓库的人（含 CI、外包、历史 fork）即获得一个可用生产账号口令，可登录生产读该账号对话与记忆、消耗模型额度；若与其他系统复用则可横向扩散，且轮换前无法从代码判断其有效性。
- 修复摘要：轮换口令 + 复查登录审计 + 脚本改读环境变量 + 评估历史清理。

#### S-04 Jasypt 主密钥缺失时静默降级明文存储厂商 API Key（high，confidence high，kind plan）

- 位置：`backend/lianyu-security/src/main/java/com/lianyu/security/util/JasyptUtil.java:61`
- 证据：`encrypt()` 在 `currentVersion == null || encryptors.isEmpty()` 时 `log.warn("No Jasypt encryptor available, returning plaintext")` 并**直接返回明文**；`decrypt()`（`:75-80`）同样原样返回。主密钥来自 `LIANYU_MASTER_KEY`，而 `.env.example:40` 正是空占位。写入路径：`ApiKeyVaultService.java:74`、`:130` 调 `jasyptUtil.encrypt(apiKey)` 落库。算法本身（PBEWITHHMACSHA512ANDAES_256 + RandomSalt/IV + 1000 iter）没问题，问题在**失败时 fail-open**。
- 影响：`.env.example` 提供了「留空也能启动」的默认路径；运维照抄后，用户自填的 DashScope/OpenAI 真实计费 Key 以明文进 MySQL，仅一条 warn 日志。DB 备份、只读账号或任一 SQL 读取面即可批量窃取，且明文与密文无版本前缀，事后无法区分哪些行未加密。
- 修复摘要：无主密钥时 fail-fast（启动校验或抛异常），并对存量行做一次性重加密。

#### S-06 NapCat OneBot WS / WebUI 令牌硬编码为 `lianyupc` 并强制覆盖（medium，confidence high，kind plan）

- 位置：`frontend/electron/napcatRuntime/napcatConfig.js:4`
- 证据：文件注释与常量写明「把 token 定死为 lianyupc（用户指定）」，`wsToken`/`webuiToken` 均为 `'lianyupc'`（`:65-74`）；`:96-131` 与 `:201-205` 在写 `config/onebot11.json`、`onebot11_<uin>.json`、`webui.json` 时**强制覆盖**既有 token，注释自称「必赢过它自身的『默认密码→随机安全密码』重生成」；`napcatHost.js:210` 把 token 拼进 `http://127.0.0.1:${webuiPort}/webui?token=${webuiToken}`。
- 影响：本机任意进程（低权限程序、其他应用内嵌 webview、恶意脚本）都知道该常量，可连 `127.0.0.1:3001` 的 OneBot WS 读写全部 QQ 消息、调用发送接口；也可打开 6099 WebUI 改配置。等于取消 NapCat 自带随机口令保护。
- 修复摘要：改每安装实例随机令牌，持久化到 userData（0600），经 `NAPCAT_WEBUI_SECRET_KEY` 注入，不再覆盖成常量。

### 3.4 供应链与更新链路（u09 / u10）

#### S-07 更新清单签名公钥缺失时静默跳过校验（medium，confidence high，kind plan）

- 位置：`frontend/electron/updater/updater.js:86`
- 证据：`updater.js:72` 读 `secrets?.updateManifestPublicKey`；`:84-89` 为空时 `logger.warn('updater','latest.yml signature check disabled: no updateManifestPublicKey')` 后**直接 return**，不抛错也不阻断更新。公钥来自打包期注入 `VITE_LIANYU_UPDATE_PUBKEY`（`frontend/scripts/electron-pack.mjs:240` 取 `cloudEnv.VITE_LIANYU_UPDATE_PUBKEY || ''`；`pack-runtime-secrets.mjs:34-44` 打进 `runtime-secrets.bin`；`runtimeSecrets.js:92-114` 读取）。剩余唯一校验是 sha512，而 sha512 与 `latest.yml` **同源**（`verifyInstallerSha512`），不构成独立校验。
- 影响：发布流水线漏注入公钥（默认空字符串）时，任何能写 MinIO `updates/` 或 MITM 更新域名的一方都能同时替换 `latest.yml` 与安装包，客户端仍「校验通过」并自动安装——Ed25519 分离签名这道防线在生产可能根本没生效，且无告警。
- 修复摘要：非本地构建缺公钥即失败；运行时空公钥拒绝更新并报 `update_pubkey_missing`。

#### S-08 依赖审计 job 设 continue-on-error（low，confidence high，kind direct）

- 位置：`.github/workflows/ci.yml:47`
- 证据：`deps-audit` job 的步骤名 `OSV-Scanner (best-effort)` 且 `continue-on-error: true`（用 `google/osv-scanner-action/osv-scanner-action@v2.0.3`）；workflow 无 `permissions:` 声明，actions 全按 tag（`checkout@v4` 等）而非 commit SHA 引用；`.github` 下无 `dependabot.yml`。
- 影响：新增带已知 CVE 的依赖不会让 CI 变红；tag 引用与默认 GITHUB_TOKEN 权限也放大第三方 action 被篡改的影响面。
- 修复摘要：去掉 `continue-on-error`、按 severity 阈值 gate、加 `permissions: contents: read`、actions 改 SHA pin。

### 3.5 浏览器安全（u10 / u03 / u07）

#### S-09 管理端 token 存 localStorage 且 admin 无 CSP（medium，confidence high，kind plan）

- 位置：`admin/src/api.js:3`、`admin/src/stores/session.js:2`
- 证据：`api.js:3` `localStorage.getItem('lianyu-admin-token')` 注入 `lianyu-token` 头；`session.js:2` 登录后写入同名 key；`admin/index.html` **没有** CSP meta（0 命中），`deploy/api-gateway/nginx.conf` 也只下发 `X-Frame-Options`/`nosniff`/`Referrer-Policy`，无 CSP；服务端 `admin_session` 有效期 8 小时（`AdminAuthService.java:44-51`）。**诚实标注**：`admin/src` 目前无 `v-html`/`dangerouslyUseHTMLString`（0 命中），需要第三方依赖或后续改动引入 XSS 才可触发。
- 影响：一旦管理端出现任意 XSS（依赖、公告/发布说明富文本、未来新增 v-html），脚本可直接读走 token，在 8 小时内以管理员身份调用 `/api/admin/v1/**`（含发布、发放 support grant），服务端无法区分。
- 修复摘要：改 HttpOnly + SameSite Cookie（或内存态 token）；登出时显式撤销 `admin_session`；admin 加 CSP；后台富文本统一 sanitizer。

#### S-10 渲染端 CSP 允许 `'unsafe-eval'`（low，confidence high，kind direct）

- 位置：`frontend/index.html:7`
- 证据：`script-src 'self' 'unsafe-eval'`；`style-src` 含 `'unsafe-inline'`。Electron 渲染进程加载的正是这份 dist 页面（`contextIsolation`/`sandbox` 已开，见第四节）。
- 影响：CSP 作为 XSS 的第二道防线被削弱——任何把攻击者字符串送进 `eval`/`new Function` 的注入点（第三方 SDK、Vue 运行时编译、动态模板）都能执行；`unsafe-inline` 样式也让 CSS 注入可覆盖 UI。
- 修复摘要：`script-src` 收成 `'self'`，build 后加静态断言禁止 `'unsafe-eval'` 回归。

### 3.6 上传与数据暴露（u01 / u08）

#### S-11 聊天图片上传只信客户端 Content-Type，扩展名取自原始文件名（low，confidence high，kind plan）

- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/storage/FileStorageService.java:284`
- 证据：`uploadImageToPath`（`:283-286`）`String ext = getExtension(file.getOriginalFilename())` 直接拼进 object key；`normalizeImageContentType`（`:582-595`）优先取客户端 header；`validateChatImage`（`:566-580`）只校验 ≤5MB 与 content type ∈ {image/jpeg,png,webp,gif}，**无 magic byte 或解码校验**。
- 缓解（已核实，因此定为 low）：`PublicFileController.java:40-44` 用 `isDangerousPublicContentType`（`FileStorageService.java:547-557`）对 `text/html`、`xhtml`、`image/svg`、`*/javascript` 返回 404，响应带 `X-Content-Type-Options: nosniff`；网关未直通 MinIO bucket。
- 影响：任何二进制声明为 `image/png` 即可落进 `chat-images/` 并得到公开 `image/*` URL（扩展名可任意，如 `.svg`/`.html`）。当前不可执行，但一旦接 CDN 或放宽类型映射就变成存储型内容伪装与存储滥用（5MB × 无限次）。
- 修复摘要：用图像解码器验证并重编码（与头像同一路径），扩展名由校验后的 content type 决定。

#### S-12 聊天语音/图片公共 URL 无鉴权、无过期，仅靠 UUID 保密（low，confidence high，kind plan）

- 位置：`backend/lianyu-web/src/main/java/com/lianyu/web/controller/PublicFileController.java:62`
- 证据：`/api/public/files/**` 在 `SaTokenConfig` 白名单内完全匿名；`FileStorageService.java:50-52` 的 `SAFE_OBJECT_KEY` 覆盖 `chat-voice/{petId}/`、`custom-voices/{userId}/`、`chat-images/`、`avatars/`；对象名是 UUID（不可猜）但**无 TTL、无签名**；响应头 `Access-Control-Allow-Origin: *` 与长 `Cache-Control`。
- 影响：语音/图片 URL 一旦出现在日志、客服工单、分享或截图中，任何持有者（含任意网站 JS）都能长期读取并转存；撤销只能依赖调用方主动删对象，缓存期内仍可访问。对私密语音（自定义音色、角色语音）是长期暴露面。
- 修复摘要：私密媒体改短期 HMAC 签名 URL，移除通配 ACAO，私有对象 `Cache-Control: private`。

---

## 四、已查未发现问题的区域

这些区域**实际读过代码**并做了针对性检查，结论是干净，不是「没看」：

- **注入（injection）**：全 backend `.last("LIMIT " + int)` 20 处均为数值；`CharacterService.java:194-195` 的 `inSql` 拼接对象是 `Long characterId`；`createStatement` / `String.format` SQL 0 命中；`Runtime.getRuntime`、`ObjectInputStream`、`XMLDecoder`、`ScriptEngine`、SpEL 0 命中；唯一 `ProcessBuilder` 在 `CustomVoiceAudioTrimmer.java:44-55`，参数固定、无 shell。
- **Electron 加固**：`main.js:140-147` `contextIsolation: true` / `nodeIntegration: false` / `sandbox: true`；`isAllowedExternalUrl`（`:361-376`）只放 https 且 host 白名单；`setWindowOpenHandler`（`:667-674`）无脑 deny + 白名单内 `shell.openExternal`；`api:request`（`:2214-2258`）先 `isAllowedEgressUrl` 再令牌桶，鉴权头由主进程注入，被拦返回 `egress_blocked`/`rate_limited`；71 个 IPC 处理器绝大多数首行 `guardTrusted(event)`；图片查看窗 url 走 `JSON.stringify` 注入 + CSP `default-src 'none'`；证书 pinning 在 `:210-320`。（唯一瑕疵：桌宠窗 `LAUNCHER_WEB_PREFS` 关闭 sandbox，仅本地页面，风险可接受。）
- **SQL / DAO**：110 个迁移无 `INSERT admin_user`、无 GRANT/CREATE USER、无明文口令；V51 seed 只创建 `super_admin` 角色与 role_permission，`password_hash NOT NULL`、`totp_secret` 可空。
- **admin 授权**：`AdminSecurityConfig.java:13-17` 独立拦截 `/api/admin/v1/**` 且要求 `admin:` 前缀；`AdminAuthorizationService` 用 `admin_session.token_hash` + `expires_at` + `revoked_at` + `u.status='active'` 复核；全 admin 路由 46 处 `require()` 覆盖（含 `ReleaseUploadService.createUpload` 拒绝 `..`/`/`/`\`、≤1GB、UUID 前缀、900s 预签名），`SupportGrantService` 用 `SecureRandom` 24 字节、只存 hash、15 分钟过期、审计打 `[REDACTED]`。
- **用户侧授权**：14 个 controller 全部 `StpUtil.getLoginIdAsLong()` 取 userId 后交 service（GroupChat/Memory/Conversation/Moments 等），`UserPublicProfileService.java:34-56` 公开资料只含 nickname/avatar/角色列表且受 `showCharactersOnProfile` 开关控制，无 PII 外泄。
- **上传**：`SAFE_OBJECT_KEY` 白名单 + `validateObjectKey` 在 stat/get/read/delete 全调用；头像 `ImageUploadValidator.validateAndReencode` 重编码 PNG；语音 2MB/15MB 上限 + `SAFE_PET_ID`/`SAFE_USER_ID`。
- **密钥入库**：`.env`（含真实 DASHSCOPE Key）已在 `.gitignore:5` 忽略且未入库；`secrets/platform-keys.txt`、`.specstory/` 同样忽略；`.env.example` 全为 `change-me` 占位。
- **日志**：全 backend 未见打印 apiKey/token/password 的语句；`HeaderOnlyTokenFilter.java:20-31` 拦截 URL 里的 `lianyu-token`（防 token 进日志）；`WebSocketConfig.java:118-124` 握手缺 token/无效 token 均 warn 拒绝。
- **端口暴露**：`docker-compose.yml` 中 backend、agent-lb、MySQL、Redis、RabbitMQ、Milvus、MinIO 全部 `127.0.0.1:` 前缀；ASR `127.0.0.1:18081`；文档面（doc.html/swagger/api-docs）在 nginx 被 `return 404`。
- **XSS 面**：`frontend/src` 仅 4 处 HTML 相关——`useSquareDanmaku.js:66,83` 清空用、`LandingPage.vue:122` 静态图标、`MomentsPage.vue:146` 已过 `sanitizeHtml`；无 localStorage 存 token；`window.open` 仅 AboutPage 常量 URL 带 `noopener,noreferrer`。
- **安装器/脚本执行面**：`installer/`、`Pets/skills` 中 `Invoke-WebRequest`/`Invoke-Expression`/`iex`/`Expand-Archive` 0 命中。

---

## 五、未验证与抽样说明

**必须明确：以下不是全量已读。**

1. **u02 / u03 / u04 / u07 为 sampled**：分别只精读 8/9/12/7 个文件，其余为 grep 模式扫描覆盖。若要在这四个单元给出「无问题」的强结论，需要补读。
2. **依赖 CVE 未真实扫描**：本次无网络，只审阅了 `frontend/package-lock.json`、`admin/package-lock.json`、`backend-agent/uv.lock` 与 CI 配置，没有跑 `osv-scanner` / `npm audit`。
3. **生产运行时配置未知**：`LIANYU_MASTER_KEY`、`LIANYU_TRUST_FORWARDED_FOR`、`VITE_LIANYU_UPDATE_PUBKEY` 三项在服务器 `.env` 与打包产物里的真实取值无法从仓库判断——它们决定 S-02 / S-04 / S-07 在**生产**是否真的触发。仓库里只能看到「默认值是危险的那一侧」。
4. **S-01 / S-05 的可利用性依赖真实部署**：`443 → nginx → agent:8001` 这条链路已从配置确认，但没有对生产发起任何主动探测（只读审查，不做攻击性验证）。建议在预发环境匿名 curl 一次确认。
5. **未执行 4 条测试命令**（`npm --prefix admin test`、`mvn -q -f backend/pom.xml test`、`pytest -q backend-agent`、`npm --prefix frontend test`）：本次不改代码，测试对安全结论无增益；修复后应作为验证手段。
6. **admin 端 XSS 未做依赖级审计**：`admin/src` 无 `v-html`，但第三方组件版本与后台富文本渲染未逐版本核对。
7. 环境无 `rg`，改用 `ops_grep` / `ops_bash` 做跨文件扫描；已忽略 `.logistics/` 与 `docs/logistics/` 下的工具产物。

---

## 六、建议修复顺序

| 顺序 | 项 | 理由 | 预估 |
|---|---|---|---|
| 1 | **S-01** 收口 `/api/agent/` + agent 内部签名 + X-User-Id 必填 | 唯一 critical，且公网匿名可读他人私密记忆，属于「必须今天处理」 | 0.5–1 天（含回归） |
| 2 | **S-03** 轮换生产口令 + 脚本改环境变量 | 凭据已在 git 历史里，越早轮换损失越小 | 0.5 天 + 审计 |
| 3 | **S-04** Jasypt fail-fast + 存量重加密 | 加密在漏配时静默失效，影响所有用户厂商 Key | 1 天（含迁移脚本） |
| 4 | **S-05** agent 出站白名单 | 与 S-01 同批改，成本低 | 0.5 天 |
| 5 | **S-02** XFF 信任模型 | 影响所有限流/配额，需同时改 nginx 与后端 | 0.5 天 |
| 6 | **S-07** 更新器公钥 fail-fast | 发布链路完整性，改在打包脚本里最省事 | 0.5 天 |
| 7 | **S-09** 管理端 token 与会话 | 需要改登录契约，排一次迭代 | 1–2 天 |
| 8 | **S-06** NapCat 随机令牌 | 涉及 QQ 桥迁移与重登 | 1 天 |
| 9 | **S-08 / S-10 / S-11 / S-12** | 低危加固，可随手带上 | 各 0.5 天内 |

修复时请遵守仓库既有纪律：一处事实来源（不要为 agent 再造第二套 URL 校验）、先读后改、修 bug 不顺手重构；`fix.steps` 里的文件清单可直接作为改动白名单。

---

## 七、跨专家转交项

| 交给 | 内容 | 依据 |
|---|---|---|
| **reliability** | 匿名 `POST /agent/memory/relay/drain`、`/agent/memory/reconcile` 可在无 userId 情况下触发全量对账；需确认这两个运维端点在生产是否应可达，并补一次「误触发」的失败模式评估 | `backend-agent/app/main.py:959-975` |
| **correctness** | `AdminTotpController.disable` 在 `totp_secret` 为 NULL 时 `jasypt.decrypt(String.valueOf(row.get("totp_secret")))` 会拿到字符串 `"null"`；`ApiKeyVaultService.isOllamaEndpoint` 用整串 URL 子串匹配 `:11434`/`ollama`，可能误判 | `AdminTotpController.java:41`、`ApiKeyVaultService.java:365-371` |
| **maintainability** | 出站 URL 校验存在两套标准（Java `OutboundUrlValidator` vs Python `dashscope.normalize_base_url`），建议抽成同一份语义说明并互相引用；`internal_signature_window_seconds` 是「声明了但没实现」的配置项 | `backend/lianyu-common/.../OutboundUrlValidator.java`、`backend-agent/app/dashscope.py:56-78`、`backend-agent/app/config.py:57` |
| **ops** | 确认生产 `LIANYU_MASTER_KEY` / `LIANYU_TRUST_FORWARDED_FOR` / `VITE_LIANYU_UPDATE_PUBKEY` 实际取值；若 `LIANYU_MASTER_KEY` 为空，需按 S-04 走重加密 | 见第五节第 3 点 |
| **correctness（附带）** | `backend-agent/app/memory/writer.py` 直接 `INSERT INTO memory_meta`，与 AGENTS.md「Python 只读 MySQL」的边界描述冲突，需要确认这是否是有意为之的例外 | `app/memory/writer.py:173-181` |

---

## 附：证据可复现性说明

- 所有 `file:line` 均基于 `main @ c652e14`，工作区 `git status` 干净。
- critical / high 四条的复现路径均为「一个 curl + 公开代码行」级别，未使用任何猜测。
- 严重度判定遵循：critical = 匿名跨用户数据读写；high = 需单一前置条件即可越权或窃取凭据；medium = 需要额外条件（漏配、本机访问、发布失误）；low = 纵深防御缺失或被现有缓解措施挡住。
