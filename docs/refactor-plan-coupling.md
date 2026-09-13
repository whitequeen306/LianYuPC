# LianYu-PC 耦合审查与重构计划

> 性质：计划文档（只读调研产物）。代码与本文冲突时以代码为准；完成后逐项打勾归档，并回写实际结果。
>
> - 审查基线：commit `92e4eb0`，重新核审确认至 `dc80bb3`（核审确认 Java/Vue/脚本零改动，结论有效）。
> - **已在本基线内完成、不列入待办的相关工作**：DESIGN.md 情绪/弹幕/品牌令牌化（`716d0cf`）、ASR int8 模型与内存调整（`6df150f`）、桌宠资产与生成脚本入仓（`1c08aa1`）、AGENTS.md 索引式重构（`9801eeb`）。
> - 审查方式：三路并行只读调研（后端全部 Maven 模块 / frontend 用户客户端 / admin·installer·deploy·scripts·Pets·compose），本仓未开启 `allow-circular-references`，依赖图实测为 DAG。

## 一、总体结论

架构健康度高于平均水平，问题呈**点状**而非系统性腐化：

- 模块依赖方向无环；`lianyu-ai` 零反向依赖；`lianyu-admin` 实测零 `lianyu-service` import；`qq-bridge` 仅 1 个文件接触 service。
- 前端 api 层（17 个按域模块 + `httpCore.js` 集中拦截）与 Pinia store（无循环、最深 2 跳）组织优秀；几乎无绕过 api 层的请求。
- 事务红线（§9）执行到位：未发现 `@Transactional` 内含 AI/MinIO/HTTP 硬违例，多处有自觉的 persistX 拆分。
- docker-compose env 集中、密钥不落仓；无跨模块 DTO 复制。

高耦合集中在**少数上帝类/上帝文件**和**一条跨全仓的隐式命名契约**上。

## 二、重构项总表（19 项）

统一格式：现状证据 → 方案 → 涉及文件 → 风险与验证 → 优先级。
优先级图例：**P0** 现在做 ｜ **P1** 下一批次 ｜ **P2** 之后批次 ｜ **P3** 顺手做 ｜ **HOLD** 只记录不排期。

---

### A. 隐式契约收敛（P0）

#### ☐ 1. 更新资产命名契约 5 处收敛为单一常量源
- **现状**：`LianYu-Setup-x.y.z.exe(.blockmap)` / `WechatChannel-*.zip` / `AgentEngine-*.zip` 的正则与构造散布 5 处：`frontend/scripts/prepare-branded-release.mjs:12`、`local/ship-release.ps1:127-141`、`scripts/_upload_update_assets.py:25,190-198`、`deploy/api-gateway/nginx.conf:115,136,157`、`frontend/electron/wechatBridge/wechatChannelRelease.js:10`。任一处改格式会**静默破坏更新链**。
- **方案**：新增单一常量模块（如 `scripts/release_assets.py` + `frontend/scripts/release-assets.mjs` 共用一个 JSON 定义），nginx 白名单片段由脚本生成或以注释锚定同一来源。
- **风险**：低（纯常量归一）。验证：本地跑一次 `npm run electron:build` + `_upload_update_assets.py --dry-run`；核对生成的 exe/blockmap/latest.yml 命名不变。

#### ☐ 2. ASR base-url 默认值漂移
- **现状**：`backend/lianyu-app/src/main/resources/application.yml:246` 默认 `http://localhost:8081`，compose 实际映射 `127.0.0.1:18081:8080`、容器内为 `asr:8080`，yml 默认值两头都对不上（现靠 compose 覆盖兜底）。
- **方案**：yml 默认值改为容器内真实地址 `http://asr:8080`，或直接去掉默认值强制显式注入。
- **风险**：极低。验证：本地 `docker compose up -d --build backend` 后 health check + 一次 ASR 调用。

#### ☐ 3. nginx `/ops` 反代硬编码 docker 网桥 IP 与端口
- **现状**（`dc80bb3` 新增）：`deploy/api-gateway/nginx.conf` 的 `/ops` 与 `/static` location 硬编码 `proxy_pass http://172.18.0.1:8090`，与项目「env 集中于 `.env`」的惯例不一致；且 ops dashboard 本体不在本仓库，为仓库外隐式依赖。
- **方案**：端口/IP 改为 `.env` 变量（`OPS_DASHBOARD_UPSTREAM`）注入 nginx（envsubst 或 include 片段）；在 AGENTS.md 或 `local/README.md` 登记 ops dashboard 的仓库外依赖。
- **风险**：低。验证：云端部署后 curl `/ops` 返回 200。

---

### B. 后端上帝类（P1）

#### ☐ 4. 拆分 `AiChatService`（2059 行 / 约 92 方法 / 19 注入者）
- **现状**：`backend/lianyu-service/src/main/java/com/lianyu/service/ai/AiChatService.java` 同时承担：模型网关调用、Resilience4j bulkhead/timeLimiter/circuitBreaker 配置持有、流式 SSE 消费、视觉多模态解析（VisionAnalysisParser）、工具调用（ToolManager）、失败重试。
- **方案**：对外接口（`chatBlocking` / 流式入口）不变，内部拆为四个协作组件：`AiResilienceRegistry`（熔断/bulkhead 配置）、`ModelGateway`（出站调用，遵守 §10 只用 `SsrfPinningClientFactory`）、`VisionAnalysisParser`、`AiToolCallCoordinator`。调用方零改动。
- **风险**：中（核心链路）。验证：重编镜像 + 全链路手测（单聊流式、群聊、语音、视觉消息）；`UpstreamCircuitBreakerFactory` 行为不变。
- **红线检查**：拆分后慢调用仍留在非事务层（§9）；HTTP 仍走 `SsrfPinningClientFactory`（§10）。

#### ☐ 5. 瘦身 `ConversationService`（1468 行 / 27 个 `private final` 依赖 / 10+ 注入者）
- **现状**：`backend/lianyu-service/.../conversation/ConversationService.java` 混合单聊、SSE 流、群聊支持、主动消息、冷开场、语音目录解析、通知、记忆写入；并被 `lianyu-qq-bridge` 直连（内部结构已固化为对外契约）。
- **方案**：先拆低风险件——`ProactiveMessageService`（`sendProactiveMessage`/`sendColdOpenFirstLine` + 现有 persistX 小事务）、`PetVoiceResolver`（PetMeetVoiceCatalog/PetVoiceRegistry 解析）；为 qq-bridge 保留一个稳定门面接口（`ConversationFacade` 或直接保留现有签名）。
- **风险**：中。验证：镜像重建后手测单聊/群聊/主动消息/qq-bridge 消息回路。
- **红线检查**：拆出的类维持「慢调用在事务外」的既有拆分模式。

#### ☐ 6. lianyu-admin 依赖清理
- **现状**：pom 声明 `lianyu-dao` 但模块内零 `com.lianyu.dao` import（持久化全走 JdbcTemplate）——冗余依赖；依赖整个 `lianyu-ai` 仅为用 `SsrfPinningClientFactory`（`GitHubReleaseImporter.java:3`）。
- **方案**：删除 dao 依赖；将 `SsrfPinningClientFactory` 下沉到 `lianyu-common`（或 `lianyu-security`），admin 改依赖下沉位置。
- **风险**：低（下沉是搬移，注意全仓 import 更新）。验证：`mvn -q compile` 全模块 + admin 模块测试。

#### ☐ 7. URL 规范化等重复逻辑下沉
- **现状**：`normalizeBaseUrl` 在 `ApiKeyVaultService` / `CustomVoiceService` / `DashScopeCloudEndpointValidator` 三处各自实现；`trimToNull` 两处重复；`lianyu-common` 的 util 未被复用。
- **方案**：下沉为 `lianyu-common` 的 `Urls.normalizeBaseUrl()` / `Strings.trimToNull()`，三处改引用。
- **风险**：极低。验证：单元测试 + 编译。

---

### C. 前端巨型文件（P2）

#### ☐ 8. Electron `main.js` 拆分（3313 行 / 81 处 `ipcMain.handle` / 105 个通道名字符串散落 preload）
- **现状**：子域模块（wechatBridge/qqBridge/mcp/napcatRuntime/desktopSettings）内聚好、互不 import IPC，但全部 IPC 注册压在 `main.js`；`startupOrchestrator.js` 仅 27 行，启动编排实际都在 main.js。
- **方案**：① 新增 `electron/ipc/channels.js` 通道名常量表，preload 与 handler 共用；② 按域把 handler 下放到各子域模块（wechat/qq/mcp/napcat/desktop/agent），main.js 只保留注册装配；③ 启动编排逻辑移入 `startupOrchestrator`。
- **风险**：低-中（机械搬移，但通道多）。验证：`npm run electron:build` + 手测桌宠切换、微信桥、QQ 桥、Agent 工具桥、launcher 双入口。

#### ☐ 9. `ChatPage.vue` 拆 composables（2433 行 / 注入 7 store + 6 api 模块）
- **现状**：`frontend/src/pages/ChatPage.vue` 为消息流 + 输入区 + 语音 + Agent 桥一体的上帝组件。
- **方案**：按项目既有 23 个 composables 惯例，抽 `useMessageStream` / `useChatInput` / `useVoiceMessage` / `useAgentTools` 四个 composable，页面只留装配。
- **风险**：中（最活跃页面）。验证：Vitest + 手测发送/流式渲染/语音/中断；挑功能间歇期做。

#### ☐ 10. 其余 >1000 行页面（只登记，不排期）
`GroupChatPage.vue` 1500 ｜ `LandingPage.vue` 1366 ｜ `QqBridgePage.vue` 1187 ｜ `CharactersPage.vue` 1186 ｜ `CharacterSquarePage.vue` 1085 ｜ `SettingsPage.vue` 1018 ｜ `MomentsPage.vue` 961 ｜ `CharacterChatDetailPage.vue` 959。
触发条件：某页面需要大改功能时顺带拆分，不为拆而拆。

---

### D. 分层与规范顺手项（P3）

#### ☐ 11. 越层 Controller 下沉 service
- `backend/lianyu-web/.../MemoryController.java`：直接注入 4 个 Mapper（MemoryMeta/Message/Character/Conversation）自行拼查询与组装 → 下沉 `MemoryQueryService`。
- `backend/lianyu-web/.../CharacterStateController.java`：注入 `CharacterMapper` 做 5 处业务查询（:47,:64,:99,:130,:157）→ 下沉 service。
- 验证：接口响应 diff 对比重构前后一致。

#### ☐ 12. `CommunityService.toggleLike` 事务内发通知
- **现状**：`:166 @Transactional`，`:185` 调 `notificationService.notifyCommunityLike`（内部 STOMP + RabbitMQ），拉长事务窗口。
- **方案**：通知移到事务外（persist 模式或 `TransactionSynchronization.afterCommit`）。

#### ☐ 13. `ApiKeyVaultService.create` 事务结构加固
- **现状**：`:36 @Transactional` 内 `validateVaultEndpoint`（`:351`）目前是本地校验、安全，但结构上易被未来塞入端点探活 HTTP（违反 §9）。
- **方案**：加显式注释声明该事务内禁止慢调用，或把校验提前到事务外。

#### ☐ 14. 令牌化残余：硬编码 hex 收敛（令牌化已做，此为残余）
- **现状**：`716d0cf` 已完成 emotion/danmaku/brand 令牌化；当前残余约 72 处：`pages/LandingPage.vue` 34、`styles/auth-page.scss` 23、landing 组件 8、`app-shell.scss` 3、`ThemeColorPicker.vue` 3（取色器可谅解）、`QuickChatPage.vue` 1。
- **方案**：landing 系与 auth-page 的 hex 映射进 DESIGN.md token（如需新增暗色层级令牌先扩 DESIGN.md，遵守 §1 规则 8）。
- 另：8 个页面 i18n 缺失（0 处翻译调用、硬编码中文），补 `useI18n`。

#### ☐ 15. 文档与配置同步
- AGENTS.md §6 中 `ChatTurnFacade`「Graph 外层入口属 lianyu-ai」的描述与实际（Facade 及图配置在 `lianyu-service/.../graph/`，lianyu-ai 只剩 Keys/State/Scene 契约）漂移 → 改 AGENTS.md 一句话。
- `installer/build-offline-installer.ps1:2` 默认参数 `$Version="0.2.363"` 双写（已由显式 `-Version` 覆盖，仅删默认值）；`pack-wechat-channel.mjs:25` 与 `_upload_wechat_channel.py` 的 `0.1.0` 双写同理收敛。

---

### E. 只记录、不排期（HOLD）

#### ☐ 16. `GroupChatService`（1333 行）拆分
轮次推进 + 并行生成 + 落库推送混合。轮次逻辑刚稳定，拆分收益/风险比一般。备用方向：`GroupTurnScheduler`（轮次/中断）+ `GroupReplyPersister`（并行落库）。触发条件：群聊功能大改时顺带。

#### ☐ 17. 桌宠多点注册收敛
新增角色需同步四处（`public/pet` 资源、`petCatalog.js`、`desktopSettings.js:ALLOWED_PET_IDS`、`pet-voices.json`），SKILL.md:385 记录过静默失败。增强方案：`ALLOWED_PET_IDS` 由 petCatalog 派生 + 资源完整性校验脚本。触发条件：下一个新桌宠立项时做。

#### ☐ 18. 一次性/脆弱脚本项
- `admin/electron/main.js:8` 跨项目引用 `../../frontend/build/icon.ico`。
- `local/resume-electron-release.mjs:28` 硬编码历史版本 `0.2.351` / AgentEngine `0.1.1` 与仓库外路径 `../AgentAssistant/packaging/`。
- `ship-release.ps1:139-141` blockmap「空格→连字符」修复补丁（electron-builder 历史行为差异的技术债）。

#### ☐ 19. nginx 按文件名正则白名单的长期演进
`LianYu-Setup-*.exe` / `AgentEngine-*.zip` / `WechatChannel-*.zip` 三组正则使「新增一种更新资产类型」必须改 nginx。与第 1 项合并治理：常量归一后，评估改为目录级白名单（`/updates/` 整目录放行）。

---

## 三、执行顺序与节奏

| 批次 | 内容 | 验证手段 |
|---|---|---|
| 批次 1（P0） | 项 1、2、3 | `electron:build` + 上传 dry-run；`docker compose up -d --build backend` + health；云端 curl `/ops` |
| 批次 2（P1 后端） | 项 4 → 5 → 6 → 7（4、5 分开提交） | 镜像重建 + 单聊/群聊/语音/qq-bridge 全链路手测 |
| 批次 3（P2 前端） | 项 8 → 9 | Vitest + `electron:build` + 手测各 IPC 域 |
| 批次 4（P3 顺手） | 项 11–15 | 接口 diff 对比 / 编译 / lint |

**纪律检查点（每批次提交前）**：
- §9 事务红线：慢调用不进 `@Transactional`；
- §10 出站 HTTP 红线：只用 `SsrfPinningClientFactory`；
- §1 DESIGN.md：颜色/圆角/缓动只用令牌，扩展先加令牌；
- §12：`pom.xml` / `package.json` 变更后跑 OSV-Scanner；
- 涉及发版链路的批次完成后走 `.\local\ship-release.ps1` 对应参数（后端项 `-BackendOnly`，前端项 `-ElectronOnly`）。

## 四、归档记录

每项完成后在此回写：`[x] 项 N — 完成于 <commit>，实际改动与计划差异：<无/说明>`。

### 已完成（2026-09-13）

- [x] **项 1** — `82d2af1`。新增 `scripts/release_assets.json` 唯一真相源 + `scripts/check_release_asset_contract.py` 校验脚本；`_upload_update_assets.py` / `_upload_wechat_channel.py` / `prepare-branded-release.mjs` / `pack-wechat-channel.mjs` / `wechatChannelRelease.js` / `local/ship-release.ps1` 全部改为从 JSON 派生；nginx 加注释锚。差异：无。
- [x] **项 2** — `82d2af1`。yml 默认值改为 `http://asr:8080`。差异：无。
- [x] **项 3** — `82d2af1`。`OPS_DASHBOARD_UPSTREAM` 经 compose → entrypoint sed 注入 nginx 占位符，`.env.example` 登记。差异：无。
- [x] **项 4** — `6d8962d`。AiChatService 2059 → 1050 行，拆出 ChatModelFactory / AiResilience / SseChatStreamHelper / VisionMessageBuilder / ModelCatalogService / VisionChatService；对外公共 API 不变，vision/模型目录调用方直接改指新服务。差异：语言门保留在 AiChatService（依赖 buildPrompt，避免环）；service 模块 255 测试全过。
- [x] **项 5** — `2c4d0d0`。主动消息/破冰/固定语音/城市变更（约 600 行）拆出 ProactiveMessageService；ConversationService 1468 → 814 行；顺带删除死依赖 proactiveRealWorldContext 字段。差异：**发现并修复既有隐患**——persistX 原为同类内自调用，`@Transactional` 实际未生效；现经 `@Lazy` 自代理调用，事务真正生效。
- [x] **项 6** — `0a59c76`。删除 lianyu-admin 冗余 lianyu-dao 依赖（OSV 扫描 0 漏洞）。差异：**SsrfPinningClientFactory 不下沉**——其依赖 netty/okhttp/reactor-netty/spring-web 全栈，下沉会把 web 栈拖进基础模块，维持原位并在代码注释/本记录说明。
- [x] **项 7** — `0a59c76`。`TextUtils.trimToNull` 下沉 common，替换两处私有副本。差异：normalizeBaseUrl 实际两处语义不同（DashScope 白名单版 vs OutboundUrlValidator 版），非重复，不合并。
- [x] **项 11** — `9a33fcd`。MemoryController / CharacterStateController 瘦身为协议壳，查询组装下沉 MemoryQueryService / CharacterStateQueryService。差异：无。
- [x] **项 12** — `9a33fcd`。toggleLike 的点赞通知经 `TransactionSynchronization.afterCommit` 发送。差异：无。
- [x] **项 13** — `9a33fcd`。ApiKeyVaultService.create 加事务红线注释。差异：无。
- [x] **项 15** — `a804738`。installer ps1 的 -Version 改必填（单一来源 frontend/package.json）。差异：AGENTS.md 的 ChatTurnFacade 描述已在 9801eeb 修正，无需再改；pack-wechat-channel / _upload_wechat_channel 的 `0.1.0` 各自是工具默认值（非同一来源双写），保留。

### 后端验证记录

- `mvn -pl lianyu-service,lianyu-web,lianyu-qq-bridge,lianyu-app -am test`：全绿（service 255 / web 10 / qq-bridge 相关用例 0 失败）。
- `docker compose up -d --build backend api-gateway`：backend `{"status":"UP"}`；api-gateway 稳定运行，`/ops` 反代链路生效（本机无 8090 上游故 502，属预期）。
- 过程修复：gateway 两个 sh 文件 CRLF→LF（CRLF 使容器内 exec 失败）；VisionAnalysisParser 补 `@Component`。

### 待办（P2 批次 3 / P3 余项）

- [ ] **项 8**：Electron main.js IPC 拆分。现状 81 个 handler 全部闭包在 `registerIpcHandlers` 内，依赖上方几十个窗口/协调器状态，需逐个线程化依赖后下放各域模块 + 建 `electron/ipcChannels.js` 通道常量表；完成后须 `npm run electron:build` + 桌宠/微信/QQ/Agent/launcher 逐域手测。
- [ ] **项 9**：ChatPage.vue 拆 composables（按功能间歇期执行）。
- [ ] **项 14**：landing 系/auth-page 约 72 处硬编码 hex 令牌化（landing 暗色层级可能需先扩 DESIGN.md）；8 个页面补 i18n。
