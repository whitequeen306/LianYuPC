# frontend — 用户界面（Vue 3 Web 应用）

本目录是 **LianYu-PC 的前端**（Vue 3 单页应用）：登录/注册、首页、角色与角色广场、单聊（SSE）、群聊（WebSocket）、记忆、朋友圈、设置等页面。数据全部请求 [`../backend`](../backend) 的 `/api` 与 `/ws`。

| 项 | 说明 |
|----|------|
| 技术栈 | Vue 3.5、Vite 5、Element Plus、Pinia、Vue Router、Axios |
| 开发端口 | `5173`（`npm run dev`，需本机或网关反代后端） |
| 生产访问 | Docker 下由 **Nginx 容器** 提供，默认 `http://localhost`（端口见 `.env` 的 `LIANYU_FRONTEND_PORT`） |
| Docker 构建 | 根目录 `docker compose up` 时使用本目录 `Dockerfile` |

## 目录结构

```
frontend/
├── src/
│   ├── pages/          # 页面：登录、聊天、群聊、角色广场、记忆、朋友圈…
│   ├── components/     # 可复用组件（侧栏、群头像、落地页动效等）
│   ├── api/            # 对后端 /api 的 Axios 封装
│   ├── stores/         # Pinia（用户、设置、通知等）
│   ├── router/         # 路由与登录守卫
│   ├── i18n/           # 多语言（简中 / 繁中 / 英 / 日）
│   └── styles/         # 全局 SCSS、主题变量
├── public/             # 静态资源（favicon、落地页图）
├── nginx.conf          # 生产镜像：反代 backend:8080 + Swagger 路径
├── Dockerfile          # npm build → Nginx 托管 dist
└── Dockerfile.gateway  # 仅开发：反代宿主机 5173/8080（compose profile dev-proxy）
```

## 主要页面（`src/pages/`）

| 文件 | 功能 |
|------|------|
| `LandingPage.vue` | 未登录落地页 |
| `LoginPage.vue` / `RegisterPage.vue` | 登录注册（验证码） |
| `HomePage.vue` | 登录后首页 |
| `CharactersPage.vue` / `CharacterSquarePage.vue` | 我的角色 / 角色广场 |
| `ChatPage.vue` / `CharacterChatDetailPage.vue` | 会话列表 / 单聊（SSE 流式） |
| `GroupChatPage.vue` | 群聊（STOMP over WebSocket） |
| `MemoryPage.vue` | 记忆查看 |
| `MomentsPage.vue` | 朋友圈 |
| `SettingsPage.vue` / `ProfilePage.vue` | 设置与个人资料 |

## 常用命令

```bash
npm ci
npm run dev      # http://localhost:5173（需后端 8080 可访问）
npm run build    # 产出 dist/（已 gitignore）
npm run test     # Vitest（当前可无业务用例）
```

全栈一键启动请在**仓库根目录**执行 `docker compose up -d --build`，无需单独 `npm run dev`。
