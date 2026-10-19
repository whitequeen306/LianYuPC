#!/usr/bin/env python3
"""Pull latest main on cloud server and rebuild asr + backend + api-gateway."""
import os
import sys
from pathlib import Path

import paramiko

HOST = "156.233.228.18"
USER = "root"
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


def run(client: paramiko.SSHClient, cmd: str, timeout: int = 900) -> str:
    print(f"$ {cmd}", flush=True)
    _, stdout, stderr = client.exec_command(cmd, timeout=timeout, get_pty=True)
    out = stdout.read().decode("utf-8", errors="replace")
    err = stderr.read().decode("utf-8", errors="replace")
    code = stdout.channel.recv_exit_status()
    if out.strip():
        text = out[-5000:]
        sys.stdout.buffer.write(text.encode("utf-8", errors="replace"))
        sys.stdout.buffer.write(b"\n")
    if code != 0:
        if err.strip():
            sys.stderr.buffer.write(err[-2000:].encode("utf-8", errors="replace"))
            sys.stderr.buffer.write(b"\n")
        raise SystemExit(code)
    return out


def main() -> None:
    load_dotenv(ROOT / ".env")
    password = os.environ.get("DEPLOY_SSH_PASSWORD")
    if not password:
        print("Set DEPLOY_SSH_PASSWORD in .env or environment", file=sys.stderr)
        sys.exit(1)

    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, username=USER, password=password, timeout=30)

    run(client, "cd /opt/lianyu && git pull origin main")
    # OFF 退役后重建清单（逐层构建，不要一把梭 --build）：
    #   1. agent / agent2 / agent-lb —— Python 运行时与 LB（LB 需 proxy_next_upstream 修复）
    #   2. backend —— Java（等 agent 层 healthy 后再起，避免启动期探测失败）
    #   3. asr / api-gateway —— 基建层
    run(client, "cd /opt/lianyu && docker compose build agent agent2 agent-lb", timeout=1800)
    run(client, "cd /opt/lianyu && docker compose up -d agent agent2 agent-lb", timeout=600)
    run(client, "cd /opt/lianyu && docker compose up -d --build backend", timeout=1800)
    run(client, "cd /opt/lianyu && docker compose up -d --build asr api-gateway", timeout=1800)
    run(
        client,
        "cd /opt/lianyu && set -a && . ./.env && set +a && "
        'docker exec lianyu-redis sh -c '
        '"redis-cli -a \\"${REDIS_PASSWORD}\\" --scan --pattern \'lianyu:user:output_lang:*\' '
        '| xargs -r redis-cli -a \\"${REDIS_PASSWORD}\\" del"',
    )
    import time
    # 双保险：backend captcha 可用 + agent LB 健康检查通过
    for attempt in range(12):
        _, stdout, _ = client.exec_command(
            "curl -s -o /dev/null -w 'captcha=%{http_code}' http://127.0.0.1:8080/api/auth/captcha; "
            "curl -s -o /dev/null -w ' agent=%{http_code}' http://127.0.0.1:18001/agent/health",
            timeout=30,
        )
        out = stdout.read().decode("utf-8", errors="replace").strip()
        print(f"$ health attempt {attempt + 1}: {out}")
        if "captcha=200" in out and "agent=200" in out:
            break
        if attempt < 11:
            time.sleep(15)
    else:
        raise SystemExit(56)
    # OFF 退役自检：所有 agent 端口必须接线（缺端口 = 该链路会直接报错）
    run(client, "docker logs lianyu-backend 2>&1 | grep -E 'all ports wired|ports missing' | tail -2")
    run(client, "docker ps --format 'table {{.Names}}\\t{{.Status}}' | head -12")

    client.close()
    print("DEPLOY_DONE")


if __name__ == "__main__":
    main()
