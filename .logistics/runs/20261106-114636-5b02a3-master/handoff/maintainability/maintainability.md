# 可维护性审查报告

## 范围与覆盖

- 工作区：`C:/Users/hp/Desktop/LianYu-PC`，审查对象是**整个项目**（非 diff、非最近提交）：1108 个文件 / 12 个审查单元 / 139994 行（scope.json 口径）。
- 方法：每个单元按 `core` + 最小文件优先选满 **30 个文件**逐文件实读，全部 12 个单元的 `core` 路径都包含在实读清单中；另叠加四类跨文件扫描——重复代码 8/14 行窗口检测、环境变量读取点对比、依赖引用计数、自建 import 图（frontend/src 219 模块 / backend-agent app+tests / service 包级引用矩阵）。
- 单元状态（详见 handoff JSON 的 `units`）：

| 单元 | 范围 | 单元文件数 / 实读 | 状态 | 说明 |
|---|---|---|---|---|
| u01 | backend/lianyu-service (1/3) | 120 / 30 | sampled | agent 包 9 个窄端口、结果类型、DTO 抽样 |
| u02 | backend/lianyu-service (2/3) | 120 / 30 | sampled | dto/relationship/rules/graph/memory + 3 个单测 |
| u03 | frontend/src (1/2) | 120 / 30 | sampled | api/ + constants/ + composables/ + 4 个测试 |
| u04 | scripts (1/2) | 120 / 30 | sampled | 30 个脚本 + 全目录统计 |
| u05 | backend/lianyu-dao | 110 / 30 | sampled | pom、20 份 Flyway 迁移、Mapper 抽样 |
| u06 | frontend/src (2/2) | 103 / 30 | sampled | 14 个巨型 SFC + 170 模块 import 图 |
| u07 | backend-agent | 102 / 30 | sampled | main.py 全读 + 各域模块抽样 |
| u08 | backend/lianyu-web | 71 / 30 | sampled | 控制器全薄（38-86 行）后抽样 filter/测试 |
| u09 | frontend/electron | 68 / 30 | sampled | main.js 3077 行抽样 + updater/qqBridge/napcat 系 |
| u10 | 其他模块（root/.github/admin 等） | 67 / 30 | sampled | admin 全部 src、.env.example、deploy、security |
| u11 | backend 小模块合集 | 59 / 30 | reviewed | lianyu-admin 全部类 + 11 个 service 单测，未见问题 |
| u12 | 其他模块（lianyu-app/common/installer 等） | 48 / 30 | sampled | common、installer C#、Pets、backend/scripts |

**已查未发现问题的区域**：u01（端口层与 56 个 DTO 无结构重复）、u05（Flyway 57 个迁移无重号，空 Mapper 是 MyBatis-Plus 约定）、u11（lianyu-admin 全部为 8-23 行极薄分层）、u07 的 prompt 层（`prompt_fragments.py` 按 Java 文件+行号锚定并有 golden 比对，是刻意单源化而非重复）、u08 的 `LegacyCommunityController`（类注释写明删除条件的过渡垫片）。

## 发现

- [medium] [u08] [plan] M01 — 五个适配器各抄一份 Python 服务健康探测，另一个适配器却用别的开关判断
详细问题：Agent 健康探测在 5 个适配器里各写一份，门控策略还不一致
位置：backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentBlockingAdapter.java:126 · 证据：grep `private boolean reachable()` 命中 5 处——AgentBlockingAdapter.java:126、AgentStreamAdapter.java:106、AgentPromptAssemblyAdapter.java:108、AgentSessionSummaryAdapter.java:109、ChatTurnAdapter.java:204，方法体同为 `if (now - healthyCheckedAt < 3_000L) return healthy; healthy = client.isHealthy();`；而 AgentTextAdapter.java:33 只判 `if (!client.properties().agentActive()) { return null; }`
影响：3 秒缓存与“服务可用”语义没有单一所有者，改窗口要改 5 处；文本链路与流式链路对可用性给出不同答案，排查“文本能回、流式不回”时会被误导 · 修复：新增 AgentHealthGate（持有 client 与 TTL），5 个适配器注入同一实例，并对齐或注明 TextAdapter 的差异，补 3 秒内命中/过期重探的单测

- [medium] [u07] [plan] M03 — 模型运行时入口单文件 1278 行、25 个端点没有拆 router
详细问题：backend-agent/app/main.py 1278 行、25 个端点没有拆 router
位置：backend-agent/app/main.py:1 · 证据：1278 行内 31 个 def、25 个 `@app.post/get`、0 处 `APIRouter`；最长 `chat_turn_stream` 102 行（:495）、`chat_stream` 94 行（:201）、`text_chat` 92 行（:360）；同目录第二大模块 gateway.py 仅 504 行
影响：端点装配、异常映射、SSE 收尾全耦合在一个文件，任何改动都要重读全文，合并冲突集中；单测只能整体导入 app，无法按域隔离 · 修复：先加 25 条路由的 method+path+状态码契约快照，再按 chat/voice/memory/prompt/observe 拆 APIRouter，main.py 只留装配，最后校验 SSE 帧契约逐字节不变

- [medium] [u02] [plan] M04 — AI 包与角色包互相 import，包边界名存实亡
详细问题：com.lianyu.service.ai 与 com.lianyu.service.character 包级互相依赖
位置：backend/lianyu-service/src/main/java/com/lianyu/service/character/CharacterDiaryService.java:18 · 证据：ai→character 为 AiChatService.java:11-12（CharacterImportDraftMapper、CharacterImportSourceParser）、AiBackgroundConsumer.java:5（CharacterDiaryService）；character→ai 为 CharacterDiaryService.java:18-20（ApiKeyVaultService、AiBackgroundPublisher、AiBackgroundTask）、CharacterImportService.java:5-6（AiChatService、ApiKeyVaultService）、CharacterService.java:32（PetVoiceRegistry）；全 service 包仅此一对互指
影响：两个包（ai 20 类、character 15 类）无法单独抽取或做包级测试，任何一方构造签名变化都会牵动另一边 · 修复：在 service/port 下抽 ApiKeyVaultPort/PetVoicePort/DiaryBackgroundPort/CharacterImportParsePort，角色侧再暴露 CharacterImportFacade 供 AiChatService 单向调用，并用棘轮脚本锁死 import 方向

- [medium] [u06] [plan] M05 — 通知 store 与“点通知跳转”组件互相 import，构成全前端唯一循环依赖
详细问题：notifications store 与 useNotificationNavigation 互相 import（全前端唯一环）
位置：frontend/src/stores/notifications.js:14 · 证据：notifications.js:14 `import { navigateToNotification } from '@/composables/useNotificationNavigation'`；useNotificationNavigation.js:3 `import { useNotificationsStore } from '@/stores/notifications'`；对 219 模块 / 476 条边做 Tarjan 检测仅此一个强连通分量，store 417 行同时承载 STOMP、提示音、弹窗、toast、未读计数
影响：两者无法单独加载与单测，“通知到达→选哪个页面”的决策散落两侧，新增通知类型要同时改两处 · 修复：store 改为发事件或注入 handler，导航决策回到 composable/router，然后用 import 图脚本复跑确认 0 个环

- [medium] [u03] [plan] M06 — 语音可拨打角色白名单在 Java 与 JS 各手写一份，靠注释人工同步
详细问题：语音可拨打角色白名单在 Java 与 JS 各手写一份，靠注释人工同步
位置：backend/lianyu-service/src/main/java/com/lianyu/service/conversation/VoiceCallService.java:69 · 证据：:67 注释 `Keep in sync with frontend {@code voiceCallPets.js}.`，:69-83 `Set.of("klee","ganyu","ayaka","raiden","elysia","erii_uesugi","yae_miko","kokomi","shenhe","nahida","hu_tao","furina","noelle")`，消费点 :242-243；frontend/src/constants/voiceCallPets.js:1 注释 `must match VoiceCallService.VOICE_CALL_PET_IDS`，导出同一批 13 个 id
影响：同一事实两处定义且无测试比对，漏改时前端仍显示“语音通话”入口而后端判为不可拨打，用户看到“按钮在但打不通” · 修复：短期加读取 Java 常量与 JS 数组做集合比对的测试；中期由后端提供可拨打列表接口或构建期生成前端常量，删除手写副本

- [medium] [u06] [plan] M07 — 617 行繁体语言包从未被加载，只有一个测试在维护它
详细问题：zh-TW 语言包未被注册（CONFIRMED_DEAD），仅测试在续命
位置：frontend/src/i18n/index.js:12 · 证据：index.js:9-16 只 import zh/ja/en 且 `messages: { zh, ja, en }`；constants/language.js:3 `SUPPORTED_LANGUAGES = ['zh', 'ja', 'en']`，normalizeLanguage 把未知值回退 'zh'；locales/zh-TW.js 617 行，唯一 import 是 i18n/__tests__/memoryLocale.test.js:5,7（断言四语 memory.* key 一致）；utils/dateLocale.js:4 仍判 'zh-TW' 但不可达。搜索过程：grep `zh-TW` frontend/src 仅命中语言包、该测试、dateLocale.js 与 language.js 注释
影响：按 AGENTS.md §3.2“四语齐”的要求，后续新增 key 仍要补一份用户永远看不到的译文，否则测试红 · 修复：确认产品是否要繁体——不要则删语言包、测试改三语、同步改 AGENTS.md；要则注册进 messages 与 SUPPORTED_LANGUAGES 并补 UI 选项

- [medium] [u06] [plan] M08 — 四个前端模块已无任何生产引用，合计 791 行，其中一个只被自己的测试引用
详细问题：4 个前端模块无任何生产引用（合计 791 行），其中一个只被自己的测试引用
位置：frontend/src/composables/useVoiceRecorder.js:1 · 证据：170 模块 import 图下 useVoiceRecorder.js（466 行）唯一引用为 composables/__tests__/useVoiceRecorder.test.js:2；utils/captureChatShareScreenshot.js（220 行，导出在 :128）0 调用；utils/antiDebug.js（93 行）仅被 utils/logger.js:4 注释提及；api/asr.js（12 行）的 `transcribeAudio` 0 调用，等价 multipart 逻辑在 api/voiceCall.js:1-13 被重写
影响：录音/截图/反调试/ASR 四个能力在代码库里看起来仍在生效，改 ASR 时可能改到没人走的 asr.js · 修复：删除 4 个模块与 useVoiceRecorder.test.js，清理 logger.js:4 的过期注释；若截图功能仍要上线，先建 issue 而从历史提交取回

- [medium] [u04] [plan] M09 — 脚本目录违反自己 README 的约定，94 个根条目里混着 23 个云部署脚本和几对重复脚本
详细问题：scripts/ 根目录膨胀且违反自身 README 约定，存在成对重复脚本
位置：scripts/README.md:18 · 证据：README.md:18 要求“新增一次性排障脚本请放入 diag/ 或 ops/”，实际根目录 94 条目 / 132 跟踪文件、23 个 `_cloud_*.py` 共 1779 行；重复对：diag/_diag_community_feed.py 与 _diag_community_feed2.py、diag/_fix_gateway_connect.py/_fix_gateway_network.py/_fix_gateway_order.py、gen_pet_fixed_voices.py 与 diag/_gen_pet_fixed_voices.py、_repair_flyway_v34.py 与 ops/_repair_flyway_v34_mark_applied.py
影响：部署/排障入口不可枚举，三个 `_fix_gateway_*` 变体哪个是最终版无从判断，新人容易跑错或重复实现已有逻辑 · 修复：清点根目录并标注长期入口，合并 23 个云部署脚本为单一入口或移入 ops/cloud/，合并 4 组重复，README 补目录树并加 CI 条目数上限

- [medium] [u12] [plan] M10 — backend/scripts 留着五个硬编码作者本机路径的一次性迁移脚本
详细问题：backend/scripts 残留一次性迁移脚本：硬编码个人绝对路径 + 0 字节占位文件
位置：backend/scripts/restore-service-imports.ps1:3 · 证据：5 个 ps1 硬编码 `c:\Users\hp\Desktop\LianYu-PC\...`——dedupe-java-imports.ps1:1、fix-service-cross-imports.ps1:2、repackage-service.ps1:3-4、restore-service-imports.ps1:3、strip-utf8-bom.ps1:1；restore-service-imports.ps1:1 注释写明它只是回滚 fix-service-cross-imports.ps1 的产物；repair-flyway-v10.sql / repair-flyway-v14-checksum.sql 写死版本号与 `checksum = -724640786`；`_vault_export.tsv` 为 0 字节但仍被跟踪；除 .logistics 快照外无任何引用
影响：目录名让人以为可以照跑，实际指向作者机器路径、SQL 绑定已过去的迁移版本，误跑会改错文件或写错 flyway_schema_history · 修复：确认对应包重构已完成，删除 5 个 ps1、2 份 repair SQL、check-v10-state.sql 与 0 字节 tsv；如需留档移入 docs/refactor-2026-06/ 并注明“仅历史参考，勿执行”

- [medium] [u09] [plan] M11 — Electron 仍在解密已被移除的 localStorage token，注释还写着“与 secureToken 保持一致”
详细问题：rendererTokenScript 解密的是已被移除的 localStorage token（恒返回 null）
位置：frontend/electron/rendererTokenScript.js:[REDACTED:secret] · 证据：该脚本读 `localStorage.getItem('_ltt')` 与 `_lkt` 做 AES-GCM 解密，注释称“逻辑与 src/utils/secureToken.js 保持一致”；但 src/utils/secureToken.js:[REDACTED:secret] 注明“#14：移除 localStorage 加密剧场”，:9 说明 token 仅驻留内存，全仓对 `_ltt`/`_lkt` 的 `localStorage.setItem` 命中为 0；消费点 electron/main.js:82 import、:991-1000 pullRendererAuthToken、:1019 调用；另 main.js:963 的 loggedIn 仍判 `localStorage.getItem('_ltt')`
影响：每次 ensureAuthSessionFromRenderer（main.js:1081/1088/1091/2057 调用）都会对候选窗口执行必然返回空的 executeJavaScript，且注释与实现相反，维护者会误以为存在 localStorage 回退 · 修复：删除 rendererTokenScript.js 与 pullRendererAuthToken 分支，认证恢复统一走主进程 safeStorage；loggedIn 改判 'lianyu-user-profile'，并补“主进程无 token 时开窗不阻塞”的 Electron 测试

- [low] [u06] [direct] M02 — 三个聊天页各抄一份消息时间解析
详细问题：消息时间解析/分隔条格式化在 3 个页面各写一份
位置：frontend/src/pages/ChatPage.vue:1556 · 证据：ChatPage.vue:1556-1565、GroupChatPage.vue:883-892、QuickChatPage.vue:157-166 的 `parseMessageTime` 与 `formatTimeDivider` 逐字相同，ChatPage.vue:1567-1569 还多一份同实现的 `formatTime`
影响：分隔条策略（脏时间回退、跨天格式、locale 透传）被拆三份，改一处漏两处会让三个页面时间显示不一致 · 修复：抽到 frontend/src/utils/messageTimeline.js，三页改 import 并补 utils 单测

- [low] [u10] [plan] M12 — 管理后台声明了 4 个从未 import 的依赖
详细问题：admin 声明了 4 个从未被引用的依赖
位置：admin/package.json:10 · 证据：该行 dependencies 含 `@element-plus/icons-vue`、`@tanstack/vue-table`、`echarts`、`vue-router`；在 admin/src 与 admin/electron 内 grep 这四个包名命中 0；admin 前端仅 10 个 src 文件（App.vue 16 行、ManagementPage.vue 16 行、DataTable.vue 6 行等）
影响：多装 4 个未使用依赖（含 echarts 这类大包），安装/审计/升级都要为它们付费，也让人误以为后台已有图表与路由能力 · 修复：删除这 4 项并 `npm install` 更新 lock，确认 `npm ls` 无 peer 冲突；后台若要做图表另开任务

## 耦合度

- **Maven 模块层无环**：lianyu-common ← lianyu-dao ← lianyu-service ← lianyu-web，lianyu-app 依赖 admin+web，security/ai 仅依赖 common(+storage)，依赖方向干净。
- **Java 包级唯一互指**：`com.lianyu.service.ai` ↔ `com.lianyu.service.character`（各 2 处，见 M04），是当前最值得优先解开的耦合。
- **前端 219 模块 / 476 边仅 1 个强连通分量**：`stores/notifications.js` ↔ `composables/useNotificationNavigation.js`（M05）。
- **跨语言重复事实**：语音白名单 Java/JS 双份（M06）；错误码→文案、SSE 帧契约、prompt 文案均由 Python 单一持有，分层边界总体保持良好。

## 未验证与抽样说明

- 12 个单元均为 30 文件实读（u11 为 reviewed，其余为 sampled）。u01/u02/u03/u04/u06 单元共 120 文件、u05 110、u07 102、u09 68、u10 67 文件，**其余文件只经过 rg 模式扫描与 import 图覆盖，不等于逐行读过**。
- 未做运行时验证：未启动服务、未连数据库、未跑 Electron 冒烟，因此 M11 的“每次开窗执行一次”依据调用点静态分析（main.js:1081/1088/1091/2057）。
- 测试结果：`mvn -q -f backend/pom.xml test` 通过（123 份 surefire 报告）、`npm --prefix frontend test` 通过（75 文件 / 459 用例）；`npm --prefix admin test` 失败于 `'vitest' 不是内部或外部命令`（admin 未安装 node_modules），`pytest -q backend-agent` 失败于收集阶段 `ModuleNotFoundError: No module named 'respx'`（缺 dev 依赖）——两条均为环境问题，不是断言失败，已如实记为 failed。
- 未纳入：性能问题（按规则一律转 perf 专家）、纯风格问题、`.logistics/` 与 `docs/logistics/` 下的工具产物、仓库根未跟踪的 `ship-*.log`。

## 建议修复顺序

1. **M11（Electron 过期 token 路径）**：先删恒失败分支，避免后续在错误前提上继续加代码。
2. **M07 / M08（死代码清零）**：语言包与 4 个前端模块，先由产品裁决再删，删完测试面收窄。
3. **M04 / M05（两个循环依赖）**：包级互指与前端唯一环，改动不大但能立刻恢复可独立测试的边界。
4. **M06（语音白名单单源化）**：先加一致性测试兜底，再收成接口/生成物。
5. **M01 / M02（重复逻辑收敛）**：健康探测与消息时间，机械改动，风险低。
6. **M09 / M10 / M12（仓库卫生）**：脚本目录归位、一次性脚本删除、admin 依赖清理，可放同一批清理提交。
7. **M03（backend-agent main.py 拆分）**：收益最大但风险最高，放最后，先补路由契约快照。

## 跨专家转交项

- **security**：`backend/scripts/_vault_export.tsv` 虽为 0 字节且无内容，但“凭据导出文件被跟踪”的模式值得确认历史提交里是否曾含真实内容；另外 M11 涉及的 token 路径属认证面，删除前请安全侧确认无隐式依赖。
- **reliability**：M01 的健康探测门控不一致，可能表现为“部分能力静默跳过”，与失败可见性相关；M11 的 `pullRendererAuthToken` 恒失败但不报错，属静默降级。
- **correctness**：M06 双份白名单的漂移会直接造成“可拨打但被拒”的行为差异，建议与后端契约一起复核。
- **perf**：M03 拆分 main.py 时若顺带调路由装配顺序，请性能侧确认冷启动变化；M12 删除 echarts 预期减小 admin 安装体积。
