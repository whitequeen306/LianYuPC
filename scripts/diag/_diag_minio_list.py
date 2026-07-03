#!/usr/bin/env python3
import os
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
    _, stdout, _ = client.exec_command(cmd, timeout=120, get_pty=True)
    print(stdout.read().decode("utf-8", errors="replace").strip())

load_dotenv(ROOT / ".env")
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("154.219.111.30", username="root", password=os.environ["DEPLOY_SSH_PASSWORD"], timeout=30)

run(c, "docker inspect lianyu-backend --format '{{.Config.Image}}'")
run(c, "docker exec lianyu-backend ls -la /app/ 2>/dev/null; docker exec lianyu-backend ls -la / 2>/dev/null | head -20")
run(c, """docker exec lianyu-backend sh -c 'find / -path "*square-avatars/zero_two*" 2>/dev/null; find / -path "*square-avatars/yuno*" 2>/dev/null'""")
run(c, """docker exec lianyu-minio sh -c 'ls /data/lianyu/square-avatars/ | sort'""")
run(c, """docker exec lianyu-minio sh -c 'ls /data/lianyu/square-avatars/ | grep -E "zero|yuno" || echo NO_MATCH'""")

c.close()
