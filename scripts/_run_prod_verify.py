#!/usr/bin/env python3
"""上传并运行部署后验证脚本，把报告拉回本地。"""
import sys
from pathlib import Path

import paramiko

ROOT = Path(__file__).resolve().parents[1]
LOCAL = ROOT / "scripts" / "_prod_verify.py"


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
    sftp.put(str(LOCAL), "/tmp/prod_verify.py")
    sftp.close()

    cmd = "python3 -X utf8 /tmp/prod_verify.py 2>&1"
    print(f"$ {cmd}", flush=True)
    _, stdout, _ = c.exec_command(cmd, timeout=1800, get_pty=True)
    out = stdout.read().decode("utf-8", errors="replace")
    sys.stdout.buffer.write(out.encode("utf-8", errors="replace"))
    code = stdout.channel.recv_exit_status()
    print(f"\nVERIFY_EXIT={code}")

    try:
        sftp = c.open_sftp()
        local_report = ROOT / ".tmp" / "prod-verify-report.json"
        local_report.parent.mkdir(exist_ok=True)
        sftp.get("/tmp/prod_verify_report.json", str(local_report))
        sftp.close()
        print(f"report -> {local_report}")
    except Exception as e:
        print(f"report pull failed: {e}")

    c.close()
    sys.exit(code)


if __name__ == "__main__":
    main()
