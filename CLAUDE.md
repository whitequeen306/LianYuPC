# CLAUDE.md — LianYu-PC

协作者与 AI Agent（Cursor / Claude Code / 等）的工作约定：技术栈、模块边界、构建命令、安全耦合、部署铁律。
PC 端桌面/Web 复刻版，独立于安卓端项目。

---

## ⚠️ 最高优先级：改代码前先读安全耦合地图

**改任何安全 / Electron / 鉴权 / 证书 / Token / Flyway / 对象存储相关代码前，必须先读：**

→ [`docs/security-coupling-map-zh.md`](docs/security-coupling-map-zh.md)

这份文件记录了各安全机制之间的**强耦合关系**——改一处不同步另一处会导致：
- 客户端启动即崩（`runtime-secrets.bin` 解包失败 / Flyway checksum 不匹配）
- 鉴权全线断（`lianyu-token` 名字贯穿前后端 6 处）
- 聊天全崩（Jasypt 主密钥轮换未重加密旧密文）
- 所有请求被 CORS 拦（apiOrigin 不在 nginx 白名单）

耦合地图里有 8 条速查 lockstep 规则、符号定位（`file:line`）、改前必查清单。**上下文压缩后，动手前重读这份文件。**

---

## 项目根目录

项目根 = 本文件（`CLAUDE.md`）所在目录，即仓库根。所有路径以仓库根为基准。
> 安卓端项目（`LianYu-master`）是**独立项目**，仅作 read-only 参考（prompt 模板、UI 数值、业务逻辑对照）。**绝不修改、绝不写入安卓端目录。**

---

## 技术栈速查

| 层 | 技术 |
|---|---|
| 前端 | Vue 3.5 + Vite 5 + Element Plus 2.8 + Pinia 2.2 + Vue Router 4.4 + Axios 1.7 + @stomp/stompjs 7 |
| 桌面端 | Electron 42 + electron-builder 26（NSIS，Windows）|
| 后端 | Spring Boot 3.3.5 (Servlet/MVC) + Maven 3.9 多模块 + JDK 17 |
| ORM | MyBatis-Plus 3.5.9 |
| DB | MySQL 8.4 + Flyway 10.20.1 (schema 迁移) |
| 缓存/会话 | Redis 7 + Lettuce + commons-pool2 2.12 |
| 消息队列 | RabbitMQ + Spring AMQP |
| 向量库 | Milvus 2.4.x Standalone（Docker 镜像 `v2.4.15`，SDK 2.4.5） |
| 对象存储 | MinIO (SDK 8.5.13) |
| 鉴权 | Sa-Token 1.39.0 (Redis 持久化，独立 DB 1) |
| 密码哈希 | BCrypt (spring-security-crypto 6.3.4, cost=12) |
| API Key 加密 | Jasypt 3.0.5 (AES-GCM，多版本轮换) |
| AI 抽象 | Spring AI 1.0.0 GA（OpenAI 兼容 + Ollama；BYOK per-user vault）|
| 熔断限流 | Resilience4j 2.2.0 |
| API 文档 | Knife4j 4.5.0 (OpenAPI 3) |
| 测试 | JUnit 5 + Mockito + Testcontainers 1.20.3 / 前端 Vitest 2.x |

---

## 后端模块结构

```
backend/
├── pom.xml                  # parent BOM + 版本锁定
├── lianyu-common/           # 工具类、基类、统一异常、Result<T>、JacksonTypeHandler
├── lianyu-dao/              # MySQL：Entity / Mapper / Flyway migration
├── lianyu-storage/          # MinIO client + Milvus client（无事务外部存储）
├── lianyu-security/         # Sa-Token、Jasypt、BCrypt、HeaderOnlyTokenFilter、密钥版本管理
├── lianyu-ai/               # Spring AI 依赖 + AiConfig（实际 LLM 客户端在 service 的 AiChatService）
├── lianyu-service/          # 业务逻辑（角色/对话/群聊/记忆/朋友圈/关系/通知）
├── lianyu-web/              # Controller、SSE、WebSocket、CORS、全局异常处理
└── lianyu-app/              # 启动类、application.yml、Docker 打包
```

依赖方向（无环）：
```
lianyu-app → lianyu-web → lianyu-service → lianyu-ai / lianyu-dao / lianyu-security
                                               ↓              ↓
                                          lianyu-storage    lianyu-common (所有模块可依赖)
```

---

## 构建与检查命令

```bash
# 后端编译（跳过测试）
cd backend && mvn -B -DskipTests compile

# 后端完整构建（跳过测试）
cd backend && mvn -B -DskipTests clean package

# 前端依赖安装
cd frontend && npm ci

# 前端 Web 构建（仅渲染层）
cd frontend && npm run build

# 前端测试
cd frontend && npm run test -- --run

# Electron 桌面客户端打包（本地执行，产出 release/v{version}/LianYu Setup {version}.exe）
cd frontend && npm run electron:build

# Electron 发布（bump 版本 + 打包）
cd frontend && npm run electron:release        # patch
cd frontend && npm run electron:release:minor  # minor
cd frontend && npm run electron:release:major  # major

# 全栈本地起（Docker Compose，首次 10–30 分钟）
cp .env.example .env   # 先填必填项，尤其 LIANYU_MASTER_KEY 与中间件密码
docker compose up -d --build
```

> `pom.xml` / `package.json` 变更后必跑 OSV-Scanner 查依赖漏洞。

---

## 关键设计决策

- **Web 容器**：Servlet（Spring MVC），不用 WebFlux。SSE 用 `SseEmitter` + Tomcat NIO。
- **dao 与 storage 分离**：`lianyu-dao` 只管 MySQL；MinIO/Milvus 走 `lianyu-storage`。事务边界清晰。
- **API Key 加密**：Jasypt AES-GCM 字段级加密；主密钥来自环境变量 `LIANYU_MASTER_KEY`，支持多版本轮换（`v1=...,v2=...,current=v2`）。**轮换必须重加密旧密文，否则聊天全崩。**
- **消息 seq**：每个 conversation 内用 Redis INCR 生成单调递增 sequence，解决毫秒级并发排序。
- **记忆去重**：`memory_meta.source_hash` = SHA-256(sorted(source_msg_ids))，唯一约束防重复摘要。
- **provider_model_cache**：走 Redis（key=`provider_models:{provider}:{user_id_or_global}`，TTL 1h），不用 MySQL 表。
- **群聊回复**：单队列串行（一次一个角色发言），用户新输入可中断当前队列。Redis 分布式锁互斥。
- **Prompt 注入防护**：用户输入剥离控制字符 → XML 包裹 → 系统指令告知模型忽略包裹内指令。
- **多设备登录**：允许并存不互踢；提供"踢全部其他设备"接口。
- **SSE 断流**：每 30s 心跳 event；前端 `EventSource` 自动重连带 `Last-Event-ID`；Sa-Token 滑动续期。
- **AI provider 容错**：Resilience4j bulkhead（限并发）+ timeLimiter（5s 首 token / 30s 总）+ circuitBreaker。
- **Flyway 迁移**：所有迁移必须可重入（`CREATE TABLE IF NOT EXISTS`）；破坏性变更须人工评审。**绝不改已发布的迁移，只能加新的（`V{n}__`），否则 checksum 不匹配启动崩。**

---

## 数据库核心表

`user` / `character` / `conversation` / `group_member` / `message` / `memory_meta` / `api_key_vault`
（完整 schema 见 `backend/lianyu-dao/src/main/resources/db/migration/V1__init_schema.sql` 及 V2–V34）

---

## 开发环境

- 最低 16GB RAM（Milvus standalone 自带 etcd+MinIO 约 4GB）
- Docker Compose 编排全部中间件（MySQL / Redis / RabbitMQ / MinIO / Milvus）
- `.env.example` 入仓，`.env` 入 `.gitignore`（**绝不提交真实密钥**）
- `LIANYU_MASTER_KEY` 须向团队负责人索取（不是 sk- Key，是 Jasypt 主密钥）

---

## 部署与发布（前后端分离，务必遵守）

**前端在本地打包，不在服务器上构建或部署；后端在服务器上跑 Docker，不涉及前端打包。**

| 端 | 运行位置 | 发布方式 | 不要做什么 |
|---|---|---|---|
| **前端（Electron 桌面客户端）** | 用户本机 | 本地 `frontend/` 下 `npm run electron:release`，产出 `frontend/release/v{version}/LianYu Setup {version}.exe` | 不要在云端执行 `vite build` / `electron-builder`；不要把 `frontend/dist`、`release` 部署到服务器 |
| **后端 + API 网关** | 云服务器 `/opt/lianyu` | `git pull origin main` 后 `docker compose up -d --build backend api-gateway`（见 `scripts/_cloud_deploy_pull.py`） | 不要指望「部署后端」顺带更新客户端 UI；前端改动必须重新打 Electron 安装包 |

补充约定：

- 云端 Compose 仅跑 **backend、api-gateway** 与中间件（MySQL / Redis / MinIO / Milvus / RabbitMQ）；**无 frontend 容器**（`web-gateway` 仅 `dev-proxy` profile 本地联调用）。
- 客户端通过 `frontend/.env.production.cloud` 中的 `VITE_LIANYU_API_ORIGIN` 指向云端 API；API 地址变更改 env 并重打 Electron，不是改服务器前端静态资源。
- 仅后端修复（如 MinIO 资源、接口逻辑）部署后，**已安装的 Electron 客户端无需重装**即可生效；**仅前端 Vue 改动**（页面、样式、加载策略等）必须 **重新打 Electron 包** 用户才会看到。
- Agent 排查「线上缺图 / 接口慢」时，先区分是 **服务端（MinIO/DB/API）** 还是 **客户端（未打包的前端改动）** 问题，避免在服务器上找不存在的前端构建产物。

### Git 与云端同步

- **前后端源码均 push 到 GitHub**（`frontend/` 与 `backend/` 同在 monorepo）；本地改动完成并验证后，先提交再推送，不要只改本地不打远程。
- **云服务器不承载 Git 开发**，只在 `/opt/lianyu` **`git pull origin main`** 拉取已合并的 `main` 分支，再 `docker compose up -d --build backend api-gateway`（自动化见 `scripts/_cloud_deploy_pull.py`）。
- 常规流程：`develop` 开发 → 合并进 `main` → `git push origin main` → 服务器 pull + 重建后端容器；**前端 Electron 安装包在本地打完，不上传服务器**。
- Agent 执行发布时：**先 push GitHub，再服务器 pull**；不要 SCP 源码或 jar 覆盖 `/opt/lianyu`，不要跳过 Git 直接在服务器改代码。

---

## 工作约定

- 所有文件操作以**仓库根**为基准（即本文件所在目录）。
- 安卓端目录绝不写入，仅 read-only 参考。
- `pom.xml` / `package.json` 变更后必跑 OSV-Scanner。
- 日志用 `@Slf4j` + traceId。
- 统一返回格式 `Result<T>`。
- 改安全相关代码前先读 [`docs/security-coupling-map-zh.md`](docs/security-coupling-map-zh.md)。

---

## 相关文档索引

| 文档 | 用途 |
|------|------|
| [`README.md`](README.md) | 项目说明、快速开始 |
| [`docs/security-coupling-map-zh.md`](docs/security-coupling-map-zh.md) | **安全机制耦合地图 + 改前必查清单**（改代码前必读）|
| [`LianYu-0.2.88-安全修复计划.md`](LianYu-0.2.88-安全修复计划.md) | 14 项安全修复计划（P0/P1/P2，含攻击链与测试矩阵）|
| [`docs/electron-client-hardening-zh.md`](docs/electron-client-hardening-zh.md) | Electron 客户端安全能力手册（C1–C14，红/蓝队对照）|
| [`docs/cortexloop/`](docs/cortexloop/) | 自动化代码审计报告（健康分、发现项、修复回顾）|
| [`docs/superpowers/specs/`](docs/superpowers/specs/) | 设计文档（关系驱动系统、角色内心空间）|
| [`.env.example`](.env.example) | 环境变量模板（部署前复制为 `.env`）|
| [`docker-compose.yml`](docker-compose.yml) | 全栈编排 |
| [`deploy/api-gateway/nginx.conf`](deploy/api-gateway/nginx.conf) | 生产 TLS 网关 + CORS 白名单 |
| [`docs/qq-bridge-zh.md`](docs/qq-bridge-zh.md) | QQ（NapCat / OneBot 11）桥接：单人模式配置 + 安全耦合影响表 + Phase 2 路线（默认关闭，进程内直连 `ConversationService`，不经 HTTP/鉴权层）|
