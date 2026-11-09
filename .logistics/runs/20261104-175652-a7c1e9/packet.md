# 后勤主编材料 · 20261104-175652-a7c1e9

> 这是给主会话模型（主编）的候选材料，不是最终报告。
> 去重、定级、取舍、模块组织由主编决定；机器只负责收集与校验。

## 运行
- 根目录：`C:/Users/hp/Desktop/LianYu-PC`
- 范围：1100 个文件 / 12 个审查单元 / 135978 行
- 测试命令：（未检测到）

## 覆盖
- 预期单元 / 专家：maintainability
- 已收到：maintainability
- 缺失：（无）
- 无效：（无）
- 失败：（无）
- partial：（无）
- 可选未选：perf

## 单元覆盖矩阵

| 单元 | 标签 | maintainability | perf |
| --- | --- | --- | --- |
| u01 | backend/lianyu-service (1/3) | reviewed | missing |
| u02 | backend/lianyu-service (2/3) | reviewed | missing |
| u03 | frontend/src (1/2) | reviewed | missing |
| u04 | scripts (1/2) | reviewed | missing |
| u05 | backend/lianyu-dao | reviewed | missing |
| u06 | backend-agent | sampled | missing |
| u07 | frontend/src (2/2) | reviewed | missing |
| u08 | backend/lianyu-web | sampled | missing |
| u09 | frontend/electron | reviewed | missing |
| u10 | 其他模块（(root)、.github、admin 等） | sampled | missing |
| u11 | backend（小模块合集 59 文件） | sampled | missing |
| u12 | 其他模块（backend/lianyu-app、backend/lianyu-common、backend 等） | sampled | missing |

## 专家摘要
### maintainability · ok
- 摘要：全仓 1100 文件 / 12 单元逐单元做了针对性扫描（跨文件近似重复检测、零引用类检测、孤儿文件与未用依赖检测、迁移与配置一致性检查），未跳过任何单元。主要问题集中在四处：一是一次性脚本与生产运维常量大量入库（55 个 scripts/ 根级 _ 前缀脚本、84 个脚本硬编码生产 IP 156.233.228.18），且 scripts/ops/_write_theme_picker.py 用内嵌副本覆盖已发布的 ThemeColorPicker.vue、当前内嵌副本已与组件产生 5 行坏 CSS 差异；二是同一领域规则跨层双份实现（回复气泡切分标记词表与阈值在 AssistantReplySplitter.java 与 assistantReplySplit.js 逐字重复、权限串在 admin 各控制器裸写而 AdminPermissionCatalog.ALL 无人使用、lianyu.ai.context-window 在 4 处注解重复默认值且 AiConfig 还留着一个无人读取的同名字段）；三是确认的死代码（前端 313 行无引用文件、security 模块整套从不生效的字段加密设施、backend-agent 4 个无人读取的配置键）；四是未用依赖 6 个（frontend 2 + admin 4）。共 12 条发现：simplify 5 条、cleanup 7 条，全部为 plan（涉及删文件、改依赖、跨模块抽取，需人判断）。
- sections：simplify=5 · cleanup=7
- notes：本轮为纯只读审查：未修改任何源码、未提交、未删除文件，仅写入本 handoff。工具限制：本机无 rg，全部扫描用 grep -rIn 与 python 临时脚本（未落盘）完成；跨文件近似重复检测阈值为 6 行 shingle、Jaccard 0.4（backend 全部 main Java 与 frontend src/electron 均 0 命中，故重复类问题集中在跨语言与页面样板）。全部 12 条发现的 severity 最高为 medium（无可用可复现证据支撑更高等级），kind 全为 plan：清理与删除类动作按纪律一律交人判断，本报告不提供 direct 修补。 | 主编规范化：子代理把 sections 写成 {count,status,notes} 对象，contract 要求整数，已按 count 转为整数并保留原文于 sectionsDetail。 | [simplify] status=checked notes=重点检查重复实现、过度抽象与第二事实来源：跨文件 6 行 shingle 近似重复检测覆盖 backend 全部 main Java 与 frontend src/electron（阈值 0.4，无命中），逐对细查了 Java/JS 切分器、两个桥接协调器、登录/注册页、admin 权限串、context-window 默认值。过度抽象方向未发现空壳包装层或未使用的接口。 | [cleanup] status=checked notes=重点检查死代码、孤儿文件、未用依赖与配置漂移：对全部 363 个 main Java 类做了零引用检测并按 Spring 组件/接口/注解过滤，对 src/utils、src/composables、src/components、electron 全部模块做了按导入语句的死文件扫描，对 frontend/admin 的 package.json 逐项做了引用计数检查，对 backend-agent Settings 59 个字段做了读取点检查，Flyway V1–V57 无缺号无重号、dao 实体与 Mapper 23:23 一对一无孤儿。
- 发现数：12
- 转交其他专家：5 条
  - → 未指定 · backend/lianyu-dao/src/main/java/com/lianyu/dao/mapper/CharacterStickerMapper.java：实体与 Mapper 为 23:23 一一对应，但未确认 character_sticker 表是否仍被业务读取，不足以判死代码，留待后续核对调用链。
  - → 未指定 · frontend/src/pages/LoginPage.vue 与 RegisterPage.vue 的表单校验规则（validatePassword/validateConfirm）相似度为 0.263，但未逐行确认是否可直接复用，故只报告了逐字相同的验证码块。
  - → 未指定 · AGENTS.md §5.3 写『当前最新 V55』，实际迁移已到 V57（V1–V57 无缺号、无重号）：属文档漂移，未达报告阈值，仅在此登记。
  - → 未指定 · scripts/ 下一次性脚本中哪几份仍被 local/ship-release.ps1 调用无法从仓库确认（local/ 被 gitignore），删除前需向维护者核对。
  - → 未指定 · backend-agent 的 internal_signature_window_seconds 是否对应一处硬编码的签名时间窗（潜在正确性问题）未深查，本轮只按『死配置』记录。

### perf · not-selected
- 可选专家，本次未选择（不是缺失）。

## 候选 findings
### MNT-3894E · [中] 回复气泡切分规则在 Java 与前端各写一遍，标记词表与阈值逐字重复且无共享用例
- 专家 / section：maintainability / simplify
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/ai/AssistantReplySplitter.java:20`
- 置信度：high · kind：plan
- 证据：AssistantReplySplitter.java:15 定义 SENTENCE_SPLIT_MIN_CHARS = 40，:20 的 ATTEMPT_MARKERS（现在就试/这就试/我去试/我试试/稍等/盯着/马上帮你/这就去）与 :23 的 OUTCOME_MARKERS（13 个词）在 frontend/src/utils/assistantReplySplit.js:12、:96、:97 逐字重复；groupComputerTaskAttemptAndOutcome 的算法同样是两语言各写一遍，两侧注释互相要求「须与 frontend/src/utils/assistantReplySplit.js 保持一致」（Java 侧该方法注释）与「须与后端 AssistantReplySplitter.groupComputerTaskAttemptAndOutcome 一致」（JS 侧 :99）。另 JS 侧 assistantReplySplit.js:155 的 SPEAKING_STYLE_MAX_REPLIES（活泼 3/元气 3/温柔 2/傲娇 2/毒舌 2/冷静 1/成熟 1/慵懒 1）注释称「与后端 CharacterChatBehaviorResolver.StyleProfile 默认条数一致」，但该映射表只存在于 JS，Java 侧是 profileForStyle + 关键词调整，JS 兜底 return 2（:187）。仓内没有任何两侧共享的 golden 用例。
- 影响：改一处漏一处的风险已经由两侧注释自己写出来了：新增同义标记或调整 40 字阈值必须人工同步两种语言与两套测试，漏改会让同一句话在落库（Java 切分后逐条落库）与实时流（前端兜底切分）两处切成不同条数，表现为刷新前后气泡数变化。新增说话风格只在 JS 表里加常量不会影响 Java 决策，反之亦然，任一侧漏改都静默退化为兜底条数。
- 修复摘要：把切分阈值、两组标记词表与说话风格默认条数收敛成一份可被两侧读取的契约文件，并用同一组 golden 用例锁住两侧行为
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/ai/AssistantReplySplitter.java、frontend/src/utils/assistantReplySplit.js、backend/lianyu-service/src/main/java/com/lianyu/service/character/CharacterChatBehaviorResolver.java、scripts/check_release_asset_contract.py、AGENTS.md
- 建议测试：backend/lianyu-service/src/test/java/com/lianyu/service/ai/AssistantReplySplitterTest.java 增加 golden 对照用例；frontend/src/utils/__tests__/assistantReplySplit.test.js 读同一 fixture 并断言条数与内容；新增契约一致性检查脚本的用例（常量与 JSON 不一致时非零退出）

### MNT-EA332 · [中] QQ 与微信桥接协调器各自复制同一套广播与 API 解包样板
- 专家 / section：maintainability / simplify
- 位置：`frontend/electron/qqBridge/qqBridgeCoordinator.js:16`
- 置信度：high · kind：plan
- 证据：qqBridgeCoordinator.js:16-26 的 broadcast(channel, payload)（遍历 deps.getWindows()、win.isDestroyed() 判断、try/catch 空忽略）与 wechatBridgeCoordinator.js:17-27 完全逐字相同；qqBridgeCoordinator.js:55-73 的 unwrap(res, path) + apiGet + apiPost 与 wechatBridgeCoordinator.js:50-68 同样逐字相同，包括 `${apiOrigin}${path}` 拼接、timeoutMs: 15000 / 30000 两个魔数、错误串 `api:${path} HTTP ${res?.status}` 与 `api:${path} code ${body.code}`，以及 body.code 缺省时直接返回 body 的分支。两个文件还各自维护一份 ensureBridgeBinding / ensureBinding。
- 影响：桥接的三条隐性契约（请求超时、错误码格式、向所有窗口广播的方式）在两个文件里各改一次；任一侧漏改会让 QQ 与微信两条链路的失败语义与超时行为分叉，而错误串格式正是前端解析与用户文案的依据。
- 修复摘要：抽出 frontend/electron/shared/bridgeTransport.js 承载 broadcastToWindows、unwrap、createApiClient(apiOrigin, authToken)，两个协调器改为注入使用
- 修复文件：frontend/electron/qqBridge/qqBridgeCoordinator.js、frontend/electron/wechatBridge/wechatBridgeCoordinator.js、frontend/electron/shared/bridgeTransport.js
- 建议测试：frontend/electron/qqBridge/__tests__/qqBridgeCoordinator.test.js；frontend/electron/wechatBridge/__tests__/wechatBridge.test.js；新增 frontend/electron/__tests__/bridgeTransport.test.js 覆盖 HTTP 非 2xx 与 code!==200 两条失败分支

### MNT-8DDBC · [中] 登录页与注册页的验证码逻辑逐字重复约 45 行，未按项目既有惯例抽 composable
- 专家 / section：maintainability / simplify
- 位置：`frontend/src/pages/RegisterPage.vue:157`
- 置信度：high · kind：plan
- 证据：RegisterPage.vue:157-233 的 captchaId/captchaImageSrc/captchaHint 三个 ref + applyCaptchaResponse + onCaptchaImgError + refreshCaptcha，与 LoginPage.vue:137-183 的同一段代码逐字相同：同样处理 `data:image/png;base64,` 前缀、同样的失败文案『获取失败，点击刷新』与『验证码加载失败，点击刷新』、同样的 ElMessage.error('无法连接服务器，请确认网络正常且后端已启动')。项目已有 src/composables/useAuthPageGsap.js、src/composables/useVoiceRecorder.js 等 26 个 composable，说明抽取是本仓既有惯例。
- 影响：验证码的加载失败文案、base64 兜底、刷新时机这些易变细节要改两处；新增一种验证码形态（拼图/滑块）时两个页面都要重写一遍，且极易只改一处导致登录与注册体验不一致。
- 修复摘要：抽 frontend/src/composables/useCaptcha.js 统一验证码状态与刷新，两个页面只保留表单差异
- 修复文件：frontend/src/pages/LoginPage.vue、frontend/src/pages/RegisterPage.vue、frontend/src/composables/useCaptcha.js
- 建议测试：新增 frontend/src/composables/__tests__/useCaptcha.test.js；frontend/src/pages/__tests__ 下已有的登录/注册相关用例回归

### MNT-A8B9D · [中] admin 权限目录类无人使用，实际鉴权全部裸写权限字符串
- 专家 / section：maintainability / simplify
- 位置：`backend/lianyu-admin/src/main/java/com/lianyu/admin/identity/AdminPermissionCatalog.java:7`
- 置信度：high · kind：plan
- 证据：AdminPermissionCatalog 只有两个成员：SUPER_ADMIN = super_admin（:6）与 ALL = Set.of(admin.manage, user.read, user.moderate, release.manage, announcement.manage, audit.read, system.health)（:7），全仓 grep 仅命中自身定义行，没有任何消费方。真实鉴权点全部写裸字符串：AdminAuditController.java:19 require(audit.read)；AdminManagementController.java:22、:23、:34、:35、:36 require(admin.manage)；AdminOperationsController.java:28、:31 require(user.moderate)，:35 与 AnnouncementController.java:20、:28、:29 require(announcement.manage)；AdminOverviewController.java:21 require(system.health)。
- 影响：这个类看起来是权限清单，实际是死常量：给它加一个权限不会生效，改一个字符串也不会与控制器同步，后续开发很容易照它以为已经登记。权限串拼错（例如 user.modrate）编译期不报错，只会静默变成『无人拥有该权限』的 403，排障时看不出是拼写问题。
- 修复摘要：让 AdminPermissionCatalog 成为唯一权限来源，控制器全部引用其常量，并删除或接入 ALL
- 修复文件：backend/lianyu-admin/src/main/java/com/lianyu/admin/identity/AdminPermissionCatalog.java、backend/lianyu-admin/src/main/java/com/lianyu/admin/identity/AdminManagementController.java、backend/lianyu-admin/src/main/java/com/lianyu/admin/identity/AdminAuthorizationService.java、backend/lianyu-admin/src/main/java/com/lianyu/admin/operations/AdminOperationsController.java、backend/lianyu-admin/src/main/java/com/lianyu/admin/operations/AnnouncementController.java、backend/lianyu-admin/src/main/java/com/lianyu/admin/audit/AdminAuditController.java、backend/lianyu-admin/src/main/java/com/lianyu/admin/overview/AdminOverviewController.java
- 建议测试：backend/lianyu-admin/src/test/java 下新增 AdminPermissionCatalogTest 断言 ALL 覆盖全部常量；既有 admin 鉴权相关测试回归（mvn -o test）

### MNT-E3F97 · [中] lianyu.ai.context-window 在 4 处注解重复默认值，AiConfig 还留着无人读取的同名字段
- 专家 / section：maintainability / simplify
- 位置：`backend/lianyu-ai/src/main/java/com/lianyu/ai/config/AiConfig.java:21`
- 置信度：high · kind：plan
- 证据：backend/lianyu-app/src/main/resources/application.yml:204 定义 context-window: 32，但代码里 4 个类各写一遍 @Value("${lianyu.ai.context-window:20}")：ConversationService.java:98、GroupChatService.java:89、ProactiveMessageService.java:197、MomentsService.java:96；AiConfig.java:21 作为 @ConfigurationProperties(prefix = lianyu.ai) 又声明 private int contextWindow = 20，全仓无人读取（真实取值都来自四个 @Value 字段）。副作用是这些字段只能靠反射改：ConversationRelationshipFlowTest.java:91-93 用 getDeclaredField(contextWindow) + setAccessible 注入，ConversationServiceGetSummaryTest.java:83 与 ConversationServiceStreamErrorTest.java:103 用 ReflectionTestUtils.setField(service, contextWindow, 20)。
- 影响：同一属性有 5 处声明、2 个不同的默认值来源（yml 32 / 注解 20），AiConfig 那个字段既不生效又给出错误暗示；调默认值要改 4 处注解，漏改任何一处都不会报错，只会让某个场景（朋友圈取 12 条上限、主动消息、群聊）用上不同窗口。测试与实现的私有字段名耦合，重命名字段会静默破坏反射赋值。
- 修复摘要：收敛成 @ConfigurationProperties 的 ChatProperties 并构造注入，删除 AiConfig.contextWindow 与四处重复注解
- 修复文件：backend/lianyu-ai/src/main/java/com/lianyu/ai/config/AiConfig.java、backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java、backend/lianyu-service/src/main/java/com/lianyu/service/conversation/GroupChatService.java、backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ProactiveMessageService.java、backend/lianyu-service/src/main/java/com/lianyu/service/moments/MomentsService.java、backend/lianyu-app/src/main/resources/application.yml
- 建议测试：backend/lianyu-service/src/test/java/com/lianyu/service/conversation/ConversationRelationshipFlowTest.java；backend/lianyu-service/src/test/java/com/lianyu/service/conversation/ConversationServiceGetSummaryTest.java；backend/lianyu-service/src/test/java/com/lianyu/service/conversation/ConversationServiceStreamErrorTest.java

### MNT-76EE8 · [中] 前端 313 行无引用文件：截图分享工具与 antiDebug 均无任何导入方
- 专家 / section：maintainability / cleanup
- 位置：`frontend/src/utils/captureChatShareScreenshot.js:128`
- 置信度：high · kind：plan
- 证据：captureChatShareScreenshot.js 共 220 行，唯一导出 export async function captureChatShareScreenshot(container, selectedIds)（:128），在 frontend/src 与 frontend/electron 全量 grep（.js/.vue，含测试）只命中自身定义；它依赖的配套文件 src/utils/renderChatShareSnapshot.js、src/utils/communityShareDraft.js 与 src/components/community/ChatShareSnapshot.vue 在仓内都已不存在，说明截图分享功能整体删除后该工具被遗留。另一处 src/utils/antiDebug.js 共 93 行，无任何 import（含 HTML、vite 配置），仅在 src/utils/logger.js:4 的注释里被提到『生产环境 console.* 被 antiDebug 吞掉』。二者均满足 CONFIRMED_DEAD（定义存在、零引用、无动态注册）。
- 影响：313 行无调用代码留在树里，且读起来像是正在生效的保护：logger.js 的注释让人以为生产环境 console 已被屏蔽，实际该模块从未被加载，据此做的排障或安全判断都会错。
- 修复摘要：删除两个零引用文件，并修正 logger.js:4 的注释
- 修复文件：frontend/src/utils/captureChatShareScreenshot.js、frontend/src/utils/antiDebug.js、frontend/src/utils/logger.js
- 建议测试：cd frontend && npx vitest run --no-file-parallelism；cd frontend && npx vite build；全量 grep 确认无残留 import 报错

### MNT-A2683 · [中] security 模块的字段加密设施从未接线：EncryptedField 与 EncryptedStringTypeHandler 零引用
- 专家 / section：maintainability / cleanup
- 位置：`backend/lianyu-security/src/main/java/com/lianyu/security/annotation/EncryptedField.java:8`
- 置信度：high · kind：plan
- 证据：EncryptedField（9 行，仅 @Target(FIELD)/@Retention(RUNTIME)/@Documented）全仓 grep 只命中自身定义；对 363 个 main Java 类做零引用检测后，它是唯一『零引用且非 Spring 组件』的注解。配套 EncryptedStringTypeHandler.java（40 行，基于 JasyptUtil 做 setNonNullParameter 加密与 getNullableResult 解密）同样零引用：仓内没有任何 MyBatis type-handlers-package/typeHandlers 配置（yml 中只有 JacksonTypeHandler 走 @TableField(typeHandler = ...) 显式引用，见 lianyu-dao/.../entity/Character.java:19），也没有实体用 @MappedTypes 或 @TableField 挂它。属 CONFIRMED_DEAD。
- 影响：安全模块里摆着一套从不生效的字段加密设施，读者会以为某些敏感列已加密；实际读写路径根本不会触发加解密，基于这个假设做数据分级或迁移会得出错误结论。同时它会让 check-java-ai-free 之类的静态审查误以为该模块仍有实质内容。
- 修复摘要：二选一并留档：要么把 handler 接到真正需要加密的列上，要么整组删除并写明原因
- 修复文件：backend/lianyu-security/src/main/java/com/lianyu/security/annotation/EncryptedField.java、backend/lianyu-security/src/main/java/com/lianyu/security/handler/EncryptedStringTypeHandler.java、backend/lianyu-security/src/main/java/com/lianyu/security/util/JasyptUtil.java、docs/security-coupling-map-zh.md
- 建议测试：cd backend && mvn -o test；scripts/check-java-ai-free.ps1 -Mode strict（基线 0/0/0 不变）

### MNT-20376 · [中] scripts/ 根目录 55 个 _ 前缀一次性排查脚本入库，84 个脚本硬编码生产 IP
- 专家 / section：maintainability / cleanup
- 位置：`scripts/_cloud_verify.py:1`
- 置信度：high · kind：plan
- 证据：git ls-files 显示 scripts 下共 114 个 tracked .py，其中 scripts/ 根目录 55 个以 _ 开头的一次性脚本（_cloud_verify.py 46 行、_cloud_verify_post.py 69 行、_cloud_verify_deploy.py 83 行、_cloud_verify_logs.py 89 行、_check_yml.py、_cloud_500_check.py、_cloud_deploy_backend_only.py、_cloud_deploy_layered.py、_repair_flyway_v31.py、_repair_flyway_v34.py 等），彼此差异只在 host 与远端命令常量；scripts/diag 下另有 21 个 tracked 文件属同类。另有 84 个 tracked 脚本硬编码生产 IP 156.233.228.18。AGENTS.md §3.1 明确要求『一次性排查脚本一律放 local/ 或带 _ 前缀且不提交』，现状违反该纪律。
- 影响：真正的发版入口是 local/ship-release.ps1（local/ 已被 gitignore，不在仓库），仓库里 55 份一次性副本让『哪份部署/校验脚本是权威』无法判断，新人极可能挑一份过期的去跑；生产 IP 与 SSH 用法散落 84 个文件，换机或换主机时要改 84 处，且这些脚本无测试、无引用关系，改错了也不会暴露。
- 修复摘要：把仍在用的一次性脚本合并成少量带参数的运维命令，其余移出仓库或删除
- 修复文件：scripts/_cloud_verify.py、scripts/_cloud_verify_post.py、scripts/_cloud_deploy_layered.py、scripts/_cloud_deploy_backend_only.py、scripts/_repair_flyway_v31.py、scripts/_repair_flyway_v34.py、scripts/_prod_verify.py、scripts/diag/、AGENTS.md、.gitignore
- 建议测试：cd backend-agent && pytest（保证未误删被测试引用的脚本）；python scripts/check_release_asset_contract.py；合并后的运维脚本各跑一次 --dry-run（或 --help）确认参数化后仍可用

### MNT-DB86B · [中] scripts/ops/_write_theme_picker.py 内嵌 270 行组件并覆盖源文件，当前副本已与组件产生 5 行坏 CSS 差异
- 专家 / section：maintainability / cleanup
- 位置：`scripts/ops/_write_theme_picker.py:277`
- 置信度：high · kind：plan
- 证据：_write_theme_picker.py:3 用 CONTENT = r'''...''' 内嵌整个组件正文，:277-278 拼路径 dest = Path(__file__).resolve().parents[1] / 'frontend' / 'src' / 'components' / 'ThemeColorPicker.vue' 并 dest.write_text(CONTENT, encoding='utf-8') 直接覆盖源文件；该组件正被 frontend/src/components/AppHeader.vue:27 使用。实测内嵌副本 270 行与当前组件 270 行逐一 diff，有 5 行差异且全部是把 gap 写成 g ap 的坏 CSS：内嵌为 '  g ap: 2px;'、'  g ap: $space-2;'（3 处）、'  g ap: $space-3;'，当前文件是正确的 gap。即重跑该脚本会把坏样式写回已发布组件。
- 影响：这是一份会覆盖好代码的第二事实来源：脚本没有任何校验，任何人为了『重新生成组件』跑一次就会给主题选择器写入 5 处失效 CSS（间距全部丢失），而且改动看起来像是脚本生成的正常结果，排查成本高。
- 修复摘要：删除该生成脚本（或改为只读模板 + 明确禁止覆盖），并以组件本身为唯一来源
- 修复文件：scripts/ops/_write_theme_picker.py、frontend/src/components/ThemeColorPicker.vue、frontend/src/components/AppHeader.vue、AGENTS.md
- 建议测试：cd frontend && npx vitest run --no-file-parallelism（主题/头部相关用例）；cd frontend && npx vite build

### MNT-3BE34 · [低] 宠物音色与图像脚本各有多份副本，其中一份逐字节相同
- 专家 / section：maintainability / cleanup
- 位置：`scripts/enroll_pet_voices.py:1`
- 置信度：high · kind：plan
- 证据：scripts/enroll_pet_voices.py 与 Pets/skills/hatch-lianyu-pet/scripts/enroll_pet_voices.py 字节相同（md5 均 b11be11664cb0a5ae832483e5ffbac0b，各 89 行，均被 git 跟踪）；scripts/gen_pet_fixed_voices.py（296 行）与 scripts/diag/_gen_pet_fixed_voices.py（271 行）是同族新旧副本；Pets/skills/hatch-lianyu-pet/scripts/gen_pet_images.py（355 行）与 scripts/gen_elysia_openai.py（331 行）开头约 40 行几乎逐字相同，只差 apikey 前缀解析（Pets-url/Pets-key 对 url/key），另 scripts/gen_elysia_dashscope.py（182 行）是同族变体。
- 影响：同一音色固定/图像生成流程存在两个可执行入口，跑哪一份决定产物是否一致；修 bug 只修一份就分叉，而这两条流程都直接写生产素材（音色文件、立绘），产物不一致很难回溯。
- 修复摘要：保留 Pets/skills/hatch-lianyu-pet/scripts 为唯一实现，scripts/ 下的副本改为调用或删除
- 修复文件：scripts/enroll_pet_voices.py、Pets/skills/hatch-lianyu-pet/scripts/enroll_pet_voices.py、scripts/gen_pet_fixed_voices.py、scripts/diag/_gen_pet_fixed_voices.py、scripts/gen_elysia_openai.py、Pets/skills/hatch-lianyu-pet/SKILL.md
- 建议测试：python -m pytest Pets/skills/hatch-lianyu-pet/scripts/test_gen_pet_images.py；删除后各脚本 --help 或 dry-run 一次确认入口仍可用

### MNT-72E73 · [低] frontend 与 admin 共 6 个依赖在源码中零引用
- 专家 / section：maintainability / cleanup
- 位置：`frontend/package.json:37`
- 置信度：high · kind：plan
- 证据：frontend/package.json:37 的 @vue/test-utils 与 :44 的 vite-plugin-electron-renderer，除 package-lock.json 外，在 frontend/src 与 frontend/electron 全量 grep 均 0 引用（488+ 个测试文件都不导入 @vue/test-utils；vite.config.js 只用 vite-plugin-electron/simple）。admin/package.json:10 的 @tanstack/vue-table、echarts、vue-router、@element-plus/icons-vue 在 admin/src（10 个 3-16 行的小壳文件）与 admin/electron 中同样 0 引用；concurrently/wait-on 只出现在 electron:dev 脚本串里，按已用处理，element-plus/axios/pinia 有引用。
- 影响：安装体积与 lockfile 噪声增加，审计面变大；admin 装上了 echarts 与 @tanstack/vue-table 却没有任何页面使用，后续开发容易误以为已有现成表格/图表能力而上错方向。
- 修复摘要：移除 6 个零引用依赖并重算 lockfile，保留 sass（styles 目录仍在用）
- 修复文件：frontend/package.json、frontend/package-lock.json、admin/package.json、admin/package-lock.json、.github/workflows/ci.yml
- 建议测试：cd frontend && npx vitest run --no-file-parallelism && npx vite build；cd admin && npm run build（或 admin 现有构建脚本）确认可打包

### MNT-C52D4 · [低] backend-agent Settings 有 4 个键只定义、无人读取（含已退役的 shadow_mode）
- 专家 / section：maintainability / cleanup
- 位置：`backend-agent/app/config.py:108`
- 置信度：high · kind：plan
- 证据：Settings 共 59 个字段，其中 multimodal_max_tokens(:24)、internal_signature_window_seconds(:57)、memory_recent_ttl_minutes(:76)、shadow_mode(:108) 在 backend-agent/app 全部模块、tests、docker-compose、.env 示例与 docs 中，除定义行外 0 命中（用脚本提取字段名后逐字段检索 settings.<name> 与裸名双路验证）。shadow_mode:108 上方注释仍写着『影子对比模式：true 时记录请求/响应到日志供新旧链路 diff』，而 OFF/SHADOW 已按 docs/refactor-2026-10/off-retirement-plan.md 退役。
- 影响：三个可配项写进了 Settings 却没有任何消费者：运维以为调 internal_signature_window_seconds 能改内部签名时间窗、调 multimodal_max_tokens 能改识图预算、调 memory_recent_ttl_minutes 能改近期记忆缓存，实际全部无效，排障时会朝错误方向查；shadow_mode 则让人以为影子链路仍存在。
- 修复摘要：删除 4 个无消费者的配置键，或在真正接线后补读取点与测试
- 修复文件：backend-agent/app/config.py、backend-agent/README.md
- 建议测试：cd backend-agent && pytest；新增或调整 tests 中对 Settings 字段集合的断言（若已有）

## 转交 deferred
- maintainability → 未指定 · backend/lianyu-dao/src/main/java/com/lianyu/dao/mapper/CharacterStickerMapper.java：实体与 Mapper 为 23:23 一一对应，但未确认 character_sticker 表是否仍被业务读取，不足以判死代码，留待后续核对调用链。
- maintainability → 未指定 · frontend/src/pages/LoginPage.vue 与 RegisterPage.vue 的表单校验规则（validatePassword/validateConfirm）相似度为 0.263，但未逐行确认是否可直接复用，故只报告了逐字相同的验证码块。
- maintainability → 未指定 · AGENTS.md §5.3 写『当前最新 V55』，实际迁移已到 V57（V1–V57 无缺号、无重号）：属文档漂移，未达报告阈值，仅在此登记。
- maintainability → 未指定 · scripts/ 下一次性脚本中哪几份仍被 local/ship-release.ps1 调用无法从仓库确认（local/ 被 gitignore），删除前需向维护者核对。
- maintainability → 未指定 · backend-agent 的 internal_signature_window_seconds 是否对应一处硬编码的签名时间窗（潜在正确性问题）未深查，本轮只按『死配置』记录。

> 这些不是当前专家的 finding，但必须写入 verdict.deferred，不能静默丢弃。

## 机器提示（仅供参考）
- 没有检测到明显的跨专家重复候选。

## 交给主编
1. 读本文件与原始 handoff；不要只依赖机器提示。
2. 合并重复项，保留最可信的证据；被合并的 ID 记入 verdict.dropped。
3. 写 `verdict.json`（schemaVersion 2、findings、coverage、dropped），再写 `report.md`。
4. 只有完整覆盖默认四模块时，才更新 `docs/logistics/latest.md` 与 `.logistics/latest.json`。
5. 工作区根目录：`C:\Users\hp\Desktop\LianYu-PC`；运行目录：`C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261104-175652-a7c1e9`。
