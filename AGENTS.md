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

## 开发环境

- 最低 16GB RAM（Milvus standalone 自带 etcd+MinIO 约 4GB）
- Docker Compose 编排全部中间件
- `.env.example` 入仓，`.env` 入 `.gitignore`

## 部署与发布（前后端分离，务必遵守）

**前端在本地打包，不在服务器上构建或部署；后端在服务器上跑 Docker，不涉及前端打包。**

| 端 | 运行位置 | 发布方式 | 不要做什么 |
|---|---|---|---|
| **前端（Electron 桌面客户端）** | 用户本机 | 本地 `frontend/` 下 `npm run electron:release`（或 `electron:build`），产出 `frontend/release/v{version}/LianYu Setup {version}.exe` | 不要在云端执行 `vite build` / `electron-builder`；不要把 `frontend/dist`、`release` 部署到服务器 |
| **后端 + API 网关** | 云服务器 `/opt/lianyu` | `git pull origin main` 后 `docker compose up -d --build backend api-gateway`（见 `scripts/_cloud_deploy_pull.py`） | 不要指望「部署后端」顺带更新客户端 UI；前端改动必须重新打 Electron 安装包 |

补充约定：

- 云端 Compose 仅跑 **backend、api-gateway** 与中间件（MySQL / Redis / MinIO / Milvus / RabbitMQ）；**无 frontend 容器**（`web-gateway` 仅 `dev-proxy` profile 本地联调用）。
- 客户端通过 `frontend/.env.production.cloud` 中的 `VITE_LIANYU_API_ORIGIN` 指向云端 API；API 地址变更改 env 并重打 Electron，不是改服务器前端静态资源。
- 仅后端修复（如 MinIO 资源、接口逻辑）部署后，**已安装的 Electron 客户端无需重装**即可生效；**仅前端 Vue 改动**（页面、样式、加载策略等）必须 **重新打 Electron 包** 用户才会看到。
- Agent 排查「线上缺图 / 接口慢」时，先区分是 **服务端（MinIO/DB/API）** 还是 **客户端（未打包的前端改动）** 问题，避免在服务器上找不存在的前端构建产物。

### Electron 客户端发布流程（自动更新通道）

从 v0.2.261 起，客户端自动更新运行时读取自家源：`/api/public/files/updates/latest.yml`，安装包与 blockmap 存放在 MinIO 的 `updates/` 前缀下。GitHub Releases 仍作为备份发布渠道，但客户端更新检查/下载不再走 GitHub Releases 代理。

发布脚本的资产流向是：本地 `electron-builder` 打包 → 上传 GitHub Releases（备份）→ 云服务器通过 GitHub Release asset API 下载安装包、blockmap、`latest.yml` → `docker cp` 进 `lianyu-minio` → `mc cp` 写入 MinIO bucket `lianyu/updates/`。不要用本地 SFTP/SCP 直接传大安装包到服务器，也不要直接写 MinIO 数据卷。

**发布命令：**
```bash
cd frontend
npm run electron:release           # patch 升级（0.2.256 → 0.2.257）
npm run electron:release:minor     # minor 升级
npm run electron:release:major     # major 升级
```

**发布流程（一条命令全自动）：**
1. `npm version <bump> --no-git-tag-version`（改 package.json 版本号）
2. `vite build` + `esbuild` 主进程 bundle + `electron-builder` 打包
3. 自动上传 `LianYu Setup x.x.x.exe` + `latest.yml` + `.blockmap` 到 GitHub Releases（备份渠道）
4. 云端从 GitHub Release 资产下载 `latest.yml`、安装包、`.blockmap`，同步到 MinIO `updates/`（客户端实际更新源）
5. `latest.yml` 最后写入，避免客户端看到 manifest 时安装包还不存在；同步完成后只保留最近 3 个版本的安装包与 `.blockmap`，删除更旧版本，`latest.yml` 永远保留

**GH_TOKEN 说明：**
- 发布需要 GitHub Personal Access Token（classic，`repo` 权限）
- 发布脚本优先读取当前 shell 的 `GH_TOKEN`；若未设置，会自动回退到 Windows Credential Manager（要求 `git credential.helper=manager` 且 `git credential fill` 能返回 `github.com` 的 `password=`）
- 查看当前机器是否已存 GitHub 凭据：在 PowerShell 执行 ``"protocol=https`nhost=github.com`n" | git credential fill``；若输出含 `password=gho_...`，脚本即可自动取到，无需手动设环境变量
- 如果 credential manager 取不到，再临时设置 `GH_TOKEN` 后重跑发布命令；**不要把 PAT 写进仓库、`.env`、`package.json` 或任何提交文件**
- 仅本地打包（不上传 Release）用 `npm run electron:build`，不检查 GH_TOKEN

**注意事项：**
- 发布前必须先检查当前最新正式 Release 与本地版本号：执行 `gh release list --limit 5` 和读取 `frontend/package.json`，确认下一个版本号高于最新正式包；不要只按本地 `package.json` 猜版本。若存在 Draft Release（如上次失败残留），需先判断是否删除/跳过，最终发布版本必须是最新正式版本的下一个 patch/minor/major。
- 发布前 **先 commit 并 push 代码**（`git commit` → `git push origin main`），确保源码与 Release 对应
- 发布后 package.json 版本号已变更，需再 `git add package.json` 并 commit push
- electron-builder 子进程已配置 `--use-system-ca`，不会因 SSL 证书问题上传失败
- 安装包路径：`frontend/release/v{version}/LianYu Setup {version}.exe`
- Release 地址：`https://github.com/whitequeen306/LianYuPC/releases`

### Git 与云端同步

- **主分支为 `main`**：本地开发、提交、推送均直接在 `main` 上进行（`git push origin main`），不经过 `develop` 分支。
- **前后端源码均 push 到 GitHub**（`frontend/` 与 `backend/` 同在 monorepo）；本地改动完成并验证后，先提交再推送，不要只改本地不打远程。
- **云服务器不承载 Git 开发**，只在 `/opt/lianyu` **`git pull origin main`** 拉取 `main` 分支，再 `docker compose up -d --build backend api-gateway`（自动化见 `scripts/_cloud_deploy_pull.py`）。
- 常规流程：本地改完 → `git commit` → `git push origin main` → 服务器 pull + 重建后端容器；**前端 Electron 安装包在本地打完，不上传服务器**（安装包可另存 release 目录或 GitHub Releases，按需）。
- Agent 执行发布时：**在 `main` 上提交并 push**，再服务器 pull；不要 SCP 源码或 jar 覆盖 `/opt/lianyu`，不要跳过 Git 直接在服务器改代码。

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

**注意**：前端改动需要重新打 Electron 包；后端 `pet-voices.json` 改动需要服务器 `python scripts/_cloud_deploy_pull.py` 重建 backend，否则截图语音不出声。
