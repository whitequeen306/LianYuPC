#!/usr/bin/env python3
import os
import sys
from pathlib import Path

import paramiko

ROOT = Path(__file__).resolve().parents[1]
HOST = "154.219.111.30"


def load_dotenv(path: Path) -> None:
    if not path.is_file():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"'))


def run(client: paramiko.SSHClient, cmd: str) -> None:
    print(f"\n=== {cmd} ===", flush=True)
    _, stdout, stderr = client.exec_command(cmd, timeout=90)
    out = stdout.read().decode("utf-8", errors="replace")
    err = stderr.read().decode("utf-8", errors="replace")
    code = stdout.channel.recv_exit_status()
    text = (out or err)[-6000:]
    print(text)
    if code != 0:
        print(f"(exit {code})", flush=True)


def main() -> None:
    load_dotenv(ROOT / ".env")
    password = os.environ.get("DEPLOY_SSH_PASSWORD")
    if not password:
        print("Set DEPLOY_SSH_PASSWORD in .env", file=sys.stderr)
        sys.exit(1)

    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, username="root", password=password, timeout=30)

    run(client, "docker ps --format 'table {{.Names}}\t{{.Status}}' | head -15")
    run(client, "curl -s -o /dev/null -w 'captcha=%{http_code}\\n' http://127.0.0.1:8080/api/auth/captcha")
    run(client, "curl -s -o /dev/null -w 'gateway_captcha=%{http_code}\\n' http://127.0.0.1:80/api/auth/captcha || true")
    run(client, "docker logs lianyu-backend --tail 60 2>&1")
    run(client, "docker logs lianyu-api-gateway --tail 30 2>&1")
    run(client, "grep -E 'LIANYU_CLIENT_ATTEST|enforce' /opt/lianyu/.env 2>/dev/null || true")
    run(client, "docker logs lianyu-api-gateway 2>&1 | grep '117.159.17.215' | tail -50")
    run(client, "docker logs lianyu-api-gateway 2>&1 | grep '0.2.128' | tail -30")
    run(client, "docker logs lianyu-api-gateway 2>&1 | grep '0.2.128' | grep character | tail -20")
    run(client, "docker logs lianyu-api-gateway 2>&1 | grep '117.159.17.215' | grep -E 'captcha|square|character|login|register' | tail -40")
    run(client, "docker logs lianyu-api-gateway 2>&1 | grep '117.159.17.215' | tail -15")
    run(client, "curl -sk -o /dev/null -w 'https_captcha=%{http_code}\\n' https://154.219.111.30/api/auth/captcha")
    run(client, "echo | openssl s_client -connect 154.219.111.30:443 -servername 154.219.111.30 2>/dev/null | openssl x509 -noout -fingerprint -sha256")
    run(client, "docker logs lianyu-backend 2>&1 | grep -iE 'ERROR|Exception' | tail -30")

    client.close()


if __name__ == "__main__":
    main()
