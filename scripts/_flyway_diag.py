#!/usr/bin/env python3
import os
import sys
from pathlib import Path

import paramiko

HOST = "156.233.228.18"
USER = "root"


def main() -> None:
    password = os.environ.get("DEPLOY_SSH_PASSWORD")
    if not password:
        print("Set DEPLOY_SSH_PASSWORD", file=sys.stderr)
        sys.exit(1)
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, username=USER, password=password, timeout=30)
    cmd = (
        "cd /opt/lianyu && MYSQL_PWD=$(grep '^MYSQL_PASSWORD=' .env | cut -d= -f2-) && "
        "docker exec -e MYSQL_PWD lianyu-mysql mysql -ulianyu lianyu -e "
        "\"SELECT installed_rank,version,description,success,installed_on FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;\""
    )
    print(f"$ {cmd}")
    _, stdout, _ = client.exec_command(cmd, timeout=60)
    print(stdout.read().decode("utf-8", errors="replace"))
    cmd2 = (
        "docker logs lianyu-backend 2>&1 | grep -i -E 'V31|relationship|flyway|memory_type' | tail -30"
    )
    print(f"$ {cmd2}")
    _, stdout, _ = client.exec_command(cmd2, timeout=60)
    print(stdout.read().decode("utf-8", errors="replace"))
    client.close()


if __name__ == "__main__":
    main()
