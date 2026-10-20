#!/usr/bin/env python3
"""生产热修：拉取 proactive 修复 → 只重建 agent/agent2 → 验证 500 消失 + parity。"""
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


def run(c, cmd, timeout=1800):
    print(f"$ {cmd}", flush=True)
    _, stdout, _ = c.exec_command(cmd, timeout=timeout, get_pty=True)
    out = stdout.read().decode("utf-8", errors="replace")
    code = stdout.channel.recv_exit_status()
    sys.stdout.buffer.write(out[-4000:].encode("utf-8", errors="replace"))
    sys.stdout.buffer.write(b"\n")
    return code, out


def main() -> None:
    load_dotenv(ROOT / ".env")
    password = os.environ.get("DEPLOY_SSH_PASSWORD")
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect("156.233.228.18", username="root", password=password, timeout=30)

    run(c, "cd /opt/lianyu && git pull origin main 2>&1 | tail -2")
    run(c, "cd /opt/lianyu && git log -1 --oneline")

    print("===== rebuild agent layer only =====", flush=True)
    run(c, "cd /opt/lianyu && docker compose build agent agent2 2>&1 | tail -2", timeout=2400)
    run(c, "cd /opt/lianyu && docker compose up -d agent agent2", timeout=600)
    run(c, "sleep 12 && docker ps --filter name=lianyu-agent --format 'table {{.Names}}\\t{{.Status}}'")

    print("===== wait + observe proactive 500 rate =====", flush=True)
    time.sleep(90)
    run(c, "docker logs lianyu-agent --since 2m 2>&1 | grep -c '500 Internal Server Error' || echo 'agent1_500=0'")
    run(c, "docker logs lianyu-agent-2 --since 2m 2>&1 | grep -c '500 Internal Server Error' || echo 'agent2_500=0'")
    run(c, "docker logs lianyu-agent --since 2m 2>&1 | grep -c '200 OK' || echo agent1_200=0")

    print("===== parity check =====", flush=True)
    run(c, "docker cp /tmp/daily_parity_check.py lianyu-agent:/tmp/parity.py 2>/dev/null || "
           "true; docker exec lianyu-agent sh -c 'test -f /tmp/parity.py' && echo SCRIPT_PRESENT || echo SCRIPT_MISSING")
    code, out = run(c, "docker exec lianyu-agent sh -c 'test -f /work/backend-agent/app/prompts/end_to_end_golden.json && echo GOLDEN_PRESENT || echo GOLDEN_MISSING'")
    if "GOLDEN_MISSING" in out:
        run(c, "docker cp /opt/lianyu/backend-agent/app/prompts lianyu-agent:/prompts-tmp && "
               "docker exec lianyu-agent sh -c 'rm -rf /work/backend-agent/app/prompts && "
               "mkdir -p /work/backend-agent/app && cp -r /prompts-tmp /work/backend-agent/app/prompts'")
    run(c, "docker exec -e LIANYU_OBS_AGENT_URL=http://agent-lb:8001 -w /work lianyu-agent "
           "python -X utf8 /tmp/parity.py; echo PARITY_EXIT=$?")

    print("===== error log final snapshot =====", flush=True)
    run(c, "docker logs lianyu-backend 2>&1 | grep -cE 'JAVA_AI_PATH' || echo 'JAVA_AI_PATH: 0'")
    run(c, "docker logs lianyu-backend --since 5m 2>&1 | grep -E 'ERROR|Exception' | grep -v NoResourceFound | tail -3 || echo NO_NEW_ERRORS")

    c.close()
    print("HOTFIX_DONE")


if __name__ == "__main__":
    main()
