"""确认容器内代码是否真的是热修版本（/app/app/graphs/proactive_turn.py 第 92-105 行）。"""
import os
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
        # 容器内代码是否包含修复标记（外层 try/except）
        "docker exec lianyu-agent grep -n '_invoke_inner' /app/app/graphs/proactive_turn.py | head -3",
        # 镜像构建时间 vs git 修复提交时间
        "docker inspect lianyu-agent --format '{{.Image}}' | head -c 20; echo",
        "cd /opt/lianyu && git log -1 --format='%h %ci' ceceff7",
        # 镜像是否为 ceceff7 之后构建
        "cd /opt/lianyu && docker image inspect lianyu-pc/agent:cloud --format '{{.Created}}'",
        # 500 的时间戳——热修容器 10:42:52 启动，看 500 是不是都在那之前
        "docker logs --since 2026-10-20T02:43:00Z lianyu-agent 2>&1 | grep '500 Internal Server Error' | wc -l",
        "docker logs lianyu-agent 2>&1 | grep '500 Internal Server Error' | tail -4",
    ]
    for cmd in cmds:
        print(f"$ {cmd[:110]}", flush=True)
        _, stdout, _ = c.exec_command(cmd, timeout=120)
        out = stdout.read().decode("utf-8", errors="replace")
        import sys
        sys.stdout.buffer.write(out[:2500].encode("utf-8", errors="replace"))
        sys.stdout.buffer.write(b"\n----\n")
    c.close()


if __name__ == "__main__":
    main()
