# 后勤主编材料 · 20261104-182459-9883f4

> 这是给主会话模型（主编）的候选材料，不是最终报告。
> 去重、定级、取舍、模块组织由主编决定；机器只负责收集与校验。

## 运行
- 根目录：`C:/Users/hp/Desktop/LianYu-PC`
- 范围：1100 个文件 / 12 个审查单元 / 135978 行
- 测试命令：（未检测到）

## 覆盖
- 预期单元 / 专家：perf
- 已收到：perf
- 缺失：（无）
- 无效：（无）
- 失败：（无）
- partial：（无）

## 单元覆盖矩阵

| 单元 | 标签 | perf |
| --- | --- | --- |
| u01 | backend/lianyu-service (1/3) | reviewed |
| u02 | backend/lianyu-service (2/3) | sampled |
| u03 | frontend/src (1/2) | reviewed |
| u04 | scripts (1/2) | sampled |
| u05 | backend/lianyu-dao | reviewed |
| u06 | backend-agent | reviewed |
| u07 | frontend/src (2/2) | sampled |
| u08 | backend/lianyu-web | reviewed |
| u09 | frontend/electron | sampled |
| u10 | 其他模块（(root)、.github、admin 等） | sampled |
| u11 | backend（小模块合集 59 文件） | sampled |
| u12 | 其他模块（backend/lianyu-app、backend/lianyu-common、backend 等） | sampled |

## 专家摘要
### perf · ok
- 摘要：按 12 个审查单元全量过了一遍性能面：Java 侧（service/dao/web）总体健康——主动发言调度已批量化、message 表有 uk_conv_seq/idx_conv_role_seq 等复合索引、Mapper 无 SELECT *、SSE 中继用 StringBuilder 而非 O(n²) 拼接、正则均为 static final 预编译；脚本与 admin/security/common 等小模块属运维/冷路径，未发现运行时热点。真正可落地的问题集中在前端打包与后端每轮链路：dist 全局单 CSS 首行带 Google Fonts 的 @import，会把三个窗口（含透明桌宠、快捷聊）的首屏样式表阻塞在跨境请求上；桌宠窗口要下载/解析约 1.94MB JS+CSS（element-plus 全量进了 launcherBootstrap），还要解码 1536×1872 的 1.6–2.7MB spritesheet，而这些成本在「预热隐藏窗口」阶段就已经付了；桌宠动画的 requestAnimationFrame 以显示器刷新率空转（idle 只需 6fps）。后端主要成本是每轮聊天前 7 次 MySQL + 1 次 Redis 全串行、连接池只有 4；主动问候路径绕过了天气 TTL 缓存且每次新建 AsyncClient；pymilvus 的同步 search/insert/delete 直接跑在事件循环上，Milvus 抖动时按 10s 超时挂住整个 agent。共 8 条发现（2 high / 4 medium / 2 low），全部 kind=plan。本次为只读静态审查，无运行时 profiling 条件，量化按 rung B（实测产物字节/行号）与 rung C（复杂度与超时常量）给出。
- sections：hot-path=1 · data-access=2 · runtime=1 · frontend=3 · memory-startup=1
- notes：方法与证据等级：本次为只读静态审查，不具备启动应用/压测的条件，因此没有 rung A（profile）数据；前端发现基于 frontend/dist（ELECTRON_BUILD=1，2026-11-01 18:30 产物）的实测文件字节与源码行号（rung B），后端发现基于调用链与配置常量的复杂度/往返上界（rung C，已在 impact 中标注 n 与逐项成本）。环境限制：本机 rg 不在 PATH，跨文件扫描改用 ops_grep/grep 完成；bash 与 python 的 /tmp 不互通，未使用临时中转文件。sections 五个分节全部检查过，计数与 findings 逐项一致（hot-path 1、data-access 2、runtime 1、frontend 3、memory-startup 1，合计 8）。12 个审查单元全部覆盖，无 skipped。未修改任何源码，仅写入本 handoff 文件。 | 主编规范化：sections 原为 {count,status,notes} 对象，已按 findings 实际条数归一为整数计数（hot-path 1 / data-access 2 / runtime 1 / frontend 3 / memory-startup 1），原文明细保留在 sectionsDetail。
- 发现数：8
- 转交其他专家：4 条
  - → 未指定 · frontend/src/pages/MomentsPage.vue:567 的评论轮询每 6s 对所有展开中的帖子各发一次 loadComments（展开 N 个帖 → N 请求/6s），属请求放大，但目前 N 由用户手动展开且量级小，证据不足以定为性能缺陷，转交后续观察。
  - → 未指定 · frontend/src/pages/QuickChatPage.vue:365 与 ChatPage.vue:1184 的 10s 轮询在窗口隐藏时是否继续，取决于 Chromium 对隐藏窗口的定时器节流与 Electron backgroundThrottling 默认值；需实测（DevTools 后台网络面板）才能定级，未作为发现。
  - → 未指定 · backend-agent/app/db.py 的 _pool/_redis 惰性全局单例在首次并发请求下可能重复建池（每个连接池 4 连接），属边界情况，需并发压测确认，未作为发现。
  - → 未指定 · backend/lianyu-web/src/main/java/com/lianyu/web/agent/DesktopObserveOrchestrator.java 的并发护栏细节未逐行核对（占槽上限/限流维度），若需确认锁竞争与线程池饥饿需单独评审。

## 候选 findings
### PRF-C4679 · [高] 全局单 CSS 首行 @import Google Fonts，三个窗口首屏样式表被跨境请求阻塞
- 专家 / section：perf / frontend
- 位置：`frontend/src/pages/LandingPage.vue:342`
- 置信度：medium · kind：plan
- 证据：打包产物实测：frontend/dist/assets/style-CMGPS_t0.css 共 616,841 B，文件开头即为 `@charset "UTF-8";@import"https://fonts.googleapis.com/css2?family=Noto+Serif+SC:wght@500;600;700&family=Syne:wght@500;600;700&display=swap";`。该 CSS 被 dist/index.html、dist/launcher.html、dist/quick.html 三个入口同时 <link> 引用（frontend/vite.config.js 中 cssCodeSplit: !isElectronPack，打包态只产一份 CSS）。@import 源头是同一行复制在 frontend/src/pages/LandingPage.vue:342、frontend/src/pages/LoginPage.vue:221、frontend/src/pages/RegisterPage.vue:272 的 <style> 里，被 Rollup 提升进全局 CSS 首行。
- 影响：CSS 内的 @import 是渲染阻塞的：浏览器必须先把被 import 的样式表下载并解析完，才继续构建 CSSOM 与首屏绘制。于是主窗口、透明桌宠窗口、快捷聊窗口每次首屏都要先付一次到 fonts.googleapis.com 的跨境往返；该域在境内常被黑洞，Chromium 要等到连接超时/失败才继续，桌宠与快捷聊（用户要求『秒开』的两个窗口）首帧因此被拖到秒级，而它们根本用不到 Landing/Login 的衬线字体。
- 修复摘要：把字体引入从全局 CSS 的 @import 里摘出去：需要该字体的页面改为自托管字体或页面级异步 <link>，保证三个 HTML 入口的首屏 CSS 不含任何外部请求。
- 修复文件：frontend/src/pages/LandingPage.vue、frontend/src/pages/LoginPage.vue、frontend/src/pages/RegisterPage.vue、frontend/vite.config.js
- 建议测试：（无）

### PRF-3C387 · [中] 桌宠/快捷聊入口加载 element-plus 全量与单份 616KB CSS，首屏 JS+CSS 约 1.94MB
- 专家 / section：perf / frontend
- 位置：`frontend/src/main.js:3`
- 置信度：high · kind：plan
- 证据：frontend/dist/launcher.html 只引用 assets/launcher-CRwrn45F.js（645 B）与 assets/style-CMGPS_t0.css（616,841 B）；launcher-CRwrn45F.js 的模块图包含 assets/launcherBootstrap-BOd8J3cO.js（1,129,706 B，文件内出现 element-plus 10 次、ElMessage 5 次）与 assets/vue-vendor-DEeJUbX_.js（193,565 B）。element-plus 的来源是 frontend/src/main.js:3-5 的 `import ElementPlus from 'element-plus'` + `import 'element-plus/dist/index.css'` + `import 'element-plus/theme-chalk/dark/css-vars.css'`，而 frontend/vite.config.js:87-91 的 manualChunks 未把 element-plus 与桌宠入口拆开；cssCodeSplit: !isElectronPack 又让三个入口共用同一份 CSS。
- 影响：桌宠窗口的实际内容是一个 192×208 的 canvas 加几个按钮，却要在首帧前下载并解析约 1.94MB 的 JS+CSS（含整套 element-plus 组件与主题样式），桌面低端机/机械盘冷启动时可观测到明显空窗；快捷聊窗口同样付这份成本，且每个窗口各自一份解析与内存。这是 rung B（实测产物字节）而非推测。
- 修复摘要：让 launcher / quick 两个轻量入口不再引入整套 element-plus，并把 CSS 按入口拆分，只让主窗口承担重依赖。
- 修复文件：frontend/src/main.js、frontend/src/entries/launcher-main.js、frontend/src/entries/quick-main.js、frontend/vite.config.js
- 建议测试：（无）

### PRF-54C33 · [低] 桌宠动画以显示器刷新率空转 rAF（idle 只需 6fps），常驻可见时不停机
- 专家 / section：perf / frontend
- 位置：`frontend/src/composables/usePetSpriteAnimator.js:143`
- 置信度：high · kind：plan
- 证据：tick() 末尾无条件 `rafId = requestAnimationFrame(tick)`（frontend/src/composables/usePetSpriteAnimator.js:143），只有帧间隔到达时才 renderFrame（:124-141，frameInterval = 1000 / def.fps）；frontend/src/constants/petSprite.js:9 定义 `idle: { row: 0, frames: 6, fps: 6, loop: true }`。playAnim('idle') 在 onMounted（:170-174）就启动且 loop 恒为 true，只有 onUnmounted（:176-180）才 stopRaf；空闲小动作 scheduleIdleVariety 每 6-16s 换一次动作但同样走这条常驻 rAF。
- 影响：以 60Hz 屏为例，idle 状态下每秒 60 次回调里只有 6 次真正重绘，其余约 54 次是空转（120Hz 屏为空转 114 次/秒，约 95%）；桌宠是 alwaysOnTop + transparent 窗口（frontend/electron/main.js:1416-1432），Windows 上透明窗口本身就少 GPU 合成路径，渲染管线被这条常驻 rAF 钉在满帧，使整机无法进入低功耗空闲态，影响续航与风扇噪音。属静态可证的浪费，量级取决于窗口是否长期可见。
- 修复摘要：动画调度从『跟着显示器刷新率跑』改成『按动画 fps 定时步进』，并在节点不可见/窗口隐藏时停表。
- 修复文件：frontend/src/composables/usePetSpriteAnimator.js、frontend/src/pages/LauncherPage.vue、frontend/src/constants/petSprite.js
- 建议测试：（无）

### PRF-19E08 · [低] ChatPage 每次轮询用 some/find 做 O(本地消息×50) 成员判断，随后全量重排
- 专家 / section：perf / hot-path
- 位置：`frontend/src/pages/ChatPage.vue:1016`
- 置信度：high · kind：plan
- 证据：frontend/src/pages/ChatPage.vue:1015-1021 对 messages.value 逐条执行 `incomingUsers.some(...)` 与 `incomingAssistants.some(...)`；:978-987 对每条临时用户消息执行 `incomingUsers.find(...)`。触发者是常驻轮询：:1184 `conversationPollTimer = window.setInterval(..., NORMAL_POLL_MS)`（NORMAL_POLL_MS=10000，:589），以及快轮询 :1157-1173（FAST_POLL_MS=2000、FAST_POLL_MAX_MS=90000）；每次轮询固定取最新 50 条（MESSAGE_PAGE_SIZE=50，:484），另有 :1150-1155 的开窗三次 burst。上翻历史（:919，getMessages(convId, {limit, beforeSeq})）会让本地 messages.value 增长到数百条；changed 时还会调用 sortMessagesInTimelineOrder（:1037）。
- 影响：静态上界（rung C，n 取 scope 中常见量）：n=300 条本地消息、m=50 条 incoming 时，单次轮询约 1.5×10⁴ 次字符串比较（isSameRecentUserMessage 比较 content+imageUrl），快轮询窗口内 10s 有 5 次轮询 ≈ 7.5×10⁴ 次；量级在毫秒，但随历史翻页线性放大，且每次 changed 就全量 sort + 触发受影响气泡的重渲染。目前不是用户体验瓶颈，属可清理的常数浪费。
- 修复摘要：把成员判断从 O(n×m) 降为哈希查表，并让轮询只处理新增消息。
- 修复文件：frontend/src/pages/ChatPage.vue
- 建议测试：（无）

### PRF-65E3F · [中] 每轮聊天在调模型前串行执行 7 次 MySQL + 1 次 Redis，连接池仅 4
- 专家 / section：perf / data-access
- 位置：`backend-agent/app/data_readers.py:209`
- 置信度：high · kind：plan
- 证据：backend-agent/app/data_readers.py:209-211 `facts = await _load_profile_facts(...)`、`base = await _load_recent_rows(...)`、`boosted = await _load_like_boosted(...)` 三次顺序 await；:242 `await load_profile_block(...)`、:247 `await load_relationship(...)`（内部 :26-66 再顺序查 relationship_state 与 relationship_event）、:260 `await load_session_summary(...)`（Redis）；backend-agent/app/prompt_pipeline.py:225-273 的 _fill_internal_data 里再顺序 `await load_character_owner(...)` → `await load_emotion(...)`。合计每轮 7 次 MySQL + 1 次 Redis，全部串行，彼此之间除 owner→emotion 外无依赖。连接池 backend-agent/app/config.py:65 `mysql_pool_size` 默认 4。
- 影响：每条用户消息在首个 token 之前要先付 8 个串行 DB/Redis 往返：同机房约 0.5-1ms/次（合计 5-8ms），跨可用区 2-3ms/次（合计 15-25ms），全部叠加在用户可感知的首 token 延迟上。更关键的是池只有 4 条连接：每个回合串行 acquire 7 次，4 个并发回合以上就开始排队，高并发时首 token 延迟被排队放大（uq 里也提到 agent 是单副本 Python 进程）。
- 修复摘要：把相互独立的读取改成并发，并按实测并发量重新评估连接池大小。
- 修复文件：backend-agent/app/data_readers.py、backend-agent/app/prompt_pipeline.py、backend-agent/app/db.py、backend-agent/app/config.py
- 建议测试：（无）

### PRF-A20C5 · [中] 主动问候路径绕过天气 TTL 缓存，每次新建 AsyncClient 打一次 wttr.in，并挂在 prompt 关键路径上
- 专家 / section：perf / data-access
- 位置：`backend-agent/app/proactive_context.py:60`
- 置信度：high · kind：plan
- 证据：backend-agent/app/proactive_context.py:60 `weather_fact = (await fetch_weather(city) or "").strip()` 调的是 backend-agent/app/tools/weather_tool.py:38 的 fetch_weather——该函数没有任何缓存；只有同文件下方的 handle_get_weather 才用 `_cache_get(key, settings.weather_cache_minutes * 60)` / `_cache_put`（配置 backend-agent/app/config.py:50 weather_cache_minutes=15.0）。fetch_weather 内部 `async with new_client() as own: return await own.get(url, params=params)`，而 backend-agent/app/http_client.py:25-40 的 new_client 每次新建 httpx.AsyncClient + AsyncHTTPTransport（无 keep-alive 复用）；超时取 backend-agent/app/config.py:29 outbound_timeout=(15.0, 60.0, 30.0, 30.0)，read 即 60s。调用点位于组装 prompt 的关键路径：backend-agent/app/prompt_pipeline.py:217 `prompt += await build_proactive_real_world_block(settings)`（scene_key == PROACTIVE）。
- 影响：每一次主动问候（产品主玩法，按调度批量触发）都要在模型调用之前先等一次未缓存的 wttr.in 往返，且每次都新建 TCP+TLS（无连接复用）；wttr.in 实测常见 1-5s 响应，抖动时按 60s read 超时挂在 assemble_prompt 节点上，用户看到的是『该主动说话时没说话』。同一城市 15 分钟内被多个角色/多个用户重复请求时全部重打外部接口（缓存函数本来就写好了却没人用）。
- 修复摘要：让主动问候复用既有的天气 TTL 缓存与共享连接池，并把天气降级为有硬上限的可选步骤。
- 修复文件：backend-agent/app/proactive_context.py、backend-agent/app/tools/weather_tool.py、backend-agent/app/http_client.py、backend-agent/app/config.py
- 建议测试：（无）

### PRF-74590 · [高] pymilvus 同步 search/insert/delete 直接在 async 路径上执行，Milvus 抖动时按 10s 超时阻塞整个事件循环
- 专家 / section：perf / runtime
- 位置：`backend-agent/app/tools/memory_search_tool.py:100`
- 置信度：medium · kind：plan
- 证据：backend-agent/app/tools/memory_search_tool.py:100 在 async 函数内同步调用 `hits = milvus_store.search(user_id, character_id, query_vectors[0], top_k=max(limit * 2, 6))`；backend-agent/app/milvus_store.py:121 的 `client.search(...)` 是 pymilvus 同步 RPC，:41 `client.has_collection(...)`、:44 `client.load_collection(...)` 同为同步调用，:70/:102 的 insert/delete 亦同；全流程无 asyncio.to_thread。超时 backend-agent/app/config.py:91 milvus_timeout_seconds=10.0。同类调用还在后台批量路径：backend-agent/app/memory/relay.py:100-101、:133 在 drain_once 的 async 循环里逐条做同步 insert/delete。
- 影响：agent 是单进程单事件循环的 FastAPI（backend-agent/app/main.py），这些同步 RPC 一旦在跑，同一进程内其他会话的流式 token、健康检查、主动问候全部停摆。正常 Milvus（同机房）单次 search 十几到几十毫秒，已足以在并发会话下造成可感的 token 抖动；Milvus 不可达/负载高时按 milvus_timeout_seconds=10.0 阻塞事件循环，等于全站 10s 挂起（每次都触发，不是偶发）。记忆检索是本产品的高频工具（memory_search 在工具循环内可被多轮调用）。
- 修复摘要：把 pymilvus 的同步调用移出事件循环，并给 Milvus 访问加独立的并发上限与更短超时。
- 修复文件：backend-agent/app/milvus_store.py、backend-agent/app/tools/memory_search_tool.py、backend-agent/app/memory/relay.py、backend-agent/app/main.py、backend-agent/app/config.py
- 建议测试：（无）

### PRF-8F8D9 · [中] 隐藏预热的桌宠窗口在挂载时就解码 1536×1872 spritesheet（1.6–2.7MB）并加载 1.94MB JS/CSS
- 专家 / section：perf / memory-startup
- 位置：`frontend/src/pages/LauncherPage.vue:165`
- 置信度：high · kind：plan
- 证据：frontend/electron/main.js:1490-1493 prewarmLauncherWindow() 创建桌宠窗口但 `show: false`（:1428），由 scheduleAuxWindowPrewarm 在主窗 did-finish-load 后 6000ms 触发（:1496-1513，另 12000ms 预热 quick chat）；该窗口 webPreferences 为 LAUNCHER_WEB_PREFS（:150-156），其中 backgroundThrottling: false。LauncherPage.vue 的 onMounted（:449 起）无条件调用 applyPetId(...)，applyPetId 内 :165 `setIdleFrame(getPetIdleUrl(...))` 与 :167-175 `const img = new Image(); img.src = getPetSpriteUrl(pet)` —— 可见性判断（isLauncherVisible）只用于 launcherActive / startObserver，没有拦住主图集加载。资源实测：frontend/dist/pet 下 19 个文件共 35.9MB，单个 *_spritesheet.webp 1.61–2.71MB（如 anya 1,761,046 B、ayaka 2,199,636 B），图集尺寸 1536×1872（PET_FRAME_W=192/PET_FRAME_H=208，8 列 9 行，frontend/src/constants/petSprite.js:2-4）。该窗口同时要先加载 launcher-CRwrn45F.js → launcherBootstrap-BOd8J3cO.js（1,129,706 B）+ vue-vendor（193,565 B）+ style（616,841 B）。
- 影响：应用启动 6 秒后再起一个完整 renderer：约 1.94MB JS+CSS 解析、一张 1536×1872 位图解码后常驻（2.87M 像素 × 4B ≈ 11.5MB RGBA），而它只是在隐藏窗口里画一张 192×208 的帧；backgroundThrottling: false 又让这个不可见窗口的定时器不参与节流。若用户从未最小化主窗（桌宠从不显示），这份 CPU/内存/磁盘成本完全白付，并且它挤在主窗首屏之后不久的时间窗里，低端机上会与首屏后续工作抢 CPU。
- 修复摘要：隐藏预热只保留骨架，图集解码与重资源加载推迟到桌宠真正第一次显示时。
- 修复文件：frontend/src/pages/LauncherPage.vue、frontend/src/composables/usePetSpriteAnimator.js、frontend/electron/main.js、frontend/src/constants/petSprite.js
- 建议测试：（无）

## 转交 deferred
- perf → 未指定 · frontend/src/pages/MomentsPage.vue:567 的评论轮询每 6s 对所有展开中的帖子各发一次 loadComments（展开 N 个帖 → N 请求/6s），属请求放大，但目前 N 由用户手动展开且量级小，证据不足以定为性能缺陷，转交后续观察。
- perf → 未指定 · frontend/src/pages/QuickChatPage.vue:365 与 ChatPage.vue:1184 的 10s 轮询在窗口隐藏时是否继续，取决于 Chromium 对隐藏窗口的定时器节流与 Electron backgroundThrottling 默认值；需实测（DevTools 后台网络面板）才能定级，未作为发现。
- perf → 未指定 · backend-agent/app/db.py 的 _pool/_redis 惰性全局单例在首次并发请求下可能重复建池（每个连接池 4 连接），属边界情况，需并发压测确认，未作为发现。
- perf → 未指定 · backend/lianyu-web/src/main/java/com/lianyu/web/agent/DesktopObserveOrchestrator.java 的并发护栏细节未逐行核对（占槽上限/限流维度），若需确认锁竞争与线程池饥饿需单独评审。

> 这些不是当前专家的 finding，但必须写入 verdict.deferred，不能静默丢弃。

## 机器提示（仅供参考）
- 没有检测到明显的跨专家重复候选。

## 交给主编
1. 读本文件与原始 handoff；不要只依赖机器提示。
2. 合并重复项，保留最可信的证据；被合并的 ID 记入 verdict.dropped。
3. 写 `verdict.json`（schemaVersion 2、findings、coverage、dropped），再写 `report.md`。
4. 只有完整覆盖默认四模块时，才更新 `docs/logistics/latest.md` 与 `.logistics/latest.json`。
5. 工作区根目录：`C:\Users\hp\Desktop\LianYu-PC`；运行目录：`C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261104-182459-9883f4`。
