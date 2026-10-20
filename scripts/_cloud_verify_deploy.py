#!/usr/bin/env python3
"""部署后验证：parity 巡检 + 3 条真实生产对话。"""
import json
import os
import subprocess
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

    print("===== 1. parity check (on server) =====", flush=True)
    # 服务器上还没有 backend-agent/.venv，用 python3 + httpx？先查依赖
    code, out = run(c, "cd /opt/lianyu/backend-agent && ls .venv/bin/python 2>/dev/null || echo NO_VENV")

    if "NO_VENV" in out:
        # 服务器没 venv：容器内跑（容器里有 python+httpx，把 golden 也拷进去）
        sftp = c.open_sftp()
        sftp.put(str(ROOT / "scripts" / "ops" / "daily_parity_check.py"),
                 "/tmp/daily_parity_check.py")
        sftp.close()
        run(c, "docker cp /tmp/daily_parity_check.py lianyu-agent:/tmp/parity.py && "
               "docker exec lianyu-agent mkdir -p /work && "
               "docker cp /opt/lianyu/backend-agent/app/prompts lianyu-agent:/prompts-tmp && "
               "docker exec lianyu-agent sh -c 'mkdir -p /work/backend-agent/app && "
               "cp -r /prompts-tmp /work/backend-agent/app/prompts' && "
               "docker exec -e LIANYU_OBS_AGENT_URL=http://agent-lb:8001 -w /work lianyu-agent "
               "python -X utf8 /tmp/parity.py; echo PARITY_EXIT=$?")
    else:
        run(c, "cd /opt/lianyu && LIANYU_OBS_AGENT_URL=http://127.0.0.1:18001 "
               ".venv/bin/python -X utf8 scripts/ops/daily_parity_check.py; echo PARITY_EXIT=$?")

    print("\n===== 2. real conversations x3 (direct to agent, real key) =====", flush=True)
    run(c, "cd /opt/lianyu && set -a && . ./.env && set +a && "
           "for i in 1 2 3; do "
           "curl -s -X POST http://127.0.0.1:18001/agent/text/chat "
           "-H 'Content-Type: application/json' -H 'X-User-Id: 1' "
           "-d '{\"userId\":1,\"baseUrl\":\"https://dashscope.aliyuncs.com/compatible-mode/v1\","
           "\"apiKey\":\"'\"$DASHSCOPE_API_KEY'\"\",\"model\":\"qwen-plus\","
           "\"messages\":[{\"role\":\"user\",\"content\":\"确认在线，报个数：'$i'\"}],"
           "\"maxTokens\":60}' | head -c 300; echo; done", timeout=300)

    print("\n===== 3. error log snapshot =====", flush=True)
    run(c, "docker logs lianyu-backend 2>&1 | grep -cE 'JAVA_AI_PATH' || echo JAVA_AI_PATH_COUNT=0")
    run(c, "docker logs lianyu-backend 2>&1 | grep -E 'ERROR|Exception' | grep -v NoResourceFound | tail -5 || echo NO_ERRORS")

    c.close()
    print("VERIFY_DONE")


if __name__ == "__main__":
    main()
