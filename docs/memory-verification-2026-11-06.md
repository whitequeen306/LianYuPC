# 记忆/上下文/遗忘 大验证报告（2026-11-06）

六组并行验证：上下文装配、写入与去重、召回、遗忘、端到端、对抗降级。环境是
克隆库 `lianyu_rehearsal`（V56–V59 全量迁移）+ 本机 Milvus `memory_vectors_v3`
+ 真 Redis / DashScope embedding。**结论：不是全绿**，抓到 4 个 blocker/major
级问题，以下是逐条现状。

## 一、已修（提交 `da04fcc`）

| 编号 | 问题 | 现场证据 | 修法 |
|---|---|---|---|
| B1 | 场景结算窗口用 UTC 卡片时间比 +08 的 `message.created_at`，生产上恒 0 条 | 同一批 6 条消息：+08 命中 0 条，UTC 命中 6 条；`close_pending_scene` 返回 0 | `scene_close._db_local()` 把卡片时间转成 `Asia/Shanghai` 墙钟后再查 |
| A1 | 先挂场景卡、后截记忆块；超预算时场景卡与会话摘要整段消失 | 8 条长记忆 1277 token → `has_scene=False`、`has_summary=False` | 把 `_cap_memory_context` 提到 `_attach_scene_card` / `_attach_resurface` 之前 |
| D1 | 归档记忆仍进候选；`strength<0.15` 的行也能注入 | `695 archived` 进了 prompt；向量路 `rows=[668]` | 候选与向量 SQL 排除 `fade_level='archived'` |
| D2 | 召回只打点不巩固，复活后下一次结算又被打回归档 | 复活后 `last_decay_at` 仍是 2025-10-03，`0.550 → 0.107` | 注入即 `strength = LEAST(1, strength+0.05)`、`last_decay_at = NOW()`；复活同样重置起点 |
| B3 | episode 向量 id（1e9 偏移）混进去重检索，命中 `memory_meta` 不存在的行 | noop、`ids=[1000000901]`，新事实被丢 | `dedupe.best_similar` 跳过 `id >= EPISODE_ID_OFFSET` |
| C1 | 向量下限 0.55 高于正确改写对（0.50–0.52） | 丢 2 题；噪声峰值只有 0.46 | 下限改 0.50 |
| C2 | RRF 只看名次、原话 tau=30，一年前的强匹配排第 15 | `ep-905` rel 3.26 / cos 0.755 仍排 15 | episode tau 30 → 180 |
| C3 | core 取 12 只渲染 6、且无排序，第 7 条从两个块里同时消失 | 533 昵称不可见 | `_CORE_SQL` 加 `ORDER BY pinned DESC, importance DESC, created_at DESC`，截断时告警 |
| F1 | MySQL 不可用时旧路径裸调，异常冒到回合层，整轮只剩「生成失败」 | 聊天返回 `{"error":"生成失败，请重试"}` | `load_profile_block` 包 try/except，降级为「这轮没有记忆」 |
| 依赖 | MySQL 8 默认 `caching_sha2_password`，venv 缺 `cryptography` 直接连不上 | `RuntimeError: 'cryptography' package is required...` | `pyproject.toml` 加 `cryptography>=42` |

修完复跑：26 题评测 `recall@5 = 21/24`、`MRR = 0.726`、负例 2/2、跨角色隔离 PASS
（修前 18/24、0.681）。Python 全量 **1368 passed**，新增 6 条回归测试锁住上述现场。

## 二、仍然待修（按危害排序）

1. **B2（major）去重阈值对「换人/换时间/换数字」误判 noop**：`妈妈` vs `爸爸` = 0.9273、
   `下周三` vs `下个月` = 0.9577、生日 3 月 vs 5 月 = 0.9251，全部被当成重复丢掉。
   真同义改写是 0.9654——两个区间有重叠，单靠阈值分不开。建议把 `0.90–0.97` 一律送
   review（当前 review 已默认开、由 Java 传凭据），或者加实体槽位比对。
2. **A2（major）九槽预算生产零调用**：`app/context/assembler.fit_sections` 只有测试在调，
   `history_trim_enabled` 默认关；实测 42 条历史 4495 token 一条不裁，文档里 PR-23 的 ✅ 不成立。
3. **B4（major）写库白名单形同虚设**：`writer.py` 三处 `cur.execute` 绕过 `app/write.py`；
   且 `_ALLOWED_UPDATES` 用 `startswith`，`UPDATE memory_meta SET status='superseded', summary=%s ...`
   也能过。需要改成「模板全等」匹配并把 writer 的更新走 `execute_write`。
4. **D3（major）Java 衰减没有 Δ 封顶**：`MemoryDecayService` 缺 `MAX_DECAY_DAYS=30`，
   400 天未上线一次从 0.658 掉到 0.128（直接 dormant）。
5. **D4/D5（major）dormant 只落档不删向量、也没有复活入口**：向量仍可被检索到；
   记忆页没有「让她想起来」，`MemoryController` 也没有 revive 接口。
6. **B5（major）v3 投影失败被吞**：`milvus_store.insert_v3` 返回 None，relay 仍回填
   `milvus_vec_id` 并把 outbox 标 DONE；`reconcile` 只挑 `vec_id IS NULL`，永不修复。
7. **E1（major）冷启动首轮被 Milvus 建连阻塞**：`main.py` 在事件循环里同步 `_connect_v3()`
   约 0.8s，500ms 召回预算被挤爆，首轮静默退回旧渲染。
8. **F2（major）无偏移时间戳两端差 8 小时**：MySQL 容器 UTC，Java 写 +08 墙钟，Python 按 UTC 读；
   前端按 +08 解析，出现「昨天 16:30」其实是今天 00:30。建议连接池统一
   `SET time_zone='+08:00'`，再清洗历史数据。
9. minor：`plan_budget` 忽略过小的 `total_tokens`、`clip_tool_output` 的 `cap<=3` 反向拼回全文、
   `drop_orphan_tool_messages` 不校验顺序、`confidence_mark` 把缺 `fade_level` 当确定、
   单日降级配额是全局而非每角色、`close_pending_scene` 误把 reflection id 计入 created、
   场景卡「Redis 丢卡按最近 12 条重建」未实现、superseded 在列表里与现役无区别。

## 三、上线前还差的三步

1. 生产库依次执行 V56–V59（克隆库演练已全部 exit 0，可直接照抄）。
2. `python -m scripts.init_milvus_v3` → 开 `memory_vectors_v3_enabled` →
   `python -m scripts.backfill_milvus_v3 --limit 5000`。
3. 灰度看 `GET /agent/memory/health`：`outbox_pending`、`outbox_dead`、
   `last_episode_at`、`last_decay_at` 四项；影子 overlap 连续三天 ≥0.6 再全量。

## 四、第二轮修复（2026-11-06 晚）

用户在看到上面的清单后要求「修复」。以下条目已落地：

| 编号 | 修法 | 验证 |
|---|---|---|
| B2 | `HIGH_SIMILARITY` 0.92 → 0.97：0.90–0.97 全部交给 review（默认已开、由 Java 传凭据），不再把「妈妈/爸爸」「下周三/下个月」直接吞掉 | `tests/test_memory_dedupe.py` 边界改为 0.97/0.9699 |
| B4 | 写库白名单从「前缀」改「整条语句全等正则」；`writer.py` 三处 `cur.execute` 收口到 `execute_write` | 新增两条回归：追加 `SET summary` 必须被拒、真实打点语句必须放行 |
| A2 | `history_trim_enabled` 默认改 True，按预算裁历史（42 条 4495 token → 按 recent 槽裁剪） | `tests/test_chat_turn_budget_trim.py` / `test_context_history.py` 8 passed |
| D3 | Java `MemoryDecayService` 新增 `MAX_DECAY_DAYS=30` 与 `settleStrength()`；800 天未上线也只按 30 天结算 | `MemoryDecayServiceTest.settleCapsOneRoundAtThirtyDays` |
| B5 | v3 投影失败不再标 DONE：回滚本次 v2 插入并返回 `retry`，让 outbox 重试 | `tests/test_memory_relay.py` 17 passed |
| E1 | 启动预热里的同步 `_connect_v3()` 改 `asyncio.to_thread`，不再阻塞首轮召回 | 代码审阅 + import 冒烟 |
| D5 | 新增 `POST /api/memory/{id}/revive`；记忆页「让她想起来」按钮（`canRevive` 只在非 active 时出现）；四语文案 | `MemoryQueryServiceTest` 9 passed、前端 i18n/page 13 passed |
| F2 | 只读连接池（也是写池）加 `init_command="SET time_zone='+08:00'"`，Python 的 `NOW()` 与 Java 墙钟对齐 | import 冒烟；历史行仍需要一次性清洗 |
| E3 | `pool()` 加 `asyncio.Lock`，并发首调用只建一个连接池 | — |

仍未做（留作下一轮）：dormant 的向量清理与 `embedding_state='dropped'`、D5 的 Python 侧复活分支、
superseded 在列表里的徽标、健康检查的并发化、`reconcile` 的 v3 去重、历史时间戳清洗脚本。

## 五、第三轮修复（2026-11-06 深夜）

| 编号 | 修法 | 验证 |
|---|---|---|
| D4 | dormant 落档时 `embedding_state='dropped'` 并给 outbox 入队删向量；`relay.reconcile` 跳过 dormant，避免对账把刚删的向量又补回来 | `MemoryDecayServiceTest.dormantRowsDropTheirVectorProjection` |
| B11 | `milvus_store.insert_v3` 写前先按 `memory_id` 删旧投影，同一条记忆不再在向量库里堆两份 | `tests/test_milvus_v3.py::test_insert_v3_drops_previous_projection` |
| E4 | 记忆列表与抽屉给「已被更新」虚线徽标；`canRevive` 排除 superseded（旧事实复活会和新事实同时生效） | `frontend/src/pages/__tests__/memoryPageBrain.test.js` 2 例 |
| E2 | `/agent/memory/health` 九条查询改并发 + 2.5s 总预算（原来 MySQL 全挂要等 18s） | `tests/test_memory_health.py::test_collect_gives_up_at_the_total_budget` |

仍未做（已知、有记录）：
- 历史时间戳清洗：本轮之前 Python 写入的 `created_at/updated_at` 是服务器 UTC，比 Java 的 +08 早 8 小时。
  新写入已对齐（连接池 `SET time_zone='+08:00'`），历史行需要一次性脚本核对后再迁移，不能盲加 8 小时。
- `relay.reconcile` 只按 `milvus_vec_id IS NULL` 找，不检查 v3 集合里是否真有该 id；
  向量库被手工清空后要重建，得先 `init_milvus_v3 --recreate` 再全量 backfill。
- 生产上线顺序仍是 §10.3：V56–V59 迁移 → `init_milvus_v3` → `backfill_milvus_v3` → 开开关 → 看 shadow 三天。

## 六、两条尾巴（2026-11-06 收尾）

### 6.1 旧时间戳清洗：`backend-agent/scripts/audit_clock_skew.py`

不猜时区，用「记忆一定晚于它引用的消息」判定：`created_at` 比 `source_msg_ids`
里最新消息早 1–9 小时才可疑（Python 的 UTC 与 Java 的 +08 正好差 8 小时）。

```
python -m scripts.audit_clock_skew                     # 只报告
python -m scripts.audit_clock_skew --apply             # 报告并 +8h
python -m scripts.audit_clock_skew --table memory_episode --apply
```

- 演练库实测：造一条 `created_at=12:00`、来源消息 `20:00` 的记忆 →
  第一次 `suspects=1, shifted=0`，`--apply` 后 `shifted=1`，再跑 `suspects=0`（幂等）。
- `memory_meta` 没有 `updated_at`，脚本按 information_schema 判断列是否存在，两个表都能跑。
- 只处理 `source_msg_ids` 非空的行；没有来源消息的旧行（早期画像槽位）需要人工看，不自动改。

### 6.2 `reconcile` 现在会问向量库

- `milvus_store.missing_v3_ids(ids)`：按批 `query(filter="memory_id in [...]")`，
  返回集合里真的没有的那些 id。
- `relay.reconcile(limit, deep=True)` 额外抽样「`milvus_vec_id` 有值但 v3 里没有」的行，
  重投影并把 `missing_in_v3` 写进统计；`POST /agent/memory/reconcile?deep=true` 可手动触发。
- 默认 `deep=False`：正常巡检不该每次都查 Milvus；**重建向量库之后必须跑一次 deep**。
- 测试：`tests/test_memory_relay.py` 两例（deep 会修缺失行、默认不查 Milvus）。

至此报告里的待办清零；上线顺序仍是 §10.3。
