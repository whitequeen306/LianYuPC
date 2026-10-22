#!/usr/bin/env python3
"""部署阶段 1：服务器侧准备（拉代码 + 校验 + 留回滚镜像标签）。

只做三件事：
1. git fetch/pull --ff-only 到指定提交，并断言 HEAD 与本地一致
2. compose config 校验
3. 把当前 agent/backend 镜像打上 rollback 标签（回滚基线）

不做任何构建、不重启任何容器。失败即停。
"""
import os
import sys
from pathlib import Path

import paramiko

ROOT = Path(__file__).resolve().parents[1]
TARGET = sys.argv[1] if len(sys.argv) > 1 else None
ROLLBACK_TAG = "cloud-rollback-ef26d6f"


def load_env() -> dict:
    env = {}
    for line in (ROOT / ".env").read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            env[k.strip()] = v.strip().strip('"')
    return env


def run(c, cmd, timeout=300, must=True):
    print(f"\n$ {cmd}", flush=True)
    _, stdout, _ = c.exec_command(cmd, timeout=timeout, get_pty=True)
    out = stdout.read().decode("utf-8", errors="replace")
    code = stdout.channel.recv_exit_status()
    sys.stdout.buffer.write(out.encode("utf-8", errors="replace"))
    if code != 0 and must:
        print(f"\n[FAIL exit={code}] 中止", file=sys.stderr)
        c.close()
        sys.exit(code)
    return code, out


def main() -> None:
    if not TARGET:
        sys.exit("用法: _cloud_deploy_prep.py <目标提交sha>")
    env = load_env()
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect("156.233.228.18", username="root",
              password=env["DEPLOY_SSH_PASSWORD"], timeout=30)

    print("=" * 70)
    print("1. 拉代码")
    print("=" * 70)
    _, head_before_out = run(c, "cd /opt/lianyu && git rev-parse --short HEAD")
    head_before = head_before_out.strip().splitlines()[-1].strip()
    print(f"\n拉取前服务器 HEAD = {head_before}")
    run(c, "cd /opt/lianyu && git fetch origin main 2>&1 | tail -3")
    run(c, "cd /opt/lianyu && git log -1 --format='%h %s' origin/main")
    run(c, "cd /opt/lianyu && git pull --ff-only origin main 2>&1 | tail -6")
    code, out = run(c, "cd /opt/lianyu && git rev-parse HEAD")
    head = out.strip().splitlines()[-1].strip()
    if not head.startswith(TARGET[:8]):
        print(f"\n[FAIL] 服务器 HEAD={head} 期望={TARGET}", file=sys.stderr)
        c.close()
        sys.exit(2)
    print(f"\n[OK] 服务器 HEAD = {head}")

    print("\n" + "=" * 70)
    print("2. compose 校验")
    print("=" * 70)
    run(c, "cd /opt/lianyu && docker compose config --quiet && echo COMPOSE_CONFIG_OK")

    print("\n" + "=" * 70)
    print("3. 回滚基线标签")
    print("=" * 70)
    # 标签名带上「本次部署前」的服务端提交号，避免每次覆盖同一枚标签、
    # 把更早的回滚基线冲掉（上次就写死过 cloud-rollback-ef26d6f）。
    rollback_tag = f"cloud-rollback-{head_before}"
    print(f"\n回滚标签: {rollback_tag}")
    for img in ("lianyu-pc/agent:cloud", "lianyu-pc/backend:cloud"):
        run(c, f"docker tag {img} {img.rsplit(':', 1)[0]}:{rollback_tag} && "
               f"docker images --format '{{{{.Repository}}}}:{{{{.Tag}}}} {{{{.ID}}}}' "
               f"| grep {rollback_tag}", must=False)
    run(c, f"docker images | grep -E 'lianyu-pc/(agent|backend)'")

    c.close()
    print("\nPREP_DONE")


if __name__ == "__main__":
    main()
