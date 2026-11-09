# 安全审查报告

审查对象：LianYu-PC（予念 PC 端）整仓，12 个审查单元 / 1108 个受审文件 / 约 140294 行（scope.json 口径）。本报告只覆盖 security 面：认证、授权与租户隔离、注入、浏览器安全、上传与 SSRF、密钥、加密、数据暴露、业务逻辑滥用、依赖与供应链。

**结论：4 条高危以上问题需要先处理**——Python agent 的内部接口在公网网关上没有任何鉴权（可匿名读任意用户的记忆与关系数据）、运维后台的登录锁定实际失效且无任何限流、生产口令与平台 API Key 密文进了仓库、NapCat 桥接令牌被写死成常量。用户侧与 Electron 侧的主体加固（Sa-Token 拦截、归属双条件查询、上传重编码、沙箱与更新器签名、NapCat 下载 sha256 固定）基本到位。

## 一、范围与覆盖

- 审查模式：project（整仓，不是 diff）
- 单元：12 个，全部有结论；每个单元实际读过的文件数均 ≥ 30（单元更小则全读），且该单元 `core` 全部读完
- 测试命令：4 条全部执行过，2 条通过、2 条因环境缺依赖失败（详见第四节）

| 单元 | 范围 | 状态 | 已读文件 | 说明 |
| --- | --- | --- | --- | --- |
| u01 | backend/lianyu-service (1/3) | reviewed | 36 | 认证、限流、验证码、AI 网关、会话与角色服务逐个读过（含全部 12 个 core 端口接口）。 |
| u02 | backend/lianyu-service (2/3) | reviewed | 34 | Web Push、TTS 端点校验、文件存储、广场/评论、记忆与通知全量读过，重点核对 SSRF 与归属过滤。 |
| u03 | frontend/src (1/2) | reviewed | 30 | 前端 API 层与 12 个消息/语音组件；v-html、token 存储、IPC 调用逐处核对。 |
| u04 | scripts (1/2) | sampled | 30 | 120 个脚本中读了 30 个核心与生产运维脚本，并对全单元做了口令/密钥/证书校验模式扫描；其余素材生成与诊断脚本未逐行读。 |
| u05 | backend/lianyu-dao | reviewed | 31 | 31 个文件：19 个迁移 + DAO 实体/mapper，核对默认账号、密钥列、索引与加密列语义。 |
| u06 | frontend/src (2/2) | reviewed | 30 | 页面/路由/状态管理 30 个文件：localStorage 内容、v-html、路由守卫、QQ 桥页面令牌展示逐处核对。 |
| u07 | backend-agent | reviewed | 30 | agent 入口、prompt 管线、数据读取层、工具循环、TTS/HTTP 客户端全部读过，据此确认 /agent/** 鉴权缺口。 |
| u08 | backend/lianyu-web | reviewed | 30 | 19 个 controller + 9 个 agent 适配器 + 4 个 Web 配置：每个入口的登录与归属校验逐个核对。 |
| u09 | frontend/electron | sampled | 31 | 68 个文件中读了 31 个（主进程、preload、IPC 代理、更新器、NapCat/微信桥、日志与设置），其余测试与打包脚本仅模式扫描。 |
| u10 | root/.github/admin 等 | sampled | 31 | 67 个文件中读了 31 个：nginx、compose、Dockerfile、安全模块、admin 前端与 CI。 |
| u11 | backend 小模块合集 | reviewed | 39 | admin 后端 27 个类 + 11 个 service 测试：逐端点核对权限串、审计脱敏、限流与登录逻辑。 |
| u12 | lianyu-app/lianyu-common/installer 等 | sampled | 30 | 48 个文件中读了 30 个：公共模块、安装器、脚本与 prod 配置；C# 安装器只核对释放与执行路径。 |

各 section 的检查结论（条数 = 本报告发现数）：

| section | 条数 | 状态 | 备注 |
| --- | --- | --- | --- |
| auth（认证） | 5 | reviewed | 认证链路（Sa-Token 配置、验证码、限流、改密、管理台登录、NapCat/agent 令牌）逐个核对，5 条发现。 |
| authz（授权） | 0 | reviewed | 逐个 controller 的登录与归属校验 + 10 处 IDOR 双条件抽查（CharacterService/CharacterDiaryService/ApiKeyVaultService/ConversationService），未发现越权；agent 侧越权已计入 auth。 |
| injection（注入） | 0 | reviewed | 无 mapper XML，@Select/@Update 全参数化，LIMIT 拼接均已 clamp；前端唯一 v-html 走 DOMPurify；agent SQL 全 `%s` 占位且只读。0 条。 |
| browser（浏览器安全） | 1 | reviewed | CORS 白名单、安全响应头、CSRF（token 头非 cookie）、localStorage 使用与 CSP 覆盖；发现 1 条（admin token 落 localStorage）。 |
| uploads（上传/SSRF） | 1 | reviewed | 上传重编码（8MB/8192/魔数/PNG 重编码）、对象键正则、公开下载前缀；SSRF 面检查到 Web Push 端点，1 条。 |
| secrets（密钥） | 2 | reviewed | 脚本、迁移、yml、.env.example、docker-compose、Electron 打包密钥全量扫描；2 条（生产口令硬编码、迁移密文）。 |
| crypto（加密） | 1 | reviewed | BCrypt(12)、Jasypt PBE 参数、管理员 token SHA-256、TOTP、TLS 校验；1 条（Jasypt fail-open）。 |
| exposure（数据暴露） | 2 | reviewed | 错误响应不泄栈、公开文件接口、日志与 URL 中的凭据、分页上限；2 条。 |
| business-logic（业务逻辑） | 0 | reviewed | 额度/配额（AiChatQuotaService）、订阅与推送、agent 端点滥用路径均已核；可滥用点已并入 auth 的 agent 发现，本 section 0 条。 |
| supply-chain（供应链） | 0 | reviewed | NapCat 固定 sha256 校验、更新器签名与 sha512、CI 工作流权限、依赖清单；frontend 有 package-lock，但 backend-agent 无锁文件且 pyproject 全为 `>=` 下限，**依赖版本未能核实**，因条目上限未单列发现。 |

## 二、发现（按严重度排序）

### 严重（critical）

- [critical] [u07] [plan] SEC-C1 — 任何人不用登录就能调用后端模型服务的内部接口，冒充任意用户拿到别人的聊天记忆和关系数据。
  详细问题：Python agent 的 /agent/** 端点在公网网关上完全没有鉴权，X-User-Id 校验可被省略
  位置：backend-agent/app/main.py:143 · 证据：deploy/api-gateway/nginx.conf:252-284 `location /api/agent/` 直接 `set $agent_upstream http://agent:8001;` + `rewrite ^/api(/agent/.*)$ $1 break;` + `proxy_pass $agent_upstream;`，不经过 Java/Sa-Token；backend-agent/app/main.py:143-151 `user_id_header = request.headers.get("X-User-Id"); if user_id_header and str(body.user_id) != user_id_header: raise HTTPException(403)` —— 不带该头即整段校验跳过；同文件 :657-704 的 prompt_assemble 用同一模式。数据面 backend-agent/app/data_readers.py:26-51 `WHERE user_id=%s AND character_id=%s`，只按调用方给的两个 id 取数。
  影响：匿名攻击者对 https://<gateway>/api/agent/prompt/assemble POST {"userId":<受害者 id>,"characterId":<角色 id>,"useInternalData":true,"scene":"SINGLE"} 即可拿到含受害者记忆、关系状态、角色状态的 systemPrompt；同族 /agent/chat-stream、/agent/memory/write、/agent/tts/synthesize、/agent/desktop-observe 等 20+ 端点同样敞开，可枚举 userId 批量窃取隐私、向他人记忆写入污染数据、并烧掉平台模型额度。
  修复：在网关与 agent 之间补一层服务间认证，并让 Python 侧只信任来自 Java 的身份声明。（1）在 deploy/api-gateway/nginx.conf 的 /api/agent/ 段加 `auth_request` 或在 backend/lianyu-web 暴露一个校验端点，要求携带登录态后再由网关注入 X-Internal-Auth（共享密钥/HMAC，含时间戳防重放），外部同名头一律 strip。 （2）backend-agent/app/main.py 增加全局依赖：校验 X-Internal-Auth 签名与时间窗，缺失即 401；删除 `if user_id_header and ...` 分支，改为强制比对。 （3）agent 侧对 userId 做归属校验：data_readers.py 读 memory/relationship 前先用 conversation/character 归属确认（或由 Java 传已鉴权的 userId 并签名）。 （4）为 /agent/memory/write 这类写端点单独限流并记录审计（谁写入哪个 userId）。 （5）补测试：无签名 401、签名过期 401、userId 与签名不符 403、正常链路 200。
  建议改动文件：deploy/api-gateway/nginx.conf、backend-agent/app/main.py、backend-agent/app/data_readers.py、backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentRuntimeClient.java；验证：pytest -q backend-agent（新增 test_agent_auth.py：缺签名/过期签名/userId 不符均拒绝）；mvn -q -f backend/pom.xml test（AgentRuntimeClient 注入签名的单测）

### 高（high）

- [high] [u11] [plan] SEC-H2 — 运维后台的登录失败锁定其实是失效的，而且登录接口没有任何限流，管理员口令可以被无限次尝试。
  详细问题：管理员登录的 locked_until 用 instanceof java.sql.Timestamp 判断，MySQL 8 返回 LocalDateTime 导致锁定永不生效
  位置：backend/lianyu-admin/src/main/java/com/lianyu/admin/identity/AdminAuthService.java:36 · 证据：AdminAuthService.java:35-39 `Object lockedUntil = row.get("locked_until"); if (lockedUntil instanceof java.sql.Timestamp t && t.toInstant().isAfter(Instant.now())) throw new IllegalStateException("登录暂时锁定");`；:38 失败计数 `failed_attempts=failed_attempts+1, locked_until=CASE WHEN failed_attempts+1>=5 THEN DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 15 MINUTE)`；backend/pom.xml:41 mysql-connector 8.4.0 对 DATETIME(3) 映射为 java.time.LocalDateTime，instanceof 恒 false；AdminAuthController.java:13 只有 `@PostMapping("/login")`，backend/lianyu-admin 全模块无任何 RateLimiter。
  影响：攻击者可以对 /api/admin/v1/auth/login 无限次撞库：5 次失败后的 15 分钟锁定形同虚设，且没有 IP/账号维度限流，一旦口令命中即拿到 8 小时运维令牌（可读用户列表、发公告、管理发版）。
  修复：修正锁定时间类型判断，并给管理员登录加账号 + IP 双维度限流。（1）把 locked_until 取值改为 `rs.getTimestamp("locked_until")` 或统一转 Instant：兼容 LocalDateTime/Timestamp 两种返回类型后再比较。 （2）在 AdminAuthController 登录入口接入与用户侧一致的 Redis 限流（账号 10 次/分钟、IP 30 次/分钟），失败响应统一文案避免账号枚举。 （3）锁定与限流都要写审计（AdminAuditService），并对连续失败触发告警。 （4）补测试：构造 LocalDateTime 的 locked_until 断言抛锁定；5 次失败后第 6 次被拒。
  建议改动文件：backend/lianyu-admin/src/main/java/com/lianyu/admin/identity/AdminAuthService.java、backend/lianyu-admin/src/main/java/com/lianyu/admin/identity/AdminAuthController.java、backend/lianyu-service/src/main/java/com/lianyu/service/auth/AuthRateLimiter.java；验证：mvn -q -f backend/pom.xml test（AdminAuthService 锁定用例 + 限流用例）

- [high] [u04] [plan] SEC-H3 — 生产环境的真实账号口令被写在仓库里的运维脚本里，任何能看代码的人都能直接登录生产后端。
  详细问题：scripts/_prod_verify.py 硬编码生产账号与口令，e2e 脚本另带默认口令并关闭 TLS 校验
  位置：scripts/_prod_verify.py:22 · 证据：scripts/_prod_verify.py:20-23 `BASE = "http://127.0.0.1:8080"` / `USERNAME`（值已隐去）/ `PASSWORD` 写死口令字面量（值已隐去）/ `ENV_FILE = "/opt/lianyu/.env"`，文件头注释说明该脚本在服务器上跑并走完整 Java 链路（登录 + 6 条阻塞对话 + SSE）；scripts/e2e/lianyu_e2e.py:23-27 默认 `BASE_URL=https://156.233.228.18`、`PASSWORD` 写死口令字面量（值已隐去）`SSL_CTX.check_hostname = False` / `SSL_CTX.verify_mode = ssl.CERT_NONE`。
  影响：拿到仓库读权限的人（含外包、镜像、fork、泄露的备份）可直接用该口令登录生产账号，读写该账号的会话与记忆；口令与服务器的 root@156.233.228.18、DEPLOY_SSH_PASSWORD 同处一套运维资产，横向移动风险高。
  修复：立即轮换该账号口令，脚本改为从 .env/密钥管理读取，并清理 git 历史中的明文。（1）在数据库侧重置 <生产账号> 与 e2e 账号口令，并检查这两个账号近期的登录/会话记录是否异常。 （2）把 _prod_verify.py 与 e2e/lianyu_e2e.py 的账号口令改为 `os.environ["LIANYU_PROD_USER"]` / `["LIANYU_PROD_PASSWORD"]`，缺失即报错退出，不再给默认值。 （3）e2e 脚本恢复证书校验（去掉 CERT_NONE），本地自签场景改用显式 CA 文件。 （4）用 git filter-repo 或 BFG 清理历史中的该口令，并在 .gitignore 中排除本地凭据文件。 （5）在 CI 加一条 secret 扫描（gitleaks/trufflehog）阻断同类提交。
  建议改动文件：scripts/_prod_verify.py、scripts/e2e/lianyu_e2e.py、.env.example；验证：python -m py_compile scripts/_prod_verify.py scripts/e2e/lianyu_e2e.py；gitleaks detect --no-git -v（本地跑通基线为空）

- [high] [u09] [plan] SEC-H4 — 本机 QQ 桥接的登录令牌是个写死的常量，用户电脑上任何一个网页都能用它冒充用户给自己的 QQ 好友发消息。
  详细问题：NapCat OneBot/WebUI 令牌被固定为常量 <固定常量>，回环端口对本机任意进程与浏览器页面敞开
  位置：frontend/electron/napcatRuntime/napcatHost.js:139 · 证据：napcatRuntime/napcatHost.js:135-140 注释「token 定死为 <固定常量>（用户指定）……不再随机生成、不读 hosting 残留旧 token」，代码里 `wsToken` 与 `webuiToken` 两个变量都赋成同一个硬编码常量（值已隐去），随后 writeQqBridgeSettings 回写 hosting.webuiToken/wsToken 并作为启动参数传给 NapCat；:200-210 fallback 直接把 WebUI 地址（把访问令牌放进 query 参数）拼出来，`buildWsUrl` 再把 access_token 拼进 ws URL；默认 wsPort 3001、webuiPort 6099 仅监听回环。
  影响：回环端口对「本机任意进程」与「用户浏览的任意网页」都可达：页面可用已知 token 连 ws://127.0.0.1:3001 调 OneBot 接口读取会话列表、以用户身份发送消息/图片，造成账号冒用与隐私泄露；WebUI 6099 同样可被直接登录。
  修复：把桥接令牌改回每台机器随机生成并持久化，同时收紧回环端口的调用方。（1）napcatHost/napcatConfig 生成 32 字节随机 token（crypto.randomBytes）并存入 settings（safeStorage 加密），仅在缺失时生成一次。 （2）启动 NapCat 时通过环境变量或受权限保护的配置文件传 token，不写进命令行（避免本机其它进程 ps 窥视）。 （3）qqBridge 侧校验入向请求来源，只接受主进程发起的连接；WebUI 默认只绑定 127.0.0.1 并要求一次性 token。 （4）升级时检测旧常量 token 并强制轮换，页面上的 QqBridgePage 不再回显明文 token。 （5）补测试：两次启动 token 不同且持久化、旧常量被拒绝。
  建议改动文件：frontend/electron/napcatRuntime/napcatHost.js、frontend/electron/qqBridge/qqBridgeSettings.js、frontend/electron/qqBridge/napCatClient.js、frontend/src/pages/QqBridgePage.vue；验证：npm --prefix frontend test（napcatHost/napCatClient 用例：token 随机化与持久化）

### 中（medium）

- [medium] [u08] [direct] SEC-M5 — 限流用的客户端 IP 取自可被伪造的请求头第一段，攻击者每次换一个假 IP 就能绕过注册和登录的频率限制。
  详细问题：ClientIpResolver 取 X-Forwarded-For 首个值，而 nginx 用 $proxy_add_x_forwarded_for 保留客户端自带前缀
  位置：backend/lianyu-web/src/main/java/com/lianyu/web/util/ClientIpResolver.java:24 · 证据：ClientIpResolver.java:24-29 `if (trustForwardedFor) { String forwarded = request.getHeader("X-Forwarded-For"); ... int comma = forwarded.indexOf(','); return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim(); }`；deploy/api-gateway/nginx.conf:52（及同文件 15 处）`proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;` 会把客户端自带的 XFF 原样前置；backend/lianyu-app/src/main/resources/application.yml:170-172 默认 `lianyu.security.trust-forwarded-for: ${LIANYU_TRUST_FORWARDED_FOR:true}`；调用点 AuthController.java:66/78 用该 IP 做 checkLoginOrRegister 与 `checkRateLimit("rate:register:ip:", ip, 3, Duration.ofDays(1))`。
  影响：攻击者每次请求换一个伪造的首段 IP，就能无限次尝试注册（3 次/日/IP）与登录（30 次/分/IP），把短信/验证码与账号枚举成本压到接近零；配合已存在的验证码弱点可放大为批量注册与撞库。
  修复：只信任最后一跳代理写入的地址：nginx 用 $remote_addr 覆盖 XFF，后端取最右侧可信值。（1）deploy/api-gateway/nginx.conf 改为 `proxy_set_header X-Forwarded-For $remote_addr;`（单层网关足够），并对外部传入的同名头做 `proxy_set_header X-Forwarded-For "";` 清理。 （2）ClientIpResolver 改为取 XFF 最后一个值（`lastIndexOf(',')`），或直接读 X-Real-IP，并在注释中写明信任层数。 （3）把 application.yml 的 trust-forwarded-for 默认改为 false，仅在网关部署的 profile 中开启。 （4）补测试：伪造 `X-Forwarded-For: 1.2.3.4` 时解析结果仍为真实来源；无头时回落 remoteAddr。
  建议改动文件：backend/lianyu-web/src/main/java/com/lianyu/web/util/ClientIpResolver.java、deploy/api-gateway/nginx.conf、backend/lianyu-app/src/main/resources/application.yml；验证：mvn -q -f backend/pom.xml test（ClientIpResolver 新增伪造 XFF 用例）

- [medium] [u01] [direct] SEC-M6 — 用户改密码之后，之前登录过的设备并不会被踢下线，旧凭证还能继续用一个月。
  详细问题：changePassword 只更新 password_hash，不吊销任何既有会话；sa-token 有效期 30 天且不因空闲失效
  位置：backend/lianyu-service/src/main/java/com/lianyu/service/auth/impl/AuthServiceImpl.java:154 · 证据：AuthServiceImpl.java:154-162 `user.setPasswordHash(passwordEncoder.encode(request.getNewPassword())); userMapper.updateById(user);` 之后只有日志，没有 StpUtil.logout/kickout/会话清理；backend/lianyu-app/src/main/resources/application.yml:133-141 sa-token `timeout: 2592000`（30 天）、`active-timeout: -1`、`is-concurrent: true`。
  影响：账号在他人设备上留存的 token 在改密后仍有效 30 天：用户发现异常后改密码并不能止损，被盗 token（例如日志泄漏、旧客户端明文降级）可继续读写全部会话与记忆。
  修复：改密成功后立即吊销该用户的全部会话，并要求重新登录。（1）在 AuthServiceImpl.changePassword 更新口令后调用 `StpUtil.logout(userId)`（或 `StpUtil.kickout(userId)`）清理该用户全部 token。 （2）若产品要求当前设备保持登录，则在改密接口返回新 token，其余会话一律失效。 （3）同步检查 password reset 路径是否也需要同样的吊销动作。 （4）补测试：改密后旧 token 访问 /api/auth/me 返回 401。
  建议改动文件：backend/lianyu-service/src/main/java/com/lianyu/service/auth/impl/AuthServiceImpl.java、backend/lianyu-web/src/main/java/com/lianyu/web/controller/AuthController.java；验证：mvn -q -f backend/pom.xml test（AuthServiceImplChangePassword 用例：旧 token 失效）

- [medium] [u05] [plan] SEC-M7 — 平台自带的 API Key 以密文形式被提交进数据库迁移文件，密文和主密钥都在同一套运维资产里，等于密钥已经外泄。
  详细问题：V22 迁移把 10 条平台 API Key 密文写进仓库，V49 只删行不删历史
  位置：backend/lianyu-dao/src/main/resources/db/migration/V22__team_default_vault_pool_encrypted.sql:5 · 证据：V22__team_default_vault_pool_encrypted.sql:5-14 共 10 条 `UPDATE api_key_vault SET 写入 `api_key_encrypted`（<base64 密文占位>）、`key_version`='v1'、`enabled`=1、 base_url='https://clove.dpdns.org', model_default='deepseek-v4-flash', remark='default-pool-N' ...`；V49__drop_default_vault_pool.sql:3 只 `DELETE FROM api_key_vault WHERE vault_scope='DEFAULT' AND provider='platform'`；解密钥来自服务器 .env 的 LIANYU_MASTER_KEY（backend/lianyu-security/.../JasyptUtil.java:20-59）。
  影响：任何拿到仓库（含历史、镜像、备份）并可访问部署 .env 的人都能还原出这 10 个平台 Key，用平台额度调用模型；即使行已被删除，密文仍永久留在 git 历史里，属于「已外泄密钥」。
  修复：轮换这 10 个平台 Key，改为运行时注入，并从历史中清除密文。（1）在模型服务侧吊销/轮换 default-pool 的 10 个 Key，检查近期调用量与异常来源。 （2）删除 V22 迁移中的密文 UPDATE（新环境不再 seed），改由部署脚本从环境变量写入 vault 表。 （3）用 git filter-repo 清理历史中的 V22 文件内容，或至少接受并记录该批次密钥作废。 （4）为 vault 增加「运行时导入」的管理入口，禁止任何含密文的迁移文件进入仓库（CI 加检测）。
  建议改动文件：backend/lianyu-dao/src/main/resources/db/migration/V22__team_default_vault_pool_encrypted.sql、backend/lianyu-dao/src/main/resources/db/migration/V49__drop_default_vault_pool.sql、scripts/_cloud_deploy_pull.py；验证：mvn -q -f backend/pom.xml test（Flyway 迁移在新库上跑通，且 vault 表为空）

- [medium] [u02] [direct] SEC-M8 — 登录用户可以让服务器向任意地址发起请求，包括内网和云厂商的元数据地址，从而探测内网或拿到实例凭据。
  详细问题：Web Push 订阅端点未经任何校验就由服务端 POST，可指向内网/元数据地址
  位置：backend/lianyu-service/src/main/java/com/lianyu/service/notification/WebPushService.java:95 · 证据：WebPushService.java:95-103 `Notification notification = new Notification(sub.getEndpoint(), sub.getP256dh(), sub.getAuth(), json); HttpPost post = service.preparePost(notification, Encoding.AESGCM); HttpResponse response = httpClient.execute(post);`，`buildHttpClient` 只设 RequestConfig，全类无 OutboundUrlValidator/主机白名单；端点来源 backend/lianyu-web/.../controller/NotificationController.java:69-74 `POST /api/notifications/push/subscribe` → NotificationService.upsertPushSubscription(userId, request)，DTO PushSubscriptionRequest 只 `@NotBlank endpoint/p256dh/auth`。
  影响：任意登录用户提交 endpoint=http://169.254.169.254/... 或 http://127.0.0.1:8080/... 后触发发送，服务端即向该地址发起带 VAPID 头的 POST；可用于内网端口探测、打内部服务（盲 SSRF），并可能读取云元数据服务响应中的实例信息。
  修复：订阅时用 OutboundUrlValidator 校验并固定解析结果，发送时只连白名单主机。（1）NotificationController.subscribe 落库前调用 `OutboundUrlValidator.validateAndResolve(endpoint, false)`，失败抛 BAD_REQUEST。 （2）WebPushService 改用 SsrfPinningClientFactory 构建的客户端，连接固定到校验时的 IP，禁止跟随重定向。 （3）限制 endpoint 长度与协议（仅 https），并对单用户订阅数量设上限。 （4）补测试：endpoint 指向 127.0.0.1/169.254.169.254/localhost 时被拒。
  建议改动文件：backend/lianyu-service/src/main/java/com/lianyu/service/notification/WebPushService.java、backend/lianyu-web/src/main/java/com/lianyu/web/controller/NotificationController.java、backend/lianyu-common/src/main/java/com/lianyu/common/util/OutboundUrlValidator.java；验证：mvn -q -f backend/pom.xml test（NotificationServiceTest 新增非法 endpoint 用例）

- [medium] [u10] [direct] SEC-M9 — 一旦服务器漏配主密钥，用户的 API Key 会被明文存进数据库，而且只在日志里留一句警告，没人会发现。
  详细问题：JasyptUtil 在缺少 LIANYU_MASTER_KEY 时静默降级为返回明文，加密列里存的是明文
  位置：backend/lianyu-security/src/main/java/com/lianyu/security/util/JasyptUtil.java:61 · 证据：JasyptUtil.java:20-27 构造器在 `LIANYU_MASTER_KEY` 缺失时只 `log.warn(...)` 后返回（encryptors 为空）；:61-73 encrypt() 在 `currentVersion == null || encryptors.isEmpty()` 或取不到 encryptor 时 `log.warn("No Jasypt encryptor available, returning plaintext"); return plaintext;`；:75-80 decrypt() 同样原样返回。调用方 ApiKeyVaultService 把结果直接写进 api_key_vault.api_key_encrypted（列注释仍写 encrypted）。
  影响：部署漏配主密钥（新环境、容器未挂 .env、轮换失败）时，所有用户与平台 API Key 明文落库，备份/binlog/只读副本里都是明文；补救密钥后系统也无法区分历史明文行，需要人工逐行迁移，等于长期静默泄漏。
  修复：改为 fail-closed：缺少主密钥时启动失败或写入报错，绝不返回明文。（1）JasyptUtil 构造器在密钥缺失时抛 IllegalStateException（或让应用启动失败），移除「return plaintext」分支。 （2）encrypt() 取不到 encryptor 时抛 BusinessException(API_KEY_ENCRYPT_FAILED)，不写库。 （3）decrypt() 对不含 vN: 前缀的值拒绝解密并抛错，提示需要迁移。 （4）加一条 Flyway 校验/启动自检：扫描 api_key_encrypted 是否存在非 vN: 前缀的行并告警。 （5）补测试：无密钥时 encrypt 抛异常、decrypt 对明文抛异常。
  建议改动文件：backend/lianyu-security/src/main/java/com/lianyu/security/util/JasyptUtil.java、backend/lianyu-security/src/main/java/com/lianyu/security/handler/EncryptedStringTypeHandler.java、backend/lianyu-service/src/main/java/com/lianyu/service/ai/ApiKeyVaultService.java；验证：mvn -q -f backend/pom.xml test（JasyptUtil 缺密钥用例）

- [medium] [u10] [plan] SEC-M10 — 运维后台把管理员登录凭证明文存在浏览器存储里，任何能在页面里执行脚本的东西都能把它读走。
  详细问题：admin 管理台把 8 小时有效的管理员令牌明文存入 localStorage
  位置：admin/src/stores/session.js:2 · 证据：admin/src/stores/session.js:2 把登录令牌放进 `token` 字段并持久化到 localStorage 的 `lianyu-admin-token`；admin/src/api.js:2-4 `baseURL = import.meta.env.VITE_ADMIN_API_ORIGIN || 'https://downloadlianyu.asia'` 并在拦截器里用该 token 塞 `lianyu-token` 头；admin/electron/main.js:5-7 窗口 contextIsolation/ nodeIntegration 已关闭但未设 sandbox，页面无 CSP；令牌有效期 8 小时（AdminAuthService.java:48）。
  影响：管理台任意 XSS、被投毒的第三方依赖或恶意 DevTools 扩展都能直接读出该令牌，之后 8 小时内以超管身份调用 /api/admin/v1/**（用户管理、发版、公告）；相比会话 cookie 方案，localStorage 无 HttpOnly 保护。
  修复：改为内存持有 + 短时刷新，或退回 HttpOnly Cookie，至少不再持久化到 localStorage。（1）admin 登录成功后只把 token 放在内存 store（Pinia），页面刷新改为静默刷新换取新 token。 （2）后端 admin_session 增加刷新接口与轮换逻辑，把 8 小时拆成短 access + refresh。 （3）如必须持久化，至少用 Electron safeStorage 存到主进程，渲染进程通过 IPC 申请，不用 localStorage。 （4）给管理台页面加 CSP（default-src 'self'，禁 inline script）并开启沙箱。
  建议改动文件：admin/src/stores/session.js、admin/src/api.js、admin/electron/main.js、backend/lianyu-admin/src/main/java/com/lianyu/admin/identity/AdminAuthService.java；验证：npm --prefix admin test（session store 用例：刷新后需重新认证，localStorage 无 token）

### 低（low）

- [low] [u08] [direct] SEC-L11 — 公开文件下载接口直接照 URL 取存储对象，没有限定目录，任何对象只要猜到名字就能匿名下载。
  详细问题：/api/public/files/** 按原始 URI 截取对象键，缺少前缀白名单
  位置：backend/lianyu-web/src/main/java/com/lianyu/web/controller/PublicFileController.java:41 · 证据：PublicFileController.java:31-79 `GET /api/public/files/**` 匿名可访问（SaTokenConfig notMatch "/api/public/**"），extractObjectKey 用 `request.getRequestURI().indexOf(PUBLIC_FILE_PREFIX)` 之后整段作为对象键，未限定 avatars/ updates/ chat-voice/ 等前缀；仅第 41 行 `isDangerousPublicContentType`（text/html、xhtml、svg、javascript）返回 404；响应带 nosniff 与 `Access-Control-Allow-Origin: *`。对象键来自 FileStorageService.java:32-38（avatars/<uuid>.png、chat-images/…、chat-voice/<petId>/…、custom-voices/<uid>/…）。
  影响：一旦对象键可预测（用户 id 组成的 custom-voices 前缀、时间戳命名的音频），他人可绕过业务接口直接下载语音/图片；也把 MinIO 中任何未来新增的桶暴露在匿名读面上，缺少纵深防御。
  修复：给公开文件接口加对象键前缀白名单，并校验键格式。（1）在 extractObjectKey 之后加白名单校验：只允许 `avatars/`、`chat-images/`、`chat-voice/`、`custom-voices/`、`updates/` 前缀，其余 404。 （2）复用 FileStorageService 的 SAFE_OBJECT_KEY 正则拒绝 `..`、空段、编码绕过（先 URL 解码再校验）。 （3）对 custom-voices 这类含用户 id 的键改为随机文件名，避免可枚举。
  建议改动文件：backend/lianyu-web/src/main/java/com/lianyu/web/controller/PublicFileController.java、backend/lianyu-service/src/main/java/com/lianyu/service/storage/FileStorageService.java；验证：mvn -q -f backend/pom.xml test（PublicFileController 前缀白名单用例：非法前缀 404）

- [low] [u08] [direct] SEC-L12 — 语音通话的 WebSocket 还接受把登录凭证放在网址里，凭证会因此被写进服务器访问日志。
  详细问题：/ws/voice 保留 query 传 token 的回退分支，30 天有效令牌会落入 nginx access log
  位置：backend/lianyu-web/src/main/java/com/lianyu/web/config/VoiceWebSocketConfig.java:42 · 证据：VoiceWebSocketConfig.java:39-49 握手时先取 `String token`，只要非空就 `return bindToken(token, attributes)` 认定身份；注释自认「query 传 token 仅作旧客户端兼容回退（会进 nginx access log，将在客户端全面切换后移除）」；:65-79 bindToken 直接 `StpUtil.getLoginIdByToken(token)` 认定身份；客户端 frontend/src/composables/useVoiceDuplex.js:173-207 已改为首帧 `{type:'auth',token}` 且注释明确「URL 不再携带 token」，说明该回退分支已无调用方；令牌有效期 30 天（application.yml:133-141）。
  影响：只要有人（旧客户端、脚本、攻击者构造）用 query 参数携带令牌这种 30 天有效的登录凭证就会以明文写进 nginx access log、代理日志与链路追踪，读日志的运维/外包即可复用该 token 长期冒用账号。
  修复：删除 query token 回退分支，只允许首帧认证。（1）VoiceWebSocketConfig.beforeHandshake 去掉 extractToken/bindToken 分支，统一走首帧认证，未认证连接在首帧超时后关闭。 （2）删除 extractToken 方法及相关 import，避免后续再被复用。 （3）在 nginx 配置中对含 token 的 query 做日志脱敏（`log_format` 去掉 $args）作为兜底。
  建议改动文件：backend/lianyu-web/src/main/java/com/lianyu/web/config/VoiceWebSocketConfig.java、frontend/src/composables/useVoiceDuplex.js；验证：mvn -q -f backend/pom.xml test（voice WS 握手用例：带 query token 不再认证）

## 三、已查未发现问题的区域

- **授权 / 租户隔离（authz，0 条）**：`CharacterService.findOwned`、`CharacterDiaryService.getDiary`、`ApiKeyVaultService.update/delete`、`ConversationService.findOwned`、`ConversationAccessService.assertUserOwnsConversation` 等 10+ 处抽查均为「主键 + userId」双条件；全部 controller 先 `StpUtil.getLoginIdAsLong()` 再下传 userId；admin 端点逐条带 `authorization.require("<permission>")`。唯一的越权面在 agent 侧，已计入 auth（SEC-C1）。
- **注入（injection，0 条）**：`backend/lianyu-dao/src/main/resources` 无 mapper XML；`@Select/@Update` 全参数化；所有 `last("LIMIT " + …)` 前都有 `Math.min(n, Math.max(1, x))` 夹取；agent 侧 SQL 只用 `%s` 占位且连接为只读池（`db.py` 只允许 SELECT）；前端唯一 `v-html` 经 DOMPurify 白名单（`frontend/src/pages/MomentsPage.vue:146`），`LandingPage.vue:122` 的 icon 来自静态数据。
- **上传（不含 SSRF 部分）**：`ImageUploadValidator` 做 8MB/8192 像素上限、魔数校验、`ImageIO` 解码后重编码 PNG；对象键走 `SAFE_OBJECT_KEY` 正则；公开下载对 html/svg/js 类型返回 404 且带 nosniff。
- **浏览器安全（除 admin token 一条）**：`/api/**` CORS 为 origin 白名单且不带 `*`；鉴权用请求头而非 Cookie，无 CSRF 面；`X-Frame-Options: DENY`、`Referrer-Policy`、`Permissions-Policy`、`nosniff` 均已设置；前端 token 只驻内存（`frontend/src/utils/secureToken.js`）。
- **Electron 加固**：`contextIsolation: true`、`nodeIntegration: false`、`sandbox: true`（除 launcher 与 admin 窗口）；preload 只暴露白名单 IPC；`api:request` 校验可信 frame 与 egress 白名单，并由主进程统一注入 Authorization（渲染进程无法伪造或回读 token）；更新器验签 latest.yml + sha512 校验，NapCat 固定 sha256 并回落 pin。
- **密钥卫生（除两条发现）**：`.env.example` 只有 `change-me-*` 占位；生产脚本从环境读 `DEPLOY_SSH_PASSWORD`；管理员 TOTP 秘密经 Jasypt 加密存库；审计日志对 password/token/secret/key 字段打码。
- **业务逻辑滥用（0 条）**：额度（`AiChatQuotaService`）、推送订阅、发版与支持授权码（SecureRandom 24 字节、SHA-256 存、15 分钟、单次）链路均已核对；可滥用点主要在 agent 端点，已并入 SEC-C1 的影响描述。

## 四、抽样与未验证说明

- 抽样单元：**u04（scripts 30/120）**、**u09（electron 31/68）**、**u10（67 个中 31 个）**、**u12（30/48）** 标为 sampled —— 读了核心与高危面文件并做了全单元模式扫描（`password|token|secret|exec|innerHTML|v-html|CERT_NONE|http://` 等），但**不能**视为全量逐行读过；这些单元内未命中的子文件仍可能存在本报告未覆盖的问题。
- 未做动态验证：没有可运行的实例与生产数据库，全部结论来自源码与部署配置的静态路径；SEC-C1 的严重度依赖「api-gateway 已对外暴露 `/api/agent/`」这一部署事实（`deploy/api-gateway/nginx.conf:252`），若线上网关未启用该 location，可按 high 处理。
- 管理台锁定失效（SEC-H2）的判定依赖 MySQL Connector/J 8.4 对 `DATETIME(3)` 返回 `java.time.LocalDateTime`；未在真实库上跑过用例，故 confidence 记 high 但建议用一条单测固化。
- Web Push SSRF（SEC-M8）为盲打：服务端只回写通知结果，攻击者无法直接读响应体，因此影响评估以「内网探测 + 打内部端点」为上限。
- 依赖版本未能核实：`backend-agent/pyproject.toml` 全部是 `>=` 下限且无 lock 文件，`deploy/asr/requirements.txt` 仅 5 行；`frontend` 有 package-lock 但未逐条比对 CVE，所以 supply-chain 记 0 条**不代表依赖是干净的**。
- 测试结果：`mvn -q -f backend/pom.xml test` 与 `npm --prefix frontend test`（75 files / 459 tests）通过；`pytest -q backend-agent` 在收集阶段失败（`ModuleNotFoundError: app`，进目录后为 `aiomysql` 缺失）；`npm --prefix admin test` 因未安装 node_modules 起不来（`'vitest' 不是内部或外部命令`）。两条失败都是环境问题，没有据此判定代码缺陷。

## 五、建议修复顺序

1. **SEC-C1（agent 无鉴权）**：先确认线上是否真的暴露 `/api/agent/`；若是，立即在网关加 `auth_request` 或直接把该 location 关掉，再做服务间签名。
2. **SEC-H2（管理台登录）+ SEC-H3（生产口令）**：这两条都通向「拿到运维权限」，应先轮换口令/收紧登录，再补锁定类型判断与限流。
3. **SEC-H4（NapCat 常量 token）**：改回随机生成并持久化，同时收回页面上的 token 回显。
4. **SEC-M5/M6/M7/M8/M9/M10**（XFF 伪造、改密不吊销、迁移密文、Web Push SSRF、Jasypt 明文降级、admin token 落 localStorage）：都是局部可控的改动，可并行；其中 Jasypt 与迁移密文建议同一次发布内完成（涉及密钥轮换）。
5. **SEC-L11/L12**（公开文件前缀、语音 WS query token）：防御纵深，随下个版本清理即可。

## 六、跨专家转交项

- reliability: backend-agent 的 pytest 在仓库根无法收集（ModuleNotFoundError: app / aiomysql），CI 门禁依赖未落地，建议由可靠性专家确认测试环境链路。
- reliability: admin 前端未安装依赖导致 `npm --prefix admin test` 无法执行，建议纳入 CI 安装步骤。
- correctness: /ws/voice 首帧认证与 VoiceDuplexHandler 的状态机在断线重连、超时关闭上的边界行为未做黑盒验证。
- maintainability: deploy/api-gateway/nginx.conf 中 X-Forwarded-For 写法在 15 处重复，改口径时容易漏改。

以上转交项只做记录，未计入本报告发现数；对应的判断留给 correctness / reliability / maintainability 专家。

