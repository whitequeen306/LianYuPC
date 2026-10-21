#!/usr/bin/env python3
"""部署后日志与巡检核对。

要点：
1. 聊天回合是否真的走了 Python LangGraph（而不是悄悄回退）
2. parity 巡检（golden 字数逐字节一致）
3. 关键错误计数
"""
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


def run(c, cmd, timeout=900, must=False):
    print(f"\n$ {cmd}", flush=True)
    _, stdout, _ = c.exec_command(cmd, timeout=timeout, get_pty=True)
    out = stdout.read().decode("utf-8", errors="replace")
    stdout.channel.recv_exit_status()
    sys.stdout.buffer.write(out[-5000:].encode("utf-8", errors="replace"))
    return out


def main() -> None:
    env = load_env()
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect("156.233.228.18", username="root",
              password=env["DEPLOY_SSH_PASSWORD"], timeout=30)

    print("=" * 70)
    print("1. 聊天回合是否走 LangGraph（backend 侧日志）")
    print("=" * 70)
    run(c, "docker logs lianyu-backend --since 15m 2>&1 | grep -E 'ChatTurn (stream|image)' | tail -10", must=False)
    run(c, "docker logs lianyu-backend --since 15m 2>&1 | grep -c 'served by LangGraph' || echo zero")
    run(c, "docker logs lianyu-backend --since 15m 2>&1 | grep -c 'Chat turn stream dispatched' || echo zero")

    print("\n" + "=" * 70)
    print("2. agent 侧收到的 chat-turn 请求")
    print("=" * 70)
    for ctr in ("lianyu-agent", "lianyu-agent-2"):
        run(c, f"docker logs {ctr} --since 15m 2>&1 | grep -E 'chat-turn|chat_turn' | tail -6", must=False)
        run(c, f"docker logs {ctr} --since 15m 2>&1 | grep -c 'POST /agent/chat-turn' || echo {ctr}_zero", must=False)

    print("\n" + "=" * 70)
    print("3. 关键错误计数（本次启动以来）")
    print("=" * 70)
    run(c, "docker logs lianyu-backend 2>&1 | grep -c JAVA_AI_PATH || echo JAVA_AI_PATH=0")
    run(c, "docker logs lianyu-agent 2>&1 | grep -c '500 Internal Server Error' || echo agent_500=0")
    run(c, "docker logs lianyu-agent-2 2>&1 | grep -c '500 Internal Server Error' || echo agent2_500=0")
    run(c, "docker logs lianyu-backend 2>&1 | grep -c 'proactiveTurnResult failed' || echo proactive_failed=0")
    run(c, "docker logs lianyu-backend 2>&1 | grep -cE 'ERROR' || echo backend_error=0")
    run(c, "docker logs lianyu-backend 2>&1 | grep -E 'ERROR' | tail -6", must=False)

    print("\n" + "=" * 70)
    print("4. parity 巡检（容器内跑）")
    print("=" * 70)
    run(c, "docker exec lianyu-agent mkdir -p /work && "
           "docker cp /opt/lianyu/scripts/ops/daily_parity_check.py lianyu-agent:/tmp/parity.py && "
           "docker cp /opt/lianyu/backend-agent/app/prompts lianyu-agent:/prompts-tmp && "
           "docker exec lianyu-agent sh -c 'mkdir -p /work/backend-agent/app && "
           "cp -r /prompts-tmp /work/backend-agent/app/prompts' && "
           "docker exec -e LIANYU_OBS_AGENT_URL=http://agent-lb:8001 -w /work lianyu-agent "
           "python -X utf8 /tmp/parity.py; echo PARITY_EXIT=$?", timeout=600)

    print("\n" + "=" * 70)
    print("5. 容器与镜像终态")
    print("=" * 70)
    run(c, "docker ps --format 'table {{.Names}}\\t{{.Status}}' | head -15")
    run(c, "cd /opt/lianyu && git rev-parse HEAD")

    c.close()
    print("\nLOG_CHECK_DONE")


if __name__ == "__main__":
    main()
