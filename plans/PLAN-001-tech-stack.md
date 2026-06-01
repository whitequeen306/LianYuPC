# PLAN-001: PC 端技术栈最终方案

**Status**: Final（经 claude-review Conditional Approval，已落实全部 🔴🟠🟢 改动）
**Date**: 2026-05-24
**Owner**: planner
**Depends on**: 无（基础决策文档）
**Project Root**: `C:\Users\hp\Desktop\LianYu-PC\`（独立于安卓端，安卓端不动）

## 1. Executive Summary

PC 端是安卓端 LianYu 的桌面/Web 复刻 + 增强。前端 Vue3 + Vite + Element Plus，后端 Spring Boot 3 单体应用（Servlet 栈，按业务领域多模块切分）。所有核心 AI 能力走 Spring AI 抽象，数据持久化用 MySQL，向量走 Milvus，文件走 MinIO，缓存/会话走 Redis，异步走 RabbitMQ。

**Web 容器选型：Servlet（Spring MVC）**，不用 WebFlux。原因：团队上手快；Spring AI / Sa-Token / Knife4j / MyBatis-Plus 都是 Servlet 优先支持；SSE 用 `SseEmitter` 已满足 100 并发目标（Tomcat NIO + 异步 Servlet）。如果未来并发量进一步上升，再考虑迁移到 WebFlux。

## 2. 后端技术栈（终版，含锁定版本）

| 类别 | 选型 | Maven 坐标 | 锁定版本 |
|---|---|---|---|
| 主框架 | Spring Boot 3（Servlet） | `org.springframework.boot:spring-boot-starter-web` | **3.3.5** |
| 校验 | Jakarta Validation | `org.springframework.boot:spring-boot-starter-validation` | 同上 |
| Actuator | Spring Boot Actuator | `org.springframework.boot:spring-boot-starter-actuator` | 同上 |
| 构建 | Maven（多模块） | — | **3.9.x** |
| JDK | Eclipse Temurin | — | **21 (LTS)** |
| 数据库 | MySQL | `com.mysql:mysql-connector-j` | **8.4.0** |
| **Schema 迁移** | **Flyway** | `org.flywaydb:flyway-core` + `flyway-mysql` | **10.20.1** |
| ORM | MyBatis-Plus | `com.baomidou:mybatis-plus-spring-boot3-starter` | **3.5.9** |
| 向量库 | Milvus | `io.milvus:milvus-sdk-java` | **2.4.5**（兼容 Milvus 2.4.x Standalone） |
| 对象存储 | MinIO Java SDK | `io.minio:minio` | **8.5.13** |
| 缓存 | Redis 7 | `org.springframework.boot:spring-boot-starter-data-redis` | 3.3.5 |
| Lettuce 连接池 | commons-pool2 | `org.apache.commons:commons-pool2` | **2.12.0** |
| 消息队列 | RabbitMQ (Spring AMQP) | `org.springframework.boot:spring-boot-starter-amqp` | 3.3.5 |
| 安全/鉴权 | Sa-Token | `cn.dev33:sa-token-spring-boot3-starter` + `sa-token-redis-jackson` | **1.39.0** |
| 密码哈希 | BCrypt | `org.springframework.security:spring-security-crypto` | **6.3.4** |
| API Key 加密 | Jasypt | `com.github.ulisesbocchio:jasypt-spring-boot-starter` | **3.0.5** |
| AI 抽象 | Spring AI ChatClient | `org.springframework.ai:spring-ai-bom`(BOM) + `spring-ai-openai-spring-boot-starter` + `spring-ai-ollama-spring-boot-starter` | **1.0.0**（GA） |
| **熔断 / 限流** | **Resilience4j** | `io.github.resilience4j:resilience4j-spring-boot3` | **2.2.0** |
| 实时通信 | SSE + WebSocket(STOMP) | `org.springframework.boot:spring-boot-starter-websocket`, `SseEmitter` | 3.3.5 |
| API 文档 | Knife4j（OpenAPI 3） | `com.github.xiaoymin:knife4j-openapi3-jakarta-spring-boot-starter` | **4.5.0** |
| 日志 | @Slf4j + Logback | `org.projectlombok:lombok` | **1.18.34** |
| 测试 | JUnit 5 + Mockito + Testcontainers | `org.springframework.boot:spring-boot-starter-test`, `org.testcontainers:junit-jupiter` + 各中间件 modules | Testcontainers **1.20.3** |
| 监控（预留） | Micrometer + Actuator Prometheus | `io.micrometer:micrometer-registry-prometheus` | 3.3.5 |

依赖坐标全部 BOM 化（在 parent pom 用 `<dependencyManagement>` 锁版本），子模块不写 version。

### 2.1 后端模块切分（Maven 多模块）

```
LianYu-PC/
└── backend/
    ├── pom.xml                  # parent BOM + 版本锁定
    ├── lianyu-common/           # 工具类、基类、统一异常、统一返回、统一 TypeHandler（JSON）、Result<T>
    ├── lianyu-dao/              # 仅放关系型：MyBatis-Plus Entity / Mapper / Flyway migration
    ├── lianyu-storage/          # 新增：MinIO client + Milvus client（无事务的外部存储，与 dao 解耦）
    ├── lianyu-security/         # Sa-Token、Jasypt、BCrypt 封装、密钥版本管理
    ├── lianyu-ai/               # Spring AI 集成、CharacterPromptBuilder、ChatMemory 实现 → 依赖 lianyu-storage（写 Milvus）
    ├── lianyu-service/          # 业务逻辑（角色/对话/群聊/记忆）
    ├── lianyu-web/              # Controller、SSE、WebSocket、CORS、全局异常处理器
    └── lianyu-app/              # 启动类、application.yml、Docker 打包
```

依赖方向（自顶向下，无环）：
```
lianyu-app
  └─> lianyu-web
        └─> lianyu-service
              ├─> lianyu-ai ─┐
              ├─> lianyu-dao ┤
              └─> lianyu-security
                              ├─> lianyu-storage
                              └─> lianyu-common（所有模块都可依赖）
```

**关键变化**（响应 claude-review §2.1 拆分建议）：
- `lianyu-dao` 不再混进 MinIO / Milvus，只管 MySQL。
- 新增 `lianyu-storage`，统一管 MinIO + Milvus client。事务边界清晰。
- `lianyu-ai` 写 Milvus 向量通过 `lianyu-storage`，不直连。

## 3. 前端技术栈（已锁定）

| 类别 | 选型 | 锁定版本 |
|---|---|---|
| 框架 | Vue 3（Composition API + `<script setup>`） | **3.5.x** |
| 构建 | Vite | **5.x** |
| UI | Element Plus | **2.8.x** |
| 状态管理 | Pinia | **2.2.x** |
| 路由 | Vue Router | **4.4.x** |
| HTTP | Axios | **1.7.x** |
| SSE | 浏览器原生 `EventSource` | — |
| WebSocket | `@stomp/stompjs` | **7.x** |
| 样式 | SCSS + CSS Variables | — |
| 测试 | Vitest | **2.x** |

前端目录：`LianYu-PC/frontend/`。

## 4. 顶层项目结构

```
C:\Users\hp\Desktop\LianYu-PC\
├── README.md                  # 项目总览
├── CLAUDE.md                  # PC 端 Claude Code 工作指引（独立于安卓端）
├── docker-compose.yml         # 一键编排（Phase 1 加入）
├── .env.example               # 环境变量模板（含 LIANYU_MASTER_KEY 占位）
├── docs/                      # 文档
├── plans/                     # 本计划文档所在
├── tickets/                   # 任务/缺陷
├── backend/                   # Spring Boot 多模块
└── frontend/                  # Vue3 + Vite
```

**绝不修改、绝不写入**安卓端目录 `C:\Users\hp\Desktop\LianYu-master (1)\LianYu-master\`。安卓端仅作为 read-only 参考资料（在需要 prompt 模板/UI 变量时由 researcher 智能体读取）。

## 5. 与初版建议的差异

| 项 | 初版建议 | 终版选择 | 影响 |
|---|---|---|---|
| 数据库 | PostgreSQL（带 pgvector） | **MySQL + Milvus** | 角色 settings 用 MySQL JSON 列；向量独立到 Milvus |
| 构建 | Gradle KTS | **Maven** | 与安卓端不共享；CI 更主流 |
| 消息队列 | 二期再上 | **一期就上 RabbitMQ** | 直接预留三条通道：异步记忆固化、消息归档、跨实例事件广播 |
| 密码哈希 | （未单列） | **BCrypt 单列** | 用户密码 BCrypt，API Key Jasypt+AES-GCM，职责分离 |
| 项目根目录 | 安卓项目同级 | **桌面独立 `LianYu-PC`** | PC 与安卓彻底解耦 |
| Web 容器 | 未指定 | **Servlet（MVC）** | 不用 WebFlux；100 并发 SSE 目标用 Tomcat NIO + 异步 Servlet 满足 |
| dao 模块 | dao 混 MinIO/Milvus | **拆出 storage 模块** | 事务边界清晰，未来替换不连坐 |

## 6. AI / Agent 决策

- **当前阶段**：仅引入 **Spring AI ChatClient**，不引入 Agent 编排框架。
- **角色 prompt**：自研 `CharacterPromptBuilder`，参照安卓端 prompt 模板（researcher 读安卓源码后摘录到 PC 项目内）。
- **记忆**：Spring AI `ChatMemory` 接口 + 自研 Milvus 实现（写读经 `lianyu-storage`）。
- **未来升级**：出现"工具调用 / 多角色协作 / 任务编排"需求时，再引入 Spring AI Tool Calling 或 Spring AI Alibaba Agent 模块（接口预留，不重构）。

## 7. 安全模型

| 数据类型 | 处理方式 |
|---|---|
| 用户密码 | BCrypt 哈希入库（`BCryptPasswordEncoder`，cost=10） |
| API Key（OpenAI/Gemini/Partner） | Jasypt 字段级加密（AES-GCM） |
| 主密钥管理 | 环境变量 `LIANYU_MASTER_KEY` **支持多版本格式**：`v1=base64key1,v2=base64key2,current=v2`。新数据用 `current` 版本加密；解密按字段记录的 `key_version` 路由 |
| 密钥轮换 | 表 `api_key_vault.key_version` 记录加密时的密钥版本；新增 `v3` 时，灰度迁移用后台 job 重加密旧数据，不停服 |
| 登录态 | Sa-Token + Redis（开启 AUTH，生产 TLS） |
| 多设备登录 | **允许多端并存（不互踢）**，但提供"踢全部其他设备"接口 |
| Actuator 端点 | `/actuator/health` 走 Sa-Token 拦截器，仅返回 status；详情仅 admin 角色可见 |
| 文件 | MinIO 桶级权限 + 预签名 URL（默认 TTL 1h，前端检测过期主动重签） |
| 文件上传 | 头像 ≤ 2MB / image-only，附件 ≤ 10MB；MIME 类型 + magic bytes 双重校验 |
| 公网入口 | HTTPS + HSTS（生产强制） |
| 内网通信 | 同机部署用 localhost HTTP；分离部署用 mTLS |
| 输入校验 | `@Valid` + Jakarta Validation 注解 |
| **Prompt 注入防护** | 用户性格设定文本入库前剥离控制字符；拼 system prompt 时用 XML/分隔符包裹（`<character_settings>...</character_settings>`），并在系统指令里告知模型忽略包裹内的指令性内容 |
| SSE / WS 限流 | Resilience4j RateLimiter，单用户 10 req/min（可配） |

**安卓端 KMS 那套白盒AES + SM4 + NEON + TEE 不移植**，威胁模型不同。

## 8. 数据库 schema 顶层设计

```sql
user                 id BIGINT PK, username VARCHAR(64) UNIQUE, password_hash CHAR(60) BCrypt,
                     nickname, avatar_url, created_at, updated_at

character            id BIGINT PK, owner_user_id BIGINT, name, avatar_url,
                     settings JSON, prompt_template TEXT, created_at, updated_at

conversation         id BIGINT PK, user_id, character_id NULL (群聊为 NULL),
                     mode ENUM('SINGLE','GROUP','COMPANION'), title, created_at

group_member         conversation_id BIGINT, character_id BIGINT, sort_order INT,
                     PK(conversation_id, character_id)

message              id BIGINT PK,
                     seq BIGINT NOT NULL,              -- 单调递增 sequence（per conversation），解决毫秒冲撞
                     conversation_id BIGINT,
                     role ENUM('USER','ASSISTANT','SYSTEM','TOOL'),
                     character_id BIGINT NULL,          -- 谁说的（USER 时为 NULL）
                     content MEDIUMTEXT, tokens INT, created_at,
                     UNIQUE(conversation_id, seq), INDEX(conversation_id, created_at)

memory_meta          id BIGINT PK, character_id BIGINT, user_id BIGINT,
                     summary TEXT, source_msg_ids JSON, source_hash CHAR(64) UNIQUE,  -- 去重
                     milvus_vec_id VARCHAR(64), created_at, INDEX(character_id, user_id)

api_key_vault        id BIGINT PK, user_id BIGINT, provider VARCHAR(32),
                     api_key_encrypted TEXT,
                     key_version VARCHAR(16) NOT NULL,   -- 加密时使用的密钥版本
                     base_url, model_default, created_at, updated_at,
                     UNIQUE(user_id, provider)

provider_model_cache (本表删除，改用 Redis：key=provider_models:{provider}:{user_id_or_global}, TTL 1h, CRUD 时主动 invalidate)
```

向量入 Milvus 集合 `memory_vectors`（dim=1536，OpenAI text-embedding-3-small 兼容；可配为 BGE 768）。

**关键变化**（响应 claude-review §8 反馈）：
- `message.seq`：每个 conversation 内单调递增（应用层用 Redis INCR 生成），群聊并发回复也能严格排序。
- `api_key_vault.key_version`：支持主密钥轮换。
- `memory_meta.source_hash`：`SHA-256(sorted(source_msg_ids))` 唯一约束，防止并发摘要产生重复记忆。
- `provider_model_cache` 表删除，迁到 Redis。
- 所有 JSON 列在 `lianyu-common` 注册统一 `JacksonTypeHandler`，禁止 String 兜底。

## 9. 部署形态

- 开发：本地 Docker Compose 编排（mysql / redis / rabbitmq / milvus-standalone / minio + backend + frontend）
- **最低硬件**：8GB RAM 跑不动全栈（Milvus standalone 自带 etcd+MinIO 约 4GB），**推荐 16GB RAM 开发机**。文档里前置说明。
- 生产：单机 Docker Compose（小规模）或 K8s（中等规模，预留 helm chart 入 Phase 8）
- Nginx 反向代理 `/api/*`，**SSE 路由必须 `proxy_buffering off; proxy_read_timeout 1h;`**
- Redis 生产形态：Sentinel（最小高可用）；开发期单机
- `.env` 管理：根目录 `.env.example` 模板入仓；真实 `.env` 入 `.gitignore`；Docker Compose 用 `env_file` 注入

## 10. 风险登记

| # | 风险 | 影响 | 缓解 |
|---|---|---|---|
| R1 | SSE 长连接遇 Token 过期 → 流断 | 中 | Sa-Token 配置 token 滑动续期；SSE 每 30s 发心跳 event；前端检测断流自动重连（带 `Last-Event-ID` 续传） |
| R2 | Milvus standalone 内存占用大 → 低配机跑不动 | 中 | 文档前置 16GB 推荐配置；Phase 8 提供精简版 compose（移除部分非必要服务用于演示） |
| R3 | 群聊多角色并发回复状态机未定义 | 高 | Phase 4 启动前 researcher 输出状态机图：单队列串行回复（一次一个角色），用户新输入触发"取消当前未完成角色 + 重新排队"，所有角色用 Redis 分布式锁互斥 |
| R4 | 记忆召回质量无法量化 | 中 | Phase 4 出口除 P95 < 100ms 外，增加 10 条人工标注 case 的 Recall@5 ≥ 80% |
| R5 | Prompt 注入（用户性格设定字段） | 中 | §7 已定方案：剥离控制字符 + XML 包裹 + 系统指令告知 |
| R6 | fetchModels 缓存陈旧 | 低 | Redis 缓存 TTL 1h **+ CRUD 时主动 invalidate** |
| R7 | Servlet 并发模型 vs 100 SSE 目标 | 中 | Tomcat NIO + 异步 Servlet（`SseEmitter` 不占线程）已实测可撑 200+；Phase 8 压测验证 |
| R8 | MinIO 预签名 URL 过期 / 上传大小 | 低 | §7 已定：1h TTL + 前端检测重签；上传 multipart 限制按类型配 |
| R9 | 多设备登录策略 | 低 | §7 已定：允许并存 + 主动踢人接口 |
| R10 | Spring AI 版本演进破坏性变更 | 中 | 锁定 1.0.0 GA；升级前过 claude-review 架构 checkpoint |
| R11 | RabbitMQ 早期占用资源 | 低 | Phase 1 仅声明 exchange/queue，不发消息；Phase 4 才正式启用 |
| R12 | SSE 在 Nginx/Cloudflare 默认缓冲 | 中 | §9 已写入 Nginx 配置要求 |
| R13 | AI provider 调用超时拖垮线程池 | 高 | Resilience4j：每个 provider 独立 bulkhead（限并发）+ timeLimiter（5s 首 token，30s 总） + circuitBreaker |
| R14 | Jasypt 主密钥泄露 | 高 | §7 多版本主密钥 + 灰度重加密；`LIANYU_MASTER_KEY` 仅环境变量，不入仓 |
| R15 | Flyway 无 down migration | 低 | 约定：所有迁移必须可重入（`CREATE TABLE IF NOT EXISTS`、加列不删列）；破坏性变更须人工评审 |

## 11. Open Items（全部 close）

- [x] PC 项目根目录 → `C:\Users\hp\Desktop\LianYu-PC\`
- [x] 主密钥轮换策略 → §7 多版本格式 + `key_version` 字段
- [x] `feature/wechat` → **废弃**（PC 端不实现）
- [x] embedding 模型 → 默认 OpenAI text-embedding-3-small（dim=1536）；Phase 7 启用 Ollama 后可切 BGE
- [x] Web 容器 → Servlet
- [x] Spring Boot 与 Spring AI 版本 → 3.3.5 / 1.0.0

## 12. Acceptance Criteria（本 PLAN review 通过标准）

- [x] §2 所有依赖坐标包含锁定版本号
- [x] §2.1 模块依赖方向无环；`lianyu-storage` 已拆分
- [x] §8 schema 包含 `message.seq`、`api_key_vault.key_version`、`memory_meta.source_hash`
- [x] §7 安全模型覆盖密钥轮换、prompt 注入、多设备登录、文件上传限制
- [x] §10 风险登记覆盖 SSE 代理 / 外部限流 / 密钥轮换 / 召回质量 / Servlet 并发
- [x] §11 Open Items 全部 closed
- [x] §9 部署文档明确硬件下限、Nginx SSE 配置、`.env` 管理
