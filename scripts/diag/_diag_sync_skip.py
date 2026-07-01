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
    _, stdout, _ = client.exec_command(cmd, timeout=60, get_pty=True)
    print(stdout.read().decode("utf-8", errors="replace").strip())

load_dotenv(ROOT / ".env")
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("154.219.111.30", username="root", password=os.environ["DEPLOY_SSH_PASSWORD"], timeout=30)

run(c, "docker logs lianyu-backend 2>&1 | grep 'Character square avatar sync finished' | tail -3")
run(c, "docker logs lianyu-backend 2>&1 | grep -E 'zero_two|yuno' | tail -10")
run(c, "docker logs lianyu-backend 2>&1 | tail -30")
# Remove bad filesystem copies so sync will upload properly
run(c, "docker exec lianyu-minio sh -c 'rm -f /data/lianyu/square-avatars/zero_two.png /data/lianyu/square-avatars/yuno.jpg; ls /data/lianyu/square-avatars/zero_two.png 2>&1 || true'")
run(c, "docker restart lianyu-backend")
c.close()
print("\nRestarted again after removing orphan files. Wait ~45s then re-check.")
