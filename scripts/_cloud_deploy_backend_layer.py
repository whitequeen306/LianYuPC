#!/usr/bin/env python3
"""部署阶段 3：backend 层（构建 + 重启 + 等 captcha 200 + 接线自检）。

backend 是用户可见链路的入口，重建期间会短暂不可用（约 40–90s），
所以这里严格等到 captcha=200 才继续，超时立即吐现场日志。
"""
import sys
import time
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


def run(c, cmd, timeout=3600, must=True):
    print(f"\n$ {cmd}", flush=True)
    _, stdout, _ = c.exec_command(cmd, timeout=timeout, get_pty=True)
    out = stdout.read().decode("utf-8", errors="replace")
    code = stdout.channel.recv_exit_status()
    sys.stdout.buffer.write(out[-6000:].encode("utf-8", errors="replace"))
    if code != 0 and must:
        print(f"\n[FAIL exit={code}] 中止", file=sys.stderr)
        c.close()
        sys.exit(code)
    return code, out


def main() -> None:
    env = load_env()
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect("156.233.228.18", username="root",
              password=env["DEPLOY_SSH_PASSWORD"], timeout=30)

    print("=" * 70)
    print("1. 构建 backend 镜像（Maven 多模块，耗时最长）")
    print("=" * 70)
    started = time.time()
    run(c, "cd /opt/lianyu && docker compose build backend 2>&1 | tail -15")
    print(f"\n[build] 耗时 {time.time() - started:.0f}s")
    run(c, "docker images | grep 'lianyu-pc/backend'")

    print("\n" + "=" * 70)
    print("2. 重建容器")
    print("=" * 70)
    run(c, "cd /opt/lianyu && docker compose up -d --no-deps backend 2>&1 | tail -5", timeout=300)

    print("\n" + "=" * 70)
    print("3. 等 captcha=200（最多 6 分钟）")
    print("=" * 70)
    ok = False
    for attempt in range(24):
        time.sleep(15)
        _, o, _ = c.exec_command(
            "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/api/auth/captcha",
            timeout=30)
        code = o.read().decode().strip()
        print(f"  attempt {attempt + 1}: captcha={code}", flush=True)
        if code == "200":
            ok = True
            break
    if not ok:
        print("\n[FAIL] backend 未起来，吐现场：", file=sys.stderr)
        run(c, "docker ps -a --filter name=lianyu-backend --format 'table {{.Names}}\\t{{.Status}}'", must=False)
        run(c, "docker logs lianyu-backend --tail 80 2>&1", must=False)
        c.close()
        sys.exit(56)

    print("\n" + "=" * 70)
    print("4. 启动自检：端口接线 / Flyway / 异常")
    print("=" * 70)
    run(c, "docker logs lianyu-backend 2>&1 | grep -E 'all ports wired|ports missing' | tail -3", must=False)
    run(c, "docker logs lianyu-backend 2>&1 | grep -iE 'flyway|migrat' | tail -8", must=False)
    run(c, "docker logs lianyu-backend 2>&1 | grep -E 'ERROR|Exception' | grep -v NoResourceFound | tail -12 || echo NO_ERRORS", must=False)
    run(c, "docker logs lianyu-backend 2>&1 | grep -c 'Started LianyuApplication' || echo STARTED_COUNT=0", must=False)

    print("\n" + "=" * 70)
    print("5. 路由行为核对（本次修复点）")
    print("=" * 70)
    run(c, "curl -s -o /dev/null -w 'push_catchup=%{http_code}\\n' -X POST "
           "http://127.0.0.1:8080/api/community/push-catchup -H 'Content-Type: application/json' -d '{}'")
    run(c, "curl -s -o /dev/null -w 'unknown_route=%{http_code}\\n' "
           "http://127.0.0.1:8080/api/definitely-not-a-route")
    run(c, "curl -s http://127.0.0.1:8080/api/community/push-catchup -X POST "
           "-H 'Content-Type: application/json' -d '{}' | head -c 200; echo")

    run(c, "docker ps --format 'table {{.Names}}\\t{{.Status}}'")
    c.close()
    print("\nBACKEND_LAYER_DONE")


if __name__ == "__main__":
    main()
