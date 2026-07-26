#!/usr/bin/env python3
"""Audit disk usage on cloud server under /opt/lianyu and Docker."""
import os
import sys

import paramiko

HOST = "156.233.228.18"
USER = "root"


def run(client, cmd, timeout=120):
    print(f"\n$ {cmd}")
    _, stdout, stderr = client.exec_command(cmd, timeout=timeout)
    out = stdout.read().decode("utf-8", errors="replace")
    err = stderr.read().decode("utf-8", errors="replace")
    if out.strip():
        print(out.rstrip())
    if err.strip():
        print(err.rstrip(), file=sys.stderr)
    return out


def main() -> None:
    password = os.environ.get("DEPLOY_SSH_PASSWORD")
    if not password:
        print("Set DEPLOY_SSH_PASSWORD", file=sys.stderr)
        sys.exit(1)

    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, username=USER, password=password, timeout=30)

    run(client, "df -h / /opt 2>/dev/null; echo '---'; free -h")
    run(client, "du -sh /opt/lianyu 2>/dev/null; du -h --max-depth=1 /opt/lianyu 2>/dev/null | sort -hr | head -25")
    run(client, "docker system df -v 2>/dev/null | head -80")
    run(client, "docker images --format 'table {{.Repository}}\t{{.Tag}}\t{{.Size}}\t{{.ID}}'")
    run(client, "find /opt/lianyu -maxdepth 4 -type f \\( -name '*.tar' -o -name '*.tar.gz' -o -name '*.zip' -o -name '*.7z' \\) -printf '%s %p\\n' 2>/dev/null | sort -nr | head -20 | awk '{printf \"%.1f MB %s\\n\", $1/1024/1024, $2}'")
    run(client, "find /opt/lianyu -type d \\( -name target -o -name node_modules -o -name dist -o -name release -o -name .deploy-export \\) 2>/dev/null | head -40")
    run(client, "du -sh /opt/lianyu/backend/*/target /opt/lianyu/frontend/node_modules /opt/lianyu/frontend/dist /opt/lianyu/frontend/release 2>/dev/null")
    run(client, "du -sh /var/lib/docker 2>/dev/null; journalctl --disk-usage 2>/dev/null")
    run(client, "ls -lah /opt/lianyu/.deploy-export 2>/dev/null; ls -lah /root/.cache 2>/dev/null | head -15")

    client.close()
    print("\nAUDIT_DONE")


if __name__ == "__main__":
    main()
