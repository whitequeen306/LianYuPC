#!/usr/bin/env python3
"""生产错误观察（24h 观察期用）：SSH 到服务器汇总关键错误计数与样本。

用法（本地，读 .env 的 DEPLOY_SSH_PASSWORD）：
    python scripts/ops/prod_error_watch.py            # 默认最近 24h
    python scripts/ops/prod_error_watch.py --hours 6

判定口径：
- JAVA_AI_PATH 必须为 0（Java 零 AI 逻辑）
- agent http 500 必须为 0（修复后）
- prompt 组装失败只允许出现在 agent 重启窗口（数量应为个位数）
- proactiveTurnResult failed 应为 0（错误已折叠为 error 字段）
"""
from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

import paramiko

ROOT = Path(__file__).resolve().parents[2]

CHECKS = [
    ("JAVA_AI_PATH（应为 0）",
     "docker logs --since {h}h lianyu-backend 2>&1 | grep -c JAVA_AI_PATH || true"),
    ("agent http 500（应为 0）",
     "docker logs --since {h}h lianyu-agent 2>&1 | grep -c '500 Internal Server Error' || true"),
    ("agent-2 http 500（应为 0）",
     "docker logs --since {h}h lianyu-agent-2 2>&1 | grep -c '500 Internal Server Error' || true"),
    ("proactiveTurnResult failed（应为 0）",
     "docker logs --since {h}h lianyu-backend 2>&1 | grep -c 'proactiveTurnResult failed' || true"),
    ("prompt 组装失败（仅允许重启窗口少量）",
     "docker logs --since {h}h lianyu-backend 2>&1 | grep -c 'prompt 组装失败' || true"),
    ("AI background job failed（重试后仍失败数）",
     "docker logs --since {h}h lianyu-backend 2>&1 | grep -c 'AI background job failed' || true"),
    ("AI background retry（重试发生次数，正常可 >0）",
     "docker logs --since {h}h lianyu-backend 2>&1 | grep -c 'retry in' || true"),
]

SAMPLES = [
    "docker logs --since {h} lianyu-backend 2>&1 | grep -E 'ERROR' | grep -v NoResourceFound | tail -5",
    "docker logs --since {h} lianyu-backend 2>&1 | grep 'AI background' | tail -5",
]


def load_env() -> dict:
    env = {}
    path = ROOT / ".env"
    if not path.is_file():
        return env
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            env[k.strip()] = v.strip().strip('"')
    return env


def run(c, cmd: str, timeout: int = 120) -> str:
    _, stdout, _ = c.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", errors="replace").strip()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--hours", type=int, default=24)
    parser.add_argument("--minutes", type=int, default=0,
                        help="与 --hours 二选一；给定时按分钟窗口统计")
    parser.add_argument("--host", default=os.environ.get("DEPLOY_SSH_HOST", "156.233.228.18"))
    parser.add_argument("--user", default=os.environ.get("DEPLOY_SSH_USER", "root"))
    args = parser.parse_args()

    window = f"{args.minutes}m" if args.minutes else f"{args.hours}h"
    label_window = f"最近 {args.minutes} 分钟" if args.minutes else f"最近 {args.hours} 小时"

    env = load_env()
    password = env.get("DEPLOY_SSH_PASSWORD")
    if not password:
        sys.exit("missing DEPLOY_SSH_PASSWORD (.env)")

    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(args.host, username=args.user, password=password, timeout=30)

    failed = 0
    print(f"== 生产错误观察（{label_window}）==")
    for label, cmd in CHECKS:
        value = run(c, cmd.format(h=window))
        print(f"  {label}: {value}")
        if label.startswith("JAVA_AI_PATH") and value not in ("0", ""):
            failed += 1
        if label.startswith(("agent http 500", "agent-2 http 500")) and value not in ("0", ""):
            failed += 1
        if label.startswith("proactiveTurnResult failed") and value not in ("0", ""):
            failed += 1

    print("\n== 错误样本 ==")
    for tpl in SAMPLES:
        sample = run(c, tpl.format(h=window))
        print(sample[:1500] if sample else "  (none)")

    c.close()
    print("\n== 结果 ==", "PASS" if failed == 0 else f"FAIL ({failed} 项超阈值)")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
