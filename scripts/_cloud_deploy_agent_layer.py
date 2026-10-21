#!/usr/bin/env python3
"""部署阶段 2：agent 层（构建一次镜像，先后重建 agent2 / agent，逐个等 healthy）。

滚动顺序保证任意时刻至少一个实例在服务：
  build → 重建 agent2 → 等 healthy → 重建 agent → 等 healthy → 探测 LB
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


def run(c, cmd, timeout=2400, must=True):
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


def wait_healthy(c, container, timeout_s=300):
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        _, o, _ = c.exec_command(
            f"docker inspect --format '{{{{.State.Health.Status}}}}' {container}", timeout=30)
        st = o.read().decode().strip()
        print(f"  {container}: {st}", flush=True)
        if st == "healthy":
            return
        time.sleep(10)
    raise SystemExit(f"[FAIL] {container} 未在 {timeout_s}s 内 healthy")


def main() -> None:
    env = load_env()
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect("156.233.228.18", username="root",
              password=env["DEPLOY_SSH_PASSWORD"], timeout=30)

    print("=" * 70)
    print("1. 构建 agent 镜像（agent/agent2 同镜像，只构建一次）")
    print("=" * 70)
    run(c, "cd /opt/lianyu && docker compose build agent 2>&1 | tail -8")
    run(c, "docker images | grep 'lianyu-pc/agent'")

    print("\n" + "=" * 70)
    print("2. 滚动重建 agent2 → 等 healthy")
    print("=" * 70)
    run(c, "cd /opt/lianyu && docker compose up -d --no-deps agent2 2>&1 | tail -3", timeout=300)
    wait_healthy(c, "lianyu-agent-2")

    print("\n" + "=" * 70)
    print("3. 滚动重建 agent → 等 healthy")
    print("=" * 70)
    run(c, "cd /opt/lianyu && docker compose up -d --no-deps agent 2>&1 | tail -3", timeout=300)
    wait_healthy(c, "lianyu-agent")

    print("\n" + "=" * 70)
    print("4. 容器内代码校验（新模块必须存在）")
    print("=" * 70)
    for f in ("app/dashscope.py", "app/tts.py", "app/voice_enrollment.py",
              "app/chat_turn.py", "app/message_assembly.py", "app/prompt_pipeline.py"):
        run(c, f"docker exec lianyu-agent test -f /app/{f} && echo AGENT_HAS {f}", must=False)
        run(c, f"docker exec lianyu-agent-2 test -f /app/{f} && echo AGENT2_HAS {f}", must=False)

    print("\n" + "=" * 70)
    print("5. 新端点存在性（两实例）")
    print("=" * 70)
    run(c, "curl -s http://127.0.0.1:18001/agent/health; echo")
    run(c, "docker exec lianyu-agent curl -s -o /dev/null -w 'enroll_no_body=%{http_code}\\n' "
           "-X POST http://127.0.0.1:8001/agent/voice/enroll -H 'Content-Type: application/json' -d '{}'")
    run(c, "docker exec lianyu-agent curl -s -o /dev/null -w 'tts_no_body=%{http_code}\\n' "
           "-X POST http://127.0.0.1:8001/agent/tts/synthesize -H 'Content-Type: application/json' -d '{}'")

    print("\n" + "=" * 70)
    print("6. agent 日志：启动期异常检查")
    print("=" * 70)
    run(c, "docker logs lianyu-agent --since 5m 2>&1 | grep -iE 'traceback|error' | tail -10 || echo NO_ERRORS", must=False)
    run(c, "docker logs lianyu-agent-2 --since 5m 2>&1 | grep -iE 'traceback|error' | tail -10 || echo NO_ERRORS", must=False)

    c.close()
    print("\nAGENT_LAYER_DONE")


if __name__ == "__main__":
    main()
