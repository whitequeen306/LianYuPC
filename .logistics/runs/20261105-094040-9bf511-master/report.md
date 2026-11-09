# 予念（YuNian）全项目审查报告

- 运行：20261105-094040-9bf511-master · mode=project · HEAD main@c652e14
- 审查方式：只读静态审查（四位并行专家 + 主编证据复核），未修改任何源码

## 一、一句话结论与审查范围

**一句话结论：整体可用，但边界失守——backend-agent 的二十余个端点事实上没有鉴权（SEC-31F68），聊天流在断连重试时会重复落库并留下半句幽灵消息（COR-D1203），模型网关的熔断器把「用户取消」当成「上游故障」（REL-D63D0）。这三条建议在任何功能改动之前处理，其余问题按第六节的批次推进。**

范围：工作区 C:/Users/hp/Desktop/LianYu-PC，1100 个文件 / 135978 行 / 12 个单元，HEAD 为 main @ c652e14（fix(relationship): 修正被刷高的历史信任分（V57））。默认四位专家（正确性、安全、健壮性、可维护性）全部跑完并各自交付 handoff，collect 判定 complete=true、12 个单元无一 skipped。合并去重后共 **42 条保留发现**（严重 1 / 高 9 / 中 24 / 低 8），另有 2 条因同一根因被并入相邻条目、18 条转入 deferred。可选专家 perf 本轮未选择，性能维度未运行。

## 二、覆盖情况：单元 × 专家矩阵

表中 reviewed(n) 表示该专家在该单元逐行读过 n 个文件（或按模式精读关键路径），sampled(n) 表示按模式扫描后抽查了 n 个文件——sampled 不等于全量已读，读的是模式与调用点，不是每一行。

| 单元 | 范围 | 文件 / 行 | 正确性 | 安全 | 健壮性 | 可维护性 |
|---|---|---|---|---|---|---|
| u01 | backend/lianyu-service (1/3) | 120 / 12117 | reviewed(9) | reviewed(14) | reviewed(9) | sampled(9) |
| u02 | backend/lianyu-service (2/3) | 120 / 16014 | reviewed(6) | sampled(7) | sampled(5) | sampled(14) |
| u03 | frontend/src (1/2) | 120 / 15840 | reviewed(10) | sampled(4) | reviewed(11) | sampled(16) |
| u04 | scripts (1/2) | 120 / 12803 | sampled(4) | sampled(3) | sampled(6) | sampled(12) |
| u05 | backend/lianyu-dao | 110 / 2934 | reviewed(9) | reviewed(4) | sampled(4) | sampled(6) |
| u06 | backend-agent | 102 / 16514 | reviewed(8) | reviewed(11) | reviewed(14) | sampled(11) |
| u07 | frontend/src (2/2) | 95 / 24648 | reviewed(7) | sampled(3) | sampled(7) | sampled(15) |
| u08 | backend/lianyu-web | 71 / 7840 | reviewed(8) | reviewed(13) | reviewed(7) | reviewed(9) |
| u09 | frontend/electron | 68 / 14745 | sampled(3) | reviewed(8) | reviewed(8) | sampled(5) |
| u10 | 其他模块（(root)、.github、admin 等） | 67 / 4923 | sampled(6) | reviewed(6) | reviewed(7) | sampled(11) |
| u11 | backend（小模块合集 59 文件） | 59 / 3141 | sampled(5) | reviewed(10) | sampled(3) | sampled(6) |
| u12 | 其他模块（backend/lianyu-app、backend/lianyu-common、backend 等） | 48 / 4459 | sampled(6) | reviewed(7) | sampled(3) | sampled(9) |

- 覆盖结论：expected = 四位专家，received = 四位专家，missing 为空，complete = true；u08（backend/lianyu-web）四位全部 reviewed，u01、u06 三位 reviewed，u03、u05、u09、u10 两位 reviewed，其余单元 1 位 reviewed，u04（scripts）四位均为 sampled。
- 读法提示：u04（scripts）四位专家都只到 sampled，是本轮覆盖最薄的单元；u02、u07、u09、u10、u11、u12 各只有 1–2 位专家 reviewed，其余为模式扫描。

## 三、发现（按单元组织）

严重度口径：严重=可直接被外部利用或造成不可逆数据损坏；高=真实可达且有明确业务后果；中=有边界条件或需要并发/异常配合；低=健壮性与工程遗留。每条保留证据、影响与修复摘要，完整字段见 verdict.json。

### u01 backend/lianyu-service (1/3)（保留 4 条：严重 0 / 高 1 / 中 2 / 低 1）
**高 · REL-205D3 · 后台 AI 任务按异常文案判定可重试，恰好漏掉熔断/隔舱的「对方模型暂时繁忙」，任务直接进无人消费的 DLQ**
backend/lianyu-service/src/main/java/com/lianyu/service/ai/background/AiBackgroundConsumer.java:102
- 证据：isAgentTransient 沿 cause 链找到 `BusinessException(ErrorCode.AI_PROVIDER_ERROR)` 后按 message 子串判定：`message.contains("prompt 组装失败") || message.contains("消息发送失败") || message.contains("服务暂时不可用")`（AiBackgroundConsumer.java:102-116）。而 AiResilience.java:108 在熔断/隔舱拒绝时抛的文案是「对方模型暂时繁忙，请稍后再试」，…
- 影响：上游模型真的繁忙或熔断打开——最应该退避重试的场景——反而被判为不可重试，日记、动态评论、语音通话总结等后台任务被整条丢弃，只在日志留一行 error；DLQ 无消费者也无告警，等于静默丢数据。另外重试靠 `Thread.sleep` 阻塞 RabbitMQ listener 线程（默认 1500ms×n），并发下会占满消费线程。
- 修复：用错误码 + 明确的 retryable 标记替代文案匹配，并让 DLQ 至少有消费者或告警，而不是黑洞。

**中 · COR-B2E9F · message.seq 只靠 Redis INCR，计数器丢失后不会用 DB 最大值回填，复用旧 seq 撞唯一键**
backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java:485
- 证据：reserveSeqBlock(:483-486) 只有 `redisTemplate.opsForValue().increment(SEQ_KEY_PREFIX + conversationId, safeCount)`，取不到值时回落 safeCount；全仓 grep 确认没有任何地方用 `MAX(seq)` 播种该键（只有 CharacterService.java:298 与 delete/clearMessages 会删键）。message 表有 `UNIQUE KEY uk_conv_seq (conversation_id, seq)`（V1__init_schema.sql:54）。…
- 影响：该会话下一次 insert 直接撞 uk_conv_seq 抛 DuplicateKeyException，发送接口 500；由于 seq 从 1 重新开始，同一会话会连续失败，直到计数追上历史最大值，期间没有任何自愈路径，也没有降级提示。
- 修复：计数器缺失时用数据库最大值播种，并把「播种 + 分配」做成原子操作；冲突时重试一次。

**中 · SEC-00767 · 改密不吊销已签发 token，被盗会话在 30 天内继续有效**
backend/lianyu-service/src/main/java/com/lianyu/service/auth/impl/AuthServiceImpl.java:152
- 证据：AuthServiceImpl.java:152-162 的 changePassword 只做 `userMapper.updateById(user)`（写新 BCrypt 口令），既不调用 StpUtil.logout 也不失效其它会话；`backend/lianyu-app/src/main/resources/application.yml:133-146` 的 sa-token 配置为 `timeout: 2592000`（30 天）、`active-timeout: -1`、`is-concurrent: true`、`is-share: false`、`is-read-cookie: false`，…
- 影响：账号被盗后『改密码』这个最常用的自救动作对攻击者无效：旧 token 仍可读写该用户的对话、记忆、API Key 配置，最长 30 天；用户会误以为自己已经踢掉了对方。
- 修复：改密成功后立即踢掉该用户的全部 sa-token 会话，并让当前请求返回需要重新登录的语义。

**低 · COR-3F587 · msg_seq 键名前缀与 seq 分配函数在四个服务里各自定义、各自实现**
backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java:72
- 证据：同一个 Redis 键前缀在四处各自声明：CharacterService.java:60（MESSAGE_SEQ_KEY_PREFIX）、ConversationService.java:72（SEQ_KEY_PREFIX）、GroupChatService.java:104、VoiceCallService.java:62，全部是字面量 "msg_seq:"；分配逻辑也是两份实现——ConversationService.java:479-487 与 GroupChatService.java:1314-1322 各自写了一份 getNextSeq/reserveSeqBlock，…
- 影响：任何一处改前缀或改增量语义，同一会话就会长出两套编号，只有在撞 uk_conv_seq 时才会暴露；这正是 AGENTS.md「单一事实来源」明令禁止的形态，也让上面那条 seq 播种修复没法一次改全。
- 修复：抽一个 MessageSequenceAllocator 单点持有键前缀与分配逻辑，四个服务统一注入使用，并加一条交错分配不重复的测试。

### u02 backend/lianyu-service (2/3)（保留 5 条：严重 0 / 高 0 / 中 5 / 低 0）
**中 · COR-8FD65 · 动态评论锁没有持有者标识，90 秒 TTL 过期后可被抢占，unlock 又会删掉别人的锁**
backend/lianyu-service/src/main/java/com/lianyu/service/moments/MomentsCommentOrchestrator.java:816
- 证据：tryLock(postId) 在 :816-820 用 `redisTemplate.opsForValue().setIfAbsent(LOCK_PREFIX + postId, "1", Duration.ofSeconds(90))`，值恒为 "1"，没有 owner token；unlock(postId) 在 :822-824 直接 `redisTemplate.delete(LOCK_PREFIX + postId)`。锁内要做模型调用（executePeerComment :326-400），超过 90 秒锁自动过期，第二个 worker 拿到锁；第一个 worker 在 finally 里无条件 delete，…
- 影响：同一条动态下同一角色会发出重复评论（并重复计费），processAuthorReplyJob(:271-296) 也会因锁被误删而重复执行作者回复任务；因为锁值没有 owner，日志里也无法判断到底是谁删的。
- 修复：锁值改成每次尝试唯一的 owner token，释放时用 Lua 比较后删除，并把 TTL 与外层模型调用超时对齐（或加续期）。

**中 · COR-493CC · 关系分数全类无事务无版本号，衰减批量与用户回合都用整行 updateById，后写者覆盖先写者**
backend/lianyu-service/src/main/java/com/lianyu/service/relationship/RelationshipStateService.java:185
- 证据：该类只有 @Service/@RequiredArgsConstructor，grep 全文件 Transactional 计数为 0；RelationshipState 实体没有 @Version。decayAll 在 :177-181 一次 select 最多 500 行，随后 :184-185 逐行 applyDecay + `relationshipStateMapper.updateById(state)`（写回全部列）；recordUserTurn/recordAssistantTurn 也走 :132/:165 的 updateById，…
- 影响：用户刚聊完看到好感上涨，下一次 30 分钟衰减任务把整行写回旧值，增量凭空消失并触发 phase 重算；两个并发的用户回合（连续快发）之间也会互相吞掉分数，表现为「聊了没反应」，且没有任何异常可见。
- 修复：把分数更新改成数据库侧原子增量（或加乐观锁重试），decay 只更新它自己负责的列，避免整行覆盖。

**中 · COR-CC8D6 · 衰减批次被「不满一天」的行长期占满：这些行不写 last_decay_at，永远排在最前，其余行再也轮不到**
backend/lianyu-service/src/main/java/com/lianyu/service/relationship/RelationshipStateService.java:180
- 证据：decayAll(:177-181) 用 `orderByAsc(lastDecayAt).last("LIMIT 500")` 取批（DECAY_BATCH_LIMIT=500，:43）；applyDecay 在 `days < 1` 时于 :200 `return false`，此时既不写 lastDecayAt 也不 update，于是这些行的 last_decay_at 保持 NULL 或旧值——MySQL 中 NULL 排序最前，每轮都会被优先选中、每轮都被丢弃，稳定占住 500 个名额。:174-179 的注释称「结算过的行会排到队尾，形成轮转」，但那只对通过 days≥1 的行成立，被跳过的活跃行不参与轮转。
- 影响：当活跃行数超过 500（代码注释提到线上已 936 行）时，批次会被最近活跃过的行填满，其余用户的关系衰减彻底停止：亲密不回落、期待不累积，而且没有任何错误或告警，只能通过「分数不再变化」间接发现。
- 修复：取批条件加上「确实到期待结算」的过滤，让不满足条件的行不再占用批次名额。

**中 · MNT-D4749 · 主动聊天与朋友圈两个定时任务各自复制同一套候选装载 / 概率抽样骨架**
backend/lianyu-service/src/main/java/com/lianyu/service/moments/MomentsScheduler.java:50
- 证据：backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ProactiveChatScheduler.java:63 `public void tick() {`（114 行）与 backend/lianyu-service/src/main/java/com/lianyu/service/moments/MomentsScheduler.java:50 `public void tick() {`（114 行）结构逐段重合：characterIds 收集 → `Map<Long, Character> characterMap = new HashM…
- 影响：「扫候选会话 → 按角色/亲密度打分 → 概率抽样 → 配额内逐条 try/catch」是同一件事，却有两份实现和两套命名（Scored/Weighted、quota/maxPosts、effectiveProb/effectiveP）。改一处限流口径（比如按用户维度去重）必须记得改两处，否则只修一半；两套 @Value 键名也让运维在配置文件里找不到统一开关。
- 修复：抽出 CandidateScanner（装载 character/最新消息/消息计数）与 ProbabilityGate（排序 + 配额 + 抽样 + 逐条异常隔离），两个调度器只保留各自的打分与执行动作。

**中 · MNT-D8078 · 角色广场把 4 语人设与整段 prompt 写进 2602 行 Java，形成第二份人设文案来源**
backend/lianyu-service/src/main/java/com/lianyu/service/square/CharacterSquareCatalogGenshin.java:14
- 证据：backend/lianyu-service/src/main/java/com/lianyu/service/square/ 下 8 个 Catalog 合计 2602 行：CharacterSquareCatalog.java 462、CharacterSquareCatalogGenshin.java 590、CharacterSquareCatalogDal.java 421、CharacterSquareCatalogBlueArchive.java 369、CharacterSquareCatalogReZero.java 326、CharacterSquareCatalogDanganronpa.java 227、Ch…
- 影响：AGENTS.md §2 规定 prompt 只允许出现在 backend-agent/app/prompts 与 Python 代码里；广场角色的整段人设却以 Java 字符串形式存在并由 Java 传给 Python，等于第二份 prompt 事实来源——改人设口径要同时改 Java 常量与 Python 模板，且 Java 侧无法参与 prompt golden。…
- 修复：把广场角色从 Java 常量迁移成后端可读的数据文件（JSON/YAML，按角色一文件），Java 只保留加载与校验，prompt 字段交 Python 侧模板。

### u03 frontend/src (1/2)（保留 1 条：严重 0 / 高 0 / 中 1 / 低 0）
**中 · COR-78D9B · 四语契约漂移：zh-TW 语言包从未注册成为孤儿文件，en/ja 缺键后回落成中文**
frontend/src/i18n/index.js:17
- 证据：i18n/index.js:7-9 只 import zh/ja/en，:17 `messages: { zh, ja, en }`，:16 `fallbackLocale: 'zh'`；locales/zh-TW.js（约 26KB）在全仓没有任何引用（grep -rn zh-TW frontend/src 只命中它自身与 utils/dateLocale.js:4）；constants/language.js:3 `SUPPORTED_LANGUAGES = ['zh', 'ja', 'en']`，normalizeLanguage 永远返回不了 zh-TW。…
- 影响：英文/日文界面出现中文按钮与确认文案（AGENTS.md §3.2 要求 zh/zh-TW/en/ja 四语齐）；繁体用户只能看简体，繁中文件成为死资产，之后每个新增 key 都会继续漏，且没有测试能拦住。
- 修复：注册 zh-TW 语言与选择项、补齐缺失键，并加一条键位一致性单测把四语锁住。

### u04 scripts (1/2)（保留 3 条：严重 0 / 高 1 / 中 2 / 低 0）
**高 · SEC-86656 · 生产账号口令硬编码在已跟踪的运维脚本里**
scripts/_prod_verify.py:22
- 证据：scripts/_prod_verify.py 是 `git ls-files` 跟踪的文件（提交 3399884），第 21-22 行以源码常量写死账号与口令，用于对 `http://127.0.0.1:8080` 做登录并跑真实对话验证；同文件第 23 行还会读取服务器 `/opt/lianyu/.env`。也就是说一个真实生产账号的口令进入了仓库历史，任何能读仓库的人（含 fork/CI 日志/历史克隆）都能直接登录生产。
- 影响：仓库读者可直接以该账号登录生产环境，读写其对话与记忆、消耗其模型额度；若该账号被复用为管理员或与其它服务共享口令，影响面进一步扩大。因为已经进了 git 历史，仅删除该行不足以消除暴露。
- 修复：立即轮换该账号口令，把脚本改成从环境变量读取凭据，并清理仓库历史中的明文口令。

**中 · MNT-A038F · scripts 下 49 个 _ 前缀一次性脚本无人引用（3968 行），部署入口实际只有 _cloud_deploy_pull.py**
scripts/_cloud_deploy_backend_layer.py:1
- 证据：`git ls-files scripts | grep -c '^scripts/_'` = 55；对每个脚本文件名在被跟踪的 md/py/ps1/yml/json/mjs/js/sh 里做全文引用检索，仅 6 个有引用：_cloud_deploy_pull.py（AGENTS.md、Pets/skills/hatch-lianyu-pet/SKILL.md、scripts/README.md）、_upload_update_assets.py、_cloud_deploy_layered.py、_prod_verify.py、_audit_installer_unpack.py、_upload_wechat_channel.py；…
- 影响：发布链路的「哪一个是真入口」被 54 个同族脚本淹没：它们都叫 _cloud_deploy_*、都连生产 SSH、口径还各不相同（见下一条 timeout/PTY 漂移），误跑一个就可能对生产执行过期逻辑。同时 scripts 目录的维护成本与审计面被无谓放大。
- 修复：保留 6 个有引用的脚本，其余 49 个移到 local/ 或直接删除；并在 scripts/README.md 写清唯一入口。

**中 · MNT-0829F · 部署脚本 16 份 load_env 与 49 份 paramiko 样板，超时口径漂移且仍有 get_pty=True**
scripts/_cloud_deploy_backend_layer.py:26
- 证据：`grep -rn "def load_env" scripts` 命中 16 处，其中 14 处逐字相同（每份 9 行，含 scripts/_cloud_chat20.py:13、scripts/_cloud_deploy_agent_base_url.py:13、scripts/_cloud_deploy_agent_layer.py:16、scripts/_cloud_deploy_backend_layer.py:16、scripts/_cloud_deploy_backend_only.py:13、scripts/_cloud_deploy_prep.py:22、scripts/_prod_verify.py:35、scr…
- 影响：同一份「读 .env → 连 SSH → 跑命令」的逻辑有 16 份副本，改一处连接参数（端口、密钥、超时、非 PTY）要改 16 处，实际只改了 1 处 —— 这就是 PTY 假死逻辑还活着的直接原因：_cloud_deploy_pull.py 改对了，同族脚本没跟上。超时 2400/3600 的漂移还让「一条命令卡多久算失败」不可预测。
- 修复：抽出 scripts/_ssh.py（load_env + connect + run(cmd, timeout, must) ，统一 get_pty=False），让保留的部署脚本各自只留业务命令序列。

### u05 backend/lianyu-dao（保留 1 条：严重 0 / 高 0 / 中 1 / 低 0）
**中 · MNT-235E2 · V55 建了 agent_capability_audit 表，写入方 AgentBridgeService 已随能力层删除**
backend/lianyu-dao/src/main/resources/db/migration/V55__agent_capability_audit.sql:15
- 证据：CONFIRMED_DEAD。backend/lianyu-dao/src/main/resources/db/migration/V55__agent_capability_audit.sql:15 `CREATE TABLE IF NOT EXISTS \`agent_capability_audit\` (`，:11 注释「Ownership: written by Java (AgentBridgeService), never by the Python agent」，表头还写「the product surfaces a 'recent actions' panel」。…
- 影响：schema 与代码的叙事脱节：迁移注释描述了一个已下线的「最近动作」面板，运维/新同学据此以为存在审计数据可查，实际永远是空表。Flyway 历史不可改，越晚处理越只能靠新增迁移绕开，成本会持续累积。
- 修复：新增一条迁移 DROP 该表（或明确保留为历史归档并修正注释），并同步清理 target 里的残留报告口径。

### u06 backend-agent（保留 10 条：严重 1 / 高 3 / 中 2 / 低 4）
**严重 · SEC-31F68 · backend-agent 全部端点无鉴权：X-User-Id 头可选即短路，且被公网 nginx 直接暴露**
backend-agent/app/main.py:988
- 证据：20+ 个端点统一写成 `if user_id_header and str(body.user_id) != user_id_header: raise HTTPException(403)`（main.py:206 chat-stream、:988 memory/write、:1064 proactive-turn、:146 desktop-observe、:469/:497/:660/:732/:787/:840/:860/:915/:1062/:1143/:1177/:1211/:1245 等），不带 X-User-Id 时整个校验被短路，服务端没有任何会话/令牌校验。…
- 影响：未认证的公网请求可以直接调用记忆写入（污染任意用户 prompt）、记忆重算/中继（触发全量向量重投影，可当 DoS）、声纹注册/删除、TTS 合成、任意 base_url 的模型调用等 20+ 端点；`/agent/chat-image` 还能白用平台视觉密钥消耗成本。这是本项目最外层的信任边界缺失。
- 修复：把 agent 运行时变成只接受来自 Java 的可信调用：nginx 侧加 auth_request 或直接不再暴露 /api/agent/，Python 侧加统一的共享密钥/HMAC 中间件并把『缺头即放行』改为『缺凭据即 401』。

**高 · SEC-E49FD · 记忆写入端点信任请求体里的 userId/characterId，缺对象级归属校验**
backend-agent/app/memory/writer.py:122
- 证据：main.py:982-1007 的 `/agent/memory/write` 把 `body.user_id` / `body.character_id` 原样传给 writer.write_memories（:1003）；writer.py:122 的 `write_memories(user_id, character_id, memories)` 直接进入 `_insert_new(...)`（:145-149）落 memory_meta，未校验 character 是否属于该 user、也不校验调用者身份（X-User-Id 由客户端自带，nginx 不覆盖）。…
- 影响：任意人可向任意 (userId, characterId) 注入任意条『记忆』（summary/importance 可控），这些内容之后会被拼进受害者的人设 prompt —— 相当于第三方可以给别人的 AI 伴侣植入虚假事实、偏好或指令式记忆，属于跨租户写入与 prompt 污染。
- 修复：记忆写入必须由服务端身份推导 userId，并校验 characterId 归属；无法证明归属的请求一律拒绝。

**高 · SEC-3BB6D · Python 出站 HTTP 无 SSRF 校验，模型 base_url 可指向内网或云元数据**
backend-agent/app/http_client.py:25
- 证据：backend-agent/app/http_client.py:25-40 的 new_client() 只设置超时、UA 与 local_address，没有任何目标地址校验；app/gateway.py:288 直接把 `normalize_openai_base_url(base_url)`（:92-110 仅拼接 /v1/chat/completions）交给该 client POST。base_url 来自请求体（main.py 的 textBaseUrl/visionBaseUrl）或 DB 配置，经上面那条无鉴权端点即可由外部指定。…
- 影响：未认证攻击者可让后端容器向内网服务（MySQL/Redis/MinIO/Milvus、docker 网络里的 Java 服务）或云厂商元数据端点 169.254.169.254 发起 POST，读取响应或至少做端口/服务探测（盲 SSRF），并可借错误信息外带内网内容。
- 修复：在 Python 出站层加与 Java 等价的校验：协议白名单、域名/IP 黑名单、DNS 解析后固化 IP，并禁止跟随跨域重定向。

**高 · REL-D63D0 · 模型网关熔断器把客户端取消/断连计为上游失败，5 次中断即可误开共享熔断**
backend-agent/app/gateway.py:279
- 证据：stream_text 与 _post_chat 都用 `except BaseException as exc: await breaker.on_error(exc); raise`（gateway.py:279、407）；resilience.py:132 的 `on_error` 只把 `GatewayBusinessError` 记成功，其余一律 `self._results.append(False)`，CLOSED 下窗口 20 / 最小 5 样本 / 50% 即 `_open()`，OPEN 30s，HALF_OPEN 任一失败立即重开。用户关闭窗口或点「停止生成」时，SSE 任务被取消，…
- 影响：正常用户行为（中途关窗、切换会话、点停止）就能在 30s 内把共享熔断器打开，此后所有用户的会话在 try_acquire 处快失败，统一提示「对方模型暂时繁忙，请稍后再试」；HALF_OPEN 期再遇到一次取消即重新打开，故障可自持。上游其实完全健康，监控与日志里也只会看到 breaker OPEN 的 warning，难以定位到“取消”这个真实原因。
- 修复：把取消/断连与上游失败区分开：取消不参与熔断统计，只记录一次 info 日志。

**中 · COR-F368E · 工具循环的无工具分支把 system prompt 整段丢掉，触发后角色人设与记忆静默消失**
backend-agent/app/tool_loop.py:128
- 证据：run_tool_loop 在 :128 `if not specs:` 时走 `gateway.chat_sync(system=None, user_text=_flatten(messages), ...)`，而 _flatten(:205-214) 的实现是 `for msg in messages: if role == "system": continue`——该分支把组装好的 system prompt（人设/记忆/关系/时间）全部丢弃，只剩用户原话。两个调用点 chat_turn.py:265 与 graphs/proactive_turn.py:116 都在 `if tools:` 之后，…
- 影响：一旦命中，回复不再是角色口吻、记忆与关系上下文全部丢失，但 HTTP 仍 200、无告警，属于完全静默的降级；因为两份工具名单没有契约校验，这种不一致只能在用户投诉里被发现。
- 修复：该分支改为把 system 作为 system 消息传下去，并给「工具全部解析失败」加显式日志与降级告警；工具名单收敛为单一来源。

**中 · REL-7EE2B · 记忆 outbox 卡在 RUNNING 的行不会自愈，重排只有手工接口且全仓无调用方**
backend-agent/app/memory/relay.py:207
- 证据：drain_once 只认领 `status='PENDING'` 的行（`UPDATE ... WHERE status='PENDING'` 影响行数判定）；进程在认领后崩溃或重启，该行永远停在 RUNNING。relay.py:207 提供了 `requeue_stuck()`（DEAD 与超 10 分钟的 RUNNING），但唯一入口是 main.py:967-971 的 `POST /agent/memory/relay/requeue`；`git grep -n "relay/requeue"` 在 backend/scripts/CI 中零命中，说明没有任何定时调用。
- 影响：每次部署/重启/OOM 都可能留下永久 RUNNING 的行：对应记忆不写 Milvus，向量检索永远查不到这条，角色表现成「突然忘了」，而日志里只有一条认领成功的 info。DEAD 行（超过重试上限）同样只能人工重排，且没有告警。
- 修复：在 _relay_loop 中定期调用 requeue_stuck()，并给 RUNNING 超时与 DEAD 行打指标日志。

**低 · SEC-0DE83 · 对话内容（SSE 帧原文）以 INFO 级别写入日志**
backend-agent/app/main.py:247
- 证据：backend-agent/app/main.py:247-249 在前 3 帧上执行 `logger.info("chat-stream frame[%d]=%s", frames, frame[:160].replace("\n", "\\n"))`，frame 是包含模型回复正文的 SSE 数据行；:223-225 也会记录 userId、model 与 system 长度。`backend/lianyu-app/src/main/resources/application.yml` 中 com.lianyu 侧日志级别为 DEBUG 且 prod profile 未收紧。
- 影响：角色回复（含用户输入触发的私密内容）会以明文进入容器日志并被 docker logs/日志采集长期留存，扩大日志读写方的数据可见面；排障时贴日志也容易把用户对话带出去。
- 修复：把帧内容日志降级为 debug 并默认关闭，或只记录帧类型与长度而不记正文。

**低 · REL-4B9D6 · 记忆批量写入注释声称「整批同一事务，任一失败全回滚」，实现却是逐条事务提交**
backend-agent/app/memory/writer.py:123
- 证据：writer.py:3 与 :123 都写「整批在同一事务内，任一失败全回滚」，main.py:917 的 endpoint docstring 同样写「整批单事务」；但 `_insert_new`(175)、`_update_meta`(196)、`_replace_and_enqueue`(209) 各自 `async with transaction()`，逐条提交。main.py:952-953 在异常时 `raise HTTPException(500, "memory write failed")`，此前已提交的条目保留。
- 影响：批次中途失败时部分记忆已落库、部分丢失，调用方只拿到 500，无法判断写入了多少；Java 侧 AgentMemoryAdapter 见 null/error 会回退到启发式重写（AgentMemoryAdapter.java:95-104），是否重复完全依赖两侧 source_hash 口径一致。按注释假设「全回滚」去写恢复逻辑的人会踩坑。
- 修复：把事务边界上移到 write_memories，让实现与文档一致；helper 改为接收 cursor。

**低 · REL-FEB0B · 工具循环只把 httpx/RuntimeError 当作可降级错误，其余异常会让整轮回复失败**
backend-agent/app/tool_loop.py:101
- 证据：`except (httpx.HTTPError, RuntimeError) as exc:` 时把异常写进工具结果继续对话（tool_loop.py:101）；ValueError/KeyError/aiomysql 等异常直接冒出 execute_tool_calls，最终由 main.py 外层 `except Exception` 收尾，产出 `sse_error("生成失败，请重试")` 并结束整轮。
- 影响：单个工具的数据异常（例如返回 NULL 触发 KeyError、参数校验 ValueError）会把本可降级的局部失败升级成整条回复失败，已生成的文本作废，用户只看到一句通用错误，排障时也缺少是哪个工具的信息。
- 修复：把工具执行的所有常规异常都降级为工具错误文本，并记录堆栈。

**低 · MNT-89C04 · backend-agent 的 profile_context 只被自己的测试调用，测试名 matches_java 已无对端**
backend-agent/app/memory/profile_context.py:39
- 证据：CONFIRMED_DEAD（生产路径）。backend-agent/app/memory/profile_context.py:39 `def build_profile_context(items: Sequence[Iterable]) -> str | None:`（文件 59 行）；`grep -rn "profile_context\|build_profile_context" backend-agent` 只命中自身与 backend-agent/tests/test_profile_context.py:13（import）、:38 `def test_profile_context_matches_java(go…
- 影响：一条保护「Python 与 Java profile 拼装一致」的 golden 测试，实际两个对端只剩一个，却依然全绿。它给 reviewer 一种跨端已对齐的错觉，也把 59 行无人调用的拼装逻辑长期留在记忆模块里，干扰「记忆上下文到底由谁组装」的判断。
- 修复：二选一：删除 profile_context.py + 其测试 + golden（对端已不存在），或把它真正接进记忆上下文组装并重命名为 Python 侧唯一事实来源。

### u07 frontend/src (2/2)（保留 3 条：严重 0 / 高 1 / 中 2 / 低 0）
**高 · COR-D1203 · 流式中断重试没有幂等键：同一条用户消息落库两次，第一次的半截回复还会变成幽灵消息**
frontend/src/pages/ChatPage.vue:1440
- 证据：ChatPage.vue:1437-1443 在 attemptStream() 抛错且 isNetworkError(err) 为真时，`await sleep(800)` 后直接再调一次 attemptStream()；useStreamAbort.js:10 的 `if (err.name === 'TypeError') return true` 把任何 TypeError（包括流读到一半 reader.read() 抛出的）都判成网络错误。api/conversation.js:67 的 POST /conversation/{id}/messages/stream 请求体只有 content/imageUrl，…
- 影响：弱网或代理抖动（哪怕只是首帧之后连接被重置）时，用户看到自己发的话出现两遍，服务端生成两次、上游计费两次；第一次的半截回复被静默写进历史，刷新后出现客户端从未完整展示过的幽灵消息，并进入下一轮上下文。
- 修复：给每次发送引入客户端幂等键并在重试时复用，后端按键去重；同时把重试限定在请求尚未产生任何响应字节的阶段。

**中 · REL-6FD30 · 前端聊天流没有空闲超时，半开连接会让输入框永久停在等待态**
frontend/src/pages/ChatPage.vue:1430
- 证据：attemptStream() 在 `drainAssistantStream(response, { signal })` 处进入 `reader.read()` 循环（assistantStreamDrain.js:26-35），除 AbortController 外没有任何计时器；useStreamAbort.js 只负责 abort，没有 watchdog；全前端搜不到 stream 相关的 timeout 常量。waitingReply 直接 disabled 输入框与发送键（ChatPage.vue:215/225/250/1363），只在 finally 里复位（:1476）。
- 影响：代理/NAT 保持 TCP 但不再返回数据，或服务端线程卡死不结束响应时，read() 永不 resolve，waitingReply 永久为 true：用户无法再发消息，界面一直显示正在输入，没有错误提示也不会自动重试；只能离开页面重进。网络层错误只覆盖了 sendMessageStream 立刻抛错的情况。
- 修复：给流式读取加空闲超时：超过 N 秒没有新 chunk 就 abort 并提示可重试。

**中 · MNT-07031 · 前端三个零引用模块仍被注释描述为生效中（antiDebug / 截图分享 / ASR HTTP）**
frontend/src/utils/antiDebug.js:12
- 证据：CONFIRMED_DEAD。`grep -rn "antiDebug\|initAntiDebug" frontend/src frontend/electron frontend/shared` 只命中本文件定义 frontend/src/utils/antiDebug.js:12 `export function initAntiDebug() {`（文件 93 行）与两处注释：frontend/src/main.js:38「---- 全局错误捕获（须在 initAntiDebug 之前注册）----」、frontend/src/utils/logger.js:4「生产环境 console.* 被 antiDebug 吞掉，…
- 影响：注释把一个不存在的生产防护说成在用：后来者会以为 console 已被兜底、全局错误已被捕获，排查线上无日志时会先怀疑构建流程而不是这段代码根本不存在。317 行（93 + 220 + 12）死代码还让每次前端重构都要顺带确认它们是否还该保留；同时形成「前端无调用方、后端仍暴露 /api/asr/transcribe」的悬空契约。
- 修复：删除三个零引用模块，改写两处误导性注释，并把后端 ASR HTTP 端点的去留交 correctness 判定（前端实际只用 WS 通道）。

### u08 backend/lianyu-web（保留 5 条：严重 0 / 高 0 / 中 4 / 低 1）
**中 · SEC-C279B · 客户端 IP 取自 X-Forwarded-For 最左值，可伪造绕过 per-IP 登录限流**
backend/lianyu-web/src/main/java/com/lianyu/web/util/ClientIpResolver.java:24
- 证据：ClientIpResolver.java:24-29 取 `X-Forwarded-For` 的第一个（最左）值作为客户端 IP；`backend/lianyu-app/src/main/resources/application.yml:172` 的 `lianyu.security.trust-forwarded-for` 默认 `${LIANYU_TRUST_FORWARDED_FOR:true}`；`deploy/api-gateway/nginx.conf:52` 用 `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for` 追加而非覆盖，…
- 影响：攻击者对每个请求换一个伪造的 XFF 值即可让 per-IP 30/min 失效，把撞库/密码喷洒速率放大到仅受 per-username 10/min 约束，配合 CaptchaService 的 OCR 可解算式验证码，撞库成本显著降低。
- 修复：在可信代理边界上取 IP：优先用 nginx 覆盖写入的 X-Real-IP（$remote_addr），XFF 只在 trust-forwarded-for=true 且从左往右跳过可信代理后取第一个不可信地址。

**中 · REL-A1E33 · 流式桥不区分「收到 [DONE]」与「上游半途断开」，截断回复按成功落库并触发通知与记忆摘要**
backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentStreamBridge.java:99
- 证据：accept() 里 `case DONE, IGNORED -> { }`（AgentStreamBridge.java:93-95）不记录任何「已收到终止帧」状态；finish() 只在内容为空时报错，否则一律 `finishSseSuccess`（:99-113）。上游连接被关掉时 WebClient 走 onComplete → relay::finish，同样按成功处理。abort() 更直接：`callback.onComplete(partial, null)`（:126-139），把截断内容以 null 异常上抛。…
- 影响：backend-agent 重启、OOM、网关中途断开导致没有 [DONE] 时，半截回复被当作完整消息落库、写入关系与记忆摘要并推送通知；用户看到一句话没说完的回答，既没有错误提示也没有重试入口，事后从日志也看不出异常（只有一条正常的 saved 日志）。客户端断连时同理。
- 修复：引入 sawDone 状态，把「正常终止」与「上游断开」区分开：断开时以错误收尾并显式标记，是否落库作为产品决策显式写出。

**中 · REL-8CDE1 · 取消与「无 [DONE] 截断」两条语义零回归测试，正好覆盖本轮两个高危缺陷**
backend/lianyu-web/src/test/java/com/lianyu/web/agent/AgentStreamBridgeTest.java:84
- 证据：AgentStreamBridgeTest 的 8 个用例（relaysContentIncrementallyThenFinishesWithDone、replaceOverrides、errorFrameFinishesWithErrorAndPartialContent、emptyContent、ignoresUnknownFrames、clientDisconnectStopsRelaying、failReportsErrorMessage、finishIsIdempotent）输入全部带 `data: [DONE]`，没有任何「流结束但没有 DONE」的断言；…
- 影响：F1（取消被计为上游失败）与 F3（截断当成功落库）都没有护栏：任何人重构 gateway/stream bridge 都可能在无人察觉的情况下恢复旧行为，而这两条恰好影响全体用户的可用性与数据正确性。
- 修复：为取消与截断各补最小用例，并纳入 CI。

**中 · MNT-F08A4 · lianyu-web agent 层：8 处凭据解析 + 5 份逐字复制的健康缓存，参数还各不相同**
backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentStreamAdapter.java:52
- 证据：「取 vault → 决定用解密 key 还是 apiKey → 空则回落 modelDefault → 判 baseUrl/apiKey 为空」这段在至少 8 处重复：backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentStreamAdapter.java:52、AgentTextStreamAdapter.java:49、ChatTurnAdapter.java:135、AgentBlockingAdapter.java:51、AgentImageAdapter.java:160、AgentMemoryAdapter.java:47、AgentSessionSum…
- 影响：「同一个 agent 在不同链路上用不同 temperature/maxTokens」不是设计，是复制后各改各的产物：同一句话在流式、阻塞、会话摘要三条链路上行为不同，排查时看不出原因。凭据解析重复 8 份意味着密钥轮换或 vault 字段变更要改 8 处，漏一处就是静默 fallback 到 Java。健康缓存的副本连注释都在解释「我抄的」，是可读性债务的典型信号。
- 修复：抽出 AgentCredentials（resolve + 判空 + 错误码）与 AgentHealthProbe（3 秒缓存）两个协作对象，注入到各 Adapter；把采样参数收敛到一处默认值表。

**低 · REL-17E6F · AGENT 链路健康探测失败只写 debug 日志，整链路静默回退 Java 旧链路**
backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentRuntimeClient.java:414
- 证据：`log.debug("Agent health probe failed: {}", e.toString());` 后 return false；ChatTurnAdapter.stream 与 reachable() 拿到 false 后返回 null，上层静默回退 Java 链路。生产默认日志级别是 INFO，这条记录不会出现。
- 影响：backend-agent 整体不可用、证书/地址配错时，全部流量悄悄回退到旧链路，行为差异（无工具调用、记忆抽取方式不同）对用户和运维都不可见；既没有 WARN 也没有指标，排障只能靠猜「为什么没有工具回复」。
- 修复：把探测失败提升为带节流的 warn 并暴露降级计数。

### u09 frontend/electron（保留 1 条：严重 0 / 高 0 / 中 1 / 低 0）
**中 · MNT-BAE80 · electron/main.js 3077 行承载 71 个 IPC 通道，主进程已无清晰分区**
frontend/electron/main.js:1
- 证据：frontend/electron/main.js 共 3077 行；`grep -c "ipcMain.handle(" frontend/electron/main.js` = 63，`grep -c "ipcMain.on("` = 8，合计 71 个通道；文件顶部 import 约 20 个本地模块（logger、exportTextFile、desktopSettings、authSessionStore、customTts、appearanceStore、desktopObserver、greetingAudio、lastChatModelStore、apiProxy、qqBridge、qqBridgeCoordinat…
- 影响：新增一个 IPC 就得在 3000 行里找位置，通道的权限边界（preload 白名单 ↔ main 处理器）无法在一个视图内核对；该类文件也是合并冲突与「改 A 弄坏 B」的高发区。AGENTS.md 把 IPC 白名单视为安全边界，边界散落在大文件里会降低审查质量。
- 修复：按域把 ipcMain 注册拆到 electron/ipc/*.js（窗口与托盘、聊天与导出、TTS/音频、QQ 桥、更新器），main.js 只保留装配顺序。

### u10 其他模块（(root)、.github、admin 等）（保留 6 条：严重 0 / 高 2 / 中 2 / 低 2）
**高 · SEC-1E385 · Jasypt 主密钥缺失时静默回退明文，API Key 与 TOTP 密钥以明文落库**
backend/lianyu-security/src/main/java/com/lianyu/security/util/JasyptUtil.java:63
- 证据：JasyptUtil.java:20-27 在 `LIANYU_MASTER_KEY` 为空时只打一条 warn 后 return（encryptors 保持空）；encrypt() 在 :63-66 与 :68-70 遇到 encryptors 为空或 current 版本缺失时执行 `log.warn("No Jasypt encryptor available, returning plaintext"); return plaintext;`。…
- 影响：一次部署疏忽（漏配环境变量）就会让用户自带的模型 API Key 与管理员 2FA 种子以明文形态长期留在数据库与备份里，且没有任何报错；之后即便补上密钥，已写入的明文行也不会被识别（decrypt 会尝试解密失败后返回原值）。属于加密能力的静默降级（fail-open）。
- 修复：把加密从 fail-open 改为 fail-closed：没有可用主密钥时拒绝写入并要求显式开启降级开关，同时给已存明文做一次迁移。

**高 · REL-CFE67 · CI 不运行 backend-agent 的 pytest 与 admin 测试，前端还违反仓库要求的串行执行**
.github/workflows/ci.yml:22
- 证据：ci.yml 只有三个 job：backend（`mvn -B -ntp test`，:22）、frontend（`npx vitest run`，:39）、deps-audit（OSV，continue-on-error）。没有 pytest 步骤、没有 admin 的 npm test。AGENTS.md §4 明确要求前端用 `npx vitest run --no-file-parallelism`（updater 并发用例在满载并行下已知 flake），CI 里是并行跑。
- 影响：backend-agent 全部用例（含本轮 F1/F4/F5/F7 涉及的 gateway/resilience/tool_loop/memory）在合并前不执行，Python 侧错误处理改动零门禁；admin 的 1 个测试也从不运行；前端并行执行会把已知 updater flake 带进 CI，红灯被当噪音忽略，真正失败的用例因此更容易漏掉。
- 修复：补齐两条 CI job，并把前端测试改成与文档一致的串行调用。

**中 · REL-C3C45 · `vitest --passWithNoTests` 让「一个测试都没跑」也返回成功**
frontend/package.json:18
- 证据：frontend/package.json:18 `"test": "vitest --passWithNoTests"`，admin/package.json:9 相同。frontend/vitest.config.js 的 include 是三条 glob（src/shared/electron 下的 __tests__），目录调整或配置漂移导致 0 命中时，命令仍然 exit 0。
- 影响：测试文件被移动、include 写错、依赖缺失导致收集为空时，门禁依旧是绿的，覆盖率静默归零而无人发现；与 AGENTS.md「测试要能非空转验证」的纪律直接冲突。admin 全模块本只有 1 个测试文件，风险更高。
- 修复：去掉 --passWithNoTests，让「零用例」成为失败；需要空跑的场景用显式环境变量。

**中 · MNT-3F647 · EncryptedField / EncryptedStringTypeHandler 从未接线，注解存在但字段并未加密**
backend/lianyu-security/src/main/java/com/lianyu/security/handler/EncryptedStringTypeHandler.java:12
- 证据：CONFIRMED_DEAD。backend/lianyu-security/src/main/java/com/lianyu/security/handler/EncryptedStringTypeHandler.java:12 `public class EncryptedStringTypeHandler extends BaseTypeHandler<String> {`（40 行）+ backend/lianyu-security/src/main/java/com/lianyu/security/annotation/EncryptedField.java:1（9 行 `public @interface Encrypt…
- 影响：这是一条「看起来已经做了字段加密」的假线索：安全评审看到注解会假定敏感字段（vault 密钥、个人资料）已落库加密，而实际是明文。留着比删掉更危险，因为它把「未做」伪装成「已做」。
- 修复：要么接线（明确列出应加密的列并补迁移/回填方案），要么删除这两个类型，避免伪造安全语义。

**低 · SEC-78045 · 渲染端 CSP 允许 unsafe-eval 与任意 https 连接，未设置导航守卫**
frontend/index.html:7
- 证据：frontend/index.html:7 的 meta CSP 为 `script-src 'self' 'unsafe-eval'; connect-src 'self' https: wss:; img-src 'self' data: blob: https:`，`'unsafe-eval'` 使字符串到代码的执行路径保持开启，`https:` 允许把任意域当外联出口（数据外带不受限）。frontend/electron/main.js 只在 setWindowOpenHandler（:667-673）里用 isAllowedExternalUrl 拦新窗口，全文没有 `will-navigate` 守卫，…
- 影响：一旦出现未被 DOMPurify 覆盖的 DOM 注入点（当前 v-html 仅 1 处且已白名单），CSP 基本不提供纵深防御：eval 可用、外联到任意 https 域不受限，能把本地数据送出；若渲染进程被诱导导航，Electron 侧没有第二道拦截。
- 修复：收敛 CSP 到具体域名并移除 unsafe-eval，同时给 BrowserWindow 加 will-navigate 白名单。

**低 · MNT-6B905 · admin 有 4 个声明依赖零 import，页面还用硬编码占位数字冒充真实状态**
admin/package.json:10
- 证据：admin/package.json:10 起 dependencies 为 @element-plus/icons-vue ^2.3.1、@tanstack/vue-table ^8.21.3、axios ^1.18.0、echarts ^6.1.0、element-plus ^2.14.5、pinia ^2.2.6、vue ^3.5.13、vue-router ^4.4.5。…
- 影响：四个未用依赖持续放大安装体积与供应链面（后台是内网工具，实际成本主要是每次装包/审计时的噪音）。App.vue 的写死版本号与待处理数会随时间失真，让人误判发布状态；这类「假数据占位」比空状态更难被发现。
- 修复：移除四个未引用依赖；把 App.vue 的版本号/待处理数改成真实数据源，取不到就显式显示占位符。

### u11 backend（小模块合集 59 文件）（保留 1 条：严重 0 / 高 1 / 中 0 / 低 0）
**高 · SEC-E682A · 管理端 2FA 可被绕过：/totp/setup 无 step-up 可静默重置，OTP 失败不计数可爆破**
backend/lianyu-admin/src/main/java/com/lianyu/admin/identity/AdminTotpController.java:19
- 证据：AdminTotpController.java:19-26 的 `@PostMapping("/setup")` 只用 `authorization.currentAdminId()`，不校验密码或当前 OTP，直接 `UPDATE admin_user SET totp_secret 改写为新密钥, totp_enabled=FALSE WHERE id=?`（jasypt.encrypt(secret)）；而同文件 :37-44 的 `/disable` 却要求 password 或当前 OTP，形成明显反差。…
- 影响：拿到一个有效 admin 会话（令牌泄露、共享电脑未退出）即可静默把 2FA 换成自己的验证器并保持 totp_enabled=false，实现持久账号接管，同时把合法管理员锁在 2FA 外；已知口令后还能以无限次尝试爆破 6 位 TOTP。管理端拥有发布桌面安装包的权限（ReleaseService/release.manage），因此该缺口可传导为供应链投毒。
- 修复：为 /totp/setup 增加与 /disable 同级的 step-up（当前口令或当前 OTP），并把 OTP 失败纳入锁定计数、拒绝重放。

### u12 其他模块（backend/lianyu-app、backend/lianyu-common、backend 等）（保留 1 条：严重 0 / 高 0 / 中 1 / 低 0）
**中 · COR-5F138 · 输入策略正则误杀正当内容：正常聊天里出现 sudo、role: system、「执行以下命令」会被直接拒绝发送**
backend/lianyu-common/src/main/java/com/lianyu/common/util/UserInputSanitizer.java:60
- 证据：FORCED_COMMAND_PATTERNS（:59 起）含 `\bsudo\s+`（:60）、`(?i)rm\s+-rf`、`执行(以下|如下)?命令|运行(以下|如下)?命令`；INJECTION_PATTERNS 含 `(?i)role\s*:\s*system`、`act\s+as\s+(a\s+)?(system|admin|root)`。assertPolicyAllowed(:105) 命中即抛 BusinessException(CONTENT_POLICY_VIOLATION)，没有任何豁免或分级。…
- 影响：用户分享技术经历（装了软件、跑了命令）、玩角色扮演（「你现在是系统管理员」）、或作为开发者提问时，会被判成提示词注入而发不出去，且提示语是「包含疑似强制系统/终端命令内容」，用户无法理解也无法绕过；这类误杀在陪伴场景里会直接打断对话。
- 修复：把硬拒绝收敛到真正危险的少数模式，其余降级为「包裹后照常发送」，并区分聊天文本与生成描述两类入口的策略。


### 跨单元文件（不属于单一单元的文件清单）

**中 · SEC-96CF9 · 自动更新清单验签已在代码里实现但未启用（公钥留空即跳过），依赖审计也不阻塞合并**
frontend/.env.production.cloud:12
- 证据：frontend/electron/updater/updater.js:81-107 的 verifyManifestOrThrow 在 `resolveManifestPublicKey()` 为空时仅 `logger.warn('latest.yml signature check disabled')` 然后 return（fail-open）；公钥来自 runtimeSecrets.updateManifestPublicKey，打包时取 `VITE_LIANYU_UPDATE_PUBKEY`，…
- 影响：更新渠道一旦被写入（MinIO 凭据泄露、nginx/存储被改、发版脚本被投毒），客户端会静默安装攻击者的安装包 —— 桌面端等于远程代码执行；而依赖漏洞扫描失败不会阻断合并，已知漏洞可能长期留在发布物里。
- 修复：给更新链补上强制验签（发布带 latest.yml.sig + 客户端内置公钥），并把依赖审计改为阻塞项、action 固定到 SHA。

## 四、专家报告索引

| 专家 | 报告 | 状态 | 保留发现 |
|---|---|---|---|
| 正确性 | handoff/correctness/correctness.md | ok · 12/12 单元 | 9 条（原始 10 条） |
| 安全 | handoff/security/security.md | ok · 12/12 单元 | 11 条（原始 11 条） |
| 健壮性 | handoff/reliability/reliability.md | ok · 12/12 单元 | 11 条（原始 11 条） |
| 可维护性 | handoff/maintainability/maintainability.md | ok · 12/12 单元 | 11 条（原始 12 条） |

- handoff/correctness/correctness.md —— 断流重试重复落库（COR-D1203）是唯一 high，其余集中在关系分数覆盖写、seq 分配、评论锁与 i18n 四语漂移；结论是「主链路逻辑自洽，边界条件成片失效」。
- handoff/security/security.md —— 一条严重加五条 high，全部围绕「信任边界画在注释里」：agent 端点无鉴权、记忆写入无归属校验、出站无 SSRF 校验、口令入库、加密 fail-open；结论是「内网假设不成立时，最外层几乎无防护」。
- handoff/reliability/reliability.md —— 三条 high 都是「失败被当成成功」：取消被计为上游失败、后台任务按文案判重试反而漏掉最该重试的场景、CI 不跑 Python 与 admin 测试；结论是「故障可见性比故障本身更缺」。
- handoff/maintainability/maintainability.md —— 十二条全为中/低，没有一条需要立即修；核心是 317 行零引用前端模块、49 个一次性脚本、8+5 份复制粘贴的 agent 适配代码；结论是「删除与收敛的收益已经大于新增抽象」。

## 五、跨单元系统性问题

1. **fail-open 家族（五个单元、五处独立实现）**：断流按成功收尾（REL-A1E33）、缺主密钥回明文（SEC-1E385）、缺公钥跳过验签（SEC-96CF9）、健康探测失败只写 debug（REL-17E6F）、记忆批量写事务与注释不符（REL-4B9D6）。共同点是失败路径不发声，正好违反 AGENTS.md §9 第 3 条「失败路径会说话」。
2. **边界校验的跨语言不对称**：Java 侧有 OutboundUrlValidator + SsrfPinningClientFactory，Python 侧出站裸奔（SEC-3BB6D）；nginx 注释声称「外部请求必须带 X-User-Id 且 app 侧二次校验」，实际既无 auth_request 也无头注入（SEC-31F68）。信任边界写在注释和文档里，没有写进代码。
3. **重试语义三处各自为政且都缺幂等键**：前端整请求重发（COR-D1203）、Java 按异常文案判 transient（REL-205D3）、Python 记忆 outbox 无自愈（REL-7EE2B）。三处都没有 Idempotency-Key 或等价去重。
4. **同一事实的多个来源**：msg_seq 前缀在四个服务各写一遍（COR-3F587）、agent 层 8 处凭据解析与 5 份健康缓存且采样参数已漂移（MNT-F08A4）、角色广场把 4 语人设与整段 prompt 写进 Java 2602 行（MNT-D8078，直撞「prompt 归 Python」红线）、i18n 声称四语实际三语（COR-78D9B）。
5. **门禁与实际覆盖脱节**：CI 无 pytest、无 admin 测试、前端未串行、vitest 带 --passWithNoTests（REL-CFE67 / REL-C3C45），于是取消与截断这两条最危险的语义零回归测试（REL-8CDE1）。这解释了为什么上述缺陷能长期存在。

## 六、修复顺序

原则：严重/高优先；同一文件合并成一个提交；先修根因再修补丁；可维护性单独批次，不与功能混提。

- **第 0 批（当天，止血）**：SEC-31F68 —— 先在 nginx 下线 /api/agent/ 的公网暴露或加 auth_request，再给 Python 加统一鉴权中间件并取消「缺头即放行」；SEC-86656 —— 轮换该生产账号口令、改读环境变量、清理 git 历史；SEC-1E385 —— 加密改 fail-closed，并给已存的明文行做一次迁移。
- **第 1 批（本周，数据正确性）**：COR-D1203 + REL-A1E33 + REL-6FD30 一起修（同一条聊天链路：幂等键、sawDone 判定、空闲超时）；REL-D63D0（取消不计入熔断）；REL-205D3（错误码替代文案匹配，并给 DLQ 加消费者或告警）。
- **第 2 批（授权与出站）**：SEC-E49FD（记忆写入由服务端推导 userId 并校验 character 归属）、SEC-3BB6D（Python 出站加与 Java 等价的校验）、SEC-E682A（/totp/setup 加 step-up、OTP 失败计数）、SEC-96CF9（强制验签 + 依赖审计阻塞）。
- **第 3 批（护栏先行）**：REL-CFE67 + REL-8CDE1 + REL-C3C45。修完没有回归测试等于没修，这一批应当与第 1 批同一个迭代内落地。
- **第 4 批（数据一致性）**：COR-493CC、COR-B2E9F 与 COR-3F587 合并（同一处 seq/关系分数根因）、COR-8FD65、COR-CC8D6、COR-5F138、COR-F368E、COR-78D9B。
- **第 5 批（可维护性，单独批次）**：MNT-07031、MNT-3F647、MNT-235E2、MNT-A038F、MNT-0829F、MNT-F08A4、MNT-D4749、MNT-D8078、MNT-BAE80、MNT-89C04、MNT-6B905。这一批只做删除/收敛/接线，不改行为。

同文件合并提示：frontend/src/pages/ChatPage.vue（COR-D1203 与 REL-6FD30）；backend/lianyu-web/.../AgentStreamBridge.java（REL-A1E33，已并入 COR-3CC0C）；backend-agent/app/memory/writer.py（SEC-E49FD 与 REL-4B9D6）；backend-agent/app/gateway.py（REL-D63D0 与 SEC-3BB6D）；frontend/src/i18n（COR-78D9B，已并入 MNT-307E0）。

## 七、覆盖缺口与未验证项

- **sampled 不是全量已读**：u04 四位专家均只到 sampled；u02/u07/u09/u10/u11/u12 各只有 1–2 位 reviewed。u10 的 electron/installer、admin 前端只做了结构扫描，IPC 参数校验与安装器回滚未逐行核对。
- **测试基线不完整**：admin 因 node_modules 缺失无法执行 npm test（只有 1 个测试文件，静态结论）；backend-agent 未用 .venv，系统 Python 缺 aiomysql 导致 pytest 收集期 26 errors，Python 侧用例**一条都没跑**；security 与 reliability 未执行任何测试；mvn 与 frontend 由 maintainability 实跑通过（71 文件 / 417 用例全绿、mvn 退出码 0）——但这两条是专家自述，不构成本轮门禁。
- **未在生产/真机复现**：nginx 前置是否另有 WAF 或安全组、云上更新通道实际配置、Electron 导航守卫的真实行为，均只读代码推断。
- **并发时序未压测**：COR-493CC（关系分数覆盖写）、COR-8FD65（评论锁抢占）来自代码与时序推理，未构造并发用例复现。
- **已澄清不计问题**：V57 迁移只改 trust_score 不重算 phase，按 RelationshipPhaseResolver 对 trust=50 的推导仍是 FAMILIAR，未构成缺陷；Python 侧不产出 pieces 帧与 Java 侧不解析 pieces 一致，不算契约漂移。
- **性能维度未运行**：本轮没有 perf 专家，健康探测 3 秒缓存、两个 scheduler 每轮全量扫描、前端 bundle 体积与首屏、记忆向量检索成本等都未评估。需要时执行 /logi-perf。

## 八、跨专家 deferred

以下 18 条不构成本轮发现，但需要在对应方向继续看。格式为「来源 → 建议承接者（文件）：内容」。

- correctness → correctness：V57__relationship_trust_anomaly_repair.sql 只改 trust_score 不重算 phase：按 RelationshipPhaseResolver 对 trust=50、intimacy<=40 的推导结果仍是 FAMILIAR，与旧值一致，未构成缺陷，…
- correctness → correctness：Python 侧从不发送 pieces 帧（stream_chat.py 注释明确本层不产出 pieces），Java AgentSseParser 不解析 pieces 与现状一致，不算契约漂移。
- correctness → maintainability：updater.js 用 x.y.z 纯数字比较版本，预发布后缀（-beta）会被解析成 NaN 而不提示更新；属边缘命名约定问题，未达上报门槛，转交 maintainability。
- correctness → security：TotpUtil.verify 用 String.equals 比较验证码（非恒定时间）、AdminAuthorizationService.require 每次请求两次 currentAdminId 查库：前者属安全面，后者是次要性能问题，均转交对应专家。
- correctness → maintainability：frontend/electron/main.js（3078 行）与 installer 的 C# 工程只做结构扫描，IPC 参数校验、安装器回滚等未逐行核对。
- reliability → correctness：backend/lianyu-dao（111 文件，V7–V57 迁移，模块内零测试类）：SQL 与迁移正确性无自动化回归，建议由 correctness 专家评估，本轮未展开。
- reliability → reliability：backend/lianyu-app application.yml:71-80 声明 spring.rabbitmq.listener.simple.retry.enabled=true，…
- reliability → reliability：frontend/src 的 30 处 `.catch(() => {})`（qqBridge 轮询、notifications 刷新等）判定为刻意静默，未逐处验证其对用户可见性的真实影响。
- reliability → reliability：SseEmitter 的 SSE 超时（ChatTurnAdapter 构造参数 SSE_TIMEOUT_MS）与前端等待态之间的交互未实测，仅从代码推断。
- reliability → reliability：4 条测试命令本轮未实际执行（只读审查，避免产生 target/、.pytest_cache 等构建产物）；F9/F10 的结论来自脚本与 CI 配置本身。
- maintainability → maintainability：frontend/src/pages/LoginPage.vue（205 行）与 frontend/src/pages/RegisterPage.vue（258 行）行级相似度 0.74（difflib，去注释与空行）：表单校验与提交态可抽 composable，但两侧文案与 OTP 分支不同，…
- maintainability → maintainability：backend/lianyu-admin/src/main/java/com/lianyu/admin/identity/AdminPermissionCatalog.java 9 行零引用（`grep -rn AdminPermissionCatalog --include=*.java back…
- maintainability → maintainability：backend/scripts 下 8 个一次性迁移/修复脚本（dedupe-java-imports.ps1、fix-service-cross-imports.ps1、restore-service-imports.ps1、repackage-service.ps1、strip-utf8-bom…
- maintainability → perf：性能类可疑点（如 Agent 健康探测 3 秒缓存、定时任务每轮全量扫描）不在本专家范围，转交 perf。
- maintainability → correctness：AsrController 的 /api/asr/transcribe 端点在前端已无调用方，是否下线涉及对外契约，转交 correctness 判定。
- security → security（secrets/platform-keys.txt）：本地工作区明文密钥文件（未跟踪）：secrets/platform-keys.txt、config.json、certs/server.key 含疑似真实平台密钥，但均被 .gitignore 忽略、未提交，不属于仓库内容问题；建议单独安排一次本地密钥轮换与磁盘清理，不列入本次发现。
- security → correctness（backend/lianyu-service/src/main/java/com/lianyu/service/file/FileStorageService.java）：聊天图片上传未重编码（与头像/背景路径不一致）：uploadChatImage 只校验客户端声明的 Content-Type 与 5MB，不重编码；因响应固定 nosniff 且类型限 image/*，当前不构成存储型 XSS，属健壮性/correctness 范畴，…
- security → security（backend/lianyu-service/src/main/java/com/lianyu/service/auth/CaptchaService.java）：图形验证码为可 OCR 的算式图片：CaptchaService 生成 a±b/a×b/a÷b 算式图，answer 存 Redis 2 分钟；属弱机器人门槛而非漏洞，需产品/风控判断是否升级为行为验证或第三方验证码。
