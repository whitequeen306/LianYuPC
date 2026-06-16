#!/usr/bin/env python3
"""Pull latest main on cloud server and rebuild backend + api-gateway."""
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


def run(client: paramiko.SSHClient, cmd: str, timeout: int = 900) -> str:
    print(f"$ {cmd}", flush=True)
    _, stdout, stderr = client.exec_command(cmd, timeout=timeout, get_pty=True)
    out = stdout.read().decode("utf-8", errors="replace")
    err = stderr.read().decode("utf-8", errors="replace")
    code = stdout.channel.recv_exit_status()
    if out.strip():
        text = out[-5000:]
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

    run(client, "cd /opt/lianyu && git pull origin main")
    run(client, "cd /opt/lianyu && docker compose up -d --build backend api-gateway", timeout=1200)
    import time
    for attempt in range(12):
        _, stdout, _ = client.exec_command(
            "curl -s -o /dev/null -w 'captcha=%{http_code}' http://127.0.0.1:8080/api/auth/captcha",
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
    run(
        client,
        "docker exec lianyu-redis redis-cli --scan --pattern 'lianyu:user:output_lang:*' "
        "| xargs -r docker exec -i lianyu-redis redis-cli del",
    )
    run(client, "docker ps --format 'table {{.Names}}\\t{{.Status}}' | head -8")

    client.close()
    print("DEPLOY_DONE")


if __name__ == "__main__":
    main()
