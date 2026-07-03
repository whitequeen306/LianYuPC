#!/usr/bin/env python3
import os
import sys
from pathlib import Path
import paramiko

ROOT = Path(__file__).resolve().parents[1]

def load_dotenv(path):
    if not path.is_file():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        os.environ.setdefault(k.strip(), v.strip().strip('"'))

def run(client, cmd):
    print(f"\n$ {cmd}")
    _, stdout, _ = client.exec_command(cmd, timeout=90, get_pty=True)
    print(stdout.read().decode("utf-8", errors="replace").strip())

load_dotenv(ROOT / ".env")
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("154.219.111.30", username="root", password=os.environ["DEPLOY_SSH_PASSWORD"], timeout=30)
run(c, "docker exec lianyu-minio sh -c 'find /data/lianyu/square-avatars -iname \"*zero*\" -o -iname \"*yuno*\" 2>/dev/null'")
run(c, "docker logs lianyu-backend 2>&1 | grep -i 'Square avatar sync failed' | tail -20")
run(c, "curl -s -o /dev/null -w 'zero_two=%{http_code}\\n' http://127.0.0.1:8080/api/public/files/square-avatars/zero_two.png")
run(c, "curl -s -o /dev/null -w 'yuno=%{http_code}\\n' http://127.0.0.1:8080/api/public/files/square-avatars/yuno.jpg")
c.close()
