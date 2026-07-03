#!/usr/bin/env python3
import os
from pathlib import Path
import paramiko

ROOT = Path(__file__).resolve().parents[1]

def load_dotenv(path):
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            os.environ.setdefault(k.strip(), v.strip().strip('"'))

load_dotenv(ROOT / ".env")
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("154.219.111.30", username="root", password=os.environ["DEPLOY_SSH_PASSWORD"], timeout=30)

def mysql(sql):
    escaped = sql.replace('"', '\\"')
    cmd = (
        "cd /opt/lianyu && MYSQL_PWD=$(grep '^MYSQL_PASSWORD=' .env | cut -d= -f2-) && "
        f'docker exec -e MYSQL_PWD="$MYSQL_PWD" lianyu-mysql mysql -ulianyu lianyu -e "{escaped}"'
    )
    _, stdout, stderr = c.exec_command(cmd, timeout=60)
    print(stdout.read().decode())
    err = stderr.read().decode()
    if err.strip():
        print(err)

mysql("SELECT installed_rank, version, checksum FROM flyway_schema_history ORDER BY installed_rank;")
c.close()
