# 可维护性审查报告

> 审查对象：予念 YuNian（PC 端）全项目 · 1100 文件 / 135,978 行 / 12 个审查单元
> 审查口径：只做 maintainability（简化、重复、过度抽象、死代码、未用依赖、孤儿文件），**行为必须保持**
> 结论：**11 条发现**（simplify 5 / cleanup 6），severity 全部 ≤ medium，无 critical / high；所有发现均为 `plan`

---

## 一、范围与覆盖

本次审查的是**整个项目**，不是 diff，也不是最近一次提交。所有 12 个单元都做了检查，状态与理由如下：

| 单元 | 范围 | 状态 | 实读文件 | 说明 |
|---|---|---|---|---|
| u01 | backend/lianyu-service (1/3) | reviewed | 9 | rules 包 4 文件全读 + 包外引用扫描；MemoryWriter、CharacterService |
| u02 | backend/lianyu-service (2/3) | sampled | 5 | 抽查 moments / relationship / notification 调度器与 config 包 |
| u03 | frontend/src (1/2) | sampled | 6 | 全量同构窗口扫描 + 导出扫描，重点读粒子组件与三个页面 |
| u04 | scripts (1/2) | reviewed | 12 | 97 个 `_` 脚本全量引用扫描 + diag/ops/smoke 目录逐项核对 |
| u05 | backend/lianyu-dao | reviewed | 8 | 57 个 Flyway 迁移编号核对 + mapper/entity/V7 迁移精读 |
| u06 | backend-agent | reviewed | 7 | main.py(1278 行)、data_readers.py、memory/profile_context.py 及 golden |
| u07 | frontend/src (2/2) | sampled | 5 | 抽查 ChatPage.vue(2266 行)、GroupChatPage.vue(1500 行) |
| u08 | backend/lianyu-web | sampled | 4 | AgentRuntimeClient(436 行)、端口适配层 |
| u09 | frontend/electron | reviewed | 8 | main.js(3077 行/71 IPC)、两个桥协调器与测试布局 |
| u10 | 其他（root / .github / admin） | reviewed | 6 | ci.yml、admin/package.json、admin/src 全部 5 个文件、.gitignore |
| u11 | backend 小模块合集 | sampled | 4 | lianyu-admin 32 + lianyu-service 27 的命名与安全配置抽查 |
| u12 | lianyu-app / common / installer / Pets | sampled | 4 | lianyu-common 15 文件清单与 util 类名、installer 16 文件清单 |

**没有 skipped 单元**。整体状态 `ok`。6 个 sampled 单元都做了全量模式扫描（同构窗口聚类 / 导出引用 / 类名孤儿扫描 / 依赖 import 扫描），只是没有逐行通读，因此结论以"扫描 + 抽查"为证据边界，不能当作全量已读。

### 已排除的疑似问题（避免下一次重复劳动）

- `milvus_store.py` 看似零引用，实际被 `memory/relay.py:19` 与 `tools/memory_search_tool.py:20` 使用；
- lianyu-web 的 `ChatTurnAdapter` / `AgentImageAdapter` 文本零引用，但实现 Spring 端口接口，是注入装配；
- `User` 实体、`CharacterCitySettingsService.MODE_FICTIONAL` 带 `@Deprecated`，但承担历史数据识别职责且有测试引用；
- `toHttpError`、`registerDirectives` 等前端导出有内部调用；
- 根目录 `ship-*.log`、`config.json`、`artifacts/` 已在 `.gitignore` 内，不算污染。

---

## 二、发现

### backend-agent（u06）

#### 1. 记忆画像块的格式有两份实现，golden 测试锁的是生产未走的那一份 · medium · simplify/duplication · plan

- **位置**：`backend-agent/app/memory/profile_context.py:18`
- **证据**：`profile_context.py:18-23` 定义 `SECTION_LABELS`，`:39 build_profile_context` 渲染 `[用户画像]` / `[近期情绪线索]` / `[关系事件]` / `[专属仪式]` 四段块；但该模块**只被** `tests/test_profile_context.py` 与 `app/prompts/profile_context_golden.json` 引用。生产路径是 `data_readers.py:174-179` 的 `TYPE_LABELS` + `:182 _render_profile_block`（经 `:201 load_profile_block` 由 `:242 memory_block` 消费）。两份 label 元组逐字相同，分组、去重、拼接逻辑逐行同构。
- **影响**：记忆块格式没有单一事实源。改格式要改两处，而 golden 只保护生产未走的那份——测试全绿也挡不住生产格式漂移；后来者无法判断哪份权威。
- **修复摘要**：让 `_render_profile_block` 复用 `build_profile_context`，删掉重复的 `TYPE_LABELS` 与同构循环。

### frontend/src（u03 / u07）

#### 2. 登录/注册页与落地页各有一份几乎相同的粒子 canvas 动画 · low · simplify/duplication · plan

- **位置**：`frontend/src/components/auth/AuthParticles.vue:1`
- **证据**：`AuthParticles.vue`（187 行）与 `landing/LandingParticles.vue`（196 行）有 21 个相同的 10 行窗口，差异只在调参：Auth 多一个 MIST 色 `{r:140,g:180,b:220}`，粒子数 `40/58/78` 对 `36/52/72`，parallax `22/14` 对 `18/12`。引用点：`LoginPage.vue:9/126`、`RegisterPage.vue:9/146`、`LandingPage.vue:8/225`。
- **影响**：canvas 尺寸、DPR、resize 监听、清理逻辑都是两份；修一处渲染缺陷要改两个文件，极易只改一边。
- **修复摘要**：抽 `<ParticleCanvas :preset>`，差异收敛为两份 preset 常量。

> u07 另查了 `ChatPage.vue`(2266 行) 与 `GroupChatPage.vue`(1500 行)：21 个同构窗口经核对几乎全是 SCSS 样式块，属弱信号，**已查未作为发现上报**。

### frontend/electron（u09）

#### 3. QQ 桥与微信桥各有一套状态协调器 · medium · simplify/duplication · plan

- **位置**：`frontend/electron/qqBridge/qqBridgeCoordinator.js:1`
- **证据**：`qqBridgeCoordinator.js`（334 行）与 `wechatBridge/wechatBridgeCoordinator.js`（229 行）共享 12 个相同的 10 行窗口，覆盖 broadcast、Notification 封装、状态推送与 unwrap；qq 版额外有 `sendQqBridgeAlert(titleMap)` 处理 `kicked / disconnected / not_logged_in / restart_failed` 四种告警。两者各有一套 `__tests__`。
- **影响**：桥接状态机有两套并行实现，修 QQ 侧的断线重连不会自动修到微信侧；新增第三种通道只能再拷一份。
- **修复摘要**：抽 `BridgeCoordinatorBase`，各通道只注入告警文案表与协议适配器。

#### 4. Electron 主进程入口 3077 行、71 个 IPC 处理器挤在一个文件 · medium · simplify/god-module · plan

- **位置**：`frontend/electron/main.js:1`
- **证据**：`main.js` 共 3077 行，含 71 处 `ipcMain.handle` / `ipcMain.on`。同目录已按域拆出 `updater/`、`qqBridge/`、`wechatBridge/`、`napcatRuntime/`、`starter/` 等 20+ 模块，但窗口创建、托盘、快捷键、更新检查、桥接启动、日志装配仍全在入口文件。
- **影响**：任何 IPC 变更都要在三千行里定位，改动面与回归面强耦合；单测只能整文件加载 `main.js`。
- **修复摘要**：`main.js` 收敛为组合根，按域拆 `ipc/<domain>.js`，通道名保持不动（属渲染端契约）。

> `backend-agent/app/main.py`（1278 行 / 25 个路由）同属上帝模块，严重度更低，为不超 12 条上限未单列，建议与本条同批处理。

### scripts（u04）

#### 5. 一次性生成脚本把整份 Vue 组件内嵌在字符串里 · low · simplify/duplication · plan

- **位置**：`scripts/ops/_write_theme_picker.py:3`
- **证据**：`:3` 以 `CONTENT = r'''<template> …'''` 内嵌整份组件，`:275` 还在做 `CONTENT.replace('g ap:', 'gap:')` 这种字符串补丁；与 `frontend/src/components/ThemeColorPicker.vue`（270 行）有 149 个相同的 10 行窗口。`git grep _write_theme_picker` 全仓零引用。
- **影响**：组件此后继续演进，谁再跑一次脚本就会用旧副本覆盖 `ThemeColorPicker.vue`；副本与产物的差异只能肉眼比对。
- **修复摘要**：删除脚本，或改为只输出模板骨架。

#### 6. 97 个 `_` 前缀一次性脚本入库，83 个零引用；`scripts/diag` 被 gitignore 却仍被跟踪 · medium · cleanup/orphan-file · plan

- **位置**：`scripts/diag/_diag_square_images.py:1`
- **证据**：
  - `git ls-files` 命中 97 个 `_` 前缀一次性脚本（`scripts/` 根级 60、`scripts/diag/` 20、`scripts/ops/` 13、`scripts/smoke/` 4），其中 **83 个**在仓库（含 `local/` 与 `docs/`）零引用；
  - `AGENTS.md §3.1.4` 明写一次性排查脚本应"带 `_` 前缀**且不提交**"；
  - `.gitignore:106-107` 整目录忽略 `scripts/diag/`（注释 *One-off cloud incident diag/fix scripts*），但该目录仍有 **21 个文件被跟踪**——ignore 对已跟踪文件无效；
  - `scripts/gen_pet_fixed_voices.py` 的 docstring 自述是 `scripts/diag/_gen_pet_fixed_voices.py` 的可版本化副本，两份 231 个相同窗口；
  - `scripts/_repair_flyway_v31.py`、`scripts/_repair_flyway_v34.py`、`scripts/ops/_repair_flyway_v30_mark_applied.py`、`scripts/ops/_repair_flyway_v34_mark_applied.py` 四份近似重复（25 个相同窗口），都硬编码 `HOST="156.233.228.18"` 并走 paramiko + mysql 直连，同样零引用；当前迁移已到 `V57__relationship_trust_anomaly_repair.sql`。
- **影响**：生产主机地址与直连运维脚本长期驻留仓库；`scripts/` 已无法区分"发布流水线"与"某次事故的临时探针"，新人（和 Agent）无法判断哪些可删；diag 的 ignore 规则事实上失效，反而误导后来者。
- **修复摘要**：三堆分流（流水线依赖转正并进 README / 事故留档移 docs 或删除 / 重复对只留一份），对 `scripts/diag/` 执行 `git rm --cached` 让 ignore 生效，硬编码 IP 改环境变量。
- **注意**：`scripts/_cloud_deploy_pull.py` 被 `local/ship-release.ps1` 与 `local/README.md` 引用，属**流水线依赖**，不能当作零引用脚本删除。

### backend/lianyu-service（u01 / u02）+ lianyu-dao（u05）

#### 7. `com.lianyu.service.rules` 整套 prompt 规则抽象无实现、无调用方 · medium · cleanup/dead-code · plan

- **位置**：`backend/lianyu-service/src/main/java/com/lianyu/service/rules/PromptRuleEngine.java:1`
- **证据**：**CONFIRMED_DEAD**。目录下只有 `PromptRuleContext.java`、`PromptRuleEngine.java`、`PromptRuleHook.java`、`PromptRuleSlot.java` 4 个文件，另有**空的** `hooks/` 子目录（`git ls-files` 只列出这 4 个文件）；`git grep` 显示包外零引用、零测试引用。`PromptRuleEngine.render(PromptRuleSlot, PromptRuleContext)` 依赖注入 `List<PromptRuleHook>`，但全仓没有任何 `PromptRuleHook` 实现类。
- **影响**：这套抽象正对 `AGENTS.md` 的红线（Java 不许拼 prompt）。留下同名 seam 会诱使后来者把规则写回 Java，而 `JavaAiLogicRatchetTest` 只查 prompt 文本字面量与已知 AI 类，**查不到这层抽象**，红线形同虚设。
- **修复摘要**：整包删除（含空 `hooks/`），Python 侧 `prompt_pipeline` 保持唯一规则入口；删除后跑 AI 棘轮确认仍为 `0/0/0`。

#### 8. `CharacterStickerMapper` 与实体全仓零引用，V7 建的两张表无读写方 · low · cleanup/dead-code · plan

- **位置**：`backend/lianyu-dao/src/main/java/com/lianyu/dao/mapper/CharacterStickerMapper.java:8`
- **证据**：**CONFIRMED_DEAD**。`CharacterStickerMapper`（`@Mapper interface … extends BaseMapper<CharacterSticker>`）与 `entity/CharacterSticker.java` 全仓零引用——`git grep CharacterSticker` 只命中自身定义与 `import`；`frontend/src`、`frontend/electron` 中 grep `"sticker"` 零命中；`V7__character_sticker.sql` 创建的 `character_sticker_job`、`character_sticker` 两张表在 dao 的 SQL/XML 中没有任何 select/insert。MyBatis 会自动注册该 mapper，但没有任何调用方。
- **影响**：死表 + 死 Mapper 让人误判"贴纸功能还在维护中"，数据库 schema 与代码能力脱节。
- **修复摘要**：确认生产库无写入后删 mapper 与实体，另起 `V58__drop_character_sticker.sql`（ASCII-only）drop 两张表。

#### 9. 已被判定有害并 `@Deprecated` 的 `deleteVectors` 仍是唯一调用方在走的路径 · medium · cleanup/deprecated-path · plan

- **位置**：`backend/lianyu-service/src/main/java/com/lianyu/service/memory/MemoryWriter.java:184`
- **证据**：`MemoryWriter.java:180-187` 的 javadoc 明写*"直接用 vecId 删除会绕过 outbox：Milvus 失败时删除意图永久丢失，留下「行已删、向量仍在」的幽灵记忆"*，方法带 `@Deprecated`，替代品为 `deleteVectorForMemory(Long, String)`；但 `CharacterService.java:214` 仍在调用 `memoryWriter.deleteVectors(...)`。
- **影响**：两套删除语义并存且没有收口。删角色时记忆向量与元数据可能不一致，后人看到 `@Deprecated` 也不敢动（唯一调用方就在隔壁），弃用形同虚设。
- **修复摘要**：把 `CharacterService` 的删除流程迁到 `deleteVectorForMemory`（走 outbox），确认等价后删除弃用方法。

### root / CI（u10）

#### 10. admin 声明了四个从未 import 的重依赖 · low · cleanup/unused-dependency · plan

- **位置**：`admin/package.json:10`
- **证据**：`dependencies` 含 `@element-plus/icons-vue ^2.3.1`、`@tanstack/vue-table ^8.21.3`、`echarts ^6.1.0`、`vue-router ^4.4.5`。但 `admin/src` 一共只有 5 个文件（`main.js` 8 行、`App.vue` 与 `ManagementPage.vue` 各 15 行、`api.js` 9 行、`menu.js` 8 行），实际 import 只有 `vue/pinia/element-plus/axios`；`main.js` 只注册 `ElementPlus` 与两个 css，既没有 `createRouter`，也没有 echarts 与图标注册。
- **影响**：四个包白占安装体积与 OSV 审计面（`ci.yml` 的 deps-audit 会扫 admin），也让接手者误以为后台已有图表与路由能力。
- **修复摘要**：`npm --prefix admin uninstall` 这四个包并重锁 lockfile。

#### 11. CI 只跑了六条门禁里的两条，两个棘轮脚本完全不在流水线里 · medium · cleanup/config-drift · plan

- **位置**：`.github/workflows/ci.yml:39`
- **证据**：CI 只有 backend 的 `mvn -B -ntp test`（:22）与 frontend 的 `npx vitest run`（:39）。`AGENTS.md §4` 声明的门禁里，`pytest backend-agent`、admin 测试、`npx vite build`、`scripts/check-java-ai-free.ps1`、`python scripts/check_release_asset_contract.py` 全部缺席；且 `:39` 没有 `--no-file-parallelism`，与文档"前端测试串行跑（既有 updater 并发用例在满载并行下会偶发红）"直接冲突。
- **影响**：两道棘轮（Java 零 AI 逻辑、发布资产契约）只在本地手工跑，PR 上不生效；Python 1222+ 测试与 admin 完全无 CI 保护；CI 跑法还与文档不一致，已知 flake 会被当成真红并稀释信号。
- **修复摘要**：补 python / admin / ratchet 三个 job，前端加 `--no-file-parallelism`，并把 job 列表回写 `AGENTS.md §4` 保持单一事实源。

### 已查未发现问题的区域

- **backend/lianyu-dao 的迁移**：57 个 Flyway 迁移编号连续、无重号（`V30__version_gap_placeholder.sql` 为 `SELECT 1;` 占位属有意设计），除上文贴纸死表外无孤儿表。
- **backend/lianyu-common（15 文件）与 lianyu-app**：util 类（`TextUtils`、`UserInputSanitizer`、`OutboundUrlValidator` 等）均有实际调用，未见未用工具类或重复实现。
- **installer（16 文件）/ Pets（6 文件）**：结构紧凑，未见重复实现。
- **backend/lianyu-web 适配层**：零文本引用的类多为端口实现，属正常装配。
- **backend 小模块合集（lianyu-admin / lianyu-service 27 文件）**：命名与安全配置一致，未见死代码。
- **全仓 TODO/FIXME**：几乎没有（仅 `backend/lianyu-web/.../AgentSseParser.java` 一处），说明"遗留待办注释"类问题不存在。

---

## 三、未验证 / 抽样说明（重要）

以下内容**只是 sampled 或扫描结论，不等于全量已读**：

1. **frontend/src 的导出引用扫描不可靠**：478 个导出中有 105 个在模块外零引用，但动态 `import()`、模板内引用、`defineExpose` 都会漏检，因此**没有**把任何前端导出列为死代码（包括 `splitAssistantReplyForDisplay`、`APP_NAME/APP_NAME_EN`、`api/ai.js getVault` 这些疑似项）。
2. **Java 类名孤儿扫描命中 140 个文件，绝大多数是误报**（`@RestController` / `@Scheduled` / `@Mapper` 等注解装配，名称不出现在别处是正常的）。只保留了 `CharacterStickerMapper` 这一条有二次证据（实体 + 前端 + SQL 三处都空）的。
3. **`.env.example`（48 键）与代码读取值的差异**未上报：扫描器漏掉 yml 占位符与 Docker Compose 注入，结论不可靠。
4. **u02 / u03 / u07 / u08 / u11 / u12 是抽样**：做了全量模式扫描（同构窗口聚类、引用扫描、依赖 import 扫描）后抽查关键文件，未逐行通读，理论上仍可能藏有局部重复。
5. **未执行全量测试**（`mvn` / `pytest` / `npm test`）：本次为只读审查，测试命令仅作为每条修复的验证入口写在 handoff 的 `fix.tests` 里。

---

## 四、建议修复顺序

按"风险 / 成本"排序，前两步几乎零风险且立刻降低认知负担：

1. **删死代码**（发现 7、8）：`rules` 包与 `CharacterSticker` 是确证死代码，删除后 `scripts/check-java-ai-free.ps1` 与 `mvn test` 即可验证，不动任何运行时行为。
2. **补 CI 门禁**（发现 11）：把 `pytest backend-agent`、admin test 与两个棘轮脚本接进流水线——先有护栏，再动后两步的重复代码，避免重构期间失去保护。
3. **收口双事实源**（发现 1、6）：记忆画像块统一到 `build_profile_context`；`scripts/` 按"流水线依赖 / 事故留档 / 重复对"三堆分流，`scripts/diag/` 执行 `git rm --cached`。
4. **清理弃用路径与依赖**（发现 9、10）：`deleteVectors` 迁到 outbox 路径（涉及记忆一致性，需配套测试）；admin 移除四个未用依赖。
5. **拆分重复实现与上帝模块**（发现 2、3、4、5）：抽 `ParticleCanvas`、`BridgeCoordinatorBase`，拆 `electron/main.js`。这几项改动面最大，建议在护栏到位、且与功能改动分开的独立提交里做。

---

## 五、跨专家转交项

| 转交对象 | 内容 | 理由 |
|---|---|---|
| **correctness** | 发现 9：`CharacterService.java:214` 走的是绕过 outbox 的删除路径，删角色时可能出现"行已删、向量仍在" | 这是语义正确性问题，不只是可维护性；本报告只从"两套实现并存"角度提出 |
| **reliability** | 发现 11：CI 不跑两个棘轮脚本，红线失守时没有任何告警 | 属回归保护缺口，需要观测/门禁视角评估 |
| **security** | 发现 6：`scripts/_repair_flyway_*.py` 等脚本硬编码生产主机 `156.233.228.18` 并走 paramiko 直连 mysql | 生产拓扑与运维凭据相关的暴露面，应由安全专家判定严重度 |
| **perf** | 发现 10：admin 引入 echarts 等四个未用重依赖，`ci.yml` 的 deps-audit 会扫到 | 若后台将来真做图表，需要先决定按需引入策略 |

---

*本报告仅报告有证据的问题；未修改任何源码，未提交。*
