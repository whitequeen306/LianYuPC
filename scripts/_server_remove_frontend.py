#!/usr/bin/env python3
"""Remove legacy frontend container/images and reclaim Docker build cache on cloud server."""
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


def run(client: paramiko.SSHClient, cmd: str, timeout: int = 900) -> str:
    print(f"$ {cmd}", flush=True)
    _, stdout, stderr = client.exec_command(cmd, timeout=timeout, get_pty=True)
    out = stdout.read().decode("utf-8", errors="replace")
    err = stderr.read().decode("utf-8", errors="replace")
    code = stdout.channel.recv_exit_status()
    if out.strip():
        text = out[-8000:]
        sys.stdout.buffer.write(text.encode("utf-8", errors="replace"))
        sys.stdout.buffer.write(b"\n")
    if code != 0:
        if err.strip():
            sys.stderr.buffer.write(err[-2000:].encode("utf-8", errors="replace"))
            sys.stderr.buffer.write(b"\n")
        raise SystemExit(code)
    return out


def main() -> None:
    load_dotenv(ROOT / ".env")
    password = os.environ.get("DEPLOY_SSH_PASSWORD")
    if not password:
        print("Set DEPLOY_SSH_PASSWORD in .env or environment", file=sys.stderr)
        sys.exit(1)

    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, username=USER, password=password, timeout=30)

    print("=== BEFORE ===")
    run(client, "df -h /")
    run(client, "docker system df")
    run(client, "docker ps -a --format 'table {{.Names}}\t{{.Status}}\t{{.Image}}'")

    run(client, "cd /opt/lianyu && git pull origin main")
    run(client, "cd /opt/lianyu && docker compose stop frontend 2>/dev/null || true")
    run(client, "cd /opt/lianyu && docker compose rm -f frontend 2>/dev/null || true")
    run(client, "docker rm -f lianyu-frontend 2>/dev/null || true")
    run(client, "docker rmi $(docker images 'lianyu-pc/frontend' -q) 2>/dev/null || true")
    run(
        client,
        "rm -rf /opt/lianyu/frontend/node_modules /opt/lianyu/frontend/dist "
        "/opt/lianyu/frontend/release /opt/lianyu/frontend/dist-electron "
        "/opt/lianyu/.deploy-export /opt/lianyu/.deploy-import "
        "/opt/lianyu/backend/lianyu-*/target 2>/dev/null || true",
    )
    run(client, "docker builder prune -af", timeout=600)
    run(client, "docker image prune -af", timeout=300)
    run(client, "cd /opt/lianyu && docker compose up -d --build backend api-gateway", timeout=1200)

    for attempt in range(12):
        _, stdout, _ = client.exec_command(
            "curl -sk -o /dev/null -w 'gateway=%{http_code}' https://127.0.0.1/api/auth/captcha",
            timeout=30,
        )
        out = stdout.read().decode("utf-8", errors="replace").strip()
        print(f"$ health attempt {attempt + 1}: {out}")
        if "200" in out:
            break
        if attempt < 11:
            time.sleep(15)
    else:
        raise SystemExit(56)

    print("\n=== AFTER ===")
    run(client, "df -h /")
    run(client, "docker system df")
    run(client, "docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Image}}' | head -10")
    run(client, "du -h --max-depth=1 /opt/lianyu 2>/dev/null | sort -hr | head -12")

    client.close()
    print("REMOVE_FRONTEND_DONE")


if __name__ == "__main__":
    main()
