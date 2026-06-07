#!/usr/bin/env python3
"""Diagnose moments peer comments and web push on cloud server."""
import os
import sys
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
        print(out)
    if err.strip():
        print(err, file=sys.stderr)
    return out


def mysql_query(client: paramiko.SSHClient, pw: str, sql: str) -> None:
    escaped = sql.replace("\\", "\\\\").replace('"', '\\"')
    cmd = f'docker exec lianyu-mysql mysql -ulianyu -p"{pw}" lianyu -e "{escaped}"'
    run(client, cmd)


def main() -> None:
    load_dotenv(ROOT / ".env")
    password = os.environ.get("DEPLOY_SSH_PASSWORD")
    if not password:
        print("Set DEPLOY_SSH_PASSWORD in .env", file=sys.stderr)
        sys.exit(1)

    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, username=USER, password=password, timeout=30)

    run(client, "cd /opt/lianyu && git log -1 --oneline")
    run(client, "docker exec lianyu-backend printenv LIANYU_PUSH_ENABLED")
    run(client, "docker exec lianyu-backend sh -c 'printenv | grep VAPID | sed \"s/=.*/=***/\"'")

    pw = run(client, "docker exec lianyu-backend printenv MYSQL_PASSWORD").strip()

    print("\n=== recent character posts ===")
    mysql_query(
        client,
        pw,
        "SELECT p.id, p.character_id, c.name, p.created_at, "
        "(SELECT COUNT(*) FROM moments_comment mc WHERE mc.post_id=p.id "
        "AND mc.source_type='AUTO_PEER_COMMENT' AND mc.character_id IS NOT NULL "
        "AND mc.character_id <> p.character_id) AS peer_comments, "
        "COALESCE(s.peer_round_done,0) AS peer_done "
        "FROM moments_post p LEFT JOIN \\`character\\` c ON c.id=p.character_id "
        "LEFT JOIN moments_interaction_state s ON s.post_id=p.id "
        "WHERE p.author_type='CHARACTER' ORDER BY p.id DESC LIMIT 15;",
    )

    print("\n=== character posts older than 10m with zero peer comments ===")
    mysql_query(
        client,
        pw,
        "SELECT COUNT(*) AS stuck FROM moments_post p WHERE p.author_type='CHARACTER' "
        "AND p.created_at < NOW() - INTERVAL 10 MINUTE AND NOT EXISTS ("
        "SELECT 1 FROM moments_comment mc WHERE mc.post_id=p.id "
        "AND mc.source_type='AUTO_PEER_COMMENT' AND mc.character_id IS NOT NULL "
        "AND mc.character_id <> p.character_id);",
    )

    print("\n=== users with fewer than 2 characters ===")
    mysql_query(
        client,
        pw,
        "SELECT u.id AS user_id, COUNT(c.id) AS char_count FROM user u "
        "LEFT JOIN \\`character\\` c ON c.owner_user_id=u.id "
        "GROUP BY u.id HAVING char_count < 2 LIMIT 10;",
    )

    print("\n=== enabled web push subscriptions ===")
    mysql_query(client, pw, "SELECT COUNT(*) AS enabled_push_subs FROM web_push_subscription WHERE enabled=1;")

    print("\n=== backend logs (moments/push) ===")
    run(
        client,
        "docker logs lianyu-backend --since 12h 2>&1 | grep -E 'Moments peer|Moments author|web push|comment AI failed' | tail -50",
    )

    client.close()


if __name__ == "__main__":
    main()
