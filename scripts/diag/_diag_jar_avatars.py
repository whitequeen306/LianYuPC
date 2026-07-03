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

run(c, """docker exec lianyu-backend sh -c '
JAR=$(find / -name "lianyu-app*.jar" 2>/dev/null | head -1)
echo "JAR=$JAR"
if command -v jar >/dev/null 2>&1; then
  jar tf "$JAR" | grep square-avatars | wc -l
  jar tf "$JAR" | grep -E "zero_two|yuno"
elif command -v unzip >/dev/null 2>&1; then
  unzip -l "$JAR" | grep square-avatars | wc -l
  unzip -l "$JAR" | grep -E "zero_two|yuno"
else
  strings "$JAR" | grep -E "square-avatars/zero_two|square-avatars/yuno" | head -5
fi
'""")
run(c, "docker logs lianyu-backend 2>&1 | grep 'Character square avatar sync finished' | tail -5")
run(c, """docker exec lianyu-mysql mysql -ulianyu -p"$MYSQL_PASSWORD" lianyyu -e "SELECT slug, avatar_url FROM character_square_template WHERE slug IN ('zero_two','yuno');" 2>/dev/null || docker exec lianyu-mysql mysql -ulianyu -p$(grep MYSQL_PASSWORD /opt/lianyu/.env | cut -d= -f2) lianyu -e "SELECT slug, avatar_url FROM character_square_template WHERE slug IN ('zero_two','yuno');" """)
run(c, "docker exec lianyu-minio sh -c 'ls -la /data/lianyu/square-avatars/ 2>/dev/null | wc -l'")

c.close()
