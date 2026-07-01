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

run(c, "docker exec lianyu-backend unzip -l /app/app.jar | grep square-avatars | wc -l")
run(c, "docker exec lianyu-backend unzip -l /app/app.jar | grep zero_two")
run(c, "docker exec lianyu-backend unzip -l /app/app.jar | grep yuno")
run(c, "docker exec lianyu-backend unzip -l /app/app.jar | grep square-avatars | tail -10")

c.close()
