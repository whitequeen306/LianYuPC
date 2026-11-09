# 可维护性审查报告

> 审查对象：`C:/Users/hp/Desktop/LianYu-PC`（予念 YuNian 桌面端 + 云端）
> 审查类型：**全项目**（不是 diff、不是最近提交），1100 个文件 / 135978 行 / 12 个审查单元
> 结论：**12 条发现**（simplify 5 条、cleanup 7 条），无 critical / high。可维护性问题的性质决定了单条很少阻断交付，但它们决定「下一个改这块的人要花多久、会不会改错」，本次集中在三类：**已下线能力留下的伪装**、**复制粘贴型重复**、**层次与体量失控**。
> 全程只读：未修改、未删除、未提交任何源码。

---

## 一、范围与覆盖

12 个单元全部覆盖，没有 skipped（skipped 会把整体状态降为 partial）。u08 为 `reviewed`（关键文件全读并交叉核对），其余按体量 `sampled`，但每个单元都做了跨文件的模式扫描，而不是只看几眼。

| 单元 | 范围 | 状态 | 读文件数 | 做了什么 |
|---|---|---|---|---|
| u01 | backend/lianyu-service (1/3) | sampled | 9 | 读 4 个 scheduler；240 个文件扫长方法 / `@Scheduled` / `@Value` 口径 |
| u02 | backend/lianyu-service (2/3) | sampled | 14 | square 目录 8 个 Catalog + Service 全读，逐角色统计 locale/prompt 方法 |
| u03 | frontend/src (1/2) | sampled | 16 | 构建 298 个 JS/Vue 的 import 图（含 `@/` 别名），据此判定零引用模块 |
| u04 | scripts (1/2) | sampled | 12 | `git ls-files` + 全文引用统计 55 个 `_` 脚本；读部署族的 `load_env`/`run`/超时 |
| u05 | backend/lianyu-dao | sampled | 6 | 57 个迁移按表名反查读写方；52 个 dao java 做死类扫描 |
| u06 | backend-agent | sampled | 11 | 100 个 py 的模块引用 + 测试引用扫描 |
| u07 | frontend/src (2/2) | sampled | 15 | 逐文件核对 import 图；Login/Register 做 difflib 相似度（0.74） |
| u08 | backend/lianyu-web | **reviewed** | 9 | 4 个 Adapter 全读，对照其余 22 个 agent 类逐行确认复制块与参数漂移 |
| u09 | frontend/electron | sampled | 5 | 统计 main.js 的 63 个 `ipcMain.handle` + 8 个 `ipcMain.on` 与 20 个本地 import |
| u10 | 其他模块（root/.github/admin…） | sampled | 11 | admin 依赖全量 grep；读 Encrypted* 与 `.env.example`；admin 未装依赖，仅静态 |
| u11 | backend 小模块合集 | sampled | 6 | 58 个 java 类级引用扫描（仅 1 个零引用，影响极小，未上报） |
| u12 | backend/lianyu-app、lianyu-common、backend | sampled | 9 | installer 5 个 C# 与 lianyu-common 11 个类逐个反查引用，**全部有使用方** |

**测试基线**（本机实跑，用于确认「改完能不能验证」）：

| 命令 | 结果 |
|---|---|
| `npm --prefix frontend test` | 71 test files / 417 tests 全绿 |
| `mvn -q -f backend/pom.xml test` | 退出码 0（日志里大量 WARN 是测试用例故意触发的降级路径） |
| `pytest -q backend-agent` | 系统 python 缺依赖（收集期 48 errors），改用 `backend-agent/.venv/Scripts/python.exe -m pytest` 后全绿 |
| `npm --prefix admin test` | **无法执行**：`admin/node_modules` 不存在，报 `'vitest' 不是内部或外部命令` |

> admin 的结论因此只有静态证据（依赖与源码 grep），已在 findings 的 confidence 上如实标注。

---

## 二、发现

> 编号与 `maintainability.json` 中的 `findings` 顺序一致；为便于按主题阅读，本报告分组展示，因此编号不连续。

### A. 已下线能力留下的「伪装」（cleanup，最该优先处理）

这类问题的共同点不是「多了一段代码」，而是**代码在撒谎**：注释、注解、迁移注释都在描述一个已经不存在的功能，读的人会据此做出错误判断。

**1) 前端三个零引用模块，注释却仍在描述它们生效** — `frontend/src/utils/antiDebug.js:12`（medium / direct）

`export function initAntiDebug()` 全库唯一提及是两处注释：`frontend/src/main.js:38`「全局错误捕获（须在 initAntiDebug 之前注册）」和 `frontend/src/utils/logger.js:4`「生产环境 console.* 被 antiDebug 吞掉」。检索命令与结果写进了 JSON 的 evidence。同批零引用还有 `frontend/src/utils/captureChatShareScreenshot.js:128`（220 行）与 `frontend/src/api/asr.js:4`（12 行）。
影响：排查「线上为什么没有日志」时，注释会把人引向一个不存在的兜底；后端 `AsrController.java:31` 的 `/api/asr/transcribe` 则成了前端已不调用、后端仍在暴露的悬空契约（真实语音走 WebSocket 的 `asr.partial/asr.final`）。
修复：删三个文件 + 改写两处注释；`/api/asr/transcribe` 的去留转交 correctness。

**2) zh-TW 整份 529 行语言包没有接线，文档却写「四语齐」** — `frontend/src/i18n/locales/zh-TW.js:1`（medium / plan）

`frontend/src/i18n/index.js:7-9` 只 import `zh/ja/en`，`messages: { zh, ja, en }`；`frontend/src/constants/language.js:1,3` 的类型与 `SUPPORTED_LANGUAGES` 也只有三种，`normalizeLanguage` 把 zh-TW 归一到 zh。而 AGENTS.md §3.2 明确要求「zh / zh-TW / en / ja 四语齐，新增 key 必须四份一起加」。key 数：zh 378、ja 373、en 377、zh-TW **381**（比 zh 还多 3 个，说明确实被单独维护过）。
影响：381 个繁体 key 永远到不了用户，却要按文档持续维护；文档与代码对「支持什么语言」给出两个答案。
修复：二选一 —— 接线上线，或删文件并把 AGENTS.md 改成三语。这是产品决策，故为 plan。

**6) `profile_context` 只被自己的测试调用，测试名 `matches_java` 已无对端** — `backend-agent/app/memory/profile_context.py:39`（low / plan）

`build_profile_context` 在 `app/` 内零调用，唯一引用是 `backend-agent/tests/test_profile_context.py:13` 与 `:38 test_profile_context_matches_java`；而它 docstring 里「对齐 `MemoryRetriever.retrieveProfileContext`」的 Java 实现，在 backend 全库已 0 命中。
影响：一条保护「Python 与 Java 一致」的 golden 测试，两个对端只剩一个，却依然全绿——它制造的是**虚假的跨端一致性保证**。
修复：删（含 golden）或真正接线并改名。

**7) EncryptedField / EncryptedStringTypeHandler 从未接线，注解在位但字段并未加密** — `backend/lianyu-security/src/main/java/com/lianyu/security/handler/EncryptedStringTypeHandler.java:12`（medium / plan）

全 backend grep 排除这两个文件后零命中：没有 `@TableField(typeHandler = ...)`，也没有字段标注 `@EncryptedField`。Handler 靠 `SpringContextHolder.getBean(JasyptUtil.class)` 取密钥，说明它依赖运行时上下文，误用时会以难排查的方式失败。
影响：这是**安全评审的假线索**——看到注解会假定 vault 密钥等敏感列已落库加密，实际是明文。留着比删掉更危险。
修复：删除（并在 `docs/security-coupling-map-zh.md` 记录「字段级加密未启用」），或选定试点列 + Flyway 迁移 + 集成测试真正接线。

**8) V55 建了 `agent_capability_audit`，写入方 AgentBridgeService 已随能力层删除** — `backend/lianyu-dao/src/main/resources/db/migration/V55__agent_capability_audit.sql:15`（medium / plan）

迁移注释写「written by Java (AgentBridgeService)」「the product surfaces a 'recent actions' panel」，但 `AgentBridgeService` 在 src 下 0 命中（`com.lianyu.service.tools.bridge` 于 2026-10-25 删除，仅 `target/surefire-reports` 有残留），表本身也无任何读写方。
影响：schema 注释描述了一个已不存在的面板，运维会以为有审计数据可查，实际永远是空表；Flyway 历史不可改，越晚处理越只能靠新迁移绕。
修复：新增 `V56__drop_agent_capability_audit.sql`（ASCII-only），不动 V55。

### B. 复制粘贴型重复（simplify）

**5) 部署脚本 16 份 `load_env` 与 49 份 paramiko 样板，超时口径漂移且仍有 `get_pty=True`** — `scripts/_cloud_deploy_backend_layer.py:26`（medium / plan）

`grep -rn "def load_env" scripts` 命中 16 处，其中 14 处逐字相同（每份 9 行）。49 个脚本各自 `import paramiko` 手拼 `exec_command`；默认超时三套：2400（`_cloud_deploy_agent_base_url.py`、`_cloud_deploy_agent_layer.py`）、3600（`scripts/_cloud_deploy_backend_layer.py:26`）、120/300 若干。而 `_cloud_deploy_backend_layer.py:28` 的 `exec_command(..., get_pty=True)` 正是 AGENTS.md §6 记录的「compose 输出接 PTY 会假死」。
影响：同一份「读 .env → 连 SSH → 跑命令」有 16 份副本，改一处连接参数要改 16 处——**实际只改了 1 处**，这就是 PTY 坑还活着的直接原因：入口脚本 `_cloud_deploy_pull.py` 修好了，同族脚本没跟上。
修复：抽 `scripts/_ssh.py`（`run(cmd, timeout, must)`，统一 `get_pty=False`），先迁入口脚本验证行为不变，再迁其余。

**9) lianyu-web agent 层：8 处凭据解析 + 5 份逐字复制的健康缓存，参数已漂移** — `backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentStreamAdapter.java:52`（medium / plan）

「取 vault → 解密 key 还是 apiKey → 空则回落 modelDefault → 判 baseUrl/apiKey 为空」重复至少 8 处（`AgentStreamAdapter:52`、`AgentTextStreamAdapter:49`、`ChatTurnAdapter:135`、`AgentBlockingAdapter:51`、`AgentImageAdapter:160`、`AgentMemoryAdapter:47`、`AgentSessionSummaryAdapter:48`，以及 `DesktopObserveOrchestrator` 一个类里三份）；健康探测在 5 个类逐字复制（`AgentStreamAdapter:101/103/106`、`ChatTurnAdapter:199/201/204` 的注释甚至写着「与 AgentStreamAdapter 一致」）。参数漂移：`AgentStreamAdapter.java:90` 是 0.8 + maxTokens，`AgentTextStreamAdapter.java:87` 是 `0.7, 256`，`ChatTurnAdapter.java:189` 是 0.8/1024。
影响：「同一句话在不同链路用不同 temperature」不是设计，是复制后各改各的产物；排查输出差异时看不出原因。凭据解析 8 份意味着密钥轮换漏一处就静默 fallback 到 Java。
修复：抽 `AgentCredentials` + `AgentHealthProbe` + `AgentModelDefaults`，允许按链路显式 override。

**10) 主动聊天与朋友圈两个定时任务各自复制同一套候选装载 / 概率抽样骨架** — `backend/lianyu-service/src/main/java/com/lianyu/service/moments/MomentsScheduler.java:50`（medium / plan）

`ProactiveChatScheduler.java:63` 与 `MomentsScheduler.java:50` 的 `tick()` 都是 114 行，且逐段重合：`characterIds` → `characterMap` 填充（两文件 `:74-78` 逐字相同）→ `convIds` + `selectLatestUserByConversationIds` 同一段 Collector → `loadUserMessageCounts`（`ProactiveChatScheduler.java:272` 与 `MomentsScheduler.java:153` 各一份，逐字相同）→ `sort(Comparator.comparingDouble(...).reversed())` → quota 循环里 `ThreadLocalRandom...nextDouble() > p` + 只 `log.debug` 的 try/catch。候选 record 也同构（`ScoredCandidate` / `WeightedCandidate`）。配置键还分裂成 `lianyu.chat.proactive.*` 与 `lianyu.moments.*` 两套。
影响：改一处限流口径（比如按用户去重）必须记得改两处；运维在配置里找不到统一开关。
修复：抽 `ConversationCandidateLoader` + `ProbabilityGate.run(...)`，两个调度器只留打分与执行动作。

### C. 依赖、孤儿文件与体量（cleanup / simplify）

**3) admin 有 4 个声明依赖零 import，页面还用硬编码数字冒充真实状态** — `admin/package.json:10`（low / plan）

`echarts`、`@tanstack/vue-table`、`vue-router`、`@element-plus/icons-vue` 在 `admin/src`、`index.html`、`vite.config.*` 里全无命中；`admin/src/App.vue:14` 还写死「最新客户端 v0.2.363」「待处理事项 12」和 3 行假活动记录。
影响：未用依赖放大安装体积与供应链审计面；写死的版本号会随时间失真，让人误判发布状态——这种「假数据占位」比空状态更难被发现。
修复：删依赖（先 grep `import(` 确认没有动态引用），把版本号/待办数改成真实接口，取不到显示「—」。

**4) scripts 下 49 个 `_` 前缀一次性脚本无人引用（3968 行）** — `scripts/_cloud_deploy_backend_layer.py:1`（medium / plan）

55 个 `scripts/_*.py` 里只有 6 个有引用（`_cloud_deploy_pull.py`、`_upload_update_assets.py`、`_cloud_deploy_layered.py`、`_prod_verify.py`、`_audit_installer_unpack.py`、`_upload_wechat_channel.py`），其余 49 个零引用、合计 3968 行。典型：`_cloud_deploy_backend_layer.py` 104 行、`_cloud_deploy_agent_layer.py` 105 行、`_verify_electron_release.py` 253 行、`_diagnose_moments_deep.py` 168 行。这与 AGENTS.md §3.1「一次性排查脚本一律放 local/ 或带 `_` 前缀且不提交」直接冲突，而 §6 又把 `_cloud_deploy_pull.py` 定为唯一入口。
影响：「哪个才是真入口」被 54 个同族脚本淹没，它们都连生产 SSH、口径还不同（见发现 5），误跑一个就可能对生产执行过期逻辑。
修复：保留 6 个，其余移 `local/` 或删除；`scripts/README.md` 写清唯一入口，并加一条「新增 `_*.py` 必须登记」的评审规则。

**11) 角色广场把 4 语人设与整段 prompt 写进 2602 行 Java** — `backend/lianyu-service/src/main/java/com/lianyu/service/square/CharacterSquareCatalogGenshin.java:14`（medium / plan）

square 目录 8 个 Catalog 合计 2602 行（`CharacterSquareCatalog` 462、`Genshin` 590、`Dal` 421、`BlueArchive` 369、`ReZero` 326、`Danganronpa` 227、`Honkai` 61、`Shizhong` 61）。每个角色手写 4 份 locale + 3 个 `promptZhX()/promptJaX()/promptEnX()`；`Genshin.java:14` 的 `register()` 里注册 13 个角色，`:148` 起是逐角色 prompt 方法。prompt 经 `CharacterSquareService.java:378` 的 `.promptTemplate(pack.prompt())` 交给 Python。
影响：AGENTS.md §2 规定 prompt 只允许在 `backend-agent/app/prompts` 与 Python 代码里。这里形成**第二份人设文案来源**，改口径要同时改 Java 常量与 Python 模板，Java 侧还进不了 prompt golden。另外这种「每角色 4 语 × ~20 字段手写」的形态，让漏一个 ja 字段照样编译通过，评审根本发现不了。
修复：导出为 `resources/character-square/*.json`，Java 只做加载 + 校验（缺 locale × 缺字段直接失败），prompt 字段改存 key。

**12) `electron/main.js` 3077 行承载 71 个 IPC 通道** — `frontend/electron/main.js:1`（medium / plan）

63 个 `ipcMain.handle(` + 8 个 `ipcMain.on(`，顶部 import 约 20 个本地模块。同目录已有 64 个 js 模块，但通道注册全挤在这一个文件里。
影响：新增 IPC 要在 3000 行里找位置；AGENTS.md 把 preload 白名单视为安全边界，而边界与处理器分居两处且其一非常巨大，审查质量必然下降。
修复：按域拆 `electron/ipc/*.js`，main.js 只留装配顺序，并加一条「preload 通道 ⊆ main 注册通道」的断言。

## 三、已查未发现问题的区域

- **u12**（`backend/lianyu-app`、`backend/lianyu-common`、`backend/scripts`、`installer`、`Pets`）：installer 的 5 个 C# 文件与 `lianyu-common` 的 11 个类逐个反查引用，全部有使用方；未发现孤儿文件、未用依赖或明显重复。
- **u11**（backend 小模块合集，58 个 java）：类级引用扫描只筛出 `AdminPermissionCatalog`（9 行、零引用），影响过小，列入 deferred 而非上表。
- **u01 的其余部分**：除两个 scheduler 的骨架重复外，读过的 `RelationshipDecayScheduler`、`MomentsCommentReconciliationScheduler` 都是薄壳且注释已说明「与既有一个同构」，属有意为之，未重复上报。
- **u02 的其余部分 / u05 的其余迁移 / u06 的其余 py**：未发现第二份 prompt 事实来源、无主表或死模块。
- **u08**：除 adapter 层重复外，`AgentSseParser` / `AgentStreamBridge` / `AgentWiringHealthCheck` 的分层与职责清晰，未见过度抽象。

---

## 四、未验证与抽样说明（避免误读为「全量已读」）

1. 除 u08 外都是 `sampled`：结论来自「抽样精读 + 全量模式扫描」，**不能**理解为每个文件都逐行读过。上表 `filesRead` 是逐行读过的文件数。
2. 结论强度依赖扫描方式：零引用判定用「全库文件名 + 符号名 grep + import 图」，已覆盖 `@/` 别名、动态 `import()` 与 electron/preload 入口；但仍存在「运行时字符串拼路径」这类无法静态发现的引用，故每条死代码证据都写了检索命令，可复核。
3. `admin` 未能跑测试（缺 `node_modules`），其结论仅静态；`echarts`/`vue-router` 等若存在未纳入 `admin/src` 的引用方式（例如运行时 CDN），需要人工确认后再删。
4. 未做运行时验证：所有发现都是静态证据（grep / 计数 / 相似度），没有启动服务复现行为，也没有 mock 生产数据。
5. 定时器、健康探测等**性能**影响不在本专家判定范围，仅作为可维护性证据提及，不构成性能结论。

---

## 五、建议修复顺序

按「收益 / 风险」排，前两条几乎是零风险，可以先做：

1. **删前端三个零引用模块 + 改注释**（发现 1，direct，跑一遍 `npm --prefix frontend test` + build 即可验证）。顺手把「注释在撒谎」这类问题在全库 grep 一遍：搜 `antiDebug`、`能力层`、`已删除` 等词，看还有多少注释指向不存在的实现。
2. **处理加密 seam 与 V55 空表**（发现 7、8）：一条删代码、一条加迁移，都只动一处，但都在消除「以为已经做了」的错觉，安全与运维收益最高。
3. **收敛 scripts**（发现 4 + 5 一起做）：先删 49 个零引用脚本，再把保留的 6 个抽到 `_ssh.py`，顺手统一 `get_pty=False`。这一步同时消掉 AGENTS.md §6 记录的运维坑。
4. **zh-TW 二选一**（发现 2）：需要产品一句话，代码量小但影响文档口径。
5. **u08 adapter 层收敛**（发现 9）：收益大（8 处 → 1 处），但要动 5 个类的构造函数，建议单独一个提交，只做抽取不改行为，靠现有测试兜底。
6. **两个 scheduler 抽骨架**（发现 10）：与 5 同类，但涉及定时任务，改完要观察一轮线上触发日志。
7. **角色广场迁数据**（发现 11）与 **electron main.js 拆分**（发现 12）：工作量最大、需要设计判断，放最后，各自独立成计划。

**跨专家转交项**

- → **correctness**：`backend/lianyu-web/.../controller/AsrController.java:31` 的 `/api/asr/transcribe` 前端已无调用方，是否下线涉及对外契约；另 `profile_context` 若接线，需要确认记忆上下文的唯一组装点。
- → **reliability**：`scripts/_cloud_deploy_backend_layer.py:28` 的 `get_pty=True` 与超时 2400/3600 漂移，会表现为部署「假死」；两个 scheduler 里只 `log.debug` 的 per-item 兜底也值得看一眼（本次不计入可维护性）。
- → **perf**：Agent 健康探测的 3 秒缓存与定时任务每轮全量扫描候选（`scan-limit:40`），本次仅作为重复证据提及，未做性能结论。
- → **security**：`EncryptedField` 未接线意味着字段级加密缺失，需与 `docs/security-coupling-map-zh.md` 的口径核对。

---

*本报告与 `maintainability.json` 一一对应：12 条发现、simplify 5 / cleanup 7，字段与行号均可按 evidence 中的检索命令复核。*
