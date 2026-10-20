#!/usr/bin/env python3
"""热修后观察：等 5 分钟，统计 agent 500 率（热修后窗口）+ 跑 parity（修正 golden 路径）。"""
import os
import sys
import time
from pathlib import Path

import paramiko

ROOT = Path(__file__).resolve().parents[1]


def load_dotenv(path: Path) -> None:
    if not path.is_file():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"'))


def run(c, cmd, timeout=600):
    print(f"$ {cmd}", flush=True)
    _, stdout, _ = c.exec_command(cmd, timeout=timeout, get_pty=True)
    out = stdout.read().decode("utf-8", errors="replace")
    sys.stdout.buffer.write(out[-3500:].encode("utf-8", errors="replace"))
    sys.stdout.buffer.write(b"\n")
    return out


def main() -> None:
    load_dotenv(ROOT / ".env")
    password = os.environ.get("DEPLOY_SSH_PASSWORD")
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect("156.233.228.18", username="root", password=password, timeout=30)

    print("waiting 240s for proactive scheduler cycles...", flush=True)
    time.sleep(240)

    print("===== agent 500 rate since hotfix (container start) =====", flush=True)
    started = run(c, "docker inspect lianyu-agent --format '{{.State.StartedAt}}'")
    run(c, "docker logs lianyu-agent 2>&1 | grep -c '500 Internal Server Error' || echo agent1_500_total=0")
    run(c, "docker logs lianyu-agent 2>&1 | grep -c '200 OK' || echo agent1_200_total=0")
    run(c, "docker logs lianyu-agent-2 2>&1 | grep -c '500 Internal Server Error' || echo agent2_500_total=0")

    # proactive 成功的证据：assistantRaw 有值（Java 端 proactiveTurnResult 不再 degraded）
    run(c, "docker logs lianyu-backend --since 6m 2>&1 | grep -c 'proactiveTurnResult failed' || echo backend_proactive_fail_6m=0")
    run(c, "docker logs lianyu-backend --since 6m 2>&1 | grep -c 'Agent proactive turn served' || echo backend_proactive_ok_6m=0")

    print("===== parity check（修正路径：脚本用 repo root 相对路径，容器内从 /work 跑）=====", flush=True)
    # 脚本里 ROOT = Path(__file__).parent.parent.parent → /work；golden 在 /work/backend-agent/app/prompts
    run(c, "docker exec lianyu-agent sh -c 'ls /work/backend-agent/app/prompts/end_to_end_golden.json' && "
           "docker exec -e LIANYU_OBS_AGENT_URL=http://agent-lb:8001 -w /work lianyu-agent "
           "python -X utf8 /tmp/parity.py 2>&1 | tail -10; echo PARITY_EXIT=$?")

    c.close()
    print("OBSERVE_DONE")


if __name__ == "__main__":
    main()
