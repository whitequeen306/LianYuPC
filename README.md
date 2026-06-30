# LianYu-PC

LianYu 桌面客户端 + 云端后端：Spring Boot 3 + Vue 3 + Electron。与安卓端项目**完全独立**（安卓端目录只读参考，勿写入）。

**当前进度**：登录、角色、单聊 SSE、群聊 WebSocket、记忆、角色广场等核心功能已实现。

## 开发与发布方式

| 端 | 在哪里跑 | 怎么发布 |
|---|---|---|
| **Electron 桌面客户端** | 用户本机 | 本地 `frontend/` 下 `npm run electron:release`，产出 `frontend/release/v{version}/LianYu Setup {version}.exe` |
| **后端 + API 网关** | 云服务器 `/opt/lianyu` | `git pull origin main` 后重建 backend / api-gateway 容器（见 `scripts/_cloud_deploy_pull.py`） |

不在本机跑全栈中间件；也不面向「clone 下来一键启动」的使用方式。协作约定见 [`CLAUDE.md`](CLAUDE.md)。

## 仓库结构

```
LianYu-PC/
├── README.md                 # 本文件
├── docker-compose.yml        # 云端部署编排（api-gateway + backend + 中间件）
├── deploy/api-gateway/       # 云端 API 网关（Nginx TLS 反代）
├── .env.example              # 服务器 / 维护用环境变量模板
│
├── frontend/                 # Vue 3 + Electron → 见 frontend/README.md
├── backend/                  # Spring Boot 多模块 → 见 backend/README.md
│
└── CLAUDE.md                 # 协作者 / Agent 约定
```

| 目录 | 干什么 |
|------|--------|
| **`frontend/`** | 桌面 UI；打包时写入加密 API 地址，运行时调 `/api`、连 `/ws` |
| **`backend/`** | 鉴权、数据库、AI、文件、推送等所有服务端能力 |
| **`deploy/`** | 云端 api-gateway 配置 |

## Electron 客户端打包

```bash
cd frontend
# 云端 API 地址写在 .env.production.cloud（不入库，见 .env.production.cloud.example）
npm run electron:release
```

产出：`frontend/release/v{version}/LianYu Setup {version}.exe`。  
迁机或换 API 地址后，更新 `frontend/.env.production.cloud` 并重打安装包。

根目录 `.env` 中的 `LIANYU_RUNTIME_SECRETS_PEPPER` 为打包必填（加密运行时配置，不入库）。

## 云端后端发布

源码 push 到 `main` 后，在服务器 `/opt/lianyu` 拉取并重建：

```bash
git pull origin main
docker compose up -d --build backend api-gateway
```

环境变量以服务器上的 `.env` 为准（模板见 `.env.example`）。`LIANYU_MASTER_KEY` 用于解密 MySQL 中平台 API Key 密文，须向负责人索取。

## API 文档（Knife4j / Swagger）

生产环境默认关闭。开发或服务器内网调试时可访问 `doc.html`（需相应 profile / 白名单开启）。

**鉴权**：调试受保护接口时，先 `POST /api/auth/login`，将 token 填入 Header **`lianyu-token`**。

## 主要 HTTP API 一览

前缀均为 `/api`，统一响应 `Result<T>`（`code` / `message` / `data`）。完整定义以 Swagger 为准。

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

**WebSocket**：`/ws`（STOMP），CONNECT 头需 `token`；订阅 `/topic/group/{conversationId}` 需会话归属。

## 构建与检查（推仓库前）

```bash
# 后端
cd backend && mvn -B test

# 前端
cd frontend && npm ci && npm run build && npm run test -- --run
```

CI（`.github/workflows/ci.yml`）在 push/PR 时自动跑上述测试。

## 与安卓端

安卓项目位于 `LianYu-master`（路径因机器而异），**禁止**在本仓库外修改安卓代码；仅作 UI / Prompt / 业务对照参考。

## 更多文档

- Agent 约定：`CLAUDE.md`
- 前端：`frontend/README.md`
- 后端：`backend/README.md`
