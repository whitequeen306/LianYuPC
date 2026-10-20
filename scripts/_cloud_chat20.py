#!/usr/bin/env python3
"""上传并在生产服务器上运行 20 条真实对话脚本。"""
import os
import sys
from pathlib import Path

import paramiko

ROOT = Path(__file__).resolve().parents[1]
LOCAL_SCRIPT = ROOT / ".tmp" / "prod_chat20.py"


def load_env() -> dict:
    env = {}
    for line in (ROOT / ".env").read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            env[k.strip()] = v.strip().strip('"')
    return env


def main() -> None:
    env = load_env()
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect("156.233.228.18", username="root",
              password=env["DEPLOY_SSH_PASSWORD"], timeout=30)

    sftp = c.open_sftp()
    sftp.put(str(LOCAL_SCRIPT), "/tmp/prod_chat20.py")
    sftp.close()

    # 服务器有 python3（标准库够用）
    cmd = "python3 -X utf8 /tmp/prod_chat20.py 2>&1"
    print(f"$ {cmd}", flush=True)
    _, stdout, stderr = c.exec_command(cmd, timeout=1200, get_pty=True)
    out = stdout.read().decode("utf-8", errors="replace")
    sys.stdout.buffer.write(out.encode("utf-8", errors="replace"))
    sys.stdout.buffer.write(b"\n")
    code = stdout.channel.recv_exit_status()
    print(f"EXIT={code}")

    # 把报告拉回本地
    try:
        sftp = c.open_sftp()
        sftp.get("/tmp/prod_chat20_report.json", str(ROOT / ".tmp" / "prod-chat20-report.json"))
        sftp.close()
        print("report pulled to .tmp/prod-chat20-report.json")
    except Exception as e:
        print(f"report pull failed: {e}")

    c.close()


if __name__ == "__main__":
    main()
