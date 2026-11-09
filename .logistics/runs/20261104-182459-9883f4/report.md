# 性能审查报告 · 全项目（run `20261104-182459-9883f4`）

## 一句话结论

全项目第一次做性能维度审查：**8 条发现（2 high / 4 medium / 2 low，全部 `plan`）**，问题集中在三处——**三个窗口共用一份全局 CSS、其中首行是一条跨境 `@import`**，**桌宠/快捷聊入口把 element-plus 全量与 616KB 样式一起吞下**，以及 **Python agent 在事件循环上直接跑 pymilvus 同步 RPC**。没有发现需要在生产立刻回滚的容量级缺陷。

## 范围

| 项 | 值 |
|---|---|
| 模式 | `project`（全项目，非 diff、非最近提交） |
| 工作区 | `C:/Users/hp/Desktop/LianYu-PC` |
| Git | `main` @ `c652e14` — fix(relationship): 修正被刷高的历史信任分（V57） |
| 专家 | perf（单专家） |
| 审查单元 | 12 |
| 文件 / 行数 | reviewed 1100（tracked 1524，排除 424）/ 135978 行 |
| 语言构成 | Java 450 · JavaScript 252 · Python 220 · SQL 60 · Vue 57 |
| 测试命令 | 未检测到（本次未执行任何测试/压测） |

## 单元 × 专家覆盖矩阵

| 单元 | 范围 | 文件/行 | perf |
|---|---|---|---|
| u01 | backend/lianyu-service (1/3) | 120 / 12117 | reviewed |
| u02 | backend/lianyu-service (2/3) | 120 / 16014 | sampled |
| u03 | frontend/src (1/2) | 120 / 15840 | reviewed |
| u04 | scripts (1/2) | 120 / 12803 | sampled |
| u05 | backend/lianyu-dao | 110 / 2934 | reviewed |
| u06 | backend-agent | 102 / 16514 | reviewed |
| u07 | frontend/src (2/2) | 95 / 24648 | sampled |
| u08 | backend/lianyu-web | 71 / 7840 | reviewed |
| u09 | frontend/electron | 68 / 14745 | sampled |
| u10 | (root)、.github、admin 等 | 67 / 4923 | sampled |
| u11 | backend 小模块合集 | 59 / 3141 | sampled |
| u12 | lianyu-app、lianyu-common 等 | 48 / 4459 | sampled |

**6 reviewed / 6 sampled / 0 skipped**。sampled 的 6 个单元都做过跨文件模式扫描（`rg` 查循环内查询、同步 IO、无界集合、轮询定时器、正则编译），但没有逐文件通读——本报告的结论只覆盖被读到的代码。

## 按模块的发现

### frontend（3 条 + 1 条 hot-path + 1 条 memory-startup）

#### PRF-C4679 · high / medium-confidence · frontend · `render-blocking-third-party`

- **位置**：`frontend/src/pages/LandingPage.vue:342`（同 `LoginPage.vue:221`、`RegisterPage.vue:272`）
- **证据**：打包产物 `frontend/dist/assets/style-CMGPS_t0.css` 共 616,841 B，首行即
  `@charset "UTF-8";@import"https://fonts.googleapis.com/css2?family=Noto+Serif+SC:wght@500;600;700&family=Syne:wght@500;600;700&display=swap";`
  （位于 `:root` 变量之前，说明被 Rollup 从页面 `<style>` 提升为全局）；`dist/index.html`、`dist/launcher.html`、`dist/quick.html` 三个入口都 `<link>` 这同一份 CSS（`vite.config.js:71` `cssCodeSplit: !isElectronPack`）。
- **影响**：CSS 里的 `@import` 是渲染阻塞的——浏览器必须先把外部样式表取回来（或等到失败）才继续 CSSOM 与首屏绘制。三个窗口各付一次到 `fonts.googleapis.com` 的跨境请求；该域在部分网络环境会被黑洞，Chromium 要等连接/解析失败才放行，桌宠与快捷聊（要求秒开）的首帧因此可能被拖到秒级。而这两个窗口根本不用这组品牌衬线字。
- **修复**：把字体引入从全局 CSS 摘出——自托管 woff2 子集 + `@font-face`/`font-display: swap`，或页面级异步 `<link>`；保证三个 HTML 入口的首屏 CSS 零外部请求。

#### PRF-3C387 · medium / high-confidence · frontend · `bundle-size`

- **位置**：`frontend/src/main.js:3`（`manualChunks` 见 `frontend/vite.config.js:91`）
- **证据**：`dist/launcher.html` 只引 `assets/launcher-CRwrn45F.js`（645 B）与 616,841 B 的 CSS；该入口的模块图含 `launcherBootstrap-BOd8J3cO.js` **1,129,706 B**（文件内 `element-plus` 出现 10 次、`ElMessage` 5 次、`el-dialog` 1 次）与 `vue-vendor-DEeJUbX_.js` 193,565 B。合计约 **1.94MB** JS+CSS。`manualChunks` 只拆了 vue/pinia/i18n，没把 element-plus 与轻量入口分开。
- **影响**：桌宠窗口的内容是 192×208 的 canvas 加几个按钮，却要先把整套 element-plus 组件与主题样式下载并解析；快捷聊同样付这份成本，且每个窗口各自解析、各自占内存。低端机 + 机械盘冷启动可观测到空窗。
- **修复**：launcher/quick 两个入口独立装配、不注册全局 ElementPlus（必要时局部 import 或手写轻量实现），element-plus 只留在主窗口 entry。

#### PRF-54C33 · low / high-confidence · frontend · `render-loop`

- **位置**：`frontend/src/composables/usePetSpriteAnimator.js:143`
- **证据**：`tick()` 末尾无条件 `rafId = requestAnimationFrame(tick)`；真正重绘只在帧间隔到达时发生（`:124-141`，`frameInterval = 1000 / def.fps`）。`frontend/src/constants/petSprite.js:9` 的 `idle` 是 `fps: 6`，`playAnim('idle')` 在 `onMounted`（`:170-174`）启动且 `loop: true`，只有 `onUnmounted`（`:176-180`）才停。
- **影响**：60Hz 屏上每秒 60 次回调只有 6 次真正重绘（120Hz 屏空转约 95%）。桌宠是 alwaysOnTop + transparent 窗口（`frontend/electron/main.js:1416-1432`），透明窗口在 Windows 上本就少 GPU 合成路径，这条常驻 rAF 把渲染管线钉在满帧，妨碍整机进入低功耗空闲态。
- **修复**：调度与显示器刷新率解耦（`setTimeout(1000/fps)` 链式步进），rAF 只留给拖拽等需要 vsync 的场景；隐藏/不可见时停表。

#### PRF-19E08 · low / high-confidence · frontend · `quadratic-membership`

- **位置**：`frontend/src/pages/ChatPage.vue:1016`
- **证据**：`:1015-1021` 对 `messages.value` 每条执行 `incomingUsers.some(...)` / `incomingAssistants.some(...)`，`:978-987` 再对每条临时消息 `incomingUsers.find(...)`；触发者是常驻轮询 `:1184`（`NORMAL_POLL_MS = 10000`，`:589`）与快轮询 `:1157-1173`（`FAST_POLL_MS = 2000`，上限 90s），每次固定取最新 50 条（`MESSAGE_PAGE_SIZE = 50`，`:484`），上翻历史 `:919` 会让本地数组涨到数百条；`changed` 后还会 `sortMessagesInTimelineOrder()`（`:1037`）。
- **影响**：静态上界（rung C）：n=300、m=50 时单次轮询约 1.5×10⁴ 次字符串比较（比较 `content + imageUrl`），快轮询窗口 10s 内约 7.5×10⁴ 次；绝对量级在毫秒，但随历史翻页线性放大，且每次都会全量排序并触发气泡重渲染。属可清理的常数浪费，不是当前瓶颈。
- **修复**：incoming 预建 Map 做 O(1) 判定；按 `seq` 只处理增量，避免整表比较与全量排序。

#### PRF-8F8D9 · medium / high-confidence · frontend · `eager-asset-decode`

- **位置**：`frontend/src/pages/LauncherPage.vue:165`
- **证据**：`frontend/electron/main.js:1490-1493` `prewarmLauncherWindow()` 创建桌宠窗口但 `show: false`（`:1428`），由 `scheduleAuxWindowPrewarm` 在主窗 `did-finish-load` 后 6000ms 触发（`:1496-1513`）；该窗口用 `LAUNCHER_WEB_PREFS`（`:150-156`），其中 `backgroundThrottling: false`。`LauncherPage.vue` 的 `onMounted`（`:449`）在 `:461` **无条件** `applyPetId(...)`，其内部 `:165` 设 idle 帧、`:167-175` `new Image()` 加载并解码 spritesheet；可见性判断（`:454-460`）只用于 `launcherActive / startObserver`，没拦住图集。资源实测：`frontend/dist/pet` 共 **42 个文件 46.0MB**，单个 spritesheet 1.61–2.71MB（anya 1,761,046 B、ayaka 2,199,636 B），图集 1536×1872（192×208 × 8 列 9 行，`frontend/src/constants/petSprite.js:2-4`），解码后常驻约 11.5MB RGBA。
- **影响**：启动 6 秒后再起一个完整 renderer：约 1.94MB JS+CSS 解析 + 一张 1536×1872 位图常驻，只为在隐藏窗口里画 192×208；`backgroundThrottling: false` 让不可见窗口的定时器也不参与节流。若用户从不把桌宠叫出来，这份成本完全白付，而且正好挤在主窗首屏之后的 CPU 竞争窗口里。
- **修复**：隐藏预热只留骨架；`applyPetId` 拆成「占位帧」与「图集加载」，后者在 `isLauncherVisible()` 为 true 或 `launcher-shown` 时才执行；顺带评估图集降采样/按行动作切分。

### backend-agent（3 条）

#### PRF-74590 · high / medium-confidence · backend-agent · `event-loop-blocking`

- **位置**：`backend-agent/app/tools/memory_search_tool.py:100`
- **证据**：async 函数内直接 `milvus_store.search(...)`；`backend-agent/app/milvus_store.py:121` 是 pymilvus 同步 RPC，`:41` `has_collection`、`:44` `load_collection`、`:70` `insert`、`:102` `delete` 同为同步；全流程无 `asyncio.to_thread`（全仓只有 `gateway.py:146` 的注释提到模型调用走 to_thread）。超时 `backend-agent/app/config.py:91` `milvus_timeout_seconds = 10.0`。同类调用还在后台批量路径 `backend-agent/app/memory/relay.py:100-101`、`:133`。
- **影响**：agent 是**单进程单事件循环**的 FastAPI（`backend-agent/app/main.py`），这些同步 RPC 一跑，同进程其他会话的流式 token、健康检查、主动问候全部停摆。正常 Milvus 单次 search 十几到几十毫秒，并发会话下已可感知 token 抖动；Milvus 不可达/高负载时按 10s 超时阻塞事件循环 = 全站 10s 挂起，每次都触发。记忆检索是本产品高频工具（工具循环内可多轮调用）。
- **修复**：`asyncio.to_thread` 包装或换 async 客户端；`has_collection/load_collection` 挪进 lifespan 预热并缓存；给 Milvus 单独并发上限、超时降到 2-3s；relay 批内并发/批量写。

#### PRF-65E3F · medium / high-confidence · backend-agent · `sequential-await`

- **位置**：`backend-agent/app/data_readers.py:209`
- **证据**：`:209-211` 顺序 `_load_profile_facts` → `_load_recent_rows` → `_load_like_boosted`；`load_memory_context` `:242` `load_profile_block`、`:247` `load_relationship`（内部 `:26-66` 再顺序查 `relationship_state` 与 `relationship_event`）、`:260` `load_session_summary`（Redis）；`backend-agent/app/prompt_pipeline.py:225-273` 再顺序 `load_character_owner` → `load_emotion`。合计每轮 **7 次 MySQL + 1 次 Redis**，除 owner→emotion 外彼此无依赖。连接池 `backend-agent/app/config.py:65` `mysql_pool_size = 4`。
- **影响**：每条用户消息在首个 token 前要付 8 个串行往返：同机房 ~0.5-1ms/次（5-8ms），跨可用区 2-3ms/次（15-25ms），全部叠加在首 token 上。更要紧的是池只有 4 条连接而每回合串行 acquire 7 次——4 个以上并发回合就开始排队，agent 又是单副本单进程，高并发时首 token 被排队放大。
- **修复**：独立读取改 `asyncio.gather`（保留 owner→emotion 依赖）；按「并发回合 × 瞬时占用」重估 `mysql_pool_size`（同步 `.env.example`）；加装配耗时探针。

#### PRF-A20C5 · medium / high-confidence · backend-agent · `missing-cache`

- **位置**：`backend-agent/app/proactive_context.py:60`
- **证据**：`weather_fact = (await fetch_weather(city) or "").strip()` 调的是 `backend-agent/app/tools/weather_tool.py:38` 的无缓存 `fetch_weather`；只有同文件 `handle_get_weather` 用了 `_cache_get/_cache_put`（`:76-80`，TTL `config.py:50` `weather_cache_minutes = 15.0`）。`fetch_weather` 内部 `async with new_client() as own`，而 `backend-agent/app/http_client.py:25-40` 每次新建 `AsyncClient` + `AsyncHTTPTransport`（无 keep-alive）；超时 `config.py:29` `outbound_timeout=(15.0, 60.0, 30.0, 30.0)`（read 60s）。调用点在 prompt 关键路径：`backend-agent/app/prompt_pipeline.py:217`。
- **影响**：每次主动问候都要在模型调用前先等一次未缓存的 wttr.in 往返，且每次新建 TCP+TLS；wttr.in 常见 1-5s 响应，抖动时按 60s read 超时挂在 `assemble_prompt` 上。失败有 try/except 降级为「仅结合时段问候」，所以表现不是报错，而是**该轮主动发言被明显推迟或错过时机**。同城 15 分钟内多个角色/用户重复请求时全部重打外部接口。
- **修复**：复用既有天气 TTL 缓存入口与 `shared_client('weather')`；给天气单独 2s 超时；多副本时缓存换 Redis。

## 跨单元的系统性问题

1. **「一份产物服务所有窗口」的成本被分摊给了最轻的两个窗口**（PRF-C4679 / PRF-3C387 / PRF-8F8D9）。`cssCodeSplit: !isElectronPack` 让三个 HTML 入口共用一份 616KB CSS，而 `main.js` 的全局 ElementPlus 注册又通过共享模块把 element-plus 带进了 launcher 的 chunk；再叠加「隐藏窗口无条件解码图集」，桌宠这条本应最省资源的路径反而背了最重的资源。三处修法独立，但根因是同一条：没有「入口级依赖边界」。
2. **渲染/调度只看「有没有变化」，不看「需不需要这么快」**（PRF-54C33 / PRF-19E08）。桌宠用 rAF 跑 6fps 动画，聊天页用 2s/10s 轮询跑全量合并；都是把「响应式」当成默认正确，代价是常驻 CPU。
3. **Python 侧同步 SDK 直接住在 async 路径上**（PRF-74590）。这是与上一轮 reliability 报告的 REL-58FC8（同步 DB 异常穿透工具循环）同一条结构性问题：`backend-agent` 的 async 边界靠约定维持，没有机制拦住阻塞调用。
4. **外部依赖没有「成本上限」**（PRF-A20C5）。天气、字体这类非核心资源都挂在用户可感知的关键路径上，且超时取的是全局出站默认值（60s read），没有按用途分级。

## 修复顺序

1. **第一批（先做，改动小、收益直接，都在 frontend 构建面）**
   - PRF-C4679：把 `@import` 从全局 CSS 摘掉（三个 `.vue` 文件 + 产物验证）。
   - PRF-8F8D9：`applyPetId` 延迟到首次可见（`LauncherPage.vue` + `electron/main.js` 预热策略）。
   - 两条都在 `frontend/`，可同一批提交；验证 = `grep fonts.googleapis.com dist` 为空 + 启动 10s 内 renderer 内存对比。
2. **第二批（backend-agent，需设计取舍）**
   - PRF-74590：Milvus 调用移出事件循环（`milvus_store.py` 加 async 包装、超时收紧、lifespan 预热）。
   - PRF-65E3F：`asyncio.gather` 并行 + 连接池重估（与 `data_readers.py` 同一文件族，合并提交）。
   - PRF-A20C5：天气复用缓存 + 共享连接池 + 独立超时。
3. **第三批（需要构建结构调整，收益中等）**
   - PRF-3C387：launcher/quick 独立装配、element-plus 只进主入口（要动 `main.js` 与 `vite.config.js` 的 chunk 策略，风险最高，放最后）。
4. **随时可做（低风险、只影响常驻功耗）**
   - PRF-54C33（rAF → fps 定时步进）、PRF-19E08（Map 查表 + 增量合并）。

## 覆盖缺口与未验证项

- **没有执行任何 profile / 压测 / bundle analyzer**：本机无运行中的 frontend dev server 与 agent 运行时，`testCommand` 也未检测到。证据等级：前端为 **rung B**（实测产物字节 + 源码行号），后端为 **rung C**（复杂度 / 往返次数 / 超时常量推出的上界，已标注为估算）。
- 6 个单元是 `sampled`（u02、u04、u07、u09、u10、u11、u12 中的 6 个），只做了模式扫描 + 关键文件抽查，未逐文件通读。
- Java 侧线程模型（`DesktopObserveOrchestrator` 的并发护栏）未逐行核对，见 deferred。
- 数据库侧没有 `EXPLAIN`：索引结论来自迁移文件与 Mapper 静态检查，不是查询计划。
- 未验证运行态：Milvus 实际延迟、wttr.in 从部署机到外网的 RTT、桌面机首次启动的真实首帧时间。

## 跨专家 deferred（5 条）

| 来源 | 转交 | 文件 | 原因 |
|---|---|---|---|
| perf | **correctness** | `backend-agent/app/tools/memory_search_tool.py:100` | 主编核对时发现：`milvus_store.search(user_id, character_id, ...)` 与 `milvus_store.py:109` 的签名 `search(character_id, user_id, ...)` **参数顺序相反**，过滤条件变成 `character_id==user_id && user_id==character_id`；对照 `relay.py:101-103` 的 insert（character 在前）可确认调用点写反 → 向量召回几乎恒空（静默降级 LIKE）或命中他人记忆，属正确性/越权读，需单独定级。 |
| perf | — | `frontend/src/pages/MomentsPage.vue:567` | 评论轮询每 6s 对每个展开帖各发一次 `loadComments`，请求放大但量级小、需用户手动展开，证据不足以定级。 |
| perf | — | `frontend/src/pages/QuickChatPage.vue:365` | 窗口隐藏时 10s 轮询是否被 Chromium 节流取决于 `backgroundThrottling` 实测，未定级。 |
| perf | — | `backend-agent/app/db.py:56-70` | `_pool/_redis` 惰性单例在首次并发下可能重复建池，需压测确认。 |
| perf | — | `backend/lianyu-web/.../DesktopObserveOrchestrator.java` | 并发护栏（占槽上限/限流维度）未逐行核对。 |

## 主编对子代理结论的三处修正

1. **PRF-8F8D9 的资源体量**：子代理写「19 个文件共 35.9MB」，实测 `frontend/dist/pet` 是 **42 个文件 46.0MB**（单个 spritesheet 1.61–2.71MB）。结论不变，数字已更正。
2. **PRF-A20C5 的影响表述**：原文暗示「主动说话时没说话」像是失败；实际 `proactive_context.py:63-65` 有 `try/except` 降级为「仅结合时段问候」，所以真实表现是**变慢/错过时机**，不是报错。影响段已改写。
3. **反向追加一条**：核对 PRF-74590 调用点时发现 `memory_search_tool.py:100` 的参数顺序与 `milvus_store.search` 签名相反，这是正确性问题而非性能问题，已写入 deferred（见上表），未混进 perf findings。

## 说明

- 本次是**单模块、非默认专家组合**（只有 perf），按流程**不更新 latest**，只写本次 run 的 `report.md` 与 `verdict.json`。
- 本报告不含健康分、评分、基线或门禁结论。
- 其余维度未运行：需要安全/正确性/可维护性/可靠性结论时，分别执行 `/logi-security`、`/logi-correctness`、`/logi-maintainability`、`/logi-reliability`。
