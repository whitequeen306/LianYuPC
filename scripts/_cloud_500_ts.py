"""最后一问：热修后仍有 4 次 500——堆栈被 FastAPI 吞了看不到业务异常。
直接在容器里对 /agent/proactive-turn 发一次真实请求，看当前代码是否还炸。
先验证 docker logs 的时间戳格式（uvicorn INFO 行没有时间戳，需要 --timestamps）。"""
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
        # docker logs -t 给每行加 RFC3339 时间戳，然后过滤 500 行看真实时间
        "docker logs -t lianyu-agent 2>&1 | grep '500 Internal Server Error' | tail -4",
        # 当前时间（服务器）
        "date -u +%Y-%m-%dT%H:%M:%SZ",
        # 业务异常是否真的还在：直接容器内 curl 触发一次 proactive（无凭据场景→应返回 no_text_credentials 200）
        "docker exec lianyu-agent sh -c 'curl -s -o /dev/null -w \"%{http_code}\" -X POST "
        "http://agent-lb:8001/agent/proactive-turn -H \"Content-Type: application/json\" "
        "-d \"{\\\"userId\\\":1,\\\"scene\\\":\\\"PROACTIVE\\\",\\\"persona\\\":\\\"p\\\","
        "\\\"taskBlock\\\":\\\"t\\\",\\\"history\\\":[]}\"'",
        # 后端近 10 分钟（热修后）还有没有 proactiveTurnResult failed
        "docker logs lianyu-backend --since 10m 2>&1 | grep -c 'proactiveTurnResult failed' || echo FAIL_10M=0",
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
