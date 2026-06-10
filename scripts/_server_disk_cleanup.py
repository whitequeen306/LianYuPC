#!/usr/bin/env python3
"""Safe cleanup of redundant build/deploy artifacts on cloud server."""
import os
import sys

import paramiko

HOST = "154.219.111.30"
USER = "root"


def run(client, cmd, timeout=300):
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

    print("=== BEFORE ===")
    run(client, "df -h /")
    run(client, "docker system df")

    # 1) Deploy / frontend build artifacts (server is backend-only; no Electron build here)
    run(client, "ls -lah /opt/lianyu/.deploy-import/ 2>/dev/null || true")
    run(client, "rm -f /opt/lianyu/.deploy-import/*.tar /opt/lianyu/.deploy-import/*.tar.gz 2>/dev/null; rmdir /opt/lianyu/.deploy-import 2>/dev/null || true")
    run(
        client,
        "rm -rf /opt/lianyu/frontend/node_modules /opt/lianyu/frontend/dist "
        "/opt/lianyu/frontend/release /opt/lianyu/frontend/dist-electron "
        "/opt/lianyu/.deploy-export /opt/lianyu/backend/lianyu-*/target 2>/dev/null || true",
    )
    run(client, "docker rmi $(docker images 'lianyu-pc/frontend' -q) 2>/dev/null || true")

    # 2) Docker build cache — does not affect running containers
    run(client, "docker builder prune -af", timeout=600)

    # 3) Dangling images from old backend builds
    run(client, "docker image prune -f")

    # 4) Images not referenced by any container (keeps running stack intact)
    run(client, "docker image prune -af")

    print("\n=== AFTER ===")
    run(client, "df -h /")
    run(client, "docker system df")
    run(client, "du -h --max-depth=1 /opt/lianyu 2>/dev/null | sort -hr | head -10")

    client.close()
    print("\nCLEANUP_DONE")


if __name__ == "__main__":
    main()
