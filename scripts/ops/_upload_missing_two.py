#!/usr/bin/env python3
"""Extract zero_two/yuno from app.jar and upload to MinIO via mc or curl."""
import os
import time
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

def run(client, cmd, timeout=120):
    print(f"\n$ {cmd}")
    _, stdout, stderr = client.exec_command(cmd, timeout=timeout, get_pty=True)
    out = stdout.read().decode("utf-8", errors="replace").strip()
    err = stderr.read().decode("utf-8", errors="replace").strip()
    if out:
        print(out)
    if err:
        print(f"[stderr] {err}")
    return out

load_dotenv(ROOT / ".env")
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("154.219.111.30", username="root", password=os.environ["DEPLOY_SSH_PASSWORD"], timeout=30)

# Extract from JAR to /tmp and copy into MinIO data dir (same bucket layout)
script = r"""
set -e
TMP=/tmp/square-fix
rm -rf "$TMP" && mkdir -p "$TMP"
docker exec lianyu-backend unzip -p /app/app.jar BOOT-INF/classes/square-avatars/zero_two.png > "$TMP/zero_two.png"
docker exec lianyu-backend unzip -p /app/app.jar BOOT-INF/classes/square-avatars/yuno.jpg > "$TMP/yuno.jpg"
ls -la "$TMP"
docker cp "$TMP/zero_two.png" lianyu-minio:/data/lianyu/square-avatars/zero_two.png
docker cp "$TMP/yuno.jpg" lianyu-minio:/data/lianyu/square-avatars/yuno.jpg
docker exec lianyu-minio sh -c 'ls -la /data/lianyu/square-avatars/zero_two.png /data/lianyu/square-avatars/yuno.jpg'
"""
run(c, script)

time.sleep(2)
run(c, "curl -s -o /dev/null -w 'zero_two=%{http_code} size=%{size_download}\\n' http://127.0.0.1:8080/api/public/files/square-avatars/zero_two.png")
run(c, "curl -s -o /dev/null -w 'yuno=%{http_code} size=%{size_download}\\n' http://127.0.0.1:8080/api/public/files/square-avatars/yuno.jpg")

c.close()
