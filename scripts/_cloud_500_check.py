"""看生产 500 的最新堆栈（热修后是否仍在发生）——精确抓时间戳和异常类型。"""
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

    # 只看热修（容器启动）之后的日志：docker logs 输出带容器重启分界，
    # 用 --since 容器启动时间即可
    cmds = [
        "ST=$(docker inspect lianyu-agent --format '{{.State.StartedAt}}'); "
        "echo container_started=$ST; "
        "docker logs --since \"$ST\" lianyu-agent 2>&1 | grep '500 Internal Server Error' | wc -l",
        "ST=$(docker inspect lianyu-agent --format '{{.State.StartedAt}}'); "
        "docker logs --since \"$ST\" lianyu-agent 2>&1 | grep -B3 '500 Internal Server Error' | grep -E 'Error|error|raise' | sort | uniq -c | sort -rn | head -8",
        "ST=$(docker inspect lianyu-agent --format '{{.State.StartedAt}}'); "
        "docker logs --since \"$ST\" lianyu-agent 2>&1 | grep -A6 'Traceback' | tail -25",
    ]
    for cmd in cmds:
        print(f"$ {cmd[:110]}", flush=True)
        _, stdout, _ = c.exec_command(cmd, timeout=120)
        out = stdout.read().decode("utf-8", errors="replace")
        sys.stdout.buffer.write(out[:4000].encode("utf-8", errors="replace"))
        sys.stdout.buffer.write(b"\n----\n")
    c.close()


if __name__ == "__main__":
    main()
