# daily parity 巡检（OFF 退役后的防漂移观测）

SHADOW 模式删除后，Java/Python 逐字节比对的运行时能力不复存在。
本巡检用「**冻结的 golden 基线**」替代它：期望值是退役前 Java 真实产出的
`end_to_end_golden.json`（committed，Python 不自己算），每天打一次线上，
prompt 组装只要漂一个字节就会失败。

## 跑什么

`scripts/ops/daily_parity_check.py`：

1. **golden 对齐**（zh + en）：调 `/agent/prompt/assemble`，注入 golden 的
   memory/relationship/session 块 + `[[TOOL_HINT]]` 占位，比对 `systemPrompt`
   与 Java 基线逐字节相等。
2. **agent 健康**：`GET /agent/health`。
3. **真实对话冒烟**（可选）：提供 `LIANYU_OBS_USER` / `LIANYU_OBS_PASSWORD`
   时走 Java 全链路发 1 条；缺凭据自动跳过，不判失败。

退出码：全过 0，任何失败 1（cron/CI 直接可用）。

## 怎么跑（本地）

前置（一次性）：`docker-compose.yml` 的 `agent-lb` 已加宿主机回环映射
`127.0.0.1:18001:8001`（仅内网可达，不暴露公网）。

```powershell
cd backend-agent
.\.venv\Scripts\python.exe -X utf8 ..\scripts\ops\daily_parity_check.py
```

报告落盘 `.tmp/parity-report.json`。

## 怎么跑（生产 cron）

```bash
# crontab -e（宿主机，每天 04:00）
0 4 * * * cd /opt/lianyu-pc/backend-agent && .venv/bin/python -X utf8 \
  ../scripts/ops/daily_parity_check.py >> /var/log/lianyu/parity.log 2>&1
```

生产需要 `.env` 里 `LIANYU_OBS_USER` / `LIANYU_OBS_PASSWORD`（巡检专用低权限账号，
不需要；不配则跳过冒烟只跑 golden 对齐）。

## 失败时怎么排查

1. `parity-report.json` 的 `firstDiff` 给出第一个不同字节的位置。
2. 常见原因：
   - Python 侧改了 prompt 组装但没跑 `backend-agent/tests` → 本地先 `pytest`
     （`test_prompt_assemble_endpoint.py` 会红）
   - LB 后面的某个 agent 实例是旧镜像 → `docker compose up -d agent agent2`
   - golden fixture 被手动改过 → `git diff backend-agent/app/prompts/end_to_end_golden.json`
3. **期望值只能来自 Java 产出的 committed fixture**；如果 prompt 有意变更，
   流程是：改 Python → pytest 绿 → 无需更新 golden（golden 是历史基线，
   改基线 = 放弃漂移检测，需要明确记录）。
