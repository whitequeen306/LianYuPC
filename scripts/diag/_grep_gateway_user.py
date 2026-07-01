#!/usr/bin/env python3
"""Grep api-gateway access log for a client IP (recent window)."""
import os
import sys
from pathlib import Path

import paramiko

ROOT = Path(__file__).resolve().parents[1]
HOST = "154.219.111.30"
USER_IP = os.environ.get("LIANYU_USER_IP", "117.159.17.215")


def load_dotenv(path: Path) -> None:
    if not path.is_file():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"'))


def main() -> None:
    load_dotenv(ROOT / ".env")
    password = os.environ.get("DEPLOY_SSH_PASSWORD")
    if not password:
        print("Set DEPLOY_SSH_PASSWORD in .env", file=sys.stderr)
        sys.exit(1)

    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, username="root", password=password, timeout=30)

    cmds = [
        f"docker logs lianyu-api-gateway 2>&1 | grep '{USER_IP}' | grep '0.2.129' | tail -40",
        f"docker logs lianyu-api-gateway 2>&1 | grep '{USER_IP}' | grep '25/Jun' | tail -50",
        f"docker logs lianyu-api-gateway 2>&1 | grep '{USER_IP}' | grep -E 'square|character/square' | tail -20",
        f"docker logs lianyu-api-gateway 2>&1 | grep '{USER_IP}' | tail -20",
    ]
    for cmd in cmds:
        print(f"\n=== {cmd} ===", flush=True)
        _, stdout, stderr = client.exec_command(cmd, timeout=120)
        out = stdout.read().decode("utf-8", errors="replace")
        err = stderr.read().decode("utf-8", errors="replace")
        print((out or err or "(no matches)")[-8000:])

    client.close()


if __name__ == "__main__":
    main()
