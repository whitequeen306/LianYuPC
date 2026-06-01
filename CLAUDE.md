# CLAUDE.md — LianYu-PC

PC 端桌面/Web 复刻版，独立于安卓端项目。

## 项目根目录

`C:\Users\hp\Desktop\LianYu-PC\`

## 安卓端（只读参考）

`C:\Users\hp\Desktop\LianYu-master (1)\LianYu-master\`

**绝不修改、绝不写入安卓端目录。** 安卓端仅作为 read-only 参考（prompt 模板、UI 变量数值、业务逻辑对照）。

## 技术栈速查

| 层 | 技术 |
|---|---|
| 前端 | Vue 3.5 + Vite 5 + Element Plus 2.8 + Pinia 2.2 + Vue Router 4.4 + Axios 1.7 + @stomp/stompjs 7 |
| 后端 | Spring Boot 3.3.5 (Servlet/MVC) + Maven 3.9 多模块 + JDK 17 |
| ORM | MyBatis-Plus 3.5.9 |
| DB | MySQL 8.4 + Flyway 10.20.1 (schema 迁移) |
| 缓存/会话 | Redis 7 + Lettuce + commons-pool2 2.12 |
| 消息队列 | RabbitMQ + Spring AMQP |
| 向量库 | Milvus 2.4.x Standalone (SDK 2.4.5) |
| 对象存储 | MinIO (SDK 8.5.13) |
| 鉴权 | Sa-Token 1.39.0 (Redis 持久化) |
| 密码哈希 | BCrypt (spring-security-crypto 6.3.4, cost=10) |
| API Key 加密 | Jasypt 3.0.5 (AES-GCM) |
| AI 抽象 | Spring AI 1.0.0 GA (ChatClient + EmbeddingClient) |
| 熔断限流 | Resilience4j 2.2.0 |
| API 文档 | Knife4j 4.5.0 (OpenAPI 3) |
| 测试 | JUnit 5 + Mockito + Testcontainers 1.20.3 |
| 前端测试 | Vitest 2.x |

## 后端模块结构

```
backend/
├── pom.xml                  # parent BOM + 版本锁定
├── lianyu-common/           # 工具类、基类、统一异常、Result<T>、JacksonTypeHandler
├── lianyu-dao/              # MySQL：Entity / Mapper / Flyway migration
├── lianyu-storage/          # MinIO client + Milvus client（无事务外部存储）
├── lianyu-security/         # Sa-Token、Jasypt、BCrypt 封装、密钥版本管理
├── lianyu-ai/               # Spring AI 集成、CharacterPromptBuilder、ChatMemory
├── lianyu-service/          # 业务逻辑（角色/对话/群聊/记忆）
├── lianyu-web/              # Controller、SSE、WebSocket、CORS、全局异常处理
└── lianyu-app/              # 启动类、application.yml、Docker 打包
```

依赖方向（无环）：
```
lianyu-app → lianyu-web → lianyu-service → lianyu-ai / lianyu-dao / lianyu-security
                                               ↓              ↓
                                          lianyu-storage    lianyu-common (所有模块可依赖)
```

## 关键设计决策

- **Web 容器**：Servlet（Spring MVC），不用 WebFlux。SSE 用 `SseEmitter` + Tomcat NIO。
- **dao 与 storage 分离**：`lianyu-dao` 只管 MySQL；MinIO/Milvus 走 `lianyu-storage`。事务边界清晰。
- **API Key 加密**：Jasypt AES-GCM 字段级加密；主密钥来自环境变量 `LIANYU_MASTER_KEY`，支持多版本轮换（`v1=...,v2=...,current=v2`）。
- **消息 seq**：每个 conversation 内用 Redis INCR 生成单调递增 sequence，解决毫秒级并发排序。
- **记忆去重**：`memory_meta.source_hash` = SHA-256(sorted(source_msg_ids))，唯一约束防重复摘要。
- **provider_model_cache**：走 Redis（key=`provider_models:{provider}:{user_id_or_global}`，TTL 1h），不用 MySQL 表。
- **群聊回复**：单队列串行（一次一个角色发言），用户新输入可中断当前队列。Redis 分布式锁互斥。
- **Prompt 注入防护**：用户输入剥离控制字符 → XML 包裹 → 系统指令告知模型忽略包裹内指令。
- **多设备登录**：允许并存不互踢；提供"踢全部其他设备"接口。
- **SSE 断流**：每 30s 心跳 event；前端 `EventSource` 自动重连带 `Last-Event-ID`；Sa-Token 滑动续期。
- **AI provider 容错**：Resilience4j bulkhead（限并发）+ timeLimiter（5s 首 token / 30s 总）+ circuitBreaker。
- **Flyway 迁移**：所有迁移必须可重入（`CREATE TABLE IF NOT EXISTS`）；破坏性变更须人工评审。

## 数据库核心表

`user` / `character` / `conversation` / `group_member` / `message` / `memory_meta` / `api_key_vault`

详见 PLAN-001 §8。

## 执行计划

8 阶段执行计划见 `plans/PLAN-002-execution-plan.md`。

当前处于 **Phase 6 → Phase 7 交界**。Phase 1-6 已完成：后端 Phase 1-4 + 前端 Phase 5-6（登录/注册/首页/设置/角色管理/单聊 SSE 流式对话/群聊 WebSocket/记忆查看器全实现，前后端构建通过）。下一步：Phase 7 本地模型集成（Ollama 管理页、离线模式）。

## 开发环境

- 最低 16GB RAM（Milvus standalone 自带 etcd+MinIO 约 4GB）
- Docker Compose 编排全部中间件
- `.env.example` 入仓，`.env` 入 `.gitignore`

## 工作约定

- 所有文件操作在 `C:\Users\hp\Desktop\LianYu-PC\` 下进行
- 安卓端目录绝不写入，仅 read-only 参考
- 每阶段结束由 checker 智能体验收
- `pom.xml` / `package.json` 变更后必跑 OSV-Scanner
- 日志用 `@Slf4j` + traceId（Phase 1 到位）
- 统一返回格式 `Result<T>`（Phase 1 到位）
