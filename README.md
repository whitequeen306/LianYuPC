# LianYu-PC

LianYu 桌面 / Web 端：Spring Boot 3 + Vue 3 + Vite + Element Plus。与安卓端项目**完全独立**（安卓端目录只读参考，勿写入）。

**当前进度**：Phase 1–6 已完成（登录、角色、单聊 SSE、群聊 WebSocket、记忆、角色广场等）；Phase 7 为 Ollama 本地模型。详见 `plans/PLAN-002-execution-plan.md`。

## 仓库结构

```
LianYu-PC/
├── README.md                 # 本文件：克隆、.env、Docker 一键启动
├── docker-compose.yml        # 编排：前端 + 后端 + MySQL/Redis/…
├── .env.example              # 环境变量模板 → 复制为 .env
│
├── frontend/                 # 【用户界面】Vue 3 单页应用 → 见 frontend/README.md
│   ├── src/pages/            #     登录、聊天、群聊、角色广场、记忆…
│   ├── Dockerfile            #     构建 dist + Nginx 反代后端
│   └── nginx.conf
│
├── backend/                  # 【服务端 API】Spring Boot 多模块 → 见 backend/README.md
│   ├── lianyu-web/           #     HTTP 接口、WebSocket、Swagger
│   ├── lianyu-service/       #     业务逻辑、AI 聊天、Vault 轮询
│   ├── lianyu-dao/           #     数据库实体 + Flyway 迁移
│   ├── lianyu-app/           #     启动模块 application.yml
│   └── Dockerfile            #     Maven 打包运行 jar
│
├── secrets/                  # 仅维护用示例（platform-keys.txt 不入库）
├── plans/                    # 技术选型与阶段计划
└── CLAUDE.md                 # 协作者 / Agent 约定
```

| 目录 | 干什么 | 谁改 |
|------|--------|------|
| **`frontend/`** | 浏览器里看到的页面与交互；调 `/api`、连 `/ws` | 前端 / 全栈 |
| **`backend/`** | 鉴权、数据库、AI、文件、推送等所有服务端能力 | 后端 / 全栈 |
| 根目录 `docker-compose.yml` | 把前后端和中间件一起拉起来 | 部署 |

## 环境要求

| 组件 | 版本建议 |
|------|----------|
| JDK | **17**（与 `backend/pom.xml`、Dockerfile 一致） |
| Maven | 3.9+ |
| Node.js | 18+ |
| Docker | 24+（Compose v2） |
| 内存 | 建议 ≥16GB（含 Milvus standalone） |

## 快速开始（推荐：全栈 Docker）

### 1. 准备 `.env`

```bash
cp .env.example .env
```

按下面 [**环境变量说明**](#环境变量说明env) 填好**必填项**（尤其中间件密码与 `LIANYU_MASTER_KEY`），再启动。

**平台聊天**：10 个团队公用 API Key 以 **密文** 存在 MySQL `api_key_vault`（Flyway **V22**），启动后 **10-key 轮询**；`.env` 里 **不要** 填 `OPENAI_API_KEY` / `OPENAI_API_KEYS`。

### 2. 一键启动（前后端 + 中间件）

**前提**：已安装 [Docker Desktop](https://www.docker.com/products/docker-desktop/)（Compose v2），本机建议 ≥16GB 内存；**`LIANYU_MASTER_KEY` 须向团队负责人私聊索取**（不能留空）。

```bash
docker compose up -d --build
```

首次构建会拉镜像并编译前后端，约 10–30 分钟视网速而定；Milvus 健康检查通过前 backend 会等待。

默认会启动：`frontend` + `backend` + MySQL/Redis/RabbitMQ/MinIO/Milvus。  
访问：`http://localhost:${LIANYU_FRONTEND_PORT:-80}`（默认 `http://localhost`）。

### 3. API 文档与接口调试

```bash
http://localhost:${LIANYU_FRONTEND_PORT:-80}/doc.html
```

Swagger/Knife4j 页面可直接浏览；调试受保护 API 时在 Authorize 填 `lianyu-token`。

### 4. 数据持久化（角色广场不会丢）

`docker-compose.yml` 已给核心数据服务挂载 volume：

- MySQL：`mysql_data`
- MinIO：`minio_data`
- Redis：`redis_data`
- RabbitMQ：`rabbitmq_data`
- Milvus：`milvus_data`

角色广场模板数据来自 Flyway migration（MySQL），头像对象存 MinIO。  
后端启动时会执行 `CharacterSquareAvatarSync`，将内置 `square-avatars/*` 同步到 MinIO（不存在才写入），因此首次启动也能看到头像；后续重启依赖 `minio_data` 持久化。

---

## 环境变量说明（`.env`）

与 `docker-compose.yml`、`.env.example` 一一对应。  
**用法**：复制 `.env.example` → `.env`，只改「必填」列；值不要加引号，整行粘贴（尤其 `LIANYU_MASTER_KEY`）。

### `LIANYU_MASTER_KEY` 是什么？（解密密钥）

这是 **Jasypt 主密钥**，用来解密数据库里 `api_key_vault.api_key_encrypted` 字段（团队 10 个 API Key 的密文），**不是**某一个 `sk-...` 聊天 Key 本身。

| 说明 | 内容 |
|------|------|
| 谁提供 | 团队负责人（与写入 V22 密文时用的是同一把） |
| 填什么 | **整行原样粘贴**，一行、无换行，通常较长（内含 Base64） |
| 标准格式 | `v1=<Base64主密钥>,current=v1` |
| 示例形态（非真实值） | `v1=AbCdEf1234...+/=,current=v1` |
| 填错现象 | 启动后聊天报「API Key 解密失败」 |

生成新主密钥（仅负责人轮换时用）：

```bash
openssl rand -base64 32
# 写入 .env：v1=<上面输出>,current=v1
```

轮换主密钥后需用 `backend/scripts/seed-default-vault-pool.ps1` 重新加密 10 个平台 Key 并更新迁移/SQL。

---

### 必填（`docker compose up` 跑不起来就先看这里）

| 变量名 | 用于 | 说明 | 示例 |
|--------|------|------|------|
| `MYSQL_ROOT_PASSWORD` | MySQL 容器 | root 密码，首次创建数据卷时生效 | 自设强密码 |
| `MYSQL_USER` | MySQL 容器 | 业务库用户，库名固定 `lianyu` | `lianyu` |
| `MYSQL_PASSWORD` | MySQL 容器 | 上面用户的密码，后端连库也用 | 与 compose 一致 |
| `MYSQL_PORT` | 宿主机映射 | 本机连 MySQL 的端口（容器内仍是 3306） | `3307` |
| `REDIS_PASSWORD` | Redis 容器 | 登录/验证码/Sa-Token/限流 | 自设 |
| `REDIS_PORT` | 宿主机映射 | 本机连 Redis | `6379` |
| `RABBITMQ_USER` | RabbitMQ | 管理账号 | `guest` |
| `RABBITMQ_PASSWORD` | RabbitMQ | 管理密码 | 自设 |
| `RABBITMQ_PORT` | 宿主机映射 | AMQP 端口 | `5672` |
| `MINIO_ACCESS_KEY` | MinIO | 对象存储访问键（头像、聊天图） | 自设 |
| `MINIO_SECRET_KEY` | MinIO | 对象存储密钥 | 自设 |
| `MINIO_BUCKET` | 后端 | 默认桶名 | `lianyu` |
| `MILVUS_PORT` | 宿主机映射 | 向量库 gRPC 端口 | `19530` |
| `LIANYU_MASTER_KEY` | 后端 | **解密** `api_key_vault` 密文（见上一节） | 向团队索取整行 |
| `SERVER_PORT` | 后端 | Spring Boot 暴露端口 | `8080` |
| `LIANYU_FRONTEND_PORT` | 前端容器 | 浏览器访问端口 | `80` |

> **注意**：改 `MYSQL_*` / `REDIS_PASSWORD` 等后，若容器已创建过旧卷，需 `docker compose down -v` 再 `up`（会清空数据），或保持与旧卷一致的密码。

---

### 后端连接地址（Docker 全栈时一般不用改）

`docker-compose.yml` 里 `backend` 服务会把下列变量**覆盖**为容器内服务名（`mysql`、`redis` 等）。  
仅在 **不用 Docker、本机直接 `mvn spring-boot:run`** 时才需要填 `localhost`：

| 变量名 | 默认值 | 说明 |
|--------|--------|------|
| `MYSQL_HOST` | `localhost` | MySQL 主机 |
| `REDIS_HOST` | `localhost` | Redis 主机 |
| `RABBITMQ_HOST` | `localhost` | RabbitMQ 主机 |
| `MINIO_ENDPOINT` | `http://localhost:9000` | MinIO API 地址 |
| `MILVUS_HOST` | `localhost` | Milvus 主机 |

---

### 平台 AI（团队 10-key 池 — 请保持为空）

| 变量名 | 是否填写 | 说明 |
|--------|----------|------|
| `OPENAI_API_KEY` | **留空** | 已关闭单 Key 兜底；填了也不会作为平台池使用 |
| `OPENAI_API_KEYS` | **留空** | 已关闭 env 逗号分隔池；平台池只走 MySQL |
| `OPENAI_BASE_URL` | 留空 | 网关地址在 `api_key_vault.base_url`（V22 已写入） |
| `OPENAI_CHAT_MODEL` | 留空 | 默认模型在 `api_key_vault.model_default` |
| `OPENAI_EMBEDDING_MODEL` | 可选 | 仅影响 env 兜底嵌入模型名 |

---

### 可选（不填也能跑主流程）

| 变量名 | 说明 |
|--------|------|
| `LIANYU_GATEWAY_PORT` | 仅 `--profile dev-proxy` 时 Nginx 反代宿主机 5173/8080 的端口，默认 `8088` |
| `APP_REVISION` | 镜像标签后缀，`docker compose build` 时可改成日期或 git hash |
| `OLLAMA_BASE_URL` / `OLLAMA_CHAT_MODEL` | 本地 Ollama（Phase 7） |
| `DASHSCOPE_API_KEY` | 百炼视觉识图等 |
| `EMBEDDING_API_KEY` | 记忆向量嵌入（百炼兼容接口） |
| `RERANKER_API_KEY` | 记忆重排序 |
| `LIANYU_VISION_MODEL` | 视觉模型名，默认 `qwen3-vl-flash` |
| `LIANYU_MOMENTS_IMAGE_*` | 朋友圈文生图（可选） |
| `LIANYU_PUSH_*` | 浏览器 Web Push（默认关） |
| `LIANYU_API_DOCS_ENABLED` | 是否开放 Knife4j，开发 `true` |
| `LIANYU_AI_DIRECT_CHAT` | 是否开放 `/api/ai/chat` 直连，默认 `false` |
| `SPRING_PROFILES_ACTIVE` | 设 `prod` 时关闭 API 文档（见 `application-prod.yml`） |

---

### 维护用（新人 clone **不需要**）

| 文件 / 脚本 | 说明 |
|-------------|------|
| `secrets/platform-keys.txt` | 仅**轮换** 10 个平台 Key 时用（gitignore，勿提交） |
| `backend/scripts/seed-default-vault-pool.ps1` | 用当前 `LIANYU_MASTER_KEY` 重新加密并写入 MySQL id 1–10 |

---

## API 文档（Knife4j / Swagger）

**开发环境**默认开启（`lianyu.api-docs.enabled=true`，见 `application.yml`）。

| 入口 | 说明 |
|------|------|
| http://localhost:8080/doc.html | Knife4j 主界面（推荐） |
| http://localhost:8080/swagger-ui.html | SpringDoc UI |
| http://localhost:8080/v3/api-docs | OpenAPI JSON |

经开发网关时同样可用：**http://localhost/doc.html**（`nginx.dev.conf` 已反代文档路径）。

**鉴权说明**

- 浏览文档页面：**无需登录**。  
- 在 Knife4j 里调试受保护接口：先 `POST /api/auth/login`（需验证码），将返回的 token 填入全局 Header **`lianyu-token`**。  
- 公开接口：`/api/auth/captcha`、`/api/auth/login`、`/api/auth/register`、`/api/public/**`。

**生产环境**

```bash
SPRING_PROFILES_ACTIVE=prod
LIANYU_API_DOCS_ENABLED=false
```

`application-prod.yml` 会关闭 Knife4j / SpringDoc / Sa-Token 文档白名单。

---

## 主要 HTTP API 一览

前缀均为 `/api`，统一响应 `Result<T>`（`code` / `message` / `data`）。完整定义以 **Swagger** 为准。

| 模块 | 路径前缀 | 说明 |
|------|----------|------|
| 认证 | `/api/auth` | 验证码、注册、登录、登出、`/me`、头像 |
| 角色 | `/api/character` | CRUD、广场 `/square`、从广场添加、AI 生成 |
| 会话 | `/api/conversation` | 单聊会话、消息（含 SSE `/messages/stream`）、分页历史、聊天图上传 |
| 群聊 | `/api/conversation/group` | 创建群、成员、改标题；消息走 **WebSocket** `/ws` + STOMP |
| AI | `/api/ai` | Vault CRUD、`/models`；直连 `/chat` 默认关闭 |
| 记忆 | `/api/memory` | 列表分页、详情、删除 |
| 动态 | `/api/moments` | 朋友圈流、评论、未读 |
| 通知 | `/api/notifications` | 站内通知、Web Push 订阅 |
| 公开文件 | `/api/public/files/**` | MinIO 公开资源 |

**WebSocket**：`ws://localhost:8080/ws`（STOMP），CONNECT 头需 `token`；订阅 `/topic/group/{conversationId}` 需会话归属。

**常见错误码**：`401` 未登录；`403` 无权限 / 内容策略 `CONTENT_POLICY_VIOLATION`；`1005` `AUTH_RATE_LIMITED` 登录限流（HTTP 429）。

---

## Docker 说明

### 默认：全栈镜像模式

| 服务 | 宿主机端口 | 用途 |
|------|------------|------|
| frontend | `${LIANYU_FRONTEND_PORT:-80}` | Web 入口（Nginx，反代 backend） |
| backend | `${SERVER_PORT:-8080}` | Spring Boot API / WS |
| mysql | 3307→3306 | 业务库 `lianyu` |
| redis | 6379 | Sa-Token、验证码、限流、seq |
| rabbitmq | 5672 / 15672 | 记忆摘要、推送 |
| minio | 9000 / 9001 | 头像、聊天图片 |
| milvus | 19530 | 记忆向量 |
 
### 镜像重建（确保使用最新代码）

```bash
# 可选：变更镜像标签，避免 Docker 复用旧 tag
set APP_REVISION=20260601

docker compose build --no-cache
docker compose up -d
```

### 可选：仅本地联调网关（宿主机跑前后端）

```bash
docker compose --profile dev-proxy up -d web-gateway
```

`web-gateway` 只是反代宿主机 `5173/8080`，不打包业务代码。

### Dockerfile 要点

| 文件 | 是否打包业务源码 |
|------|------------------|
| `backend/Dockerfile` | ✅ 所有模块 `src` + `mvn clean package` |
| `frontend/Dockerfile` | ✅ `src` + `npm run build` → `dist` |
| `frontend/Dockerfile.gateway` | ❌ 仅 Nginx 配置，反代宿主机 |

构建后端需 `backend/settings-docker.xml`（阿里云 Maven 镜像）。

---

## 构建与检查（推仓库前）

```bash
# 后端
cd backend && mvn -B -DskipTests compile

# 前端
cd frontend && npm ci && npm run build && npm run test -- --run
```

`npm test` 当前无业务用例（`vitest --passWithNoTests`），以 `build` + 手工/API 文档回归为准。

---

## 与安卓端

安卓项目位于 `LianYu-master`（路径因机器而异），**禁止**在本仓库外修改安卓代码；仅作 UI / Prompt / 业务对照参考。

## 更多文档

- 技术栈：`plans/PLAN-001-tech-stack.md`
- 阶段计划：`plans/PLAN-002-execution-plan.md`
- Agent 约定：`CLAUDE.md`
