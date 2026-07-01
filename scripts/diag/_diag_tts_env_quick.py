#!/usr/bin/env python3
import os, sys
from pathlib import Path
import paramiko
ROOT = Path(__file__).resolve().parents[1]
def load_dotenv(p):
    if not p.is_file(): return
    for line in p.read_text(encoding='utf-8').splitlines():
        line=line.strip()
        if not line or line.startswith('#') or '=' not in line: continue
        k,v=line.split('=',1); os.environ.setdefault(k.strip(), v.strip().strip('"'))
load_dotenv(ROOT/'.env')
pw=os.environ.get('DEPLOY_SSH_PASSWORD')
c=paramiko.SSHClient(); c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect('154.219.111.30', username='root', password=pw, timeout=30)
for cmd in [
    "docker exec lianyu-backend printenv | grep -iE 'LIANYU_PET_TTS|LIANYU_AI_TTS|tts' || true",
    "docker logs lianyu-backend 2>&1 | grep -F '9dff5ac35f2046babbbcdf6b5ad66a6d' | head -20",
]:
    print('$', cmd)
    _, o, e = c.exec_command(cmd, timeout=60)
    print(o.read().decode() or e.read().decode() or '(empty)')
    print()
c.close()
