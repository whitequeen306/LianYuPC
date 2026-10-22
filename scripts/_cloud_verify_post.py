#!/usr/bin/env python3
"""部署后补验：8 端口接线 + parity 巡检（正确的容器内路径）+ 关键错误计数。"""
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


def run(c, cmd, timeout=900):
    print(f"\n$ {cmd[:150]}", flush=True)
    _, stdout, _ = c.exec_command(cmd, timeout=timeout, get_pty=True)
    out = stdout.read().decode("utf-8", errors="replace")
    stdout.channel.recv_exit_status()
    sys.stdout.buffer.write(out[-4000:].encode("utf-8", errors="replace"))
    return out


def main() -> None:
    env = load_env()
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect("156.233.228.18", username="root", password=env["DEPLOY_SSH_PASSWORD"], timeout=30)

    print("=" * 70)
    print("1. 端口接线（应为 8 个全 true）")
    print("=" * 70)
    run(c, "docker logs lianyu-backend 2>&1 | grep -E 'all ports wired|ports missing' | tail -2")

    print("\n" + "=" * 70)
    print("2. parity 巡检（脚本必须放在 /work/scripts/ops 下，否则仓库根会被推成 /）")
    print("=" * 70)
    run(c, "docker exec lianyu-agent mkdir -p /work/scripts/ops && "
           "docker cp /opt/lianyu/scripts/ops/daily_parity_check.py "
           "lianyu-agent:/work/scripts/ops/daily_parity_check.py && "
           "docker cp /opt/lianyu/backend-agent/app/prompts lianyu-agent:/prompts-tmp && "
           "docker exec lianyu-agent sh -c 'mkdir -p /work/backend-agent/app && "
           "rm -rf /work/backend-agent/app/prompts && cp -r /prompts-tmp /work/backend-agent/app/prompts' && "
           "docker exec -e LIANYU_OBS_AGENT_URL=http://agent-lb:8001 "
           "-e LIANYU_OBS_BASE_URL=http://127.0.0.1:8080 -w /work lianyu-agent "
           "python -X utf8 /work/scripts/ops/daily_parity_check.py; echo PARITY_EXIT=$?")

    print("\n" + "=" * 70)
    print("3. 关键错误计数（本次部署以来）")
    print("=" * 70)
    run(c, "docker logs lianyu-backend 2>&1 | grep -c JAVA_AI_PATH || echo JAVA_AI_PATH=0")
    run(c, "docker logs lianyu-backend 2>&1 | grep -c 'proactiveTurnResult failed' || echo proactive_failed=0")
    run(c, "docker logs lianyu-agent 2>&1 | grep -c '500 Internal Server Error' || echo agent_500=0")
    run(c, "docker logs lianyu-agent-2 2>&1 | grep -c '500 Internal Server Error' || echo agent2_500=0")
    run(c, "docker logs lianyu-backend 2>&1 | grep -E 'ERROR' | grep -v NoResourceFound | tail -5 || echo NO_ERRORS")
    run(c, "docker logs lianyu-agent 2>&1 | grep -iE 'tts|voice' | tail -6 || echo NO_TTS_LOG")

    c.close()
    print("\nPOST_DEPLOY_CHECK_DONE")


if __name__ == "__main__":
    main()
