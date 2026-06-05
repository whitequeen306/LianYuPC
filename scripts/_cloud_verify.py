#!/usr/bin/env python3
import os
import sys
import time
from pathlib import Path

import paramiko

ROOT = Path(__file__).resolve().parents[1]


def load_dotenv(path: Path) -> None:
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        os.environ.setdefault(k.strip(), v.strip().strip('"'))


def main() -> None:
    load_dotenv(ROOT / ".env")
    password = os.environ.get("DEPLOY_SSH_PASSWORD")
    if not password:
        sys.exit("missing DEPLOY_SSH_PASSWORD")

    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect("154.219.111.30", username="root", password=password, timeout=30)

    for cmd in [
        "cd /opt/lianyu && docker compose ps",
        "sleep 25 && curl -s -o /dev/null -w 'captcha=%{http_code}\\n' http://127.0.0.1:8080/api/auth/captcha",
        "docker logs lianyu-backend --tail 40 2>&1",
    ]:
        print(">>>", cmd, flush=True)
        _, o, _ = c.exec_command(cmd, timeout=180, get_pty=True)
        text = o.read().decode("utf-8", errors="replace")
        sys.stdout.buffer.write(text.encode("utf-8", errors="replace"))
        sys.stdout.buffer.write(b"\n")

    c.close()


if __name__ == "__main__":
    main()
