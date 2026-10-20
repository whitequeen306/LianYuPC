#!/usr/bin/env python3
"""只重建 backend（后台任务重试修复），并验证。"""
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


def run(c, cmd, timeout=1800):
    print(f"$ {cmd}", flush=True)
    _, stdout, _ = c.exec_command(cmd, timeout=timeout, get_pty=True)
    out = stdout.read().decode("utf-8", errors="replace")
    code = stdout.channel.recv_exit_status()
    sys.stdout.buffer.write(out[-3000:].encode("utf-8", errors="replace"))
    sys.stdout.buffer.write(b"\n")
    return code, out


def main() -> None:
    env = load_env()
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect("156.233.228.18", username="root",
              password=env["DEPLOY_SSH_PASSWORD"], timeout=30)

    run(c, "cd /opt/lianyu && git pull origin main 2>&1 | tail -2")
    run(c, "cd /opt/lianyu && git log -1 --oneline")
    run(c, "cd /opt/lianyu && docker compose up -d --build backend 2>&1 | tail -3", timeout=2400)

    for attempt in range(12):
        time.sleep(15)
        _, o, _ = c.exec_command(
            "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/api/auth/captcha",
            timeout=30)
        code = o.read().decode().strip()
        print(f"health attempt {attempt + 1}: {code}", flush=True)
        if code == "200":
            break
    else:
        sys.exit(56)

    run(c, "docker logs lianyu-backend 2>&1 | grep -E 'all ports wired|ports missing' | tail -2")
    run(c, "docker ps --filter name=lianyu-backend --format 'table {{.Names}}\\t{{.Status}}'")
    c.close()
    print("BACKEND_DEPLOY_DONE")


if __name__ == "__main__":
    main()
