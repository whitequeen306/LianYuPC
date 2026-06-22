#!/usr/bin/env python3
"""Repair failed Flyway V34: dedupe conversations, ensure index, clear failed history row."""
import os
import sys
import time
from pathlib import Path

import paramiko

HOST = "154.219.111.30"
USER = "root"
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

    mysql(client, "SELECT version,success,description FROM flyway_schema_history WHERE version='34';")
    mysql(
        client,
        "SELECT user_id, character_id, mode, COUNT(*) c FROM conversation "
        "WHERE mode='SINGLE' GROUP BY user_id, character_id, mode HAVING c>1 LIMIT 5;",
    )
    mysql(
        client,
        "DELETE c1 FROM conversation c1 "
        "INNER JOIN conversation c2 ON c1.user_id = c2.user_id "
        "AND c1.character_id = c2.character_id AND c1.mode = 'SINGLE' "
        "AND c2.mode = 'SINGLE' AND c1.id > c2.id;",
    )
    mysql(
        client,
        "SELECT user_id, character_id, mode, COUNT(*) c FROM conversation "
        "WHERE mode='SINGLE' GROUP BY user_id, character_id, mode HAVING c>1 LIMIT 5;",
    )
    # Drop partial index if present (ignore error via procedure-style check)
    mysql(
        client,
        "SET @idx := (SELECT COUNT(*) FROM information_schema.statistics "
        "WHERE table_schema=DATABASE() AND table_name='conversation' "
        "AND index_name='uk_conversation_user_character_mode'); "
        "SET @sql := IF(@idx>0, 'SELECT 1', "
        "'CREATE UNIQUE INDEX uk_conversation_user_character_mode "
        "ON conversation (user_id, character_id, mode)'); "
        "PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;",
    )
    mysql(client, "DELETE FROM flyway_schema_history WHERE version='34';")
    mysql(client, "SELECT version,success,description FROM flyway_schema_history WHERE version='34';")
    run(client, "cd /opt/lianyu && git pull origin main", timeout=120)
    run(client, "cd /opt/lianyu && docker compose up -d --build backend", timeout=1200)

    for attempt in range(16):
        _, stdout, _ = client.exec_command(
            "curl -s -o /dev/null -w 'captcha=%{http_code}' http://127.0.0.1:8080/api/auth/captcha",
            timeout=30,
        )
        out = stdout.read().decode("utf-8", errors="replace").strip()
        print(f"$ health attempt {attempt + 1}: {out}")
        if "200" in out:
            run(client, "docker ps --format 'table {{.Names}}\t{{.Status}}' | head -8")
            client.close()
            print("REPAIR_V34_DONE")
            return
        time.sleep(15)

    run(client, "docker logs lianyu-backend --tail 50 2>&1")
    client.close()
    sys.exit(56)


if __name__ == "__main__":
    main()
