# 正确性审查报告

- 项目：予念 YuNian（PC 端）— Electron + Spring Boot 多模块 + Python Agent
- 运行：`.logistics/runs/20261104-194640-2a6a2d`（HEAD `c652e14`，分支 `main`，工作区干净）
- 范围：**整个项目**（非 diff、非单次提交），1100 个送审文件 / 12 个审查单元 / 135,978 行
- 结论：**status = ok**，共 **5 条发现**（high 1 / medium 2 / low 2），无 skipped 单元，无 critical

---

## 一、范围与覆盖

逐单元执行方式：先按单元文件清单做跨文件模式扫描（`grep`/`ops_grep`，本机 bash 无 `rg`），再对热点文件全文精读。`filesRead` 为实际逐行读过的文件数（不含仅被 grep 命中的文件）。

| 单元 | 内容 | 状态 | filesRead | 理由 |
|---|---|---|---|---|
| u01 | backend/lianyu-service (1/3)：conversation / relationship / ai / character | reviewed | 9 | 读了 `ConversationService`、`SessionSummaryService`、`RelationshipStateService`、`RelationshipPhaseResolver`、`RelationshipHeuristics`、`AssistantReplySplitter`、`AiChatQuotaService`、`ProactiveChatScheduler`、`CharacterStateQueryService` |
| u02 | backend/lianyu-service (2/3)：moments / notification / square / voice | sampled | 4 | 按 moments/square/voice 目录 grep 后，精读 `MomentsCommentOrchestrator`（891 行）关键段 |
| u03 | frontend/src (1/2)：api 层 | sampled | 3 | 精读 `api/httpCore.js` + `api/__tests__/sessionRefresh.test.js`，其余按 401/重试/错误码路径 grep |
| u04 | scripts (1/2) | sampled | 3 | 精读 `release-bump-version.mjs`（正则对真实文件实跑）、`check_release_asset_contract.py`、`sign_update_manifest.py`；一次性排查脚本抽样 |
| u05 | backend/lianyu-dao：entity / mapper / V1–V57 迁移 | reviewed | 4 | V1 建表、V56、V57 逐行读，并按唯一键/索引/回填语句 grep 交叉核对 |
| u06 | backend-agent：app/* 与 tests/* | reviewed | 3 | 精读 `stream_chat.py`、`tool_loop.py`；`tests` 因缺 `respx` 无法执行 |
| u07 | frontend/src (2/2)：pages / stores / utils / components | sampled | 7 | 精读 `utils/innerThoughtFilter.js`（node 实跑复现）、`assistantReplySplit.js`、`relationship.js`、`AssistantMessageContent.vue`，并在 4 个调用点 grep |
| u08 | backend/lianyu-web：agent 适配层 / controller / filter | reviewed | 4 | `AgentSseParser`、`AgentStreamBridge` 全文；controller/filter 按鉴权与转发路径 grep |
| u09 | frontend/electron | sampled | 5 | `updater.js`（版本比较 / manifest 校验）、`manifestSignature.js`、`qqBridge.js`、`messageExtractor.js`、`shared/sseParser.js` |
| u10 | (root) / .github / admin / deploy / docker-compose | sampled | 2 | `nginx.conf`、`docker-compose.yml` 按 location 与 redis/agent 配置 grep；admin 模块靠 `npm test` 整体验证 |
| u11 | backend 小模块合集（lianyu-admin + service 测试） | sampled | 3 | 依赖 `mvn test` 整体门禁（exit 0）与测试目录 grep，未逐文件精读 |
| u12 | lianyu-app / lianyu-common / installer / Pets / shared | sampled | 4 | `lianyu-common` 的 `TextUtils`、`JacksonListTypeHandler`、`ImageUploadValidator` 精读；`installer/*.cs`、`Pets/` 未逐行读 |

### 测试门禁（本机实测）

| 门禁 | 结果 |
|---|---|
| `npm --prefix frontend test` | **全绿**：71 files / 417 tests（7.27s） |
| `npm --prefix admin test` | **退出码 0** |
| `mvn -q -f backend/pom.xml test` | **退出码 0**（日志仅业务 WARN/INFO） |
| `pytest -q backend-agent` | **采集阶段失败**：48 errors，根因 `ModuleNotFoundError: No module named 'respx'`（`tests/test_tools.py:8`）。已单独验证 `python -c "import app"` 正常，属本机缺 dev 依赖，**不计为代码缺陷** |

> 直接后果：Python 侧结论只有代码级证据，没有测试佐证；Java/前端结论有门禁背书。

---

## 二、发现

### A. 前端 / 内心独白（u07）— 1 条 high

#### A1 [high] 嵌套括号时内心独白重复渲染，隐藏模式下会把整条回复删空

- 位置：`frontend/src/utils/innerThoughtFilter.js:146`（`parseInnerThoughtSegments` 循环），关联 `findParenthesisRanges:118/125`、`stripInnerThoughts:171-175`
- 触发路径：模型输出含嵌套括号 `（心想（笑）才不是呢）好呀` → `findParenthesisRanges` 用栈配对后**直接返回重叠区间** `[{0,12},{3,6}]` → 分段循环只有单指针 `lastIndex`，外层处理完把 `lastIndex` 置 13，内层 `start=3` 仍被 push，且第 154 行把 `lastIndex` **回退**成 5 → 循环末尾 `text.slice(5)` 再 push 一段「台词」。
- 实跑证据（node 动态 import 该模块）：

  ```
  parseInnerThoughtSegments('（心想（笑）才不是呢）好呀')
  => [ {inner:'（心想（笑）才不是呢）'}, {inner:'（笑）'}, {speech:'才不是呢）好呀'} ]
  stripInnerThoughts('（心想（笑）才不是呢）好呀', false) => ''
  stripInnerThoughts('嗯（我（偷偷）看了你一眼）', false) => '嗯'
  ```

- 影响：开着「内心独白」时同一段独白在气泡里出现两次，并把括号内的动作文字泄漏成台词；关掉独白后整条回复变空白（或用例 2 那样丢掉真实台词）。渲染入口 `frontend/src/components/AssistantMessageContent.vue:43-51`；导出与历史记录侧 `frontend/src/utils/exportChatTranscript.js:44`、`frontend/src/pages/ChatPage.vue:641`、`frontend/src/pages/GroupChatPage.vue:412`、`frontend/src/pages/QuickChatPage.vue:174` 同样受损。
- 为什么现有用例没拦住：`frontend/src/utils/__tests__/innerThoughtFilter.test.js` 只覆盖单层、混排宽度、跨行与未闭合括号，**没有嵌套用例**，所以 417 条前端用例全绿。
- 修复摘要：在 `findParenthesisRanges` 返回前按 start 升序做区间归一化（外层吞并内层），分段与删除都基于互不重叠的区间；`stripInnerThoughts` 改为从左到右一次性重建（`cursor = end + 1`）而不是按索引累积 slice；补嵌套用例（含隐藏模式期望）。

### B. service 并发正确性（u01 / u02）— 2 条 medium + 1 条 low

#### B1 [medium] 朋友圈评论锁 `unlock` 直接 DEL，TTL 过期后会删掉新持有者的锁

- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/moments/MomentsCommentOrchestrator.java:822`（`tryLock:816-820`）
- 证据：

  ```java
  private boolean tryLock(Long postId) {
      Boolean ok = redisTemplate.opsForValue()
          .setIfAbsent(LOCK_PREFIX + postId, "1", Duration.ofSeconds(90));
      return Boolean.TRUE.equals(ok);
  }
  private void unlock(Long postId) { redisTemplate.delete(LOCK_PREFIX + postId); }
  ```

  锁值恒为 `"1"`，没有 owner token；锁内是模型调用（`processAuthorReplyJob:271-296`、`executePeerComment:326+`），单次耗时与 90s TTL 同量级。
- 影响：一次持锁超过 90s 后锁自动过期，第二个 worker 拿到锁开始处理同一 post；第一个 worker 的 `finally unlock` 随后删掉**新持有者**的锁，第三个 worker 也能进入 → 互斥失效，同一帖子重复生成/重复写入角色评论（幂等键 `"peer:"+postId+":"+peerCharacterId` 只覆盖 peer 维度、不覆盖轮次），并重复消耗模型配额。
- 修复摘要：锁值改为随机 token，解锁用 Lua 比对后删除；TTL 提到大于模型超时上限（或长耗时前续期）；`finally` 全部改为带 token 的释放。

#### B2 [medium] 会话摘要 Redis 状态是「读 → 调模型 → 写」，并发 merge 会让 `lastSummarizedSeq` 回退

- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/conversation/SessionSummaryService.java:83`
- 证据：`@Async maybeMergeAsync:49-52` → `maybeMerge:54-98` = `loadState`（`:73`，读 `session:summary:<convId>`）→ `merger.merge(...)`（`:83`，秒级模型调用）→ `saveState`（`:95`）写回本批 pending 的 max seq（`:88-94`）。**无锁、无 CAS、无版本号**；`state==null` 分支（`:74-76`）是死代码，`loadState` 从不返回 null。
- 影响：同一会话连续两条消息各触发一次异步摘要，后启动者可能先写入较大 `lastSummarizedSeq`，先启动者再用较小值覆盖 → 下一轮把已摘要消息再摘要一次（重复摘要 + 重复模型调用）；反向交错则会跳过一段未摘要消息，摘要文本与游标不一致。
- 修复摘要：写入改为「仅当新值大于当前值」的条件写（Lua 或 WATCH/MULTI + 重试），同一会话 merge 加带 token 的短锁，删除死分支。

#### B3 [low] 关系状态整行覆盖写且无事务/版本号，回合更新与定时衰减互相覆盖

- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/relationship/RelationshipStateService.java:132`（`:165`、`:185` 同类）
- 证据：`recordUserTurn:107-134` / `recordAssistantTurn:136-166` / `decayAll:172-190` 都是 `loadOrCreateState` → 改内存 → `relationshipStateMapper.updateById(state)` **整行覆盖**；类上无 `@Transactional`，实体无 `version` 字段。`applyEvents:256-257` 还会用 `RelationshipPhaseResolver.derive(...)` 重算并写 `phase`。
- 影响：用户消息线程的增量写与 `@Scheduled` 衰减写并发时，后提交者用自己读到的旧快照覆盖全行：刚加的 intimacy/trust 被回滚，或本次衰减被吞，`streak`/`lastInteractionAt` 也可能回退。单用户串行时不触发，故定 low。
- 修复摘要：加 `version` 乐观锁（新 Flyway 迁移）或改成「只更新变化列 + 条件 WHERE」；一次回合的多次写放进同一事务；影响行数为 0 时重读重算一次。

### C. 数据完整性（u01 / u05）— 1 条 low

#### C1 [low] 消息 seq 只靠 Redis INCR，计数器丢失后从 1 重发会撞 `uk_conv_seq`

- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java:483`
- 证据：

  ```java
  private long reserveSeqBlock(Long conversationId, int count) {
      int safeCount = Math.max(1, count);
      Long lastSeq = redisTemplate.opsForValue().increment(SEQ_KEY_PREFIX + conversationId, safeCount);
      return lastSeq != null ? lastSeq : safeCount;
  }
  ```

  key 为 `msg_seq:<conversationId>`（`:72`），**无 TTL、无 `MAX(seq)` 回种**；`backend/lianyu-dao/src/main/resources/db/migration/V1__init_schema.sql:45-54` 的 `message` 表有 `UNIQUE KEY uk_conv_seq (conversation_id, seq)`。`MessageMapper` 里已有基于 `MAX(seq)` 的 `@Select`（`selectLatestByConversationIds` 等），但从未用于回种；`msg_seq:` 前缀还在 `GroupChatService.java:104`、`VoiceCallService.java:62`、`CharacterService.java:60` 重复定义。
- 影响：Redis 数据丢失（`FLUSHALL`、卷重建、AOF 未落盘即崩溃、迁移新实例）后计数器从 1 递增，与该会话既有 seq 冲突，之后每次发消息落库都抛 `DuplicateKeyException`，该会话持续 500 且**无自愈路径**。生产 `docker-compose.yml:275` 用 `--appendonly yes` + 命名卷，正常运维不易触发，故定 low。
- 修复摘要：INCR 后与库内 `max(seq)` 比较（Lua 原子补种），落到唯一键冲突时补种重试一次；把前缀收敛到单一常量。

---

## 三、已查未发现问题（明确记 0）

- **SSE 双端契约**：`backend/lianyu-web/.../agent/AgentSseParser.java`（带/不带 `data:` 前缀、心跳、手写转义）、`AgentStreamBridge.java`（CONTENT/REPLACE/ERROR/DONE 分流、`finish()` 取 `lastReplacement` 优先、空白→错误帧、`abort()` 断连仍持久化）、`frontend/shared/sseParser.js`（唯一解析实现，content/replace/pieces/error 全字段）三者口径一致。
- **关系展示契约**：`frontend/src/utils/relationship.js` 读取的字段与 `CharacterStateQueryService.java:177-208` 输出（含 `relationshipMilestones[{key,happenedAt}]`）逐项对齐，无漂移。
- **分段规则双端一致**：`AssistantReplySplitter.java` 与 `frontend/src/utils/assistantReplySplit.js` 在阈值 40、CJK/EN 边界、rebalance→group→cap 顺序、尾部 `join(' ')`、`limit=max(limit,2)` 上完全一致。
- **更新器**：`updater.js:408-418 compareVersions` 语义正确（1=有更新、缺段补 0）；`verifyManifestOrThrow:81-107` 无公钥 warn 放行、有公钥失败即抛（fail-closed）；`manifestSignature.js:31-48` 与 `scripts/sign_update_manifest.py:90-93` 的「签原始字节 + base64+换行 + 校验端 trim」一致。
- **发布脚本**：`scripts/release-bump-version.mjs` 三条正则对真实文件实跑均命中（`frontend/package.json` 顶层 1 次；`package-lock.json` 的 2 空格与 6 空格 `"version"` 各命中；三处当前同为 `0.3.15`）；`check_release_asset_contract.py` 的 json/nginx/client 三段检查逻辑成立。
- **导出与工具**：`frontend/src/utils/exportChatTranscript.js`、`lianyu-common` 的 `TextUtils`、`JacksonListTypeHandler`、`ImageUploadValidator`（8MB/8192 边界、magic bytes、PNG 重编码去嵌入内容）未见缺陷。
- **qqBridge**：`messageExtractor.js:8-36` 正确剥离 `@机器人`/引用段并回退 `raw_message`；`qqBridge.js:100-215` 每轮重读设置、白名单默认拒绝、404 重建会话重试一次、`pieces` 优先回退 `replace`。
- **迁移**：V56 回填 `last_interaction_at = updated_at`、V57 一次性修正被刷高的 trust，均与 `RelationshipPhaseResolver` 的 phase 推导自洽（`trust=100/intimacy<=40` 修成 `50` 后 phase 仍为 FAMILIAR，不需要同步改写）。
- **并发相关的其他入口**：`AiChatQuotaService`（首次 `increment` 才设 2 天过期，逻辑自洽）、`ProactiveChatScheduler`（cooldown 去重、`sent < quota` 上限、成功后 `sent++`）读后未发现状态错误。
- **关系事件可达性**：`RelationshipStateService.applyEvents` 的 `injuryOpen` 在循环外求值、循环内才 `setLastInjuryAt`，理论上同批「先受伤后道歉」会吞掉 REPAIR；但 `RelationshipHeuristics.fromUserTurn:57-103` 中 DISMISSIVE 要求 `safe.length() <= 2`，而 REPAIR 最短词「对不起」3 字，**同一轮不可同时产出两类事件** → 判为不可达，未作为缺陷上报（已记录在 JSON `notes`）。

---

## 四、未验证 / 抽样说明（不能当作全量已读）

1. **Python 侧无测试证据**：`pytest` 在采集阶段就因缺 `respx` 失败，`backend-agent/app/*` 的结论全部来自代码阅读，未被执行验证。
2. **sampled 单元**：u02、u03、u04、u07、u09、u10、u11、u12 为「模式扫描 + 抽样精读」，`filesRead` 分别为 4/3/3/7/5/2/3/4，**不代表这些单元每行都被审过**。
3. **u10 / u12 有明显空白**：`.github/workflows/ci.yml`、`deploy/asr/server.py`（515 行）、`installer/*.cs`、`Pets/` 未逐行读；u11 的测试代码只做了门禁级验证。
4. **并发结论均为「代码可证 + 未压测」**：B1/B2/B3 的竞态由代码结构（无 owner token / 无 CAS / 无版本号）证明，未做故障注入复现；B3 定为 low 是因为单用户串行路径不触发。
5. **生产环境未验证**：Redis 持久化、多实例部署、模型实际耗时（决定 90s TTL 是否真的会过期）来自配置与代码推断，未在生产复现。

---

## 五、建议修复顺序与跨专家转交

**修复顺序（按用户可感知度 × 成本）**

1. **A1**（high，前端单文件 + 用例）：用户肉眼可见的渲染错误，改动局限在 `innerThoughtFilter.js`，先修。
2. **C1**（low 但故障后不可恢复）：补 `MAX(seq)` 回种与撞键重试，成本低、消除「会话永久 500」的单点。
3. **B1**（medium）：锁改 token + Lua 释放，配合 TTL 调整，改一个类。
4. **B2**（medium）：摘要条件写 + 会话级短锁，涉及异步路径，需要并发用例保障。
5. **B3**（low）：乐观锁需要新迁移 + 实体改动，放最后，可与维护性清理合并。

**跨专家转交**

| 事项 | 归属专家 | 说明 |
|---|---|---|
| `backend-agent` 测试无法采集（缺 `respx`） | reliability / 测试基建 | 需确认 dev 依赖是否在 `requirements-dev` 中声明；当前 CI 与本地口径不一致 |
| `tool_loop.py:153` `nudged` 恒为 False（死标志） | maintainability | 「空口承诺防护」未生效，属意图与实现不符 |
| `ProactiveChatScheduler.tick` 异常仅 `log.debug` 吞掉 | reliability | 失败不可见，无法从日志判断主动消息为何停止 |
| `AiChatQuotaService` 超限请求仍计数 | 产品/设计 | 是否要让被拒请求占用配额，需产品口径确认 |
| `msg_seq:` / `session:summary:` 等 Redis key 散落在多个服务类 | maintainability | 与 C1 一并收敛到常量类 |
| `RelationshipStateService` 无 `@Transactional` 的事务边界 | reliability | 与 B3 的乐观锁方案一起评估回滚语义 |

---

*本报告为只读审查产物：未修改任何源码、未提交、未删除文件；仅写入本次运行目录的 `handoff/`。*
