# backend-agent — 予念 Agent 运行时（FastAPI + LangGraph）

Strangler 迁移阶段 1 的 Python 侧。决策依据见 [`../docs/refactor-2026-10/`](../docs/refactor-2026-10/rewrite-options.md)。

| 项 | 说明 |
|----|------|
| 技术栈 | Python 3.12 + FastAPI + LangGraph + pydantic-settings |
| 端口 | `8001`（仅内网，经 api-gateway 分流 `/api/agent/**`） |
| 包管理 | uv（`uv sync` / `uv run pytest` / `uv run uvicorn app.main:app`） |

## 目录

| 路径 | 职责 |
|------|------|
| `app/config.py` | pydantic-settings 绑定全部环境变量（对齐 AGENTS 红线：禁止散落 @Value 式配置） |
| `app/http_client.py` | 出站 HTTP 工厂——**显式超时红线**（connect 15s / read 60s / write 30s） |
| `app/gateway.py` | **ModelGateway 唯一出口**：所有 OpenAI-compatible 调用走这里（审计 B1 的解药） |
| `app/vision.py` | 视觉两段式 Stage 1：VL 结构化 JSON（prompt 逐字移植 Java） |
| `app/desktop_observe.py` | 桌面感知链路（第一条迁移链路） |
| `app/main.py` | FastAPI 路由：`POST /agent/desktop-observe`、`GET /agent/health` |
| `tests/` | 行为保真测试：VisionAnalysisParser 断言移植 + prompt 逐字对照 |

## 行为保真原则

- Java 侧 prompt 措辞**逐字移植**，改一个字都要先在测试里对照（`test_desktop_greeting_prompt.py`）
- 失败语义对齐 Java：各阶段失败静默降级返回 `null`（→ Python `None`）
- 出站超时对齐 Java `SsrfPinningClientFactory`（2026-08-07 全站 park 事故红线）

## 常用命令

```powershell
cd backend-agent
uv sync                # 安装依赖
uv run pytest          # 测试
uv run uvicorn app.main:app --host 0.0.0.0 --port 8001
```

## 迁移状态

- [x] 骨架 + 桌面感知链路（Stage 1 / Stage 2，契约对齐 ObserveController）
- [ ] Java 适配层（vault 解析后转发，X-User-Id 头）
- [ ] nginx 分流 `/api/agent/**`
- [ ] 影子流量对比
- [ ] LangGraph 状态图接入（单聊流式）
