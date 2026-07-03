#!/usr/bin/env python3
import os
import sys
from pathlib import Path

import paramiko

HOST = "154.219.111.30"
USER = "root"
ROOT = Path(__file__).resolve().parents[1]
SLUGS = ["kurumi", "kotori", "tohka", "origami", "yoshino", "mukuro", "izayoi", "nia"]


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
    print(f"$ {cmd}")
    _, stdout, stderr = client.exec_command(cmd, timeout=120)
    out = stdout.read().decode("utf-8", errors="replace")
    err = stderr.read().decode("utf-8", errors="replace")
    if out.strip():
        print(out.rstrip())
    if err.strip():
        print(err.rstrip(), file=sys.stderr)
    return out


def main() -> int:
    load_dotenv(ROOT / ".env")
    password = os.environ.get("DEPLOY_SSH_PASSWORD")
    if not password:
        print("Set DEPLOY_SSH_PASSWORD", file=sys.stderr)
        return 1

    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, username=USER, password=password, timeout=30)

    run(client, "docker exec lianyu-minio sh -c 'ls /data/lianyu/square-avatars/ 2>/dev/null' | head -20")
    run(client, "docker exec lianyu-minio sh -c 'ls /data/lianyu/square-avatars-thumb/ 2>/dev/null | wc -l'")
    for slug in SLUGS:
        run(
            client,
            f"docker exec lianyu-minio sh -c 'test -f /data/lianyu/square-avatars/{slug}.jpg && echo orig_{slug}=yes || echo orig_{slug}=no; test -f /data/lianyu/square-avatars-thumb/{slug}.jpg && echo thumb_{slug}=yes || echo thumb_{slug}=no'",
        )
        run(
            client,
            f"curl -k -s -o /dev/null -w 'gw_thumb_{slug}=%{{http_code}}\\n' https://127.0.0.1/api/public/files/square-avatars-thumb/{slug}.jpg",
        )
        run(
            client,
            f"curl -k -s -o /dev/null -w 'gw_orig_{slug}=%{{http_code}}\\n' https://127.0.0.1/api/public/files/square-avatars/{slug}.jpg",
        )

    run(client, "docker exec lianyu-minio sh -c 'ls /data/lianyu/square-avatars-thumb/ 2>/dev/null' | grep -E 'kurumi|kotori|tohka|origami|emilia' || true")
    run(client, "curl -k -s -o /dev/null -w 'emilia_thumb=%{http_code}\\n' https://127.0.0.1/api/public/files/square-avatars-thumb/emilia.jpg")
    run(client, "curl -k -s -I https://127.0.0.1/api/public/files/square-avatars-thumb/kurumi.jpg | head -8")

    run(client, "docker logs lianyu-backend 2>&1 | grep -E 'thumb backfill|Square avatar thumb' | tail -15")
    client.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
