# 生产部署记录（2026-10-20 · OFF 退役上线）

> 对应提交：`0580a07..28cb429`（OFF 退役 111 个提交 + 2 个生产 500 热修）。
> 部署方式：`scripts/_cloud_deploy_layered.py`（分层构建，非一把梭）。
> 本文只记录**事实与证据**，结论性判断见 `off-retirement-plan.md`。

## 一、部署前置检查

| 项 | 结果 |
|---|---|
| `git pull` | HEAD `ef61588`（后热修至 `28cb429`） |
| `docker compose config --quiet` | COMPOSE_CONFIG_OK |
| `.env` 关键键 | DASHSCOPE / MYSQL_USER / MYSQL_PASSWORD / REDIS_PASSWORD / EMBEDDING / RERANKER / LIANYU_MASTER_KEY / MULTIMODAL_* 全部 SET |
| `LIANYU_AGENT_MODE` | **absent**（OFF 退役已删除该配置） |
| `LIANYU_AGENT_BASE_URL` | 原缺失 → 已补 `http://agent-lb:8001`（备份 `.env.bak.deploy-*`） |
| 服务器 agent 容器 | 首次部署前**不存在**（agent 层首次上生产） |

## 二、分层部署

顺序：`agent` / `agent2` / `agent-lb` → `backend` → `asr` / `api-gateway`。

| 层 | 结果 |
|---|---|
| agent / agent2 | healthy |
| agent-lb | up（带 `proxy_next_upstream` 故障转移修复） |
| backend | started，`Agent runtime — all ports wired`（5 个端口全 true） |
| asr / api-gateway | healthy / started |
| 健康探测 | `captcha=200` + `agent=200`（第 4 次尝试通过） |

## 三、部署中发现并修复的问题（2 个）

### 1. 主动发言定时任务全量 500（严重）

- 现象：`/agent/proactive-turn` 持续 500，backend 每分钟数条 `proactiveTurnResult failed`
- 根因（两层）：
  1. `proactive_turn.invoke` 只在「无工具」分支捕获 `ModelGatewayError`；带 tools 时走 `tool_loop`，异常直接炸出图
  2. 用户 key 欠费（402）打开熔断后，上游抛的是 `GatewayRejectedError`（熔断快速失败），与 `ModelGatewayError` 是**兄弟类**，原 except 接不住
- 修复：
  - `MODEL_GATEWAY_ERRORS = (ModelGatewayError, GatewayError)` 统一捕获元组
  - proactive 图两个分支外层统一 `except MODEL_GATEWAY_ERRORS`
- 提交：`ceceff7`、`28cb429`
- 验证：容器启动后 5 分钟窗口 `agent http500=0`；上游错误被正确折叠为 `error` 字段（backend 记 WARN，不再 500）

### 2. 单实例故障时 LB 不 failover（严重，部署前已修）

- 现象：停掉一个 agent，健康探测 6/6 返回 502（另一个 agent 明明健康）
- 修复：`proxy_next_upstream error timeout http_502 http_503 http_504; proxy_next_upstream_tries 2; proxy_connect_timeout 3s;`
- 提交：`174e55a`
- 验证：停一台后 8/8 探测 200，6/6 真实对话成功

### 3. agent 重启窗口丢后台任务（部署后发现 → 已修）

- 现象：`11:35:39` 一条 MOMENTS_POST 失败（`prompt 组装失败`），该任务直接进 DLQ——容器工厂
  `defaultRequeueRejected(false)`，失败不重投
- 实证：失败在 `03:35:39.458`，agent 应用就绪在 `03:35:39.892`——**0.4 秒启动竞态**
- 修复（`90c200e`）：`AiBackgroundConsumer` 对「agent 侧暂时不可用」错误做**有界重试**
  （3 次，1.5s/3s 递增退避）；业务错误仍立即失败进 DLQ
- 验证：新增 8 个单测（重试成功 / 非瞬态立即失败 / 3 次耗尽后抛出 / cause 链识别）；
  `mvn test` BUILD SUCCESS；已部署生产（镜像创建时间晚于提交时间，parity 仍 ALL_PASS）

## 四、部署后验证

### parity 巡检（生产容器内跑）

```
[PASS] golden[en] expected=4583 actual=4583 status=200
[PASS] golden[zh] expected=2588 actual=2588 status=200
[PASS] agent_health: {"status":"UP"}
RESULT: ALL_PASS
```

### 20 条真实对话（账号 hjkl，完整 Java 链路）

- 前置：该账号为全新账号，已建 vault（Provider381 / qwen-plus）+ 角色 950「林晚」+ 会话 900
- 结果：**20/20 有内容，0 失败**，耗时 1.1–2.1s/条

| 指标 | 值 |
|---|---|
| 总轮次 | 20 |
| 非空回复 | 20 |
| HTTP 失败 | 0 |
| 最短/最长 | 3 / 41 字 |
| 平均耗时 | ≈1.6s |

### SSE 流式

```
SSE_STATUS 200 FRAMES 11
SSE_HAS_CONTENT True  SSE_HAS_PIECES True  SSE_HAS_DONE True
tail: {"replace":"...","pieces":["...","..."]} + [DONE]
```

### 日志

| 项 | 值 |
|---|---|
| agent http 200（容器启动以来） | 264 |
| agent http 500 | **0** |
| `JAVA_AI_PATH` | **0** |
| `proactiveTurnResult failed` | 0 |

## 五、已知遗留（未修，需决策）

1. ~~**agent 重启窗口内的在途后台任务会失败一次**~~ → **已修**（`90c200e`，有界重试），见 §三.3
2. **测试数据留在生产**
   - 账号 `hjkl`：vault 381（存有生产 DASHSCOPE key，加密）、角色 950、会话 900 + 21 条消息
   - 如不需要可自行删除（删除 vault 前确认没有其他客户端在用）
3. **旧镜像未清理**：按计划等 24h 观察期后再删。

## 六、观察工具

```powershell
# 24h 观察期任意时刻跑（读 .env 的 DEPLOY_SSH_PASSWORD）
python scripts/ops/prod_error_watch.py --hours 24
# 短窗口
python scripts/ops/prod_error_watch.py --minutes 30
```

判定阈值（脚本内置）：`JAVA_AI_PATH=0`、`agent http 500=0`、`proactiveTurnResult failed=0`；
`prompt 组装失败` 允许重启窗口内极少量；`AI background job failed` 应为 0
（有重试后，瞬态错误会先出现 `retry in` 日志）。
