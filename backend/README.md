# backend — 服务端（Spring Boot API）

本目录是 **LianYu-PC 的后端**（Spring Boot 多模块）：提供 REST API、SSE 流式单聊、WebSocket/STOMP 群聊、鉴权、AI 调度、记忆与文件存储等。桌面客户端在 [`../frontend`](../frontend) 本地打包；云端经 `api-gateway` 反代本服务。

| 项 | 说明 |
|----|------|
| 技术栈 | Spring Boot 3.3、JDK 17、Maven 多模块 |
| 默认端口 | `8080`（`SERVER_PORT`；compose 绑定 `127.0.0.1`） |
| API 文档 | `http://localhost:8080/doc.html`（开发环境） |
| Docker 构建 | 根目录 `docker compose up` 时使用本目录 `Dockerfile` |

## 模块一览（`pom.xml` 子模块）

依赖方向：`lianyu-app` → `lianyu-web` → `lianyu-service` → 各基础模块。

| 目录 | 职责 |
|------|------|
| **lianyu-app** | 启动入口、`application.yml`、角色广场头像资源 `square-avatars/` |
| **lianyu-web** | Controller、全局异常、CORS、Knife4j、WebSocket 配置 |
| **lianyu-service** | 业务：用户/角色/会话/群聊/记忆/朋友圈/Vault/AI 聊天 |
| **lianyu-ai** | Spring AI、Prompt 组装、ChatMemory |
| **lianyu-dao** | MyBatis-Plus 实体与 Mapper、**Flyway** 迁移 `db/migration/` |
| **lianyu-security** | Sa-Token、BCrypt、Jasypt 字段加密（`LIANYU_MASTER_KEY`） |
| **lianyu-storage** | MinIO、Milvus 客户端 |
| **lianyu-common** | `Result<T>`、错误码、工具类、统一异常 |

## 常用命令

```bash
# 在 backend/ 目录下
mvn -B -DskipTests compile          # 编译检查
mvn -pl lianyu-app spring-boot:run  # 本机跑（需根目录 .env 且中间件已启动）

# Docker 全栈（推荐，在仓库根目录）
cd ..
docker compose up -d --build
```

## 其它路径

| 路径 | 说明 |
|------|------|
| `scripts/` | 维护脚本（如 `seed-default-vault-pool.ps1` 轮换平台 Key） |
| `Dockerfile` | 多阶段镜像：Maven 打包 → JRE 运行 |
| `settings-docker.xml` | Docker 构建时 Maven 阿里云镜像 |

## Flyway 迁移

SQL 在 `lianyu-dao/src/main/resources/db/migration/`。版本 **V30** 为刻意占位（`V30__version_gap_placeholder.sql`），**禁止复用**该版本号。

更全的设计说明见仓库根目录 [`CLAUDE.md`](../CLAUDE.md)。
