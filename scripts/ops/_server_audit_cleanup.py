#!/usr/bin/env python3
"""Audit cloud server health + disk; safe cleanup (no build/maven cache)."""
import os
import sys
from pathlib import Path

import paramiko

HOST = "154.219.111.30"
USER = "root"
ROOT = Path(__file__).resolve().parents[1]
APPLY_CLEANUP = "--apply" in sys.argv


def load_dotenv(path: Path) -> None:
    if not path.is_file():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        os.environ.setdefault(k.strip(), v.strip().strip('"'))


def run(client: paramiko.SSHClient, cmd: str, timeout: int = 120) -> tuple[int, str]:
    print(f"$ {cmd}", flush=True)
    _, stdout, stderr = client.exec_command(cmd, timeout=timeout, get_pty=True)
    out = stdout.read().decode("utf-8", errors="replace")
    err = stderr.read().decode("utf-8", errors="replace")
    code = stdout.channel.recv_exit_status()
    text = (out + err).strip()
    if text:
        sys.stdout.buffer.write(text.encode("utf-8", errors="replace"))
        sys.stdout.buffer.write(b"\n")
    return code, text


def main() -> None:
    load_dotenv(ROOT / ".env")
    password = os.environ.get("DEPLOY_SSH_PASSWORD")
    if not password:
        print("Set DEPLOY_SSH_PASSWORD in .env", file=sys.stderr)
        sys.exit(1)

    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, username=USER, password=password, timeout=30)

    section = "=== 服务状态 ==="
    print(section)
    run(client, "uptime")
    run(client, "df -h / /opt /var/lib/docker 2>/dev/null | head -5")
    run(client, "free -h")
    run(client, "docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Image}}'")
    run(client, "curl -s -o /dev/null -w 'backend_captcha=%{http_code}\\n' http://127.0.0.1:8080/api/auth/captcha")
    run(client, "curl -sk -o /dev/null -w 'gateway_captcha=%{http_code}\\n' https://127.0.0.1/api/auth/captcha")

    print("=== Docker 磁盘占用（含 build cache，勿清） ===")
    run(client, "docker system df -v 2>/dev/null | head -40")

    print("=== 镜像列表 ===")
    run(client, "docker images --format 'table {{.Repository}}\t{{.Tag}}\t{{.Size}}\t{{.ID}}'")

    print("=== 悬空/未使用资源统计 ===")
    run(client, "docker images -f dangling=true -q | wc -l | xargs -I{} echo dangling_images={}")
    run(client, "docker ps -a --filter status=exited -q | wc -l | xargs -I{} echo stopped_containers={}")
    run(client, "docker volume ls -f dangling=true -q | wc -l | xargs -I{} echo dangling_volumes={}")

    print("=== /opt/lianyu 目录大小 ===")
    run(client, "du -sh /opt/lianyu /opt/lianyu/.git 2>/dev/null; du -sh /opt/lianyu/*/ 2>/dev/null | sort -hr | head -15")

    print("=== 大日志 / tmp（可安全清理候选） ===")
    run(client, "journalctl --disk-usage 2>/dev/null || true")
    run(client, "du -sh /var/log/journal 2>/dev/null || true")
    run(client, "find /var/log -type f -size +50M 2>/dev/null | head -10")
    run(client, "du -sh /tmp /var/tmp 2>/dev/null")

    if not APPLY_CLEANUP:
        print("=== 审计完成（未清理）。安全清理请运行: python scripts/_server_audit_cleanup.py --apply ===")
        client.close()
        return

    print("=== 安全清理（保留 build cache + 在用的镜像） ===")
    # dangling images only — not docker builder prune
    run(client, "docker image prune -f")
    # stopped containers (not running stack)
    run(client, "docker container prune -f")
    # vacuum journal to 200M max
    run(client, "journalctl --vacuum-size=200M 2>/dev/null || true")
  # apt cache if present
    run(client, "apt-get clean 2>/dev/null; rm -rf /var/lib/apt/lists/* 2>/dev/null; true")

    print("=== 清理后 ===")
    run(client, "df -h / /var/lib/docker")
    run(client, "docker system df")

    client.close()
    print("CLEANUP_DONE")


if __name__ == "__main__":
    main()
