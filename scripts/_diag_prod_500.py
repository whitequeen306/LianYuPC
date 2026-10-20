#!/usr/bin/env python3
"""线上只读诊断：查「服务暂时不可用」是从哪条链路冒出来的。

只跑只读命令（docker ps / logs / curl），不改任何状态。
"""
import os
import sys
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


def run(c, cmd: str, timeout: int = 120) -> str:
    _in, out, err = c.exec_command(cmd, timeout=timeout)
    body = out.read().decode("utf-8", "replace")
    errors = err.read().decode("utf-8", "replace")
    return body + (("\n[stderr] " + errors) if errors.strip() else "")


def main() -> None:
    load_dotenv(ROOT / ".env")
    password = os.environ.get("DEPLOY_SSH_PASSWORD")
    if not password:
        sys.exit("missing DEPLOY_SSH_PASSWORD")
    host = os.environ.get("DEPLOY_SSH_HOST") or "156.233.228.18"
    user = os.environ.get("DEPLOY_SSH_USER") or "root"

    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(host, username=user, password=password, timeout=30)

    print("=== docker ps ===")
    print(run(c, "docker ps --format '{{.Names}}\t{{.Status}}' | sort"))

    print("\n=== 服务器代码版本 ===")
    print(run(c, "cd /opt/lianyu && git log --oneline -3 2>&1 | cat"))

    print("\n=== 后端最近 ERROR（含堆栈首行）===")
    print(run(
        c,
        "docker logs lianyu-backend --since 2h 2>&1 | grep -E 'ERROR|Exception' "
        "| tail -40",
    ))

    print("\n=== 后端最近 HTTP 5xx 的请求路径 ===")
    print(run(
        c,
        "docker logs lianyu-backend --since 2h 2>&1 "
        "| grep -oE '(GET|POST|PUT|DELETE) /[^ \"]*' | sort | uniq -c | sort -rn | head -20",
    ))

    print("\n=== agent / agent-lb 健康 ===")
    print(run(
        c,
        "docker exec lianyu-backend sh -lc "
        "'wget -qO- --timeout=5 http://agent-lb:8001/agent/health; echo; "
        "wget -qO- --timeout=5 http://agent:8001/agent/health; echo; "
        "wget -qO- --timeout=5 http://agent2:8001/agent/health; echo' 2>&1",
    ))

    print("\n=== 后端启动自检（端口接线）===")
    print(run(
        c,
        "docker logs lianyu-backend 2>&1 | grep -E 'Agent runtime|Started LianYuApplication' | tail -5",
    ))

    c.close()


if __name__ == "__main__":
    main()
