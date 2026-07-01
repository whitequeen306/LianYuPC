#!/usr/bin/env python3
"""Quick cloud diagnostics: commit, CORS, auth endpoints."""
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


def run(client: paramiko.SSHClient, cmd: str) -> str:
    print(f"$ {cmd}", flush=True)
    _, stdout, stderr = client.exec_command(cmd, timeout=30)
    out = stdout.read().decode("utf-8", errors="replace").strip()
    err = stderr.read().decode("utf-8", errors="replace").strip()
    if out:
        print(out)
    if err:
        print(f"ERR: {err[-800:]}")
    print("---")
    return out


def main() -> None:
    load_dotenv(ROOT / ".env")
    password = os.environ.get("DEPLOY_SSH_PASSWORD")
    if not password:
        print("Set DEPLOY_SSH_PASSWORD in .env", file=sys.stderr)
        sys.exit(1)

    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, username=USER, password=password, timeout=30)

    run(client, "cd /opt/lianyu && git log -1 --oneline && git rev-parse HEAD")
    run(client, "curl -s -o /dev/null -w 'backend_captcha=%{http_code}\\n' http://127.0.0.1:8080/api/auth/captcha")
    run(client, "curl -sk -o /dev/null -w 'gw_captcha=%{http_code}\\n' https://127.0.0.1/api/auth/captcha")
    run(
        client,
        "curl -sk -D - -o /dev/null -H 'Origin: https://154.219.111.30' "
        "https://127.0.0.1/api/auth/captcha 2>/dev/null | grep -i access-control || true",
    )
    run(client, "curl -sk -o /dev/null -w 'chars_noauth=%{http_code}\\n' https://127.0.0.1/api/characters")
    run(
        client,
        "curl -sk -o /dev/null -w 'chars_header=%{http_code}\\n' "
        "-H 'lianyu-token: invalid' https://127.0.0.1/api/characters",
    )
    run(
        client,
        "curl -sk -o /dev/null -w 'chars_query=%{http_code}\\n' "
        "'https://127.0.0.1/api/characters?lianyu-token=invalid'",
    )
    run(
        client,
        "docker logs lianyu-backend --since 6h 2>&1 | "
        "grep -E 'ERROR|WARN|NotLogin|401|HeaderOnly|characters|conversations' | tail -30 || true",
    )
    client.close()


if __name__ == "__main__":
    main()
