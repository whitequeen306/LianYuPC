#!/usr/bin/env python3
"""Mark Flyway V30 placeholder as applied on cloud DB (version gap repair)."""
import os
import sys
import time
from pathlib import Path

import paramiko

HOST = "154.219.111.30"
USER = "root"
ROOT = Path(__file__).resolve().parents[1]

# CRC32 (signed int32) for V30__version_gap_placeholder.sql
V30_CHECKSUM = 1177663107


def load_dotenv(path: Path) -> None:
    if not path.is_file():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"'))


def run(client: paramiko.SSHClient, cmd: str, timeout: int = 120) -> str:
    print(f"$ {cmd}", flush=True)
    _, stdout, stderr = client.exec_command(cmd, timeout=timeout, get_pty=True)
    out = stdout.read().decode("utf-8", errors="replace")
    err = stderr.read().decode("utf-8", errors="replace")
    if out.strip():
        sys.stdout.buffer.write(out.encode("utf-8", errors="replace"))
        sys.stdout.buffer.write(b"\n")
    if err.strip():
        sys.stderr.buffer.write(err[-2000:].encode("utf-8", errors="replace"))
        sys.stderr.buffer.write(b"\n")
    code = stdout.channel.recv_exit_status()
    if code != 0:
        raise SystemExit(code)
    return out


def mysql(client: paramiko.SSHClient, sql: str) -> None:
    escaped = sql.replace('"', '\\"')
    run(
        client,
        "cd /opt/lianyu && "
        "MYSQL_PWD=$(grep '^MYSQL_PASSWORD=' .env | cut -d= -f2-) && "
        f'docker exec -e MYSQL_PWD="$MYSQL_PWD" lianyu-mysql mysql -ulianyu lianyu -e "{escaped}"',
    )


def main() -> None:
    load_dotenv(ROOT / ".env")
    password = os.environ.get("DEPLOY_SSH_PASSWORD")
    if not password:
        print("Set DEPLOY_SSH_PASSWORD in .env", file=sys.stderr)
        sys.exit(1)

    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, username=USER, password=password, timeout=30)

    mysql(
        client,
        "SELECT installed_rank, version, success, checksum FROM flyway_schema_history "
        "WHERE version IN ('29','30','31') ORDER BY installed_rank;",
    )

    run(client, "cd /opt/lianyu && docker compose stop backend", timeout=120)

    mysql(
        client,
        "SET @r29 := (SELECT installed_rank FROM flyway_schema_history WHERE version = '29'); "
        "UPDATE flyway_schema_history SET installed_rank = installed_rank + 1 "
        "WHERE installed_rank > @r29; "
        "INSERT INTO flyway_schema_history "
        "(installed_rank, version, description, type, script, checksum, installed_by, "
        "installed_on, execution_time, success) "
        "SELECT @r29 + 1, '30', 'version gap placeholder', 'SQL', "
        "'V30__version_gap_placeholder.sql', "
        f"{V30_CHECKSUM}, 'repair-v30', NOW(), 0, 1 "
        "FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM flyway_schema_history WHERE version = '30');",
    )

    mysql(
        client,
        "SELECT installed_rank, version, success, checksum FROM flyway_schema_history "
        "WHERE version IN ('29','30','31','34') ORDER BY installed_rank;",
    )

    run(client, "cd /opt/lianyu && docker compose start backend", timeout=120)

    for attempt in range(24):
        _, stdout, _ = client.exec_command(
            "curl -s -o /dev/null -w 'captcha=%{http_code}' http://127.0.0.1:8080/api/auth/captcha",
            timeout=30,
        )
        out = stdout.read().decode("utf-8", errors="replace").strip()
        print(f"$ health attempt {attempt + 1}: {out}")
        if "200" in out:
            client.close()
            print("REPAIR_V30_DONE")
            return
        time.sleep(10)

    run(client, "docker logs lianyu-backend --tail 80 2>&1")
    client.close()
    sys.exit(56)


if __name__ == "__main__":
    main()
