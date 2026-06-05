#!/usr/bin/env python3
"""Upload locally built lianyu-pc/backend:cloud image and restart backend on server."""
import os
import sys
import time
from pathlib import Path

import paramiko

HOST = '154.219.111.30'
USER = 'root'
ROOT = Path(__file__).resolve().parents[2]
TAR_PATH = ROOT / '.deploy-export' / 'image-lianyu-pc_backend_cloud_like.tar'
REMOTE_TAR = '/opt/lianyu/.deploy-import/backend_cloud_like.tar'


def main():
    password = os.environ.get('DEPLOY_SSH_PASSWORD')
    if not password:
        print('Set DEPLOY_SSH_PASSWORD env var', file=sys.stderr)
        sys.exit(1)
    if not TAR_PATH.is_file():
        print(f'Missing {TAR_PATH}. Run: docker compose build backend && docker save ...', file=sys.stderr)
        sys.exit(1)

    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, username=USER, password=password, timeout=30)

    def run(cmd, timeout=600):
        print(f'$ {cmd}', flush=True)
        _, stdout, stderr = client.exec_command(cmd, timeout=timeout, get_pty=True)
        out = stdout.read().decode('utf-8', errors='replace')
        err = stderr.read().decode('utf-8', errors='replace')
        code = stdout.channel.recv_exit_status()
        print(f'exit={code}', flush=True)
        if out.strip():
            print(out[-3000:])
        if err.strip() and code != 0:
            print('stderr:', err[-2000:], file=sys.stderr)
        if code != 0:
            raise SystemExit(code)
        return out

    size_mb = TAR_PATH.stat().st_size / 1024 / 1024
    print(f'Uploading {TAR_PATH.name} ({size_mb:.1f} MB)...', flush=True)
    sftp = client.open_sftp()
    run('mkdir -p /opt/lianyu/.deploy-import')
    sent = [0]

    def progress(transferred, total):
        pct = transferred * 100 // total if total else 0
        if pct >= sent[0] + 10:
            sent[0] = pct - (pct % 10)
            print(f'  {pct}%', flush=True)

    sftp.put(str(TAR_PATH), REMOTE_TAR, callback=progress)
    sftp.close()
    print('Upload complete', flush=True)

    run(f'docker load -i {REMOTE_TAR}', timeout=900)
    run('cd /opt/lianyu && docker compose up -d backend')
    time.sleep(10)
    run('docker logs lianyu-backend --tail 30 2>&1')
    run("curl -s -o /dev/null -w 'captcha=%{http_code}\\n' http://127.0.0.1:8080/api/auth/captcha")
    run(f'rm -f {REMOTE_TAR}')

    client.close()
    print('DEPLOY_DONE')


if __name__ == '__main__':
    main()
