# 失败路径用户体验分析（OFF 退役后：无 Java 回退）

> 结论先行：**agent 全挂时用户看到明确、可读的错误提示（1~15 秒内），
> 前端有自动重试；消息不会丢（输入框内容恢复）。**
> **但单实例故障时 LB 存在架构缺陷（见文末「发现的真实 bug」），导致 50% 请求失败
> 而不是 failover 到健康实例。这是一个需要修复的部署配置问题。**

## 测试方法

真实 docker 栈（非 mock），三种场景 × 两条链路：

| 场景 | 操作 | 测试链路 |
|---|---|---|
| A 全部健康 | 基线 | `POST /api/conversation/{id}/messages`（阻塞）<br>`POST /api/conversation/{id}/messages/stream`（SSE） |
| B 两个 agent 全停 | `docker stop lianyu-agent lianyu-agent-2` | 同上 |
| C 只停一个 | `docker stop lianyu-agent-2`，连发 6 次 | 阻塞 × 6 |

## 场景 A：基线（全部健康）

| 链路 | 结果 | 耗时 |
|---|---|---|
| 阻塞 | 200，回复「晚安啦～今晚的月光，我替你先收好一半。」 | 1303ms |
| SSE | 200，6 帧 content + 1 帧 pieces + [DONE] | 1029ms |

## 场景 B：agent 全停

### 用户看到什么

**阻塞对话（非流式）：**

| 项 | 值 |
|---|---|
| HTTP 状态 | 500 |
| 响应体 | `{"code":500,"message":"服务暂时不可用，请稍后再试"}` |
| 耗时 | **14445ms**（≈15秒） |

**流式对话（SSE）：**

| 项 | 值 |
|---|---|
| HTTP 状态 | 200（SSE 已开流） |
| SSE 帧 | **0 帧（连接立即关闭，无内容帧、无错误帧、无 [DONE]）** |
| 耗时 | 3095ms |

### 用户感知分析

| 维度 | 阻塞 | 流式 |
|---|---|---|
| **提示语可读** | ✅「服务暂时不可用，请稍后再试」 | ⚠️ **无提示**，连接静默关闭 |
| **有重试引导** | ✅ 含「请稍后再试」 | ⚠️ 前端会显示通用错误，但 SSE 收不到错误帧 |
| **消息是否丢失** | ✅ 前端恢复输入框内容 | ✅ 前端恢复输入框内容 |
| **响应延迟** | ⚠️ **15 秒**（健康探测超时 × 4 次端口） | 3 秒 |

### 延迟构成（阻塞 15 秒）

```
第 1 次请求：prompt 组装 → 健康探测失败（~3s 超时）→ 报错
                                            ↓ 不是立即报错
后续 5 次请求：健康缓存命中（3s TTL）→ 立即报错（~30ms）
```

第一次请求最慢（探测超时），之后 3 秒内走缓存快速失败。

### 前端处理（代码追踪）

| 链路 | 前端行为 | 来源 |
|---|---|---|
| 阻塞（`Result` JSON） | `httpCore.js` 解析 `code != 200` → `ElMessage.error(humanizeError(msg))` → **用户看到 toast** | `httpCore.js:113-119` |
| 流式（fetch SSE） | `ChatPage.vue:1414` `if (!response.ok)` → 解析 JSON error body → throw → `catch` → **toast + 恢复输入框** | `ChatPage.vue:1414-1420, 1458-1463` |
| 流式中途断 | `drainAssistantStream` reader 自然结束 → `fullContent=""` → 正常返回（**不是 throw**）→ 页面表现为「没有回复」但不报错 | `assistantStreamDrain.js:26-38` |

**⚠️ 发现的问题：流式中途断连（SSE 开了但 agent 死了）用户看不到任何错误提示。**
`drainAssistantStream` 会把空流当成正常结束返回空字符串，前端不会弹 toast。
用户感知是「消息发出去了但角色没回复」。

## 场景 C：只停一个 agent（LB fail_timeout 窗口）

| 试次 | 结果 | 耗时 | 说明 |
|---|---|---|---|
| 1 | **500 服务暂时不可用** | 14340ms | 请求路由到挂掉的 agent-2，LB 尝试后 502 |
| 2-6 | **500 服务暂时不可用** | 20-40ms | 健康缓存命中（3s TTL），快速失败 |
| — | **0 次成功** | — | agent-1 明明健康，但 LB 没有把请求转过去 |

### 发现的真实 bug：LB 健康检查不 failover

nginx 轮询 LB（`max_fails=2 fail_timeout=10s`）在**一个后端停掉**时：

```bash
# agent-2 停掉后，从 backend 容器探测 LB 健康端点 6 次
$ wget http://agent-lb:8001/agent/health
HTTP/1.1 502 Bad Gateway   × 6 次（agent-1 明明活着）
```

**原因**：nginx 的 `max_fails` 只统计「主动转发失败」次数；
健康探测（短连接 GET）被均匀分到两个后端，路由到 agent-2 的那 50% 直接 502。
LB 不会把 GET /agent/health 转到 agent-1。

这意味着：**单实例故障时，约 50% 的用户请求会失败**（取决于轮询命中哪个后端），
而不是全部 failover 到健康实例。

## 结论汇总

| 问题 | 严重度 | 状态 | 建议 |
|---|---|---|---|
| agent 全挂：阻塞回复报错可读、有重试引导 | — | ✅ 满足 | 不需要改 |
| agent 全挂：消息不丢（前端恢复输入） | — | ✅ 满足 | 不需要改 |
| agent 全挂：流式静默关闭，无错误帧 | 中 | ⚠️ 待修 | `AssemblePromptNode` 异常时应在 SSE 前写入错误帧，但 `GlobalExceptionHandler` 的 `shouldSkipJsonErrorBody` 会拦截——需要确认 SSE 端点的异常时序（是开流前还是开流后抛） |
| 单实例挂：LB 不 failover 健康请求 | **高** | ✅ **已修复实测** | `proxy_next_upstream` 已加，见文末验证 |
| 阻塞首次请求 15 秒才报错 | 低 | ⚠️ 可接受 | 健康探测超时可从 3s 降到 1s；但 OFF 退役后探测失败就该立即报错 |

## 建议修复（单实例 LB failover）→ ✅ 已修复并实测

```nginx
# deploy/agent-lb/nginx.conf location / 内新增：
proxy_next_upstream error timeout http_502 http_503 http_504;
proxy_next_upstream_tries 2;
proxy_connect_timeout 3s;
```

**修复后实测（2026-10-19，同一套 docker 栈）：**

1. 停 `lianyu-agent-2`，从 backend 容器探测 LB 8 次：
   `{"status":"UP"}` × 8（修复前：502 × 6）。
2. 停 `lianyu-agent-2`，跑 6 条真实阻塞对话：
   **6/6 成功**，耗时 850–1770ms（修复前：0/6 成功）。
3. `docker start lianyu-agent-2` 恢复后两实例均 healthy。

**代价**：SSE 请求若在已发出后遇到上游错误，`proxy_next_upstream` 不重试
（`proxy_next_upstream` 对 non-idempotent POST 仅在「请求未发出」时生效），
语义安全，不会出现双重生成。
