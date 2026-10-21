#!/usr/bin/env python3
"""部署前只读体检：服务器 git 状态、容器、磁盘、.env 关键键、镜像 revision。

只读，不改服务器任何状态。用于部署前留档 + 回滚基线。
"""
import os
import sys
from pathlib import Path

import paramiko

ROOT = Path(__file__).resolve().parents[1]


def load_env() -> dict:
    env = {}
    for line in (ROOT / ".env").read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            env[k.strip()] = v.strip().strip('"')
    return env


def run(c, cmd, timeout=120):
    _, stdout, _ = c.exec_command(cmd, timeout=timeout, get_pty=True)
    out = stdout.read().decode("utf-8", errors="replace")
    stdout.channel.recv_exit_status()
    print(f"\n$ {cmd}")
    sys.stdout.buffer.write(out[-4000:].encode("utf-8", errors="replace"))
    return out


def main() -> None:
    env = load_env()
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect("156.233.228.18", username="root",
              password=env["DEPLOY_SSH_PASSWORD"], timeout=30)

    print("=" * 70)
    print("1. git 状态")
    print("=" * 70)
    run(c, "cd /opt/lianyu && git log -1 --format='%H %ci %s'")
    run(c, "cd /opt/lianyu && git status --short | head -20")
    run(c, "cd /opt/lianyu && git rev-parse --abbrev-ref HEAD && git remote -v | head -1")

    print("\n" + "=" * 70)
    print("2. 容器与镜像 revision 基线（回滚用）")
    print("=" * 70)
    run(c, "docker ps --format 'table {{.Names}}\\t{{.Status}}\\t{{.Image}}'")
    run(c, "docker images --format 'table {{.Repository}}\\t{{.Tag}}\\t{{.ID}}\\t{{.CreatedAt}}\\t{{.Size}}' | grep -E 'lianyu|REPOSITORY'")

    print("\n" + "=" * 70)
    print("3. 磁盘 / 内存")
    print("=" * 70)
    run(c, "df -h / /var/lib/docker 2>/dev/null | head -5")
    run(c, "free -m | head -3")
    run(c, "docker system df")

    print("\n" + "=" * 70)
    print("4. .env 关键键（只报 SET/absent，不打印值）")
    print("=" * 70)
    keys = [
        "DASHSCOPE_API_KEY", "MYSQL_USER", "MYSQL_PASSWORD", "MYSQL_ROOT_PASSWORD",
        "REDIS_PASSWORD", "MINIO_ACCESS_KEY", "MINIO_SECRET_KEY",
        "LIANYU_MASTER_KEY", "EMBEDDING_API_KEY", "EMBEDDING_BASE_URL",
        "EMBEDDING_MODEL", "RERANKER_API_KEY", "RERANKER_BASE_URL",
        "LIANYU_AGENT_BASE_URL", "LIANYU_AGENT_MODE", "MULTIMODAL_API_KEY",
        "MULTIMODAL_BASE_URL", "MULTIMODAL_MODEL", "APP_REVISION",
        "SPRING_PROFILES_ACTIVE", "LIANYU_AGENT_RELAY_ENABLED",
    ]
    script = "; ".join(
        f"grep -qE '^{k}=' /opt/lianyu/.env && printf '{k}=SET ' || printf '{k}=ABSENT '"
        for k in keys
    )
    run(c, script + "; echo")

    print("\n" + "=" * 70)
    print("5. compose config 校验 + 服务清单")
    print("=" * 70)
    run(c, "cd /opt/lianyu && docker compose config --quiet && echo COMPOSE_CONFIG_OK")
    run(c, "cd /opt/lianyu && docker compose config --services")

    print("\n" + "=" * 70)
    print("6. 当前健康基线")
    print("=" * 70)
    run(c, "curl -s -o /dev/null -w 'captcha=%{http_code}\\n' http://127.0.0.1:8080/api/auth/captcha")
    run(c, "curl -s -o /dev/null -w 'agent_lb=%{http_code}\\n' http://127.0.0.1:18001/agent/health")
    run(c, "cd /opt/lianyu && docker compose ps --format 'table {{.Name}}\\t{{.State}}\\t{{.Health}}'")

    print("\n" + "=" * 70)
    print("7. 近 24h 错误基线")
    print("=" * 70)
    run(c, "docker logs lianyu-backend --since 24h 2>&1 | grep -c 'No resource' || echo Noroute=0")
    run(c, "docker logs lianyu-backend --since 24h 2>&1 | grep -E 'ERROR' | tail -8 || echo NO_ERRORS")
    run(c, "docker logs lianyu-agent --since 24h 2>&1 | grep -c 'JAVA_AI_PATH' || echo JAVA_AI_PATH=0")

    c.close()
    print("\nPREDEPLOY_DIAG_DONE")


if __name__ == "__main__":
    main()
