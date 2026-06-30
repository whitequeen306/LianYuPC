# frontend — 用户界面（Vue 3 + Electron 桌面客户端）

本目录是 **LianYu-PC 的前端**：Vue 3 单页应用 + Electron 桌面壳。页面包括登录/注册、首页、角色与角色广场、单聊（SSE）、群聊（WebSocket）、记忆、朋友圈、设置等。运行时请求云端 `/api` 与 `/ws`。

| 项 | 说明 |
|----|------|
| 技术栈 | Vue 3.5、Vite 5、Element Plus、Pinia、Vue Router、Axios、Electron |
| 开发端口 | `5173`（`npm run dev`，需可连通的 backend 或 dev-proxy） |
| 生产分发 | **Electron 本地打包**（`npm run electron:release`） |
| 云端 API | `frontend/.env.production.cloud` 的 `VITE_LIANYU_API_ORIGIN`（不入库） |

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

打包前确保根目录 `.env` 含 `LIANYU_RUNTIME_SECRETS_PEPPER`，且 `frontend/.env.production.cloud` 已配置 API 地址。
