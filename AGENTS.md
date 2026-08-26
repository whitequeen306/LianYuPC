# AGENTS.md — LianYu-PC

协作者与Agent 约定（技术栈、模块边界、工作守则）。  
PC 端桌面/Web 复刻版，独立于安卓端项目。

## 设计系统基线（强制遵守）

项目根目录 `DESIGN.md` 是视觉设计的**唯一真相源**。它定义了：
- 色板（rose-pink `#f4a6b5` + 暖黑背景 + 状态色）
- 字体（PingFang SC 正文 / Noto Serif SC 标题 / Syne 品牌字）
- 圆角（8/14/24/28/25px pill / 9999px full）
- 间距（4px 步进 rem 制）
- 组件令牌（按钮/输入框/卡片/对话框/玻璃面板）

**任何新增或修改的 UI 必须遵守以下规则：**

1. **先读 `DESIGN.md`** — 开始任何前端工作前，必须读取项目根的 `DESIGN.md`
2. **只许用令牌** — 颜色、字体、圆角、间距只能引用 `DESIGN.md` 中已定义的令牌或对应的 CSS 变量（`--ly-accent`、`--ly-bg-*`、`--ly-text-*` 等），**不许硬编码 hex 值**
3. **不许引入新色** — 不允许出现调色板之外的装饰色（蓝/绿/紫等），状态色（success/warning/error/info）仅用于 toast/badge
4. **不许用 `border-radius: 0`** — 按钮必须 pill(25px)，卡片 lg(24px)，对话框 xl(28px)
5. **不许用 `linear`/`ease` 缓动** — 过渡一律 `cubic-bezier(0.23, 1, 0.32, 1)` (EaseOutQuint)，时长 0.2~0.28s
6. **深度靠玻璃不是阴影** — 卡片/对话框用 `backdrop-filter: blur()`，不用扁平阴影；主要按钮才加 pink glow
7. **暗色优先 + 亮色对等** — 所有新组件必须同时适配 dark/light 两套 CSS 变量
8. **如需扩展** — 如果 `DESIGN.md` 没有覆盖某种需求，先在 `DESIGN.md` 中新增令牌，再用新令牌写组件

违反以上任一条的 UI 代码不得合入 `main` 分支。

## 项目根目录

`C:\Users\hp\Desktop\LianYu-PC\`

主要入仓目录：

```
frontend/     # 用户恋语客户端（Vue + Electron；含微信通道打包脚本）
admin/        # 独立管理端 Electron（LianYu Admin）
installer/    # 用户客户端 WPF 离线安装器
backend/     # Spring Boot 多模块
deploy/       # api-gateway（Nginx TLS）+ ASR 镜像
Pets/         # 桌宠 skill / 参考图
scripts/      # 云端部署 / MinIO 上传等运维脚本（发版入口仍是 local/ship-release.ps1）
```

## 推送 / 部署 / 发版（强制）

凡涉及 **`git push`、云服务器部署、Electron 发版、GitHub Releases、MinIO 更新包**（用户说「一条龙」「发版」「部署」「上线」等）时：

1. **先读** `local/ship-release.ps1` 与 `local/README.md`（`local/` 已 gitignore，只在本机）
2. **用脚本跑**，不要手写拆开 `git push` + `python scripts/_cloud_deploy_pull.py` + `npm run electron:release`
3. 按改动选参数：`-BackendOnly` / `-ElectronOnly` / 无参数全量（见下方「发布发版」表）
4. 如果遇到错误无法正常执行，必须明确告诉用户脚本的问题是什么

缺 `local/ship-release.ps1` 时按 `local/README.md` 重建，不要另发明流程。

## 安卓端（只读参考）

若本机另有安卓端仓库，**只读、禁止写入**（prompt 模板、UI 数值、业务对照）。不要假定固定路径一定存在。

## 技术栈速查

| 层 | 技术 |
|---|---|
| 前端 | Vue 3.5 + Vite 6 + Element Plus 2.8 + Pinia 2.2 + Vue Router 4.4 + Vue I18n 9 + Axios 1.7 + @stomp/stompjs 7 + Electron 42（桌面客户端程序本体；`frontend/package.json` `engines.node` ≥ 22.12） |
| 安装器 | WPF（.NET 10，`installer/`）把 Electron `win-unpacked` 打成离线单文件 `LianYu-Setup-*.exe`。用户安装包**不是** electron-builder 默认 NSIS |
| 管理端 | 独立 `admin/` Electron 客户端 + 后端 `lianyu-admin`（`/api/admin/v1/**`）。与用户恋语客户端分开打包，不要混进 `-ElectronOnly` |
| 微信通道 | 独立 zip（`frontend/electron/wechatBridge/pack-wechat-channel.mjs` → `WechatChannel-win-x64-*.zip`），上传 MinIO `updates/`。**不是**用户安装包，一条龙 / `-ElectronOnly` 都不打 |
| 后端 | Spring Boot 3.5.5 (Servlet/MVC) + Maven 多模块 + JDK 17 |
| ORM | MyBatis-Plus 3.5.9 |
| DB | MySQL 8.4 + Flyway 10.20.1（当前迁移到 `V51`） |
| 缓存/会话 | Redis 7 + Lettuce + commons-pool2 2.12 |
| 消息队列 | RabbitMQ + Spring AMQP |
| 向量库 | Milvus 2.4.x Standalone（Compose 镜像 `v2.4.15`，SDK 2.4.5） |
| 对象存储 | MinIO (SDK 8.5.13) |
| ASR | 独立容器 `deploy/asr`（SenseVoice + Zipformer；backend 经 `LIANYU_ASR_BASE_URL` 调用） |
| 网关 | `deploy/api-gateway` Nginx TLS 反代 `/api` `/ws`（不托管前端静态资源） |
| 鉴权 | Sa-Token 1.39.0（token 名 `lianyu-token`，Redis database 1） |
| 密码哈希 | BCrypt（`spring-security-crypto` 6.5.3，**cost=12**；`PasswordConfig`） |
| API Key 加密 | Jasypt 3.0.5 (AES-GCM) |
| AI 抽象 | Spring AI 1.1.2（OpenAI / Ollama model starter）+ Spring AI Alibaba Graph `1.1.2.2`（`spring-ai-alibaba-graph-core` 回合编排） |
| 熔断限流 | Resilience4j 2.2.0（bulkhead + timeLimiter 30s 阻塞总超时 + **按上游隔离**的 circuitBreaker） |
| API 文档 | Knife4j 4.5.0 (OpenAPI 3) |
| 测试 | JUnit 5 + Mockito + Testcontainers 1.20.3 |
| 前端测试 | Vitest 3.x |

## 后端模块结构

```
backend/
├── pom.xml                  # parent BOM + 版本锁定（含 spring-ai / spring-ai-alibaba BOM）
├── lianyu-common/           # 工具类、基类、统一异常、Result<T>、JacksonTypeHandler
├── lianyu-dao/              # MySQL：Entity / Mapper / Flyway migration
├── lianyu-storage/          # MinIO client + Milvus client（无事务外部存储）
├── lianyu-security/         # Sa-Token、Jasypt、BCrypt 封装、密钥版本管理
├── lianyu-ai/               # Spring AI 集成、Spring AI Alibaba Graph、ChatTurn 契约（Keys/State/Scene）
├── lianyu-service/          # 业务逻辑（角色/对话/群聊/记忆/朋友圈/社区/关系/Agent 桥）
├── lianyu-web/              # Controller、SSE、WebSocket、CORS、全局异常处理
├── lianyu-qq-bridge/        # QQ 桥（NapCat/OneBot 11；默认关闭，直连 ConversationService）
├── lianyu-admin/            # 管理中枢 API（`/api/admin/v1/**`；不含密钥，供独立 Admin EXE 调用）
└── lianyu-app/              # 启动类、application.yml、Docker 打包
```

依赖方向（无环）：
```
lianyu-app → lianyu-web → lianyu-service → lianyu-ai / lianyu-dao / lianyu-security
                                               ↓              ↓
                                          lianyu-storage    lianyu-common（所有模块可依赖）
lianyu-ai → lianyu-storage
lianyu-app → lianyu-qq-bridge → lianyu-service
lianyu-app → lianyu-admin → lianyu-dao / lianyu-security / lianyu-storage / lianyu-ai
                            （admin 不依赖 lianyu-service）
```

## 关键设计决策

- **AI 回合编排**：Spring AI Alibaba Graph（`spring-ai-alibaba.version`；`CompiledGraph chatTurn`）。`OverAllState` + `ChatTurnKeys` 承载上下文；`ChatTurnFacade` 为外层入口。模型调用走 Spring AI 1.1.x starter。SSE / 配额 / Resilience4j / 落库回调留在 ConversationService 等适配层。
- **Web 容器**：Servlet（Spring MVC），不用 WebFlux。SSE 用 `SseEmitter` + Tomcat NIO（单连接硬超时 30 分钟）。前端聊天流用 `fetch` 读 stream（`sendMessageStream`），**不是** `EventSource`。
- **dao 与 storage 分离**：`lianyu-dao` 只管 MySQL；MinIO/Milvus 走 `lianyu-storage`。事务边界清晰。
- **API Key 加密**：Jasypt AES-GCM 字段级加密；主密钥来自环境变量 `LIANYU_MASTER_KEY`，支持多版本轮换（`v1=...,v2=...,current=v2`）。
- **消息 seq**：每个 conversation 内用 Redis INCR 生成单调递增 sequence，解决毫秒级并发排序。
- **记忆去重**：`memory_meta.source_hash` = SHA-256(sorted(source_msg_ids))，唯一约束防重复摘要。
- **provider_model_cache**：走 Redis（key 前缀 `provider_models:`，命中 TTL 约 1h + 抖动；空列表更短 TTL），不用 MySQL 表。
- **群聊回复**：按轮次推进（`lianyu.group.auto-rounds`）。**同一轮内多角色可并行生成**，落库/推送按完成顺序，不是按花名册串行。用户新消息会换 Redis turnId，中断进行中的回复。
- **Prompt 注入防护**：用户输入剥离控制字符 → XML 包裹 → 系统指令告知模型忽略包裹内指令（`UserInputSanitizer`）。
- **多设备登录**：Sa-Token `is-concurrent=true`、`is-share=false`，多设备并存不互踢。**没有**「踢全部其他设备」接口。token 最长 30 天；`active-timeout=-1` 不因空闲冻结；前端可调 `POST /api/auth/refresh` 续签。
- **SSE 保活**：语言门阻塞重生成时发 SSE comment `keep-alive`，避免代理空闲断开；不是固定 30s event。
- **AI provider 容错**：Resilience4j `ai-chat` bulkhead（前台并发 + 最多等 5s 入槽）+ timeLimiter **30s 阻塞总超时**（没有单独的 5s 首 token 限时）+ circuitBreaker（`UpstreamCircuitBreakerFactory` 按 provider+baseUrl 隔离，不是全局一只熔断器）。
- **桌面 Agent 桥**：客户端把本地 MCP 工具注册到云端，模型经 STOMP `/user/queue/agent-tools` 下发，结果 POST 回 `/api/agent-bridge/result`（`AgentBridgeService`，无事务慢调用）。
- **后台任务走 RabbitMQ**：记忆摘要、社区审核/通知、Web Push、后台 AI（日记/朋友圈评论等）用 `@RabbitListener` 消费，不在请求线程里做慢调用。
- **QQ 桥**：`lianyu-qq-bridge` 默认关闭（`lianyu.qq-bridge.enabled`，环境变量 `LIANYU_QQ_BRIDGE_ENABLED`）；开启后直连 `ConversationService`，不经 HTTP/Sa-Token。说明见 `docs/qq-bridge-zh.md`。
- **Flyway 迁移**：新表用 `CREATE TABLE IF NOT EXISTS`。MySQL 8.4 **没有** `ADD COLUMN IF NOT EXISTS`，后续 ALTER 必须用 `information_schema` 守卫。破坏性变更须人工评审。当前最新 `V51__admin_management_schema.sql`。

## 数据库核心表

对话主链：`user` / `character` / `conversation` / `group_member` / `message` / `memory_meta` / `api_key_vault`

另外已有、改业务时常碰到：

- 广场 / 状态：`character_square_template` / `character_state` / `character_diary` / `square_like` / `square_comment`
- 关系：`relationship_state` / `relationship_event`
- 朋友圈 / 社区：`moments_post` / `moments_comment` / `community_post` / `community_comment`
- 通知：`user_notification` / `web_push_subscription`
- 自定义音色：`user_custom_voice`
- 管理中枢（V51）：`admin_user` / `admin_role` / `admin_permission` / `admin_session` / `admin_audit_log` / `app_release` / `announcement` / `release_rollout` / `support_access_grant` 等

## 开发环境

- 最低 16GB RAM（Milvus standalone 自带 etcd+MinIO 约 4GB；ASR 容器另约 2.5GB）
- Docker Compose 编排 backend、api-gateway、ASR 与全部中间件（MySQL/Redis/RabbitMQ/MinIO/Milvus）
- 打 WPF 安装器需要本机 .NET 10 SDK
- `.env.example` 入仓，`.env` 入 `.gitignore`

### 本地联调：后端镜像同步（强制）

本地 backend 容器运行的是**镜像内代码，不挂载工作区源码**。凡修改会影响后端运行结果的内容（`backend/**/*.java`、resources、Flyway、`pom.xml`、Dockerfile 等），在本地验收前必须重新构建镜像并重建容器：

```powershell
cd C:\Users\hp\Desktop\LianYu-PC
docker compose up -d --build backend
```

- **禁止**在后端源码变化后只执行 `docker compose restart backend` 或 `docker compose up -d backend`；这两种方式可能继续运行旧镜像。
- 重建后至少确认 `docker compose ps backend` 为 `Up`，并确认 `http://127.0.0.1:8080/actuator/health` 返回 `UP`。
- 日常重建不要执行 `docker compose down -v`，否则会删除 MySQL / Redis / MinIO / Milvus 等本地数据卷。
- 当前前端开发不使用 Docker 静态前端镜像；运行 `cd frontend; npm run dev` 直接读取当前源码。不要再使用历史遗留的 `lianyu-pc-frontend:latest`。
- 改 `deploy/asr` 或 `deploy/api-gateway` 时要 `--build` 对应服务，只 rebuild `backend` 不会带上它们。
- 可选本地反代：`docker compose --profile dev-proxy up -d web-gateway`（`frontend/Dockerfile.gateway`），不打包前端静态资源。
- 本地重建只用于联调，**不等于**云端部署，也不能替代发版时的 `.\local\ship-release.ps1 -BackendOnly`。

## 发布发版（Agent 必看 — 不要另想流程）

**唯一入口：`.\local\ship-release.ps1`。** 详细说明见文首「推送 / 部署 / 发版（强制）」与 `local/README.md`。

**前后端分离：** 用户客户端只在本机打；云端只跑 backend/api-gateway + 中间件，不构建前端。

功能改动**先自行 commit 到 `main`**、工作区干净后，再跑一条龙（脚本**不**替你写业务 commit）。

**禁止：**

- 手搓 `git push` + `_cloud_deploy_pull.py` + `electron:release`（易漏 Draft 清理 / 版本号回写）
- 无脑跑无参数全量（只改一侧却 FULL）

脚本启动会打印 `SHIP PLAN`（push/deploy/electron 各 YES/no），对不上就 Ctrl+C。

### 用户客户端：Electron 程序 + WPF 安装器

装完之后跑的仍是 Electron（`win-unpacked/LianYu.exe`）。换掉的只是安装外壳：

1. `frontend/scripts/electron-pack.mjs` 打出 Electron `win-unpacked`（仍调用 electron-builder；`package.json` 的 `win.target=nsis` 只是 builder 配置，**不要**把这一步产出的 NSIS exe 当用户安装包）
2. `frontend/scripts/prepare-branded-release.mjs` 调用 `installer/build-offline-installer.ps1`，用 WPF 安装器把 payload 打成离线单文件 `LianYu-Setup-*.exe`（含 blockmap / `latest.yml`）
3. `npm run electron:release` 上传上述 WPF 安装包到 GitHub Releases + MinIO

本地只打程序、不发版：`cd frontend; npm run electron:build`。只要安装器：先有 `win-unpacked`，再 `installer/build-offline-installer.ps1`（详见 `installer/README.md`）。改安装器 UI/逻辑只动 `installer/`，不改 Electron 业务代码。

**GitHub Release 双 Draft / 只有 blockmap：** electron-builder 并行上传仍可能竞态建多个 draft。入仓侧已在 `electron-pack.mjs` 预建 draft + `publish.releaseType=draft`；随后 WPF 安装包 `--clobber` 覆盖同名 exe。`local/ship-release.ps1` 发版后强制校验并补传 `LianYu-Setup-*.exe`，缺 exe 则失败而非静默成功。

`-ElectronOnly` 打的是**用户恋语客户端**。下面两条**不在**一条龙里：

- 管理端 `admin/`（Electron + NSIS，产物 `LianYu-Admin-Setup-*.exe`）
- 微信通道 zip（`pack-wechat-channel.mjs`，上传用 `scripts/_upload_wechat_channel.py`）

| 场景 | 命令 | 会触发 |
|---|---|---|
| **只改后端**（Java / Flyway / `pet-voices.json` 等） | `.\local\ship-release.ps1 -BackendOnly` | push + 云端 rebuild；**绝不**打 Electron / Releases / MinIO 更新包 |
| **只改前端**（Vue / Electron / `public/pet` / `installer/`） | `.\local\ship-release.ps1 -ElectronOnly` | push + 用户客户端（Electron + WPF 安装器）+ GitHub Releases + MinIO；**绝不** docker rebuild backend |
| **前后端都改** | `.\local\ship-release.ps1` | 全量（唯一允许的无参数用法） |
| minor / major | 在上面对应命令后加 `-Bump minor` | 同上 |

```powershell
cd C:\Users\hp\Desktop\LianYu-PC
.\local\ship-release.ps1 -BackendOnly    # 例：只后端
```

**边界（仍须记住）：**

- 主分支始终是 `main`；禁止 SCP 源码/jar 覆盖 `/opt/lianyu`
- 只改后端 → 用户无需重装客户端；只改前端 Vue/Electron/`public/pet` → 必须打用户客户端（Electron + WPF 安装器）
- 只改 `installer/` → 也要走用户客户端发版（重新套 WPF 安装器）；程序本体未变时仍须出新 `LianYu-Setup-*.exe`
- 改 API Origin → 改已入仓的 `frontend/.env.production.cloud` 后重打用户客户端
- 只改微信通道宿主 / 打包脚本 → 打 `WechatChannel-win-x64-*.zip` 并上传 MinIO，不要当成用户客户端发版
- 云端部署读根目录 `.env` 的 `DEPLOY_SSH_PASSWORD`；`GH_TOKEN` 用环境变量或 Credential Manager，**禁止写入任何文件**

## 工作约定

- 所有文件操作在 `C:\Users\hp\Desktop\LianYu-PC\` 下进行
- `pom.xml` / `package.json` 变更后必跑 OSV-Scanner
- 日志用 `@Slf4j` + traceId
- 统一返回格式 `Result<T>`

## 事务红线（强制）：慢调用不进事务

**任何会「等别人」的操作，都不要进 `@Transactional`。** 「等别人」= AI 调用、HTTP 请求、ASR/TTS、MinIO 上传、第三方 SDK、文件下载 —— 耗时不归你管（网络、对端负载、对端挂起）。事务持有的是**你的 Hikari 连接和行锁**，把两者绑在一起，就是把「别人的慢」变成「你全站的卡」。

**后台任务 ≠ 例外。** 慢调用占的是**资源**（DB 连接、行锁），不是用户的时间。定时器/MQ 触发的任务（比如 `sendProactiveMessage`）不耗用户时间，但照样耗 Hikari 连接；连接一耗光，前台用户请求拿不到连接，全场卡死。所以「反正后台跑的」不能成为慢调用进事务的借口。

**写法模板（必须照这个拆）：**

```java
// 外层：故意不加 @Transactional —— 慢调用在这里，挂了只影响这一条请求
public Result doX(Long userId, Req req) {
    AiResult ai = aiChatService.chatBlocking(userId, aiReq); // 慢调用
    return persistX(userId, req, ai);                         // 最后才进事务
}

// 内层：只有「防重 + 落库 + 通知」，快进快出
@Transactional
protected Result persistX(Long userId, Req req, AiResult ai) { ... }
```

**配套纪律：**

- **重试只重「瞬断」，不重「语义错」**：DNS/连接拒绝/连接超时 → 可重试 1 次；AI 返回空/审核不通过/参数错 → 不重试（非幂等，重试=重复生成、重复扣 token）。参考 `AiChatService.isTransientStreamFailure`。
- **落库多步需原子性时一起进事务**（比如建角色+建会话），不需要就拆细。
- 纯 DB 的 CRUD（无慢调用）→ 随便 `@Transactional`。

违反本条的后端代码不得合入 `main`。

## 出站 HTTP 红线（强制）：不许用零超时客户端

**任何「等别人」的 HTTP 调用，都必须显式带超时。** 对端可以 TCP/TLS 握手成功后永远不回一个字节（挂起的连接）——没有读超时，调用线程就**永久** park 在 `Mono.block()`，攒够后 Tomcat 全场卡死（2026-08-07 线上实例：线程 park 78 分钟，全站 499/504）。

**唯一允许的写法**（`SsrfPinningClientFactory`）：

```java
// 阻塞路径（RestClient/.call）：OkHttp，connect 15s / read 60s / write 30s
RestClient client = SsrfPinningClientFactory.defaultRestClientBuilder().build();          // 受信端点
RestClient client = SsrfPinningClientFactory.restClientBuilder(validatedEndpoint).build(); // 用户自填 URL（SSRF 固定）

// 流式路径（WebClient/.stream）：reactor-netty，connect 15s / 读间隙 90s
WebClient.Builder b = SsrfPinningClientFactory.defaultWebClientBuilder();                  // 受信端点
WebClient.Builder b = SsrfPinningClientFactory.webClientBuilder(validatedEndpoint);        // 用户自填 URL
```

**禁止：**

- `RestClient.create()` / `WebClient.create()` / Spring 自动探测的裸 Builder——classpath 里有 reactor-netty 时会选中它，而 reactor-netty **默认零读超时**
- 只包 Resilience4j `TimeLimiter` 就当有超时——它只放弃 Future，**杀不死**底层 park 住的线程；超时必须设在 HTTP 客户端上
- reactor-netty `responseTimeout` 语义是「两次读之间的最长间隔」，不是总时长，故流式 SSE 可安全使用（正常分块持续到达不触发）

违反本条的后端代码不得合入 `main`。

## 桌宠开发

新增角色桌宠（atlas + 语音 + 前后端接入）时，**必须先读取 skill 文档**：

`Pets/skills/hatch-lianyu-pet/SKILL.md`

先读本仓库 skill，再读它引用的 [awesome-codex-pet `hatch-pet-v1`](https://github.com/legeling/awesome-codex-pet)（图像生成 / 提帧 / 拼 atlas 以那边为准）。本仓库 skill 只写接入差异：参考图 → gpt-image-2 生成动作条 → hatch-pet 拼合 atlas → 接入 petCatalog / desktopSettings / pet-voices → 打用户客户端。参考图放到 `Pets/images/` 下。

涉及的关键文件（新增角色时都要改）：

| 文件 | 作用 |
|---|---|
| `frontend/public/pet/<id>_spritesheet.webp` | 1536×1872 atlas（新增） |
| `frontend/public/pet/<id>_idle0.png` | 192×208 预览帧（新增） |
| `frontend/src/constants/petCatalog.js` | 桌宠目录条目 |
| `frontend/electron/desktopSettings.js` | 主进程 ALLOWED_PET_IDS（**漏了会导致切换不生效**） |
| `backend/lianyu-service/src/main/resources/pet-voices.json` | 后端语音映射（改后需重建 backend） |

**注意**：前端 / 桌宠资源改动走 `.\local\ship-release.ps1`（用户客户端 = Electron + WPF 安装器）；仅改 `pet-voices.json` / 后端可用 `-BackendOnly`。
