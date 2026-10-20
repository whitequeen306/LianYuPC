#!/usr/bin/env python3
"""生产热修：只重建 agent 层（base_url 归一化），先 agent 后 agent2。"""
import os
import sys
import time
from pathlib import Path

import paramiko

ROOT = Path(__file__).resolve().parents[1]


def load_env() -> dict:
    env = {}
    for line in (ROOT / ".env").read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            env[k.strip()] = v.strip().strip('"')
    return env


def run(c, cmd, timeout=2400):
    print(f"$ {cmd}", flush=True)
    _, stdout, _ = c.exec_command(cmd, timeout=timeout, get_pty=True)
    out = stdout.read().decode("utf-8", errors="replace")
    sys.stdout.buffer.write(out[-3000:].encode("utf-8", errors="replace"))
    sys.stdout.buffer.write(b"\n")
    return stdout.channel.recv_exit_status(), out


def wait_healthy(c, name, timeout_s=180):
    for _ in range(timeout_s // 5):
        _, o, _ = c.exec_command(
            f"docker inspect {name} --format '{{{{.State.Health.Status}}}}'", timeout=30)
        status = o.read().decode().strip()
        if status == "healthy":
            return True
        time.sleep(5)
    return False


def main() -> None:
    env = load_env()
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect("156.233.228.18", username="root",
              password=env["DEPLOY_SSH_PASSWORD"], timeout=30)

    run(c, "cd /opt/lianyu && git pull origin main 2>&1 | tail -3")
    run(c, "cd /opt/lianyu && git log -1 --oneline")
    run(c, "cd /opt/lianyu && grep -c normalize_openai_base_url backend-agent/app/gateway.py")

    print("===== build agent image =====", flush=True)
    run(c, "cd /opt/lianyu && docker compose build agent agent2 2>&1 | tail -3")

    print("===== step 1: recreate agent =====", flush=True)
    run(c, "cd /opt/lianyu && docker compose up -d agent 2>&1 | tail -3")
    ok1 = wait_healthy(c, "lianyu-agent")
    print(f"agent healthy: {ok1}", flush=True)
    if not ok1:
        sys.exit(56)

    print("===== step 2: recreate agent2 =====", flush=True)
    run(c, "cd /opt/lianyu && docker compose up -d agent2 2>&1 | tail -3")
    ok2 = wait_healthy(c, "lianyu-agent-2")
    print(f"agent2 healthy: {ok2}", flush=True)
    if not ok2:
        sys.exit(57)

    run(c, "docker ps --filter name=lianyu-agent --format 'table {{.Names}}\\t{{.Status}}'")
    # 容器内代码核验
    for name in ("lianyu-agent", "lianyu-agent-2"):
        code, out = run(
            c,
            f"docker exec {name} grep -c normalize_openai_base_url /app/app/gateway.py")
        print(f"{name} normalize_present={out.strip()}", flush=True)

    c.close()
    print("AGENT_DEPLOY_DONE")


if __name__ == "__main__":
    main()
