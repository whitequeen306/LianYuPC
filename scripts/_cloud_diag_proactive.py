#!/usr/bin/env python3
"""生产 500 根因排查：看 agent 容器日志里 proactive 500 的堆栈。"""
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


def main() -> None:
    load_dotenv(ROOT / ".env")
    password = os.environ.get("DEPLOY_SSH_PASSWORD")
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect("156.233.228.18", username="root", password=password, timeout=30)

    cmds = [
        # agent 日志里的 proactive 错误堆栈
        "docker logs lianyu-agent --since 15m 2>&1 | grep -B2 -A18 'Traceback' | tail -50",
        "docker logs lianyu-agent-2 --since 15m 2>&1 | grep -B2 -A18 'Traceback' | tail -50",
        # 500 前后上下文
        "docker logs lianyu-agent --since 15m 2>&1 | grep -B5 '500 Internal Server Error' | tail -30",
        # 生产 MySQL/Redis 连通性（从 agent 容器内）
        "docker exec lianyu-agent python -c \"import asyncio, os; from app.config import get_settings; s=get_settings(); print('mysql_host:', s.mysql_host, 'redis:', s.redis_url[:30])\"",
        # 记忆/情绪取数走 DB——生产 agent 是否连得上 MySQL
        "docker exec lianyu-agent python -c \"import asyncio; from app.db import fetch_all; asyncio.run((lambda: __import__('asyncio').get_event_loop().run_until_complete(_t()))())\" 2>&1 | head -5 || true",
    ]
    for cmd in cmds:
        print(f"$ {cmd[:100]}", flush=True)
        _, stdout, _ = c.exec_command(cmd, timeout=120)
        out = stdout.read().decode("utf-8", errors="replace")
        sys.stdout.buffer.write(out[:4000].encode("utf-8", errors="replace"))
        sys.stdout.buffer.write(b"\n----\n")

    c.close()


if __name__ == "__main__":
    main()
