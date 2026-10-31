# AGENTS.md — 予念 YuNian（PC 端）协作须知

> 给在本仓库工作的 AI Agent 与人类协作者。**先读这一份，再动手。**
> 本文只写「边界 / 纪律 / 去哪找」，具体实现以代码为准；口径冲突时以本文为准并回来更新本文。

---

## 1. 项目是什么

**予念（YuNian）** 是面向 Windows 的 AI 情感陪伴桌面应用：外表是角色聊天 + 像素桌宠，
内核是一条为「陪伴」定制的 Agent 回合编排（人设 / 记忆 / 关系 / 情绪 / 真实时间 → 模型 → 工具 → 流式回复）。
云端承载对话、记忆、文件与推送；客户端是 Electron 桌面应用。

| 维度 | 事实 |
|---|---|
| 客户端 | Electron + Vue 3 + Vite（`frontend/`），托盘驻留、快捷聊天、桌宠、QQ 桥 |
| 服务端 | Spring Boot 3 多模块（`backend/`，JDK 17），鉴权/事务/落库/通知 |
| 模型运行时 | Python FastAPI + LangGraph（`backend-agent/`），prompt 组装 / 模型调用 / 工具循环 |
| 存储 | MySQL + Redis + RabbitMQ + Milvus + MinIO |
| 部署 | Docker Compose（`:8080` backend、`:18001` agent-lb、`:443/:80` api-gateway） |

---

## 2. 三条运行时边界（最重要的红线）

```
Electron 主进程          服务端 Java                  Python backend-agent
（窗口/录音/播放）    →   （事实 / 鉴权 / 事务）  →   （模型 / prompt / 工具决策）
```

| 层 | 只做 | 绝不做 |
|---|---|---|
| **Java** | 取事实（时间/开关/语言/桥状态）、Sa-Token、事务与落库、MQ、通知、SSE 收尾、加解密 | ❌ 拼 prompt 文案、❌ 调模型、❌ 定义工具 schema |
| **Python** | prompt 组装、模型调用、工具循环与预算、错误码→文案、能力注册表 | ❌ 写 MySQL（只读）、❌ 直接操作桌面 |
| **Electron** | 窗口/托盘/更新、录音推流、TTS 播放、桌宠感知与主动问候 | ❌ 调模型、❌ 操控用户电脑（能力层已于 2026-10-25 删除） |

辅助事实：`backend/lianyu-web/.../JavaAiLogicRatchetTest` + `scripts/check-java-ai-free.ps1`
会**失败**任何长在 Java 里的 AI 逻辑（prompt 文本 / 模型调用点 / 已知 AI 类），基线是 `0/0/0`。
2026-10-26 起还有**规则 5「裸 prompt 长文案」**：≥60 字的字符串或非 SQL 文本块，只要命中
`=== 标题 ===` 段头或 ≥2 个三语 prompt 特征词，就判定为 prompt 文案，必须搬去 Python；
确实要留在 Java 的，登记进 `scripts/java_bare_prompt_allowlist.txt`（**只许变短**，
登记了却没命中也会红）。该清单已于 **2026-10-31 清零**（A/B/C/D 全部迁完或按死代码删除），
现在新长出来的裸文案会直接红；历史台账见 `docs/refactor-2026-10/prompt-migration-inventory.md`。

---

## 3. 开发纪律

### 3.1 改动方式
1. **先读后改**：改任何文件前先读它；不要凭印象重写（本仓库踩过太多次「按记忆改」）。
2. **最小改动**：修 bug 不顺手重构；重构不和功能混在一个提交里。
3. **单一事实来源**：同一个事实只允许有一处定义，别处引用它。
   - 发布资产命名 → `scripts/release_assets.json`
   - 注册表 → `backend-agent/app/tool_registry.py`（**叶子模块**，不许 import 其它 app 内部包）
   - 版本号 → `frontend/package.json`
4. **临时物不进仓库**：一次性排查脚本一律放 `local/` 或带 `_` 前缀且**不提交**；
   `docker-compose.override.yml`、`local/`、`.env` 都在 `.gitignore` 里。

### 3.2 语言与文案
- 用户可见文案（UI、角色台词、错误提示）**中文优先**；i18n 四语齐（`zh / zh-TW / en / ja`），
  新增 key 必须四份一起加，缺一份就视为未完成。
- **prompt / 提示词**只允许出现在 `backend-agent/app/prompts/` 与 Python 代码里；
  改提示词要同步 golden 基线（`tool_hints_golden.json`），跑 `pytest` 锁定。
- 日志与注释写「为什么」，不写「做了什么」的复述；错误码用机器可读短词（如 `window_not_found`），
  **由 Python 翻成角色台词**，前端/Java 不拼人话。

### 3.3 数据与安全
- 数据库结构改动一律走 Flyway：`backend/lianyu-dao/src/main/resources/db/migration/V<n>__*.sql`；
  **迁移文件保持 ASCII-only**（中文注释在 charset 不一致的客户端会炸）。
- 密钥、token、证书只来自 `.env` / vault；**不写进仓库、不打印全文、不进日志**。
  新增环境变量要同步 `.env.example`（只放占位与说明）。
- 出站 HTTP 必须有显式超时、走既有 client 工厂（`SsrfPinningClientFactory` / `app/http_client.py`），
  不许裸 `new URL()` 拼请求；用户可控 URL 必须过既有白名单校验。

### 3.4 契约不许随手改
- **SSE 帧契约**（`content / replace / pieces / error / [DONE] / : keep-alive`）是客户端资产，
  改它要同时改 Java 转发层与渲染端解析，且必须写迁移说明。
- **工具名**受路由校验约束：`^[A-Za-z0-9_-]{1,64}$`（不能用点号）。
  并补测试。
- 角色默认能力是**关**：新增任何自动能力，默认值一律取最保守的一侧。

### 3.5 提交与分支
- 分支：`main` 直线开发；功能改动**先 commit**，再走发版脚本（脚本不夹带业务提交）。
- 提交信息：Conventional Commits + 中文正文，写清「现象 / 根因 / 修法 / 验证」。
- 一个提交只做一件事；删除文件要说明「为什么它已无人使用」。
- 推送前必须跑完 §4 的门禁。

---

## 4. 测试与门禁（提交前全绿）

| 门禁 | 命令（仓库根） | 期望 |
|---|---|---|
| Python | `cd backend-agent && pytest` | 全绿（当前 1222+） |
| Java | `cd backend && mvn -o test` | `BUILD SUCCESS`（service/web/ai/admin 全绿） |
| 前端 | `cd frontend && npx vitest run --no-file-parallelism` | 全绿（当前 488+） |
| 构建 | `cd frontend && npx vite build` | 产出 `dist/` |
| Java 零 AI 逻辑 | `scripts/check-java-ai-free.ps1 -Mode strict` | 通过（`0/0/0` + 裸文案 `0` 未登记） |
| 发布资产契约 | `python scripts/check_release_asset_contract.py` | `json/nginx/client in sync` |

约定：
- 前端测试**串行跑**（`--no-file-parallelism`）：既有 `updater` 并发用例在满载并行下会偶发红，属已知 flake。
- 测试要能「非空转验证」：改了策略/预算就先造一个探针让它失败，再让它通过。
- 不为了让测试过而改断言意图；要改就写清「旧断言为什么失效」。

---

## 5. 文件索引（先看这里，再动手）

### 5.1 顶层
| 路径 | 作用 |
|---|---|
| `README.md` | 产品与链路总览 |
| `DESIGN.md` | 视觉令牌与设计规则（改 UI 前必读） |
| `AGENTS.md` | 本文件 |
| `docker-compose.yml` | 服务编排（**注意**：本地端口覆盖在 gitignored 的 override 里） |
| `.env.example` | 环境变量模板（部署 SSH 口令等） |

### 5.2 客户端 `frontend/`
| 路径 | 作用 |
|---|---|
| `electron/main.js` | 主进程装配：窗口/托盘/IPC/更新器/桥接 |
| `electron/preload.js` | 渲染端可见的 IPC 白名单（新增能力必须在这里显式暴露） |
| `electron/desktopObserver.js` | 桌面感知（定时截图 + 前台窗口 → 主动问候），与已删除的能力层无关 |
| `src/pages/CharacterChatDetailPage.vue` | 角色级设置（免打扰 / 内心独白 / 语音音色等） |
| `src/pages/AboutPage.vue` | 关于页（电脑能力开关已随能力层一起删除） |
| `scripts/electron-pack.mjs` | 打包（产出原生 NSIS + 前端产物） |
| `scripts/prepare-branded-release.mjs` | **把 NSIS 内嵌进 WPF 品牌壳**（丢这一步 = 安装界面变回原生白框） |
| `scripts/electron-release.mjs` | 发版一条龙：bump → pack → branded → GitHub Release → MinIO |
| `installer/LianYu.Installer/` | WPF 安装器工程（`build-offline-installer.ps1` 负责构建） |

### 5.3 服务端 `backend/`
| 路径 | 作用 |
|---|---|
| `lianyu-web/.../agent/` | Java↔Python 适配层（`ChatTurnAdapter`、`AgentRuntimeClient`、`AgentStreamBridge`） |
| `lianyu-service/.../graph/ChatTurnFacade.java` | 回合外层适配器：取事实 → 交给 Python → 回结果（**不在这里拼 prompt**） |
| `lianyu-service/.../tools/` | `ToolManager`（开关事实）、`TimeTool`/`WeatherTool`/…（内置只读工具） |
| `lianyu-service/.../character/CharacterPreferenceResolver.java` | 角色偏好（免打扰 / 内心独白） |
| `lianyu-dao/src/main/resources/db/migration/` | Flyway 迁移（当前最新 `V55`） |
| `lianyu-security/.../SaTokenConfig.java` | 鉴权拦截范围（`/api/**`；`/internal/**` 不在其中） |

### 5.4 模型运行时 `backend-agent/`
| 路径 | 作用 |
|---|---|
| `app/chat_turn.py` | LangGraph 回合（assemble_prompt → assemble_messages → invoke） |
| `app/prompt_pipeline.py` + `app/prompts/` | prompt 拼装与全部文案模板、golden 基线 |
| ~~`app/capability/`~~ | 已于 2026-10-25 **删除**（模型侧下线「角色操控电脑」）。能力层代码不在了，不要按旧文档去改 |
| `app/tools/` + `app/tool_registry.py` | 工具注册表（叶子模块）与内置工具实现 |
| `app/tool_loop.py` / `app/stream_chat.py` | 有界工具循环、SSE 契约实现 |
| `app/gateway.py` / `app/resilience.py` | 模型网关（超时/熔断/隔舱/语言门） |
| `app/vision.py` / `app/desktop_observe.py` | 识图与桌面感知（截图→VLM→角色口吻） |

### 5.5 运维与脚本
| 路径 | 作用 |
|---|---|
| `local/ship-release.ps1` | **一条龙发布部署**（见 §6） |
| `scripts/_cloud_deploy_pull.py` | 云端拉取 + 分层重建 + 健康自检 |
| `scripts/_upload_update_assets.py` | 安装包同步到 MinIO `updates/`（保留最近 3 版） |
| `scripts/release_assets.json` | 更新资产命名唯一真相源（安装器 / 微信渠道） |
| `scripts/check-java-ai-free.ps1` | Java 零 AI 逻辑棘轮 |
| `scripts/java_bare_prompt_allowlist.txt` | 规则 5 的允许清单（裸 prompt 长文案，只许变短） |
| `scripts/check_release_asset_contract.py` | json / nginx / 客户端命名一致性 |
| `deploy/api-gateway/nginx.conf` | 网关路由（含更新资产下发） |

---

## 6. 发布与部署（一条龙脚本 · 脚本部署索引）

**入口**：`local/ship-release.ps1`（本地专用，已被 `.gitignore`；换机需按
`local/README.md` 重建或让 Agent 重新生成）。它串起四段：

| 段 | 实际动作 | 落点 |
|---|---|---|
| 1. push | `git push origin main` | GitHub |
| 2. deploy | `python scripts/_cloud_deploy_pull.py`：云端 `git pull` → 分层 `build` + `up --no-build` → 健康自检 | 生产 Docker |
| 3. electron | `cd frontend; npm run electron:release [minor/major]`：bump → `electron-pack` → **`prepare-branded-release`（WPF 壳）** → GitHub Release → MinIO | GitHub + MinIO |
| 4. 收尾 | `Publish-ReleaseAndFixAssets`（发布 draft、补齐 `.exe`/blockmap）+ 版本号提交推送 | GitHub |

模式（**only 互斥，禁止误触发**）：

| 命令 | push | 云端 deploy | Electron+GH+MinIO |
|---|---|---|---|
| `.\local\ship-release.ps1 -BackendOnly` | ✅ | ✅ | ❌ |
| `.\local\ship-release.ps1 -ElectronOnly` | ✅ | ❌ | ✅ |
| `.\local\ship-release.ps1`（无参数） | ✅ | ✅ | ✅ 仅前后端都改时 |
| 追加 `-SkipPush` / `-SkipDeploy` / `-SkipElectron` | — | — | 按需关闭 |
| `-Bump patch\|minor\|major` | 默认 patch | | |

**发版前必查**：
1. 工作区**已 commit**（脚本只允许未跟踪的垃圾文件存在）；
2. `.env` 有 `DEPLOY_SSH_PASSWORD`（部署用）；
3. `frontend/.env.production.cloud` 的 API/更新地址正确（改了地址必须重打客户端）。

**已知坑（脚本已兜底，别绕过它）**：
- **`docker compose up -d --build <svc>` 在服务器上会挂死**（构建完成但 compose 不退出）：
  必须 `build` 与 `up -d --no-build` 两步走。
- **compose 输出接 PTY 时可能永不返回 EOF**：deploy 段会假死在最后一条 `build` 上（服务器侧其实
  早已完成、镜像也在），表现为「日志停住 5 分钟以上且服务器上没有构建进程」。排查口诀：
  `ps -eo etime,cmd | grep 'docker compose'` 为空 = 命令已结束，别等它；
  `_cloud_deploy_pull.py` 里跑 docker 的命令应加 `timeout` 并改用非 PTY（`get_pty=False`），或显式按 exit code 判定。
- **不要单独跑 `electron-pack.mjs` 就发布**：那样只有原生 NSIS（约 168MB），
  安装界面会退化成白框；品牌 WPF 壳由 `prepare-branded-release.mjs` 生成（约 231MB）。
- 本机打包/发版前要**清掉 `ELECTRON_RUN_AS_NODE`**，并给打包冒烟传
  `SMOKE_ELECTRON_ARGS="--no-sandbox --user-data-dir=<临时目录>"`（脚本已自动处理）。

---

## 7. 排障索引（现象 → 先看哪）

| 现象 | 先看 |
|---|---|
| 流式回复空/串行 | SSE 帧是否被改；`stream_chat.py` 与 `AgentStreamBridge` 是否同步 |
| 安装器界面变原生白框 | 是否走了 `prepare-branded-release`；产物大小是否 ~168MB（异常）/ ~231MB（正常） |
| 更新器不提示新版本 | MinIO `updates/latest.yml` 的 version/sha512；`_upload_update_assets.py` 是否成功；客户端证书指纹 |
| Java 侧棘轮红 | 新代码里是否出现 prompt 文本/模型调用；应搬去 Python。规则 5 报「未登记」就搬走或登记进 `scripts/java_bare_prompt_allowlist.txt`；报「已失效」说明迁完了没删条目 |

---

## 8. 文档索引

| 文档 | 内容 |
|---|---|
| `docs/refactor-2026-10/capability-layer-zh.md` | **历史文档**：2026-10-25 能力层已删除，仅作决策留档 |
| `docs/refactor-2026-10/java-ai-inventory.md` | Java 零 AI 逻辑的现状与三条 seam |
| `docs/refactor-2026-10/langgraph-orchestration.md` | 编排层为什么归 Python、踩过的坑 |
| `docs/refactor-2026-10/off-retirement-plan.md` | OFF/SHADOW 退役与验收方式 |
| `docs/refactor-2026-10/audit-findings.md` | 历史审计（A4 工具循环、A5 630s 阻塞等） |
| `docs/refactor-2026-10/production-rollout.md` | 生产上线流程 |
| `docs/security-coupling-map-zh.md` | 安全耦合面（凭据/出站/审计） |
| `docs/reusable-capabilities/electron-client-hardening-zh.md` | Electron 安全加固与红队验收 |
| `docs/qq-bridge-zh.md` | QQ 桥接（NapCat）设计与运维 |

---

## 9. 一句话自检（提交前）

1. 这段逻辑**放在对的层**了吗？（模型/prompt→Python，事实/事务→Java，本机动作→Electron）
2. 有没有**造出第二个事实来源**？（schema / 命名 / 版本号 / 开关口径）
3. 失败路径**会说话**吗？（错误码→文案；动作失败不静默；危险动作有出口）
4. 门禁跑了吗？（§4 六条）
5. 改动能被一句话说清「为什么」吗？说不清就再想一遍。
