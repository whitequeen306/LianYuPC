#!/usr/bin/env python3
"""线上只读诊断 2：把「No static resource ...」按路径归类，看旧客户端到底在打哪些已下线的接口。"""
import os
import re
import sys
from collections import Counter
from pathlib import Path

import paramiko

ROOT = Path(__file__).resolve().parents[1]


def load_dotenv(path: Path) -> None:
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        os.environ.setdefault(k.strip(), v.strip().strip('"'))


def run(c, cmd: str, timeout: int = 180) -> str:
    _in, out, err = c.exec_command(cmd, timeout=timeout)
    return out.read().decode("utf-8", "replace")


def main() -> None:
    load_dotenv(ROOT / ".env")
    password = os.environ.get("DEPLOY_SSH_PASSWORD")
    if not password:
        sys.exit("missing DEPLOY_SSH_PASSWORD")
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(os.environ.get("DEPLOY_SSH_HOST") or "156.233.228.18",
              username=os.environ.get("DEPLOY_SSH_USER") or "root",
              password=password, timeout=30)

    raw = run(c, "docker logs lianyu-backend --since 24h 2>&1 | grep -o 'No static resource [^ ]*' ")
    counts = Counter(line.split("No static resource ", 1)[1].strip() for line in raw.splitlines() if "No static resource " in line)
    print("=== 24h 内「路由不存在」按路径计数 ===")
    for path, n in counts.most_common(20):
        print(f"{n:6d}  {path}")
    print(f"合计 {sum(counts.values())} 次，去重 {len(counts)} 条路径")

    print("\n=== push-catchup 每小时次数 ===")
    hourly = run(c, "docker logs lianyu-backend --since 24h 2>&1 "
                    "| grep 'No static resource api/community/push-catchup' "
                    "| grep -oE '^\\S+ [0-9]{2}' | sort | uniq -c | tail -12")
    print(hourly)

    print("\n=== 后端实际暴露的 community 路由 ===")
    print(run(c, "docker logs lianyu-backend 2>&1 | grep -oE 'Mapped .*community' | head -5; echo '(无则说明后端没有 community 路由)'"))

    print("\n=== 线上后端版本与前端资源 ===")
    print(run(c, "cd /opt/lianyu && git log --oneline -1 | cat; ls -la /opt/lianyu/frontend/dist/assets 2>/dev/null | head -3"))

    c.close()


if __name__ == "__main__":
    main()
