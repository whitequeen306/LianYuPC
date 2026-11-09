# 可维护性审查报告 · 予念（YuNian）PC 端

**一句话结论**：全项目 12 个审查单元跑完可维护性维度，确认 12 项维护债（无 high/critical，8 中 4 低）：最值得先处理的是「同一领域规则跨层双份实现」和「一次性运维脚本与内嵌副本大量入库」两类——它们不是当前缺陷，但会让下一次改动必然漏改或静默回退。

## 1. 范围

| 项 | 值 |
|---|---|
| 工作区 | `C:/Users/hp/Desktop/LianYu-PC` |
| Git | 分支 `main`，HEAD `c652e14`（fix(relationship): 修正被刷高的历史信任分（V57）） |
| 范围类型 | **全项目**（1100 个文件 / 12 个审查单元 / 135978 行；tracked 1506，按规则排除 406） |
| 语言构成 | Java 450 · JavaScript 252 · Python 220 · SQL 60 · Vue 57 |
| 本次专家 | maintainability（1 位，section：simplify / cleanup） |
| 未运行 | perf（可选专家未选）、security / correctness / reliability |

只读审查：未修改源码、未提交、未删除任何文件。可维护性按纪律最高只到 medium，不使用 high/critical 来表达「看着难受」。

## 2. 单元 × 专家覆盖矩阵

| 单元 | 标签 | maintainability | 说明 |
|---|---|---|---|
| u01 | backend/lianyu-service (1/3) | reviewed | 会话/切分器/关系域逐一读 |
| u02 | backend/lianyu-service (2/3) | reviewed | 群聊、主动消息、朋友圈、摘要 |
| u03 | frontend/src (1/2) | reviewed | utils 与页面级重复块 |
| u04 | scripts (1/2) | reviewed | 脚本清点、硬编码常量、副本比对 |
| u05 | backend/lianyu-dao | reviewed | 实体↔Mapper 23:23 一对一，无孤儿 |
| u06 | backend-agent | sampled | 全模块 grep + Settings 59 字段逐字段读取点检查 |
| u07 | frontend/src (2/2) | reviewed | 组件与 composable 引用面 |
| u08 | backend/lianyu-web | sampled | agent 适配层按引用扫描 |
| u09 | frontend/electron | reviewed | 两个桥接协调器逐行对照 |
| u10 | 其他模块（(root)、.github、admin 等） | sampled | package.json / 工作流 / admin 壳 |
| u11 | backend（小模块合集 59 文件） | sampled | lianyu-admin / security 关键类零引用检测 |
| u12 | 其他模块（lianyu-app / common / backend 等） | sampled | 配置与迁移一致性 |

**0 skipped。** 7 reviewed / 5 sampled —— sampled 只做了模式扫描与关键文件精读，不等于全量逐行读过；具体缺口见 §5。

## 3. 按模块的发现

### 3.1 跨层重复：同一规则两处实现

**MNT-3894E · 中 · 回复气泡切分规则在 Java 与前端各写一遍，标记词表与阈值逐字重复**
`backend/lianyu-service/src/main/java/com/lianyu/service/ai/AssistantReplySplitter.java:20`

`AssistantReplySplitter.java:15` 的 `SENTENCE_SPLIT_MIN_CHARS = 40`、`:20` 的 8 个 `ATTEMPT_MARKERS`（现在就试/这就试/我去试/我试试/稍等/盯着/马上帮你/这就去）与 `:23` 的 13 个 `OUTCOME_MARKERS`，在 `frontend/src/utils/assistantReplySplit.js:96-101` 逐字重复；`groupComputerTaskAttemptAndOutcome` 算法也是两语言各写一份，两侧注释互相要求对方保持一致（Java 方法注释 ↔ JS `:103`）。此外 JS `:155` 的 `SPEAKING_STYLE_MAX_REPLIES`（8 种风格→条数）注释声称「与后端 `CharacterChatBehaviorResolver.StyleProfile` 一致」，但该映射只存在于 JS，Java 侧是 `profileForStyle` + 关键词调整。

影响：改阈值或加同义标记必须人工同步两种语言；漏改会让同一句回复在落库（Java 切完后逐条入库）与实时流（前端兜底切分）切成不同条数，表现为刷新前后气泡数变化。

修复：把阈值、两组词表、风格→条数收敛成一份契约文件（如 `scripts/contracts/reply_split.json`），两侧读同一份 + 同一组 golden 用例，CI 比对不一致即退出。

**MNT-EA332 · 中 · QQ 与微信桥接协调器各自复制同一套广播与 API 解包样板**
`frontend/electron/qqBridge/qqBridgeCoordinator.js:16`

`broadcast(channel,payload)`（`qqBridgeCoordinator.js:16-26`）与 `wechatBridgeCoordinator.js:17-27` 逐字相同；`unwrap`/`apiGet`/`apiPost`（`qq:55-73` ↔ `wechat:50-70`）也逐字相同，连 `timeoutMs: 15000/30000` 两个魔数、错误串 `api:${path} HTTP ${res?.status}` 与 `api:${path} code ${body.code}`、`body.code` 缺省直返 body 的分支都一致。

影响：超时、错误码格式、广播方式这三条隐性契约要改两处；任一侧漏改，QQ 与微信的失败语义就分叉，而错误串正是前端文案的依据。

修复：抽 `frontend/electron/shared/bridgeTransport.js`（`broadcastToWindows` / `unwrap` / `createApiClient(apiOrigin, authToken)`），两个协调器注入使用。

**MNT-8DDBC · 中 · 登录页与注册页验证码逻辑逐字重复约 45 行**
`frontend/src/pages/RegisterPage.vue:157`

`RegisterPage.vue:157-233` 与 `LoginPage.vue:137-183` 的 `captchaId`/`captchaImageSrc`/`captchaHint` 三 ref + `applyCaptchaResponse` + `onCaptchaImgError` + `refreshCaptcha` 逐字相同：同样剥 `data:image/png;base64,`、同样两句失败文案、同样 `ElMessage.error('无法连接服务器…')`。项目已有 26 个 composable（`useAuthPageGsap.js`、`useVoiceRecorder.js` 等），抽取是本仓既有惯例，不是新风格。

影响：验证码加载失败文案、base64 兜底、刷新时机要改两处；将来换成拼图/滑块验证码时两个页面各重写一遍，极易只改一处。

修复：抽 `frontend/src/composables/useCaptcha.js`，两个页面只保留表单差异。

### 3.2 单一事实来源被架空的配置与常量

**MNT-A8B9D · 中 · admin 权限目录类无人使用，鉴权全部裸写权限字符串**
`backend/lianyu-admin/src/main/java/com/lianyu/admin/identity/AdminPermissionCatalog.java:7`

该类只有 `SUPER_ADMIN`（:6）与 `ALL = Set.of("admin.manage","user.read","user.moderate","release.manage","announcement.manage","audit.read","system.health")`（:7），全仓零消费（构造器私有，也没有任何 import）。真实鉴权点全部裸字符串：`AdminManagementController.java:22/23/25/34/35/36` require("admin.manage")、`AdminOperationsController.java:23/28/31/35/41`、`AnnouncementController.java:20/28/29`、`AdminAuditController.java:19`、`AdminOverviewController.java:21`、`ReleaseController.java:19`、`GitHubReleaseController.java:12`、`ReleaseMetadataController.java:20`、`ReleaseService.java:20`。

影响：这个「权限清单」是死常量：加权限不生效，改字符串不与控制器同步。权限串拼错（如 `user.modrate`）编译期不报错，只静默变成「无人拥有」的 403，排障时看不出是拼写问题。

修复：让 `AdminPermissionCatalog` 成为唯一来源，控制器引用其常量，并用一个测试断言 `ALL` 覆盖全部常量。

**MNT-E3F97 · 中 · `lianyu.ai.context-window` 在 4 处注解重复默认值，`AiConfig` 还留着无人读取的同名字段**
`backend/lianyu-ai/src/main/java/com/lianyu/ai/config/AiConfig.java:21`

`backend/lianyu-app/src/main/resources/application.yml:204` 写 `context-window: 32`，但 4 个类各写一遍 `@Value("${lianyu.ai.context-window:20}")`：`ConversationService.java:98`、`GroupChatService.java:89`、`ProactiveMessageService.java:197`、`MomentsService.java:96`；`AiConfig.java:21` 又声明 `private int contextWindow = 20`（`@ConfigurationProperties(prefix="lianyu.ai")`），全仓无读取点。副作用：这些字段只能反射改——`ConversationRelationshipFlowTest.java:91-93`、`ConversationServiceGetSummaryTest.java:83`、`ConversationServiceStreamErrorTest.java:103` 都在 `setAccessible`。

影响：同一属性 5 处声明、2 个默认值来源（yml 32 / 注解 20）；调默认值要改 4 处，漏改不报错，只会让某个场景（朋友圈 12 条上限、主动消息、群聊）用上不同窗口；测试与字段名耦合，重命名即静默破坏。

修复：收敛成 `@ConfigurationProperties` 的 `ChatProperties` 构造注入，删掉 `AiConfig.contextWindow` 与 4 处重复注解。

### 3.3 死代码与未接线设施

**MNT-76EE8 · 中 · 前端 313 行零引用文件：截图分享工具与 antiDebug**
`frontend/src/utils/captureChatShareScreenshot.js:128`

`captureChatShareScreenshot.js`（220 行，唯一导出 `captureChatShareScreenshot(container, selectedIds)` 在 `:128`）在 `frontend/src`、`frontend/electron` 全量检索只命中自身定义；它依赖的 `renderChatShareSnapshot.js`、`communityShareDraft.js`、`components/community/ChatShareSnapshot.vue` 在仓内都已不存在——截图分享功能整体删除后的遗留。`antiDebug.js`（93 行）同样零 import，仅被 `frontend/src/utils/logger.js:4` 的注释提到「生产环境 console.* 被 antiDebug 吞掉」，`docs/reusable-capabilities/electron-client-hardening-zh.md:195` 也仍写着「antiDebug 覆写」。

影响：313 行无调用代码留在树里，且读起来像正在生效的保护——按注释做的排障或安全判断都会错（生产 `console` 其实没有被这个模块接管）。

修复：删两个文件并修正 `logger.js:4` 注释与文档表述。

**MNT-A2683 · 中 · security 模块的字段加密设施从未接线**
`backend/lianyu-security/src/main/java/com/lianyu/security/annotation/EncryptedField.java:8`

`EncryptedField`（9 行注解）全仓只命中自身定义；`EncryptedStringTypeHandler`（40 行，基于 `JasyptUtil`）除自身定义外只出现在 `docs/security-coupling-map-zh.md:24/161` 的文档里。仓内没有任何 MyBatis `type-handlers-package` / `typeHandlers` 配置，也没有实体用 `@TableField(typeHandler=…)` 挂它（`lianyu-dao/.../entity/Character.java:19` 挂的是 `JacksonTypeHandler`，属不同用途）。

影响：安全模块里摆着一套从不生效的字段加密，读者会以为某些敏感列已加密；按这个假设做数据分级或密钥轮换会得出错误结论——而 `docs/security-coupling-map-zh.md` 恰恰把 `LIANYU_MASTER_KEY` 轮换与 `EncryptedStringTypeHandler` 绑在一起描述。

修复：二选一留档——真正接到需要加密的列上，或整组删除并在 `docs/security-coupling-map-zh.md` 更正（顺带处理该文档与 `JasyptUtil` 的关系，见 deferred）。

### 3.4 脚本与仓库卫生

**MNT-20376 · 中 · scripts/ 根目录 55 个 `_` 前缀一次性脚本入库，84 个脚本硬编码生产 IP**
`scripts/_cloud_verify.py:1`

`git ls-files 'scripts/*.py'` = 114 个，其中 `scripts/_*.py`（根级）55 个，多为同一份「连生产机 → 拉日志/校验/热修」的变体（`_cloud_verify*.py`、`_cloud_deploy_*.py`、`_repair_flyway_v31.py`、`_server_disk_*.py` 等），`scripts/diag/` 下另有同类；84 个 tracked 脚本含 `156.233.228.18`（全仓 tracked 88 个文件含该 IP）。`AGENTS.md §3.1` 明确要求「一次性排查脚本一律放 `local/` 或带 `_` 前缀且不提交」。

影响：权威发版入口是 gitignored 的 `local/ship-release.ps1`，仓库里 55 份一次性副本让「哪份是权威」无法判断；生产 IP 与 SSH 用法散落 84 个文件，换机要改 84 处，且这些脚本无测试、无引用关系，改错不会暴露。

修复：仍在用的合并成少量带参数的运维命令，其余移出仓库；IP 与凭据统一从 `.env` 读。

**MNT-DB86B · 低 · `scripts/ops/_write_theme_picker.py` 用 270 行内嵌副本覆盖活跃组件，副本只靠一条字符串 replace 维持正确**
`scripts/ops/_write_theme_picker.py:3`（写文件在 `:277-278`）

> **主编复核更正**：子代理称「内嵌副本与组件有 5 行坏 CSS 差异、重跑会把坏样式写回」，这一条不成立。脚本 `:275` 有 `CONTENT = CONTENT.replace('g ap:', 'gap:')`，写入前自行把 6 处 `g ap:`（`:167/:175/:196/:219/:245`）修成 `gap:`；我把内嵌 CONTENT 按该 replace 规范化后与 `frontend/src/components/ThemeColorPicker.vue` 逐行比对，270 行 vs 270 行**完全一致**（diff=0）。因此严重度从 medium 降为 low。

仍成立的部分：脚本以字面量内嵌整个组件正文，`dest.write_text(CONTENT)` 直接覆盖 `frontend/src/components/ThemeColorPicker.vue`（该组件被 `frontend/src/components/AppHeader.vue:27` 使用），没有任何一致性检查；副本本身需要那条 `replace` 兜底，说明它历史上已漂移过。

影响：真实风险是「静默回退人工改动」——组件发布后任何手工修改，只要没同步进脚本，重跑一次就被 270 行字面量覆盖，不报错、不留 diff 提示。

修复：删除脚本，或改为「只检查一致性、不一致就非零退出」，并禁止直接覆盖活跃组件。

**MNT-3BE34 · 低 · 宠物音色与图像脚本各有多份副本，其中一份逐字节相同**
`scripts/enroll_pet_voices.py:1`

`scripts/enroll_pet_voices.py` 与 `Pets/skills/hatch-lianyu-pet/scripts/enroll_pet_voices.py` 逐字节相同（md5 均 `b11be11664cb0a5ae832483e5ffbac0b`，2871 字节 / 90 行，均被跟踪）；`scripts/gen_pet_fixed_voices.py`（296 行）与 `scripts/diag/_gen_pet_fixed_voices.py`（271 行）是同族新旧副本；`Pets/skills/.../gen_pet_images.py`（355 行）与 `scripts/gen_elysia_openai.py`（331 行）开头约 40 行几乎逐字相同。

影响：同一流程两个可执行入口，跑哪份决定产物是否一致；两条流程都直接写生产素材（音色文件、立绘），产物不一致很难回溯。

修复：以 `Pets/skills/hatch-lianyu-pet/scripts` 为唯一实现，`scripts/` 下副本改为调用或删除。

### 3.5 依赖与配置

**MNT-72E73 · 低 · frontend 与 admin 共 6 个依赖在源码中零引用**
`frontend/package.json:37`

`@vue/test-utils`（:37）、`vite-plugin-electron-renderer`（:44）在 `frontend/src`、`frontend/electron` 全量检索 0 引用（488+ 测试都不导入 test-utils；`vite.config.js` 只用 `vite-plugin-electron/simple`）；`admin/package.json:10` 的 `@tanstack/vue-table`、`echarts`、`vue-router`、`@element-plus/icons-vue` 在 `admin/src`、`admin/electron` 同样 0 引用（`concurrently`/`wait-on` 只出现在 `electron:dev` 脚本串里，按已用处理）。

影响：安装体积、lockfile 噪声与审计面变大；admin 装了 `echarts`/`@tanstack/vue-table` 却无页面使用，后续开发容易误以为已有表格/图表能力。

修复：移除 6 项并重算 lockfile；`element-plus`/`axios`/`pinia`/`sass` 保留。

**MNT-C52D4 · 低 · backend-agent Settings 有 4 个键只定义、无人读取（含已退役的 `shadow_mode`）**
`backend-agent/app/config.py:108`

Settings 共 59 字段，`multimodal_max_tokens`（:24）、`internal_signature_window_seconds`（:57）、`memory_recent_ttl_minutes`（:76）、`shadow_mode`（:108）在 `backend-agent/app`、`tests`、`docker-compose.yml`、`.env` 示例与 docs 中除定义行外 0 命中。`shadow_mode` 上方注释仍写「影子对比模式：true 时记录请求/响应供新旧链路 diff」，而 OFF/SHADOW 已按 `docs/refactor-2026-10/off-retirement-plan.md` 退役。

影响：三个可配项写进 Settings 却无消费者——运维以为调 `internal_signature_window_seconds` 能改签名时间窗、调 `multimodal_max_tokens` 能改识图预算、调 `memory_recent_ttl_minutes` 能改近期缓存，实际全部无效，排障会朝错误方向查；`shadow_mode` 则让人以为影子链路还在。

修复：删掉 4 个键，或真正接线并补读取点与测试。

## 4. 跨单元的系统性问题

1. **同一规则存在两个「都声称是权威」的副本**：切分规则 Java/JS 双份（MNT-3894E）、桥接样板两份（MNT-EA332）、验证码块两份（MNT-8DDBC）、权限串在控制器裸写而目录类无人用（MNT-A8B9D）、`context-window` 五处声明（MNT-E3F97）。共同点是**没有一处能被机器验证同步**——注释里写着「须保持一致」，但没有任何检查会红。
2. **「看起来在生效」的设施**：`antiDebug` 从未被加载（MNT-76EE8）、字段加密 handler 从未接线（MNT-A2683）、4 个配置键无人读取（MNT-C52D4）、权限目录类无人引用（MNT-A8B9D）。读者会据此做出错误判断，这比缺失更贵。
3. **脚本目录失去了「哪个是权威」的信息**：55 个一次性脚本 + 84 个硬编码 IP（MNT-20376）、同一个生成流程多份副本（MNT-3BE34）、会覆盖活跃组件的生成器（MNT-DB86B）。与 `AGENTS.md §3.1` 的纪律直接冲突。
4. **可测试性被私有字段与内联 IO 拖住**：`context-window` 只能反射注入（MNT-E3F97），生成脚本把 IO 与字面量写死在同一文件（MNT-DB86B）——想加一条「不覆盖源文件」的测试都没有接缝。

## 5. 建议修复顺序

**第一批（低成本、立刻减少误判）**
1. MNT-76EE8：删两个零引用文件 + 改 `logger.js:4` 注释（纯删除，先跑前端门禁）。
2. MNT-A8B9D：权限串收敛到 `AdminPermissionCatalog`（纯替换 + 一条断言测试）。
3. MNT-C52D4：删 4 个无消费者配置键（或接线），同步 README。

**第二批（同一根因，按文件合并）**
4. MNT-E3F97 + MNT-3894E 都涉及「规则分散」：`context-window` 先收敛成 `ChatProperties`；切分规则契约化工作量大，单独排期，但可以先把 `ATTEMPT/OUTCOME` 词表抽成 JSON 让两侧共读。
5. MNT-EA332：抽 `bridgeTransport.js`（两个文件一次改完，注意 `timeoutMs` 魔数同时登记）。
6. MNT-8DDBC：`useCaptcha.js`（两个页面一次改完）。

**第三批（仓库卫生，需要人确认，不能自动删）**
7. MNT-20376 / MNT-3BE34 / MNT-DB86B：先确认 `local/ship-release.ps1` 实际调用哪些脚本，再决定保留哪一份；`_write_theme_picker.py` 建议直接删（组件已是唯一正确来源）。
8. MNT-A2683：字段加密设施要么接线要么删除，并同步 `docs/security-coupling-map-zh.md`。
9. MNT-72E73：删 6 个依赖并重算 lockfile（放最后，避免与其他改动混在一个提交）。

**全部 12 条都是 `plan`**：涉及删文件、改依赖、跨模块抽取与公共 API，按纪律不进 `/logi-fix` 的 direct 名单。

## 6. 覆盖缺口与未验证项

- **sampled 的 5 个单元不是全量已读**：u06（backend-agent 102 文件只做全模块 grep + Settings 字段检查）、u08（lianyu-web 按引用扫描）、u10（admin 壳与工作流抽样）、u11（小模块零引用检测）、u12（配置与迁移一致性）。这些单元里的重复/死代码可能没被看见。
- **跨文件近似重复检测的阈值**：以 6 行 shingle / Jaccard 0.4 扫描 backend 全部 main Java 与 frontend src/electron，0 命中；阈值以下的「近似但不同」重复（例如 `LoginPage`/`RegisterPage` 的 `validatePassword`，相似度 0.263）未被判为 finding，已转 deferred。
- **未验证的怀疑**：`character_sticker` 表是否仍被业务读取（决定 `CharacterStickerMapper` 是否死代码）；`local/ship-release.ps1` 的脚本清单（`local/` 被 gitignore）；`internal_signature_window_seconds` 是否对应一处硬编码签名时间窗（可能是正确性问题）。
- **本次只跑 maintainability**：security / correctness / reliability 没有跑，不代表干净。已知的跨维度线索在 §7。
- **性能维度未运行**：需要时执行 `/logi-perf`。没检查不等于没问题。

## 7. 跨专家 deferred（转交项）

| 来源 | 目标 | 文件 | 原因 |
|---|---|---|---|
| maintainability | （未指定，建议 correctness） | `backend/lianyu-dao/src/main/java/com/lianyu/dao/mapper/CharacterStickerMapper.java` | 实体与 Mapper 23:23 一对一，但未确认 `character_sticker` 表是否仍被业务读取，不足以判死代码 |
| maintainability | （未指定，建议 maintainability 下轮） | `frontend/src/pages/LoginPage.vue` / `RegisterPage.vue` | `validatePassword`/`validateConfirm` 相似度 0.263，未逐行确认可否复用，本轮只报了逐字相同的验证码块 |
| maintainability | （未指定） | `AGENTS.md` §5.3 | 文档写「当前最新 V55」，实际已到 V57（V1–V57 无缺号无重号），属文档漂移，未达报告阈值 |
| maintainability | （未指定，需维护者确认） | `scripts/` | 哪些一次性脚本仍被 `local/ship-release.ps1` 调用无法从仓库确认（`local/` 被 gitignore），删除前须核对 |
| maintainability | （未指定，建议 correctness） | `backend-agent/app/config.py:57` | `internal_signature_window_seconds` 是否对应一处硬编码的签名时间窗未深查，本轮只按「死配置」记录 |

> 本报告不含健康分、评分、基线或门禁结论。本次为单模块、非默认专家组合（仅 maintainability，默认四专家未齐），**未更新 latest**。
