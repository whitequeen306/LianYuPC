#!/usr/bin/env python3
import os
import sys
from pathlib import Path

import paramiko

HOST = "156.233.228.18"
USER = "root"
ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    password = os.environ.get("DEPLOY_SSH_PASSWORD")
    if not password:
        print("Set DEPLOY_SSH_PASSWORD", file=sys.stderr)
        sys.exit(1)
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, username=USER, password=password, timeout=30)
    for cmd in (
        "docker ps -a --format 'table {{.Names}}\t{{.Status}}' | head -10",
        "curl -s -o /dev/null -w 'captcha=%{http_code}' http://127.0.0.1:8080/api/auth/captcha",
        "docker logs lianyu-backend --tail 100 2>&1",
    ):
        print(f"$ {cmd}")
        _, stdout, stderr = client.exec_command(cmd, timeout=90)
        out = stdout.read().decode("utf-8", errors="replace")
        err = stderr.read().decode("utf-8", errors="replace")
        if out.strip():
            print(out.rstrip())
        if err.strip():
            print(err.rstrip(), file=sys.stderr)
    client.close()


if __name__ == "__main__":
    main()
