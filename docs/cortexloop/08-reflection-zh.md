# CodeCortexLoop 修复回顾（中文 · 人类阅读版）

> 对应英文结构化记录：`.cortexloop/reflection.json`、`.cortexloop/playbook.json`  
> 英文回顾摘要：`docs/cortexloop/08-reflection.md`  
> 基准扫描：`docs/cortexloop/report.json`（deep 预设，overall=32，Report 模式）

**修复批次**：Critical + High 共 41 条（Medium/Low 本轮未做）  
**验证**：`mvn test`（lianyu-service / lianyu-web）✅ · `npx vitest run` ✅ · `npm run build` ✅

---

## 一、逐条 CL 状态（41 条 High/Critical）

### Batch 1 — 正确性 + 安全

| ID | 状态 | 做了什么 |
|----|------|----------|
| **CL-010** | ✅ 已改+已测 | `AiChatService.finishSseError` 始终向 callback 传非空 error；`ConversationService` stream 回调 `error != null` 时直接 return，不落库半截回复。回归：`ConversationServiceStreamErrorTest` |
| **CL-001** | ✅ 已改 | `AuthController.captcha()` 去掉 `expression` 字段；Login/Register 页移除表达式 fallback，仅显示图片 |
| **CL-002** | ✅ 已改 | 新增 `ClientIpResolver` + `lianyu.security.trust-forwarded-for`（默认 true，适配 api-gateway）；Auth/Observe 注入使用 |

### Batch 2 — Critical 错误处理

| ID | 状态 | 做了什么 |
|----|------|----------|
| **CL-023** | ✅ 已改 | `ChatPage` 轮询 catch 加 `console.warn`；连续失败 ≥3 次 `ElMessage.warning`；成功后清零 |
| **CL-024** | ✅ 已改 | `notifications.js` 各 sync 操作 catch 加 warn；暴露 `lastSyncError` ref |

### Batch 3 — High 正确性

| ID | 状态 | 做了什么 |
|----|------|----------|
| **CL-011** | ✅ 已改 | SSE 发送失败后 `pollCurrentConversationMessages(true)` 与 DB 对齐 |
| **CL-012** | ✅ 已改 | `GroupChatService` 多气泡 loop 每次 insert / sleep 前复查 `turnId` |
| **CL-013** | ✅ 已改 | `GroupChatPage` 处理 WS `USER_MESSAGE`，与乐观插入去重 |
| **CL-014** | ✅ 已改* | `MemoryWriter` 新 hash = SHA-256(sorted source_msg_ids)；空 ID 时回退 legacy 文本 hash |
| **CL-015** | ✅ 已改 | enqueue 前 Redis debounce 30s，防并发重复摘要任务 |
| **CL-016** | ✅ 已改 | `RelationshipStateService.loadOrCreateState` DuplicateKey → 重读 |
| **CL-017** | ✅ 已改 | Flyway V34 单聊唯一索引 + create 冲突 catch 重选 |
| **CL-018** | ✅ 已改* | 冷开场 Redis SETNX 锁 + 写库前二次检查 |
| **CL-019** | 📋 仅注释 | 保持 LLM 完成顺序落库；产品是否改 roster 顺序待人类定 |
| **CL-020** | ✅ 已改 | 群聊 `reserveSeqBlock` Redis increment 为 null 时抛异常 |
| **CL-022** | ✅ 已改 | `MemoryWriter.insert` DuplicateKey → SKIPPED |

\*CL-014 新 hash 与历史 `source_hash` 可能并存；CL-018 锁 TTL/语义可再评审。

### Batch 4 — High 错误处理

| ID | 状态 | 做了什么 |
|----|------|----------|
| **CL-025** | ✅ 已改 | notifications STOMP JSON parse 失败打 warn |
| **CL-026** | ✅ 已改 | `useConversationUnread` refresh 失败 warn，保留上次角标 |
| **CL-027** | ✅ 已改 | `desktopObserver.js` observe 失败 `console.warn` |
| **CL-028** | ✅ 已改 | 群聊零回复发 `TURN_ERROR`；前端已处理 |
| **CL-029** | ✅ 已改 | `CharacterStateController` inner space 失败 `log.warn` |
| **CL-030** | ✅ 已改 | Milvus insert 返回 null 打 warn；禁止 null 覆盖已有 `milvusVecId` |
| **CL-031** | ✅ 已改 | MinIO `objectExists` 区分 NoSuchKey 与其它错误并 warn |
| **CL-032** | ✅ 已改 | 群聊 `openGroup` 加载失败 toast，**不清空**已有消息 |

### Batch 6 — High 简化

| ID | 状态 | 做了什么 |
|----|------|----------|
| **CL-062** | 📋 仅文档 | `docs/cortexloop/CL-062-prompt-dedup-notes.md`（未改 Prompt） |
| **CL-063** | ✅ 已改 | 删除未使用的 `buildOutputLanguageBlock()` |
| **CL-064** | ✅ 已改 | 6 个 franchise catalog 改用 `CharacterSquareCatalog.localePack` / `franchiseTags` |
| **CL-065** | ✅ 已改 | `slugForSortOrder` 改为 `SLUGS_BY_SORT_ORDER` 列表映射 |

### Batch 7 — High 性能

| ID | 状态 | 做了什么 |
|----|------|----------|
| **CL-038** | ✅ 已改 | `MemoryController` 列表 N+1 → `selectBatchIds` 批量查角色 |
| **CL-039** | ✅ 已改 | `ConversationService.list` 加 `LIMIT 200` |
| **CL-040** | 📋 仅方案 | `docs/cortexloop/performance-cl040-cl041.md` |
| **CL-041** | 📋 仅方案 | 同上 |
| **CL-042** | ✅ 已改 | 朋友圈评论轮询仅针对**已展开**的帖子 |

### Batch 8 — 测试补齐

| ID | 状态 | 新增/覆盖 |
|----|------|-----------|
| **CL-050** | ✅ | `AuthRateLimiterTest` |
| **CL-051** | ✅ | `CaptchaServiceTest` |
| **CL-052** | ✅ | `vitest.config.js` + secureToken / sseParser 测试 |
| **CL-053** | ✅ | `ConversationServiceStreamErrorTest`（CL-010 回归） |
| **CL-056** | ✅ | 同上 AuthRateLimiter |
| **CL-058** | ✅ | `AuthControllerCaptchaTest` + `ClientIpResolverTest` |
| **CL-054~055,057,059** | ⏸ 未单独补 | 逻辑已在 Batch3/4 改完，无独立切片测试 |
| **Catalog 冒烟** | ✅ | `CharacterSquareCatalogTest`（refactor 热修后补） |

**统计**：✅ 已改 **34** · 📋 文档/待决策 **4**（CL-019/062/040/041）· ⏸ 测试未单列 **3** · *需人类确认 **2**（CL-014/018）

---

## 二、热修与踩坑（catalog CL-064 后续）

脚本全局替换 `pack(` 时误伤了**方法签名**，6 个 franchise 文件出现非法语法：

```java
private static LocalePack CharacterSquareCatalog.localePack(...)
```

- 第一次只跑了 `mvn compile`，**不够**  
- 清理残留 stub 后补 `CharacterSquareCatalogTest`，`mvn test` 通过  

**教训**：机械 refactor 后必须 **compile + 领域冒烟测试**，不能只看 IDE 是否还红。

---

## 三、仍需人类决策

| 项 | 说明 |
|----|------|
| CL-002 默认值 | 经 api-gateway 保持 `trust-forwarded-for=true`；直连后端可设 false |
| CL-014 历史数据 | 是否清/迁移旧 `source_hash` |
| CL-019 | 群聊按 roster 顺序 vs 完成顺序 |
| CL-040/041 | 见性能方案文档，先测量再改 |
| CL-062 | Prompt 去重方案，见 notes 文档 |
| **前端重打包** | Login/Register/Chat/Group/Moments/notifications 等有改动 → 需重打 Electron |
| **后端部署** | 含 V34 迁移，`git pull` + 重建 backend |

---

## 四、与 Playbook 的关系

Playbook 机器可读版：`.cortexloop/playbook.json`（英文）  
**中文对照（给你看模型学到了什么）：[playbook-zh.md](./playbook-zh.md)**

---

## 五、复测建议

1. 合并前再跑一遍 `mvn test` + `npx vitest run`  
2. 重跑 `/cortexloop-deep` Direct 或 Report，对比 overall 分数  
3. 人工抽测：验证码图片登录、单聊 SSE 中断、群聊多设备 USER_MESSAGE、记忆列表加载  

---

*生成时间：2026-06-22 · 模式：Direct 等价手工执行*
