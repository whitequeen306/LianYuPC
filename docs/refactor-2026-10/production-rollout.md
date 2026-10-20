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

## 七、2026-10-20 代理站 404 事故（base_url 归一化 + WAF UA）

### 现象

- 生产 backend：`proactiveTurn error: model gateway http 404`（连续）
- agent 日志：`POST https://linzihan.fun/chat/completions -> 404`、
  `POST https://api.xiaomimimo.com/chat/completions -> 404`
- 30 分钟窗口每个实例 37 次 404；DeepSeek 走 `/chat/completions` 返回 402（key 欠费），
  恰好掩盖了路径问题

### 根因 1：迁移时丢了 Java 的 base_url 归一化（`fa6f55d`）

旧 `ChatModelFactory.normalizeOpenAiBaseUrl`：去尾 `/` → 去末尾 `/v1`，
再由 Spring AI 固定追加 `/v1/chat/completions`。Python 侧写成了 `<base>/chat/completions`，
对不带 `/v1` 的代理站直接 404。

修复：`gateway.normalize_openai_base_url`，`_post_chat` / `_stream_once` 统一使用；
12 个归一化单测覆盖生产全部 base_url 形态。

### 根因 2（部署后复测发现）：WAF 按 UA 拦截 httpx 默认值（`89a5359`）

部署根因 1 后，`https://linzihan.fun/v1/chat/completions` **仍然 404**。
同一 URL 的 UA 对照实验（假 key，只看 404 vs 401）：

| User-Agent | 结果 |
|---|---|
| `curl/8.5.0` | 401（路由存在） |
| `Python-urllib/3.11` | 401 |
| `Mozilla/5.0 ... Chrome/124` | 401 |
| `LianYu-Agent/1.0` | 401 |
| **`python-httpx/0.28.1`** | **404** |

结论：linzihan.fun 的 CDN/WAF 专门拦截 httpx 默认 UA，与 URL 形态无关。
修复：`http_client.new_client()` 统一设置 `User-Agent: LianYu-Agent/1.0 (+https://downloadlianyu.asia)`；
新增回归测试断言不得使用 `python-httpx` 默认 UA。

### 部署与验证（只重建 agent 层，先 agent 后 agent2）

| 项 | 结果 |
|---|---|
| agent / agent2 | 均 healthy |
| 容器内 `normalize_openai_base_url` | 3 处（两实例一致） |
| 容器内 `LianYu-Agent/1.0` | 1 处（两实例一致） |
| `linzihan.fun` 探测 | `POST https://linzihan.fun/v1/chat/completions -> 401`（不再是 404） |
| `api.xiaomimimo.com` 探测 | `/v1/chat/completions -> 401`（不再是 404） |
| 404 计数（部署后 10 分钟，两实例） | **0 / 0**（部署前 30 分钟各 37） |
| 出站 URL 形态 | 全部为 `https://.../v1/chat/completions` |
| 20 条真实对话（账号 hjkl，完整 Java 链路） | **20/20 非空，0 失败** |
| parity 巡检 | `golden[zh] 2588/2588`、`golden[en] 4583/4583` → ALL_PASS |
| `JAVA_AI_PATH` / `proactiveTurnResult failed` | 0 / 0 |

> 401/402/`upstream circuit open` 是用户自己的 key 无效/欠费，属预期降级，不是本次修复范围。

## 八、2026-10-20 4xx 误熔断事故（熔断分类）

### 现象

30 分钟窗口：

```
model gateway http 401                 114 次
model gateway http 402                 130 次
upstream circuit open for Provider326   11 次
```

401/402 是**用户自己的 key 失效/欠费**，却被按 `provider|baseUrl` 记入熔断失败率
（阈值 50%），导致同 provider 下的正常用户被一起快失败。

### 修复

- `gateway._raise_for_status`：2xx 正常；4xx（429 除外）→ `GatewayBusinessError`
  （熔断按成功记录）；5xx / 429 / 传输层 → `ModelGatewayError`（计入熔断）
- `ModelGatewayError` 改为继承 `GatewayError`；`stream_chat` / `text_turn` /
  `vision` / `image_chat` / `desktop_observe` 统一 `except GatewayError`

### 部署时发现提交的修复不完整（已补，`ef26d6f`）

只测 `_raise_for_status` 会漏掉真实调用路径：

1. **阻塞路径 `_do_post` 未接入分类**：仍 `raise ModelGatewayError(http {status})`，
   4xx 继续计入熔断；
2. **流式路径 `_stream_once` 的 `except Exception` 重新包装**：先抛
   `GatewayBusinessError`，随后被网络层 `except Exception` 包成
   `ModelGatewayError("transport error")`，又变成熔断失败。

复现证据（修复前）：

```
blocking: ModelGatewayError: model gateway http 401 (business=False)
stream:   ModelGatewayError: model gateway transport error: model gateway http 401 (business=False)
```

补齐：`_do_post` 改用 `_raise_for_status`；`_stream_once` 改为 `except GatewayError: raise`。
新增路径级 + 熔断级测试：10 次 401 后 breaker 仍 `CLOSED`；10 次 500 后 `OPEN`。

### 部署与验证

- 只重建 agent 层，先 agent 后 agent2；两实例 healthy
- 容器内 `GatewayBusinessError` 4 处、`_raise_for_status(status)` 已接入
- 20 条真实对话：**20/20 非空，0 失败**
- agent 500：**0**
- parity：`golden[zh] 2588/2588`、`golden[en] 4583/4583` → ALL_PASS
- 30 分钟观察（部署后窗口，agent 启动 09:53:15Z）：

| 指标 | 修复前（30min） | 修复后（30min） |
|---|---|---|
| `circuit open` | 11 | **0** |
| `model gateway http 401` | 114 | 109 |
| `model gateway http 402` | 130 | 133 |
| `model gateway http 500` | — | **0** |
| agent / agent2 `500 Internal Server Error` | — | **0 / 0** |
| `proactiveTurnResult failed` | — | **0** |

401/402 仍会出现（用户自己的 key 失效/欠费，预期不变），但**不再连累同 provider 的其他用户**。
