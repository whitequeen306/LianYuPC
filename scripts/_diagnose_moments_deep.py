#!/usr/bin/env python3
"""Deep moments peer-comment diagnostics on cloud server."""
import os
import sys
from pathlib import Path

import paramiko

HOST = "156.233.228.18"
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


def mysql(client: paramiko.SSHClient, pw: str, sql: str) -> None:
    # Pass SQL via stdin to avoid shell/backtick escaping issues.
    cmd = (
        f'docker exec -i lianyu-mysql mysql -ulianyu -p"{pw}" lianyu -t -e '
        f'"$(cat <<\'EOSQL\'\n{sql}\nEOSQL\n)"'
    )
    # Simpler: base64 encode SQL
    import base64

    b64 = base64.b64encode(sql.encode("utf-8")).decode("ascii")
    cmd = (
        f"echo {b64} | base64 -d | "
        f'docker exec -i lianyu-mysql mysql -ulianyu -p"{pw}" lianyu -t'
    )
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
    pw = run(client, "docker exec lianyu-backend printenv MYSQL_PASSWORD").strip()

    print("\n=== stuck posts detail ===")
    mysql(
        client,
        pw,
        """
SELECT p.id, p.user_id, p.character_id, p.created_at,
       (SELECT COUNT(*) FROM `character` c WHERE c.owner_user_id = p.user_id) AS char_count,
       COALESCE(s.peer_round_done, 0) AS peer_done
FROM moments_post p
LEFT JOIN moments_interaction_state s ON s.post_id = p.id
WHERE p.author_type = 'CHARACTER'
  AND p.created_at < NOW() - INTERVAL 10 MINUTE
  AND NOT EXISTS (
    SELECT 1 FROM moments_comment mc
    WHERE mc.post_id = p.id
      AND mc.source_type = 'AUTO_PEER_COMMENT'
      AND mc.character_id IS NOT NULL
      AND mc.character_id <> p.character_id
  )
ORDER BY p.id;
""",
    )

    print("\n=== stuck due to single-character account ===")
    mysql(
        client,
        pw,
        """
SELECT COUNT(*) AS single_char_stuck
FROM moments_post p
WHERE p.author_type = 'CHARACTER'
  AND p.created_at < NOW() - INTERVAL 10 MINUTE
  AND (SELECT COUNT(*) FROM `character` c WHERE c.owner_user_id = p.user_id) < 2
  AND NOT EXISTS (
    SELECT 1 FROM moments_comment mc
    WHERE mc.post_id = p.id
      AND mc.source_type = 'AUTO_PEER_COMMENT'
      AND mc.character_id IS NOT NULL
      AND mc.character_id <> p.character_id
  );
""",
    )

    print("\n=== multi-char accounts still stuck (should be 0) ===")
    mysql(
        client,
        pw,
        """
SELECT p.id, p.user_id, p.character_id, p.created_at,
       (SELECT COUNT(*) FROM `character` c WHERE c.owner_user_id = p.user_id) AS char_count
FROM moments_post p
WHERE p.author_type = 'CHARACTER'
  AND p.created_at < NOW() - INTERVAL 10 MINUTE
  AND (SELECT COUNT(*) FROM `character` c WHERE c.owner_user_id = p.user_id) >= 2
  AND NOT EXISTS (
    SELECT 1 FROM moments_comment mc
    WHERE mc.post_id = p.id
      AND mc.source_type = 'AUTO_PEER_COMMENT'
      AND mc.character_id IS NOT NULL
      AND mc.character_id <> p.character_id
  )
ORDER BY p.id;
""",
    )

    print("\n=== peer comments missing author reply ===")
    mysql(
        client,
        pw,
        """
SELECT mc.id AS peer_comment_id, mc.post_id, p.character_id AS author_char_id,
       mc.created_at AS peer_at
FROM moments_comment mc
JOIN moments_post p ON p.id = mc.post_id
WHERE mc.source_type = 'AUTO_PEER_COMMENT'
  AND p.author_type = 'CHARACTER'
  AND mc.character_id <> p.character_id
  AND NOT EXISTS (
    SELECT 1 FROM moments_comment ar
    WHERE ar.source_type = 'AUTO_AUTHOR_REPLY'
      AND ar.parent_id = mc.id
  )
ORDER BY mc.id DESC
LIMIT 25;
""",
    )

    print("\n=== recent peer + author reply counts ===")
    mysql(
        client,
        pw,
        """
SELECT
  (SELECT COUNT(*) FROM moments_comment WHERE source_type='AUTO_PEER_COMMENT' AND created_at > NOW() - INTERVAL 1 HOUR) AS peer_1h,
  (SELECT COUNT(*) FROM moments_comment WHERE source_type='AUTO_AUTHOR_REPLY' AND created_at > NOW() - INTERVAL 1 HOUR) AS author_1h;
""",
    )

    client.close()


if __name__ == "__main__":
    main()
