#!/usr/bin/env python3
"""Remove failed Flyway V31 row so fixed migration can re-run."""
import os
import sys
import time

import paramiko

HOST = "154.219.111.30"
USER = "root"


def run(client, cmd, timeout=120):
    print(f"$ {cmd}")
    _, stdout, stderr = client.exec_command(cmd, timeout=timeout)
    out = stdout.read().decode("utf-8", errors="replace")
    err = stderr.read().decode("utf-8", errors="replace")
    if out.strip():
        print(out.rstrip())
    if err.strip():
        print(err.rstrip(), file=sys.stderr)
    return out


def main() -> None:
    password = os.environ.get("DEPLOY_SSH_PASSWORD")
    if not password:
        print("Set DEPLOY_SSH_PASSWORD", file=sys.stderr)
        sys.exit(1)

    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, username=USER, password=password, timeout=30)

    run(
        client,
        "cd /opt/lianyu && git pull origin main",
    )
    mysql_sh = (
        "cd /opt/lianyu && "
        "MYSQL_PWD=$(grep '^MYSQL_PASSWORD=' .env | cut -d= -f2-) && "
        'docker exec -e MYSQL_PWD="$MYSQL_PWD" lianyu-mysql mysql -ulianyu lianyu -N -e '
    )
    run(client, mysql_sh + '"SELECT version,success,description FROM flyway_schema_history WHERE version=\'31\';"')
    run(client, mysql_sh + '"DELETE FROM flyway_schema_history WHERE version=\'31\';"')
    run(client, mysql_sh + '"SELECT version,success,description FROM flyway_schema_history WHERE version=\'31\';"')
    run(client, "cd /opt/lianyu && docker compose up -d --build backend api-gateway", timeout=1200)

    for attempt in range(12):
        _, stdout, _ = client.exec_command(
            "curl -s -o /dev/null -w 'captcha=%{http_code}' http://127.0.0.1:8080/api/auth/captcha",
            timeout=30,
        )
        out = stdout.read().decode("utf-8", errors="replace").strip()
        print(f"$ health attempt {attempt + 1}: {out}")
        if "200" in out:
            run(client, "docker ps --format 'table {{.Names}}\t{{.Status}}' | head -8")
            client.close()
            print("REPAIR_DEPLOY_DONE")
            return
        time.sleep(15)

    run(client, "docker logs lianyu-backend --tail 40 2>&1")
    client.close()
    sys.exit(56)


if __name__ == "__main__":
    main()
