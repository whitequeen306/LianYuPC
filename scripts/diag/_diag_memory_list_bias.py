#!/usr/bin/env python3
"""Check if memory list API top-50 bias explains missing older rows."""
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


def run(client, cmd):
    _, stdout, _ = client.exec_command(cmd, timeout=60)
    return stdout.read().decode("utf-8", errors="replace").strip()


def main():
    load_dotenv(ROOT / ".env")
    pw = os.environ.get("DEPLOY_SSH_PASSWORD")
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, username="root", password=pw, timeout=30)
    root_pw = run(client, "grep MYSQL_ROOT_PASSWORD /opt/lianyu/.env | cut -d= -f2")
    mysql = f"docker exec lianyu-mysql mysql -uroot -p{root_pw} lianyu -e"

    uid = sys.argv[1] if len(sys.argv) > 1 else "24"
    cid = sys.argv[2] if len(sys.argv) > 2 else "16"

    sql = (
        f"SELECT DATE(created_at) d, COUNT(*) cnt FROM memory_meta "
        f"WHERE user_id={uid} AND character_id={cid} GROUP BY DATE(created_at) ORDER BY d DESC;"
    )
    print("=== per-day for user/char ===")
    print(run(client, f'{mysql} "{sql}" 2>/dev/null'))

    sql2 = (
        f"SELECT DATE(created_at) d, COUNT(*) cnt FROM ("
        f"SELECT created_at FROM memory_meta WHERE user_id={uid} "
        f"ORDER BY created_at DESC LIMIT 50) t GROUP BY DATE(created_at) ORDER BY d DESC;"
    )
    print("\n=== dates in API default top-50 (user-wide) ===")
    print(run(client, f'{mysql} "{sql2}" 2>/dev/null'))

    sql3 = (
        f"SELECT DATE(created_at) d, COUNT(*) cnt FROM ("
        f"SELECT created_at FROM memory_meta WHERE user_id={uid} AND character_id={cid} "
        f"ORDER BY created_at DESC LIMIT 50) t GROUP BY DATE(created_at) ORDER BY d DESC;"
    )
    print("\n=== dates in top-50 for one character ===")
    print(run(client, f'{mysql} "{sql3}" 2>/dev/null'))

    client.close()


if __name__ == "__main__":
    main()
