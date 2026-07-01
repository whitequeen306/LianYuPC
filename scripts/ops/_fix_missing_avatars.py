#!/usr/bin/env python3
"""Check deployed JAR for zero_two/yuno and restart backend to trigger avatar sync."""
import os
import sys
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
        print(err, file=sys.stderr)
    return out

load_dotenv(ROOT / ".env")
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("154.219.111.30", username="root", password=os.environ["DEPLOY_SSH_PASSWORD"], timeout=30)

# Find JAR and list square-avatars entries
run(c, "docker exec lianyu-backend sh -c 'ls -la /app/*.jar 2>/dev/null || ls -la /opt/lianyu/*.jar 2>/dev/null || find / -name \"lianyu-app*.jar\" 2>/dev/null | head -3'")
run(c, """docker exec lianyu-backend sh -c '
JAR=$(find / -name "lianyu-app*.jar" 2>/dev/null | head -1)
if [ -z "$JAR" ]; then echo "JAR not found"; exit 1; fi
echo "JAR=$JAR"
jar tf "$JAR" 2>/dev/null | grep -E "square-avatars/(zero_two|yuno)" || unzip -l "$JAR" 2>/dev/null | grep -E "square-avatars/(zero_two|yuno)" || echo "jar/unzip not available, try strings"
'""")

# Last sync log line
run(c, "docker logs lianyu-backend 2>&1 | grep -E 'Character square avatar sync|Square avatar sync failed|Square template avatar_url updated' | tail -30")

# Restart backend to re-run sync
print("\n--- Restarting backend to trigger avatar sync ---")
run(c, "cd /opt/lianyu && docker compose restart lianyu-backend")
time.sleep(25)
run(c, "docker logs lianyu-backend 2>&1 | grep -E 'Character square avatar sync|Square avatar sync failed|Square template avatar_url updated|zero_two|yuno' | tail -20")
run(c, "curl -s -o /dev/null -w 'zero_two=%{http_code}\\n' http://127.0.0.1:8080/api/public/files/square-avatars/zero_two.png")
run(c, "curl -s -o /dev/null -w 'yuno=%{http_code}\\n' http://127.0.0.1:8080/api/public/files/square-avatars/yuno.jpg")

c.close()
