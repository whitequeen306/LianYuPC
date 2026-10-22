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

## 九、2026-10-21 追平进度（`ef26d6f → c9be5a0`，25 个提交）

上一轮部署停在 `ef26d6f`，之后本地累积 25 个提交（LangGraph 编排整体接管聊天回合、
TTS/音色注册迁 Python、路由 404 修复、客户端 0.3.1）。本节记录这一次追平。

### 部署范围判定

先看改动面再决定重建什么（不是一把梭）：

| 检查项 | 结果 |
|---|---|
| `docker-compose.yml` / `Dockerfile` / `deploy/**` | **0 处改动** |
| `backend-agent/pyproject.toml` / `uv.lock` | **0 处改动**（无新依赖） |
| Flyway 迁移 `*.sql` | **0 个新增**（schema 版本仍为 54） |
| 需重建的层 | **agent + backend**；asr / api-gateway 不动 |

因此本次是「源码层重建」，中间件与网关不重启。

### 部署前置

| 项 | 值 |
|---|---|
| 本地回归 | pytest **1197 passed**；`mvn test` BUILD SUCCESS；vitest **466 passed**；`check-java-ai-free.ps1 -Mode strict` **0/0/0/0** |
| 推送 | `047eafb..c9be5a0` → `origin/main`，服务器 `git pull --ff-only` 后 HEAD = `c9be5a0` |
| 磁盘 / 可用内存 | `/` 28G 可用；available 11.4G |
| `.env` 关键键 | DASHSCOPE / MYSQL_* / REDIS / MINIO / LIANYU_MASTER_KEY / LIANYU_AGENT_BASE_URL / APP_REVISION 全部 SET |
| 回滚基线 | `lianyu-pc/agent:cloud-rollback-ef26d6f` = `829287aed55e`；`lianyu-pc/backend:cloud-rollback-ef26d6f` = `ea3cd3ca7b97` |

### 分层结果

| 层 | 结果 |
|---|---|
| agent 镜像 | `341ba762bc09`（554MB），构建 32s |
| agent2 | 先重建 → healthy；agent 后重建 → healthy（滚动，任意时刻至少一台在服务） |
| 容器内新模块 | `dashscope.py` / `tts.py` / `voice_enrollment.py` / `chat_turn.py` / `message_assembly.py` / `prompt_pipeline.py` **两实例均存在** |
| backend 镜像 | `ac2bfbefd33e`（835MB），构建 **120s** |
| backend 启动 | captcha=200（第 3 次探测，约 15s） |
| 端口接线 | **8/8 all ports wired**：proactiveTurn / stream / text / memory / image / **chatTurn** / **tts** / **voiceEnrollment** |
| Flyway | validated **54** migrations，schema 版本 54，`No migration necessary` |

### 端点存在性（agent 容器内 python 探针，无 curl）

空 body 语义：端点存在 → 422（缺字段）；不存在 → 404。两实例结果一致：

```
/agent/voice/enroll     -> 422
/agent/tts/synthesize   -> 422
/agent/chat-turn/inspect-> 422
/agent/chat-turn/stream -> 422
/agent/does-not-exist   -> 404
```

### 端到端验证（`scripts/_prod_verify.py`，完整 Java 链路，账号 hjkl / vault 381 / 会话 900）

| 项 | 结果 |
|---|---|
| 阻塞回合 | **6/6 非空，0 失败**，1503–1854ms |
| SSE 流式 | status 200，9 帧，`content` 帧 + `[DONE]`，正文 16 字，1744ms |
| `POST /api/community/push-catchup` | **200**（原来是 500） |
| 未知路由 | **404**（原来是 500） |

回合确实走 Python LangGraph，两侧日志互证（不是悄悄回退）：

```
backend: ChatTurn stream served by LangGraph runtime: scene=SINGLE
agent:   chat_turn.assemble_prompt scene=SINGLE chars=3765
agent:   chat_turn.assemble_messages count=33 prepared=false
agent:   chat-turn stream done: user=402 frames=8
```

### parity 巡检（生产容器内）

```
[PASS] golden[en]: status=200 expectedChars=4583 actualChars=4583
[PASS] golden[zh]: status=200 expectedChars=2588 actualChars=2588
[PASS] agent_health: status=200 body={"status":"UP"}
4/4 checks passed
```

> 踩坑：脚本用 `Path(__file__).parent.parent.parent` 推仓库根，直接丢到 `/tmp` 跑会把根算成 `/`。
> 必须放到 `/work/scripts/ops/` 下、并把 prompts 拷到 `/work/backend-agent/app/prompts`。

### 迁移后才有的链路：实测

| 链路 | 证据 |
|---|---|
| 记忆写入 + relay（原 Java `EmbeddingService` 职责） | `POST /agent/memory/extract-write 200`；`memory write done: created=2/2/1`；`memory relay: {'picked':2,'done':2,'dead':0}` 与 `{'picked':3,'done':3}` |
| 向量库 | `milvus collection ready: memory_vectors_v2`；`/embeddings` 连续 200 OK |
| TTS（Python 合成 + 下载音频） | `qwen3-tts-flash` + `Cherry` → **200 / audio/wav / 169004 bytes / magic 52494646 (RIFF)** |

### 30 分钟观察（部署后窗口）

`scripts/ops/prod_error_watch.py --minutes 30` → **PASS**

| 指标 | 值 |
|---|---|
| `JAVA_AI_PATH` | **0** |
| agent / agent2 `500 Internal Server Error` | **0 / 0** |
| `proactiveTurnResult failed` | **0** |
| `prompt 组装失败` | **0** |
| `AI background job failed` / `retry in` | **0 / 0** |
| backend `ERROR` 行 | **0** |
| `NoResourceFoundException` | **0**（垫片接管，改为 INFO `legacy client still polls …`） |
| `circuit open` | **0**（4xx 分类修复仍生效） |

### 已知且**预期**的降级（不是本次引入）

- 主动发言 20 分钟内 25 次，全部 `model gateway http 401/402`：**用户自己的 key 失效/欠费**。
  关键是没有一条变成 500 —— 错误被折叠进 `error` 字段，backend 记 WARN。
- 本次未在**生产**验证的两条：① 图片回合（需真实图片 + vault）；
  ② Java→Python TTS seam 的完整链路（`/api/desktop/observe` 需要已配置音色的桌宠）。
  两者都有端口接线 + 单测覆盖，但不算生产实测，后续有人用到时再补证据。

### 回滚方式

```bash
cd /opt/lianyu
docker tag lianyu-pc/agent:cloud-rollback-ef26d6f   lianyu-pc/agent:cloud
docker tag lianyu-pc/backend:cloud-rollback-ef26d6f lianyu-pc/backend:cloud
docker compose up -d --no-deps agent2 agent backend
```

回滚只回镜像，不回代码：容器是从镜像跑的，与 `/opt/lianyu` 的工作区无关。
代码要回退需另做 `git reset`，且必须重新构建镜像才生效。

> 注意：本次部署后 `/opt/lianyu` 又 `git pull` 过一次（只含文档与脚本，无源码改动），
> 所以工作区 HEAD 会领先于「实际在跑的镜像源码」，这是预期的。

## 十一、2026-10-22 语音链路修复上线（`be3ed8f → 781f108`）

### 上线前发现的致命前提：音色按账号隔离

13 个角色音色先是用临时给的 key（`sk-0fd7927…`）注册在 `qwen-audio-3.1-tts-flash` 上，
而生产 `.env` 是另一把 key（`sk-01de47e…`）。**两者不统一，部署上去仍会报
`voice does not exist or is not licensed`** —— 等于白部署一次。

用生产 key 实测三个模型：

| 模型 | 结果 |
|---|---|
| `qwen3-tts-flash` + Cherry | ✅ OK（但不支持复刻，做不了角色音色） |
| **`cosyvoice-v3-flash`** + longanyang | ✅ **OK（支持复刻）** |
| `qwen-audio-3.1-tts-flash` + Cherry | ❌ `[cosyvoice:]Engine error [411]`（该模型未开通） |

结论：**不换生产 key、不动生产配置**，改用 `cosyvoice-v3-flash`，用生产 key 重注册。
CosyVoice 完整链路（生产 key）：注册 → 第 5 次轮询 OK → 整句有 audio.url →
**SSE 流式首包 536ms / 27 块 / 195,840 字节** → 删除 200。

### 部署内容

- agent 镜像 `e7f5d86f6e37`（`voice_enrollment` 新接口、`tts.synthesize_stream`、
  `/agent/tts/stream`、`RECOMMENDED_HTTP_MODEL=cosyvoice-v3-flash`）
- backend 镜像 `8f5e8b78318b`（`pet-voices.json` 13 个 CosyVoice 音色、
  `TtsPort.streamSynthesize` + 适配器 + 通话按块流式、`voiceCallReady` 判据改为 httpVoiceId）
- 回滚基线：`cloud-rollback-be3ed8f0` → agent `341ba762bc09` / backend `ac2bfbefd33e`
  （`cloud-rollback-ef26d6f` 仍保留，未覆盖）

### 部署后验证（生产容器内，生产 key）

| 项 | 结果 |
|---|---|
| 端点存在性 | `voice/enroll`、`voice/delete`、`tts/synthesize`、**`tts/stream`**、`chat-turn/*` 均 422（存在）；未知路由 404 |
| 整句合成（cosyvoice klee 音色） | **200 / audio/wav / 169,004 字节 / magic 52494646** |
| 流式合成 | **200 / audio/pcm / 24000Hz / 首包 895ms / 21 块 / 168,960 字节** |
| 不存在的音色 | 整句 204、流式 200+0 字节（**优雅降级，不 500**） |
| 8 端口接线 | `all ports wired`：proactiveTurn/stream/text/memory/image/**chatTurn**/**tts**/**voiceEnrollment** 全 true |
| 真实对话 | 6/6 非空，860–1717ms；SSE 8 帧 + `[DONE]`，15 字 |
| 路由行为 | `push-catchup` 200、未知路由 404（沿用 0.3.3 那次修复） |
| parity 巡检 | `golden[en] 4583/4583`、`golden[zh] 2588/2588` → **4/4 PASS** |
| 错误计数 | `JAVA_AI_PATH` 0、`proactiveTurnResult failed` 0、agent/agent2 500 **0/0**、backend ERROR **0** |
| 公网 | `https://downloadlianyu.asia/api/auth/captcha` → 200；agent-lb 健康 200 |

### 踩到的坑

1. **音色与账号绑定**：跨账号的 voiceId 一律报 voice does not exist。今后凡换 key，
   必须用该 key 重跑 `scripts/re_enroll_pet_voices.py`。
2. parity 脚本必须放在容器内 `/work/scripts/ops/` 下跑（它用
   `Path(__file__).parents[3]` 推仓库根，丢到 `/tmp` 会把根推成 `/`）。
3. 探针里 `docker exec -e DSK="$DASHSCOPE_API_KEY"` 前必须先在远端
   `set -a; . /opt/lianyu/.env; set +a`，否则变量是空的（第一次就跑出全 204）。
4. `docker exec ... curl` 在 agent 容器里不可用（python:slim 无 curl），用 python 探针。

### 仍未做（不阻塞本次上线）

- 退役 Java 侧实时 WS 客户端（`DashScopeTtsRealtimeService` 等约 1,232 行），
  并把 `wss://` 加进 ratchet —— 否则「Java 零 AI 逻辑」的绿灯仍是假阴性。
- 按句流水线预取（目前是整轮回复一次性流式，首句之后没有并行预取下一句）。
- 老用户已注册的自定义音色绑在已下架的 `qwen3-tts-vc-*` 上，需要各自重新上传注册。
