# CL-040 / CL-041 性能改造方案（待人类确认后再实施）

## CL-040 — SSE 首 token 前重活

**现状**：`ConversationService.sendMessageStream` 在打开 SSE 前同步完成 prompt 组装、记忆检索、关系状态等。

**测量**（部署前在 staging 执行）：
```bash
# 后端日志加 traceId，统计 sendMessageStream 入口 → 首个 SseEmitter.send 的耗时
grep 'chatStream' lianyu-backend.log | ... # 或 Micrometer timer: lianyu.chat.sse.first_token_ms
```
目标基线：P95 > 2s 则值得异步化。

**方案 A（推荐）**：首包立即发送 `{"status":"thinking"}` 心跳，重活放 `CompletableFuture` 或已有 bulkhead 线程池；token 流仍走现有 `AiChatService.chatStream`。

**方案 B**：仅把 `memoryRetriever.retrieve*` 与 `relationshipStateService` 改为并行 `CompletableFuture.allOf`，保持单线程 prompt 组装。

**风险**：Prompt 顺序/工具上下文绑定需保持与现网一致；需回归 CL-010 半截保存逻辑。

---

## CL-041 — 群聊 fan-out 打满 bulkhead

**现状**：`GroupChatService` 每轮为每个成员 `supplyAsync` 调 LLM，与 Resilience4j bulkhead 共享池。

**测量**：
- 5 人群、3 轮 auto-reply，观察 bulkhead 等待队列长度与 `rejected` 计数。
- JMeter：并发 10 个群各发 1 条用户消息。

**方案 A**：每轮串行 roster 顺序（与 CL-019 产品决策联动）。

**方案 B**：限制每轮最大并发 = `min(members, bulkheadMaxConcurrent)`，其余排队。

**方案 C**：按 `@mention` 缩小 roundMembers（已有），再加全局 per-user 群聊并发上限 1。

---

生成时间：CortexLoop 修复批次 Batch7 handoff。
