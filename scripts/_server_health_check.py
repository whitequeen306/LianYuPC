#!/usr/bin/env python3
import os
import sys

import paramiko

HOST = "154.219.111.30"
USER = "root"


def main() -> None:
    password = os.environ.get("DEPLOY_SSH_PASSWORD")
    if not password:
        print("Set DEPLOY_SSH_PASSWORD", file=sys.stderr)
        sys.exit(1)

    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, username=USER, password=password, timeout=30)

    for cmd in [
        'docker ps --format "table {{.Names}}\t{{.Status}}"',
        'curl -s -o /dev/null -w "backend:%{http_code}\n" http://127.0.0.1:8080/api/auth/captcha',
        'curl -sk -o /dev/null -w "gateway:%{http_code}\n" https://127.0.0.1/api/auth/captcha',
    ]:
        _, stdout, stderr = client.exec_command(cmd, timeout=20)
        out = stdout.read().decode("utf-8", errors="replace").strip()
        err = stderr.read().decode("utf-8", errors="replace").strip()
        print(out or err)

    client.close()


if __name__ == "__main__":
    main()
