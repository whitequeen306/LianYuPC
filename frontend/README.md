# frontend — 用户界面（Vue 3 + Electron 桌面客户端）

本目录是 **LianYu-PC 的前端**：Vue 3 单页应用 + Electron 桌面壳。页面包括登录/注册、首页、角色与角色广场、单聊（SSE）、群聊（WebSocket）、记忆、朋友圈、设置等。数据请求 [`../backend`](../backend) 的 `/api` 与 `/ws`。

| 项 | 说明 |
|----|------|
| 技术栈 | Vue 3.5、Vite 5、Element Plus、Pinia、Vue Router、Axios、Electron |
| 开发端口 | `5173`（`npm run dev`，需本机 backend `8080` 或 dev-proxy 网关） |
| 生产分发 | **Electron 本地打包**（`npm run electron:release`），不走服务器 Docker frontend 容器 |
| 云端 API | `frontend/.env.production.cloud` 的 `VITE_LIANYU_API_ORIGIN` |

## 目录结构

```
frontend/
├── src/
│   ├── pages/          # 页面：登录、聊天、群聊、角色广场、记忆、朋友圈…
│   ├── components/     # 可复用组件
│   ├── composables/    # 组合式逻辑
│   ├── api/            # 对后端 /api 的 Axios 封装
│   ├── stores/         # Pinia
│   ├── router/         # 路由与登录守卫
│   └── electron/       # Electron 主进程（main.js）
├── public/             # 静态资源
├── Dockerfile          # 可选：独立 Web 镜像（compose 默认不用）
├── Dockerfile.gateway  # dev-proxy：反代宿主机 5173/8080
└── scripts/electron-pack.mjs  # Electron 打包流水线
```

## 常用命令

```bash
npm ci
npm run dev              # http://localhost:5173
npm run build            # Web dist（已 gitignore）
npm run electron:release # 桌面安装包 → release/
npm run test             # Vitest
```

**全栈 Docker（仅后端 + 网关）**：在仓库根目录 `docker compose up -d --build`。  
**本地前后端联调**：`docker compose --profile dev-proxy up -d web-gateway` + `npm run dev`。
