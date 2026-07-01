#!/usr/bin/env python3
"""Inspect memory_meta distribution on cloud server."""
import os
import sys
from pathlib import Path

import paramiko

HOST = "154.219.111.30"
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


def run(client: paramiko.SSHClient, cmd: str) -> str:
    _, stdout, stderr = client.exec_command(cmd, timeout=60)
    out = stdout.read().decode("utf-8", errors="replace").strip()
    err = stderr.read().decode("utf-8", errors="replace").strip()
    return out or err


def main() -> None:
    load_dotenv(ROOT / ".env")
    pw = os.environ.get("DEPLOY_SSH_PASSWORD")
    if not pw:
        print("missing DEPLOY_SSH_PASSWORD", file=sys.stderr)
        sys.exit(1)

    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, username="root", password=pw, timeout=30)

    root_pw = run(client, "grep MYSQL_ROOT_PASSWORD /opt/lianyu/.env | cut -d= -f2")
    mysql = f"docker exec lianyu-mysql mysql -uroot -p{root_pw} lianyu -e"

    queries = {
        "by_date": (
            "SELECT DATE(created_at) d, COUNT(*) cnt FROM memory_meta "
            "GROUP BY DATE(created_at) ORDER BY d DESC LIMIT 15;"
        ),
        "totals": (
            "SELECT COUNT(*) total, MIN(created_at) oldest, MAX(created_at) newest FROM memory_meta;"
        ),
        "by_user_char": (
            "SELECT user_id, character_id, COUNT(*) cnt, MIN(DATE(created_at)) first_day, "
            "MAX(DATE(created_at)) last_day FROM memory_meta "
            "GROUP BY user_id, character_id ORDER BY cnt DESC LIMIT 12;"
        ),
    }

    for name, sql in queries.items():
        print(f"=== {name} ===")
        print(run(client, f'{mysql} "{sql}" 2>/dev/null'))
        print()

    client.close()


if __name__ == "__main__":
    main()
