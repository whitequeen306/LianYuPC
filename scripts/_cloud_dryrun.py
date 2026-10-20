#!/usr/bin/env python3
"""生产部署第一步：干跑——git pull + compose config 校验 + .env 核对。只读，不改任何服务。"""
import os
import sys
from pathlib import Path

import paramiko

ROOT = Path(__file__).resolve().parents[1]


def load_dotenv(path: Path) -> None:
    if not path.is_file():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"'))


def run(c, cmd, timeout=600):
    print(f"$ {cmd}", flush=True)
    _, stdout, stderr = c.exec_command(cmd, timeout=timeout, get_pty=True)
    out = stdout.read().decode("utf-8", errors="replace")
    code = stdout.channel.recv_exit_status()
    if out.strip():
        sys.stdout.buffer.write(out[-4000:].encode("utf-8", errors="replace"))
        sys.stdout.buffer.write(b"\n")
    if code != 0:
        print(f"[exit {code}]", file=sys.stderr)
    return code, out


def main() -> None:
    load_dotenv(ROOT / ".env")
    password = os.environ.get("DEPLOY_SSH_PASSWORD")
    if not password:
        sys.exit("missing DEPLOY_SSH_PASSWORD")

    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect("156.233.228.18", username="root", password=password, timeout=30)

    # 1. git pull
    code, _ = run(c, "cd /opt/lianyu && git fetch origin && git status --porcelain | head -5")
    code, out = run(c, "cd /opt/lianyu && git rev-parse HEAD && git log origin/main -1 --oneline")
    run(c, "cd /opt/lianyu && git pull origin main 2>&1 | tail -5")
    run(c, "cd /opt/lianyu && git log -1 --oneline")

    # 2. compose config 校验
    code, _ = run(c, "cd /opt/lianyu && docker compose config --quiet && echo COMPOSE_CONFIG_OK")

    # 3. .env 核对（只报键名是否存在，不打印值）
    run(c, """cd /opt/lianyu && for k in DASHSCOPE_API_KEY MYSQL_USER MYSQL_PASSWORD REDIS_PASSWORD
              EMBEDDING_API_KEY RERANKER_API_KEY LIANYU_AGENT_BASE_URL; do
        if grep -qE "^${k}=" .env 2>/dev/null; then
            v=$(grep -E "^${k}=" .env | head -1 | cut -d= -f2-)
            if [ -n "$v" ]; then echo "  $k: SET(${#v} chars)"; else echo "  $k: EMPTY"; fi
        else
            echo "  $k: MISSING"
        fi
    done
    if grep -qE "^LIANYU_AGENT_MODE=" .env; then
        echo "  LIANYU_AGENT_MODE: PRESENT (obsolete since OFF retirement — recommend removing)"
    else
        echo "  LIANYU_AGENT_MODE: absent (good)"
    fi""")

    # 4. 当前容器状态快照
    run(c, "docker ps --format 'table {{.Names}}\\t{{.Status}}' | head -14")

    c.close()
    print("DRYRUN_DONE")


if __name__ == "__main__":
    main()
