#!/usr/bin/env python3
"""Verify character square rows in MySQL and avatar objects in MinIO data volume."""
import os
import sys
from pathlib import Path

import paramiko

HOST = "156.233.228.18"
USER = "root"
ROOT = Path(__file__).resolve().parents[1]
SLUGS = (
    "yu_nianan", "zhongli", "enoshima_junko", "kirigiri_kyoko",
    "nanami_chiaki", "fukawa_toko", "asahina_aoi",
)


def load_dotenv(path: Path) -> None:
    if not path.is_file():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"'))


def run(client: paramiko.SSHClient, cmd: str, timeout: int = 60) -> str:
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
    load_dotenv(ROOT / ".env")
    ssh_password = os.environ.get("DEPLOY_SSH_PASSWORD")
    if not ssh_password:
        print("Set DEPLOY_SSH_PASSWORD", file=sys.stderr)
        sys.exit(1)

    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, username=USER, password=ssh_password, timeout=30)

    run(client, "docker volume ls | grep -E 'mysql_data|minio_data'")

    in_list = ",".join(f"'{s}'" for s in SLUGS)
    sql = (
        "SELECT slug,name,IFNULL(avatar_url,'NULL') "
        f"FROM character_square_template WHERE slug IN ({in_list}) ORDER BY sort_order"
    )
    run(
        client,
        "cd /opt/lianyu && MYSQL_PWD=$(grep '^MYSQL_PASSWORD=' .env | cut -d= -f2-) && "
        f'docker exec -e MYSQL_PWD="$MYSQL_PWD" lianyu-mysql mysql -ulianyu lianyu -N -e "{sql}"',
    )

    slug_grep = "|".join(SLUGS)
    run(
        client,
        f"docker exec lianyu-minio sh -c 'ls /data/lianyu/square-avatars 2>/dev/null | grep -E \"^({slug_grep})\"'",
    )

    client.close()
    print("VERIFY_DONE")


if __name__ == "__main__":
    main()
