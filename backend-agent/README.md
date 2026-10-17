# backend-agent — 予念 Agent 运行时（FastAPI + LangGraph）

Strangler 迁移的 Python 侧。**决策与路线见 [`../docs/refactor-2026-10/refactor-plan.md`](../docs/refactor-2026-10/refactor-plan.md)**（总纲），
审计证据见 [`../docs/refactor-2026-10/audit-findings.md`](../docs/refactor-2026-10/audit-findings.md)。

| 项 | 说明 |
|----|------|
| 技术栈 | Python 3.12 + FastAPI + LangGraph + pydantic-settings |
| 端口 | `8001`（仅内网，经 api-gateway 分流 `/api/agent/**`） |
| 包管理 | uv（`uv sync` / `uv run pytest` / `uv run uvicorn app.main:app`） |

## 目录

| 路径 | 职责 |
|------|------|
| `app/config.py` | pydantic-settings 绑定全部环境变量（禁止散落式配置读取） |
| `app/http_client.py` | 出站 HTTP 工厂——**显式超时红线**（connect 15s / read 60s / write 30s） |
| `app/gateway.py` | **ModelGateway 唯一出口**：所有 OpenAI-compatible 调用走这里（台账 B1 的解药） |
| `app/vision.py` | 视觉两段式 Stage 1：VL 结构化 JSON（prompt 逐字移植 Java） |
| `app/desktop_observe.py` | 桌面感知链路 |
| `app/graphs/proactive_turn.py` | LangGraph 主动发言子图（条件边 + 有界递归） |
| `app/prompts/` | 场景任务块资源（8 个，逐字移植 Java 内联字面量）+ fail-fast loader |
| `app/main.py` | FastAPI 路由：`/agent/desktop-observe`、`/agent/proactive-turn`、`/agent/health` |
| `tests/` | 行为保真测试：parser 断言移植、prompt 逐字对照、图行为、出站超时红线 |

## 行为保真原则

- Java 侧 prompt 措辞**逐字移植**，改一个字都要先在测试里对照（`tests/test_prompt_snapshot.py`）
- 失败语义对齐 Java：各阶段失败静默降级返回 `None` / 空串
- 出站超时对齐 Java `SsrfPinningClientFactory`（2026-08-07 全站 park 事故红线）

## 常用命令

```powershell
cd backend-agent
uv sync                # 安装依赖
uv run pytest          # 测试
uv run uvicorn app.main:app --host 0.0.0.0 --port 8001
```

## 迁移状态

对照总纲 §6 路线图：

- [x] 骨架 + 桌面感知链路（契约对齐 `ObserveController`）
- [x] Java 适配层（三模式编排 `OFF/SHADOW/AGENT`，vault 解析后转发，`X-User-Id`）
- [x] nginx 分流 `/api/agent/**`（已修 `rewrite` 去 `/api` 前缀）
- [x] LangGraph 主动发言子图 + 4 条链路接入（定时主动 / 冷启动 / 破冰跟进 / 城市变更）
- [ ] 网关对等层：`resilience.py`（熔断/隔舱/瞬断重试）+ `language_gate.py`（总纲 P1）
- [ ] 工具循环 + 有界 `recursion_limit`（总纲 P2）
- [ ] SHADOW 观察 → AGENT 切流（总纲 P3）
- [ ] 单聊流式 `SingleTurn` 图（总纲 P4）

> **前置约束（总纲 D6）**：在 P1/P2 补齐前，不把需要工具或语言门的链路
> （朋友圈 / 日记）接到 Python，否则 `AGENT` 模式会相对 Java 路径降级。
