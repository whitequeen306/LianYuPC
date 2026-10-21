#!/usr/bin/env python3
"""部署后 30 分钟观察：证明关键链路「在跑且成功」，而不只是「没报错」。"""
import sys
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


def run(c, cmd, timeout=600):
    print(f"\n$ {cmd}", flush=True)
    _, stdout, _ = c.exec_command(cmd, timeout=timeout, get_pty=True)
    out = stdout.read().decode("utf-8", errors="replace")
    stdout.channel.recv_exit_status()
    sys.stdout.buffer.write(out[-6000:].encode("utf-8", errors="replace"))
    return out


def main() -> None:
    env = load_env()
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect("156.233.228.18", username="root",
              password=env["DEPLOY_SSH_PASSWORD"], timeout=30)

    print("=" * 70)
    print("1. 主动发言是否在跑（成功样本）")
    print("=" * 70)
    run(c, "docker logs lianyu-backend --since 20m 2>&1 | grep -E 'proactive' | tail -8")
    run(c, "docker logs lianyu-backend --since 20m 2>&1 | grep -ci 'proactiveTurn' || echo 0")

    print("\n" + "=" * 70)
    print("2. 老客户端社区轮询：现在走垫片（应无 NoResourceFound）")
    print("=" * 70)
    run(c, "docker logs lianyu-backend --since 20m 2>&1 | grep -c 'NoResourceFoundException' || echo 0")
    run(c, "docker logs lianyu-backend --since 20m 2>&1 | grep -E 'push-catchup' | tail -5 || echo NONE")

    print("\n" + "=" * 70)
    print("3. 真实用户流量（部署后有无人用）")
    print("=" * 70)
    run(c, "docker logs lianyu-backend --since 20m 2>&1 | grep -cE 'ChatTurn stream dispatched|Chat turn stream dispatched' || echo 0")
    run(c, "docker logs lianyu-backend --since 20m 2>&1 | grep -oE 'POST /api/conversation/[0-9]+/messages/(stream)?' | sort | uniq -c | tail -10 || echo NONE")
    run(c, "docker logs lianyu-api-gateway --since 20m 2>&1 | grep -oE '\"POST /api/[^\"]+' | sort | uniq -c | sort -rn | head -10 || echo NONE")

    print("\n" + "=" * 70)
    print("4. 上游错误（用户 key 问题属预期降级）")
    print("=" * 70)
    run(c, "docker logs lianyu-agent --since 20m 2>&1 | grep -oE 'model gateway http [0-9]+' | sort | uniq -c || echo NONE")
    run(c, "docker logs lianyu-agent --since 20m 2>&1 | grep -c 'circuit open' || echo circuit_open=0")

    c.close()
    print("\nOBSERVE_DONE")


if __name__ == "__main__":
    main()
