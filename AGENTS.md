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

## 推送 / 部署 / 发版（强制）

凡涉及 **`git push`、云服务器部署、Electron 发版、GitHub Releases、MinIO 更新包**（用户说「一条龙」「发版」「部署」「上线」等）时：

1. **先读** `local/ship-release.ps1` 与 `local/README.md`（`local/` 已 gitignore，只在本机）
2. **用脚本跑**，不要手写拆开 `git push` + `python scripts/_cloud_deploy_pull.py` + `npm run electron:release`
3. 按改动选参数：`-BackendOnly` / `-ElectronOnly` / 无参数全量（见下方「发布发版」表）

缺 `local/ship-release.ps1` 时按 `local/README.md` 重建，不要另发明流程。

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
| 向量库 | Milvus 2.4.x Standalone（Docker 镜像 `v2.4.15`，SDK 2.4.5） |
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
├── lianyu-ai/               # Spring AI 集成、ChatTurn Graph 契约（Keys/State/Scene）
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

- **AI 回合编排**：Spring AI Alibaba Graph（`CompiledGraph chatTurn`）。`OverAllState` + `ChatTurnKeys` 承载上下文；`ChatTurnFacade` 为外层入口。SSE / 配额 / Resilience4j / 落库回调留在 ConversationService 等适配层。
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

## 开发环境

- 最低 16GB RAM（Milvus standalone 自带 etcd+MinIO 约 4GB）
- Docker Compose 编排全部中间件
- `.env.example` 入仓，`.env` 入 `.gitignore`

## 发布发版（Agent 必看 — 不要另想流程）

**唯一入口：`.\local\ship-release.ps1`。** 详细说明见文首「推送 / 部署 / 发版（强制）」与 `local/README.md`。

**前后端分离：** Electron 只在本机打；云端只跑 backend/api-gateway + 中间件，不构建前端。

功能改动**先自行 commit 到 `main`**、工作区干净后，再跑一条龙（脚本**不**替你写业务 commit）。

**禁止：**

- 手搓 `git push` + `_cloud_deploy_pull.py` + `electron:release`（易漏 Draft 清理 / 版本号回写）
- 无脑跑无参数全量（只改一侧却 FULL）

脚本启动会打印 `SHIP PLAN`（push/deploy/electron 各 YES/no），对不上就 Ctrl+C。

| 场景 | 命令 | 会触发 |
|---|---|---|
| **只改后端**（Java / Flyway / `pet-voices.json` 等） | `.\local\ship-release.ps1 -BackendOnly` | push + 云端 rebuild；**绝不**打 Electron / Releases / MinIO 更新包 |
| **只改前端**（Vue / Electron / `public/pet`） | `.\local\ship-release.ps1 -ElectronOnly` | push + Electron + GitHub Releases + MinIO；**绝不** docker rebuild backend |
| **前后端都改** | `.\local\ship-release.ps1` | 全量（唯一允许的无参数用法） |
| minor / major | 在上面对应命令后加 `-Bump minor` | 同上 |

```powershell
cd C:\Users\hp\Desktop\LianYu-PC
.\local\ship-release.ps1 -BackendOnly    # 例：只后端
```

**边界（仍须记住）：**

- 主分支始终是 `main`；禁止 SCP 源码/jar 覆盖 `/opt/lianyu`
- 只改后端 → 用户无需重装客户端；只改前端 Vue/Electron/`public/pet` → 必须打 Electron
- 改 API Origin → 改已入仓的 `frontend/.env.production.cloud` 后重打 Electron
- 云端部署读根目录 `.env` 的 `DEPLOY_SSH_PASSWORD`；`GH_TOKEN` 用环境变量或 Credential Manager，**禁止写入任何文件**

## 工作约定

- 所有文件操作在 `C:\Users\hp\Desktop\LianYu-PC\` 下进行
- `pom.xml` / `package.json` 变更后必跑 OSV-Scanner
- 日志用 `@Slf4j` + traceId
- 统一返回格式 `Result<T>`

## 桌宠开发

新增角色桌宠（atlas + 语音 + 前后端接入）时，**必须先读取 skill 文档**：

`Pets/skills/hatch-lianyu-pet/SKILL.md`

该 skill 自带脚本（`Pets/skills/hatch-lianyu-pet/scripts/`），包含完整流程：参考图 → gpt-image-2 生成逐帧 → hatch-pet 拼合 atlas → 接入 petCatalog/desktopSettings/pet-voices → 打包发布。参考图放到 `Pets/images/` 下。

涉及的关键文件（新增角色时都要改）：

| 文件 | 作用 |
|---|---|
| `frontend/public/pet/<id>_spritesheet.webp` | 1536×1872 atlas（新增） |
| `frontend/public/pet/<id>_idle0.png` | 192×208 预览帧（新增） |
| `frontend/src/constants/petCatalog.js` | 桌宠目录条目 |
| `frontend/electron/desktopSettings.js` | 主进程 ALLOWED_PET_IDS（**漏了会导致切换不生效**） |
| `backend/.../resources/pet-voices.json` | 后端语音映射（改后需重建 backend） |

**注意**：前端改动走 `.\local\ship-release.ps1`；仅改 `pet-voices.json` / 后端可用 `-BackendOnly`。
