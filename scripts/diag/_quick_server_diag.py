#!/usr/bin/env python3
import os
from pathlib import Path
import paramiko

ROOT = Path(__file__).resolve().parents[1]

def load_dotenv(path: Path) -> None:
    if not path.is_file():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        os.environ.setdefault(k.strip(), v.strip().strip('"'))

def run(client: paramiko.SSHClient, cmd: str) -> None:
    print(f"$ {cmd}")
    _, stdout, stderr = client.exec_command(cmd, timeout=120)
    out = stdout.read().decode("utf-8", errors="replace")
    err = stderr.read().decode("utf-8", errors="replace")
    if out.strip():
        print(out[-8000:])
    if err.strip():
        print(err[-2000:])

load_dotenv(ROOT / ".env")
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("154.219.111.30", username="root", password=os.environ["DEPLOY_SSH_PASSWORD"], timeout=30)
run(c, "docker ps -a --format 'table {{.Names}}\t{{.Status}}' | head -12")
run(c, "docker logs lianyu-backend --tail 100 2>&1")
run(c, "curl -s -o /dev/null -w 'backend=%{http_code}' http://127.0.0.1:8080/api/auth/captcha")
print()
run(c, "curl -sk -o /dev/null -w 'gateway=%{http_code}' https://127.0.0.1/api/auth/captcha")
print()
c.close()
