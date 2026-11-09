# 予念（YuNian）PC 端 · 全项目安全审查报告

- 运行：`/logi-security` · run `20261104-172545-309806`
- 范围：**全项目** 1100 个文件 / 12 个审查单元 / 135978 行（`mode: project`，不是 diff，不是最近一次提交）
- Git：分支 `main` · HEAD `c652e14`（fix(relationship): 修正被刷高的历史信任分（V57））· 工作区干净
- 语言构成：Java 450 · JavaScript 252 · Python 220 · SQL 60 · Vue 57
- 专家：security（1/1 完整）；perf 为可选专家，本次未选择

## 一句话结论

最高危的一条是**公网可达的 `/api/agent/**` 反代在 Python 侧没有任何真实认证**——匿名请求即可用任意 `userId` 写记忆、冒名对话、删他人音色；它与「agent 出站 URL 无校验」组合成匿名盲 SSRF。其余 5 条属于会话/加密降级、私有对象匿名读取、部署与 CI 加固层面的中低风险项；Java 侧鉴权、租户过滤、SQL 参数化、前端 XSS、Electron 隔离在本轮检查范围内未发现问题。

## 覆盖矩阵（单元 × 专家）

| 单元 | 标签 | security | 说明 |
| --- | --- | --- | --- |
| u01 | backend/lianyu-service (1/3) | reviewed | 认证/会话/对象级归属（`AuthServiceImpl`、`AuthRateLimiter`、`ConversationService.findOwned`） |
| u02 | backend/lianyu-service (2/3) | reviewed | 存储上传白名单、记忆查询按 userId 过滤、MQ/通知路径 |
| u03 | frontend/src (1/2) | sampled | token 存储（内存 + safeStorage）、请求头注入；`v-html/innerHTML` 全量命中逐条判定 |
| u04 | scripts (1/2) | sampled | 全量模式扫描 176 文件；硬编码密钥 0 命中，命中部署脚本凭据卫生问题 |
| u05 | backend/lianyu-dao | reviewed | 111 个 Java 文件 `${}` 0 命中、无 mapper XML、Flyway V1–V57 无 SQL 注入面 |
| u06 | backend-agent | reviewed | 25 个路由逐个核对鉴权、网关出站、只读/写库白名单 |
| u07 | frontend/src (2/2) | sampled | `sanitizeHtml(DOMPurify)`、弹幕清空、静态 `window.open`、CSP |
| u08 | backend/lianyu-web | reviewed | `SaTokenConfig` 放行面、CORS、WebSocket 订阅归属、`PublicFileController`、限流+captcha |
| u09 | frontend/electron | reviewed | `webPreferences`（contextIsolation/sandbox/无 nodeIntegration）、`setWindowOpenHandler` 白名单、apiProxy egress 白名单、证书指纹固定 |
| u10 | 其他模块（(root)、.github、admin 等） | reviewed | docker-compose 端口绑定、CI action 固定方式、admin Electron 加固 |
| u11 | backend（小模块合集） | sampled | `OutboundUrlValidator`、`SsrfPinningClientFactory` 与出站调用点核对 |
| u12 | 其他模块（lianyu-app、lianyu-common 等） | sampled | Sa-Token 参数、prod 关闭 api-docs、CORS/WS origin 取环境变量 |

- 9 个 `reviewed`、3 个 `sampled`（u03/u04/u07/u11/u12 中体量大者按模式扫描 + 关键文件抽查）、**0 个 skipped**。
- `sampled` 不等于全量已读：u04（scripts，120 文件/12803 行）只对凭据与危险命令模式做了全量 grep，未逐文件通读；u07（frontend/src 2/2，95 文件/24648 行）与 u12 同理。

## 发现（按模块组织）

### 1. 网关与模型运行时（u10 / u06）— 最需要先处理

#### SEC-B2BD5 · 严重 / 高置信 · `deploy/api-gateway/nginx.conf:252`

- **问题**：443 公网 server 里的 `location /api/agent/` 只做限流与 `rewrite`/`proxy_pass`，不校验 Sa-Token，也不剥离客户端自带的 `X-User-Id`；而 `backend-agent/app/main.py` 的 25 个路由里，19 个只有 `if user_id_header and str(body.user_id) != user_id_header: 403` 这种「可选头」判断——不带就跳过，带了也只是与客户端自己填的 `body.user_id` 自洽。`/agent/prompt/render`(614)、`/agent/prompt/scene-instruction`(639)、`/agent/memory/relay/drain`(959)、`/relay/requeue`(967)、`/agent/memory/reconcile`(974)、`/agent/health`(102)、`/agent/capabilities`(107) 连这层都没有。
- **证据要点**：`/agent/memory/write`(982-1003) 与 `/agent/memory/extract-write`(912-957) 直接 `write_memories(body.user_id, ...)`；agent 用 `${MYSQL_USER:-lianyu}` 连库（`docker-compose.yml:112-116`，即 Java 同款可写账号），写权限只靠 `app/write.py` 的语句/表白名单（`memory_meta`、`memory_outbox`），不校验归属；agent 无 `ports`，唯一外网入口就是这条 nginx location。
- **影响**：未登录即可为任意用户写/覆盖记忆（进而向受害者后续对话注入伪造记忆）、冒用任意 `userId` 对话与语音、删除他人音色、外部驱动 outbox 投影。
- **修复摘要**：网关侧 `allow` 内网段 + `deny all` + 显式 `proxy_set_header X-User-Id ""`；Python 侧加 `X-Agent-Token` 共享密钥依赖与**必填** `X-User-Id`；补齐无校验路由；Java 侧透传密钥；保持 agent 不发布宿主端口。
- 备注：handoff 原文称 agent「持只读凭据」，实测是主账号 `lianyu`，影响比原文更重，已按实测改写。

#### SEC-4D2AD · 高 / 高置信 · `backend-agent/app/gateway.py:413`

- **问题**：`normalize_openai_base_url`(92-111) 只做字符串拼接，无 host 白名单；`_do_post`(413-432) 直接 `client.post(url, ...)`；`http_client.new_client()`(24-40) 只设超时/UA/local_address。`base_url` 来自请求体 `textBaseUrl`（`main.py:774/787-795`、`89/168` → `desktop_observe.py:86-101`、`1026/1077` → `graphs/proactive_turn.py:118,149`）。Java 侧有 `OutboundUrlValidator`+`SsrfPinningClientFactory`，Python 侧无等价物。
- **影响**：配合 SEC-B2BD5 即匿名盲 SSRF——向 `mysql:3306`、`redis:6379`、`169.254.169.254` 发带 Bearer 头的 POST，按错误差异/耗时探测内网；若内网返回可解析的 chat completion JSON，内容会作为模型输出回到调用方。
- **修复摘要**：新增 `app/url_guard.py`（协议白名单 + 私网/环回/链路本地/元数据拒绝 + 解析后二次校验 + DNS pinning），所有透传 `base_url` 的调用点统一校验，内网自建模型走显式白名单。

### 2. 文件与数据暴露（u08 / u02）

#### SEC-79B9D · 中 / 高置信 · `backend/lianyu-web/src/main/java/com/lianyu/web/controller/PublicFileController.java:31`

- **问题**：匿名端点 `GET /api/public/files/**` 接受任意通过 `SAFE_OBJECT_KEY` 形状校验的对象键，而该正则（`FileStorageService.java:50-52`）**包含私有前缀** `chat-voice/<petId>/<file>` 与 `custom-voices/<userId>/<file>`；响应固定 `ACAO: *`（62）与 `Cache-Control: public, max-age=86400`（78-88），nginx 还为 `/api/public/files/chat-voice/` 单独加了 `ACAO:*` 反代（168-190）。
- **证据修正**：handoff 原文说「没有对 objectKey 套 SAFE_OBJECT_KEY」——实测 `statObject/getObject` 内部都调了 `validateObjectKey`（434-450、558-564）。但该正则是形状白名单而非公开/私有边界，私有前缀照样放行，结论不变。
- **影响**：用户自录音色、聊天 TTS 等私有语音可被匿名拉取并被 24h public cache + 任意 Origin 放大，无吊销手段；因 key 含随机 uuid，无法批量枚举，故中危。
- **修复摘要**：公开端点收敛为显式公开前缀白名单；私有前缀迁到需登录+归属校验的 `/api/media/**`（或短时预签名）；非公开前缀去掉 `ACAO:*` 与 public 缓存；nginx 同步。

### 3. 认证与会话（u01 / u12）

#### SEC-B7C0A · 中 / 高置信 · `backend/lianyu-service/src/main/java/com/lianyu/service/auth/impl/AuthServiceImpl.java:154`

- **问题**：`changePassword`(154-162) 只更新 `password_hash`，无 `StpUtil.kickout`、无 session 版本号；全仓 `kickout|session_version|sessionVersion` 在 `backend/**/*.java` 零命中。`refresh`(90-99) 每次 `renewTimeout(2592000)`，配置 `active-timeout: -1`、`is-concurrent: true`、`is-share: false`（`application.yml:136-139`），无绝对上限。
- **影响**：凭据泄露后改密无法止损，旧 token 可无限续期 30 天；多设备各自独立 token，无法单点吊销。
- **修复摘要**：加 `session_version` 列并在鉴权时比对；改密即 `kickout` 全端；refresh 加绝对过期（如 90 天）；补「在其它设备登出」。

### 4. 加密（u11）

#### SEC-C62DD · 中 / 中置信 · `backend/lianyu-security/src/main/java/com/lianyu/security/util/JasyptUtil.java:65`

- **问题**：缺 `LIANYU_MASTER_KEY` 时构造函数只 warn 后 return（21-25），`encrypt()` 在无 encryptor 时 `return plaintext`（62-71），`decrypt()` 原样返回（75-80）；`EncryptedStringTypeHandler.setNonNullParameter`(16-19) 直接调它。
- **影响**：漏配一个环境变量即静默退化为无加密，模型 API Key、管理员 `totp_secret` 明文进 MySQL 并随备份/只读副本/慢日志扩散，事后无法区分明文行。
- **修复摘要**：prod 缺 key 启动失败 + `encrypt()` 抛异常（fail-closed）+ 启动自检扫描历史明文行 + compose `${LIANYU_MASTER_KEY:?}` + 一次性重加密脚本。

### 5. 运维脚本与供应链（u04 / u10）

#### SEC-44999 · 低 / 中置信 · `scripts/_cloud_deploy_pull.py:125`

- **问题**：`HOST = "156.233.228.18"`、`USER = "root"`（9-10），以 root 口令 SSH（49-56）；`redis-cli -a "${REDIS_PASSWORD}"` 把口令放在命令行（125-126，同类 `_cloud_deploy_layered.py:97-98`；硬编码主机另见 `_cloud_deploy_layered.py:46`、`_cloud_env_check.py:40`）。全仓 `sk-/AIza/ghp_/PRIVATE KEY` 实值扫描 0 命中。
- **影响**：redis 口令出现在服务器 `ps`、容器 exec 日志与 shell history；生产 IP + root 账号是明确的攻击目标画像（口令本身未入库，故低危）。
- **修复摘要**：`REDISCLI_AUTH` 环境变量替代 `-a`；deploy 账号 + SSH key；HOST/USER 外移 `.env`；轮换口令；CI 加 gitleaks。

#### SEC-33910 · 低 / 高置信 · `.github/workflows/ci.yml:47`

- **问题**：`actions/checkout@v4`(14/28/45)、`setup-java@v4`(15)、`setup-node@v4`(29)、`osv-scanner-action@v2.0.3`(48) 全是可变 tag；deps-audit 的 `continue-on-error: true`(47) 让漏洞扫描永不阻断。
- **影响**：上游 action 被劫持/tag 被重写可在构建机执行任意代码并读取发布 secrets；已知依赖漏洞不阻断合并。
- **修复摘要**：四个 action 固定 40 位 SHA；移除 `continue-on-error`；豁免改仓库内 osv 忽略清单；开 dependabot github-actions。

## 跨单元的系统性问题

1. **「内网通道」被当成「可信通道」**（u10 网关 + u06 运行时）：`/api/agent/**` 公开可达、且 Python 侧用「客户端自报 `X-User-Id`」代替认证。同一根因在三处表现：无认证、无出站校验（SSRF）、匿名信息端点。修一处不够，必须网关 + 运行时双层同时收口。
2. **「形状校验」被当成「权限校验」**（u08 + u11）：`SAFE_OBJECT_KEY` 本意是防路径穿越/非法字符，被当成公开/私有边界，私有前缀照样从匿名端点出。同类思路在 `/api/public/files/updates/` 的精细前缀规则上做得对，未复制到 voice 前缀。
3. **安全控制默认 fail-open**（u01 会话 + u11 加密）：缺配置/缺步骤时系统继续工作（改密不踢人、缺密钥返回明文、token 无限续期），错误只体现在一行 warn 或完全没有日志。建议统一「生产缺配置即启动失败」。
4. **部署与 CI 凭据卫生**（u04 + u10）：`-a` 口令、root 口令 SSH、可变 tag action，属同一条「运维侧信任假设过宽」链路。

## 建议修复顺序

1. **立刻（同一批必须一起上）**：SEC-B2BD5 + SEC-4D2AD。两者同属 `/api/agent/**` 入口，只修一个仍然可打（修网关不修 `base_url` 仍能 SSRF；修 SSRF 不修认证仍能跨用户写记忆）。同批改 `nginx.conf`、`backend-agent/app/main.py`、新增 `app/security.py`、`app/url_guard.py`、`AgentRuntimeClient.java`、`compose`/`.env.example`。
2. **本迭代**：SEC-79B9D（`PublicFileController` + `FileStorageService` + nginx 三处同改，含私有语音迁移与旧 URL 兼容）、SEC-C62DD（fail-closed + 启动自检，注意先做明文行普查再切）。
3. **下迭代**：SEC-B7C0A（需 Flyway 迁移 + 全端重新登录，建议与客户端发版节奏对齐）。
4. **随手批次**：SEC-33910（纯 yml，direct，可单独 PR）；SEC-44999（运维改动，需服务器上先建 deploy 账号再切脚本，不能只改代码）。

## 覆盖缺口与未验证项

- u03/u04/u07/u11/u12 为 `sampled`：做了模式扫描 + 关键文件确认，**没有**逐文件通读；这些单元里「未发现问题」只代表已扫模式未命中。
- `.env.example` 未能读取（被凭据策略拒绝），环境变量完整性以 `docker-compose.yml` 与 `application.yml` 占位为据，需人工确认无真实值。
- 未做动态验证：本轮无实际运行实例，SEC-B2BD5/SEC-4D2AD 的「可复现」依据是代码路径与 nginx location 可达性，不是实打请求；上线前建议按 findings 里的 tests 逐条打一遍。
- 未覆盖：依赖漏洞数据库比对（未选 supply-chain 工具链）、Jasypt 历史明文行普查、MinIO bucket 策略与签名 URL 实际配置。
- **性能维度未运行，需要时执行 `/logi-perf`**；不要把本报告当作「性能没问题」的依据。

## 跨专家转交（deferred）

| 来源 | 目标 | 文件 | 摘要 |
| --- | --- | --- | --- |
| security | 未指定 | `admin/electron/main.js` | admin 桌面端未开 sandbox；preload 仅 3 个窗口 IPC、无远程内容，随 Electron 加固统一开启 |
| security | 未指定 | `docker-compose.yml` / `application.yml:70` | RabbitMQ 仍用 guest/guest，容器仅绑 127.0.0.1，部署加固时换强口令并限 vhost |
| security | 未指定 | `backend-agent/app/main.py` | `/agent/capabilities`、`/agent/health` 匿名披露能力清单，随 SEC-B2BD5 一并收口 |
| security | 未指定 | `backend/lianyu-dao` | SQL 注入面本轮无发现，建议新增 Mapper 时加 `${}` 静态检查防回归 |
| security | 未指定 | `.env.example` | 未能读取，需人工确认无真实值并补 `LIANYU_MASTER_KEY`/`AGENT_SHARED_TOKEN` 说明 |

本次为单模块（security）审查，非默认四专家组合，**未更新 latest**；结论仅代表本次 run。
