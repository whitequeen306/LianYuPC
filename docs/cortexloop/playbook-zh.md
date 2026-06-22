# CodeCortexLoop Playbook（中文对照版）

> **机器可读原文（英文，给模型 query/record 用）：** [`.cortexloop/playbook.json`](../../.cortexloop/playbook.json)  
> **本文件：** 同一份 Playbook 的中文翻译，供人类阅读；**不替代** JSON，两者并存。  
> **更新时间：** 2026-06-22 · **版本：** 2.2 · **条目数：** 5

---

## 如何使用

| 谁 | 读什么 |
|----|--------|
| **你（人类）** | 本文档 — 看懂模型「学到了什么手法」 |
| **Agent / 脚本** | `.cortexloop/playbook.json` — `playbook.mjs query/record` |

**重要：** 所有条目当前为 **candidate（候选）**，confidence **0.4**。  
含义：只是「下次优先查这类问题」的线索，**不是**可直接粘贴的补丁；应用前仍需读代码 + 跑测试。

**tier 说明：**

| tier | 含义 |
|------|------|
| candidate | 未充分验证的假设，默认 query 不展示 |
| verified | 多样本验证通过后才晋升 |
| quarantined | 曾失败/置信度过低，已隔离 |

---

## PB-001 · 正确性（Java）

**签名：** `correctness:async-stream-callback-saves-partial-success-when:java`

**问题模式（要优先查什么）**  
异步流式回调在失败路径上，若 `error` 为 null 或未传递，仍会把「半截成功内容」当正常结果落库。

**推荐手法（怎么修，需结合现场重推）**  
- 回调 **fail-closed**：`error != null` 时立刻 return，**任何** persist / 通知 / 入队都不得执行  
- 错误发射端（如 `finishSseError`）即使已有 partial content，也 **必须** 向 callback 传非空 error  

**本仓库实例**  
- `ConversationService.java:316`（stream 回调）  
- 关联 CL：**CL-010**、**CL-011**  
- 回归测试：`ConversationServiceStreamErrorTest`

**元数据**  
- tier: candidate · confidence: 0.4 · 已应用 1 次 · 自验证 1 次

---

## PB-002 · 错误处理（通用）

**签名：** `errorHandling:empty-catch-blocks-swallow-network-store-sync-fa:any`

**问题模式**  
空的 `catch {}` 吞掉网络 / store / 同步失败，无日志、无指标、用户无感知。

**推荐手法**  
- `console.warn` / `log.warn`，带上操作名（如 `[notifications] refreshUnreadCount`）  
- Store 可暴露 `lastSyncError` 供 UI 选用  
- 前端轮询类：连续失败 N 次再 toast，**保留上次正确状态**，不要静默清空  

**本仓库实例**  
- `frontend/src/stores/notifications.js:106`  
- 关联 CL：**CL-023**、**CL-024**、**CL-025**~**CL-027**

**元数据**  
- tier: candidate · confidence: 0.4

---

## PB-003 · 简化 / 重构安全（Java）

**签名：** `simplicity:global-substring-replace-during-refactor-corrupt:java`

**问题模式**  
重构时对短标识符做全局字符串替换（如 `pack(` → `X.localePack(`），误伤 **方法定义行**，产生非法语法；`compile` 可能仍过，但 IDE/模块编译报错。

**推荐手法**  
1. 先在父类/公共类抽出 shared helper  
2. **只改调用点**，再删本地 private 副本  
3. 禁止无词界替换 method 名  
4. 验证 = **compile + 领域冒烟**（如 catalog 每个 slug 能 resolve），不能只做 compile  

**本仓库实例**  
- `CharacterSquareCatalogBlueArchive.java:97`（热修残留）  
- 关联 CL：**CL-064**、**CL-065**  
- 冒烟测试：`CharacterSquareCatalogTest`

**元数据**  
- tier: candidate · confidence: 0.4

---

## PB-004 · 正确性 / 并发（Java）

**签名：** `correctness:check-then-insert-race-creates-duplicate-rows-un:java`

**问题模式**  
「先 select 不存在 → 再 insert」在并发下产生重复行或抛未处理冲突。

**推荐手法**  
- 业务允许时加 **唯一索引**（Flyway）  
- `insert` 包在 `try/catch DuplicateKeyException`  
- catch 后 **重读** 已有行，必要时短暂 retry（`getOrCreate` 范式，参考 `CharacterStateService`）  

**本仓库实例**  
- `RelationshipStateService.java:140`  
- 同范式还用于：`ConversationService.create`（V34）、`MemoryWriter.insert`  
- 关联 CL：**CL-016**、**CL-017**、**CL-022**

**元数据**  
- tier: candidate · confidence: 0.4

---

## PB-005 · 性能（Java）

**签名：** `performance:n-1-database-queries-when-enriching-list-items-w:java`

**问题模式**  
列表接口在 loop 里对每条记录 `selectById` 补全关联实体（典型 N+1）。

**推荐手法**  
1. 先从本页结果收集外键 id 集合  
2. 一次 `selectBatchIds` / `WHERE id IN (...)`  
3. 建 `id → entity` Map，内存里 enrich  

**本仓库实例**  
- `MemoryController.java:62`  
- 关联 CL：**CL-038**

**元数据**  
- tier: candidate · confidence: 0.4

---

## 与完整修复清单的关系

Playbook **只有 5 条泛化模式**，不等于 [08-reflection-zh.md](./08-reflection-zh.md) 里 34 条「已改 CL」的逐条记录。

| Playbook | 概括了哪些 CL |
|----------|----------------|
| PB-001 | CL-010, CL-011 |
| PB-002 | CL-023~027 |
| PB-003 | CL-064, CL-065（及 catalog 热修） |
| PB-004 | CL-016, CL-017, CL-022 |
| PB-005 | CL-038 |

其余已改项（验证码、X-Forwarded-For、群聊 TURN_ERROR、Milvus warn 等）**尚未**单独写入 Playbook；若后续再次遇到同类问题，可在 `/cortexloop-reflect` 时追加 record。

---

## 维护命令（给 Agent，JSON 仍用英文）

```bash
# 查询（默认仅 verified；当前 5 条均为 candidate，需加 --include-candidates）
node ~/.cursor/scripts/playbook.mjs query --include-candidates --format=md

# 记录新 pattern（输入仍为英文 reflection.json）
node ~/.cursor/scripts/playbook.mjs record .cortexloop/reflection.json

# 某条 pattern 不适用 / 测失败
node ~/.cursor/scripts/playbook.mjs feedback --signature=<sig> --outcome=rejected
```

---

*本文档随 `.cortexloop/playbook.json` 更新而手动或脚本同步；若 JSON 增删条目，请一并更新本文件。*
