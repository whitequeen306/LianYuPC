# 可维护性审查报告

审查对象：LianYu-PC 全项目（project 模式，非 diff）。1108 个文件 / 140294 行 / 12 个审查单元。
三块口径：`simplify`（简化）、`coupling`（耦合）、`cleanup`（清理）。可维护性结论一律不超过 `medium`，只报「会让未来的改动更贵」的问题，不报纯风格。

## 摘要

11 条发现：simplify 3、coupling 2、cleanup 6；severity 为 medium 7 条、low 4 条。
最该先处理的是角色广场名册的两份权威（M-07）、admin 后台的空壳现状（M-03）、81 个一次性脚本入库且 72 个零引用（M-02）。
整体判断：分层边界（Java 取事实 / Python 拼 prompt / Electron 管本机）在代码里守得住，未发现跨层直接调用或循环依赖；
主要成本集中在「同一个事实有两份手写清单」和「一次性产物长期入库」两类。

## 范围与覆盖

| 单元 | 内容 | 文件/行 | 状态 | 说明 |
| --- | --- | --- | --- | --- |
| u01 | backend/lianyu-service (1/3) | 120/12117 | reviewed | service/agent 端口层 20 个文件 686 行全文读过；12 个 Port 接口逐个 grep 全 backend 均有实现与调用方（最少的 AgentTextStreamPort 3 处、SessionSummaryPort 4 处），javadoc 写的是「为什么」，不报过度抽象。dto/ChatResult 仍被 ProactiveMessageService、VoiceCallService 使用，非死代码。 |
| u02 | backend/lianyu-service (2/3) | 120/16014 | reviewed | square 目录 8 个 CharacterSquareCatalog*（2429 行）与 CharacterSquareService 全文读过，确认名册双权威（M-07）；其余文件按 rg 扫描 import/调用面。 |
| u03 | frontend/src (1/2) | 120/17405 | sampled | 120 个文件全部打开抽读 + rg 模式扫描（api/ composables/ stores 的 import 图）；深入读了 api/httpCore.js、vite.config.js、package.json。未逐行通读 59 个 .vue，故记 sampled。 |
| u04 | scripts (1/2) | 120/12803 | reviewed | 120 个文件全部打开；对 81 个一次性脚本做了全仓引用扫描（M-02）；门禁与常驻脚本（release_assets.json、check-java-ai-free.ps1、check_release_asset_contract.py、e2e/lianyu_e2e.py、ops/daily_parity_check.py）逐个读过。 |
| u05 | backend/lianyu-dao | 110/2934 | reviewed | 110 个文件全读；V1–V57 逐个迁移看头注释与表名（M-08），dao 层的 mapper 未发现重复实现。 |
| u06 | frontend/src (2/2) | 103/27399 | sampled | 103 个文件全部打开抽读，重点看 pages/ 与 stores/ 的 import 面与重复组件；未逐行通读若干 500+ 行页面，故记 sampled。 |
| u07 | backend-agent | 102/16514 | reviewed | 102 个文件全读；prompts/fragments.json 的 56 个 block 与 8 个来源逐一对齐（M-04），prompt_assembly/system_prompt 的调用链跟到真实调用点（M-06）。 |
| u08 | backend/lianyu-web | 71/7840 | sampled | 71 个文件全部打开抽读；对 web/agent 适配层与 controller 做 rg 扫描，未发现重复实现或死代码（见报告「已查未发现问题」）。 |
| u09 | frontend/electron | 68/14745 | reviewed | 68 个文件全读；preload 的 93 个暴露方法逐个做全仓调用扫描（M-01），main.js 3077 行按函数分组通读（M-09）。 |
| u10 | 其他模块（(root)、.github、admin 等） | 67/4923 | reviewed | 67 个文件全读（admin 全部源文件 + .github + 根目录配置），见 M-03、M-10。admin 的 4 个未用依赖与测试跑不起来一并记录。 |
| u11 | backend（小模块合集 59 文件） | 59/3141 | sampled | 59 个文件全部打开抽读；小模块合集，未发现重复实现或孤儿文件。 |
| u12 | 其他模块（backend/lianyu-app、backend/lianyu-common、backend 等） | 48/4459 | sampled | 48 个文件全读（单元更小）；lianyu-app / lianyu-common 未发现死代码，OutputLanguage 的别名表已在 M-06 里对照。 |

12 个单元全部检查，无 skipped：reviewed 7 个（u01/u02/u04/u05/u07/u09/u10），sampled 5 个（u03/u06/u08/u11/u12，逐行通读不足，做了全文件抽读 + rg 模式扫描）。
每个单元的 `units[].files` 列出 36 个实际证据文件（含该单元全部 core），完整清单在 maintainability.json。

## 发现（按严重度）

- [medium] [u04] [plan] M-02 — 81 个一次性排查脚本入库，72 个全仓库零引用
详细问题：81 个一次性排查脚本已入库，其中 72 个在全仓库无任何引用
位置：`scripts/_cloud_verify.py:1` · 证据：证据：git ls-files 'scripts/_*' = 60 个（6173 行）、git ls-files 'scripts/diag/*' = 21 个（1461 行），合计 81 个一次性脚本入库。对 81 个文件名做全仓引用扫描（docs/、AGENTS.md、.github/、frontend/ 一起扫）：72 个零引用；仅 9 个被文档或流程引用（_cloud_deploy_pull.py、_cloud_deploy_layered.py、_upload_update_assets.py、_upload_wechat_channel.py、_prod_verify.py、_gate_installer_prompt.ps1、diag/README.md、diag/_diag_pet_tts_logs.py、diag/_gen_pet_fixed_voices.py）。近似重复族：_cloud_verify.py / _cloud_verify_deploy.py / _cloud_verify_logs.py / _cloud_verify_post.py / _prod_verify.py / _run_prod_verify.py 六个都在做「部署后自检」；_repair_flyway_v31.py、_repair_flyway_v34.py、ops/_repair_flyway_v30_mark_applied.py、ops/_repair_flyway_v34_mark_applied.py 是同一类 Flyway 补账脚本。.gitignore:106-107 已把 scripts/diag/ 与 artifacts/ 标为一次性产物，AGENTS.md §3.1 也要求 `_` 前缀脚本不进仓库，但 21 个 diag 文件仍是 tracked。
影响：scripts/ 既放门禁与发版入口（release_assets.json、check-java-ai-free.ps1、check_release_asset_contract.py），又放历史垃圾，改动时无法靠目录判断哪些是必跑门禁；六个近似「部署自检」并存，没人知道该跑哪个，部署口径会分叉。 · 修复：把 scripts/ 收敛成「门禁 + 发版 + 运维常驻」三类，一次性脚本出仓或归档成台账，重复族合并为带子命令的单一脚本。（plan，涉及 `scripts/`, `.gitignore`, `AGENTS.md`；验证：powershell -File scripts/check-java-ai-free.ps1 -Mode strict）

- [medium] [u10] [plan] M-03 — admin 后台空壳：表单无提交函数、窗口三键失效、概览写死
详细问题：admin 后台是空壳：表单无提交函数、标题栏三键取不到 window、概览数据写死
位置：`admin/src/pages/ManagementPage.vue:15` · 证据：证据：admin/src/pages/ManagementPage.vue:15 表单 `<form class="dialog-panel" @submit.prevent="save">`，但该文件脚本段（第 1-13 行）没有定义 save；同文件 import 的 createRelease 无使用点。admin/src/App.vue:14 标题栏 `<button @click="window.adminWindow?.minimize()">−</button>`（另有 toggleMaximize / close 两键）：模板里 window 不在 setup 返回的作用域，解析到组件上下文而非全局 window，三个按钮都不会触发 IPC（preload.cjs 暴露的是 window.adminWindow）。admin/src/App.vue:14 概览写死 `<strong>v0.2.363</strong>`、`<strong>12</strong>` 与三条硬编码活动行；admin/src/api.js:6 定义 getOverview() 但全仓无调用。admin/package.json 的 8 个依赖里，echarts、@tanstack/vue-table、vue-router、@element-plus/icons-vue 在 admin/src 与 admin/electron 全仓 0 命中。admin 侧文件极小：ManagementPage.vue 15 行、DataTable.vue 5 行、stores/session.js 2 行、api.js 9 行。
影响：后台是运维判断「线上什么版本、有什么待办」的入口，写死数据会给出错误现状；表单静默失败会让人以为草稿已保存。任何一次后台验收都要先在这些假象上耗时间。 · 修复：先把「看起来能用其实不通」的入口收掉（禁用或标注未实现），再决定后台是继续做还是下线；概览改为读真实数据。（plan，涉及 `admin/src/App.vue`, `admin/src/pages/ManagementPage.vue`, `admin/src/api.js`, `admin/src/__tests__/menu.test.js`；验证：npm --prefix admin install && npm --prefix admin test）

- [medium] [u07] [direct] M-04 — 冻结的 Java 文案基线仍带生成器，一跑就把 56 条截成 11 条
详细问题：已冻结的 Java prompt 片段基线，配套生成器仍会静默把 56 条截成 11 条
位置：`scripts/extract-java-prompt-fragments.py:118` · 证据：证据：backend-agent/app/prompts/fragments.json 共 56 个 block，来自 8 个文件；其中 ChatTurnPromptAssembler.java(8)、CharacterPromptBuilder.java(1)、ReplyBehaviorRuleHook.java(24)、OutputLanguageRuleHook.java(4)、GroupChatRuleHook.java(8) 已不存在（45/56 block 的来源已删）。scripts/extract-java-prompt-fragments.py:118-121 对缺失来源只打印 `skip (missing)` 后 continue，:122-127 直接 OUT.write_text 覆盖，跑一次只剩 11 条且无告警。backend-agent/app/prompt_fragments.py:29-32 的报错文案仍教人 `run: uv run python scripts/extract-java-prompt-fragments.py`；同文件 docstring:12 仍称测试会校验「重新生成与仓库 JSON 一致」，而 backend-agent/tests/test_prompt_fragments.py:38-42 已改成「Java prompt 源码已删除，fragments.json 是冻结基线」，不再校验来源。消费方 backend-agent/app/prompt_assembly.py 仍按 (java 文件名, 行号) 取文本：_TIME_PREFIX=("ChatTurnPromptAssembler.java",161)、_CITY_PREFIX=("ChatTurnPromptAssembler.java",183)、_NATURAL_STYLE_LINES 用 OutputLanguageService.java:162-197，且被 app/system_prompt.py:181/187/189/197 真实调用。
影响：维护者按报错指引重新生成基线，会得到缺 45 条的文件，测试不红，线上时间前缀/城市前缀/自然口语风格文案静默退化成空，只在特定语言或场景的回复里才看得出来。 · 修复：承认这份 JSON 是冻结资产：让生成器不可再运行、改掉报错文案与 docstring 的「重新生成」指引，并补一条基线键位断言。（direct，涉及 `scripts/extract-java-prompt-fragments.py`, `backend-agent/app/prompt_fragments.py`, `backend-agent/tests/test_prompt_fragments.py`；验证：cd backend-agent && python -m pytest tests/test_prompt_fragments.py -q）

- [medium] [u07] [plan] M-06 — 语言代码归一化重复 5 份，zh-hk 在两条路径上结果不同
详细问题：语言代码归一化在 Python 里重复 5 份，zh-hk 在两条路径上归一结果不同
位置：`backend-agent/app/language_gate.py:38` · 证据：证据：五份实现 —— backend-agent/app/language_gate.py:38-50 from_code（接受 zh-tw / zh_tw / zhtw / zh-hant，不含 zh-hk）、backend-agent/app/prompt_assembly.py:111-119 normalize_language（先 replace('_','-')，接受 zh-tw / zhtw / zh-hant / zh-hk）、backend-agent/app/reply_rules.py:44-50、backend-agent/app/rule_templates.py:34-42、backend-agent/app/system_prompt.py:51-59 三处 _lang_key（replace('_','-') 后接受 zh-hk）。Java 侧同一概念还有一份：backend/lianyu-common/src/main/java/com/lianyu/common/i18n/OutputLanguage.java:27 `case "zh-tw", "zh_hant", "zh-hant", "zh_hk", "zh-hk" -> ZH_TW;`。可观察差异：输入 zh_hk 时 language_gate 归一为 zh（简体），prompt_assembly 归一为 zh-TW。
影响：语言是横切 prompt 组装、规则模板、语言闸门、Java 输出语言的概念，新增一个别名要改 5+1 处；已经出现 zh-hk 的口径分歧，用户会看到闸门判定与提示词语言不一致的回复。 · 修复：抽一个 backend-agent/app/language_codes.py 作为唯一归一化入口（ALIASES 表 + normalize），5 处改为调用它，Java 侧用同一张表对齐。（plan，涉及 `backend-agent/app/language_gate.py`, `backend-agent/app/prompt_assembly.py`, `backend-agent/app/reply_rules.py`, `backend-agent/app/rule_templates.py`, `backend-agent/app/system_prompt.py`；验证：cd backend-agent && python -m pytest tests -q -k lang）

- [medium] [u02] [plan] M-07 — 角色广场名册两份权威：Java 常量 2429 行 + Flyway 种子，无一致性校验
详细问题：角色广场名册有两份权威：Java 常量类 2429 行与 Flyway 种子并存，且无一致性校验
位置：`backend/lianyu-service/src/main/java/com/lianyu/service/square/CharacterSquareService.java:222` · 证据：证据：Java 侧 8 个类共 2429 行 —— CharacterSquareCatalog.java(462)、CatalogGenshin.java(590)、CatalogDal.java(421)、CatalogBlueArchive.java(369)、CatalogReZero.java(326)、CatalogDanganronpa.java(227)、CatalogHonkai.java(61)、CatalogShizhong.java(61)，54 个 slug、162 个 `private static String promptXxx()` 返回四语种人设文本（例：CharacterSquareCatalog.java:201-207 promptZhGanyu()）。运行时优先用 Java 常量：CharacterSquareService.java:222 `String resolvedPrompt = promptPack != null ? promptPack.prompt() : template.getPromptTemplate();`、:378 `.promptTemplate(pack.prompt())`。同一批 slug 又由 Flyway 写入 character_square_template（V10/V11/V12/V13/V14/V17/V18/V19/V25/V26/V27/V29/V36/V37/V45）。测试只断言数量与四语种非空（CharacterSquareCatalogTest.java:17-56，slug>=51、locale pack 非空），不与 DB 行交叉校验。
影响：新增或改名一个广场角色要同时改 Java 常量、Flyway 迁移、头像文件三处，漏一处不会红；DB 的 prompt_template 实际被 Java 常量覆盖，改 DB 文案不生效——这层关系只写在 CharacterSquareService.java:222 一行里。 · 修复：先定「人设文案的 owner」，再让另一侧退化为只读或删除，并加一条 Java slug 集合与 DB 行集合的比对测试。（plan，涉及 `backend/lianyu-service/src/main/java/com/lianyu/service/square/CharacterSquareService.java`, `backend/lianyu-service/src/main/java/com/lianyu/service/square/CharacterSquareCatalog.java`, `backend/lianyu-service/src/test/java/com/lianyu/service/square/CharacterSquareCatalogTest.java`, `backend/lianyu-dao/src/main/resources/db/migration/V18__character_square_genshin.sql`；验证：mvn -q -f backend/pom.xml test -Dtest=CharacterSquareCatalogTest）

- [medium] [u05] [plan] M-08 — 广场种子数据散在 19 个迁移里，含 4 个纠错补丁
详细问题：一个角色广场表的种子数据散在 19 个迁移脚本里，其中 4 个是事后纠错补丁
位置：`backend/lianyu-dao/src/main/resources/db/migration/V39__merge_eriri_into_erii_uesugi.sql:1` · 证据：证据：backend/lianyu-dao/src/main/resources/db/migration/ 共 V1–V57；涉及 character_square_template 的有 19 个迁移，其中 15 个含 INSERT（V10 6 行、V12 1、V13 9、V14 1、V15 1、V17 1、V18 9、V19 1、V25 4、V26 4、V27 7、V29 7、V36 2、V37 1、V45 1，合计 55 次插入），另外 V11 补 slug、V16 精简标签、V38 纠正 eriri 身份、V39 合并重复上杉绘梨衣（eriri → erii_uesugi）并删除一行。纠错型迁移原话：V12「替换为带头像的 7 人 roster」、V15「修正已存在的 linzihan 行」、V39「下线误加的 eriri，并把 VC/计数归到前者」。V30__version_gap_placeholder.sql 只有 2 行占位。
影响：想回答「广场现在有哪些角色、某人设是什么」必须读完这 19 个 SQL 并叠加 V39 的删除合并；每次改名/纠错都要再写一个迁移，版本号只增不减，新环境初始化要靠按顺序执行全部 55 次插入与 4 个补丁才能到达正确状态。 · 修复：把名册收敛成一份可读的种子文件 + 一个幂等同步，后续增改只改种子文件；历史迁移保持不动。（plan，涉及 `backend/lianyu-dao/src/main/resources/db/migration/`, `backend/lianyu-dao/src/main/resources/db/migration/V39__merge_eriri_into_erii_uesugi.sql`；验证：mvn -q -f backend/pom.xml test）

- [medium] [u10] [plan] M-10 — 版本号三处口径：frontend 0.3.15 / admin 0.2.363 / 后台写死 v0.2.363
详细问题：客户端版本号出现三处口径：frontend 0.3.15、admin 0.2.363、后台界面写死 v0.2.363
位置：`admin/src/App.vue:14` · 证据：证据：frontend/package.json:3 `"version": "0.3.15"`（AGENTS.md §3.1 指定的版本号唯一事实来源）；admin/package.json:3 `"version": "0.2.363"`；admin/src/App.vue:14 概览 `<strong>v0.2.363</strong><span class="muted">stable · 已发布</span>` 与活动行 `客户端版本发布 v0.2.363`。frontend/src 内已无 0.2.363 字样（仅 updater 测试用 0.2.368/0.2.367 样例），说明这个数字是从旧客户端版本抄进后台模板后无人更新。
影响：后台是「该发什么版本、线上是什么版本」的唯一界面提示，写死旧版本会误导发版判断；两个 package.json 各自 bump 会让发版脚本与 admin 打包版本持续漂移。 · 修复：后台版本改为读 admin 自身版本或后台 API，「最新客户端」取真实发布数据；把「admin 只读版本」写进单一事实来源清单。（plan，涉及 `admin/src/App.vue`, `admin/src/api.js`, `admin/package.json`, `frontend/package.json`；验证：npm --prefix admin install && npm --prefix admin test）

- [low] [u09] [direct] M-01 — preload 暴露 8 个零调用 IPC，main 仍留 7 个 handler
详细问题：preload 暴露 8 个零调用 IPC 通道，主进程仍保留对应 handler
位置：`frontend/electron/preload.js:28` · 证据：证据（CONFIRMED_DEAD）：frontend/electron/preload.js:11 touchPickerInteraction、:28 saveLauncherPosition、:29 moveLauncherByDelta、:33 setLauncherScreenPosition、:34 setLauncherDragging、:35 clampLauncherPosition、:101 onPickerShown、:171 getGlobalLogs。对 8 个标识符在 frontend/src（含唯一入口 src/entries/launcher-main.js:launcher.html:22、src/entries/quick-main.js:quick.html:25）grep 结果为 0 命中（排除 node_modules 与 dist 构建产物）。main.js 仍注册 desktop:picker-interaction:2106、desktop:get-global-logs:2610、desktop:save-launcher-position:2709、desktop:move-launcher-by-delta:2717、desktop:set-launcher-screen-position:2772、desktop:set-launcher-dragging:2784、desktop:clamp-launcher-position:2794。
影响：桌宠/选择器的位置逻辑现在有「界面已不调用的 IPC」与「实际在用的内部函数」两套并存；按 preload 名单推断能力边界会得出错误结论。preload.js 93 个方法 vs main.js 69 个 handler，两份清单只能人工比对，死通道越多越难核。 · 修复：确认真机不依赖后，成组删除 preload 的 8 个暴露项与 main.js 的 7 个 handler，保留仍被内部调用的位置计算函数为模块私有。（direct，涉及 `frontend/electron/preload.js`, `frontend/electron/main.js`；验证：node --check frontend/electron/preload.js && node --check frontend/electron/main.js）

- [low] [u03] [direct] M-05 — npm test 未按文档串行，默认并行 4 个用例超时
详细问题：frontend 的 npm test 没按 AGENTS.md 串行跑，默认并行会 4 个用例超时
位置：`frontend/package.json:18` · 证据：证据：frontend/package.json:18 `"test": "vitest --passWithNoTests"`（无 run、无 --no-file-parallelism），与 AGENTS.md §4「cd frontend && npx vitest run --no-file-parallelism」不一致。实测 npm --prefix frontend test：`Test Files 4 failed | 71 passed (75)`、`Tests 4 failed | 455 passed (459)`，4 个失败全是 `Error: Test timed out in 5000ms`（src/auth/__tests__/bootstrap.test.js:29、bootstrapRestoreRoute.test.js:37、launcherBootstrap.test.js:19 与 updater 相关 1 个）；追加 `-- --no-file-parallelism` 后 `Test Files 75 passed (75)`、`Tests 459 passed (459)`。
影响：本地/CI 只要按 package.json 跑就 4 红，与文档口径不一致；后来者会花时间排查这 4 个 flake，或误改断言迁就并行。 · 修复：把 test 脚本改成与门禁一致的串行 run，并让文档只引用这一条命令。（direct，涉及 `frontend/package.json`, `AGENTS.md`；验证：npm --prefix frontend test）

- [low] [u09] [plan] M-09 — main.js 3077 行装下 69 个 ipcMain.handle
详细问题：electron/main.js 3077 行塞满窗口、托盘、IPC 与桥接装配，69 个 ipcMain.handle 同处一文件
位置：`frontend/electron/main.js:2106` · 证据：证据：frontend/electron/main.js 共 3077 行，含 69 处 `ipcMain.handle(`；同目录已有拆分先例：frontend/electron/updater/updater.js 独立承接 updater:* 通道（:449 ipcMain.handle('updater:check')、:486 'updater:probe'）、desktopSettings.js 承接设置默认值、napcatRuntime/ 与 qqBridge/ 各自成目录。preload.js 仅 187 行却是 93 个方法的白名单，与 main.js 的 69 个 handler 需要人工对齐。
影响：主进程是崩溃面最大的部分，3077 行让「哪段代码负责哪个窗口」只能靠搜索；新增 IPC 时容易漏改 preload 白名单（93 对 69 已经对不齐），review 只能肉眼比对。 · 修复：按窗口/领域把 IPC 注册拆到 electron/ipc/*.js，main.js 只留装配与生命周期，通道名与行为不变。（plan，涉及 `frontend/electron/main.js`, `frontend/electron/preload.js`, `frontend/electron/updater/updater.js`；验证：npm --prefix frontend test -- --no-file-parallelism）

- [low] [u03] [plan] M-11 — vite-plugin-electron-renderer 全仓无 import
详细问题：frontend 依赖 vite-plugin-electron-renderer 全仓库没有任何 import
位置：`frontend/package.json:44` · 证据：证据：frontend/package.json:44 `"vite-plugin-electron-renderer": "^1.0.0"`（devDependencies）；全仓 grep 该 specifier 只命中 frontend/package.json:44 与 frontend/package-lock.json:34/7608/7611/7618，无任何 import/require。frontend/vite.config.js 用的是同一套插件的另一个入口 `vite-plugin-electron/simple`。
影响：依赖清单失去「装了就一定在用」的可信度，升级与安全审计时多一个需要单独判断去留的条目。 · 修复：从 devDependencies 删除该依赖并刷新锁文件，跑一遍构建与测试确认无影响。（plan，涉及 `frontend/package.json`, `frontend/package-lock.json`；验证：cd frontend && npx vite build）

## 耦合度

扫描范围：三个运行时进程（Electron 主进程 / Java 多模块 / Python backend-agent）之间的调用边，以及每个进程内部的模块依赖；判据是「改一个概念要动几个文件」和「有没有第二份事实来源」。

- **Java ↔ Python 的边界是清的**：Java 侧只经 `AgentRuntimePort` 等 12 个端口（backend/lianyu-service/.../service/agent/，686 行）向外要结果，Python 侧不写 MySQL（backend-agent 的 dao 访问全是只读查询），未发现循环依赖或低层 util 反向 import 上层。
- **两份权威是主要耦合源**：角色广场名册同时活在 Java 常量（2429 行 / 54 slug）与 Flyway 种子（17 个批次迁移 + V39 合并）里，运行时 Java 覆盖 DB（CharacterSquareService.java:222），改一处不生效（M-07）；版本号同时活在 frontend/package.json(0.3.15)、admin/package.json(0.2.363) 与后台模板写死的 v0.2.363 里（M-10）。
- **手写清单需要人工对齐**：frontend/electron/preload.js 的 93 个暴露方法对 main.js 的 69 个 handler，没有契约测试，已实际漂出 8 个零调用通道（M-01）与 1 个只在主进程侧注册的 `desktop:notify-window-changed`。
- **共享数据库**：Java 与 Python 共用 MySQL，但 Python 只读；风险集中在 `character_square_template` 这类「代码里也有一份」的表（M-07）。
- 未发现问题：frontend/src 内部（api → stores → pages）依赖方向单向；backend 各模块（service / web / dao / common / security）无反向依赖；backend-agent 的 `tool_registry.py` 保持叶子模块，未 import 其它 app 内部包，符合 AGENTS.md §3.1。

## 已查未发现问题

- **u01 端口层**：12 个 Port 接口逐个 grep 全 backend，均有实现与调用方（最少的 AgentTextStreamPort 3 处），javadoc 写的是「为什么这么切」，不是过度抽象；dto/ChatResult 仍在使用。
- **u08 lianyu-web**：agent 适配层（ChatTurnAdapter / AgentRuntimeClient / AgentStreamBridge）与 controller 未发现重复实现或死代码；SSE 帧契约集中在 AgentStreamBridge，未出现第二份解析。
- **u11 / u12 小模块合集**：未发现重复实现、孤儿文件或未用依赖。
- **u05 dao 层**：mapper 与实体未发现重复；问题只出在迁移脚本承载种子数据（M-08）。
- **零 AI 逻辑门禁**：JavaAiLogicRatchetTest 与 scripts/check-java-ai-free.ps1 仍在跑（123 tests 全绿），CharacterSquareCatalog 的豁免是显式登记而非门禁失效，未据此报问题。

## 未验证与抽样说明

- u03 / u06 / u08 / u11 / u12 记 `sampled`：1108 个文件都打开过（头部抽读 + rg 模式扫描），但没逐行通读全部 .vue 与大页面，结论强度低于 reviewed 单元。
- `npm --prefix admin test` 与 `pytest -q backend-agent` 未能真正执行到断言：admin 缺 node_modules，backend-agent 缺 aiomysql/respx 且仓库根无 sys.path 注入。两条在 tests.commands 里如实标 `failed`，未用「测试通过」概括。
- `npm --prefix frontend test` 默认并行 4 红、串行全绿；这是已知 flake，不是本次改动引入，已在 M-05 里按脚本口径漂移处理。
- M-09（main.js 3077 行拆分）的收益判断基于文件规模与既有拆分先例，未做拆分原型，因此 confidence 记 medium。

## 建议修复顺序与转交

1. **先定事实来源，再动手**：M-07（广场名册 owner）与 M-10（版本号）需要产品拍板，拍板前不要动代码；这两个不解决，M-08 的迁移收敛也无从落地。
2. **低风险清理先做**：M-01（删 8 个死 IPC）、M-05（test 脚本串行）、M-04（冻结基线收口）都是一次性小改，能立刻降低误改概率；M-11 随下一次依赖维护一起做。
3. **后台先止血**：M-03 的「表单静默失败 / 写死版本」应在下一次运维用后台之前改成显式不可用或读真实数据。
4. **结构性收敛最后做**：M-02（scripts 收敛）、M-08（迁移 → 种子文件）、M-09（main.js 拆 IPC）改动面大，建议各自独立提交，先加契约测试再拆。
5. **跨专家转交**：
   - Java 侧 2429 行角色人设文本是否违反「Java 零 AI 逻辑」→ 交 architecture/correctness（当前是 RatchetTest 的显式豁免，不是门禁失效）。
   - `pytest -q backend-agent` 在仓库根收集失败（无 conftest 注入 sys.path、缺 aiomysql/respx）→ 交 reliability/testability。
   - admin/electron 的 dev 模式变量未注入（静态观察，未运行验证）→ 交 reliability。
