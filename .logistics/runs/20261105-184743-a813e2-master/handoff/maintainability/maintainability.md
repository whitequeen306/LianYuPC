# 可维护性审查报告

审查对象：`C:/Users/hp/Desktop/LianYu-PC`（予念 YuNian PC），运行 `20261105-184743-a813e2-master`。
审查口径：整仓审查（非 diff、非最近提交），以 `scope.json` 的 `project.units` 为准；只负责可维护性，仅输出 `simplify` 与 `cleanup` 两个 section。

## 范围与覆盖

| 维度 | 数值 |
|---|---|
| 受审文件 | 1100 个（已跟踪 1615，按 scope 省略 515） |
| 代码行数 | 135978 行 |
| 审查单元 | 12 个 |
| 核心文件 | 144 个（每单元 12 个），**全部读过，缺失 0 个** |
| 实际列出的已读文件 | 384 个（每单元 32 个，含该单元全部 core） |
| 跨文件扫描 | `grep`/`rg` 模式扫描：脚本重复、i18n key 差集、非 ASCII 迁移、SSE 解析实现、依赖引用、测试中的 `.vue` 源码断言 |

单元状态说明：**reviewed** = 读过该单元全部核心文件 + 主要相邻实现，并做了针对性检查；**sampled** = 因单元体量大（95–120 个文件）按核心优先抽读 32 个，并配合全单元的 `rg` 模式扫描。两者都不是全文逐行通读。

| 单元 | 范围 | 状态 | 已读/总量 | 理由 |
|---|---|---|---|---|
| u01 | backend/lianyu-service (1/3) | sampled | 32/120 | 通读 `agent/` 15 个 Port 接口与 `ai/` 服务层；核实 15 个 `*Port` 为 AGENTS.md 记录的 Java↔Python seam，不作为过度抽象上报 |
| u02 | backend/lianyu-service (2/3) | sampled | 32/120 | 通读 `dto/*`、`graph/*`（ChatTurnFacade 322 行）、`memory/*`；发现角色广场 prompt 双写 |
| u03 | frontend/src (1/2) | sampled | 32/120 | 通读 `api/*`（httpCore 148 行）、`auth/*`、`components/*`；另读 `package.json`、`i18n/index.js`、`constants/language.js`、`shared/sseParser.js` 佐证 |
| u04 | scripts (1/2) | sampled | 32/120 | 通读 12 个 core 与 20 个 `_cloud_*`；对全目录做重复/硬编码统计 |
| u05 | backend/lianyu-dao | sampled | 32/110 | 通读 pom、V1–V19 迁移、`MyBatisPlusConfig`、`dto/*Row`、`entity/*` |
| u06 | backend-agent | sampled | 32/102 | 通读 config/main(1279)/gateway(504)/chat_turn(395)/tts(372)/resilience(362)/stream_chat(295) 与两个大测试 |
| u07 | frontend/src (2/2) | sampled | 32/95 | 通读 router、stores、9 个大页面（ChatPage 2266 行等）与 `pages/__tests__` |
| u08 | backend/lianyu-web | reviewed | 32/71 | 全量覆盖 11 个 controller 与 `agent/` 13 个适配类，含 `AgentSseParser` 全文 |
| u09 | frontend/electron | reviewed | 32/68 | 覆盖 `main.js`(3077)、qqBridge、updater、wechatBridge、napcatRuntime 与 11 个测试 |
| u10 | 其他模块（root/.github/admin） | reviewed | 32/67 | 覆盖 `.env.example`、admin 全量、lianyu-ai/lianyu-security 小模块、`ci.yml` 全文 |
| u11 | backend 小模块合集 | reviewed | 32/59 | 覆盖 lianyu-admin 全部源码与 11 个 lianyu-service 测试类 |
| u12 | 其他模块（app/common/installer） | reviewed | 32/48 | 覆盖 lianyu-app/lianyu-common、installer 三个 C# 文件、`backend/scripts` 6 个一次性脚本 |

**已查未发现问题的区域**：

- u01 的 15 个 `*Port.java` 全部是 1 个实现 + 3–11 处引用，属设计中的 seam，不是过度抽象（已查未发现问题）。
- 前端无孤儿组件：30 个 `.vue` 全部被 `import`（已查未发现问题）。
- `frontend/src/api/index.js` 的单行 `re-export` 与 `api/*.js` 的薄封装是刻意分层（已查未发现问题）。
- `LegacyCommunityController.java` 有文档化的删除条件，按 Chesterton's fence 不报（已查未发现问题）。
- `backend-agent/app/tools/registry.py` 兼容垫片仍被 4 个模块 import，是活代码（已查未发现问题）。
- u11 的 11 个测试类命名与断言意图清晰，无空转断言（已查未发现问题）。
- 全仓仅 3 个 0 字节已跟踪文件，其中 `backend-agent/app/prompts/__init__.py` 是合法包标记（其余两个见 M-07）。

## 发现

共 11 条：medium 6 条、low 5 条；无 critical/high（可维护性问题的上限按 medium 记）。kind：plan 8 条、direct 3 条。

- [medium] [u09] [plan] M-01 — 桌面端主进程把所有窗口、托盘、更新、桥接塞进一个 3077 行文件，且没有任何自动化测试碰到它
详细问题：Electron 主进程 main.js 是 3077 行的上帝模块，71 个 IPC 注册点且没有任何测试
位置：frontend/electron/main.js:1 · 证据：3077 行、111 个函数、71 处 `ipcMain.handle/ipcMain.on`，一次性 import electron 的 20+ 能力；`frontend/electron/__tests__` 下 11 个测试只覆盖被抽出的叶子模块（launcherBounds/startupOrchestrator/startupProfiler/updater），无一处 import `main.js`
影响：任何 IPC 改动都可能影响其它入口，且无回归证据；新人上手成本最高 · 修复：按能力拆成 `registerXxx(ctx)` 模块，main.js 只留生命周期，并用 fake `ipcMain` 断言通道集合（详见 JSON `fix.steps`）

- [medium] [u03] [plan] M-02 — “支持哪些语言”被四处各写一遍且互不一致：繁体中文包 529 行从没被加载，英日界面有 6 处会显示裸 key
详细问题：语言清单存在 4 份互相不一致的定义，zh-TW 语言包 529 行无人引用，中英日三语缺 6 个仍在使用的 key
位置：frontend/src/constants/language.js:3 · 证据：`OutputLanguage.java:6-9`（zh/zh-TW/ja/en）、`language_gate.py:22`（同 4 语）、`constants/language.js:3`（zh/ja/en）、`i18n/index.js:16-17`（messages 仅 zh/ja/en）；`locales/zh-TW.js` 529 行 0 import；key 数 zh 583 / zh-TW 588 / en 582 / ja 575；en+ja 均缺 `chat.scrollToBottom`（ChatPage.vue:173、GroupChatPage.vue:131 在用）等 6 个 key；`memory.*` 11 个 key 只在 zh-TW；`characterSquare.addDialogTitle` 在 zh-TW 缺失（CharacterSquarePage.vue:223 在用）
影响：切英文/日文的用户会看到 key 名而不是人话，记忆页整块文案缺失；“四语齐”约定事实上已失守 · 修复：先决策 zh-TW 上线或删除，再以 zh 为基准补齐 en/ja，补 key 集合一致性与 `t('...')` 存在性测试

- [medium] [u02] [plan] M-03 — 角色设定文案在 Java 和数据库各存一份，数据库那份永远不会被读取
详细问题：角色广场的 prompt 文案在 Java 与数据库里各存一份，数据库那份永远不会被读取
位置：backend/lianyu-service/src/main/java/com/lianyu/service/square/CharacterSquareCatalog.java:219 · 证据：`String resolvedPrompt = promptPack != null ? promptPack.prompt() : template.getPromptTemplate();`；`CharacterSquareService.java:145` 先以 `isKnownSlug` 过滤，回退分支不可达；18 个迁移文件写 `prompt_template`、共 55 条 `INSERT INTO character_square_template`；对应文案同时存在于 `CharacterSquareCatalog*` Java 类，与 AGENTS.md §3.2「prompt 只在 backend-agent/app/prompts/」冲突
影响：改设定要改两处而只有一处生效，按 DB 改文案会白改且无测试能发现 · 修复：以 prompt pack 为唯一真值，回退分支改为显式报错，新迁移把该列置 NULL，再评估把文案迁到 Python prompts

- [medium] [u08] [plan] M-04 — 流式回复的帧解析写了三份，前端有个测试测的是文件里抄的副本
详细问题：SSE 帧解析有三份实现，前端单元测试测的是文件内自造的副本而不是生产代码
位置：frontend/src/utils/__tests__/sseParser.test.js:4 · 证据：测试文件内自定义 `parseSseDataLine` 并只测它；生产实现在 `frontend/shared/sseParser.js`（107 行，导出 `parseSseDataPayload:31`/`parseSseStream:57`/`createSseAccumulator:81`），已被 `assistantStreamDrain.js:1`、`qqBridge.js:17`、`wechatBridge.js:5` 引用且已有 `shared/__tests__`；Java 侧第三份 `AgentSseParser.java`（160 行）；帧的生产方是 `stream_chat.py`
影响：§3.4 要求帧契约三端同步，但没有任何测试从帧样本校验两端一致；副本测试给出虚假安全感 · 修复：测试改为 import 共享模块，并建立 golden 帧样本同时约束前端/Java/Python

- [medium] [u04] [plan] M-07 — scripts/ 沦为一次性脚本垃圾场：97 个 `_` 前缀脚本、7 个部署变体、4 组重复脚本、109 处硬编码生产主机
详细问题：scripts/ 里 97 个下划线开头的一次性脚本、7 个部署变体、4 组重复脚本和 2 个 0 字节文件长期留在版本库里
位置：scripts/_cloud_deploy_pull.py:1 · 证据：`git ls-files scripts` 132 个，`_` 前缀 97 个（73%），另有 `backend/scripts` 10 个、`frontend/scripts` 13 个；部署变体 `_cloud_deploy_{agent_base_url,agent_layer,backend_layer,backend_only,layered,prep,pull}.py` 共 7 个；`scripts/` 内 81 份 `def load_dotenv`、16 份 `def load_env`、105 处 paramiko `SSHClient`、109 处硬编码 `156.233.228.18`；`scripts/enroll_pet_voices.py` 与 `Pets/skills/hatch-lianyu-pet/scripts/enroll_pet_voices.py` md5 相同，`gen_elysia_openai.py`(331) 与 `Pets/.../gen_pet_images.py`(355) 14 个同名函数；7 处作者本机绝对路径（`scripts/gen_elysia_dashscope.py:111`、`scripts/gen_elysia_openai.py:255`、`scripts/replay_catalog.py:13`、`scripts/_check_yml.py:6`、`backend/scripts/{dedupe-java-imports,fix-service-cross-imports,repackage-service}.ps1`）；0 字节已跟踪文件 `frontend/scripts/_run-release.ps1`、`backend/scripts/_vault_export.tsv`
影响：新人不知道跑哪个；改一次部署要同步改 7 份；硬编码主机与本机路径让脚本在别人机器上必然失败 · 修复：收敛为 `scripts/ops/cloud_deploy.py --layer`，抽 `_ssh.py` 公共层，一次性脚本移入 `local/`（gitignore），清重复与 0 字节文件

- [medium] [u10] [direct] M-08 — CI 只跑 backend 与 frontend，Python 与 admin 从来没被门禁住，前端跑法还与文档冲突
详细问题：CI 只跑 backend 与 frontend，没有 pytest 与 admin job，前端还用了与 AGENTS.md 冲突的并行跑法
位置：.github/workflows/ci.yml:39 · 证据：`ci.yml` 55 行只有 backend（mvn test）、frontend（第 39 行 `run: npx vitest run`）、deps-audit(OSV)；无 `backend-agent` pytest job、无 admin job；AGENTS.md §4 要求 pytest 全绿且前端必须 `--no-file-parallelism`（文档记录 updater 用例满载并行会偶发红）
影响：700+ Python 用例与 admin 后台零自动化回归；已知 flake 在 CI 上随机变红会训练团队忽略红灯 · 修复：前端加 `--no-file-parallelism`，新增 backend-agent 与 admin 两个 job（工作目录与可导入路径显式设置）

- [low] [u09] [plan] M-05 — 启动耗时统计有两份实现，时间基准不同导致两边数字不可比
详细问题：startupProfiler 在 electron 与 src 下各有一份实现，时间基准不同
位置：frontend/electron/startupProfiler.js:1 · 证据：electron 版 `now = () => Date.now()`；`frontend/src/utils/startupProfiler.js:2-8` 用 `performance.now()` 兜底 `Date.now()` 并 `Math.round`；两份各有测试
影响：同名指标不可横向比较，同一修复要改两遍 · 修复：抽 `frontend/shared/startupProfiler.js`，时钟由调用方注入并在日志字段标注 base

- [low] [u07] [plan] M-06 — 三个前端测试把 .vue 源码当文本断言，测的是排版不是行为
详细问题：三个前端测试用 readFileSync 读 .vue 源码做字符串断言，测的是排版不是行为
位置：frontend/src/pages/__tests__/settingsLayout.test.js:1 · 证据：`settingsLayout.test.js`、`wechatBridgePage.test.js`、`momentsCommentThread.test.js` 均 `readFileSync(resolve(currentDir, '../XxxPage.vue'))` 后断言源码；同目录 `charactersRealtime.test.js` 只测导出纯函数，是更可持续的写法
影响：无害重构即变红，降低红灯可信度，也让人不敢重排模板 · 修复：把可判定部分抽成纯函数（或导出 `SECTION_ORDER` 常量）后断言，删掉 fs 读取

- [low] [u03] [direct] M-09 — frontend 的 devDependencies 里有两个全仓零引用的包
详细问题：frontend 的 devDependencies 里挂着两个全仓零引用的包
位置：frontend/package.json:37 · 证据：`:37` `@vue/test-utils`、`:44` `vite-plugin-electron-renderer`；在 `src`/`electron`/`shared`/`scripts` 与各 vite 配置中均无引用（测试全部直接测模块，不做组件挂载）
影响：安装体积与审计面白增，并误导后来者以为有组件测试设施 · 修复：删除两个 devDependency 并同步 lockfile，跑 test 与 build 验证

- [low] [u05] [plan] M-10 — 57 个 Flyway 迁移里 33 个含非 ASCII，与 AGENTS.md 的 ASCII-only 约定相反
详细问题：57 个 Flyway 迁移里有 33 个含非 ASCII 字符，与 AGENTS.md 的 ASCII-only 约定相反
位置：backend/lianyu-dao/src/main/resources/db/migration:1 · 证据：该目录 57 个 `.sql`，`grep -lP '[^\x00-\x7F]'` 命中 33 个（V1、V10–V29 中大部分，含中文注释与中文 INSERT 值）；AGENTS.md §3.3 明确要求迁移保持 ASCII-only
影响：charset 不一致的客户端/容器可能写入乱码或校验失败；已应用迁移不能改（会破 Flyway checksum），只能靠新文件守住 · 修复：加 `check_migration_ascii.py` 门禁 + 存量基线清单，只对新迁移报错，并接入 CI 与门禁表

- [low] [u10] [direct] M-11 — admin 后台 6 个源文件被压成单行，diff 永远是整行替换
详细问题：admin 后台的 6 个源文件被压成单行/少行，编辑器与 code review 都无法阅读
位置：admin/src/styles.css:1 · 证据：`styles.css` 1 行 5759 字符；`management.css` 2 行（1057）；`App.vue` 15 行但单行 2178 字符；`ManagementPage.vue` 15 行（1058）；`stores/session.js` 2 行（635）；`components/DataTable.vue` 5 行（667）
影响：diff 无法评审、无法 blame 到具体改动、编辑器无法跳转 · 修复：`npx prettier --write "src/**/*.{vue,css,js}"`，确认 `git diff -w` 为空后单独成一个纯格式化提交

## 耦合度

- **跨语言契约靠“同一条事实多处抄写”维持**：语言清单 4 份（M-02）、SSE 帧解析 3 份（M-04）、启动耗时 2 份（M-05）、角色 prompt 2 份（M-03）。这类耦合的危险不在于重复，而在于**改一处不报错**——没有任何测试把两端绑在一起。
- **单文件承载过多关注点**：`frontend/electron/main.js`（3077 行、71 个 IPC）、`ChatPage.vue`（2266 行）、`backend-agent/app/main.py`（1279 行、25 个路由）、`GroupChatService.java`（1335 行）。其中只有 `main.js` 完全没有测试，是风险最高的一处。
- **运维脚本与生产环境强耦合**：109 个脚本硬编码同一台主机、7 个部署变体各自维护一份 SSH 样板，任何口令/端口变更都要批量改文件（M-07）。
- **门禁与文档脱节**：AGENTS.md §4 规定四套测试与串行前端跑法，`ci.yml` 只实现两套（M-08）；§3.3 的 ASCII-only 在 33 个迁移文件上已失守（M-10）。规则写在文档里但没有机器执行，是最容易被时间侵蚀的耦合。

## 未验证与抽样说明

- 12 个单元中 **u01–u07 为 sampled**（每单元 32 个文件，占该单元 27%–34%），**u08–u12 为 reviewed**（32 个文件，占 45%–67%）。所有单元都**不是**全文逐行通读；结论以“核心文件 + 针对性模式扫描”为基础。
- 未逐一确认 `scripts/` 下约 40 个 `_cloud_*`/`_diag_*` 脚本的最后使用时间，M-07 的删除清单需维护者确认后再执行。
- 未做依赖树级别的分析（例如 `frontend/package-lock.json` 与 `admin/package-lock.json` 的版本重复），M-09 只覆盖源码零引用这一确定性证据。
- u01 的 `*Port` 是否“过度抽象”只从引用数量与 AGENTS.md 记录判断，未做架构级评估，因此不作为发现上报。
- 未运行 `npx vite build`、`scripts/check-java-ai-free.ps1`、`python scripts/check_release_asset_contract.py` 等门禁脚本（超出可维护性范围，且需构建环境）。
- 测试结果中 admin 与 pytest 的失败均定位为**环境未就绪**（未安装依赖、仓库根缺少可导入路径），没有出现断言级失败；因此不把这两条计入“代码缺陷”，仅作为事实记录。

## 建议修复顺序

1. **M-08（CI 补 job + 串行前端）** —— 先让门禁可信，否则后面每一步都缺少回归保护。改动小、风险低（direct）。
2. **M-02（语言清单收敛 + i18n 一致性测试）** —— 用户直接可见（裸 key），且能立刻拦住后续文案漂移。
3. **M-03（删除不可达的 prompt 回退路径）** —— 只需改一处判断 + 一条迁移，收益是所有角色文案回到单一真值。
4. **M-04 / M-05（SSE 与 profiler 去重）** —— 先补 golden 帧/共享模块，再删副本，属“先立后破”。
5. **M-01（Electron 拆分）** —— 收益最大但工作量也最大，建议在前四项稳定后按四批独立 PR 推进。
6. **M-07（脚本收敛）** —— 需要维护者确认清单，可与 M-01 并行，但不建议一次性大删。
7. **M-09 / M-11（依赖与格式化，direct）** —— 随时可做，建议压在其它改动之后以免干扰 diff。
8. **M-06（脆断测试改写）** 与 **M-10（迁移 ASCII 门禁）** —— 收尾项，前者随页面重构顺手做，后者只对新文件生效。

## 跨专家转交

- **安全（security）**：`scripts/` 内 109 处硬编码生产主机 `156.233.228.18`、`scripts/_check_yml.py` 直接把 `application.yml` sftp 到生产、`backend/scripts/_vault_export.tsv` 这个 0 字节却在版本库里的“vault 导出”占位文件，均建议做一次凭据与出站面复核。
- **可靠性（reliability）**：`CharacterSquareService` 的不可达回退分支（M-03）与 `AgentSseParser` 的独立解析实现（M-04）意味着帧格式变化时失败可能表现为“静默降级”而非报错，值得从失败可见性角度复看。
- **正确性（correctness）**：i18n 缺失的 6 个 key（M-02）在运行时表现为显示 key 名，属用户可见的界面缺陷；`memory.*` 仅存在于未加载的 zh-TW，建议确认记忆页文案的实际来源。
- **性能（perf）**：`ChatPage.vue`（2266 行）与 `GroupChatPage.vue`（1500 行）的拆分属可维护性诉求，但其内部 watcher/渲染开销应交由性能专家评估，避免两次重构冲突。
