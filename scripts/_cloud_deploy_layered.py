#!/usr/bin/env python3
"""补服务器 .env 的 LIANYU_AGENT_BASE_URL（带时间戳备份），然后执行分层部署。"""
import os
import sys
import time
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


def run(c, cmd, timeout=1800, show=True):
    if show:
        print(f"$ {cmd}", flush=True)
    _, stdout, stderr = c.exec_command(cmd, timeout=timeout, get_pty=True)
    out = stdout.read().decode("utf-8", errors="replace")
    code = stdout.channel.recv_exit_status()
    if out.strip():
        sys.stdout.buffer.write(out[-5000:].encode("utf-8", errors="replace"))
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

    # 0. 补 .env：LIANYU_AGENT_BASE_URL（compose 有默认值，但显式写入更稳）
    ts = int(time.time())
    run(c, "cd /opt/lianyu && cp .env .env.bak.deploy-" + str(ts))
    run(c, "cd /opt/lianyu && grep -qE '^LIANYU_AGENT_BASE_URL=' .env || "
           "printf '\\nLIANYU_AGENT_BASE_URL=http://agent-lb:8001\\n' >> .env")
    run(c, "cd /opt/lianyu && grep -E '^LIANYU_AGENT_BASE_URL=' .env")

    # 1. 第一层：agent / agent2 / agent-lb（构建 + 起，等 healthy）
    print("\n===== layer 1: agent/agent2/agent-lb =====", flush=True)
    run(c, "cd /opt/lianyu && docker compose build agent agent2 agent-lb 2>&1 | tail -3", timeout=2400)
    run(c, "cd /opt/lianyu && docker compose up -d agent agent2 agent-lb", timeout=600)
    run(c, "sleep 12 && docker ps --filter name=lianyu-agent --format 'table {{.Names}}\\t{{.Status}}'")

    # 2. 第二层：backend
    print("\n===== layer 2: backend =====", flush=True)
    run(c, "cd /opt/lianyu && docker compose up -d --build backend 2>&1 | tail -3", timeout=2400)

    # 3. 第三层：asr / api-gateway
    print("\n===== layer 3: asr + api-gateway =====", flush=True)
    run(c, "cd /opt/lianyu && docker compose up -d --build asr api-gateway 2>&1 | tail -3", timeout=2400)

    # 4. 健康探测（双通道：captcha + agent LB 宿主端口）
    print("\n===== health probe =====", flush=True)
    import time as _t
    ok = False
    for attempt in range(12):
        _, stdout, _ = c.exec_command(
            "curl -s -o /dev/null -w 'captcha=%{http_code}' http://127.0.0.1:8080/api/auth/captcha; "
            "curl -s -o /dev/null -w ' agent=%{http_code}' http://127.0.0.1:18001/agent/health",
            timeout=30)
        out = stdout.read().decode("utf-8", errors="replace").strip()
        print(f"attempt {attempt + 1}: {out}", flush=True)
        if "captcha=200" in out and "agent=200" in out:
            ok = True
            break
        _t.sleep(15)
    if not ok:
        print("HEALTH_PROBE_FAILED — 不清理，保留现场排查", file=sys.stderr)
        run(c, "docker ps --format 'table {{.Names}}\\t{{.Status}}'")
        run(c, "docker logs lianyu-backend --tail 30 2>&1")
        sys.exit(56)

    # 5. OFF 退役自检：agent 端口接线
    print("\n===== wiring check =====", flush=True)
    run(c, "docker logs lianyu-backend 2>&1 | grep -E 'all ports wired|ports missing' | tail -2")

    # 6. 输出语言缓存清理（与旧部署脚本保持一致）
    run(c, "cd /opt/lianyu && set -a && . ./.env && set +a && "
           'docker exec lianyu-redis sh -c '
           '"redis-cli -a \\"${REDIS_PASSWORD}\\" --scan --pattern \'lianyu:user:output_lang:*\' '
           '| xargs -r redis-cli -a \\"${REDIS_PASSWORD}\\" del" 2>&1 | tail -1')

    run(c, "docker ps --format 'table {{.Names}}\\t{{.Status}}'")
    c.close()
    print("DEPLOY_DONE")


if __name__ == "__main__":
    main()
